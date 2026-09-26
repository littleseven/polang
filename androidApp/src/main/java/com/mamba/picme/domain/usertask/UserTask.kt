package com.mamba.picme.domain.usertask

/** 用户任务协议模型（spec 2026-09-26-user-task-protocol-design.md §4）。 */
data class UserTask(
    val id: String,
    val kind: UserTaskKind,
    /** 体系自带名字（如下载的模型展示名），原样展示不参与 i18n；null 时 UI 按 kind 取 string resource */
    val displayName: String?,
    val status: UserTaskStatus,
    /** 值域 0..1；无法量化时 null（渲染不定进度条） */
    val progress: Float?,
    val progressText: String?,
    /** 预计剩余毫秒数；无法估计时 null */
    val etaMs: Long?,
    /** 不变量：仅 FAILED、或 COMPLETED + PARTIAL_FAILURES 时非空（spec §4.1「N 张失败附在 COMPLETED」） */
    val errorCode: UserTaskErrorCode?,
    val errorDetail: String?,
    /** 体系可收窄、不得超出 UserTaskMapping.actionsFor(status) 推导集 */
    val supportedActions: Set<UserTaskAction>,
    val destination: UserTaskDestination,
    val updatedAt: Long,
)

enum class UserTaskKind { TAG_SCAN, MODEL_DOWNLOAD }
enum class UserTaskStatus { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class UserTaskAction { PAUSE, RESUME, CANCEL, RETRY }
enum class UserTaskErrorCode { PROCESS_TERMINATED, PARTIAL_FAILURES }
enum class UserTaskDestination { TAG_SCAN_CONTROL, MODEL_CENTER }

/** 高频进度快照（仅内存，不落库——spec §5 混合注册表）。 */
data class TaskProgressSnapshot(
    val progress: Float?,
    val progressText: String?,
    val etaMs: Long?,
)
