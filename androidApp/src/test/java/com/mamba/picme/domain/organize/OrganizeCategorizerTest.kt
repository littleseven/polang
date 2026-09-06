package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeCategorizerTest {

    private val now = 1_800_000_000_000L

    @Suppress("LongParameterList")
    private fun item(
        uri: String,
        isVideo: Boolean = false,
        captureDate: Long = now,
        sizeBytes: Long = 1_000_000,
        relativePath: String? = "DCIM/Camera/",
        ocrText: String? = null,
        pixelArea: Long? = 12_000_000,
        labels: String? = null,
        hasFace: Boolean = false,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
        blurScore: Float? = null,
        exposureScore: Float? = null,
        lastViewedAt: Long? = null,
        isFavorite: Boolean = false,
        personPhotoCount: Int? = null,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = captureDate, sizeBytes = sizeBytes,
        relativePath = relativePath, ocrText = ocrText, pixelArea = pixelArea,
        labels = labels, hasFace = hasFace, aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore, blurScore = blurScore,
        exposureScore = exposureScore, lastViewedAt = lastViewedAt, isFavorite = isFavorite,
        personPhotoCount = personPhotoCount, exactDupGroupSize = exactDupGroupSize,
        similarDupGroupSize = similarDupGroupSize,
    )

    @Test
    fun `mutex - screenshot with low scores lands only in SCREEN_CONTENT`() {
        val classified = OrganizeCategorizer.classifyAll(
            listOf(
                item(
                    "shot", relativePath = "Pictures/Screenshots/",
                    aestheticScore = 1.0f, blurScore = 1.0f, ocrText = "x".repeat(600),
                )
            ),
            now = now,
        )
        assertEquals(1, classified.size)
        assertEquals(OrganizeCategory.SCREEN_CONTENT, classified[0].category)
    }

    @Test
    fun `hero reclaim is HIGH non-protected union - no double count regression AC-F1-1`() {
        // 同一张图在 v1 会被截图+模糊+文档计 3 次；v2 互斥后 Hero 只计 1 次
        val board = OrganizeCategorizer.board(
            listOf(
                item("shot", relativePath = "Pictures/Screenshots/", sizeBytes = 100),
                item("big", isVideo = true, sizeBytes = 250L * 1024 * 1024),
                item("normal"),
            ),
            now = now,
        )
        assertEquals(100L + 250L * 1024 * 1024, board.heroReclaimBytes)
    }

    @Test
    fun `protected items excluded from hero and never high-counted`() {
        val board = OrganizeCategorizer.board(
            listOf(
                // 6 年前的模糊老照片：HIGH 置信但 protected → 不进 Hero、不进 highCount
                item(
                    "old", captureDate = now - 6 * OrganizeThresholds.YEAR_MILLIS,
                    blurScore = 10.0f, sizeBytes = 500,
                ),
            ),
            now = now,
        )
        assertEquals(0L, board.heroReclaimBytes)
        val card = board.categories.single { card -> card.category == OrganizeCategory.LOW_QUALITY_PHOTOS }
        assertEquals(1, card.totalCount)
        assertEquals(0, card.highCount)
        assertEquals(1, card.protectedCount)
    }

    @Test
    fun `categories sorted by high confidence bytes descending`() {
        val board = OrganizeCategorizer.board(
            listOf(
                item("shot", relativePath = "Pictures/Screenshots/", sizeBytes = 100),
                item("big", isVideo = true, sizeBytes = 250L * 1024 * 1024),
            ),
            now = now,
        )
        assertEquals(
            listOf(OrganizeCategory.LARGE_FILES, OrganizeCategory.SCREEN_CONTENT),
            board.categories.map { card -> card.category },
        )
    }

    @Test
    fun `needs-scan coverage when no library item has the signal`() {
        val board = OrganizeCategorizer.board(
            listOf(item("a")), // 全库无 blurScore
            now = now,
        )
        // 无命中时类目卡不渲染（totalCount=0 不生成卡），但覆盖度可查询
        assertEquals(
            SignalCoverage.NEEDS_SCAN,
            OrganizeCategorizer.coverageOf(OrganizeCategory.LOW_QUALITY_PHOTOS, listOf(item("a"))),
        )
        assertEquals(
            SignalCoverage.READY,
            OrganizeCategorizer.coverageOf(OrganizeCategory.SCREEN_CONTENT, listOf(item("a"))),
        )
        assertTrue(board.categories.isEmpty())
    }

    @Test
    fun `review count aggregates MEDIUM and LOW non-protected`() {
        val board = OrganizeCategorizer.board(
            listOf(
                item("borderline", blurScore = 80.0f, sizeBytes = 10),       // MEDIUM
                item("aesthetic", blurScore = 500.0f, exposureScore = 0.5f,
                    aestheticScore = 2.0f, sizeBytes = 10),                 // 不进类目（无强信号）
            ),
            now = now,
        )
        assertEquals(1, board.heroReviewCount)
    }
}
