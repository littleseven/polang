package com.mamba.picme.data.repository

import com.mamba.picme.data.local.dao.MediaFeedbackDao
import com.mamba.picme.domain.search.FeedbackScore

class MediaFeedbackRepositoryImpl(
    private val dao: MediaFeedbackDao
) : MediaFeedbackRepository {

    override suspend fun getFeedbackScores(queryText: String): List<FeedbackScore> {
        return dao.getFeedbackScoresForQuery(queryText).map { row ->
            FeedbackScore(
                mediaId = row.mediaId,
                likeCount = row.likeCount,
                dislikeCount = row.dislikeCount
            )
        }
    }
}
