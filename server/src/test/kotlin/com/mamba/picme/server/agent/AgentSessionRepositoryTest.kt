package com.mamba.picme.server.agent

import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 会话元数据仓储（ADR-018 决策 1：agent_session 生命周期状态机）。
 */
class AgentSessionRepositoryTest {

    @Before
    fun setUp() {
        TestDb.init(AgentSessions, AgentEvents)
    }

    @Test
    fun `create returns running session with unique id`() = runBlocking {
        val a = AgentSessionRepository.create("hashA", AgentChannelKind.GATEWAY, "deepseek-chat")
        val b = AgentSessionRepository.create("hashA", AgentChannelKind.BYOK, "kimi-k2.6")
        assertEquals(AgentSessionStatus.RUNNING, a.status)
        assertEquals(AgentChannelKind.GATEWAY, a.channelKind)
        assertEquals("deepseek-chat", a.model)
        assertNotEquals(a.id, b.id)
        assertTrue(a.id.isNotBlank())
        assertNull(a.finishedAt)
    }

    @Test
    fun `find returns session by id and null for unknown`() = runBlocking {
        val created = AgentSessionRepository.create("hashA", AgentChannelKind.GATEWAY, "m")
        val found = AgentSessionRepository.find(created.id)
        assertEquals(created.id, found?.id)
        assertEquals("hashA", found?.tokenHash)
        assertNull(AgentSessionRepository.find("no-such-id"))
    }

    @Test
    fun `updateStatus to FINISHED stamps finishedAt`() = runBlocking {
        val s = AgentSessionRepository.create("hashA", AgentChannelKind.GATEWAY, "m")
        assertTrue(AgentSessionRepository.updateStatus(s.id, AgentSessionStatus.FINISHED))
        val after = AgentSessionRepository.find(s.id)!!
        assertEquals(AgentSessionStatus.FINISHED, after.status)
        assertTrue(after.finishedAt != null)
        assertTrue(after.updatedAt >= after.createdAt)
    }

    @Test
    fun `updateStatus to PAUSED does not stamp finishedAt`() = runBlocking {
        val s = AgentSessionRepository.create("hashA", AgentChannelKind.GATEWAY, "m")
        AgentSessionRepository.updateStatus(s.id, AgentSessionStatus.PAUSED)
        val after = AgentSessionRepository.find(s.id)!!
        assertEquals(AgentSessionStatus.PAUSED, after.status)
        assertNull(after.finishedAt)
    }

    @Test
    fun `updateStatus returns false for unknown session`() = runBlocking {
        assertEquals(false, AgentSessionRepository.updateStatus("ghost", AgentSessionStatus.ERROR))
    }

    @Test
    fun `listByOwner returns only own sessions newest first`() = runBlocking {
        val s1 = AgentSessionRepository.create("hashA", AgentChannelKind.GATEWAY, "m")
        AgentSessionRepository.create("hashB", AgentChannelKind.GATEWAY, "m")
        val s3 = AgentSessionRepository.create("hashA", AgentChannelKind.BYOK, "m")
        val mine = AgentSessionRepository.listByOwner("hashA")
        assertEquals(listOf(s3.id, s1.id), mine.map { it.id })
    }
}
