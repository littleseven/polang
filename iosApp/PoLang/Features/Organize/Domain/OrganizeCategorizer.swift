import Foundation

/// hub 卡片预览缩略图张数。
private let previewLimit = 4

/// 整理中心 v2 管线编排 Facade（纯函数，对外唯一入口）：
/// CategoryArbiter（互斥裁定）→ ValueGuard（价值保护）→ ConfidenceGrader（置信分级）。
/// 直译 Android domain/organize/OrganizeCategorizer.kt；类目页与 SwipeReview 同源消费本管线。
enum OrganizeCategorizer {

    /// 逐媒体裁定（类目详情页输入）。未命中任何类目的媒体不出现在结果中。
    static func classifyAll(_ items: [OrganizeItem], now: Int64) -> [ClassifiedItem] {
        items.compactMap { item in
            guard let category = CategoryArbiter.classify(item) else { return nil }
            let verdict = ValueGuard.assess(item, category: category, now: now)
            return ClassifiedItem(
                item: item,
                category: category,
                confidence: ConfidenceGrader.grade(item, category: category),
                protectReasons: verdict.reasons
            )
        }
    }

    /// hub 聚合：类目卡（按建议优先级 highBytes 降序）+ Hero 口径（HIGH 非 protected 去重并集；
    /// DUPLICATES 按精确组扣 1 张 keeper，与去重结果页只计非 keeper 口径一致）。
    /// 六类目全量产卡（含零命中类目：计数/字节全零、coverage 正常计算），
    /// NEEDS_SCAN 引导卡与空卡是否渲染由 UI 层决定（spec P3/AC-R2-4：类目不再静默消失）。
    static func board(_ items: [OrganizeItem], now: Int64) -> OrganizeBoard {
        let classified = classifyAll(items, now: now)
        let entriesByCategory = Dictionary(grouping: classified, by: { $0.category })
        let cards = OrganizeCategory.allCases.enumerated()
            .map { index, category -> (Int, CategoryBoard) in
                let entries = entriesByCategory[category] ?? []
                let high = entries.filter { $0.confidence == .high && !$0.isProtected }
                let review = entries.filter { $0.confidence != .high && !$0.isProtected }
                var highBytes = high.reduce(Int64(0)) { $0 + $1.item.sizeBytes }
                if category == .duplicates {
                    highBytes -= duplicatesKeeperBytes(high)
                }
                return (index, CategoryBoard(
                    category: category,
                    totalCount: entries.count,
                    totalBytes: entries.reduce(Int64(0)) { $0 + $1.item.sizeBytes },
                    highCount: high.count,
                    highBytes: highBytes,
                    reviewCount: review.count,
                    protectedCount: entries.filter { $0.isProtected }.count,
                    // 按 uri 排序取前 4：rows 无序（rowid 序漂移），稳定序防预览缩略图闪换
                    previewUris: entries.sorted { $0.item.uri < $1.item.uri }
                        .prefix(previewLimit)
                        .map { $0.item.uri },
                    coverage: coverageOf(category, items: items)
                ))
            }
            // highBytes 降序；等值按类目声明序（对齐 Kotlin sortedByDescending 稳定排序语义）
            .sorted { lhs, rhs in
                if lhs.1.highBytes != rhs.1.highBytes { return lhs.1.highBytes > rhs.1.highBytes }
                return lhs.0 < rhs.0
            }
            .map { $0.1 }
        return OrganizeBoard(
            categories: cards,
            // 互斥裁定保证一媒体一卡，high 求和即并集（AC-F1-1 回归防线）
            heroReclaimBytes: cards.reduce(Int64(0)) { $0 + $1.highBytes },
            heroReviewCount: cards.reduce(0) { $0 + $1.reviewCount }
        )
    }

    /// DUPLICATES keeper 扣减：hub「可释放」按每精确组留 1 张计（与详情页预选/全选按组
    /// 排除 1 张 keeper 同口径）。组内同 MD5 内容相同，扣组内最大 sizeBytes
    /// （防 first 落到 0 字节异常行）。
    /// 守卫 `group.count == exactDupGroupSize`：组全员都在建议集才扣。生产管线中聚类输入
    /// 已按库内 uri 收敛，组必然全员在列，本守卫仅防御直调 board 传部分列表的异常输入
    /// ——不扣时按全组字节计（高估方向，仅展示口径偏差，无安全风险）。
    /// 注：DUPLICATES 不经 ValueGuard（见 ValueGuard.guardedCategories），
    /// 精确组成员恒 HIGH 非 protected，「全员在列」即「组未被建议集拆散」。
    private static func duplicatesKeeperBytes(_ high: [ClassifiedItem]) -> Int64 {
        Dictionary(grouping: high, by: { $0.item.exactDupGroupKey })
            .filter { $0.key != nil }
            .values
            .filter { group in group.count == group.first?.item.exactDupGroupSize }
            .reduce(Int64(0)) { $0 + ($1.map { $0.item.sizeBytes }.max() ?? 0) }
    }

    /// 类目信号覆盖度：全库任一媒体持有该类目关键信号 → READY，否则 NEEDS_SCAN
    /// （驱动 hub「需先扫描」引导态，修复 v1 类目静默消失）。
    /// 路径/大小类信号（SCREEN_CONTENT/LARGE_FILES/DUPLICATES）数据源常备，恒 READY。
    static func coverageOf(_ category: OrganizeCategory, items: [OrganizeItem]) -> SignalCoverage {
        switch category {
        case .duplicates, .screenContent, .largeFiles:
            return .ready
        case .documents:
            return items.contains { $0.ocrText != nil || $0.labels != nil } ? .ready : .needsScan
        case .lowQualityPortraits:
            return items.contains { $0.faceQualityScore != nil } ? .ready : .needsScan
        case .lowQualityPhotos:
            // 信号集与 CategoryArbiter 准入一致（blur 或 exposure 任一持有即已覆盖）
            return items.contains { $0.blurScore != nil || $0.exposureScore != nil } ? .ready : .needsScan
        }
    }
}
