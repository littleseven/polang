package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.domain.chat.MessagePart

/**
 * Turn 流式事件（ADR-016 M2，spec §4 chunk 三段式语义）。
 *
 * 对齐的是**事件语义**，传输仍走 Koog agent 循环，不引入 SSE 线协议。
 * 块级 id 是唯一锚点：文本块用 partId（`txt-N`），工具块用 toolCallId（`call-N`，
 * 由 [ChatStreamTurnAdapter] 按到达序合成；Koog hook 不提供稳定 call id）。
 *
 * 事件由 [TurnPartsReducer] 消费拼装 turn 的 parts 快照；全部瞬态（内存轨），
 * 流式消息不落 Room，持久化仍走 legacy 双写（M1 接缝不变）。
 */
sealed interface TurnStreamEvent {

    /** 文本块开始（一轮 LLM 输出的正文段）。同 partId 重复 Start 幂等。 */
    data class TextStart(val partId: String) : TurnStreamEvent

    /** 文本增量（追加到 [partId] 块尾部）。 */
    data class TextDelta(val partId: String, val delta: String) : TurnStreamEvent

    /** 文本块完结（state → DONE）。DONE 后的块不再接受 Delta（reducer 忽略）。 */
    data class TextEnd(val partId: String) : TurnStreamEvent

    /** 工具调用开始：即插入占位 part（骨架/进度，spec §4 卡片占位契约）。 */
    data class ToolInputStart(val toolCallId: String, val toolName: String) : TurnStreamEvent

    /** 工具参数增量（M2 仅累积存档，占位状态推进以 [ToolInputAvailable] 为准）。 */
    data class ToolInputDelta(val toolCallId: String, val argsDelta: String) : TurnStreamEvent

    /** 工具参数完整可用（占位 part 状态 → INPUT_AVAILABLE）。 */
    data class ToolInputAvailable(val toolCallId: String, val args: String) : TurnStreamEvent

    /**
     * 工具产物就绪：占位 part 原位填充。payload 由调用方组装成最终 part
     * （reducer 不解析工具产物——draw_chart 的 SVG / render_html 的 HTML 是上游关注点），
     * 锚定语义：有占位则替换同位置，无占位则按到达顺序 append。
     */
    data class ToolOutputAvailable(val toolCallId: String, val output: MessagePart) : TurnStreamEvent

    /** 工具失败：占位 part 标 OUTPUT_ERROR，errorText 入 [TurnPartsReducer.toolErrors]（spec §5.3 错误进文档）。 */
    data class ToolOutputError(val toolCallId: String, val errorText: String) : TurnStreamEvent
}
