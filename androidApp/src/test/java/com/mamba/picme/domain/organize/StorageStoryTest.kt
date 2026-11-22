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
        val exactlyOneYear = NOW - StorageStory.STALE_SCREENSHOT_AGE_MS
        val items = listOf(
            item("old-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = old),
            item("new-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = NOW),
            item("old-video", isVideo = true, size = 10, path = "Pictures/Screenshots", captureDate = old),
            // 恰 365 天：`>=` 含端，计入 stale
            item("edge-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = exactlyOneYear),
            // captureDate=0（未知时间）：不被当作 1970 年老图计入 stale
            item("unknown-date-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = 0),
        )
        val story = StorageStory.of(items, emptyBoard(), now = NOW)
        assertEquals(2, story.staleScreenshotCount)
        assertEquals(20L, story.staleScreenshotBytes)
        // screenshotBytes 口径 = 全部截图（含录屏），与 stale「仅照片」分野
        assertEquals(50L, story.screenshotBytes)
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
    fun `reclaim is clamped to zero when low quality bytes exceed hero`() {
        val board = OrganizeBoard(
            categories = listOf(
                card(OrganizeCategory.LOW_QUALITY_PHOTOS, highBytes = 300),
                card(OrganizeCategory.LOW_QUALITY_PORTRAITS, highBytes = 200),
            ),
            heroReclaimBytes = 100L,
            heroReviewCount = 0,
        )
        assertEquals(0L, StorageStory.of(emptyList(), board, NOW).reclaimBytes)
    }

    @Test
    fun `stale screenshots exclude protected importance tiers`() {
        val old = NOW - 400L * 24 * 3600 * 1000
        val items = listOf(
            // 保护档（金融包名）：过期也不计入建议口径
            item(
                "fin-shot", size = 10, path = "Pictures/Screenshots", captureDate = old,
                fileName = "Screenshot_x_cn.futu.trader.jpg",
            ),
            // 低价值（美团包名）：过期照常计入（建议删除候选）
            item(
                "low-shot", size = 10, path = "Pictures/Screenshots", captureDate = old,
                fileName = "Screenshot_x_com.sankuai.meituan.jpg",
            ),
            // 无信号：照常计入
            item("plain-shot", size = 10, path = "Pictures/Screenshots", captureDate = old),
        )
        val story = StorageStory.of(items, emptyBoard(), now = NOW)
        assertEquals(2, story.staleScreenshotCount)
        assertEquals(20L, story.staleScreenshotBytes)
        // screenshotBytes 口径 = 全部截图（含保护档，总账展示不含保护剔除）
        assertEquals(30L, story.screenshotBytes)
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
        fileName: String? = null,
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
        displayName = fileName,
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
