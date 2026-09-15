import Foundation

/// 价值保护判定结果。
struct ProtectVerdict {
    let reasons: Set<ProtectReason>

    /// 派生属性：命中任一保护原因即 protected（与 ClassifiedItem.isProtected 同口径）。
    var isProtected: Bool { !reasons.isEmpty }
}

/// 价值保护（用户决策 2026-09-06：低质 ≠ 可删，老照片/稀缺照片有情感价值）：
/// 仅对 LOW_QUALITY_PHOTOS / LOW_QUALITY_PORTRAITS 生效；命中任一保护信号 → protected
/// （不默认勾选、不计入 Hero、排详情页保护区）。
/// 直译 Android domain/organize/ValueGuard.kt；纯函数，now 注入保证测试确定性。
///
/// 保守偏置：captureDate = 0（未知时间戳，下载件常见）会恒判老照片进入保护区
/// ——保守方向是有意为之：宁可不预选，不可误删。
enum ValueGuard {

    private static let guardedCategories: Set<OrganizeCategory> = [
        .lowQualityPhotos, .lowQualityPortraits,
    ]

    static func assess(_ item: OrganizeItem, category: OrganizeCategory, now: Int64) -> ProtectVerdict {
        guard guardedCategories.contains(category) else { return ProtectVerdict(reasons: []) }
        var reasons = Set<ProtectReason>()
        if item.captureDate < now - OrganizeThresholds.oldPhotoYears * OrganizeThresholds.yearMillis {
            reasons.insert(.oldPhoto)
        }
        // 下界 1：personPhotoCount = 0 为异常数据（计数不可能为 0），视为无信号
        if let personCount = item.personPhotoCount,
           personCount >= 1, personCount <= OrganizeThresholds.personScarceMax {
            reasons.insert(.scarcePerson)
        }
        if item.isFavorite || item.lastViewedAt != nil {
            reasons.insert(.userEngaged)
        }
        return ProtectVerdict(reasons: reasons)
    }
}
