package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
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
 * - [searchBridge] 已注入（Swift `MediaSearchEngine`）→ search/refine 两命令
 *   对齐 Android `ChatSearchCapability` + `ChatViewModel` Delegate 语义：
 *   结果只保留 PHOTO；refine 在上一轮结果集内过滤（结构化 intent → 引擎 in-set；
 *   字符串 → [IosChatGallerySearch.resolveRefine]）；refined 为空保留上一轮不变；
 *   无搜索基数时 refine 返回明确错误引导改用 search_media（对齐 Android 回合护栏 c，
 *   spec §3.5-c），绝不静默全局重搜；
 * - [searchBridge] 为 null（引擎未接线，防御路径）→ 保持原有文件名匹配降级
 *   （查不到返回 0 结果，不做「返回最新 N 张」的误导兜底）。
 *
 * 横滑卡退役（2026-11）：search/refine 回执 = [AgentAction.TextReply] 纯计数文案
 * （逐字对齐 Android `SearchOutcome.toTextReply`）；命中 id 清单由 [lastSearchAssets]
 * 搜索基数快照承载，LLM 出图集卡经 render_html + media:// 引用。
 * feedback/more/exclude 三命令随横滑卡交互一并下线。
 *
 * 其余命令与 Android 三件套（ChatGallerySummaryCapability / ChatSearchCapability /
 * ChatMediaWriteCapability）语义对齐，差异均为诚实降级：
 * - delete 经 Photos framework 系统确认窗，无需 Android 11+ IntentSender 授权链路；
 * - view/select/share 为 UI 直通命令：此处只校验参数并返回 [AgentAction.Success]，
 *   实际跳转/选中/分享由 Swift 侧消费 uiActions 完成。
 *
 * [PRIVACY]：TextReply 只含计数与固定文案，不输出路径/GPS/base64。
 */
