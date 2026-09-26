package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.domain.tag.scan.ScanSessionState

/** 体系状态 → 协议状态的纯映射（spec §4.1/§4.2）；UI 零逻辑，全部可单测。 */
object UserTaskMapping {

    /** TAG 扫描会话 7 态 → 协议 6 态；IDLE = 无任务（返回 null 不发射）。 */
    fun fromTagScanState(state: ScanSessionState): UserTaskStatus? = when (state) {
        ScanSessionState.IDLE -> null
        ScanSessionState.RUNNING,
        ScanSessionState.PAUSING,
        ScanSessionState.CANCELLING -> UserTaskStatus.RUNNING
        ScanSessionState.PAUSED -> UserTaskStatus.PAUSED
        ScanSessionState.COMPLETED -> UserTaskStatus.COMPLETED
        ScanSessionState.CANCELLED -> UserTaskStatus.CANCELLED
    }

    fun fromDownloadStatus(status: DownloadStatus): UserTaskStatus = when (status) {
        DownloadStatus.PENDING -> UserTaskStatus.PENDING
        DownloadStatus.DOWNLOADING -> UserTaskStatus.RUNNING
        DownloadStatus.PAUSED -> UserTaskStatus.PAUSED
        DownloadStatus.COMPLETED -> UserTaskStatus.COMPLETED
        DownloadStatus.FAILED -> UserTaskStatus.FAILED
        DownloadStatus.CANCELLED -> UserTaskStatus.CANCELLED
    }

    fun actionsFor(status: UserTaskStatus): Set<UserTaskAction> = when (status) {
        UserTaskStatus.PENDING -> setOf(UserTaskAction.CANCEL)
        UserTaskStatus.RUNNING -> setOf(UserTaskAction.PAUSE, UserTaskAction.CANCEL)
        UserTaskStatus.PAUSED -> setOf(UserTaskAction.RESUME, UserTaskAction.CANCEL)
        UserTaskStatus.FAILED -> setOf(UserTaskAction.RETRY)
        UserTaskStatus.COMPLETED, UserTaskStatus.CANCELLED -> emptySet()
    }

    fun isActive(status: UserTaskStatus): Boolean =
        status == UserTaskStatus.PENDING || status == UserTaskStatus.RUNNING || status == UserTaskStatus.PAUSED

    fun destinationFor(kind: UserTaskKind): UserTaskDestination = when (kind) {
        UserTaskKind.TAG_SCAN -> UserTaskDestination.TAG_SCAN_CONTROL
        UserTaskKind.MODEL_DOWNLOAD -> UserTaskDestination.MODEL_CENTER
    }
}
