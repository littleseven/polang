package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.domain.tag.scan.ScanSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTaskMappingTest {

    @Test
    fun `tag scan 会话态映射全分支`() {
        assertNull(UserTaskMapping.fromTagScanState(ScanSessionState.IDLE))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromTagScanState(ScanSessionState.RUNNING))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromTagScanState(ScanSessionState.PAUSING))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromTagScanState(ScanSessionState.CANCELLING))
        assertEquals(UserTaskStatus.PAUSED, UserTaskMapping.fromTagScanState(ScanSessionState.PAUSED))
        assertEquals(UserTaskStatus.COMPLETED, UserTaskMapping.fromTagScanState(ScanSessionState.COMPLETED))
        assertEquals(UserTaskStatus.CANCELLED, UserTaskMapping.fromTagScanState(ScanSessionState.CANCELLED))
    }

    @Test
    fun `下载状态 1 比 1 映射`() {
        assertEquals(UserTaskStatus.PENDING, UserTaskMapping.fromDownloadStatus(DownloadStatus.PENDING))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromDownloadStatus(DownloadStatus.DOWNLOADING))
        assertEquals(UserTaskStatus.PAUSED, UserTaskMapping.fromDownloadStatus(DownloadStatus.PAUSED))
        assertEquals(UserTaskStatus.COMPLETED, UserTaskMapping.fromDownloadStatus(DownloadStatus.COMPLETED))
        assertEquals(UserTaskStatus.FAILED, UserTaskMapping.fromDownloadStatus(DownloadStatus.FAILED))
        assertEquals(UserTaskStatus.CANCELLED, UserTaskMapping.fromDownloadStatus(DownloadStatus.CANCELLED))
    }

    @Test
    fun `能力集推导矩阵`() {
        assertEquals(setOf(UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.PENDING))
        assertEquals(setOf(UserTaskAction.PAUSE, UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.RUNNING))
        assertEquals(setOf(UserTaskAction.RESUME, UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.PAUSED))
        assertEquals(setOf(UserTaskAction.RETRY), UserTaskMapping.actionsFor(UserTaskStatus.FAILED))
        assertTrue(UserTaskMapping.actionsFor(UserTaskStatus.COMPLETED).isEmpty())
        assertTrue(UserTaskMapping.actionsFor(UserTaskStatus.CANCELLED).isEmpty())
    }

    @Test
    fun `活动态判据与 destination 推导`() {
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.PENDING))
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.RUNNING))
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.PAUSED))
        assertFalse(UserTaskMapping.isActive(UserTaskStatus.COMPLETED))
        assertFalse(UserTaskMapping.isActive(UserTaskStatus.FAILED))
        assertFalse(UserTaskMapping.isActive(UserTaskStatus.CANCELLED))
        assertEquals(UserTaskDestination.TAG_SCAN_CONTROL, UserTaskMapping.destinationFor(UserTaskKind.TAG_SCAN))
        assertEquals(UserTaskDestination.MODEL_CENTER, UserTaskMapping.destinationFor(UserTaskKind.MODEL_DOWNLOAD))
    }
}
