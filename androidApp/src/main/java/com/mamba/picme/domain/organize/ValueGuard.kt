package com.mamba.picme.domain.organize

/** 价值保护判定结果。 */
data class ProtectVerdict(
    val reasons: Set<ProtectReason>,
) {
    /** 派生属性：命中任一保护原因即 protected（与 ClassifiedItem.isProtected 同口径）。 */
    val isProtected: Boolean get() = reasons.isNotEmpty()
}

/**
 * 价值保护（用户决策 2026-09-06：低质 ≠ 可删，老照片/稀缺照片有情感价值）：
 * 仅对 [OrganizeCategory.LOW_QUALITY_PHOTOS] / [OrganizeCategory.LOW_QUALITY_PORTRAITS]
 * 生效；命中任一保护信号 → protected（不默认勾选、不计入 Hero、排详情页保护区）。
 * 纯函数，[now] 注入保证测试确定性。
 *
 * 保守偏置：captureDate = 0（未知时间戳，下载件常见）会恒判老照片进入保护区
 * ——保守方向是有意为之：宁可不预选，不可误删。
 */
object ValueGuard {

    private val GUARDED_CATEGORIES = setOf(
        OrganizeCategory.LOW_QUALITY_PHOTOS,
        OrganizeCategory.LOW_QUALITY_PORTRAITS,
    )

    fun assess(item: OrganizeItem, category: OrganizeCategory, now: Long): ProtectVerdict {
        if (category !in GUARDED_CATEGORIES) return ProtectVerdict(reasons = emptySet())
        val reasons = mutableSetOf<ProtectReason>()
        if (item.captureDate < now - OrganizeThresholds.OLD_PHOTO_YEARS * OrganizeThresholds.YEAR_MILLIS) {
            reasons += ProtectReason.OLD_PHOTO
        }
        val personCount = item.personPhotoCount
        // 下界 1：personPhotoCount = 0 为异常数据（计数不可能为 0），视为无信号
        if (personCount != null && personCount in 1..OrganizeThresholds.PERSON_SCARCE_MAX) {
            reasons += ProtectReason.SCARCE_PERSON
        }
        if (item.isFavorite || item.lastViewedAt != null) {
            reasons += ProtectReason.USER_ENGAGED
        }
        return ProtectVerdict(reasons = reasons)
    }
}
