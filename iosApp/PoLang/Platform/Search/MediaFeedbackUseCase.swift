import Foundation

// MARK: - 反馈加权（读路径；双端契约 SSOT: contracts.md §8）
//
// 逐字对齐 Android `MediaFeedbackUseCase.kt` 现存读路径（LIKE_BONUS/DISLIKE_PENALTY=0.15、
// query_text 精确等值 R10）。
//
// 2026-11-08 横滑相册卡退役（终审 🟡6）：反馈写路径（record/resolveTarget/FeedbackTarget/
// FeedbackAction）随唯一生产消费方（横滑卡三反馈钮）整删——对齐 Android「删写留读」处置；
// 排序加权读路径保留（搜索引擎 scoreAdjustments 消费）。

final class MediaFeedbackUseCase {

    /// 契约 §8：LIKE 加分（MediaFeedbackUseCase.kt）
    static let likeBonus: Float = 0.15
    /// 契约 §8：DISLIKE 减分（MediaFeedbackUseCase.kt）
    static let dislikePenalty: Float = 0.15

    private let db: TagDatabase

    init(db: TagDatabase) {
        self.db = db
    }

    /// 契约 §8：`query_text` 精确等值取 LIKE/DISLIKE 计数 → 排序调整分
    /// `delta = likeCount * 0.15 - dislikeCount * 0.15`；delta == 0 不出现在结果里（"不动"）。
    /// 返回 mediaId（String，= media_assets.id 的十进制串）→ 调整分。
    func scoreAdjustments(queryText: String) -> [String: Float] {
        var out: [String: Float] = [:]
        for (mediaId, counts) in db.feedbackLikeDislikeCounts(queryText: queryText) {
            let delta = Float(counts.likeCount) * Self.likeBonus
                      - Float(counts.dislikeCount) * Self.dislikePenalty
            if delta != 0 { out[mediaId] = delta }
        }
        return out
    }
}