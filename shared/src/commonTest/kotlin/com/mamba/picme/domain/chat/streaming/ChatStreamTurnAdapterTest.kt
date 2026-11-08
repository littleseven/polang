package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.PartState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ChatStreamTurnAdapter] 事件翻译（ADR-016 M2，spec §4）钉桩：
 * Koog 累计快照语义 → 块级三段式语义（差分 delta / 轮边界闭合 / 工具占位事件）。
 */
class ChatStreamTurnAdapterTest {

    @Test
    fun `cumulative snapshots diff into deltas on one text block`() {
        val adapter = ChatStreamTurnAdapter()
        val first = adapter.onEvent(ChatStreamEvent.TextSnapshot("你好"))
        assertEquals(
            listOf(
                TurnStreamEvent.TextStart("txt-0"),
                TurnStreamEvent.TextDelta("txt-0", "你好"),
            ),
            first,
        )
        // 本轮内连续增长：只发差量
        assertEquals(
            listOf(TurnStreamEvent.TextDelta("txt-0", "，世界")),
            adapter.onEvent(ChatStreamEvent.TextSnapshot("你好，世界")),
        )
        // 快照未变（重复帧）：零事件
        assertEquals(emptyList(), adapter.onEvent(ChatStreamEvent.TextSnapshot("你好，世界")))
    }

