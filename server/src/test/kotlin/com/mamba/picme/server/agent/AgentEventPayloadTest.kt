package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件载荷序列化（ADR-018 决策 1）：payload_json 往返无损，
 * 且每个载荷类型映射到唯一事件类型 wire 值。
 */
class AgentEventPayloadTest {

    @Test
    fun `message payload round-trips`() {
        val p = AgentEventPayload.Message("你好，云端 Agent")
        val decoded = AgentEventPayloadCodec.decode(AgentEventPayloadCodec.encode(p))
        assertEquals(p, decoded)
    }

    @Test
    fun `action payload round-trips with reversible flag`() {
        val p = AgentEventPayload.Action(tool = "gallery.search", argsJson = """{"q":"猫"}""", reversible = false)
        val decoded = AgentEventPayloadCodec.decode(AgentEventPayloadCodec.encode(p))
        assertEquals(p, decoded)
    }

    @Test
    fun `observation payload round-trips with actionEventId and revert mark`() {
        val p = AgentEventPayload.Observation(actionEventId = 42L, content = "找到 3 张", revert = true)
        val decoded = AgentEventPayloadCodec.decode(AgentEventPayloadCodec.encode(p))
        assertEquals(p, decoded)
        assertEquals(42L, (decoded as AgentEventPayload.Observation).actionEventId)
    }

    @Test
    fun `condensation payload round-trips`() {
        val p = AgentEventPayload.Condensation(summary = "前文摘要", upToEventId = 7L)
        assertEquals(p, AgentEventPayloadCodec.decode(AgentEventPayloadCodec.encode(p)))
    }

    @Test
    fun `finish payload round-trips`() {
        val p = AgentEventPayload.Finish(reason = "done")
        assertEquals(p, AgentEventPayloadCodec.decode(AgentEventPayloadCodec.encode(p)))
    }

    @Test
    fun `html card payload round-trips with chinese quotes and newlines`() {
        val html = "<html><body><h1>相册周报 \"精选\" '单引号'</h1>\n<p>换行与&nbsp;实体</p></body></html>"
        val p = AgentEventPayload.HtmlCard(html = html, summary = "相册周报卡")
        val decoded = AgentEventPayloadCodec.decode(AgentEventPayloadCodec.encode(p))
        assertEquals(p, decoded)
        assertEquals(html, (decoded as AgentEventPayload.HtmlCard).html)
    }

    @Test
    fun `payload type maps to distinct event type wire values`() {
        val wires = listOf(
            AgentEventPayload.Message("").type(),
            AgentEventPayload.Action("t", "{}", false).type(),
            AgentEventPayload.Observation(1L, "").type(),
            AgentEventPayload.Condensation("", 0L).type(),
            AgentEventPayload.Finish("").type(),
            AgentEventPayload.HtmlCard("", "").type(),
        ).map { it.wire }
        assertEquals(6, wires.toSet().size)
        assertEquals(
            setOf("message", "action", "observation", "condensation", "finish", "html_card"),
            wires.toSet(),
        )
    }

    @Test
    fun `decode unknown payload throws`() {
        try {
            AgentEventPayloadCodec.decode("""{"type":"bogus"}""")
            org.junit.Assert.fail("should throw on unknown payload")
        } catch (e: Exception) {
            assertTrue(true)
        }
    }
}
