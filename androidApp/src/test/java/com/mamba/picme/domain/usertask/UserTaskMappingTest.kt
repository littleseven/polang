package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.domain.tag.scan.ScanSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 体系状态投影扩展（Android 侧 [UserTaskMapping] 残留半）分支矩阵。
 * 纯推导（actionsFor/isActive/destinationFor）的用例已随迁 shared commonTest。
 */
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
}
