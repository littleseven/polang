package com.mamba.picme.server.ratelimit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RateLimiterTest {

    @Test
    fun `peek does not consume quota`() {
        val limiter = RateLimiter(1, 60_000L)
        assertTrue(limiter.peek("u"))
        assertTrue(limiter.peek("u"))
        assertTrue(limiter.allow("u"))
        assertFalse(limiter.peek("u"))
        assertFalse(limiter.allow("u"))
    }

    @Test
    fun `peek with zero limit rejects`() {
        val limiter = RateLimiter(0, 60_000L)
        assertFalse(limiter.peek("u"))
        assertFalse(limiter.allow("u"))
    }

    @Test
    fun `peek respects window expiry`() {
        val limiter = RateLimiter(1, 60_000L)
        assertTrue(limiter.allow("u", now = 1_000L))
        assertFalse(limiter.peek("u", now = 2_000L))
        assertTrue(limiter.peek("u", now = 62_000L))
        assertTrue(limiter.allow("u", now = 62_000L))
    }
}
