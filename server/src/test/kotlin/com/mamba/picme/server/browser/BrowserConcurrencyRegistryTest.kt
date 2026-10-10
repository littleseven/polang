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
        reg.release("u1")                                 // 未 bind，走 null 清理路径
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
        reg.release("u1", "sess-1")
        assertEquals(0, reg.activeCount())
    }

    @Test
    fun `release on non-existent owner is no-op`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.release("ghost")            // 不抛异常
        reg.release("ghost", "sess-x")  // 条件路径同样不抛
        assertEquals(0, reg.activeCount())
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
    }

    @Test
    fun `conditional release only evicts matching session`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.tryAcquire("u1", now = 1_000L)
        reg.bind("u1", "sess-new")
        reg.release("u1", "sess-stale") // 旧请求的出错路径 release，不匹配则保留
        assertFalse(reg.tryAcquire("u1", now = 2_000L))
        reg.release("u1", "sess-new")
        assertTrue(reg.tryAcquire("u1", now = 3_000L))
    }

    @Test
    fun `bind refreshes lease clock`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        reg.tryAcquire("u1", now = 1_000L)
        reg.bind("u1", "sess-1") // bind 内部用 System.currentTimeMillis() 刷新租约时钟
        // 以 bind 时刻为基准留 1s 余量：59s 内仍占坑（若时钟未刷新，按 1000L 起算早被清扫），61s 后被清扫
        val bindNow = System.currentTimeMillis()
        assertFalse(reg.tryAcquire("u1", now = bindNow + 59_000L))
        assertTrue(reg.tryAcquire("u1", now = bindNow + 61_000L))
    }

    @Test
    fun `sweep evicts expired only, keeps fresh`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
        assertTrue(reg.tryAcquire("u2", now = 50_000L))
        // 62s：u1 过期被清扫，u2（50s 获取，仅过 12s）仍占坑
        assertTrue(reg.tryAcquire("u1", now = 62_000L))
        assertFalse(reg.tryAcquire("u2", now = 62_000L))
        assertTrue(reg.tryAcquire("u3", now = 62_000L)) // 清扫正常，第三人可进
    }

    @Test
    fun `default lease outlives bridge hardCap 10min`() {
        // 不变式：默认租约必须 > bridge 会话 hardCap（10min），否则活跃会话会形成双活窗口
        val reg = BrowserConcurrencyRegistry()
        assertTrue(reg.tryAcquire("u1", now = 1_000L))
        assertFalse(reg.tryAcquire("u1", now = 1_000L + 10 * 60_000L)) // hardCap 时点租约仍在
        assertTrue(reg.tryAcquire("u1", now = 1_000L + 16 * 60_000L))  // 15min 后才过期
    }

    @Test
    fun `leaseSessionId returns bound session or null`() {
        val reg = BrowserConcurrencyRegistry(leaseMs = 60_000L)
        assertEquals(null, reg.leaseSessionId("ghost"))        // 无租约
        reg.tryAcquire("u1", now = 1_000L)
        assertEquals(null, reg.leaseSessionId("u1"))           // 租约未 bind（极端中间态）
        reg.bind("u1", "sess-1")
        assertEquals("sess-1", reg.leaseSessionId("u1"))
        reg.release("u1", "sess-1")
        assertEquals(null, reg.leaseSessionId("u1"))           // 释放后清空
    }
}
