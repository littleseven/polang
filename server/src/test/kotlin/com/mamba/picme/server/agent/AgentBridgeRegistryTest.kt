package com.mamba.picme.server.agent

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBridgeRegistryTest {

    @Test
    fun `register then complete delivers result and removes entry`() = runTest {
        val registry = AgentBridgeRegistry()
        val deferred = registry.register("s1", "tr_aaa")
        assertEquals(1, registry.pendingCount())
        assertTrue(registry.complete("tr_aaa", "找到 3 张"))
        assertEquals("找到 3 张", deferred.await())
        assertEquals(0, registry.pendingCount()) // invokeOnCompletion 自动摘除
    }

    @Test
    fun `complete unknown requestId returns false`() {
        val registry = AgentBridgeRegistry()
        assertFalse(registry.complete("tr_ghost", "x"))
    }

    @Test
    fun `double complete returns false on second`() = runTest {
        val registry = AgentBridgeRegistry()
        registry.register("s1", "tr_aaa")
        assertTrue(registry.complete("tr_aaa", "first"))
        assertFalse(registry.complete("tr_aaa", "second"))
    }

    @Test
    fun `failAll completes session pendings with error and leaves others`() = runTest {
        val registry = AgentBridgeRegistry()
        val d1 = registry.register("s1", "tr_a")
        val d2 = registry.register("s2", "tr_b")
        registry.failAll("s1", "connection_closed")
        assertEquals("error: connection_closed", d1.await())
        assertFalse(d2.isCompleted)
        assertEquals(1, registry.pendingCount())
    }

    @Test
    fun `cancelled deferred is removed from registry`() = runTest {
        val registry = AgentBridgeRegistry()
        val d = registry.register("s1", "tr_c")
        val job = launch { d.await() }
        job.cancel()
        d.cancel()
        assertEquals(0, registry.pendingCount())
    }
}
