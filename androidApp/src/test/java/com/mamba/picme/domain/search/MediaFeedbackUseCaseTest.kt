package com.mamba.picme.domain.search

import com.mamba.picme.data.repository.MediaFeedbackRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFeedbackUseCaseTest {

    private val repository: MediaFeedbackRepository = mockk(relaxed = true)
    private val useCase = MediaFeedbackUseCase(repository)

    @Test
    fun `calculateScoreDelta returns bonus for likes only`() {
        val score = FeedbackScore("media_1", likeCount = 2, dislikeCount = 0)

        val delta = useCase.calculateScoreDelta(score)

        assertEquals(0.30f, delta, 0.001f)
    }

    @Test
    fun `calculateScoreDelta returns negative for dislikes only`() {
        val score = FeedbackScore("media_1", likeCount = 0, dislikeCount = 1)

        val delta = useCase.calculateScoreDelta(score)

        assertEquals(-0.15f, delta, 0.001f)
    }

    @Test
    fun `calculateScoreDelta returns zero for null`() {
        assertEquals(0f, useCase.calculateScoreDelta(null), 0.001f)
    }

    @Test
    fun `getScoresForQuery returns map keyed by media id`() = runTest {
        coEvery { repository.getFeedbackScores("海边日落") } returns listOf(
            FeedbackScore("media_1", 1, 0),
            FeedbackScore("media_2", 0, 1)
        )

        val scores = useCase.getScoresForQuery("海边日落")

        assertEquals(2, scores.size)
        assertEquals(1, scores["media_1"]?.likeCount)
        assertEquals(1, scores["media_2"]?.dislikeCount)
    }
}
