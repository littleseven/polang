package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent

/**
 * [ChatStreamEvent]（引擎层事件：本轮累计快照语义）→ [TurnStreamEvent]（spec §4 块级
 * 三段式语义）的翻译适配器（ADR-016 M2）。
 *
 * 状态机口径：
 * - 一轮 LLM 文本 = 一个 Text 块（`txt-N`）：快照差分出 delta（Koog 语义是本轮累计全文，
 *   非 delta）；
 * - **轮边界（M4 收口，spec §10）= 显式 [ChatStreamEvent.RoundStarted] 信号**：闭合当前
 *   文本块（TextEnd）并透传 [TurnStreamEvent.RoundStarted]（turn 内 0 起轮次序号）；
 *   差分启发式（快照不再是前值的连续扩展 ⇒ 轮边界）降级为**兜底**——仅在上游未发
 *   RoundStarted 的路径（如直执/旧引擎）生效，与显式信号并存时不重复闭合（RoundStarted
 *   已闭合旧块并重置差分基准，后续快照必开新块，兜底分支自然不再命中）；
 * - [ChatStreamEvent.ToolCallStarted] = 轮内工具边界：闭合当前文本块，合成 `call-N`
 *   toolCallId，发 ToolInputStart + ToolInputAvailable（Koog 在调用开始即给全量 args，无增量）；
 * - 工具的产出/失败不由本适配器产出——payload 在能力执行点（如图表 SVG 落库处），
 *   由调用方直接喂 [TurnPartsReducer] 的 ToolOutputAvailable/ToolOutputError。
 *
 * 兜底启发式前提（Koog 线行为锚定，2026-09-27 实证；M4 起仅为防御路径）：onPartialText
 * 快照在同一轮内**单调追加**（新快照恒以旧快照为前缀），新一轮从空重新累计。若上游语义
 * 变更（快照乱序 / 修正前文 / 跨轮不清空）且 RoundStarted 未到达，表现为旧块提前 DONE +
 * 前文重复落新块（文本双显）、跨轮文本归属错块。
 *
 * 单线程使用（调用方收口在 Main.immediate，同 [TurnPartsReducer] 契约）；turn 边界由
 * 调用方 [reset]（一次 sendMessage = 一个 turn）。
 */
class ChatStreamTurnAdapter {

    private var textSeq = 0
    private var toolSeq = 0
    private var roundSeq = 0

    /** 当前未闭合的文本块 partId（null = 不在文本轮）。 */
    private var openTextPartId: String? = null

    /** 本轮上一次累计快照（差分基准）。 */
    private var prevSnapshot = ""

    fun reset() {
        textSeq = 0
        toolSeq = 0
        roundSeq = 0
        openTextPartId = null
        prevSnapshot = ""
    }

    /** 翻译一个引擎事件为 0..N 个 turn 事件（保到达顺序）。 */
    fun onEvent(event: ChatStreamEvent): List<TurnStreamEvent> = when (event) {
        is ChatStreamEvent.TextSnapshot -> onTextSnapshot(event.text)
        is ChatStreamEvent.ToolCallStarted -> onToolCallStarted(event)
        is ChatStreamEvent.RoundStarted -> onRoundStarted()
    }

    /**
     * 显式轮边界（M4 主路径）：闭合未完结文本块、清空差分基准（下一轮快照从空累计，
     * 必走「开新块」分支），透传轮次序号。
     */
    private fun onRoundStarted(): List<TurnStreamEvent> {
        val events = mutableListOf<TurnStreamEvent>()
        openTextPartId?.let { open ->
            events += TurnStreamEvent.TextEnd(open)
            openTextPartId = null
        }
        prevSnapshot = ""
        events += TurnStreamEvent.RoundStarted(roundSeq++)
        return events
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
        // 兜底路径（M4 起显式 RoundStarted 为主）：新一轮快照从空重新累计且 RoundStarted
        // 未到达（直执/旧引擎路径），或首块——闭合旧块、开新块、全量作首个 delta
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
