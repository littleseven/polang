package com.mamba.picme.domain.chat

/**
 * UIMessage → ModelMessage 双层分离（ADR-016 D1 / spec §6）的 commonMain 表达。
 *
 * 渲染/持久层保存富 parts 文档（[ChatMessage.parts]，防信息丢失）；回灌 LLM 时经
 * [toModelInput] 显式转换为精简项序列：
 * - [MessagePart.Text] → [ModelInputItem.TextMessage]（角色随消息）；
 * - [MessagePart.Chart] / [MessagePart.HtmlCard] / [MessagePart.TaskCard] →
 *   tool-call + tool-result 语义对（卡片生命周期 = tool call 生命周期）；
 * - [MessagePart.Image] → 英文中性占位文本（图片本体不进上下文，[PRIVACY] 媒体红线；
 *   占位保住多轮会话的回合结构）；
 * - [MessagePart.EditResult] → 回灌其文字说明（沿旧路径 `(agent_edit_result, content)` 语义，
 *   防多轮编辑上下文断裂）；
 * - [MessagePart.MediaResults] / [MessagePart.OptimizeCandidates] 等 data part
 *   **不进上下文**（对齐 Vercel convertToModelMessages 丢弃规则）。
 *
 * 引擎无关：不引用 Koog 类型；Koog Message 组装是上层（inference/remote）关注点。
 */
enum class ModelInputRole { USER, ASSISTANT }

sealed interface ModelInputItem {

    /** 文本消息（用户/助手正文）。 */
    data class TextMessage(val role: ModelInputRole, val text: String) : ModelInputItem

    /** 工具调用（卡片类 part 的调用侧投影；args 仅有摘要，不保证完整参数回放）。 */
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val argsSummary: String,
    ) : ModelInputItem

    /** 工具结果（卡片类 part 的产物侧投影；工具失败以 isError 落上下文，模型可自我修正——spec §5.3）。 */
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val resultSummary: String,
        val isError: Boolean = false,
    ) : ModelInputItem
}

/** Room role 列（"user"/"agent"）→ 模型角色（spec §2：role 升格为独立列后的唯一派生点）。 */
fun roleOf(roomRole: String): ModelInputRole =
    if (roomRole == "user") ModelInputRole.USER else ModelInputRole.ASSISTANT

/** 整条消息 → 回灌项序列（空列表 = 整条消息不进上下文）。 */
fun ChatMessage.toModelInput(): List<ModelInputItem> = parts.toModelInput(role, id)

/** parts 序列 → 回灌项序列（块内顺序即上下文顺序；[messageId] 用于 toolCallId 命名空间锚定）。 */
fun List<MessagePart>.toModelInput(role: ModelInputRole, messageId: String): List<ModelInputItem> =
    flatMap { part -> part.toModelInput(role, messageId) }

private fun MessagePart.toModelInput(role: ModelInputRole, messageId: String): List<ModelInputItem> = when (this) {
    is MessagePart.Text ->
        if (markdown.isBlank()) emptyList() else listOf(ModelInputItem.TextMessage(role, markdown))

    // toolCallId = "${messageId}:${partId}" 命名空间锚（M2 起）：跨消息防碰撞；
    // 转真 tool_call 协议时换 Koog/网关侧真实 callId。
    is MessagePart.Chart -> listOf(
        ModelInputItem.ToolCall(namespacedToolCallId(messageId), TOOL_DRAW_CHART, argsSummary = ""),
        ModelInputItem.ToolResult(
            namespacedToolCallId(messageId),
            TOOL_DRAW_CHART,
            resultSummary = "Chart card (SVG, ${svg.length} chars)",
            isError = state == ToolPartState.OUTPUT_ERROR,
        ),
    )

    is MessagePart.HtmlCard -> listOf(
        ModelInputItem.ToolCall(
            namespacedToolCallId(messageId),
            TOOL_RENDER_HTML,
            argsSummary = meta.display?.let { "display=$it" } ?: "",
        ),
        ModelInputItem.ToolResult(
            namespacedToolCallId(messageId),
            TOOL_RENDER_HTML,
            resultSummary = meta.summary ?: "HTML card (${html.length} chars)",
            isError = state == ToolPartState.OUTPUT_ERROR,
        ),
    )

    is MessagePart.TaskCard -> listOf(
        ModelInputItem.ToolCall(toolCallId, TOOL_ENGINEER_TASK, argsSummary = task.sourceText),
        ModelInputItem.ToolResult(
            toolCallId,
            TOOL_ENGINEER_TASK,
            resultSummary = task.errorSummary
                ?: task.resultSummary
                ?: "Task status: ${task.status.name}",
            isError = state == ToolPartState.OUTPUT_ERROR,
        ),
    )

    // 图片本体不进上下文（[PRIVACY] 媒体红线）；英文中性占位文本保住回合结构（spec §6）。
    is MessagePart.Image -> listOf(
        ModelInputItem.TextMessage(
            role,
            if (role == ModelInputRole.USER) USER_IMAGE_PLACEHOLDER else AGENT_IMAGE_PLACEHOLDER,
        ),
    )

    // 编辑结果图不进上下文；文字说明回灌（沿旧路径 (agent_edit_result, content) 语义），
    // 防多轮编辑上下文断裂（spec §6）。
    is MessagePart.EditResult ->
        if (description.isBlank()) emptyList() else listOf(ModelInputItem.TextMessage(role, description))

    // 浏览器直播卡：帧永不回灌；正式回灌投影（结果摘要）属 browser-vnc 直播卡 Task 13，
    // 此前不回灌任何内容（此间管线未产此类 part）。
    is MessagePart.BrowserLive -> emptyList()

    // data part：默认不进上下文（spec §6）
    is MessagePart.MediaResults,
    is MessagePart.OptimizeCandidates,
    -> emptyList()
}

private fun MessagePart.namespacedToolCallId(messageId: String): String = "$messageId:$partId"

private const val TOOL_DRAW_CHART = "draw_chart"
private const val TOOL_RENDER_HTML = "render_html"
private const val TOOL_ENGINEER_TASK = "engineer_task"

private const val USER_IMAGE_PLACEHOLDER = "[user sent an image]"
private const val AGENT_IMAGE_PLACEHOLDER = "[assistant generated an image]"
