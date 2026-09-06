package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryArbiterTest {

    @Suppress("LongParameterList") // 测试夹具工厂：与 OrganizeItem 构造参数一一对应
    private fun item(
        uri: String = "a",
        isVideo: Boolean = false,
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
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = 1_000L, sizeBytes = sizeBytes,
        relativePath = relativePath, ocrText = ocrText, pixelArea = pixelArea,
        labels = labels, hasFace = hasFace, aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore, blurScore = blurScore,
        exposureScore = exposureScore, exactDupGroupSize = exactDupGroupSize,
        similarDupGroupSize = similarDupGroupSize,
    )

    @Test
    fun `exact duplicate group member wins over everything`() {
        // 截图路径 + 重复组 → DUPLICATES（优先级 1 截走，修复 v1 多属重复计账）
        val result = CategoryArbiter.classify(
            item(relativePath = "Pictures/Screenshots/", exactDupGroupSize = 3)
        )
        assertEquals(OrganizeCategory.DUPLICATES, result)
    }

    @Test
    fun `screenshot path classified SCREEN_CONTENT and never blurry`() {
        // v1 痛点：截图低美学分误落 BLURRY；v2 必须先被 SCREEN_CONTENT 截走
        val result = CategoryArbiter.classify(
            item(relativePath = "Pictures/Screenshots/", aestheticScore = 1.0f, blurScore = 1.0f)
        )
        assertEquals(OrganizeCategory.SCREEN_CONTENT, result)
    }

    @Test
    fun `screen recording video by path keyword`() {
        val result = CategoryArbiter.classify(
            item(isVideo = true, relativePath = "Movies/ScreenRecorder/", sizeBytes = 5_000_000)
        )
        assertEquals(OrganizeCategory.SCREEN_CONTENT, result)
    }

    @Test
    fun `document via OCR density beats low quality photo`() {
        // 文档翻拍天然模糊/低美学 → 必须落 DOCUMENTS 而非 LOW_QUALITY_PHOTOS
        val dense = "x".repeat(500)
        val result = CategoryArbiter.classify(
            item(ocrText = dense, pixelArea = 12_000_000, blurScore = 1.0f)
        )
        assertEquals(OrganizeCategory.DOCUMENTS, result)
    }

    @Test
    fun `low face quality portrait`() {
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PORTRAITS,
            CategoryArbiter.classify(item(hasFace = true, faceQualityScore = 0.2f))
        )
        // 阈值边界：恰好等于不命中
        assertNull(CategoryArbiter.classify(item(hasFace = true, faceQualityScore = 0.35f)))
        // null 评分不判定
        assertNull(CategoryArbiter.classify(item(hasFace = true, faceQualityScore = null)))
    }

    @Test
    fun `true blur and exposure anomaly classified LOW_QUALITY_PHOTOS`() {
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PHOTOS,
            CategoryArbiter.classify(item(blurScore = 20.0f))
        )
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PHOTOS,
            CategoryArbiter.classify(item(exposureScore = 0.05f))
        )
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PHOTOS,
            CategoryArbiter.classify(item(exposureScore = 0.95f))
        )
        // NIMA 低分单独不再定类（v1 BLURRY 名实错位修复）
        assertNull(CategoryArbiter.classify(item(aestheticScore = 1.0f)))
    }

    @Test
    fun `large video and super resolution photo are LARGE_FILES`() {
        assertEquals(
            OrganizeCategory.LARGE_FILES,
            CategoryArbiter.classify(item(isVideo = true, sizeBytes = 150L * 1024 * 1024))
        )
        assertEquals(
            OrganizeCategory.LARGE_FILES,
            CategoryArbiter.classify(
                item(pixelArea = 60_000_000, sizeBytes = 25L * 1024 * 1024)
            )
        )
        // 高像素但体积小（高效压缩）不判
        assertNull(
            CategoryArbiter.classify(item(pixelArea = 60_000_000, sizeBytes = 5L * 1024 * 1024))
        )
    }

    @Test
    fun `domain constraint - videos never enter document or quality categories`() {
        val video = item(
            isVideo = true, ocrText = "x".repeat(500),
            faceQualityScore = 0.1f, hasFace = true, blurScore = 1.0f, sizeBytes = 1_000,
        )
        assertNull(CategoryArbiter.classify(video))
    }

    @Test
    fun `uncategorized returns null`() {
        assertNull(CategoryArbiter.classify(item()))
    }
}
