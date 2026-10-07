package com.mamba.picme.server.ratelimit

import io.ktor.server.application.ApplicationCall
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory sliding-window rate limiter (per IP).
 * Not suitable for multi-instance deployment without external store.
 */
class RateLimiter(
    private val limitProvider: () -> Int,
    private val windowMs: Long,
) {
    constructor(maxRequests: Int, windowMs: Long = 60_000L) : this({ maxRequests }, windowMs)

    private val log = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = log.computeIfAbsent(ip) { mutableListOf() }
        synchronized(entry) {
            prune(entry, now)
            if (entry.size >= limitProvider()) return false
            entry.add(now)
            return true
        }
    }

    /** 只检查不记录——成功才计费的场景（browser 会话配额）先 peek 后 allow。 */
    fun peek(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = log.computeIfAbsent(ip) { mutableListOf() }
        synchronized(entry) {
            prune(entry, now)
            return entry.size < limitProvider()
        }
    }

    /** 必须在 synchronized(entry) 内调用，保证与调用方检查/写入同一把锁。 */
    private fun prune(entry: MutableList<Long>, now: Long) {
        entry.removeAll { it <= now - windowMs }
    }
}
