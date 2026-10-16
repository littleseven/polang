package com.mamba.picme.server.agent

import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 事件流仓储（ADR-018 决策 1）：只追加、三维标注、pending action 不变式。
 */
class AgentEventRepositoryTest {

    @Before
    fun setUp() {
        TestDb.init(AgentSessions, AgentEvents)
    }

    private suspend fun newSession(tokenHash: String = "hashA"): AgentSession =
        AgentSessionRepository.create(tokenHash, AgentChannelKind.GATEWAY, "m")

    @Test
    fun `append assigns monotonically increasing ids and echoes dimensions`() = runBlocking {
        val s = newSession()
        val e1 = AgentEventRepository.append(
            s.id, AgentEventSource.USER, AgentEventRole.USER,
            payload = AgentEventPayload.Message("hi"),
        )
        val e2 = AgentEventRepository.append(
            s.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT, parentId = e1.id,
            payload = AgentEventPayload.Message("hello"),
        )
        assertTrue(e2.id > e1.id)
        assertEquals(AgentEventType.MESSAGE, e1.type)
        assertEquals(AgentEventSource.USER, e1.source)
        assertEquals(AgentEventRole.ASSISTANT, e2.role)
        assertEquals(e1.id, e2.parentId)
        assertEquals(s.id, e1.sessionId)
    }

    @Test
    fun `listBySession returns events in id order with afterId and limit`() = runBlocking {
        val s = newSession()
        val ids = (1..5).map {
            AgentEventRepository.append(
                s.id, AgentEventSource.USER, AgentEventRole.USER,
                payload = AgentEventPayload.Message("m$it"),
            ).id
        }
        assertEquals(ids, AgentEventRepository.listBySession(s.id).map { it.id })
        assertEquals(ids.drop(2), AgentEventRepository.listBySession(s.id, afterId = ids[1]).map { it.id })
        assertEquals(ids.take(2), AgentEventRepository.listBySession(s.id, limit = 2).map { it.id })
        // 跨会话隔离
        val other = newSession("hashB")
        assertTrue(AgentEventRepository.listBySession(other.id).isEmpty())
    }

    @Test
    fun `payload survives append and read back typed`() = runBlocking {
        val s = newSession()
        AgentEventRepository.append(
            s.id, AgentEventSource.AGENT, AgentEventRole.TOOL,
            payload = AgentEventPayload.Observation(actionEventId = 99L, content = "ok", revert = false),
        )
        val back = AgentEventRepository.listBySession(s.id).single()
        assertEquals(AgentEventType.OBSERVATION, back.type)
        assertEquals(AgentEventPayload.Observation(99L, "ok", false), back.payload)
    }

    @Test
    fun `pendingActions returns actions without matching observation`() = runBlocking {
        val s = newSession()
        val a1 = AgentEventRepository.append(
            s.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action("gallery.search", "{}", reversible = false),
        )
        val a2 = AgentEventRepository.append(
            s.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action("media.trash", "{}", reversible = true),
        )
        // a1 有 Observation 闭环，a2 没有 → pending = [a2]
        AgentEventRepository.append(
            s.id, AgentEventSource.ENVIRONMENT, AgentEventRole.TOOL,
            payload = AgentEventPayload.Observation(actionEventId = a1.id, content = "done"),
        )
        val pending = AgentEventRepository.pendingActions(s.id)
        assertEquals(listOf(a2.id), pending.map { it.id })
    }

    @Test
    fun `pendingActions ignores observations from other sessions`() = runBlocking {
        val s1 = newSession()
        val s2 = newSession("hashB")
        val actionInS1 = AgentEventRepository.append(
            s1.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action("t", "{}", reversible = false),
        )
        // s2 里的 Observation 指向 s1 的 action id（伪造），不得清除 s1 的 pending
        AgentEventRepository.append(
            s2.id, AgentEventSource.ENVIRONMENT, AgentEventRole.TOOL,
            payload = AgentEventPayload.Observation(actionEventId = actionInS1.id, content = "x"),
        )
        assertEquals(listOf(actionInS1.id), AgentEventRepository.pendingActions(s1.id).map { it.id })
        assertTrue(AgentEventRepository.pendingActions(s2.id).isEmpty())
    }

    @Test
    fun `pendingActions keeps append order for crash recovery replay`() = runBlocking {
        val s = newSession()
        val a1 = AgentEventRepository.append(
            s.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action("t1", "{}", reversible = false),
        )
        val a2 = AgentEventRepository.append(
            s.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action("t2", "{}", reversible = false),
        )
        assertEquals(listOf(a1.id, a2.id), AgentEventRepository.pendingActions(s.id).map { it.id })
    }
}
