package com.mamba.picme.agent.core.inference.remote

/**
 * chat 远程流式事件（sealed 枚举所有合法事件，供 UI 穷尽处理）。
 *
 * 由 [RemoteChatEngine.streamChat] 的 onEvent 回调产出，承载流式期间的瞬态内容；
 * 全部走内存轨（ViewModel 的 _streamingMessage），**不落 Room**。
 *
 * ADR-016 M2：本事件流经 `ChatStreamTurnAdapter`（domain/chat/streaming）翻译为
 * spec §4 的 TurnStreamEvent 三段式（块级 id 锚定），驱动 turn parts 快照装配。
 */
sealed interface ChatStreamEvent {

    /**
     * 模型本轮累计文本快照（**非 delta**，含 Markdown），UI 直接整体替换气泡内容，
     * 避免乱序累积问题。新一轮（工具调用后的下一轮）从空重新累计。
     */
    data class TextSnapshot(val text: String) : ChatStreamEvent

    /**
     * 模型本轮产出 tool_calls，进入端侧工具执行。
     * UI 可将气泡内容切换为"正在调用工具"类状态文案（文案由 app 层按语言本地化）。
     *
     * M2 起携带 [toolName]（Koog onToolCallStarting 透传）供 turn 装配器生成类型化
     * 占位 part；缺省空串 = 未知工具（直执路径等），占位降级为通用登记。
     * [args] 为工具参数 JSON（Koog 在调用开始即给全量，无增量），瞬态不入库。
     */
    data class ToolCallStarted(val toolName: String = "", val args: String = "") : ChatStreamEvent

    /**
     * 新一轮 LLM 流式开始（ADR-016 M4 收口，spec §10）：显式轮边界信号，
     * 替代 turn 装配器「非扩展快照 = 轮边界」的差分启发式猜测（失效表现见
     * `ChatStreamTurnAdapter` 类注释）。发射点 = Koog `onLLMStreamingStarting`
     * （每轮一次，含首轮；工具调用后的下一轮同样触发）。
     *
     * UI 无需响应本事件（气泡态由 TextSnapshot/ToolCallStarted 驱动）；
     * 消费方是 turn 装配器（闭合上一轮文本块）。
     */
    data object RoundStarted : ChatStreamEvent
}
