package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction

/**
 * Swift 安全的 UI 动作 DTO（Phase 6.2 T5）。
 *
 * [AgentAction] 是 sealed class，经 K/N 导出到 Swift 后 `when` 穷尽检查在 ObjC 桥接层
 * 失效（Swift 只看到 ObjC 协议，不能 switch sealed）。本 DTO 将 iOS chat 关心的 4 种
 * action 扁平为单一 data class + kind 标记，Swift 直接按 `kind` 字段 switch。
 *
 * [PRIVACY] 红线：只携带文本与图片标识字符串；**不含**GPS、base64、缩略图数据。
 * 例外：ai_optimize 的 [imageUri]（图片标识字符串）经进程内桥传递——与 Android
 * AgentCommand 进程内流转同口径，红线针对远程上传，此路径不触网。
 *
 * 横滑卡退役（2026-11）：`media_results` kind 随原生图集卡一并下线——search/refine
 * 回执已改 [AgentAction.TextReply] 纯计数文案，图集展示走 render_html HTML 卡。
 *
 * @property kind 动作类型："text_reply" | "success" | "ai_optimize" | "error"
 * @property message 文本内容（TextReply.message / Error.message / Success.command 的 method 名 /
 *   ai_optimize 的场景解释句 / 空串）
 * @property imageUri 优化目标图片标识（仅 ai_optimize；LLM 传入的 PHAsset id / file:// 路径 / 媒体 URI）
 */
data class ChatUiActionDto(
    val kind: String,
    val message: String = "",
    val imageUri: String = ""
) {

    companion object {
        private const val KIND_TEXT_REPLY = "text_reply"
        private const val KIND_SUCCESS = "success"
        private const val KIND_AI_OPTIMIZE = "ai_optimize"
        private const val KIND_ERROR = "error"

        /**
         * 从 [AgentAction]（[ChatToolService.uiActions] 产出）转换。
         * 不关心的 action 子类型返回 null（Swift 不消费）。
         */
        fun from(action: AgentAction): ChatUiActionDto? = when (action) {
            is AgentAction.TextReply -> ChatUiActionDto(
                kind = KIND_TEXT_REPLY,
                message = action.message
            )
            is AgentAction.Success -> when (val cmd = action.command) {
                // AI 一键优化：独立 kind，Swift 据此分流到 ChatOptimizeGachaController.draw()
                // （对齐 Android ChatViewModel.handleAiOptimize 的 gacha 分流点）。
                is AgentCommand.AiOptimize -> ChatUiActionDto(
                    kind = KIND_AI_OPTIMIZE,
                    message = cmd.explanation.orEmpty(),
                    imageUri = cmd.imageUri
                )
                else -> ChatUiActionDto(
                    // command 的 method 名（如 "navigate_to"），Swift 端映射「✅ 已执行 …」可见反馈
                    // （对齐 Android describeCommandResult 兜底分支）
                    kind = KIND_SUCCESS,
                    message = AgentCommand.getMethodName(action.command)
                )
            }
            is AgentAction.Error -> ChatUiActionDto(
                kind = KIND_ERROR,
                message = action.message
            )
            is AgentAction.BatchResult -> null
        }
    }
}
