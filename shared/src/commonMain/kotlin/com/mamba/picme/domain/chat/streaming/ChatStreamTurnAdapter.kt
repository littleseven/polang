package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent

/**
 * [ChatStreamEvent]（引擎层事件：本轮累计快照语义）→ [TurnStreamEvent]（spec §4 块级
 * 三段式语义）的翻译适配器（ADR-016 M2）。
 *
 * 状态机口径：
 * - 一轮 LLM 文本 = 一个 Text 块（`txt-N`）：快照差分出 delta（Koog 语义是本轮累计全文，
 *   非 delta）；快照不再是前值的连续扩展（新一轮从空累计）时，先 TextEnd 旧块再开新块；
 * - [ChatStreamEvent.ToolCallStarted] = 轮边界：闭合当前文本块，合成 `call-N` toolCallId，
 *   发 ToolInputStart + ToolInputAvailable（Koog 在调用开始即给全量 args，无增量）；
 * - 工具的产出/失败不由本适配器产出——payload 在能力执行点（如图表 SVG 落库处），
 *   由调用方直接喂 [TurnPartsReducer] 的 ToolOutputAvailable/ToolOutputError。
 *
 * 🔴 差分启发式前提（Koog 线行为锚定，2026-09-27 实证）：onPartialText 快照在同一轮内
 * **单调追加**（新快照恒以旧快照为前缀），新一轮从空重新累计。「非扩展快照 = 轮边界」
 * 的猜测建立在该前提上；若上游语义变更（快照乱序 / 修正前文 / 跨轮不清空），表现为
 * 旧块提前 DONE + 前文重复落新块（文本双显）、跨轮文本归属错块。M4 切渲染源前须以显式
 * round-start 信号替代本猜测（spec §10 M4 收口项）。
 *
 * 单线程使用（调用方收口在 Main.immediate，同 [TurnPartsReducer] 契约）；turn 边界由
 * 调用方 [reset]（一次 sendMessage = 一个 turn）。
 */
class ChatStreamTurnAdapter {

    private var textSeq = 0
    private var toolSeq = 0

    /** 当前未闭合的文本块 partId（null = 不在文本轮）。 */
    private var openTextPartId: String? = null

    /** 本轮上一次累计快照（差分基准）。 */
    private var prevSnapshot = ""

    fun reset() {
        textSeq = 0
        toolSeq = 0
        openTextPartId = null
        prevSnapshot = ""
    }

    /** 翻译一个引擎事件为 0..N 个 turn 事件（保到达顺序）。 */
    fun onEvent(event: ChatStreamEvent): List<TurnStreamEvent> = when (event) {
        is ChatStreamEvent.TextSnapshot -> onTextSnapshot(event.text)
        is ChatStreamEvent.ToolCallStarted -> onToolCallStarted(event)
    }

    private fun onTextSnapshot(snapshot: String): List<TurnStreamEvent> {
        val open = openTextPartId
        if (open != null && snapshot.length >= prevSnapshot.length && snapshot.startsWith(prevSnapshot)) {
            // 本轮内连续增长：差分出 delta
            val delta = snapshot.substring(prevSnapshot.length)
            prevSnapshot = snapshot
            return if (delta.isEmpty()) {
                emptyList()
            } else {
                listOf(TurnStreamEvent.TextDelta(open, delta))
            }
        }
        // 新一轮（快照从空重新累计）或首块：闭合旧块、开新块、全量作首个 delta
        val events = mutableListOf<TurnStreamEvent>()
        if (open != null) events += TurnStreamEvent.TextEnd(open)
        val partId = "txt-${textSeq++}"
        openTextPartId = partId
        prevSnapshot = snapshot
        events += TurnStreamEvent.TextStart(partId)
        if (snapshot.isNotEmpty()) events += TurnStreamEvent.TextDelta(partId, snapshot)
        return events
    }

    private fun onToolCallStarted(event: ChatStreamEvent.ToolCallStarted): List<TurnStreamEvent> {
        val events = mutableListOf<TurnStreamEvent>()
        // 工具调用 = 轮边界：闭合当前文本块（下一轮文本另开新块）
        openTextPartId?.let { open ->
            events += TurnStreamEvent.TextEnd(open)
            openTextPartId = null
            prevSnapshot = ""
        }
        val toolCallId = "call-${toolSeq++}"
        events += TurnStreamEvent.ToolInputStart(toolCallId, event.toolName)
        events += TurnStreamEvent.ToolInputAvailable(toolCallId, event.args)
        return events
    }
}
