package com.mamba.picme.server.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * per-user 并发=1 登记（spec §7）：内存态租约，重启即清；
 * 租约过期兜底（App 崩溃/未 close 也能自愈），与 RateLimiter 同样单实例约束。
 * 不变式：默认 15min 必须 > bridge 会话 hardCap（10min）——act 持续 touch 的活跃会话
 * 最长活到 hardCap，短于此的租约会被 sweep 提前清扫形成双活窗口；
 * App 崩后的快速解锁由 busy 自愈探测（GET /session/{id}/status）承担，不依赖短租约。
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

    /** 现存租约绑定的 sessionId（未 bind 或已释放返回 null）；busy 自愈探测用。 */
    fun leaseSessionId(owner: String): String? = leases[owner]?.sessionId

    private fun sweep(now: Long) {
        leases.entries.removeIf { now - it.value.acquiredAt > leaseMs }
    }
}
