package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeCategorizerTest {

    @Suppress("LongParameterList") // 测试夹具工厂：与 OrganizeItem 构造参数一一对应，默认值覆盖避免每用例全列
    private fun item(
        uri: String,
        isVideo: Boolean = false,
        sizeBytes: Long = 1_000_000,
        relativePath: String? = "DCIM/Camera/",
        ocrText: String? = null,
        pixelArea: Long? = 12_000_000,
        labels: String? = null,
        hasFace: Boolean = false,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
    ) = OrganizeItem(uri, isVideo, 1_000L, sizeBytes, relativePath, ocrText, pixelArea, labels, hasFace, aestheticScore, faceQualityScore)

    private fun categoriesOf(vararg items: OrganizeItem): Map<String, Set<OrganizeCategory>> =
        items.associate { item -> item.uri to OrganizeCategorizer.categoriesOf(item) }

    @Test
    fun `screenshot path detected case-insensitively`() {
        val cats = categoriesOf(item("a", relativePath = "Pictures/Screenshots/"))
        assertTrue(OrganizeCategory.SCREENSHOTS in cats.getValue("a"))
    }

    @Test
    fun `null aesthetic score is not blurry`() {
        val cats = categoriesOf(item("a", aestheticScore = null))
        assertTrue(OrganizeCategory.BLURRY !in cats.getValue("a"))
    }

    @Test
    fun `low aesthetic score below threshold is blurry`() {
        val cats = categoriesOf(item("a", aestheticScore = 2.1f), item("b", aestheticScore = 3.5f))
        assertTrue(OrganizeCategory.BLURRY in cats.getValue("a"))
        assertTrue(OrganizeCategory.BLURRY !in cats.getValue("b"))
    }

    @Test
    fun `low face quality portrait detected, null excluded`() {
        val cats = categoriesOf(
            item("a", hasFace = true, faceQualityScore = 0.2f),
            item("b", hasFace = true, faceQualityScore = null),
            item("c", hasFace = false, faceQualityScore = 0.2f),
        )
        assertTrue(OrganizeCategory.LOW_QUALITY_PORTRAITS in cats.getValue("a"))
        assertTrue(OrganizeCategory.LOW_QUALITY_PORTRAITS !in cats.getValue("b"))
        assertTrue(OrganizeCategory.LOW_QUALITY_PORTRAITS !in cats.getValue("c"))
    }

    @Test
    fun `large video threshold`() {
        val big = item("a", isVideo = true, sizeBytes = 150L * 1024 * 1024)
        val small = item("b", isVideo = true, sizeBytes = 50L * 1024 * 1024)
        val photo = item("c", isVideo = false, sizeBytes = 150L * 1024 * 1024)
        val cats = categoriesOf(big, small, photo)
        assertTrue(OrganizeCategory.LARGE_VIDEOS in cats.getValue("a"))
        assertTrue(OrganizeCategory.LARGE_VIDEOS !in cats.getValue("b"))
        assertTrue(OrganizeCategory.LARGE_VIDEOS !in cats.getValue("c"))
    }

    @Test
    fun `document by ocr density and fallback`() {
        val dense = item("a", ocrText = "x".repeat(500), pixelArea = 12_000_000)
        val unknownArea = item("b", ocrText = "x".repeat(250), pixelArea = null)
        val sparse = item("c", ocrText = "hi", pixelArea = 12_000_000)
        val byLabel = item("d", labels = """["receipt","shop"]""")
        val cats = categoriesOf(dense, unknownArea, sparse, byLabel)
        assertTrue(OrganizeCategory.DOCUMENTS in cats.getValue("a"))
        assertTrue(OrganizeCategory.DOCUMENTS in cats.getValue("b"))
        assertTrue(OrganizeCategory.DOCUMENTS !in cats.getValue("c"))
        assertTrue(OrganizeCategory.DOCUMENTS in cats.getValue("d"))
    }

    @Test
    fun `stats aggregate count bytes and previews`() {
        val items = listOf(
            item("s1", relativePath = "Pictures/Screenshots/", sizeBytes = 100),
            item("s2", relativePath = "Pictures/Screenshots/", sizeBytes = 200),
            item("p1"),
        )
        val stats = OrganizeCategorizer.stats(items)
        val shot = stats.first { stat -> stat.category == OrganizeCategory.SCREENSHOTS }
        assertEquals(2, shot.count)
        assertEquals(300, shot.totalBytes)
        assertEquals(listOf("s1", "s2"), shot.previewUris)
        assertTrue(stats.none { stat -> stat.category == OrganizeCategory.DUPLICATES })
        assertTrue(stats.none { stat -> stat.count == 0 })
    }
}
