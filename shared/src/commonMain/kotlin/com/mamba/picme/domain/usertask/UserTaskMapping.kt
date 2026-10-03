package com.mamba.picme.domain.usertask

/**
 * 协议状态纯推导（spec §4.1/§4.2）：动作集/活跃判据/目的地。UI 零逻辑，全部可单测。
 * 平台体系状态 → 协议状态的投影（ScanSessionState/DownloadStatus 等）属各端适配层，
 * 不进 commonMain（Android 侧为同包扩展函数，iOS 侧在 Swift 适配器内自写等价投影）。
 */
object UserTaskMapping {

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
