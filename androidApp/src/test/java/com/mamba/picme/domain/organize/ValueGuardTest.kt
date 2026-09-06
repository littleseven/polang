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
    ) = OrganizeItem(
        uri = "a", isVideo = false, captureDate = captureDate, sizeBytes = 1_000_000,
        relativePath = "DCIM/Camera/", ocrText = null, pixelArea = 12_000_000,
        labels = null, hasFace = false, aestheticScore = null, faceQualityScore = null,
        blurScore = 10.0f, lastViewedAt = lastViewedAt, isFavorite = isFavorite,
        personPhotoCount = personPhotoCount,
    )

    @Test
    fun `old photo protected with boundary`() {
        val fiveYearsAgo = now - OrganizeThresholds.OLD_PHOTO_YEARS * OrganizeThresholds.YEAR_MILLIS
        val old = ValueGuard.assess(
            item(captureDate = fiveYearsAgo - 1), OrganizeCategory.LOW_QUALITY_PHOTOS, now
        )
        assertTrue(old.protected)
        assertEquals(setOf(ProtectReason.OLD_PHOTO), old.reasons)
        // 边界：恰好 5 年不保护（判定为「早于」）
        val boundary = ValueGuard.assess(
            item(captureDate = fiveYearsAgo), OrganizeCategory.LOW_QUALITY_PHOTOS, now
        )
        assertTrue(ProtectReason.OLD_PHOTO !in boundary.reasons)
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
    }

    @Test
    fun `favorite or viewed protected`() {
        val fav = ValueGuard.assess(item(isFavorite = true), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        assertEquals(setOf(ProtectReason.USER_ENGAGED), fav.reasons)
        val viewed = ValueGuard.assess(item(lastViewedAt = now - 1000), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        assertEquals(setOf(ProtectReason.USER_ENGAGED), viewed.reasons)
    }

    @Test
    fun `protection only applies to quality categories`() {
        // 5 年前的截图不保护（价值保护只适用 LOW_QUALITY_PHOTOS / LOW_QUALITY_PORTRAITS）
        val screenshot = ValueGuard.assess(
            item(captureDate = now - 6 * OrganizeThresholds.YEAR_MILLIS), OrganizeCategory.SCREEN_CONTENT, now
        )
        assertTrue(!screenshot.protected && screenshot.reasons.isEmpty())
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