class IosChatGalleryCapability(
    private val repository: MediaRepository,
    private val bridge: IosMediaRepositoryBridge,
    private val searchBridge: IosChatSearchBridge? = null
) : BaseCapability() {

    override val name: String = "chat_gallery"

    override val description: String = "相册对话操作：盘点、搜索、细化、查看、选择、收藏、删除、分享"

    /** 上一轮搜索命中的资产快照（全量 PHOTO），供 refine 做 in-set 过滤。 */
    private var lastSearchAssets: List<MediaAsset>? = null

    /**
     * 上一轮搜索的媒体类型口径（云端 Agent 专用，2026-11-12）：refine 的类型过滤跟随
     * 产生基数的 search 的 mediaType（对齐 Android CloudAgentGalleryTools.refineMediaType
     * ——视频搜索的细化不把照片混回结果集）。
     */
    private var lastSearchMediaType: String = "photo"

    /** 最近一轮搜索基数快照（云端句柄注册消费；已按 mediaType 过滤）。 */
    fun lastSearchSnapshot(): List<MediaAsset> = lastSearchAssets.orEmpty()

    /** 最近一轮搜索的类型口径（云端 refine 回执文案用）。 */
    fun lastSearchMediaTypeIdiom(): String = lastSearchMediaType

    /**
     * 搜索基数存在性（只读）：组合根注入 ChatAgentBridge 作意图路由器紧凑状态
     * hasSearchBase 的 iOS 数据源（缺失时 refine 收到明确错误引导，见 [handleRefine]）。
     */
    val hasSearchBase: Boolean get() = !lastSearchAssets.isNullOrEmpty()

    /**
     * MediaAsset.id（localIdentifier.hashCode）→ media_assets 主键 的映射。
     * 由 [IosChatSearchBridge] 每批结果累积更新；refine in-set（limitToIds）
     * 用 dbId 口径，展示仍用 hashCode id 口径。
     */
    private val dbIdByAssetId = mutableMapOf<Long, Long>()

    override fun supportedCommands(): List<String> = SUPPORTED_COMMANDS

    override fun getCommandDescription(command: String): String = when (command) {
        // 契约 §9.1 逐字照抄
        "search_media" -> "搜索相册照片，参数: query (自然语言，如'去年夏天'、'海边的')"
        "refine_media_search" -> "在上一轮相册搜索结果中细化筛选，参数: constraint (如'海边的'、'夜景')"
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

    /**
     * 云端 Agent 搜索（2026-11-12 主聊天云端回合接线）：mediaType 维度过滤——
     * "video"=只视频 / "all"=全部 / 其余=只照片（对齐 Android CloudAgentGalleryTools
     * .typeFilterOf 口径，chat 链路 search_media 无此参数不受影响）。
     * 结果记录 refine 基数（已过滤集）与 [lastSearchMediaType]，供 refineForCloud 跟随。
     */
    suspend fun searchMediaForCloud(query: String, intent: SearchIntent?, mediaType: String): List<MediaAsset> {
        val typeFilter = typeFilterOf(mediaType)
        val assets = if (searchBridge != null) {
            awaitEngineSearch(query, intent, limitToDbIds = null).map(::toDomain).filter(typeFilter)
        } else {
            val all = repository.allMedia.first()
            val hits = filterAssets(all, query, intent?.timeRange)
            (if (query.isBlank()) hits.take(MAX_RECENT_RESULTS) else hits).filter(typeFilter)
        }
        lastSearchAssets = assets
        lastSearchMediaType = mediaType
        return assets
    }

    /**
     * 云端 Agent 细化：无基数返回 null（调用方回落明确错误引导）；类型过滤跟随基数
     * mediaType；in-set 空 → 保留上一轮基数不变（返回 null 语义由调用方区分文案）。
     */
    suspend fun refineMediaSearchForCloud(constraint: String, intent: SearchIntent?): List<MediaAsset>? {
        val prior = lastSearchAssets ?: return null
        val refined = if (searchBridge != null) {
            if (intent != null) {
                val priorDbIds = prior.mapNotNull { dbIdByAssetId[it.id] }
                awaitEngineSearch(constraint, intent, limitToDbIds = priorDbIds)
                    .map(::toDomain).filter(typeFilterOf(lastSearchMediaType))
            } else {
                val cleaned = IosChatGallerySearch.cleanConstraint(constraint)
                val priorDbIds = prior.mapNotNull { dbIdByAssetId[it.id] }
                val hits = awaitEngineSearch(cleaned, intent = null, limitToDbIds = priorDbIds.ifEmpty { null })
                IosChatGallerySearch.resolveRefine(
                    prior,
                    hits.map(::toDomain).filter(typeFilterOf(lastSearchMediaType)),
                    cleaned,
                )
            }
        } else {
            filterAssets(prior, constraint, intent?.timeRange).filter(typeFilterOf(lastSearchMediaType))
        }
        // in-set 空 → 保留上一轮结果集不变（与 chat 链路同口径，不覆盖基数）
        return refined.ifEmpty { null }
    }

    /** mediaType 参数 → 类型谓词（"video"=只视频 / "all"=全部 / 其余=只照片，Android 同口径）。 */
    private fun typeFilterOf(mediaType: String): (MediaAsset) -> Boolean =
        when (mediaType.lowercase()) {
            "video" -> { asset -> asset.type == MediaType.VIDEO }
            "all" -> { _ -> true }
            else -> { asset -> asset.type == MediaType.PHOTO }
        }

    private suspend fun handleSearch(command: AgentCommand.SearchMedia): Result<AgentAction> {
        if (searchBridge == null) return handleSearchLegacy(command)
        val items = awaitEngineSearch(command.query, command.intent, limitToDbIds = null)
        // 契约 §9.2：结果只保留 PHOTO
        val assets = items.map(::toDomain).filter { it.type == MediaType.PHOTO }
        recordRound(assets)
        return Result.success(
            AgentAction.TextReply(command.commandId, "找到 ${assets.size} 张「${command.query}」的照片")
        )
    }

    private suspend fun handleRefine(command: AgentCommand.RefineMediaSearch): Result<AgentAction> {
        if (searchBridge == null) return handleRefineLegacy(command)
        // 回合护栏 c（对齐 Android onRefineMediaSearch）：无搜索基数 → 明确错误引导，
        // 不静默全局重搜（那会用与既有约束无关的新结果覆盖会话搜索状态，
        // 且观测文本与真实行为脱节，诱发幻觉链）
        if (!hasSearchBase) {
            return Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.INVALID_PARAMS,
                    "没有上一轮搜索结果可细化：请改用 search_media 重新全局搜索" +
                        "（人物/时间用 person/fromMs/toMs 结构化参数）"
                )
            )
        }
        val refined = executeRefine(command.constraint, command.intent)
        if (refined.isEmpty()) {
            // 契约 §9.2：in-set 空 → 保留上一轮结果集不变（绝不全局重搜覆盖状态）
            return Result.success(
                AgentAction.TextReply(command.commandId, "在上一轮结果内找到 0 张「${command.constraint}」的照片")
            )
        }
        recordRound(refined)
        return Result.success(
            AgentAction.TextReply(command.commandId, "在上一轮结果内找到 ${refined.size} 张「${command.constraint}」的照片")
        )
    }

    /**
     * refine 核心（search 引擎路径，调用方保证搜索基数非空）。
     * 结果为空时不更新状态——由调用方决定保留/覆盖语义。
     */
    private suspend fun executeRefine(constraint: String, intent: SearchIntent?): List<MediaAsset> {
        val prior = lastSearchAssets.orEmpty()
        return if (intent != null) {
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
    }

    /** 记录一轮搜索结果（契约 §9.2：命中存入 lastSearchAssets 供 refine in-set）。 */
    private fun recordRound(assets: List<MediaAsset>) {
        lastSearchAssets = assets
        lastSearchMediaType = "photo"
    }

    /**
     * 调 Swift 搜索引擎（completion 回调转 suspend）。失败/空桥一律回空列表，
     * 异常绝不逃逸出 Kotlin 边界（signal 6 铁律）。
     * 每批结果顺带累积 assetId → dbId 映射（refine in-set 用）。
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
        // id 口径与 IosMediaRepository 的 javaHashCode 映射同一 id 空间（媒体定位用）
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

    // ── 降级路径（searchBridge 未注入：文件名匹配，保持原行为）────────────────────

    private suspend fun handleSearchLegacy(command: AgentCommand.SearchMedia): Result<AgentAction> {
        val all = repository.allMedia.first()
        val hits = filterAssets(all, command.query, command.intent?.timeRange)
        // 空关键词 = 「看看最近的」，repo 已按 creationDate 降序，截前 N 条
        val results = if (command.query.isBlank()) hits.take(MAX_RECENT_RESULTS) else hits
        recordRound(results)
        return Result.success(
            AgentAction.TextReply(command.commandId, "找到 ${results.size} 张「${command.query}」的照片")
        )
    }

    private suspend fun handleRefineLegacy(command: AgentCommand.RefineMediaSearch): Result<AgentAction> {
        val base = lastSearchAssets
        val source = base ?: repository.allMedia.first()
        val hits = filterAssets(source, command.constraint, command.intent?.timeRange)
        if (base != null && hits.isEmpty()) {
            // in-set 空 → 保留上一轮结果集不变（对齐引擎路径语义）
            return Result.success(
                AgentAction.TextReply(command.commandId, "在上一轮结果内找到 0 张「${command.constraint}」的照片")
            )
        }
        recordRound(hits)
        val message = if (base != null) {
            "在上一轮结果内找到 ${hits.size} 张「${command.constraint}」的照片"
        } else {
            "找到 ${hits.size} 张「${command.constraint}」的照片"
        }
        return Result.success(AgentAction.TextReply(command.commandId, message))
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
        dbIdByAssetId.clear()
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

        val SUPPORTED_COMMANDS: List<String> = listOf(
            "get_gallery_summary",
            "search_media",
            "refine_media_search",
            "view_media",
            "select_media",
            "favorite_media",
            "delete_media",
            "share_media"
        )
    }
}
