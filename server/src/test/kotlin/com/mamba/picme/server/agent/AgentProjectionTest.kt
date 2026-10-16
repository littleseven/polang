package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 事件流 → LLM 视图投影（ADR-018 决策 1 的 Condenser 最小实现）。
 *
 * M1 规则：
 * - Message 事件按 role 列直映射（system/user/assistant）；
 * - Action/Observation/Finish 不进跨轮视图（当轮工具循环由 Koog 策略内存承载）；
 * - 最新一条 Condensation 的 summary 作为一条 system 消息插在最前（历史压缩的投影点）；
 *   被其覆盖（id <= upToEventId）的 Message 不再重复投影；
 * - 顺序保持事件序。
 */
class AgentProjectionTest {

    private fun ev(
        id: Long,
        role: AgentEventRole,
        payload: AgentEventPayload,
        source: AgentEventSource = AgentEventSource.USER,
    ) = AgentEvent(id, "s1", payload.type(), source, role, null, payload, id)

    @Test
    fun `messages project by role in event order`() {
        val events = listOf(
            ev(1, AgentEventRole.SYSTEM, AgentEventPayload.Message("你是助手")),
            ev(2, AgentEventRole.USER, AgentEventPayload.Message("你好")),
            ev(3, AgentEventRole.ASSISTANT, AgentEventPayload.Message("你好呀"), AgentEventSource.AGENT),
        )
        val view = AgentProjection.project(events)
        assertEquals(
            listOf(
                ProjectedMessage.System("你是助手"),
                ProjectedMessage.User("你好"),
                ProjectedMessage.Assistant("你好呀"),
            ),
            view,
        )
    }

    @Test
    fun `action observation finish events are excluded from cross-turn view`() {
        val events = listOf(
            ev(2, AgentEventRole.USER, AgentEventPayload.Message("几点了")),
            ev(3, AgentEventRole.ASSISTANT, AgentEventPayload.Action("server_time", "{}", false), AgentEventSource.AGENT),
            ev(4, AgentEventRole.TOOL, AgentEventPayload.Observation(3, "12:00"), AgentEventSource.ENVIRONMENT),
            ev(5, AgentEventRole.ASSISTANT, AgentEventPayload.Message("12点"), AgentEventSource.AGENT),
            ev(6, AgentEventRole.ASSISTANT, AgentEventPayload.Finish("done"), AgentEventSource.AGENT),
        )
        val view = AgentProjection.project(events)
        assertEquals(
            listOf(
                ProjectedMessage.User("几点了"),
                ProjectedMessage.Assistant("12点"),
            ),
            view,
        )
    }

    @Test
    fun `latest condensation becomes leading system message and covers prior messages`() {
        val events = listOf(
            ev(1, AgentEventRole.SYSTEM, AgentEventPayload.Message("你是助手")),
            ev(2, AgentEventRole.USER, AgentEventPayload.Message("旧问题")),
            ev(3, AgentEventRole.ASSISTANT, AgentEventPayload.Message("旧回答"), AgentEventSource.AGENT),
            ev(4, AgentEventRole.SYSTEM, AgentEventPayload.Condensation("前文摘要：聊了旧问题", upToEventId = 3), AgentEventSource.AGENT),
            ev(5, AgentEventRole.USER, AgentEventPayload.Message("新问题")),
        )
        val view = AgentProjection.project(events)
        assertEquals(
            listOf(
                ProjectedMessage.System("你是助手"),
                ProjectedMessage.System("前文摘要：聊了旧问题"),
                ProjectedMessage.User("新问题"),
            ),
            view,
        )
    }

    @Test
    fun `only the latest condensation projects`() {
        val events = listOf(
            ev(1, AgentEventRole.USER, AgentEventPayload.Message("m1")),
            ev(2, AgentEventRole.SYSTEM, AgentEventPayload.Condensation("摘要A", upToEventId = 1), AgentEventSource.AGENT),
            ev(3, AgentEventRole.USER, AgentEventPayload.Message("m2")),
            ev(4, AgentEventRole.SYSTEM, AgentEventPayload.Condensation("摘要B", upToEventId = 3), AgentEventSource.AGENT),
            ev(5, AgentEventRole.USER, AgentEventPayload.Message("m3")),
        )
        val view = AgentProjection.project(events)
        assertEquals(
            listOf(
                ProjectedMessage.System("摘要B"),
                ProjectedMessage.User("m3"),
            ),
            view,
        )
    }

    @Test
    fun `empty history projects empty`() {
        assertEquals(emptyList<ProjectedMessage>(), AgentProjection.project(emptyList()))
    }
}
