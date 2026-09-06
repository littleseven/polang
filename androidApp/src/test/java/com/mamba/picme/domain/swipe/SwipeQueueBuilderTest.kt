package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeQueueBuilderTest {

    private fun item(
        uri: String, isVideo: Boolean = false, captureDate: Long = 0L,
        relativePath: String? = "DCIM/Camera/",
        hasFace: Boolean = false, aestheticScore: Float? = null, faceQualityScore: Float? = null,
    ) = OrganizeItem(uri, isVideo, captureDate, 1_000_000, relativePath, null, 12_000_000, null, hasFace, aestheticScore, faceQualityScore)

    @Test
    fun `buckets ordered screenshot then blurry then portrait then recent`() {
        val queue = SwipeQueueBuilder.build(listOf(
            item("recent", captureDate = 9),
            item("portrait", hasFace = true, faceQualityScore = 0.1f, captureDate = 8),
            item("blurry", aestheticScore = 1.5f, captureDate = 7),
            item("shot", relativePath = "Pictures/Screenshots/", captureDate = 6),
        ))
        assertEquals(listOf("shot", "blurry", "portrait", "recent"), queue.map { c -> c.uri })
        assertEquals(SwipeReason.SCREENSHOT, queue[0].reason)
        assertEquals(SwipeReason.RECENT, queue[3].reason)
    }

    @Test
    fun `item lands in first matching bucket only`() {
        val queue = SwipeQueueBuilder.build(listOf(
            item("both", relativePath = "Pictures/Screenshots/", aestheticScore = 1.0f),
        ))
        assertEquals(1, queue.size)
        assertEquals(SwipeReason.SCREENSHOT, queue[0].reason)
    }

    @Test
    fun `videos never enqueued`() {
        val queue = SwipeQueueBuilder.build(listOf(item("v", isVideo = true, captureDate = 5)))
        assertEquals(emptyList<SwipeCandidate>(), queue)
    }

    @Test
    fun `within bucket newest first`() {
        val queue = SwipeQueueBuilder.build(listOf(
            item("old", captureDate = 1), item("new", captureDate = 3), item("mid", captureDate = 2),
        ))
        assertEquals(listOf("new", "mid", "old"), queue.map { c -> c.uri })
    }
}
