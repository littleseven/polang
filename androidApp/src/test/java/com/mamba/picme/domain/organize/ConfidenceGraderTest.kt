package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidenceGraderTest {

    private val now = 1_800_000_000_000L // 固定参考时间，测试确定性

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
        captureDate: Long = 1_000L,
        displayName: String? = null,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = captureDate, sizeBytes = sizeBytes,
        relativePath = relativePath, ocrText = ocrText, pixelArea = pixelArea,
        labels = labels, hasFace = hasFace, aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore, blurScore = blurScore,
        exposureScore = exposureScore, exactDupGroupSize = exactDupGroupSize,
        similarDupGroupSize = similarDupGroupSize, displayName = displayName,
    )

    /** 一年毫秒（与 StorageStory.STALE_SCREENSHOT_AGE_MS 同口径的测试便利值）。 */
    private val oneYear = 365L * 24 * 3600 * 1000

    @Test
    fun `duplicates - exact HIGH, similar MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(exactDupGroupSize = 2), OrganizeCategory.DUPLICATES, now)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(similarDupGroupSize = 3), OrganizeCategory.DUPLICATES, now)
        )
    }

    @Test
    fun `screen content neutral stays HIGH`() {
        // 无重要度信号：路径判定为确定性强信号（既有行为不变）
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(relativePath = "Pictures/Screenshots/"), OrganizeCategory.SCREEN_CONTENT, now
            )
        )
    }

    @Test
    fun `screen content - low value stale HIGH, fresh or unknown date MEDIUM`() {
        fun meituanShot(captureDate: Long) = item(
            relativePath = "Pictures/Screenshots/",
            displayName = "Screenshot_2025-06-12-08-29-24-341_com.sankuai.meituan.jpg",
            captureDate = captureDate,
        )
        // ≥ 365 天 → HIGH（与 hub 过期截图建议行同口径；400 天常规情形）
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(meituanShot(now - 400L * 24 * 3600 * 1000), OrganizeCategory.SCREEN_CONTENT, now)
        )
        // 恰 365 天：`>=` 含端（与 StorageStory stale 口径一致）
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(meituanShot(now - oneYear), OrganizeCategory.SCREEN_CONTENT, now)
        )
        // 新鲜低价值截图 → MEDIUM
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(meituanShot(now - 100L * 24 * 3600 * 1000), OrganizeCategory.SCREEN_CONTENT, now)
        )
        // captureDate = 0 未知时间戳 → 保守不判过期 → MEDIUM
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(meituanShot(0), OrganizeCategory.SCREEN_CONTENT, now)
        )
        // OCR 兜底低价值同分级：无包名 + 「外卖」关键词
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(
                    relativePath = "Pictures/Screenshots/",
                    ocrText = "外卖订单",
                    captureDate = now,
                ),
                OrganizeCategory.SCREEN_CONTENT,
                now,
            )
        )
    }

    @Test
    fun `screen content - protected tier is MEDIUM`() {
        // 保护档不给建议删除信号（保护区口径由 ValueGuard 承接）
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(
                    relativePath = "Pictures/Screenshots/",
                    displayName = "Screenshot_2025-06-12-08-29-24-341_cn.futu.trader.jpg",
                    captureDate = now - 3 * oneYear,
                ),
                OrganizeCategory.SCREEN_CONTENT,
                now,
            )
        )
        // OCR 兜底保护档同分级
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(
                    relativePath = "Pictures/Screenshots/",
                    ocrText = "门诊挂号单",
                    captureDate = now - 3 * oneYear,
                ),
                OrganizeCategory.SCREEN_CONTENT,
                now,
            )
        )
    }

    @Test
    fun `documents - strong OCR HIGH, borderline MEDIUM, labels only MEDIUM`() {
        // 12MP 图上密度阈值 20 字符/MP = 240 字符；2× = 480
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(ocrText = "x".repeat(600)), OrganizeCategory.DOCUMENTS, now)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(ocrText = "x".repeat(300)), OrganizeCategory.DOCUMENTS, now)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(labels = """["文档","纸张"]"""), OrganizeCategory.DOCUMENTS, now)
        )
        // sub-MP 回归：0.9MP 真阈值 0.9×20×2=36 字符，20 < 36 → MEDIUM（旧先除后乘实现阈值塌缩为 0 会误判 HIGH）
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(ocrText = "x".repeat(20), pixelArea = 900_000), OrganizeCategory.DOCUMENTS, now
            )
        )
        // pixelArea=null 兜底分支：500 ≥ 200×2=400 → HIGH；300 < 400 → MEDIUM
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(ocrText = "x".repeat(500), pixelArea = null), OrganizeCategory.DOCUMENTS, now
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(ocrText = "x".repeat(300), pixelArea = null), OrganizeCategory.DOCUMENTS, now
            )
        )
    }

    @Test
    fun `low quality portraits - strong face quality HIGH else MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(hasFace = true, faceQualityScore = 0.1f), OrganizeCategory.LOW_QUALITY_PORTRAITS, now
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(hasFace = true, faceQualityScore = 0.3f), OrganizeCategory.LOW_QUALITY_PORTRAITS, now
            )
        )
    }

    @Test
    fun `low quality photos - strong blur HIGH, borderline blur or exposure MEDIUM, aesthetic only LOW`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(blurScore = 20.0f), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(blurScore = 80.0f), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(exposureScore = 0.05f), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        )
        // 过曝侧（OR 右半边）
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(exposureScore = 0.9f), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        )
        // 等值边界锁定：blur = 100×0.5=50 时 `<` 严格不命中 HIGH → MEDIUM
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(blurScore = 50.0f), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        )
        // 防御性兜底分支单测（该输入经 arbiter 不会进此类目，此处锁定 grader 独立语义）
        assertEquals(
            OrganizeConfidence.LOW,
            ConfidenceGrader.grade(
                item(blurScore = 500.0f, exposureScore = 0.5f, aestheticScore = 2.0f),
                OrganizeCategory.LOW_QUALITY_PHOTOS,
                now,
            )
        )
    }

    @Test
    fun `large files - double threshold HIGH else MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 250L * 1024 * 1024), OrganizeCategory.LARGE_FILES, now
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 120L * 1024 * 1024), OrganizeCategory.LARGE_FILES, now
            )
        )
        // 照片分支：≥2×20MB → HIGH；1.25× → MEDIUM
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(pixelArea = 60_000_000, sizeBytes = 45L * 1024 * 1024), OrganizeCategory.LARGE_FILES, now
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(pixelArea = 60_000_000, sizeBytes = 25L * 1024 * 1024), OrganizeCategory.LARGE_FILES, now
            )
        )
        // 等值边界锁定：2×100MiB=200MiB 时 `>=` 恰等命中 HIGH
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 200L * 1024 * 1024), OrganizeCategory.LARGE_FILES, now
            )
        )
    }
}
