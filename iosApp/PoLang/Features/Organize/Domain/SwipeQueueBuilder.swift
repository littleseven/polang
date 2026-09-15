import Foundation

/// 手势快速整理（F2）队列构建纯函数：与整理中心类目页同一管线产出（口径同源，
/// 修复 v1 桶规则与类目页口径漂移）。废片桶只收 HIGH/MEDIUM 且非 protected 项
/// （LOW 弱信号与受保护项落 RECENT 兜底，由用户全手动决策）。
/// 桶内 captureDate 倒序；视频永不入队（在类目页处理：LARGE_FILES / 录屏落 SCREEN_CONTENT /
/// 重复视频落 DUPLICATES）。
/// 非三类废片类目（DUPLICATES/DOCUMENTS/LARGE_FILES）的照片同样落 RECENT 兜底
/// ——F2 只收截图与低质两类废片。
/// 直译 Android domain/swipe/SwipeQueueBuilder.kt。
enum SwipeQueueBuilder {

    /// 桶优先级声明顺序即出队顺序。
    private static let buckets: [(category: OrganizeCategory?, reason: SwipeReason)] = [
        (.screenContent, .screenshot),
        (.lowQualityPhotos, .blurry),
        (.lowQualityPortraits, .lowQualityPortrait),
        (nil, .recent), // 兜底桶：其余照片
    ]

    static func build(_ items: [OrganizeItem],
                      now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> [SwipeCandidate] {
        // 视频不做无效裁定（永不入队）；media_assets.uri 非唯一索引，重复行按 uri 去重
        // （只出一张卡，与类目详情页 VM 同防线；重复行保留最后一条快照，
        //  顺序位置取首次出现——对齐 Kotlin LinkedHashMap associateBy 语义）
        let photos = items.filter { !$0.isVideo }
        var uriOrder: [String] = []
        var photosByUri: [String: OrganizeItem] = [:]
        for item in photos {
            if photosByUri[item.uri] == nil { uriOrder.append(item.uri) }
            photosByUri[item.uri] = item
        }
        var classifiedByUri: [String: ClassifiedItem] = [:]
        for entry in OrganizeCategorizer.classifyAll(photos, now: now) {
            classifiedByUri[entry.item.uri] = entry
        }
        var grouped: [SwipeReason: [SwipeCandidate]] = [:]
        for bucket in buckets { grouped[bucket.reason] = [] }
        for uri in uriOrder {
            guard let item = photosByUri[uri] else { continue }
            let entry = classifiedByUri[uri]
            let wasteHit = entry != nil && !entry!.isProtected && entry!.confidence != .low
            let reason = buckets.first { bucket in
                bucket.category == nil || (wasteHit && entry?.category == bucket.category)
            }!.reason
            grouped[reason]!.append(SwipeCandidate(
                uri: item.uri,
                sizeBytes: item.sizeBytes,
                captureDate: item.captureDate,
                reason: reason
            ))
        }
        return buckets.flatMap { bucket in
            (grouped[bucket.reason] ?? []).sorted { $0.captureDate > $1.captureDate }
        }
    }
}
