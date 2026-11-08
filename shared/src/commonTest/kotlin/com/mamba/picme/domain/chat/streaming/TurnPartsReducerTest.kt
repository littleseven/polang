package com.mamba.picme.domain.chat.streaming

import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.ToolPartState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [TurnPartsReducer] turn parts 快照装配（ADR-016 M2，spec §4/§5）状态机钉桩：
 * 文本三段式 / 占位契约 / 原位填充 / 错误进文档 / DONE 不可变 / 待关联查询。
 */
class TurnPartsReducerTest {

    // ── 文本块 ────────────────────────────────────────────────

    @Test
    fun `text start delta end lifecycle builds one done block`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextStart("txt-0"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "你好"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "，世界"))
        val parts = reducer.apply(TurnStreamEvent.TextEnd("txt-0"))
        assertEquals(
            listOf(MessagePart.Text("txt-0", "你好，世界", PartState.DONE)),
            parts,
        )
    }

    @Test
    fun `repeated text start is idempotent`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextStart("txt-0"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "a"))
        val parts = reducer.apply(TurnStreamEvent.TextStart("txt-0"))
        assertEquals(1, parts.size)
        assertEquals("a", (parts[0] as MessagePart.Text).markdown)
    }

    @Test
    fun `delta before start auto-opens the block`() {
        val reducer = TurnPartsReducer()
        val parts = reducer.apply(TurnStreamEvent.TextDelta("txt-0", "孤儿 delta"))
        assertEquals(
            listOf(MessagePart.Text("txt-0", "孤儿 delta", PartState.STREAMING)),
            parts,
        )
    }

    @Test
    fun `done text block rejects further deltas`() {
        // DONE 不可变（spec §3）：适配层在 DONE 后继续发 delta 是 bug 信号，忽略
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextStart("txt-0"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "定稿"))
        reducer.apply(TurnStreamEvent.TextEnd("txt-0"))
        val parts = reducer.apply(TurnStreamEvent.TextDelta("txt-0", "篡改"))
        assertEquals(
            listOf(MessagePart.Text("txt-0", "定稿", PartState.DONE)),
            parts,
        )
    }

    // ── 思考链块（值域终态 spec §6.1）──────────────────────────

    @Test
    fun `reasoning start delta end lifecycle builds one done block`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ReasoningStart("rsn-0"))
        reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "用户想要"))
        val parts = reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "最新海报"))
        assertEquals(listOf(MessagePart.Reasoning("rsn-0", "用户想要最新海报", PartState.STREAMING)), parts)
        val done = reducer.apply(TurnStreamEvent.ReasoningEnd("rsn-0"))
        assertEquals(listOf(MessagePart.Reasoning("rsn-0", "用户想要最新海报", PartState.DONE)), done)
    }

    @Test
    fun `reasoning assembles before text in natural order`() {
        // extended thinking 模型先流 reasoning 再流 text：到达序即立法序
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "思考"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "回答"))
        assertEquals(
            listOf(
                MessagePart.Reasoning("rsn-0", "思考", PartState.STREAMING),
                MessagePart.Text("txt-0", "回答", PartState.STREAMING),
            ),
            reducer.parts,
        )
    }

    @Test
    fun `reasoning arriving after text is still ordered before it`() {
        // 排序立法（spec §6.1）：无论到达序，reasoning 恒插主区首 part 之前
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "先到正文"))
        reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "后到思考"))
        assertEquals(
            listOf(
                MessagePart.Reasoning("rsn-0", "后到思考", PartState.STREAMING),
                MessagePart.Text("txt-0", "先到正文", PartState.STREAMING),
            ),
            reducer.parts,
        )
    }

    @Test
    fun `done reasoning block rejects further deltas`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ReasoningStart("rsn-0"))
        reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "定稿思考"))
        reducer.apply(TurnStreamEvent.ReasoningEnd("rsn-0"))
        val parts = reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "篡改"))
        assertEquals(listOf(MessagePart.Reasoning("rsn-0", "定稿思考", PartState.DONE)), parts)
    }

    @Test
    fun `reasoning delta before start auto-opens the block`() {
        val reducer = TurnPartsReducer()
        val parts = reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "孤儿 delta"))
        assertEquals(listOf(MessagePart.Reasoning("rsn-0", "孤儿 delta", PartState.STREAMING)), parts)
    }

    @Test
    fun `repeated reasoning start is idempotent`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ReasoningStart("rsn-0"))
        reducer.apply(TurnStreamEvent.ReasoningDelta("rsn-0", "a"))
        val parts = reducer.apply(TurnStreamEvent.ReasoningStart("rsn-0"))
        assertEquals(1, parts.size)
        assertEquals("a", (parts[0] as MessagePart.Reasoning).text)
    }

    // ── source 区（值域终态 spec §6.2：chips 恒在正文下方）──────

    @Test
    fun `sources append after text as tail zone`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "搜索结果如下"))
        val parts = reducer.apply(
            TurnStreamEvent.SourcesAvailable(
                listOf(
                    MessagePart.SourceUrl("", "https://a.com", "https://a.com", "A"),
                    MessagePart.SourceUrl("", "https://b.com", "https://b.com", null),
                ),
            ),
        )
        assertEquals(
            listOf(
                MessagePart.Text("txt-0", "搜索结果如下", PartState.STREAMING),
                MessagePart.SourceUrl("src-0", "https://a.com", "https://a.com", "A"),
                MessagePart.SourceUrl("src-1", "https://b.com", "https://b.com"),
            ),
            parts,
        )
    }

    @Test
    fun `text arriving after sources inserts before the source run`() {
        // 异常序自愈：sources 先到（web_search 工具先于终稿 text 执行），正文后到仍落在
        // chips 上方（spec §6.2 渲染「文本气泡下引用 chips 行」）
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.SourcesAvailable(listOf(MessagePart.SourceUrl("", "u", "https://a.com"))))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "终稿正文"))
        assertEquals(
            listOf(
                MessagePart.Text("txt-0", "终稿正文", PartState.STREAMING),
                MessagePart.SourceUrl("src-0", "u", "https://a.com"),
            ),
            reducer.parts,
        )
    }

    @Test
    fun `tool placeholder and output keep source zone at tail`() {
        // web_search 之后同回合再调 draw_chart：占位/产物落主区（source 区之前），
        // chips 恒在尾部
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.SourcesAvailable(listOf(MessagePart.SourceUrl("", "u", "https://a.com"))))
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", TurnPartsReducer.TOOL_DRAW_CHART))
        val parts = reducer.apply(
            TurnStreamEvent.ToolOutputAvailable("call-0", MessagePart.Chart("call-0", "<svg/>")),
        )
        assertEquals(
            listOf(
                MessagePart.Chart("call-0", "<svg/>"),
                MessagePart.SourceUrl("src-0", "u", "https://a.com"),
            ),
            parts,
        )
    }

    @Test
    fun `sources are re-stamped with unique part ids across emissions`() {
        // 发射方 partId 只作占位；reducer 统一重编 src-N（LazyColumn key = messageId:partId
        // 需 turn 内唯一，跨多次 web_search 调用也不撞）
        val reducer = TurnPartsReducer()
        reducer.apply(
            TurnStreamEvent.SourcesAvailable(
                listOf(MessagePart.SourceUrl("dup", "u1", "https://a.com")),
            ),
        )
        val parts = reducer.apply(
            TurnStreamEvent.SourcesAvailable(
                listOf(MessagePart.SourceUrl("dup", "u2", "https://b.com")),
            ),
        )
        assertEquals(listOf("src-0", "src-1"), parts.map { it.partId })
    }

    @Test
    fun `empty sources emission is a no-op`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "x"))
        val parts = reducer.apply(TurnStreamEvent.SourcesAvailable(emptyList()))
        assertEquals(listOf(MessagePart.Text("txt-0", "x", PartState.STREAMING)), parts)
    }

    // ── 工具占位契约 ────────────────────────────────────────────

    @Test
    fun `draw chart tool input start inserts typed placeholder`() {
        val reducer = TurnPartsReducer()
        val parts = reducer.apply(TurnStreamEvent.ToolInputStart("call-0", TurnPartsReducer.TOOL_DRAW_CHART))
        assertEquals(
            listOf(MessagePart.Chart(partId = "call-0", svg = "", state = ToolPartState.INPUT_STREAMING)),
            parts,
        )
        assertEquals("call-0", reducer.oldestPendingToolCallId())
    }

    @Test
    fun `render html placeholder advances to input available`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", TurnPartsReducer.TOOL_RENDER_HTML))
        val parts = reducer.apply(TurnStreamEvent.ToolInputAvailable("call-0", """{"html":"x"}"""))
        assertEquals(
            listOf(MessagePart.HtmlCard(partId = "call-0", html = "", state = ToolPartState.INPUT_AVAILABLE)),
            parts,
        )
    }

    @Test
    fun `non card tool registers without producing a part`() {
        // search_media 等非卡片工具只占位登记（待关联查询可见），不产 part
        val reducer = TurnPartsReducer()
        val parts = reducer.apply(TurnStreamEvent.ToolInputStart("call-0", "search_media"))
        assertEquals(emptyList(), parts)
        assertEquals("call-0", reducer.oldestPendingToolCallId())
        assertEquals("call-0", reducer.oldestPendingToolCallId("search_media"))
        assertNull(reducer.oldestPendingToolCallId("draw_chart"))
    }

    @Test
    fun `tool output fills the placeholder in place keeping block order`() {
        // 位置锚定：文本-占位-文本 交错，产物到位后原位替换（数组顺序即锚点）
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextStart("txt-0"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "前段"))
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", TurnPartsReducer.TOOL_DRAW_CHART))
        reducer.apply(TurnStreamEvent.TextStart("txt-1"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-1", "后段"))
        val output = MessagePart.Chart(partId = "call-0", svg = "<svg/>")
        val parts = reducer.apply(TurnStreamEvent.ToolOutputAvailable("call-0", output))
        assertEquals(
            listOf(
                MessagePart.Text("txt-0", "前段", PartState.STREAMING),
                output,
                MessagePart.Text("txt-1", "后段", PartState.STREAMING),
            ),
            parts,
        )
        // 产物到位后不再是 pending
        assertNull(reducer.oldestPendingToolCallId())
    }

    @Test
    fun `tool output without placeholder appends in arrival order`() {
        val reducer = TurnPartsReducer()
        val output = MessagePart.Chart(partId = "call-x", svg = "<svg/>")
        val parts = reducer.apply(TurnStreamEvent.ToolOutputAvailable("call-x", output))
        assertEquals(listOf(output), parts)
    }

    @Test
    fun `script produced chart associated to script call appends after text`() {
        // M2 审查修复钉桩：run_gallery_script 直出图卡——调用方经 oldestPendingToolCallId
        // 降级关联到脚本的 callId（非卡片工具无占位）→ reducer 走 append 分支落尾部并完成调用
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "统计如下"))
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", TurnPartsReducer.TOOL_RUN_GALLERY_SCRIPT))
        val output = MessagePart.Chart(partId = "call-0", svg = "<svg/>")
        val parts = reducer.apply(TurnStreamEvent.ToolOutputAvailable("call-0", output))
        assertEquals(
            listOf(
                MessagePart.Text("txt-0", "统计如下", PartState.STREAMING),
                output,
            ),
            parts,
        )
        assertNull(reducer.oldestPendingToolCallId())
    }

    @Test
    fun `tool output error on non card tool records error without producing a part`() {
        // M2：脚本 eval 失败——无占位可标错，错误进 toolErrors 瞬态轨并完成调用（不产 part）
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", TurnPartsReducer.TOOL_RUN_GALLERY_SCRIPT))
        val parts = reducer.apply(TurnStreamEvent.ToolOutputError("call-0", "SCRIPT_TIMEOUT"))
        assertEquals(emptyList(), parts)
        assertEquals(mapOf("call-0" to "SCRIPT_TIMEOUT"), reducer.toolErrors)
        assertNull(reducer.oldestPendingToolCallId())
    }

    @Test
    fun `tool output error marks placeholder and records error text`() {
        // 错误进文档（spec §5.3）：占位标 OUTPUT_ERROR，errorText 入 toolErrors
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", TurnPartsReducer.TOOL_DRAW_CHART))
        val parts = reducer.apply(TurnStreamEvent.ToolOutputError("call-0", "JS 执行超时"))
        assertEquals(
            listOf(MessagePart.Chart(partId = "call-0", svg = "", state = ToolPartState.OUTPUT_ERROR)),
            parts,
        )
        assertEquals(mapOf("call-0" to "JS 执行超时"), reducer.toolErrors)
        assertNull(reducer.oldestPendingToolCallId())
    }

    @Test
    fun `oldest pending returns the earliest unfinished call`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", "draw_chart"))
        reducer.apply(TurnStreamEvent.ToolInputStart("call-1", "draw_chart"))
        reducer.apply(
            TurnStreamEvent.ToolOutputAvailable("call-0", MessagePart.Chart("call-0", "<svg/>")),
        )
        assertEquals("call-1", reducer.oldestPendingToolCallId("draw_chart"))
    }

    @Test
    fun `reset clears turn state`() {
        val reducer = TurnPartsReducer()
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "x"))
        reducer.apply(TurnStreamEvent.ToolInputStart("call-0", "draw_chart"))
        reducer.apply(TurnStreamEvent.ToolOutputError("call-0", "err"))
        reducer.reset()
        assertEquals(emptyList(), reducer.parts)
        assertEquals(emptyMap(), reducer.toolErrors)
        assertNull(reducer.oldestPendingToolCallId())
    }

    @Test
    fun `parts snapshot is an immutable copy safe to hold across applies`() {
        val reducer = TurnPartsReducer()
        val first = reducer.apply(TurnStreamEvent.TextDelta("txt-0", "a"))
        reducer.apply(TurnStreamEvent.TextDelta("txt-0", "b"))
        // 旧快照不被后续 apply 污染（List 不可变拷贝收口）
        assertEquals("a", (first[0] as MessagePart.Text).markdown)
        assertEquals("ab", (reducer.parts[0] as MessagePart.Text).markdown)
    }
}
