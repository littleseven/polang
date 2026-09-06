package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeQueueBuilderTest {

    private val now = 1_800_000_000_000L

    @Suppress("LongParameterList")
    private fun item(
        uri: String,
        isVideo: Boolean = false,
        captureDate: Long = now,
        relativePath: String? = "DCIM/Camera/",
        blurScore: Float? = null,
        faceQualityScore: Float? = null,
        hasFace: Boolean = false,
        isFavorite: Boolean = false,
        exactDupGroupSize: Int = 0,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = captureDate, sizeBytes = 1_000_000,
        relativePath = relativePath, ocrText = null, pixelArea = 12_000_000, labels = null,
        hasFace = hasFace, aestheticScore = null, faceQualityScore = faceQualityScore,
        blurScore = blurScore, isFavorite = isFavorite, exactDupGroupSize = exactDupGroupSize,
    )

    @Test
    fun `buckets ordered screenshot then blurry then recent`() {
        val queue = SwipeQueueBuilder.build(
            listOf(
                // 无类目命中（entry==null）→ RECENT
                item("recent"),
                item("blur", blurScore = 10.0f),
                item("shot", relativePath = "Pictures/Screenshots/"),
            ),
            now = now,
        )
        assertEquals(
            listOf("shot", "blur", "recent"),
            queue.map { candidate -> candidate.uri },
        )
    }

    @Test
    fun `protected low quality photo falls back to RECENT bucket`() {
        // 收藏过的模糊照片：类目页进保护区，手势队列同样不往废片桶塞（口径一致）
        val queue = SwipeQueueBuilder.build(
            listOf(item("favBlur", blurScore = 10.0f, isFavorite = true)),
            now = now,
        )
        assertEquals(SwipeReason.RECENT, queue.single().reason)
    }

    @Test
    fun `videos never enqueued`() {
        val queue = SwipeQueueBuilder.build(
            listOf(item("v", isVideo = true, relativePath = "Pictures/Screenshots/")),
            now = now,
        )
        assertEquals(0, queue.size)
    }

    @Test
    fun `item lands in first matching bucket only`() {
        val queue = SwipeQueueBuilder.build(
            listOf(item("both", relativePath = "Pictures/Screenshots/", blurScore = 10.0f)),
            now = now,
        )
        assertEquals(1, queue.size)
        assertEquals(SwipeReason.SCREENSHOT, queue.single().reason)
    }

    @Test
    fun `within bucket newest first`() {
        val queue = SwipeQueueBuilder.build(
            listOf(
                item("old", captureDate = now - 3_000),
                item("new", captureDate = now - 1_000),
                item("mid", captureDate = now - 2_000),
            ),
            now = now,
        )
        assertEquals(listOf("new", "mid", "old"), queue.map { candidate -> candidate.uri })
    }

    @Test
    fun `duplicates photo falls back to RECENT bucket`() {
        // DUPLICATES 不是 F2 废片桶（去重走类目页/去重主页），照片落 RECENT 兜底
        val queue = SwipeQueueBuilder.build(
            listOf(item("dup", exactDupGroupSize = 2)),
            now = now,
        )
        assertEquals(SwipeReason.RECENT, queue.single().reason)
    }

    @Test
    fun `duplicate uri rows produce single candidate`() {
        // media_assets.uri 非唯一索引：重复行进队列只出一张卡（保留最后一条快照）
        val queue = SwipeQueueBuilder.build(
            listOf(
                item("dup", captureDate = now - 1_000),
                item("dup", captureDate = now - 2_000),
                item("other", captureDate = now - 3_000),
            ),
            now = now,
        )
        assertEquals(listOf("dup", "other"), queue.map { candidate -> candidate.uri })
        assertEquals(now - 2_000, queue.first().captureDate)
    }

    @Test
    fun `low quality portrait bucket ordered after blurry`() {
        val queue = SwipeQueueBuilder.build(
            listOf(
                item("portrait", hasFace = true, faceQualityScore = 0.1f),
                item("blur", blurScore = 10.0f),
            ),
            now = now,
        )
        assertEquals(listOf("blur", "portrait"), queue.map { candidate -> candidate.uri })
        assertEquals(SwipeReason.LOW_QUALITY_PORTRAIT, queue[1].reason)
    }
}
