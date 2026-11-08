package com.mamba.picme.data.repository

import com.mamba.picme.domain.search.FeedbackScore

/**
 * 媒体反馈存储（横滑卡退役后只保留读路径：存量分数供搜索排序加权消费）。
 */
interface MediaFeedbackRepository {
    suspend fun getFeedbackScores(queryText: String): List<FeedbackScore>
}
