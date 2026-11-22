package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageStoryTest {

    @Test
    fun `ledger buckets by media kind and source`() {
        val items = listOf(
            item("a", isVideo = false, size = 100, path = "DCIM/Camera"),
            item("b", isVideo = true, size = 900, path = "DCIM/Camera"),
            item("c", isVideo = false, size = 200, path = "Pictures/WeChat"),
            item("d", isVideo = false, size = 50, path = "Pictures/Screenshots"),
        )
        val story = StorageStory.of(items, emptyBoard(), now = NOW)
        assertEquals(1250L, story.totalBytes)
        assertEquals(350L, story.photoBytes)
        assertEquals(900L, story.videoBytes)
        assertEquals(50L, story.screenshotBytes)
        assertEquals(
            listOf(
                SourceLedger(AppSource.CAMERA, 2, 1000L),
                SourceLedger(AppSource.WECHAT, 1, 200L),
                SourceLedger(AppSource.SCREENSHOTS, 1, 50L),
            ),
            story.sourceLedgers,
        )
    }

    @Test
    fun `stale screenshots count photos older than 365 days only`() {
        val old = NOW - 400L * 24 * 3600 * 1000
        val items = listOf(
            item("old-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = old),
            item("new-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = NOW),
            item("old-video", isVideo = true, size = 10, path = "Pictures/Screenshots", captureDate = old),
        )
        val story = StorageStory.of(items, emptyBoard(), now = NOW)
        assertEquals(1, story.staleScreenshotCount)
        assertEquals(10L, story.staleScreenshotBytes)
    }

    @Test
    fun `reclaim excludes both low quality categories from hero`() {
        val board = OrganizeBoard(
            categories = listOf(
                card(OrganizeCategory.DUPLICATES, highBytes = 500),
                card(OrganizeCategory.LOW_QUALITY_PHOTOS, highBytes = 300),
                card(OrganizeCategory.LOW_QUALITY_PORTRAITS, highBytes = 200),
            ),
            heroReclaimBytes = 1000L,
            heroReviewCount = 0,
        )
        assertEquals(500L, StorageStory.of(emptyList(), board, NOW).reclaimBytes)
    }

    @Test
    fun `zero size and empty inputs do not crash`() {
        val story = StorageStory.of(listOf(item("x", size = 0, path = null)), emptyBoard(), NOW)
        assertEquals(0L, story.totalBytes)
        assertEquals(0L, story.reclaimBytes)
    }

    companion object {
        private const val NOW = 1_764_000_000_000L
    }

    private fun item(
        name: String,
        isVideo: Boolean = false,
        size: Long,
        path: String?,
        captureDate: Long = NOW,
    ): OrganizeItem = OrganizeItem(
        uri = "content://media/external/$name",
        isVideo = isVideo,
        captureDate = captureDate,
        sizeBytes = size,
        relativePath = path,
        ocrText = null,
        pixelArea = null,
        labels = null,
        hasFace = false,
        aestheticScore = null,
        faceQualityScore = null,
    )

    private fun emptyBoard(): OrganizeBoard = OrganizeBoard(
        categories = emptyList(),
        heroReclaimBytes = 0L,
        heroReviewCount = 0,
    )

    private fun card(
        category: OrganizeCategory,
        highBytes: Long = 0L,
    ): CategoryBoard = CategoryBoard(
        category = category,
        totalCount = 0,
        totalBytes = 0L,
        highCount = 0,
        highBytes = highBytes,
        reviewCount = 0,
        protectedCount = 0,
        previewUris = emptyList(),
        coverage = SignalCoverage.READY,
    )
}
