package com.mamba.picme.domain.organize

/** 价值保护判定结果。 */
data class ProtectVerdict(
    val protected: Boolean,
    val reasons: Set<ProtectReason>,
)

/**
 * 价值保护（用户决策 2026-09-06：低质 ≠ 可删，老照片/稀缺照片有情感价值）：
 * 仅对 [OrganizeCategory.LOW_QUALITY_PHOTOS] / [OrganizeCategory.LOW_QUALITY_PORTRAITS]
 * 生效；命中任一保护信号 → protected（不默认勾选、不计入 Hero、排详情页保护区）。
 * 纯函数，[now] 注入保证测试确定性。
 */
object ValueGuard {

    private val GUARDED_CATEGORIES = setOf(
        OrganizeCategory.LOW_QUALITY_PHOTOS,
        OrganizeCategory.LOW_QUALITY_PORTRAITS,
    )

    fun assess(item: OrganizeItem, category: OrganizeCategory, now: Long): ProtectVerdict {
        if (category !in GUARDED_CATEGORIES) return ProtectVerdict(protected = false, reasons = emptySet())
        val reasons = mutableSetOf<ProtectReason>()
        if (item.captureDate < now - OrganizeThresholds.OLD_PHOTO_YEARS * OrganizeThresholds.YEAR_MILLIS) {
            reasons += ProtectReason.OLD_PHOTO
        }
        val personCount = item.personPhotoCount
        if (personCount != null && personCount in 1..OrganizeThresholds.PERSON_SCARCE_MAX) {
            reasons += ProtectReason.SCARCE_PERSON
        }
        if (item.isFavorite || item.lastViewedAt != null) {
            reasons += ProtectReason.USER_ENGAGED
        }
        return ProtectVerdict(protected = reasons.isNotEmpty(), reasons = reasons)
    }
}
