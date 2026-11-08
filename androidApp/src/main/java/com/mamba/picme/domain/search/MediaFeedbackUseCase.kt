package com.mamba.picme.domain.search

import com.mamba.picme.data.repository.MediaFeedbackRepository

/**
 * 媒体反馈读路径（横滑卡退役后写路径随卡片反馈按钮一并删除）：
 * 存量 like/dislike 分数继续参与 [MediaSearchEngine] 排序加权。
 */
class MediaFeedbackUseCase(
    private val repository: MediaFeedbackRepository
) {
    suspend fun getScoresForQuery(queryText: String): Map<String, FeedbackScore> {
        return repository.getFeedbackScores(queryText).associateBy { it.mediaId }
    }

    fun calculateScoreDelta(score: FeedbackScore?): Float {
        if (score == null) return 0f
        return score.likeCount * LIKE_BONUS - score.dislikeCount * DISLIKE_PENALTY
    }

    companion object {
        const val LIKE_BONUS = 0.15f
        const val DISLIKE_PENALTY = 0.15f
    }
}
