@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import java.lang.ref.WeakReference

/**
 * Chat 场景相册摘要 Capability。
 *
 * 职责：在 CHAT 场景暴露 `get_gallery_summary`，把命令回调给 [Delegate]（ChatViewModel）执行。
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
                val message = summary?.let { formatGallerySummaryForReply(it) }
                    ?: GALLERY_SUMMARY_UNAVAILABLE_HINT
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
