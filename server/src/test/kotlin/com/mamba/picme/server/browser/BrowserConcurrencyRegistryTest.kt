package com.mamba.picme.server.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserConcurrencyRegistryTest {

    @Test
    fun `one active session per user`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
        assertFalse(reg.tryAcquire("u1", now = 2_000L))  // 同用户冲突
        assertTrue(reg.tryAcquire("u2", now = 2_000L))   // 不同用户互不影响
        reg.release("u1")
        assertTrue(reg.tryAcquire("u1", now = 3_000L))
    }

    @Test
    fun `stale lease is swept`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
        // 61s 后租约过期，崩溃未释放也能再获取
        assertTrue(reg.tryAcquire("u1", now = 62_000L))
    }

    @Test
    fun `bind and activeCount`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.tryAcquire("u1", now = 1_000L)
        reg.bind("u1", "sess-1")
        assertEquals(1, reg.activeCount())
        reg.release("u1")
        assertEquals(0, reg.activeCount())
    }
}
