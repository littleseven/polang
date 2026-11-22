package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueGuardTest {

    private val now = 1_800_000_000_000L // 固定参考时间，测试确定性

    @Suppress("LongParameterList")
    private fun item(
        captureDate: Long = now,
        lastViewedAt: Long? = null,
        isFavorite: Boolean = false,
        personPhotoCount: Int? = null,
        relativePath: String? = "DCIM/Camera/",
        displayName: String? = null,
        ocrText: String? = null,
    ) = OrganizeItem(
        uri = "a", isVideo = false, captureDate = captureDate, sizeBytes = 1_000_000,
        relativePath = relativePath, ocrText = ocrText, pixelArea = 12_000_000,
        labels = null, hasFace = false, aestheticScore = null, faceQualityScore = null,
        blurScore = 10.0f, lastViewedAt = lastViewedAt, isFavorite = isFavorite,
        personPhotoCount = personPhotoCount, displayName = displayName,
    )

    @Test
    fun `old photo protected with boundary`() {
        val fiveYearsAgo = now - OrganizeThresholds.OLD_PHOTO_YEARS * OrganizeThresholds.YEAR_MILLIS
        val old = ValueGuard.assess(
            item(captureDate = fiveYearsAgo - 1), OrganizeCategory.LOW_QUALITY_PHOTOS, now
        )
        assertTrue(old.isProtected)
        assertEquals(setOf(ProtectReason.OLD_PHOTO), old.reasons)
        // 边界：恰好 5 年不保护（判定为「早于」）
        val boundary = ValueGuard.assess(
            item(captureDate = fiveYearsAgo), OrganizeCategory.LOW_QUALITY_PHOTOS, now
        )
        assertTrue(ProtectReason.OLD_PHOTO !in boundary.reasons)
        assertTrue(!boundary.isProtected)
    }

    @Test
    fun `scarce person protected with boundary`() {
        val scarce = ValueGuard.assess(item(personPhotoCount = 3), OrganizeCategory.LOW_QUALITY_PORTRAITS, now)
        assertEquals(setOf(ProtectReason.SCARCE_PERSON), scarce.reasons)
        val notScarce = ValueGuard.assess(item(personPhotoCount = 4), OrganizeCategory.LOW_QUALITY_PORTRAITS, now)
        assertTrue(ProtectReason.SCARCE_PERSON !in notScarce.reasons)
        // 无人物信息不触发
        val noPerson = ValueGuard.assess(item(personPhotoCount = null), OrganizeCategory.LOW_QUALITY_PORTRAITS, now)
        assertTrue(ProtectReason.SCARCE_PERSON !in noPerson.reasons)
        // 计数 0 = 异常数据（计数不可能为 0），视为无信号不触发
        val zeroCount = ValueGuard.assess(item(personPhotoCount = 0), OrganizeCategory.LOW_QUALITY_PORTRAITS, now)
        assertTrue(ProtectReason.SCARCE_PERSON !in zeroCount.reasons)
    }

    @Test
    fun `favorite or viewed protected`() {
        val fav = ValueGuard.assess(item(isFavorite = true), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        assertEquals(setOf(ProtectReason.USER_ENGAGED), fav.reasons)
        val viewed = ValueGuard.assess(item(lastViewedAt = now - 1000), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        assertEquals(setOf(ProtectReason.USER_ENGAGED), viewed.reasons)
    }

    @Test
    fun `protection quality rules only apply to quality categories`() {
        // 低质三规则（老照片等）不外溢到 SCREEN_CONTENT（截图仅敏感截图规则，见下方 Task 7.6 用例）
        val screenshot = ValueGuard.assess(
            item(captureDate = now - 6 * OrganizeThresholds.YEAR_MILLIS), OrganizeCategory.SCREEN_CONTENT, now
        )
        assertTrue(!screenshot.isProtected && screenshot.reasons.isEmpty())
    }

    @Test
    fun `sensitive screenshot protected by importance tier`() {
        // 金融包名截图（截图路径）→ SENSITIVE_SCREENSHOT
        val finance = ValueGuard.assess(
            item(
                relativePath = "Pictures/Screenshots/",
                displayName = "Screenshot_2025-06-12-08-29-24-341_cn.futu.trader.jpg",
            ),
            OrganizeCategory.SCREEN_CONTENT, now,
        )
        assertEquals(setOf(ProtectReason.SENSITIVE_SCREENSHOT), finance.reasons)
        assertTrue(finance.isProtected)
        // OCR 兜底保护档同样入区
        val health = ValueGuard.assess(
            item(relativePath = "Pictures/Screenshots/", ocrText = "门诊挂号单"),
            OrganizeCategory.SCREEN_CONTENT, now,
        )
        assertEquals(setOf(ProtectReason.SENSITIVE_SCREENSHOT), health.reasons)
    }

    @Test
    fun `low value or neutral screenshot not protected`() {
        // 低价值（美团包名）：不进保护区（过期升级建议删除由 ConfidenceGrader 承接）
        val lowValue = ValueGuard.assess(
            item(
                relativePath = "Pictures/Screenshots/",
                displayName = "Screenshot_1_com.sankuai.meituan.jpg",
            ),
            OrganizeCategory.SCREEN_CONTENT, now,
        )
        assertTrue(!lowValue.isProtected && lowValue.reasons.isEmpty())
        // 无信号截图：不保护
        val neutral = ValueGuard.assess(
            item(relativePath = "Pictures/Screenshots/"),
            OrganizeCategory.SCREEN_CONTENT, now,
        )
        assertTrue(!neutral.isProtected && neutral.reasons.isEmpty())
    }

    @Test
    fun `screen content ignores quality rules and non screenshot paths`() {
        // 低质三规则（老照片/稀缺人物/用户互动）不扩大到 SCREEN_CONTENT（保护规则演进属 M2）
        val oldFavoriteShot = ValueGuard.assess(
            item(
                captureDate = now - 6 * OrganizeThresholds.YEAR_MILLIS,
                isFavorite = true,
                personPhotoCount = 1,
                relativePath = "Pictures/Screenshots/",
            ),
            OrganizeCategory.SCREEN_CONTENT, now,
        )
        assertTrue(oldFavoriteShot.reasons.isEmpty())
        // 非截图路径（camera）：即使文件名携带金融包名也不触发（appSourceOf 前置守卫）
        val cameraPath = ValueGuard.assess(
            item(
                relativePath = "DCIM/Camera/",
                displayName = "Screenshot_2025-06-12-08-29-24-341_cn.futu.trader.jpg",
            ),
            OrganizeCategory.SCREEN_CONTENT, now,
        )
        assertTrue(cameraPath.reasons.isEmpty())
    }

    @Test
    fun `multiple reasons accumulate`() {
        val verdict = ValueGuard.assess(
            item(captureDate = now - 6 * OrganizeThresholds.YEAR_MILLIS, isFavorite = true, personPhotoCount = 1),
            OrganizeCategory.LOW_QUALITY_PHOTOS, now,
        )
        assertEquals(
            setOf(ProtectReason.OLD_PHOTO, ProtectReason.USER_ENGAGED, ProtectReason.SCARCE_PERSON),
            verdict.reasons,
        )
    }
}
