package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.ToolPartState

/**
 * Turn parts 快照装配器（ADR-016 M2，spec §4）：把 [TurnStreamEvent] 事件流拼装为
 * 当前 turn 的有序 parts 快照。
 *
 * 语义要点：
 * - **同帧顺序 = 到达顺序**：「文本+tool_calls 同帧」按事件到达序落 part（沿用
 *   poLangSingleRunStrategy 修复后的事件序），reducer 只做 append / 同 id 原位覆写；
 * - **占位契约**：[TurnStreamEvent.ToolInputStart] 即插入占位 part（[TOOL_DRAW_CHART] /
 *   [TOOL_RENDER_HTML] 有类型化占位，其余工具不产卡片只占位登记），
 *   [TurnStreamEvent.ToolOutputAvailable] 原位填充（位置锚定，不挪动块序）；
 * - **错误进文档**（spec §5.3）：[TurnStreamEvent.ToolOutputError] 把占位 part 标
 *   [ToolPartState.OUTPUT_ERROR]，errorText 入 [toolErrors]（瞬态；持久化错误以
 *   TaskCard.errorSummary 为准，经 M1 双写落 Room）；
 * - **DONE 不可变**（spec §3）：[PartState.DONE] 的 Text 块不再接受 Delta（忽略并视为
 *   适配层 bug 信号）；占位 part 的原位覆写是 spec 显式豁免。
 *
 * 纯 Kotlin 状态机，单线程使用（与调用方 ViewModel 主线程/事件回调串行语义一致；
 * 线程约束同 [StreamingPacingController]）。
 */
class TurnPartsReducer {

    /** 当前 turn 的 parts 快照（每次 [apply] 后整体替换，List 不可变拷贝收口）。 */
    var parts: List<MessagePart> = emptyList()
        private set

    /** toolCallId → 错误文案（OUTPUT_ERROR 瞬态记录，供状态文案/调试）。 */
    val toolErrors: Map<String, String>
        get() = _toolErrors.toMap()

    private val _toolErrors = mutableMapOf<String, String>()

    /** toolCallId → parts 下标（占位/填充的位置锚）。 */
    private val toolPartPositions = mutableMapOf<String, Int>()

    /** toolCallId → toolName（全部 in-flight 工具登记，含无占位卡片的工具）。 */
    private val toolNames = mutableMapOf<String, String>()

    /** toolCallId → args 累积（ToolInputDelta/Available；M2 不渲染，存档供 M3+）。 */
    private val toolArgs = mutableMapOf<String, String>()

    /** 已完结（output/error 已到）的 toolCallId。 */
    private val completedToolCalls = mutableSetOf<String>()

    /** 新 turn 开始前清空（turn 边界由调用方判定：一次 sendMessage = 一个 turn）。 */
    fun reset() {
        parts = emptyList()
        _toolErrors.clear()
        toolPartPositions.clear()
        toolNames.clear()
        toolArgs.clear()
        completedToolCalls.clear()
    }

    /** 消费一个事件并返回最新 parts 快照。 */
    fun apply(event: TurnStreamEvent): List<MessagePart> {
        when (event) {
            is TurnStreamEvent.TextStart -> textStart(event.partId)
            is TurnStreamEvent.TextDelta -> textDelta(event.partId, event.delta)
            is TurnStreamEvent.TextEnd -> textEnd(event.partId)
            is TurnStreamEvent.ToolInputStart -> toolInputStart(event.toolCallId, event.toolName)
            is TurnStreamEvent.ToolInputDelta ->
                toolArgs[event.toolCallId] = (toolArgs[event.toolCallId] ?: "") + event.argsDelta
            is TurnStreamEvent.ToolInputAvailable -> toolInputAvailable(event.toolCallId, event.args)
            is TurnStreamEvent.ToolOutputAvailable -> toolOutput(event.toolCallId, event.output)
            is TurnStreamEvent.ToolOutputError -> toolError(event.toolCallId, event.errorText)
        }
        return parts
    }

    /** 最老的未完成 toolCallId（[toolName] 非空时按名过滤）；无则 null。供产物回填关联。 */
    fun oldestPendingToolCallId(toolName: String? = null): String? =
        toolNames.entries.firstOrNull { (id, name) ->
            id !in completedToolCalls && (toolName == null || name == toolName)
        }?.key

