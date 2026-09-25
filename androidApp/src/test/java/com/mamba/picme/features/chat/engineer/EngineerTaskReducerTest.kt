package com.mamba.picme.features.chat.engineer

import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineerTaskReducerTest {

    private fun running() = EngineerTaskReducer.initial("task_1", "修 bug", nowMs = 1000L)

    @Test
    fun `initial is RUNNING with empty progress`() {
        val state = running()
        assertEquals(EngineerTaskStatus.RUNNING, state.status)
        assertEquals(0, state.turns)
        assertNull(state.sid)
    }

    @Test
    fun `session event records sid`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Session("a1b2c3d4e5f6"), canDeliver = false, nowMs = 1100L)
        assertEquals("a1b2c3d4e5f6", next.sid)
    }

    @Test
    fun `tool use updates stage and history with cap`() {
        var state = running()
        repeat(25) { idx ->
            state = EngineerTaskReducer.reduce(state, ClaudeEvent.ToolUse("Tool$idx", JSONObject()), canDeliver = false, nowMs = 1100L + idx)
        }
        assertEquals("Tool24", state.stage)
        assertEquals(20, state.recentStages.size)
        assertEquals("Tool5", state.recentStages.first())
    }

    @Test
    fun `assistant text and tool result do not change card`() {
        val state = running()
        assertEquals(state, EngineerTaskReducer.reduce(state, ClaudeEvent.AssistantText("hello"), canDeliver = false, nowMs = 1100L))
        assertEquals(state, EngineerTaskReducer.reduce(state, ClaudeEvent.ToolResult(true, "ok"), canDeliver = false, nowMs = 1100L))
    }

    @Test
    fun `file change increments counter`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.FileChange("A.kt", "edit"), canDeliver = false, nowMs = 1100L)
        assertEquals(1, next.fileChangeCount)
    }

    @Test
    fun `cost fills turns and cents`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Cost(turns = 5, cents = 37), canDeliver = false, nowMs = 1100L)
        assertEquals(5, next.turns)
        assertEquals(37, next.costCents)
    }

    @Test
    fun `done truncated awaits continue`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Done(turns = 9, truncated = true, reason = "max_turns"), canDeliver = true, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.AWAITING_CONTINUE, next.status)
        assertEquals("max_turns", next.truncatedReason)
        assertEquals(9, next.turns)
    }

    @Test
    fun `error truncated awaits continue`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Error("timeout", truncated = true, reason = "phase_timeout"), canDeliver = false, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.AWAITING_CONTINUE, next.status)
        assertEquals("phase_timeout", next.truncatedReason)
    }

    @Test
    fun `error plain fails with summary`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Error("boom"), canDeliver = false, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.FAILED, next.status)
        assertEquals("boom", next.errorSummary)
    }

    @Test
    fun `done with file change and canDeliver awaits deliver`() {
        val withFile = EngineerTaskReducer.reduce(running(), ClaudeEvent.FileChange("A.kt", "edit"), canDeliver = true, nowMs = 1050L)
        val next = EngineerTaskReducer.reduce(withFile, ClaudeEvent.Done(turns = 3), canDeliver = true, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.AWAITING_DELIVER, next.status)
    }

    @Test
    fun `done with file change but no permission completes`() {
        val withFile = EngineerTaskReducer.reduce(running(), ClaudeEvent.FileChange("A.kt", "edit"), canDeliver = false, nowMs = 1050L)
        val next = EngineerTaskReducer.reduce(withFile, ClaudeEvent.Done(turns = 3), canDeliver = false, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.COMPLETED, next.status)
    }

    @Test
    fun `done plain completes`() {
        val next = EngineerTaskReducer.reduce(running(), ClaudeEvent.Done(turns = 2), canDeliver = true, nowMs = 1100L)
        assertEquals(EngineerTaskStatus.COMPLETED, next.status)
    }

    @Test
    fun `resolution is terminal and idempotent`() {
        val awaiting = EngineerTaskReducer.reduce(running(), ClaudeEvent.Done(turns = 1, truncated = true, reason = "max_turns"), canDeliver = true, nowMs = 1100L)
        val resolved = EngineerTaskReducer.resolved(awaiting, EngineerTaskResolution.CONTINUED, nowMs = 1200L)
        assertEquals(EngineerTaskStatus.COMPLETED, resolved.status)
        assertEquals(EngineerTaskResolution.CONTINUED, resolved.resolution)
        val again = EngineerTaskReducer.resolved(resolved, EngineerTaskResolution.ABANDONED, nowMs = 1300L)
        assertEquals(EngineerTaskResolution.CONTINUED, again.resolution)
    }

    @Test
    fun `summarize takes first non blank line capped`() {
        assertEquals("结果", EngineerTaskReducer.summarize("\n\n结果\n第二行"))
        assertEquals(80, EngineerTaskReducer.summarize("x".repeat(200)).length)
        assertEquals("", EngineerTaskReducer.summarize(""))
    }

    @Test
    fun `only assistant text is non structural`() {
        assertTrue(!EngineerTaskReducer.isStructural(ClaudeEvent.AssistantText("x")))
        assertTrue(EngineerTaskReducer.isStructural(ClaudeEvent.Done()))
        assertTrue(EngineerTaskReducer.isStructural(ClaudeEvent.Session("a1b2c3d4e5f6")))
    }
}
