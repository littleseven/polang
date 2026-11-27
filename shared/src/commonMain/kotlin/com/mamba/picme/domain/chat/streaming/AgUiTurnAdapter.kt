package com.mamba.picme.domain.chat.streaming

/**
 * [AgUiFrame]（AG-UI 线协议事件）→ [TurnStreamEvent]（spec §4 块级三段式）的翻译
 * 适配器（AG-UI 迁移 M1，spec《2026-11-28-ag-ui-migration-design》§6）。
 *
 * 与 [ChatStreamTurnAdapter] 的分工：那个吃本地 Koog 引擎层事件（快照语义），
 * 这个吃云端 v2 端点帧（已是真 delta + 显式块边界）——服务端 `AgUiFrameSink` 已
 * 完成快照差分与闭合，本适配器基本是直译：
 * - TEXT_MESSAGE_* → TextStart/TextDelta/TextEnd（partId = messageId 服务端锚定）；
 *   role=user 的块跳过（用户消息端上本地已落库，不参与流式渲染）；
 * - TOOL_CALL_START → ToolInputStart；ARGS 累计存档（[TurnStreamEvent.ToolInputDelta]），
 *   TOOL_CALL_END 时一次性 ToolInputAvailable（服务端 Koog 全量 args 单 chunk，累计即完整）；
 * - ToolCallResult / Custom / Run* 不进 TurnStreamEvent——由调用方分流（设备工具桥 /
 *   性能 metadata / 错误收尾），同 `CloudAgentTurnRunner` 现行帧分流口径。
 *
 * 单线程使用（同 [TurnPartsReducer] 契约）；turn 边界由调用方 [reset]。
 */
class AgUiTurnAdapter {

    /** 进行中文本块角色（user 块的内容/完结帧跳过）。 */
    private val openTextRoles = mutableMapOf<String, String>()

    /** 进行中工具调用的参数累计（END 时作 ToolInputAvailable 全量）。 */
    private val openToolArgs = mutableMapOf<String, StringBuilder>()

    fun reset() {
        openTextRoles.clear()
        openToolArgs.clear()
    }

    /** 翻译一个 AG-UI 帧为 0..N 个 turn 事件（保到达顺序；生命周期/自定义帧恒空）。 */
    fun onFrame(frame: AgUiFrame): List<TurnStreamEvent> = when (frame) {
        is AgUiFrame.TextMessageStart -> {
            openTextRoles[frame.messageId] = frame.role
            if (frame.role == "assistant") {
                listOf(TurnStreamEvent.TextStart(frame.messageId))
            } else {
                emptyList()
            }
        }
        is AgUiFrame.TextMessageContent -> {
            if (openTextRoles[frame.messageId] == "assistant") {
                listOf(TurnStreamEvent.TextDelta(frame.messageId, frame.delta))
            } else {
                emptyList()
            }
        }
        is AgUiFrame.TextMessageEnd -> {
            val role = openTextRoles.remove(frame.messageId)
            if (role == "assistant") {
                listOf(TurnStreamEvent.TextEnd(frame.messageId))
            } else {
                emptyList()
            }
        }
        is AgUiFrame.ToolCallStart -> {
            openToolArgs[frame.toolCallId] = StringBuilder()
            listOf(TurnStreamEvent.ToolInputStart(frame.toolCallId, frame.toolCallName))
        }
        is AgUiFrame.ToolCallArgs -> {
            openToolArgs[frame.toolCallId]?.append(frame.delta)
            listOf(TurnStreamEvent.ToolInputDelta(frame.toolCallId, frame.delta))
        }
        is AgUiFrame.ToolCallEnd -> {
            val args = openToolArgs.remove(frame.toolCallId)?.toString() ?: ""
            listOf(TurnStreamEvent.ToolInputAvailable(frame.toolCallId, args))
        }
        // ToolCallResult（observation 不产卡片）/ Custom（桥帧）/ Run*（生命周期）由调用方分流
        else -> emptyList()
    }
}