    // ── 文本块 ────────────────────────────────────────────────

    private fun textStart(partId: String) {
        if (parts.any { it.partId == partId }) return // 幂等：重复 Start 不复制块
        parts = parts + MessagePart.Text(partId, markdown = "", state = PartState.STREAMING)
    }

    private fun textDelta(partId: String, delta: String) {
        val index = parts.indexOfFirst { it.partId == partId }
        val existing = parts.getOrNull(index) as? MessagePart.Text
        if (existing == null) {
            // 鲁棒性：Delta 先于 Start 到达（适配层简化路径）自动补 Start
            textStart(partId)
            textDelta(partId, delta)
            return
        }
        if (existing.state == PartState.DONE) return // DONE 不可变（spec §3）
        replaceAt(index, existing.copy(markdown = existing.markdown + delta))
    }

    private fun textEnd(partId: String) {
        val index = parts.indexOfFirst { it.partId == partId }
        val existing = parts.getOrNull(index) as? MessagePart.Text ?: return
        if (existing.state == PartState.DONE) return
        replaceAt(index, existing.copy(state = PartState.DONE))
    }

    // ── 工具块 ────────────────────────────────────────────────

    private fun toolInputStart(toolCallId: String, toolName: String) {
        if (toolNames.containsKey(toolCallId)) return // 幂等
        toolNames[toolCallId] = toolName
        val placeholder = placeholderPart(toolCallId, toolName) ?: return // 无卡片工具只占位登记
        toolPartPositions[toolCallId] = parts.size
        parts = parts + placeholder
    }

    private fun toolInputAvailable(toolCallId: String, args: String) {
        toolArgs[toolCallId] = args
        updateToolPlaceholder(toolCallId) { part -> part.withToolState(ToolPartState.INPUT_AVAILABLE) }
    }

    private fun toolOutput(toolCallId: String, output: MessagePart) {
        completedToolCalls += toolCallId
        val position = toolPartPositions[toolCallId]
        if (position != null && position < parts.size) {
            // 原位填充：位置锚定（占位契约），partId 以产物自身为准（调用方契约：与 toolCallId 一致）
            replaceAt(position, output)
        } else {
            // 无占位（非卡片工具的产物 part，如脚本直出图卡）：按到达顺序 append
            toolPartPositions[toolCallId] = parts.size
            parts = parts + output
        }
    }

    private fun toolError(toolCallId: String, errorText: String) {
        completedToolCalls += toolCallId
        _toolErrors[toolCallId] = errorText
        updateToolPlaceholder(toolCallId) { part -> part.withToolState(ToolPartState.OUTPUT_ERROR) }
    }

    /** 占位 part 工厂：卡片类工具（图卡/HTML 卡）有类型化占位；其余工具不产 part。 */
    private fun placeholderPart(toolCallId: String, toolName: String): MessagePart? = when (toolName) {
        TOOL_DRAW_CHART ->
            MessagePart.Chart(partId = toolCallId, svg = "", state = ToolPartState.INPUT_STREAMING)
        TOOL_RENDER_HTML ->
            MessagePart.HtmlCard(partId = toolCallId, html = "", state = ToolPartState.INPUT_STREAMING)
        else -> null
    }

    private inline fun updateToolPlaceholder(toolCallId: String, transform: (MessagePart) -> MessagePart) {
        val position = toolPartPositions[toolCallId] ?: return
        val existing = parts.getOrNull(position) ?: return
        replaceAt(position, transform(existing))
    }

    private fun MessagePart.withToolState(state: ToolPartState): MessagePart = when (this) {
        is MessagePart.Chart -> copy(state = state)
        is MessagePart.HtmlCard -> copy(state = state)
        is MessagePart.TaskCard -> copy(state = state)
        else -> this // Text / data part 无工具状态机，不动
    }

    private fun replaceAt(index: Int, part: MessagePart) {
        parts = parts.toMutableList().also { it[index] = part }
    }

    companion object {
        const val TOOL_DRAW_CHART = "draw_chart"
        const val TOOL_RENDER_HTML = "render_html"
    }
}
