package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentRoundGate] 租约语义单元测试：独占占用、配对释放、释放后可再占用。
 */
class AgentRoundGateTest {

    @Test
    fun `same user acquires once, second acquire rejected until release`() {
        val gate = AgentRoundGate()
        assertTrue(gate.tryAcquire("hashA"))
        assertFalse(gate.tryAcquire("hashA"))
        assertEquals(1, gate.activeCount())

        gate.release("hashA")
        assertEquals(0, gate.activeCount())
        assertTrue(gate.tryAcquire("hashA"))
    }

    @Test
    fun `different users are independent`() {
        val gate = AgentRoundGate()
        assertTrue(gate.tryAcquire("hashA"))
        assertTrue(gate.tryAcquire("hashB"))
        assertEquals(2, gate.activeCount())

        gate.release("hashA")
        assertFalse(gate.tryAcquire("hashB"))
        assertTrue(gate.tryAcquire("hashA"))
    }

    @Test
    fun `release unknown lease is a no-op`() {
        val gate = AgentRoundGate()
        gate.release("nobody")
        assertEquals(0, gate.activeCount())
    }
}
