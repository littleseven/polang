package com.mamba.picme.server.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

/**
 * AG-UI 映射表测试（spec《2026-11-28-ag-ui-migration-design》§4 SSOT 钉住）：
 * 差分三态 / 终结去重三分支 / ACTION-OBSERVATION 配对锚 / CUSTOM 桥 / RUN 边界与收尾。
 */
class AgUiFrameSinkTest {

    private fun newSink(): Pair<AgUiFrameSink, StringWriter> {
        val w = StringWriter()
        return AgUiFrameSink(w, threadId = "sess-1", runId = "run-abc") to w
    }

    private fun framesOf(w: StringWriter): List<JsonObject> =
        w.toString().split("\n\n").filter { it.isNotBlank() }.map { block ->
            Json.parseToJsonElement(block.removePrefix("data: ").trim()).jsonObject
        }

    private fun typeOf(f: JsonObject) = f["type"]!!.jsonPrimitive.content

    private fun evt(id: Long, role: AgentEventRole, payload: AgentEventPayload) = AgentEvent(
        id = id, sessionId = "sess-1", type = payload.type(), source = AgentEventSource.AGENT,
        role = role, parentId = null, payload = payload, createdAt = 0L,
    )

    @Test
    fun `turn start emits RUN_STARTED with thread and run ids`() = runTest {
        val (sink, w) = newSink()
        sink.onTurnStart()
        val f = framesOf(w).single()
        assertEquals("RUN_STARTED", typeOf(f))
        assertEquals("sess-1", f["threadId"]!!.jsonPrimitive.content)
        assertEquals("run-abc", f["runId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `program delta first prefix starts block and extension emits diff`() = runTest {
        val (sink, w) = newSink()
        sink.onProgramDelta("root = Mark")
        sink.onProgramDelta("root = Markdown")
        val frames = framesOf(w)
        assertEquals(listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT"), frames.map(::typeOf))
        assertEquals("stream-0", frames[0]["messageId"]!!.jsonPrimitive.content)
        assertEquals("assistant", frames[0]["role"]!!.jsonPrimitive.content)
        assertEquals("root = Mark", frames[1]["delta"]!!.jsonPrimitive.content)
        assertEquals("down", frames[2]["delta"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non extending prefix closes old block and starts new one`() = runTest {
        val (sink, w) = newSink()
        sink.onProgramDelta("root = Card")
        sink.onProgramDelta("// summary: 新轮") // 轮重置：非扩展
        val frames = framesOf(w)
        assertEquals(
            listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT"),
            frames.map(::typeOf),
        )
        assertEquals("stream-0", frames[2]["messageId"]!!.jsonPrimitive.content)
        assertEquals("stream-1", frames[3]["messageId"]!!.jsonPrimitive.content)
        assertEquals("// summary: 新轮", frames[4]["delta"]!!.jsonPrimitive.content)
    }

    @Test
    fun `action closes open text and emits tool call triad with full args`() = runTest {
        val (sink, w) = newSink()
        sink.onProgramDelta("root = X")
        sink.onAgentEvent(evt(41, AgentEventRole.ASSISTANT, AgentEventPayload.Action("search_media", "{\"query\":\"海边\"}", reversible = false)))
        val frames = framesOf(w)
        assertEquals(
            listOf(
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END",
                "TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END",
            ),
            frames.map(::typeOf),
        )
        assertEquals("act-41", frames[3]["toolCallId"]!!.jsonPrimitive.content)
        assertEquals("search_media", frames[3]["toolCallName"]!!.jsonPrimitive.content)
        assertEquals("{\"query\":\"海边\"}", frames[4]["delta"]!!.jsonPrimitive.content)
        assertEquals("act-41", frames[5]["toolCallId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `observation maps to tool call result paired by action event id`() = runTest {
        val (sink, w) = newSink()
        sink.onAgentEvent(evt(42, AgentEventRole.TOOL, AgentEventPayload.Observation(actionEventId = 41, content = "找到 87 张")))
        val f = framesOf(w).single()
        assertEquals("TOOL_CALL_RESULT", typeOf(f))
        assertEquals("act-41", f["toolCallId"]!!.jsonPrimitive.content)
        assertEquals("act-41-result", f["messageId"]!!.jsonPrimitive.content)
        assertEquals("找到 87 张", f["content"]!!.jsonPrimitive.content)
        assertEquals("tool", f["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `final message equal to streamed accumulation only closes block`() = runTest {
        val (sink, w) = newSink()
        sink.onProgramDelta("root = Markdown(\"hi\")")
        sink.onAgentEvent(evt(50, AgentEventRole.ASSISTANT, AgentEventPayload.Message("root = Markdown(\"hi\")")))
        val frames = framesOf(w)
        assertEquals(listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END"), frames.map(::typeOf))
        assertEquals("stream-0", frames[2]["messageId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `final message rewritten by fallback emits fresh triad after closing stream block`() = runTest {
        val (sink, w) = newSink()
        sink.onProgramDelta("这不是程序")
        sink.onAgentEvent(evt(51, AgentEventRole.ASSISTANT, AgentEventPayload.Message("root = Markdown(\"这不是程序\")")))
        val frames = framesOf(w)
        assertEquals(
            listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END"),
            frames.map(::typeOf),
        )
        assertEquals("stream-0", frames[2]["messageId"]!!.jsonPrimitive.content)
        assertEquals("evt-51", frames[3]["messageId"]!!.jsonPrimitive.content)
        assertEquals("root = Markdown(\"这不是程序\")", frames[4]["delta"]!!.jsonPrimitive.content)
    }

    @Test
    fun `final message without streaming emits plain triad`() = runTest {
        val (sink, w) = newSink()
        sink.onAgentEvent(evt(52, AgentEventRole.ASSISTANT, AgentEventPayload.Message("root = Markdown(\"x\")")))
        val frames = framesOf(w)
        assertEquals(listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END"), frames.map(::typeOf))
        assertEquals("evt-52", frames[0]["messageId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user message emits role user triad`() = runTest {
        val (sink, w) = newSink()
        sink.onAgentEvent(evt(10, AgentEventRole.USER, AgentEventPayload.Message("帮我找照片")))
        val frames = framesOf(w)
        assertEquals(listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END"), frames.map(::typeOf))
        assertEquals("user", frames[0]["role"]!!.jsonPrimitive.content)
        assertEquals("evt-10", frames[0]["messageId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `device tool request maps to custom event`() = runTest {
        val (sink, w) = newSink()
        sink.onToolRequest(ToolRequestFrame("tr_ab12", "search_media", kotlinx.serialization.json.buildJsonObject { }, 120_000L))
        val f = framesOf(w).single()
        assertEquals("CUSTOM", typeOf(f))
        assertEquals("device_tool_request", f["name"]!!.jsonPrimitive.content)
        val value = f["value"]!!.jsonObject
        assertEquals("tr_ab12", value["request_id"]!!.jsonPrimitive.content)
        assertEquals("search_media", value["tool"]!!.jsonPrimitive.content)
        assertEquals(120_000L, value["timeout_ms"]!!.jsonPrimitive.long)
    }

    @Test
    fun `done emits run finished with usage metadata`() = runTest {
        val (sink, w) = newSink()
        val usage = TurnUsage()
        usage.add(100, 20)
        usage.finish(3456)
        sink.onDone(usage)
        val f = framesOf(w).single()
        assertEquals("RUN_FINISHED", typeOf(f))
        assertEquals("sess-1", f["threadId"]!!.jsonPrimitive.content)
        val meta = f["metadata"]!!.jsonObject
        assertEquals(3456L, meta["elapsed_ms"]!!.jsonPrimitive.long)
        assertEquals(100L, meta["prompt_tokens"]!!.jsonPrimitive.long)
        assertEquals(20L, meta["completion_tokens"]!!.jsonPrimitive.long)
        assertEquals(com.mamba.picme.domain.chat.openui.OpenUiContract.LANG_VERSION.toLong(), meta["openui_lang_version"]!!.jsonPrimitive.long)
    }

    @Test
    fun `error emits run error with code and closes open block`() = runTest {
        val (sink, w) = newSink()
        sink.onProgramDelta("root = X")
        sink.onError("round_timeout", "round_timeout")
        val frames = framesOf(w)
        assertEquals(listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_ERROR"), frames.map(::typeOf))
        assertEquals("round_timeout", frames[3]["message"]!!.jsonPrimitive.content)
        assertEquals("round_timeout", frames[3]["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `internal payloads produce no frames`() = runTest {
        val (sink, w) = newSink()
        sink.onAgentEvent(evt(60, AgentEventRole.SYSTEM, AgentEventPayload.Condensation("摘要", 59)))
        sink.onAgentEvent(evt(61, AgentEventRole.ASSISTANT, AgentEventPayload.HtmlCard("<html/>", "卡")))
        sink.onAgentEvent(evt(62, AgentEventRole.SYSTEM, AgentEventPayload.Finish("done")))
        assertEquals(emptyList<JsonObject>(), framesOf(w))
    }
}
