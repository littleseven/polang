package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描态主状态卡状态机映射（spec 2026-10-01-scan-progress-ux-redesign §7 边界态矩阵）。
 * UI 零逻辑：标题/按钮集/叙述行可见性全部由本映射决定。
 */
class ScanCardUiModelTest {

    private fun session(
        state: ScanSessionState,
        pass: TagScanPass? = TagScanPass.IMAGE_TAGGING,
        processed: Int = 128,
        total: Int = 500,
        failed: Int = 0,
        etaMs: Long? = 360_000L,
    ) = TagScanSessionProgress(
        sessionId = "s1", state = state, currentPass = pass,
        processed = processed, total = total, pending = total - processed,
        failed = failed, estimatedRemainingMs = etaMs,
    )

    @Test
    fun `RUNNING maps stage from current pass with pause and cancel`() {
        val model = scanCardUiModel(session(ScanSessionState.RUNNING, pass = TagScanPass.FACE_DETECTION))!!
        assertEquals(ScanStage.FACE, model.stage)
        assertEquals(ScanCardAction.PAUSE, model.primaryAction)
        assertTrue(model.cancelEnabled)
        assertFalse(model.isPaused)
        assertFalse(model.isTerminalWithFailures)
        assertEquals(ScanNarrative(128, 500, 360_000L), model.narrative)
    }

    @Test
    fun `null current pass maps to PREPARING stage`() {
        val model = scanCardUiModel(session(ScanSessionState.RUNNING, pass = null))!!
        assertEquals(ScanStage.PREPARING, model.stage)
    }

    @Test
    fun `DBSCAN and semantic passes map to their stages`() {
        assertEquals(ScanStage.CLUSTER, scanCardUiModel(session(ScanSessionState.RUNNING, pass = TagScanPass.DBSCAN))!!.stage)
        assertEquals(ScanStage.CONTENT, scanCardUiModel(session(ScanSessionState.RUNNING, pass = TagScanPass.IMAGE_TAGGING))!!.stage)
        assertEquals(ScanStage.SEMANTIC, scanCardUiModel(session(ScanSessionState.RUNNING, pass = TagScanPass.MOBILE_CLIP_ENCODING))!!.stage)
    }

    @Test
    fun `PAUSING disables primary action but keeps cancel`() {
        val model = scanCardUiModel(session(ScanSessionState.PAUSING))!!
        assertEquals(ScanCardAction.NONE, model.primaryAction)
        assertTrue(model.cancelEnabled)
        assertFalse(model.isPaused)
    }

    @Test
    fun `PAUSED offers resume and drops eta from narrative`() {
        val model = scanCardUiModel(session(ScanSessionState.PAUSED))!!
        assertTrue(model.isPaused)
        assertEquals(ScanCardAction.RESUME, model.primaryAction)
        assertTrue(model.cancelEnabled)
        // 暂停时 ETA 无意义：叙述行只保留「第 x/y 张」
        assertEquals(ScanNarrative(128, 500, null), model.narrative)
    }

    @Test
    fun `CANCELLING disables everything`() {
        val model = scanCardUiModel(session(ScanSessionState.CANCELLING))!!
        assertEquals(ScanCardAction.NONE, model.primaryAction)
        assertFalse(model.cancelEnabled)
    }

    @Test
    fun `COMPLETED without failures hides card`() {
        assertNull(scanCardUiModel(session(ScanSessionState.COMPLETED, processed = 500)))
    }

    @Test
    fun `COMPLETED with failures keeps terminal card with retry action`() {
        val model = scanCardUiModel(session(ScanSessionState.COMPLETED, processed = 498, failed = 2))!!
        assertTrue(model.isTerminalWithFailures)
        assertEquals(ScanCardAction.RETRY_FAILED, model.primaryAction)
        assertFalse(model.cancelEnabled)
        assertEquals(2, model.failedCount)
        assertNull(model.narrative)
    }

    @Test
    fun `CANCELLED and IDLE hide card`() {
        assertNull(scanCardUiModel(session(ScanSessionState.CANCELLED)))
        assertNull(scanCardUiModel(session(ScanSessionState.IDLE)))
    }

    @Test
    fun `zero total session has no narrative line`() {
        val model = scanCardUiModel(session(ScanSessionState.RUNNING, processed = 0, total = 0))!!
        assertNull(model.narrative)
    }

    @Test
    fun `active state retains last known pass when caller omits it`() {
        // 暂停/过渡态调用点不重传阶段：保留上一帧，标题才不会回退成「准备中」
        assertEquals(
            TagScanPass.FACE_DETECTION,
            retainedCurrentPass(ScanSessionState.PAUSED, incoming = null, previous = TagScanPass.FACE_DETECTION),
        )
        assertEquals(
            TagScanPass.FACE_DETECTION,
            retainedCurrentPass(ScanSessionState.PAUSING, incoming = null, previous = TagScanPass.FACE_DETECTION),
        )
    }

    @Test
    fun `terminal and idle states do not retain stale pass`() {
        assertNull(retainedCurrentPass(ScanSessionState.IDLE, incoming = null, previous = TagScanPass.FACE_DETECTION))
        assertNull(retainedCurrentPass(ScanSessionState.CANCELLED, incoming = null, previous = TagScanPass.FACE_DETECTION))
        assertNull(retainedCurrentPass(ScanSessionState.COMPLETED, incoming = null, previous = TagScanPass.FACE_DETECTION))
    }

    @Test
    fun `explicit incoming pass always wins`() {
        assertEquals(
            TagScanPass.IMAGE_TAGGING,
            retainedCurrentPass(ScanSessionState.RUNNING, incoming = TagScanPass.IMAGE_TAGGING, previous = TagScanPass.FACE_DETECTION),
        )
        assertNull(retainedCurrentPass(ScanSessionState.RUNNING, incoming = null, previous = null))
    }
}
