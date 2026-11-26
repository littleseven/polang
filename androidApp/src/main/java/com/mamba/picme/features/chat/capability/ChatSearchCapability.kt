package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.LocalHandleStore
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.ScanPhotoEntry
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Chat 场景相册搜索 Capability。
 *
 * 职责：在 CHAT 场景把 search_media / refine_media_search 暴露为可用工具，
 * 并把命令回调给 [Delegate]（由 ChatViewModel 实现）执行。
 * 镜像 GalleryCapability 的 WeakReference Delegate 套路。
 *
 * 横滑卡退役（2026-11-08）：搜索/细化结果不再产出卡片 action，统一回 [AgentAction.TextReply]
 * 计数 + 前 24 个媒体 id 文本（2026-11-14 id 下发，兑现工具描述契约）；展示由 LLM 经
 * render_html 图集卡完成。反馈三件套（record_feedback / more_like_this /
 * exclude_constraint）同步删除。
 *
 * 句柄回执（U2-7，2026-11-26）：search/refine 回执尾部附
 * `（数据句柄 handle: scan/<12hex>）` 并把命中集注册进 [LocalHandleStore]，
 * 供 OpenUI 程序 Query("local_handle") 绑定照片/视频——逐字对齐云端工具桥
 * `CloudAgentGalleryTools`（appendHandleSuffix / registerScanHandle / refineMediaType
 * 先例）；注册失败静默降级纯前缀（不走 error: 协议）。mediaType 类型过滤由本层
 * 承载（Delegate 的搜索基数口径不受影响）：search 按 [AgentCommand.SearchMedia.mediaType]
 * 过滤命中集条目，refine 跟随产生基数的 search 的 mediaType（单槽位，mutex 保护）。
 * 依赖经 [attachScanHandles] 由组合根注入（未注入 = 纯前缀，兼容无容器环境与旧测试）。
 */
class ChatSearchCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatSearchCapability? = null
        fun getInstance(): ChatSearchCapability =
            instance ?: synchronized(this) {
                instance ?: ChatSearchCapability().also { instance = it }
            }
    }

    private val tag = "ChatSearchCapability"

    override val name: String = "chat_gallery_search"
    override val description: String = "在聊天中搜索相册照片；支持多轮细化筛选"

    /**
     * 执行委托：由 ChatViewModel 实现并绑定。返回 [SearchOutcome]（全量命中 id）。
     */
    interface Delegate {
        suspend fun onSearchMedia(query: String, intent: SearchIntent? = null): SearchOutcome
        suspend fun onRefineMediaSearch(constraint: String, intent: SearchIntent? = null): SearchOutcome
    }

    private var delegateRef: WeakReference<Delegate>? = null

    // ── U2-7 句柄回执接缝（组合根注入）──────────────────────────────────────────

    /** 命中集注册入口；null = 未接线，回执纯前缀。 */
    @Volatile
    private var scanHandleStore: LocalHandleStore? = null

    /** id → MediaAsset 解析（生产 = repository.allMedia 快照；条目需 captureDate/mediaType）。 */
    @Volatile
    private var scanMediaLookup: (suspend (List<Long>) -> List<MediaAsset>)? = null

    private val handleJson = Json { encodeDefaults = true }

    /** refine 的类型过滤跟随产生基数的 search（CloudAgentGalleryTools.refineMediaType 先例）。 */
    private val mediaTypeMutex = Mutex()
    private var refineMediaType = "photo"

    /** 组合根接线：注入句柄 store 与 id→MediaAsset 解析（幂等，重调覆盖）。 */
    fun attachScanHandles(
        localHandleStore: LocalHandleStore,
        lookupMedia: suspend (List<Long>) -> List<MediaAsset>,
    ) {
        scanHandleStore = localHandleStore
        scanMediaLookup = lookupMedia
        Logger.i(tag, "Scan handle support attached")
    }

    /** 清空句柄接缝与 mediaType 槽位（测试隔离用；槽位直写——execute 路径由 mutex 保护，
     *  测试无并发 execute）。 */
    internal fun resetScanHandleSupport() {
        scanHandleStore = null
        scanMediaLookup = null
        refineMediaType = "photo"
    }

    fun bindDelegate(delegate: Delegate) {
        delegateRef = WeakReference(delegate)
        Logger.i(tag, "Delegate bound")
    }

    fun unbindDelegate() {
        delegateRef = null
        Logger.i(tag, "Delegate unbound")
    }

    override fun isAvailable(): Boolean = delegateRef?.get() != null

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(
        "search_media",
        "refine_media_search"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "search_media" -> "搜索相册照片，参数: query (自然语言，如'去年夏天'、'海边的')"
        "refine_media_search" -> "在上一轮相册搜索结果中细化筛选，参数: constraint (如'海边的'、'夜景')"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val d = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "相册搜索暂不可用（聊天页未激活）"
                )
            )
        return try {
            when (command) {
                is AgentCommand.SearchMedia -> {
                    // refine 的类型过滤跟随产生基数的 search（CloudAgentGalleryTools.refineMediaType
                    // 先例，单槽位 mutex 保护——chat 命令执行由引擎回合串行）
                    mediaTypeMutex.withLock { refineMediaType = command.mediaType.ifBlank { "photo" } }
                    d.onSearchMedia(command.query, command.intent)
                        .toTextReplyWithScanHandle(command.commandId, command.mediaType)
                }
                is AgentCommand.RefineMediaSearch -> {
                    val baseMediaType = mediaTypeMutex.withLock { refineMediaType }
                    d.onRefineMediaSearch(command.constraint, command.intent)
                        .toTextReplyWithScanHandle(command.commandId, baseMediaType)
                }
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatSearchCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Search failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "搜索失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }

    // ── U2-7 句柄回执实现（镜像 CloudAgentGalleryTools）───────────────────────

    /**
     * [SearchOutcome] → 回执（U2-7）：先按现行口径组装计数 + id 文本，命中非空且
     * 句柄接缝就绪时注册命中集并附尾缀。错误 / 空命中 / 类型过滤后空集均不注册
     * （与云端空分支同口径：空集句柄是无意义锚点 + 垃圾行）。注册失败（Room 写
     * 失败 / id 解析失败）静默降级纯前缀——只记日志、不抛异常、不走 `error:`
     * 协议（避免成功搜索被当成工具失败）。尾缀字面量与云端工具桥逐字一致：
     * `（数据句柄 handle: scan/<12hex>）`。
     */
    private suspend fun SearchOutcome.toTextReplyWithScanHandle(
        commandId: Int,
        mediaType: String,
    ): Result<AgentAction> {
        val base = toTextReply(commandId)
        if (errorMessage != null || mediaIds.isEmpty()) return base
        val store = scanHandleStore ?: return base
        val lookup = scanMediaLookup ?: return base
        val reply = base.getOrNull() as? AgentAction.TextReply ?: return base
        return try {
            // id 序 = 搜索相关度序（lookup 按 ids 序投影），typeFilter 过滤保序
            val entries = lookup(mediaIds).filter(typeFilterOf(mediaType)).map { asset ->
                ScanPhotoEntry(id = asset.id, captureDate = asset.captureDate, mediaType = asset.type.name)
            }
            if (entries.isEmpty()) {
                base
            } else {
                val handleId = store.put(
                    OpenUiContract.DOMAIN_SCAN,
                    handleJson.encodeToString(entries),
                )
                val token = OpenUiContract.formatHandleToken(OpenUiContract.DOMAIN_SCAN, handleId)
                Result.success(reply.copy(message = "${reply.message}（数据句柄 $token）"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "scan handle registration failed, degrading to plain text", e)
            base
        }
    }
}

/** mediaType 参数 → 类型谓词（"video"=只视频 / "all"=全部 / 其余=只照片，
 *  未知值保守回落 photo 口径与旧行为一致——CloudAgentGalleryTools.typeFilterOf 同款）。 */
private fun typeFilterOf(mediaType: String): (MediaAsset) -> Boolean =
    when (mediaType.lowercase()) {
        "video" -> { asset -> asset.type == MediaType.VIDEO }
        "all" -> { _ -> true }
        else -> { asset -> asset.type == MediaType.PHOTO }
    }

/**
 * Delegate 执行搜索后的结果。mediaIds 为全量命中 id（供 ChatViewModel 持有做细化）。
 *
 * [errorMessage] 非空表示前置条件失败（如 refine 无搜索基数，spec §3.5-c），
 * 映射为 [AgentAction.Error] 让 LLM 拿到明确观测文本自行改道，而非空结果假成功。
 */
data class SearchOutcome(
    val query: String,
    val mediaIds: List<Long>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val errorMessage: String? = null
) {
    /**
     * 横滑卡退役后的产出形态：计数 + 前 [MAX_OBSERVATION_IDS] 个媒体 id
     * （**仅 LLM 观测，不直达用户气泡**——Android uiActions 收集器与 iOS text_reply
     * 均忽略 TextReply；中文硬编码文案若直达用户会触 [I18N] 红线，复用者注意）。
     * 全量命中 id 清单由 ChatViewModel 搜索基数（lastResultAssets / 快照）承载；
     * LLM 出图集卡经 render_html + media://{id} 引用——2026-11-14 起 id 随回执
     * 下发兑现工具描述契约（此前只回计数，模型拿不到 id 只能出占位框架）。
     * id 为 opaque 数字（无路径/元数据），与 run_gallery_script gallery.query 的
     * {ids,total} 同一暴露面，[PRIVACY] 不新增暴露类。
     * U2-7（2026-11-26）起句柄尾缀由 ChatSearchCapability 挂接（toTextReplyWithScanHandle，
     * store 注入时），本方法保持纯前缀口径供降级与旧测试。
     */
    fun toTextReply(commandId: Int): Result<AgentAction> {
        if (errorMessage != null) {
            return Result.success(
                AgentAction.Error(
                    commandId = commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = errorMessage
                )
            )
        }
        val message = buildCountWithIds(
            if (isRefinement) "在上一轮结果内找到 $totalCount 张「$query」的照片"
            else "找到 $totalCount 张「$query」的照片",
            mediaIds
        )
        return Result.success(AgentAction.TextReply(commandId = commandId, message = message))
    }

    companion object {
        /** 回执附带的媒体 id 上限（图集卡一屏所需；更多 id 经 gallery.query 取）。 */
        const val MAX_OBSERVATION_IDS = 24

        /** 计数前缀 + 有界 id 尾缀（双端 chat 链路同一字面量，iOS 逐字对齐）。 */
        fun buildCountWithIds(prefix: String, mediaIds: List<Long>): String {
            if (mediaIds.isEmpty()) return prefix
            val shown = mediaIds.take(MAX_OBSERVATION_IDS)
            val suffix = shown.joinToString(",")
            return if (mediaIds.size <= MAX_OBSERVATION_IDS) {
                "$prefix，媒体 id：$suffix"
            } else {
                "$prefix，前 $MAX_OBSERVATION_IDS 个媒体 id：$suffix" +
                    "（共 ${mediaIds.size} 个，更多 id 用 run_gallery_script 的 gallery.query 获取）"
            }
        }
    }
}
