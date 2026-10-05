package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.domain.tag.scan.ScanSessionState

/**
 * 体系状态 → 协议状态的纯映射（spec §4.1/§4.2）；UI 零逻辑，全部可单测。
 * 协议侧纯推导（actionsFor/isActive/destinationFor）在 shared `UserTaskMapping`（本 object 由 shared 透出）；
 * 本文件仅承载依赖 Android 体系类型（ScanSessionState/DownloadStatus）的投影扩展——
 * 扩展挂 shared object，调用形态 `UserTaskMapping.fromXxx(...)` 不变。
 */

/** TAG 扫描会话 7 态 → 协议 6 态；IDLE = 无任务（返回 null 不发射）。 */
fun UserTaskMapping.fromTagScanState(state: ScanSessionState): UserTaskStatus? = when (state) {
    ScanSessionState.IDLE -> null
    ScanSessionState.RUNNING,
    ScanSessionState.PAUSING,
    ScanSessionState.CANCELLING -> UserTaskStatus.RUNNING
    ScanSessionState.PAUSED -> UserTaskStatus.PAUSED
    ScanSessionState.COMPLETED -> UserTaskStatus.COMPLETED
    ScanSessionState.CANCELLED -> UserTaskStatus.CANCELLED
}

fun UserTaskMapping.fromDownloadStatus(status: DownloadStatus): UserTaskStatus = when (status) {
    DownloadStatus.PENDING -> UserTaskStatus.PENDING
    DownloadStatus.DOWNLOADING -> UserTaskStatus.RUNNING
    DownloadStatus.PAUSED -> UserTaskStatus.PAUSED
    DownloadStatus.COMPLETED -> UserTaskStatus.COMPLETED
    DownloadStatus.FAILED -> UserTaskStatus.FAILED
    DownloadStatus.CANCELLED -> UserTaskStatus.CANCELLED
}
