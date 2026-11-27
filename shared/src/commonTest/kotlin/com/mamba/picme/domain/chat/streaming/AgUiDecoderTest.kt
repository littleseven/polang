package com.mamba.picme.domain.chat.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * AG-UI 解码器测试（spec《2026-11-28-ag-ui-migration-design》§6）：
 * 解析容错 + adapter 映射（role 分流 / 工具 args 累计 / 生命周期帧透传边界）。
 */
class AgUiDecoderTest {

    // ---------- AgUiSseParser ----------

    @Test
    fun `parse run started`() {
        val f = AgUiSseParser.parseBlock("""data: {"type":"RUN_STARTED","threadId":"s1","runId":"r1"}""")
        assertEquals(AgUiFrame.RunStarted("s1", "r1"), f)
    }

    @Test
    fun `parse text message frames`() {
        val start = AgUiSseParser.parseBlock("""data: {"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""")
        assertEquals(AgUiFrame.TextMessageStart("m1", "assistant"), start)
        val content = AgUiSseParser.parseBlock("""data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"你好"}""")
        assertEquals(AgUiFrame.TextMessageContent("m1", "你好"), content)
        val end = AgUiSseParser.parseBlock("""data: {"type":"TEXT_MESSAGE_END","messageId":"m1"}""")
        assertEquals(AgUiFrame.TextMessageEnd("m1"), end)
    }

    @Test
    fun `parse tool call frames`() {
        assertEquals(
            AgUiFrame.ToolCallStart("act-1", "search_media"),
            AgUiSseParser.parseBlock("""data: {"type":"TOOL_CALL_START","toolCallId":"act-1","toolCallName":"search_media"}"""),
        )
        assertEquals(
            AgUiFrame.ToolCallArgs("act-1", "{\"q\":\"x\"}"),
            AgUiSseParser.parseBlock("""data: {"type":"TOOL_CALL_ARGS","toolCallId":"act-1","delta":"{\"q\":\"x\"}"}"""),
        )
        assertEquals(
            AgUiFrame.ToolCallEnd("act-1"),
            AgUiSseParser.parseBlock("""data: {"type":"TOOL_CALL_END","toolCallId":"act-1"}"""),
        )
        assertEquals(
            AgUiFrame.ToolCallResult("act-1-result", "act-1", "找到 3 张"),
            AgUiSseParser.parseBlock("""data: {"type":"TOOL_CALL_RESULT","messageId":"act-1-result","toolCallId":"act-1","content":"找到 3 张"}"""),
        )
    }

    @Test
    fun `parse custom and run terminal frames`() {
        val custom = AgUiSseParser.parseBlock("""data: {"type":"CUSTOM","name":"device_tool_request","value":{"request_id":"tr_1"}}""")
        assertIs<AgUiFrame.Custom>(custom)
        assertEquals("device_tool_request", custom.name)
        assertEquals("tr_1", custom.value?.get("request_id")?.toString()?.trim('"'))

        val finished = AgUiSseParser.parseBlock("""data: {"type":"RUN_FINISHED","threadId":"s1","runId":"r1","metadata":{"elapsed_ms":100}}""")
        assertIs<AgUiFrame.RunFinished>(finished)
        assertEquals(100, finished.metadata?.get("elapsed_ms")?.toString()?.toInt())

        assertEquals(
            AgUiFrame.RunError("round_timeout", "round_timeout"),
            AgUiSseParser.parseBlock("""data: {"type":"RUN_ERROR","message":"round_timeout","code":"round_timeout"}"""),
        )
    }

    @Test
    fun `unknown types and malformed blocks are tolerated`() {
        assertNull(AgUiSseParser.parseBlock("""data: {"type":"STATE_DELTA","delta":[]}""")) // M1 未产出
        assertNull(AgUiSseParser.parseBlock("""data: {not json"""))
        assertNull(AgUiSseParser.parseBlock(""))
        assertNull(AgUiSseParser.parseBlock("""data: {"type":"TEXT_MESSAGE_CONTENT"}""")) // 缺关键字段
        assertNull(AgUiSseParser.parseBlock(": comment"))
    }

    // ---------- AgUiTurnAdapter ----------

    @Test
    fun `assistant text triad maps to text block`() {
        val adapter = AgUiTurnAdapter()
        assertEquals(
            listOf(TurnStreamEvent.TextStart("stream-0")),
            adapter.onFrame(AgUiFrame.TextMessageStart("stream-0", "assistant")),
        )
        assertEquals(
            listOf(TurnStreamEvent.TextDelta("stream-0", "root = ")),
            adapter.onFrame(AgUiFrame.TextMessageContent("stream-0", "root = ")),
        )
        assertEquals(
            listOf(TurnStreamEvent.TextEnd("stream-0")),
            adapter.onFrame(AgUiFrame.TextMessageEnd("stream-0")),
        )
    }

    @Test
    fun `user role messages are skipped`() {
        val adapter = AgUiTurnAdapter()
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.TextMessageStart("evt-1", "user")))
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.TextMessageContent("evt-1", "帮我找照片")))
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.TextMessageEnd("evt-1")))
    }

    @Test
    fun `tool call args accumulate until end`() {
        val adapter = AgUiTurnAdapter()
        assertEquals(
            listOf(TurnStreamEvent.ToolInputStart("act-9", "render_html")),
            adapter.onFrame(AgUiFrame.ToolCallStart("act-9", "render_html")),
        )
        assertEquals(
            listOf(TurnStreamEvent.ToolInputDelta("act-9", "{\"html\":\"<p>")),
            adapter.onFrame(AgUiFrame.ToolCallArgs("act-9", "{\"html\":\"<p>")),
        )
        assertEquals(
            listOf(TurnStreamEvent.ToolInputDelta("act-9", "hi</p>\"}")),
            adapter.onFrame(AgUiFrame.ToolCallArgs("act-9", "hi</p>\"}")),
        )
        assertEquals(
            listOf(TurnStreamEvent.ToolInputAvailable("act-9", "{\"html\":\"<p>hi</p>\"}")),
            adapter.onFrame(AgUiFrame.ToolCallEnd("act-9")),
        )
    }

    @Test
    fun `lifecycle and custom frames produce no turn events`() {
        val adapter = AgUiTurnAdapter()
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.RunStarted("s", "r")))
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.RunFinished("s", "r", null)))
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.RunError("boom", null)))
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.Custom("device_tool_request", null)))
        assertEquals(emptyList(), adapter.onFrame(AgUiFrame.ToolCallResult("m", "act-1", "ok")))
    }
}
