package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidenceGraderTest {

    @Suppress("LongParameterList")
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
    fun `duplicates - exact HIGH, similar MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(exactDupGroupSize = 2), OrganizeCategory.DUPLICATES)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(similarDupGroupSize = 3), OrganizeCategory.DUPLICATES)
        )
    }

    @Test
    fun `screen content by path is HIGH`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(relativePath = "Pictures/Screenshots/"), OrganizeCategory.SCREEN_CONTENT
            )
        )
    }

    @Test
    fun `documents - strong OCR HIGH, borderline MEDIUM, labels only MEDIUM`() {
        // 12MP 图上密度阈值 20 字符/MP = 240 字符；2× = 480
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(ocrText = "x".repeat(600)), OrganizeCategory.DOCUMENTS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(ocrText = "x".repeat(300)), OrganizeCategory.DOCUMENTS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(labels = """["文档","纸张"]"""), OrganizeCategory.DOCUMENTS)
        )
        // sub-MP 回归：0.9MP 真阈值 0.9×20×2=36 字符，20 < 36 → MEDIUM（旧先除后乘实现阈值塌缩为 0 会误判 HIGH）
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(ocrText = "x".repeat(20), pixelArea = 900_000), OrganizeCategory.DOCUMENTS
            )
        )
        // pixelArea=null 兜底分支：500 ≥ 200×2=400 → HIGH；300 < 400 → MEDIUM
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(ocrText = "x".repeat(500), pixelArea = null), OrganizeCategory.DOCUMENTS
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(ocrText = "x".repeat(300), pixelArea = null), OrganizeCategory.DOCUMENTS
            )
        )
    }

    @Test
    fun `low quality portraits - strong face quality HIGH else MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(hasFace = true, faceQualityScore = 0.1f), OrganizeCategory.LOW_QUALITY_PORTRAITS
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(hasFace = true, faceQualityScore = 0.3f), OrganizeCategory.LOW_QUALITY_PORTRAITS
            )
        )
    }

    @Test
    fun `low quality photos - strong blur HIGH, borderline blur or exposure MEDIUM, aesthetic only LOW`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(blurScore = 20.0f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(blurScore = 80.0f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(exposureScore = 0.05f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        // 过曝侧（OR 右半边）
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(exposureScore = 0.9f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        // 等值边界锁定：blur = 100×0.5=50 时 `<` 严格不命中 HIGH → MEDIUM
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(blurScore = 50.0f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        // 防御性兜底分支单测（该输入经 arbiter 不会进此类目，此处锁定 grader 独立语义）
        assertEquals(
            OrganizeConfidence.LOW,
            ConfidenceGrader.grade(
                item(blurScore = 500.0f, exposureScore = 0.5f, aestheticScore = 2.0f),
                OrganizeCategory.LOW_QUALITY_PHOTOS,
            )
        )
    }

    @Test
    fun `large files - double threshold HIGH else MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 250L * 1024 * 1024), OrganizeCategory.LARGE_FILES
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 120L * 1024 * 1024), OrganizeCategory.LARGE_FILES
            )
        )
        // 照片分支：≥2×20MB → HIGH；1.25× → MEDIUM
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(pixelArea = 60_000_000, sizeBytes = 45L * 1024 * 1024), OrganizeCategory.LARGE_FILES
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(pixelArea = 60_000_000, sizeBytes = 25L * 1024 * 1024), OrganizeCategory.LARGE_FILES
            )
        )
        // 等值边界锁定：2×100MiB=200MiB 时 `>=` 恰等命中 HIGH
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 200L * 1024 * 1024), OrganizeCategory.LARGE_FILES
            )
        )
    }
}
