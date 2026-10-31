package com.mamba.picme.features.cloudagent

import com.mamba.picme.domain.chat.AgentStepStatus
import com.mamba.picme.domain.chat.AgentStepUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStepTimelineTest {

    @Test
    fun `start appends running step and finish marks success`() {
        val timeline = AgentStepTimeline()
        timeline.start(1L, "search_media")
        timeline.start(2L, "get_gallery_summary")
        assertEquals(
            listOf(
                AgentStepUi(1L, "search_media", AgentStepStatus.RUNNING),
                AgentStepUi(2L, "get_gallery_summary", AgentStepStatus.RUNNING),
            ),
            timeline.steps,
        )
        val finished = timeline.finish(1L, ok = true)
        assertEquals(AgentStepStatus.SUCCESS, finished?.status)
        assertEquals(AgentStepStatus.SUCCESS, timeline.steps[0].status)
        assertEquals(AgentStepStatus.RUNNING, timeline.steps[1].status)
    }

    @Test
    fun `finish with ok=false marks FAILED`() {
        val timeline = AgentStepTimeline()
        timeline.start(1L, "search_media")
        val finished = timeline.finish(1L, ok = false)
        assertEquals(AgentStepStatus.FAILED, finished?.status)
        assertEquals(AgentStepStatus.FAILED, timeline.steps.single().status)
    }

    @Test
    fun `finish unknown stepId returns null and is ignored`() {
        // 上一回合 pending 重放场景：未知 id 静默忽略，不改变任何状态
        val timeline = AgentStepTimeline()
        timeline.start(1L, "search_media")
        val snapshot = timeline.steps
        assertNull(timeline.finish(99L, ok = true))
        assertEquals(snapshot, timeline.steps)
    }

    @Test
    fun `duplicate start with same id is idempotent`() {
        val timeline = AgentStepTimeline()
        timeline.start(1L, "search_media")
        timeline.start(1L, "other_label")
        assertEquals(
            listOf(AgentStepUi(1L, "search_media", AgentStepStatus.RUNNING)),
            timeline.steps,
        )
    }

    @Test
    fun `finish already finished step returns null`() {
        val timeline = AgentStepTimeline()
        timeline.start(1L, "search_media")
        timeline.finish(1L, ok = true)
        val snapshot = timeline.steps
        assertNull(timeline.finish(1L, ok = false))
        assertEquals(snapshot, timeline.steps)
    }

    @Test
    fun `finish returns updated step with duration recorded`() {
        // spec §5.5 结构化日志的耗时来源：finish 返回携带 durationMs 的更新后步骤
        val timeline = AgentStepTimeline()
        timeline.start(7L, "search_media")
        val finished = timeline.finish(7L, ok = true)
        assertNotNull(finished)
        assertEquals(AgentStepStatus.SUCCESS, finished!!.status)
        val duration = finished.durationMs
        assertNotNull(duration)
        assertTrue(duration!! >= 0L)
        assertEquals(duration, timeline.steps.single().durationMs)
    }
}
