package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.IosChatSearchBridge
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.data.IosSearchResultItem
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS Chat 相册能力 —— chat 场景的相册工具执行端。
 *
 * 搜索链路（契约 `tmp/ios-follow/gallery-search/contracts.md` §9）：
 * - [searchBridge] 已注入（Swift `MediaSearchEngine`）→ search/refine/feedback/more/exclude
 *   五命令全量对齐 Android `ChatSearchCapability` + `ChatViewModel` Delegate 语义：
 *   结果只保留 PHOTO；refine 在上一轮结果集内过滤（结构化 intent → 引擎 in-set；
 *   字符串 → [IosChatGallerySearch.resolveRefine]）；refined 为空保留上一轮不变；
 * - [searchBridge] 为 null（引擎未接线，防御路径）→ 保持原有文件名匹配降级
 *   （查不到返回 0 结果，不做「返回最新 N 张」的误导兜底）。
 *
 * 其余命令与 Android 三件套（ChatGallerySummaryCapability / ChatSearchCapability /
 * ChatMediaWriteCapability）语义对齐，差异均为诚实降级：
 * - delete 经 Photos framework 系统确认窗，无需 Android 11+ IntentSender 授权链路；
 * - view/select/share 为 UI 直通命令：此处只校验参数并返回 [AgentAction.Success]，
 *   实际跳转/选中/分享由 Swift 侧消费 uiActions 完成。
 *
 * [PRIVACY]：TextReply 只含计数与固定文案，不输出路径/GPS/base64；
 * MediaResults 只带媒体 id（与 Android 同口径）。
 */
