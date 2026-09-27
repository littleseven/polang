package com.mamba.picme.domain.chat

/**
 * UIMessage → ModelMessage 双层分离（ADR-016 D1 / spec §6）的 commonMain 表达。
 *
 * 渲染/持久层保存富 parts 文档（[ChatMessage.parts]，防信息丢失）；回灌 LLM 时经
 * [toModelInput] 显式转换为精简项序列：
 * - [MessagePart.Text] → [ModelInputItem.TextMessage]（角色随消息）；
 * - [MessagePart.Chart] / [MessagePart.HtmlCard] / [MessagePart.TaskCard] →
 *   tool-call + tool-result 语义对（卡片生命周期 = tool call 生命周期）；
 * - [MessagePart.MediaResults] / [MessagePart.Image] / [MessagePart.EditResult] /
 *   [MessagePart.OptimizeCandidates] 等 data part / 媒体块 **不进上下文**
 *   （对齐 Vercel convertToModelMessages 丢弃规则；[PRIVACY] 媒体红线：图片不发给远程 LLM）。
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

/** 消息角色（spec §2：role = USER | AGENT）。M1 自 legacy [ChatMessage.type] 派生。 */
val ChatMessage.modelRole: ModelInputRole
    get() = when (type) {
        ChatMessageType.USER_TEXT,
        ChatMessageType.USER_IMAGE,
        ChatMessageType.USER_IMAGE_TEXT,
        -> ModelInputRole.USER

        else -> ModelInputRole.ASSISTANT
    }

/** 整条消息 → 回灌项序列（空列表 = 整条消息不进上下文）。 */
fun ChatMessage.toModelInput(): List<ModelInputItem> = parts.toModelInput(modelRole)

/** parts 序列 → 回灌项序列（块内顺序即上下文顺序）。 */
fun List<MessagePart>.toModelInput(role: ModelInputRole): List<ModelInputItem> =
    flatMap { part -> part.toModelInput(role) }

private fun MessagePart.toModelInput(role: ModelInputRole): List<ModelInputItem> = when (this) {
    is MessagePart.Text ->
        if (markdown.isBlank()) emptyList() else listOf(ModelInputItem.TextMessage(role, markdown))

    is MessagePart.Chart -> listOf(
        ModelInputItem.ToolCall(partId, TOOL_DRAW_CHART, argsSummary = ""),
        ModelInputItem.ToolResult(partId, TOOL_DRAW_CHART, resultSummary = "图表卡片（SVG，${svg.length} 字符）"),
    )

    is MessagePart.HtmlCard -> listOf(
        ModelInputItem.ToolCall(
            partId,
            TOOL_RENDER_HTML,
            argsSummary = meta.display?.let { "display=$it" } ?: "",
        ),
        ModelInputItem.ToolResult(
            partId,
            TOOL_RENDER_HTML,
            resultSummary = meta.summary ?: "HTML 卡片（${html.length} 字符）",
        ),
    )

    is MessagePart.TaskCard -> listOf(
        ModelInputItem.ToolCall(toolCallId, TOOL_ENGINEER_TASK, argsSummary = task.sourceText),
        ModelInputItem.ToolResult(
            toolCallId,
            TOOL_ENGINEER_TASK,
            resultSummary = task.errorSummary
                ?: task.resultSummary
                ?: "任务状态 ${task.status.name}",
            isError = state == ToolPartState.OUTPUT_ERROR,
        ),
    )

    // data part / 媒体块：默认不进上下文（spec §6）
    is MessagePart.MediaResults,
    is MessagePart.Image,
    is MessagePart.EditResult,
    is MessagePart.OptimizeCandidates,
    -> emptyList()
}

private const val TOOL_DRAW_CHART = "draw_chart"
private const val TOOL_RENDER_HTML = "render_html"
private const val TOOL_ENGINEER_TASK = "engineer_task"
