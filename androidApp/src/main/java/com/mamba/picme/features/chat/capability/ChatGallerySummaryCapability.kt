@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.LocalHandleStore
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.ScanPhotoEntry
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Chat 场景相册摘要 Capability。
 *
 * 职责：在 CHAT 场景暴露 `get_gallery_summary`，把命令回调给 [Delegate]（ChatViewModel）执行。
 *
 * 句柄回执（U2-7，2026-11-26）：摘要回执尾部附 `（数据句柄 handle: scan/<12hex>）` 并把
 * **全库媒体**注册进 [LocalHandleStore]——逐字对齐云端工具桥
 * `CloudAgentGalleryTools.getGallerySummary`（2026-11-13 placeholder 假句柄事故修复：
 * 「我的个人相册」→ 月度分布卡流程有合法 handle，`#histogram`/`#photos`/`#videos`
 * 均可绑定，模型不再臆造 handleId）。全库快照/注册失败静默降级纯文本（不走 error: 协议）。
 * 依赖经 [attachScanHandles] 由组合根注入（未注入 = 纯文本，兼容无容器环境与旧测试）。
 */
class ChatGallerySummaryCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatGallerySummaryCapability? = null

        fun getInstance(): ChatGallerySummaryCapability =
            instance ?: synchronized(this) {
                instance ?: ChatGallerySummaryCapability().also { instance = it }
            }
    }

    private val tag = "ChatGallerySummaryCapability"

    override val name: String = "chat_gallery_summary"
    override val description: String = "在聊天中获取本地相册摘要，包括照片数、人脸数、人物数、已/未打标数量以及扫描建议"

    interface Delegate {
        suspend fun onGetGallerySummary(includeDetails: Boolean): GallerySummary?
    }

    private var delegateRef: WeakReference<Delegate>? = null

    // ── U2-7 句柄回执接缝（组合根注入）──────────────────────────────────────────

    /** 全库媒体句柄注册入口；null = 未接线，回执纯文本。 */
    @Volatile
    private var scanHandleStore: LocalHandleStore? = null

    /** 全库媒体快照（生产 = repository.allMedia）。 */
    @Volatile
    private var allMediaSnapshot: (suspend () -> List<MediaAsset>)? = null

    private val handleJson = Json { encodeDefaults = true }

    /** 组合根接线：注入句柄 store 与全库媒体快照（幂等，重调覆盖）。 */
    fun attachScanHandles(
        localHandleStore: LocalHandleStore,
        allMedia: suspend () -> List<MediaAsset>,
    ) {
        scanHandleStore = localHandleStore
        allMediaSnapshot = allMedia
        Logger.i(tag, "Scan handle support attached")
    }

    /** 清空句柄接缝（测试隔离用）。 */
    internal fun resetScanHandleSupport() {
        scanHandleStore = null
        allMediaSnapshot = null
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

    override fun supportedCommands(): List<String> = listOf("get_gallery_summary")

    override fun getCommandDescription(command: String): String =
        if (command == "get_gallery_summary") {
            "获取本地相册摘要，参数: include_details (boolean, 默认 false)"
        } else {
            "未知命令"
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
                    message = "相册摘要暂不可用（聊天页未激活）"
                )
            )
        return try {
            if (command is AgentCommand.GetGallerySummary) {
                val summary = d.onGetGallerySummary(command.includeDetails)
                val text = summary?.let { formatGallerySummaryForReply(it) }
                    ?: GALLERY_SUMMARY_UNAVAILABLE_HINT
                val message = appendScanHandleSuffix(text)
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = message
                    )
                )
            } else {
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatGallerySummaryCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Get gallery summary failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "获取相册摘要失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }

    // ── U2-7 全库句柄（镜像 CloudAgentGalleryTools.getGallerySummary）─────────

    /**
     * 摘要文本 + 全库数据句柄尾缀；快照为空/查询失败/注册失败均静默降级纯文本
     * （只记日志，不走 error: 协议——摘要本身已成功）。尾缀字面量与云端工具桥
     * 逐字一致：`（数据句柄 handle: scan/<12hex>）`。
     */
    private suspend fun appendScanHandleSuffix(text: String): String {
        val store = scanHandleStore ?: return text
        val allMedia = allMediaSnapshot ?: return text
        val media = try {
            allMedia()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "gallery summary all-media snapshot failed, degrading to plain text", e)
            return text
        }
        if (media.isEmpty()) return text
        return try {
            val entries = media.map { asset ->
                ScanPhotoEntry(id = asset.id, captureDate = asset.captureDate, mediaType = asset.type.name)
            }
            val handleId = store.put(
                OpenUiContract.DOMAIN_SCAN,
                handleJson.encodeToString(entries),
            )
            val token = OpenUiContract.formatHandleToken(OpenUiContract.DOMAIN_SCAN, handleId)
            "$text（数据句柄 $token）"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "scan handle registration failed, degrading to plain text", e)
            text
        }
    }
}

/** 相册数据不可用时的引导文案（chat 链路与云端 Agent 工具桥共用同一口径）。 */
internal const val GALLERY_SUMMARY_UNAVAILABLE_HINT =
    "我还没拿到你的相册数据，可能是首次使用或尚未完成同步。我可以帮你启动 TAG 扫描，让人脸、场景和物体标签都生成出来。要开始吗？"

/**
 * 相册摘要 → 模型观测/回复文本（自 [ChatGallerySummaryCapability] 提升为顶层，
 * 供云端 Agent 工具桥 CloudAgentGalleryTools 同源复用）。
 */
internal fun formatGallerySummaryForReply(summary: GallerySummary): String {
    return buildString {
        append("当前相册共有 ${summary.totalMedia} 个媒体")
        if (summary.totalPhotos > 0 || summary.totalVideos > 0) {
            append("（${summary.totalPhotos} 张照片")
            if (summary.totalVideos > 0) append("，${summary.totalVideos} 个视频")
            append("）")
        }
        append("；检测到 ${summary.hasFaceCount} 张含人脸的照片，聚类出 ${summary.personClusterCount} 个人物")
        if (summary.namedPersonCount > 0) {
            append("（其中 ${summary.namedPersonCount} 个已命名）")
        }
        append("。已打标 ${summary.labeledCount} 张，未打标 ${summary.unlabeledCount} 张")
        when (summary.recommendation) {
            GallerySummary.ScanRecommendation.NONE -> append("。目前状态良好，无需扫描。")
            GallerySummary.ScanRecommendation.INCREMENTAL -> append("。建议运行增量扫描补齐未打标照片。")
            GallerySummary.ScanRecommendation.PASS3_FULL -> append("。未打标比例较高，建议执行内容标签全量扫描。")
            GallerySummary.ScanRecommendation.PASS1_FIRST -> append("。大量照片尚未完成人脸检测，建议先执行人脸检测扫描。")
        }
        if (summary.isScanning) {
            append(" [当前扫描中")
            summary.currentPass?.let { append(" · $it") }
            summary.scanProgressText?.let { append(" · $it") }
            append("]")
        }
    }
}