class IosChatGalleryCapability(
    private val repository: MediaRepository,
    private val bridge: IosMediaRepositoryBridge,
    private val searchBridge: IosChatSearchBridge? = null
) : BaseCapability() {

    override val name: String = "chat_gallery"

    override val description: String = "相册对话操作：盘点、搜索、细化、查看、选择、收藏、删除、分享"

    /** 上一轮搜索命中的资产快照（全量 PHOTO），供 refine/more/exclude 做 in-set 过滤。 */
    private var lastSearchAssets: List<MediaAsset>? = null

    /**
     * 搜索基数存在性（只读）：组合根注入 ChatAgentBridge 作意图路由器紧凑状态
     * hasSearchBase 的 iOS 数据源（缺失时 refine 直执退化为全局重搜）。
     */
    val hasSearchBase: Boolean get() = !lastSearchAssets.isNullOrEmpty()

    /** 最近一轮搜索 query（feedback 落库 query_text 口径，契约 §8/R10 精确等值）。 */
    private var lastRoundQuery: String? = null

    /**
     * MediaAsset.id（localIdentifier.hashCode）→ media_assets 主键 的映射。
     * 由 [IosChatSearchBridge] 每批结果累积更新；refine in-set（limitToIds）与
     * feedback 落库（media_id）都用 dbId 口径，展示仍用 hashCode id 口径。
     */
    private val dbIdByAssetId = mutableMapOf<Long, Long>()

    /** exclude 命令的内存排除词集（契约 §9.6：仅内存，不写库；iOS 单会话版无需按 session 分桶）。 */
    private val sessionExcludes = mutableSetOf<String>()

    override fun supportedCommands(): List<String> = SUPPORTED_COMMANDS

    override fun getCommandDescription(command: String): String = when (command) {
        // 契约 §9.1 逐字照抄
        "search_media" -> "搜索相册照片，参数: query (自然语言，如'去年夏天'、'海边的')"
        "refine_media_search" -> "在上一轮相册搜索结果中细化筛选，参数: constraint (如'海边的'、'夜景')"
        "feedback" -> "记录用户对某张图片的反馈，参数: target (last/ordinal:N/desc:xxx/mediaId:xxx), action (like/dislike)"
        "more" -> "基于指定图片推荐更多相似照片，参数: target (last/ordinal:N/desc:xxx/mediaId:xxx)"
        "exclude" -> "在后续搜索中排除某类约束，参数: constraint (如'夜景'、'室内')"
        else -> super.getCommandDescription(command)
    }

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return try {
            when (command) {
                is AgentCommand.GetGallerySummary -> handleSummary(command)
                is AgentCommand.SearchMedia -> handleSearch(command)
                is AgentCommand.RefineMediaSearch -> handleRefine(command)
                is AgentCommand.RecordMediaFeedback -> handleFeedback(command, context)
                is AgentCommand.MoreLikeThis -> handleMoreLikeThis(command)
                is AgentCommand.ExcludeConstraint -> handleExclude(command)
                is AgentCommand.ViewMedia -> handleView(command)
                is AgentCommand.SelectMedia -> Result.success(AgentAction.Success(command.commandId, command))
                is AgentCommand.FavoriteMedia -> handleFavorite(command)
                is AgentCommand.DeleteMedia -> handleDelete(command)
                is AgentCommand.ShareMedia -> handleShare(command)
                else -> Result.success(
                    AgentAction.Error(
                        command.commandId,
                        AgentErrorCode.METHOD_NOT_FOUND,
                        "不支持的命令：${AgentCommand.getMethodName(command)}"
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INTERNAL_ERROR, "操作失败：${e.message}")
            )
        }
    }

    // ── 盘点 ──────────────────────────────────────────────────────────────

    private suspend fun handleSummary(command: AgentCommand.GetGallerySummary): Result<AgentAction> {
        val all = repository.allMedia.first()
        val photos = all.count { it.type == MediaType.PHOTO }
        val videos = all.count { it.type == MediaType.VIDEO }
        val message = "当前相册共有 ${all.size} 个媒体（$photos 张照片，$videos 个视频）。" +
            "iOS 端暂未开启标签/人脸索引，暂无人物与场景统计。"
        return Result.success(AgentAction.TextReply(command.commandId, message))
    }

    // ── 搜索 / 细化 ─────────────────────────────────────────────────────────

    private suspend fun handleSearch(command: AgentCommand.SearchMedia): Result<AgentAction> {
        if (searchBridge == null) return handleSearchLegacy(command)
        val items = awaitEngineSearch(command.query, command.intent, limitToDbIds = null)
        // 契约 §9.2：结果只保留 PHOTO
        val assets = items.map(::toDomain).filter { it.type == MediaType.PHOTO }
        recordRound(query = command.query, assets = assets)
        return Result.success(toMediaResults(command.commandId, command.query, assets, isRefinement = false))
    }

    private suspend fun handleRefine(command: AgentCommand.RefineMediaSearch): Result<AgentAction> {
        if (searchBridge == null) return handleRefineLegacy(command)
        val (refined, hadPrior) = executeRefine(command.constraint, command.intent)
        if (!hadPrior) {
            // 契约 §9.2：无上一轮结果 → 当 fresh 全局搜
            recordRound(query = command.constraint, assets = refined)
            return Result.success(toMediaResults(command.commandId, command.constraint, refined, isRefinement = false))
        }
        if (refined.isEmpty()) {
            // 契约 §9.2：in-set 空 → 保留上一轮结果集不变，返回空细化结果（绝不全局重搜覆盖状态）
            return Result.success(toMediaResults(command.commandId, command.constraint, emptyList(), isRefinement = true))
        }
        recordRound(query = command.constraint, assets = refined)
        return Result.success(toMediaResults(command.commandId, command.constraint, refined, isRefinement = true))
    }

    /**
     * refine 核心（search 引擎路径）：返回（细化结果，是否有上一轮结果集）。
     * 结果为空时不更新状态——由调用方决定保留/覆盖语义。
     */
    private suspend fun executeRefine(constraint: String, intent: SearchIntent?): Pair<List<MediaAsset>, Boolean> {
        val prior = lastSearchAssets.orEmpty()
        if (prior.isEmpty()) {
            val items = awaitEngineSearch(constraint, intent, limitToDbIds = null)
            return items.map(::toDomain).filter { it.type == MediaType.PHOTO } to false
        }
        val refined = if (intent != null) {
            // LLM 已给出标准化意图：结构化精确交集（契约 §9.2，引擎 in-set）
            val priorDbIds = prior.mapNotNull { dbIdByAssetId[it.id] }
            awaitEngineSearch(constraint, intent, limitToDbIds = priorDbIds)
                .map(::toDomain).filter { it.type == MediaType.PHOTO }
        } else {
            // 字符串兜底：清洗后引擎 in-set 搜索（等价 Android search(cleaned, limitToIds = priorIds)），
            // 再 resolveRefine（filterInSet 优先，空则 hits ∩ prior）
            val cleaned = IosChatGallerySearch.cleanConstraint(constraint)
            val priorDbIds = prior.mapNotNull { dbIdByAssetId[it.id] }
            val hits = awaitEngineSearch(cleaned, intent = null, limitToDbIds = priorDbIds.ifEmpty { null })
            IosChatGallerySearch.resolveRefine(
                prior,
                hits.map(::toDomain).filter { it.type == MediaType.PHOTO },
                cleaned
            )
        }
        return refined to true
    }

    /** 记录一轮搜索结果（契约 §9.2：命中存入 lastResultAssets 供 refine；快照 query 供 feedback）。 */
    private fun recordRound(query: String, assets: List<MediaAsset>) {
        lastSearchAssets = assets
        lastRoundQuery = query
    }

    /**
     * 调 Swift 搜索引擎（completion 回调转 suspend）。失败/空桥一律回空列表，
     * 异常绝不逃逸出 Kotlin 边界（signal 6 铁律）。
     * 每批结果顺带累积 assetId → dbId 映射（refine in-set / feedback 用）。
     */
    private suspend fun awaitEngineSearch(
        query: String,
        intent: SearchIntent?,
        limitToDbIds: List<Long>?
    ): List<IosSearchResultItem> {
        val engine = searchBridge ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            try {
                engine.search(query, intent, limitToDbIds) { items ->
                    items.forEach { dbIdByAssetId[it.localIdentifier.hashCode().toLong()] = it.dbId }
                    if (cont.isActive) cont.resume(items)
                }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    private fun toDomain(item: IosSearchResultItem): MediaAsset = MediaAsset(
        // id 口径与 IosMediaRepository / Chat MediaCardRow 的 javaHashCode 映射一致（展示定位用）
        id = item.localIdentifier.hashCode().toLong(),
        uri = item.localIdentifier,
        type = if (item.mediaType == "VIDEO") MediaType.VIDEO else MediaType.PHOTO,
        captureDate = item.captureDateMs,
        fileName = item.fileName,
        duration = item.durationMs,
        hasFace = item.hasFace,
        labels = item.labels,
        ocrText = item.ocrText,
        locationName = item.locationName,
        city = item.city
    )

    /**
     * 构建 MediaResults。iOS 展示侧（Chat MediaCardRow）按 mediaIds 全量渲染、无截断，
     * 故在此截前 [MAX_CARDS] 项对齐 Android 卡片上限（契约 §9.6）；totalCount 仍为全量命中数。
     */
    private fun toMediaResults(
        commandId: Int,
        query: String,
        assets: List<MediaAsset>,
        isRefinement: Boolean
    ): AgentAction.MediaResults = AgentAction.MediaResults(
        commandId = commandId,
        query = query,
        mediaIds = assets.take(MAX_CARDS).map { it.id },
        totalCount = assets.size,
        isRefinement = isRefinement
    )

    // ── feedback / more / exclude（契约 §9.6）────────────────────────────────

    /** FeedbackTarget → 上一轮结果项（契约 §9.6 resolveTarget；会话内无结果 → null）。 */
    private fun resolveTarget(target: FeedbackTarget): MediaAsset? {
        val assets = lastSearchAssets.orEmpty()
        if (assets.isEmpty()) return null
        return when (target) {
            is FeedbackTarget.LastShown -> assets.firstOrNull()
            is FeedbackTarget.Ordinal -> assets.getOrNull((target.index - 1).coerceAtLeast(0))
            is FeedbackTarget.MediaId -> assets.find { it.id.toString() == target.id }
            is FeedbackTarget.Description -> assets.find {
                IosChatGallerySearch.matchesDescription(it, target.text)
            }
        }
    }

    private fun resolveFailure(command: AgentCommand): Result<AgentAction> = Result.success(
        // 契约 §9.1：resolve 失败 → Error(INVALID_PARAMS, "feedback_resolve_failure")
        AgentAction.Error(command.commandId, AgentErrorCode.INVALID_PARAMS, "feedback_resolve_failure")
    )

    private fun handleFeedback(
        command: AgentCommand.RecordMediaFeedback,
        context: AgentContext
    ): Result<AgentAction> {
        val engine = searchBridge ?: return resolveFailure(command)
        val asset = resolveTarget(command.target) ?: return resolveFailure(command)
        val dbId = dbIdByAssetId[asset.id] ?: return resolveFailure(command)
        // 契约 §8：media_id = media_assets 主键字符串；query = 最近一轮快照 query（精确等值）；
        // feedback_type = FeedbackAction.name.lowercase()（对齐 MediaFeedbackRepositoryImpl）
        engine.recordFeedback(
            mediaId = dbId.toString(),
            feedbackType = command.action.name.lowercase(),
            query = lastRoundQuery.orEmpty(),
            sessionId = context.memorySessionId
        )
        return Result.success(AgentAction.Success(command.commandId, command))
    }

    private suspend fun handleMoreLikeThis(command: AgentCommand.MoreLikeThis): Result<AgentAction> {
        val asset = resolveTarget(command.target)
            ?: return Result.success(toMediaResults(command.commandId, "", emptyList(), isRefinement = false))
        val tags = asset.labels?.let { IosChatGallerySearch.parseLabelTags(it) }?.take(MAX_MORE_TAGS).orEmpty()
        val constraint = if (tags.isNotEmpty()) {
            "和这张照片类似的：${tags.joinToString("、")}"
        } else {
            "更多类似这张照片的"
        }
        val (refined, hadPrior) = executeRefine(constraint, intent = null)
        if (refined.isNotEmpty()) {
            recordRound(query = constraint, assets = refined)
        }
        // 契约 §9.6 + Android 特化：MoreLikeThis 结果 isRefinement 强制 false
        return Result.success(toMediaResults(command.commandId, constraint, refined, isRefinement = false))
    }

    private fun handleExclude(command: AgentCommand.ExcludeConstraint): Result<AgentAction> {
        // 契约 §9.6：constraint 非空且当前有结果才生效；recordExclude 为空实现（不写库）
        if (command.constraint.isBlank()) return resolveFailure(command)
        val current = lastSearchAssets
        if (current.isNullOrEmpty()) return resolveFailure(command)
        sessionExcludes.add(command.constraint)
        lastSearchAssets = current.filter { asset ->
            val labels = asset.labels?.let { IosChatGallerySearch.parseLabelTags(it) } ?: emptyList()
            val text = (labels + asset.fileName).joinToString(" ")
            sessionExcludes.none { constraint -> text.contains(constraint, ignoreCase = true) }
        }
        return Result.success(AgentAction.Success(command.commandId, command))
    }

    // ── 降级路径（searchBridge 未注入：文件名匹配，保持原行为）────────────────────

    private suspend fun handleSearchLegacy(command: AgentCommand.SearchMedia): Result<AgentAction> {
        val all = repository.allMedia.first()
        val hits = filterAssets(all, command.query, command.intent?.timeRange)
        // 空关键词 = 「看看最近的」，repo 已按 creationDate 降序，截前 N 条
        val results = if (command.query.isBlank()) hits.take(MAX_RECENT_RESULTS) else hits
        recordRound(query = command.query, assets = results)
        return Result.success(
            AgentAction.MediaResults(
                commandId = command.commandId,
                query = command.query,
                mediaIds = results.map { it.id },
                totalCount = results.size,
                isRefinement = false
            )
        )
    }

    private suspend fun handleRefineLegacy(command: AgentCommand.RefineMediaSearch): Result<AgentAction> {
        val base = lastSearchAssets
        val isRefinement = base != null
        val source = base ?: repository.allMedia.first()
        val hits = filterAssets(source, command.constraint, command.intent?.timeRange)
        recordRound(query = command.constraint, assets = hits)
        return Result.success(
            AgentAction.MediaResults(
                commandId = command.commandId,
                query = command.constraint,
                mediaIds = hits.map { it.id },
                totalCount = hits.size,
                isRefinement = isRefinement
            )
        )
    }

    /**
     * 文件名关键词 + 时间范围过滤（降级路径专用）。
     * 关键词大小写不敏感匹配 [MediaAsset.fileName]；时间范围按 captureDate（毫秒）闭区间。
     */
    private fun filterAssets(base: List<MediaAsset>, keyword: String, timeRange: TimeRange?): List<MediaAsset> {
        var result = base
        if (timeRange != null) {
            result = result.filter { it.captureDate in timeRange.startMs..timeRange.endMs }
        }
        if (keyword.isNotBlank()) {
            result = result.filter { it.fileName.contains(keyword, ignoreCase = true) }
        }
        return result
    }

    // ── UI 直通命令（Swift 消费 uiActions）────────────────────────────────────

    private fun handleView(command: AgentCommand.ViewMedia): Result<AgentAction> {
        if (command.mediaId.isNullOrBlank()) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要查看的媒体")
            )
        }
        return Result.success(AgentAction.Success(command.commandId, command))
    }

    private fun handleShare(command: AgentCommand.ShareMedia): Result<AgentAction> {
        if (command.mediaIds.isEmpty()) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要分享的媒体")
            )
        }
        return Result.success(AgentAction.Success(command.commandId, command))
    }

    // ── 写命令（经 Photos 桥）─────────────────────────────────────────────────

    private suspend fun handleFavorite(command: AgentCommand.FavoriteMedia): Result<AgentAction> {
        val id = command.mediaId.toLongOrNull()
        val asset = id?.let { repository.getMediaById(it) }
            ?: return Result.success(AgentAction.TextReply(command.commandId, "未找到指定媒体（可能已被删除）"))
        val ok = bridge.setFavorite(asset.uri, command.favorite)
        val message = when {
            !ok -> "收藏操作失败"
            command.favorite -> "已收藏"
            else -> "已取消收藏"
        }
        return Result.success(AgentAction.TextReply(command.commandId, message))
    }

    private suspend fun handleDelete(command: AgentCommand.DeleteMedia): Result<AgentAction> {
        val ids = command.mediaIds.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要删除的媒体")
            )
        }
        val idSet = ids.toSet()
        val targets = repository.allMedia.first().filter { it.id in idSet }
        if (targets.isEmpty()) {
            return Result.success(AgentAction.TextReply(command.commandId, "没有找到要删除的媒体（可能已被删除）"))
        }
        val ok = bridge.deleteMedia(targets.map { it.uri })
        // 删除后媒体 id 失效，上一轮搜索快照一并作废
        lastSearchAssets = null
        lastRoundQuery = null
        dbIdByAssetId.clear()
        sessionExcludes.clear()
        val message = if (ok) {
            "已请求删除 ${targets.size} 个媒体，系统会弹确认窗，确认后才会真正删除"
        } else {
            "删除请求提交失败"
        }
        return Result.success(AgentAction.TextReply(command.commandId, message))
    }

    companion object {
        /** 空关键词搜索（「看看最近的」，降级路径）的返回上限。 */
        private const val MAX_RECENT_RESULTS = 50

        /** 卡片展示上限（契约 §9.6：只取前 20 项；totalCount 仍为全量）。 */
        private const val MAX_CARDS = 20

        /** more 命令取标签数（契约 §9.6：labels.tags 前 3 个）。 */
        private const val MAX_MORE_TAGS = 3

        val SUPPORTED_COMMANDS: List<String> = listOf(
            "get_gallery_summary",
            "search_media",
            "refine_media_search",
            "feedback",
            "more",
            "exclude",
            "view_media",
            "select_media",
            "favorite_media",
            "delete_media",
            "share_media"
        )
    }
}
