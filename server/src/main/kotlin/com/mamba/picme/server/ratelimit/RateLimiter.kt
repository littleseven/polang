package com.mamba.picme.server.ratelimit

import io.ktor.server.application.ApplicationCall
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory sliding-window rate limiter (per IP).
 * Not suitable for multi-instance deployment without external store.
 */
class RateLimiter private constructor(
    private val limitProvider: () -> Int,
    private val windowMs: Long,
) {
    constructor(maxRequests: Int, windowMs: Long = 60_000L) : this({ maxRequests }, windowMs)

    private val log = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val windowStart = now - windowMs
        val entry = log.computeIfAbsent(ip) { mutableListOf() }
        synchronized(entry) {
            entry.removeAll { it <= windowStart }
            if (entry.size >= limitProvider()) return false
            entry.add(now)
            return true
        }
    }

    /** 只检查不记录——成功才计费的场景（browser 会话配额）先 peek 后 allow。 */
    fun peek(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val windowStart = now - windowMs
        val entry = log.computeIfAbsent(ip) { mutableListOf() }
        synchronized(entry) {
            entry.removeAll { it <= windowStart }
            return entry.size < limitProvider()
        }
    }
}
