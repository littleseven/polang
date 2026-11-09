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

    /**
     * 新一轮 LLM 流式开始（ADR-016 M4 收口，spec §10）：显式轮边界信号。
     *
     * 由 [ChatStreamTurnAdapter] 自引擎层 `ChatStreamEvent.RoundStarted` 翻译（Koog
     * `onLLMStreamingStarting` 为地面真值），替代「非扩展快照 = 轮边界」的差分猜测；
     * 未闭合文本块的闭合（TextEnd）由 adapter 一并翻译。[round] 为 turn 内 0 起轮次序号
     * （事件流自描述/审计用）；reducer 不消费轮次——块状态闭合以 TextEnd 为准。
     */
    data class RoundStarted(val round: Int) : TurnStreamEvent

    /** 文本块开始（一轮 LLM 输出的正文段）。同 partId 重复 Start 幂等。 */
    data class TextStart(val partId: String) : TurnStreamEvent

    /** 文本增量（追加到 [partId] 块尾部）。 */
    data class TextDelta(val partId: String, val delta: String) : TurnStreamEvent

    /** 文本块完结（state → DONE）。DONE 后的块不再接受 Delta（reducer 忽略）。 */
    data class TextEnd(val partId: String) : TurnStreamEvent

    /**
     * 思考链块开始（一轮 LLM 的 extended thinking 段，先于正文 text 流出；值域终态
     * spec §6.1）。块 id 命名 `rsn-N`（[ChatStreamTurnAdapter] 合成）；同 partId 重复
     * Start 幂等。**生产开关默认关**——事件族先行落地，Koog 链路接线就位后由模型配置
     * 决定是否实际产出（RemoteModelFactory thinking=disabled 注入不变）。
     */
    data class ReasoningStart(val partId: String) : TurnStreamEvent

    /** 思考链增量（追加到 [partId] 块尾部；装配恒排在 text 前，spec §6.1 排序规则）。 */
    data class ReasoningDelta(val partId: String, val delta: String) : TurnStreamEvent

    /** 思考链块完结（state → DONE）。DONE 后的块不再接受 Delta（reducer 忽略）。 */
    data class ReasoningEnd(val partId: String) : TurnStreamEvent

    /**
     * web_search 引用就绪（值域终态 spec §6.2）：SourceUrl parts 挂当轮 assistant
     * 消息尾部（正文下方 chips 行）。partId 由 reducer 统一重编（`src-N`）；发射方
     * 只保证 sourceId/url/title 语义。不经引擎事件流——参照 [ToolOutputAvailable]
     * 的能力执行点直喂先例（ChatToolService.webSearchSources 通道）。
     */
    data class SourcesAvailable(val sources: List<MessagePart.SourceUrl>) : TurnStreamEvent

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
