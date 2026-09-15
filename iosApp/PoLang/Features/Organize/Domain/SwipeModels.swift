import Foundation

// MARK: - 手势快速整理（F2）领域模型（直译 Android domain/swipe/SwipeModels.kt）

/// 入列原因（卡片角标 + 可解释性）。
/// ⚠️ 声明序即出队桶序：BLURRY 先于 LOW_QUALITY_PORTRAIT 延续 v1 UX
/// （organize.yaml §5/§8 bucket_order_v1_ux，有意偏差不「修复」对齐）。
enum SwipeReason: String, CaseIterable {
    case screenshot = "SCREENSHOT"
    case blurry = "BLURRY"
    case lowQualityPortrait = "LOW_QUALITY_PORTRAIT"
    case recent = "RECENT"
}

struct SwipeCandidate {
    let uri: String
    let sizeBytes: Int64
    let captureDate: Int64
    let reason: SwipeReason
}