    @Test
    fun `non extension snapshot closes old block and opens a new one`() {
        // 新一轮从空重新累计（Koog 语义）→ 先 TextEnd 旧块再开新块
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.TextSnapshot("第一轮答复"))
        val events = adapter.onEvent(ChatStreamEvent.TextSnapshot("第二轮"))
        assertEquals(
            listOf(
                TurnStreamEvent.TextEnd("txt-0"),
                TurnStreamEvent.TextStart("txt-1"),
                TurnStreamEvent.TextDelta("txt-1", "第二轮"),
            ),
            events,
        )
    }

    @Test
    fun `tool call started closes text block and emits input start plus available`() {
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.TextSnapshot("让我画个图"))
        val events = adapter.onEvent(
            ChatStreamEvent.ToolCallStarted(toolName = "draw_chart", args = """{"type":"bar"}"""),
        )
        // 轮边界：闭合当前文本块；Koog 在调用开始即给全量 args（Start + Available 同帧）
        assertEquals(
            listOf(
                TurnStreamEvent.TextEnd("txt-0"),
                TurnStreamEvent.ToolInputStart("call-0", "draw_chart"),
                TurnStreamEvent.ToolInputAvailable("call-0", """{"type":"bar"}"""),
            ),
            events,
        )
    }

    @Test
    fun `tool call without open text block emits no text end`() {
        val adapter = ChatStreamTurnAdapter()
        val events = adapter.onEvent(ChatStreamEvent.ToolCallStarted(toolName = "search_media"))
        assertEquals(
            listOf(
                TurnStreamEvent.ToolInputStart("call-0", "search_media"),
                TurnStreamEvent.ToolInputAvailable("call-0", ""),
            ),
            events,
        )
    }

    @Test
    fun `text after tool call starts a fresh block`() {
        // 工具调用后的下一轮文本是新块（不续写旧块）
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.TextSnapshot("前"))
        adapter.onEvent(ChatStreamEvent.ToolCallStarted(toolName = "search_media"))
        val events = adapter.onEvent(ChatStreamEvent.TextSnapshot("后"))
        assertEquals(
            listOf(
                TurnStreamEvent.TextStart("txt-1"),
                TurnStreamEvent.TextDelta("txt-1", "后"),
            ),
            events,
        )
    }

    @Test
    fun `ids sequence across rounds and reset restarts`() {
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.TextSnapshot("a"))
        adapter.onEvent(ChatStreamEvent.ToolCallStarted(toolName = "t"))
        adapter.onEvent(ChatStreamEvent.ToolCallStarted(toolName = "t"))
        val events = adapter.onEvent(ChatStreamEvent.TextSnapshot("b"))
        // 第二个工具是 call-1；文本块序 txt-1
        assertEquals(TurnStreamEvent.TextStart("txt-1"), events[0])

        adapter.reset()
        assertEquals(
            listOf(
                TurnStreamEvent.TextStart("txt-0"),
                TurnStreamEvent.TextDelta("txt-0", "新回合"),
            ),
            adapter.onEvent(ChatStreamEvent.TextSnapshot("新回合")),
        )
    }

    @Test
    fun `adapter plus reducer assemble a full turn`() {
        // 端到端钉桩：文本 → 工具占位 → 产物原位填充 → 新一轮文本，块序即到达序
        val adapter = ChatStreamTurnAdapter()
        val reducer = TurnPartsReducer()
        fun feed(event: ChatStreamEvent) = adapter.onEvent(event).forEach(reducer::apply)

        feed(ChatStreamEvent.TextSnapshot("统计如下"))
        feed(ChatStreamEvent.ToolCallStarted(toolName = "draw_chart", args = "{}"))
        reducer.apply(
            TurnStreamEvent.ToolOutputAvailable("call-0", MessagePart.Chart("call-0", "<svg/>")),
        )
        feed(ChatStreamEvent.TextSnapshot("以上"))

        val parts = reducer.parts
        assertEquals(3, parts.size)
        assertEquals("txt-0", parts[0].partId)
        assertEquals("call-0", parts[1].partId)
        assertEquals("txt-1", parts[2].partId)
    }

    // ── 值域终态 §6.1：Reasoning 快照差分（与文本块同构独立状态机）─────

    @Test
    fun `reasoning snapshots diff into deltas on one reasoning block`() {
        val adapter = ChatStreamTurnAdapter()
        val first = adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("先分析"))
        assertEquals(
            listOf(
                TurnStreamEvent.ReasoningStart("rsn-0"),
                TurnStreamEvent.ReasoningDelta("rsn-0", "先分析"),
            ),
            first,
        )
        assertEquals(
            listOf(TurnStreamEvent.ReasoningDelta("rsn-0", "需求")),
            adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("先分析需求")),
        )
        // 快照未变（重复帧）：零事件
        assertEquals(emptyList(), adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("先分析需求")))
    }

    @Test
    fun `reasoning and text blocks are independent state machines`() {
        // extended thinking 轮内形态：reasoning 先流、text 后流，两块独立差分互不串扰
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("思考"))
        adapter.onEvent(ChatStreamEvent.TextSnapshot("回答"))
        val reasoningGrow = adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("思考加深"))
        assertEquals(listOf(TurnStreamEvent.ReasoningDelta("rsn-0", "加深")), reasoningGrow)
        val textGrow = adapter.onEvent(ChatStreamEvent.TextSnapshot("回答完毕"))
        assertEquals(listOf(TurnStreamEvent.TextDelta("txt-0", "完毕")), textGrow)
    }

    @Test
    fun `round started closes both reasoning and text blocks`() {
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("思考"))
        adapter.onEvent(ChatStreamEvent.TextSnapshot("回答"))
        val events = adapter.onEvent(ChatStreamEvent.RoundStarted)
        assertEquals(
            listOf(
                TurnStreamEvent.TextEnd("txt-0"),
                TurnStreamEvent.ReasoningEnd("rsn-0"),
                TurnStreamEvent.RoundStarted(0),
            ),
            events,
        )
        // 新一轮 reasoning 开新块
        assertEquals(
            listOf(
                TurnStreamEvent.ReasoningStart("rsn-1"),
                TurnStreamEvent.ReasoningDelta("rsn-1", "再想"),
            ),
            adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("再想")),
        )
    }

    @Test
    fun `tool call started closes reasoning block`() {
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.ReasoningSnapshot("要搜索"))
        val events = adapter.onEvent(ChatStreamEvent.ToolCallStarted(toolName = "web_search", args = "{}"))
        assertEquals(
            listOf(
                TurnStreamEvent.ReasoningEnd("rsn-0"),
                TurnStreamEvent.ToolInputStart("call-0", "web_search"),
                TurnStreamEvent.ToolInputAvailable("call-0", "{}"),
            ),
            events,
        )
    }

    @Test
    fun `adapter plus reducer assemble reasoning text sources turn`() {
        // 端到端钉桩（值域终态 §6.1/§6.2）：思考链 → 正文 → web_search 引用，
        // 终态 parts = [reasoning][text][source] 三分区
        val adapter = ChatStreamTurnAdapter()
        val reducer = TurnPartsReducer()
        fun feed(event: ChatStreamEvent) = adapter.onEvent(event).forEach(reducer::apply)

        feed(ChatStreamEvent.ReasoningSnapshot("查一下最新版本"))
        feed(ChatStreamEvent.ToolCallStarted(toolName = "web_search", args = "{}"))
        reducer.apply(
            TurnStreamEvent.SourcesAvailable(
                listOf(MessagePart.SourceUrl("", "https://a.com", "https://a.com", "A")),
            ),
        )
        feed(ChatStreamEvent.TextSnapshot("最新版本是 2.0"))

        val parts = reducer.parts
        assertEquals(
            listOf(
                MessagePart.Reasoning("rsn-0", "查一下最新版本", PartState.DONE),
                MessagePart.Text("txt-0", "最新版本是 2.0", PartState.STREAMING),
                MessagePart.SourceUrl("src-0", "https://a.com", "https://a.com", "A"),
            ),
            parts,
        )
    }

    // ── M4：显式 RoundStarted 轮边界（spec §10 收口）─────────────────

    @Test
    fun `round started closes open text block and emits round event`() {        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.TextSnapshot("第一轮"))
        val events = adapter.onEvent(ChatStreamEvent.RoundStarted)
        assertEquals(
            listOf(
                TurnStreamEvent.TextEnd("txt-0"),
                TurnStreamEvent.RoundStarted(0),
            ),
            events,
        )
        // 下一轮快照开新块（差分基准已清空）
        assertEquals(
            listOf(
                TurnStreamEvent.TextStart("txt-1"),
                TurnStreamEvent.TextDelta("txt-1", "第二轮"),
            ),
            adapter.onEvent(ChatStreamEvent.TextSnapshot("第二轮")),
        )
    }

    @Test
    fun `round started without open block emits only round event and increments`() {
        val adapter = ChatStreamTurnAdapter()
        assertEquals(
            listOf(TurnStreamEvent.RoundStarted(0)),
            adapter.onEvent(ChatStreamEvent.RoundStarted),
        )
        assertEquals(
            listOf(TurnStreamEvent.RoundStarted(1)),
            adapter.onEvent(ChatStreamEvent.RoundStarted),
        )
    }

    @Test
    fun `explicit round signal beats extension heuristic`() {
        // 关键钉桩：新轮首个快照恰是旧快照的前缀扩展（如模型复述前文）时，
        // 显式 RoundStarted 优先——必须开新块全量落，而非差分续写旧块
        val adapter = ChatStreamTurnAdapter()
        adapter.onEvent(ChatStreamEvent.TextSnapshot("abc"))
        adapter.onEvent(ChatStreamEvent.RoundStarted)
        val events = adapter.onEvent(ChatStreamEvent.TextSnapshot("abcd"))
        assertEquals(
            listOf(
                TurnStreamEvent.TextStart("txt-1"),
                TurnStreamEvent.TextDelta("txt-1", "abcd"),
            ),
            events,
        )
    }

    @Test
    fun `reducer ignores round started without touching parts`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextStart("txt-0"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "内容"))
        val before = reducer.parts
        reducer.apply(TurnStreamEvent.RoundStarted(0))
        assertEquals(before, reducer.parts)
    }
}
