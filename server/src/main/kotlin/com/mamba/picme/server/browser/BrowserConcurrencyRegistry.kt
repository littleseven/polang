package com.mamba.picme.server.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * per-user 并发=1 登记（spec §7）：内存态租约，重启即清；
 * 租约过期兜底（App 崩溃/未 close 也能自愈），与 RateLimiter 同样单实例约束。
 */
class BrowserConcurrencyRegistry(private val leaseMs: Long = 15 * 60_000L) {

    private data class Lease(val sessionId: String?, val acquiredAt: Long)

    private val leases = ConcurrentHashMap<String, Lease>()

    @Synchronized
    fun tryAcquire(owner: String, now: Long = System.currentTimeMillis()): Boolean {
        sweep(now)
        return leases.putIfAbsent(owner, Lease(null, now)) == null
    }

    fun bind(owner: String, sessionId: String) {
        leases[owner] = Lease(sessionId, System.currentTimeMillis())
    }

    /**
     * 条件释放：sessionId 为 null（清理路径）或与现存租约的 sessionId 匹配时才移除；
     * 防止过期请求的出错路径 release 误删刚被重新获取的新租约。
     */
    fun release(owner: String, sessionId: String? = null) {
        leases.computeIfPresent(owner) { _, lease ->
            if (sessionId == null || lease.sessionId == sessionId) null else lease
        }
    }

    fun activeCount(): Int = leases.size

    private fun sweep(now: Long) {
        leases.entries.removeIf { now - it.value.acquiredAt > leaseMs }
    }
}
