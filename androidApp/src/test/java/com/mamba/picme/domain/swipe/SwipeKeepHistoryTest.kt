package com.mamba.picme.domain.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeKeepHistoryTest {

    private val now = 10_000_000_000_000L

    @Test
    fun `encode decode roundtrip`() {
        val entry = SwipeKeepHistory.encode("content://media/1", now)
        assertEquals(setOf("content://media/1"), SwipeKeepHistory.activeUris(setOf(entry)))
        assertEquals(setOf(entry), SwipeKeepHistory.activeEntries(setOf(entry), now))
    }

    @Test
    fun `entries within ttl stay active`() {
        val entry = SwipeKeepHistory.encode("a", now - SwipeKeepHistory.TTL_MS + 1)
        assertEquals(setOf(entry), SwipeKeepHistory.activeEntries(setOf(entry), now))
    }

    @Test
    fun `entries at ttl boundary expire`() {
        val entry = SwipeKeepHistory.encode("a", now - SwipeKeepHistory.TTL_MS)
        assertTrue(SwipeKeepHistory.activeEntries(setOf(entry), now).isEmpty())
    }

    @Test
    fun `dirty entries without timestamp are dropped`() {
        assertTrue(SwipeKeepHistory.activeEntries(setOf("no-separator", "tail|"), now).isEmpty())
        assertTrue(SwipeKeepHistory.activeEntries(setOf("a|not-a-number"), now).isEmpty())
    }

    @Test
    fun `activeUris strips timestamp suffix`() {
        val entries = setOf(
            SwipeKeepHistory.encode("a", now),
            SwipeKeepHistory.encode("b|c", now), // uri 自身含分隔符：取最后一段为时间戳
        )
        assertEquals(setOf("a", "b|c"), SwipeKeepHistory.activeUris(entries))
        assertEquals(entries, SwipeKeepHistory.activeEntries(entries, now))
    }
}
