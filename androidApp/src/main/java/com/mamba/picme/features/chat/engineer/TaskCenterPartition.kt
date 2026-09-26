package com.mamba.picme.features.chat.engineer

import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/** 任务中心列表项：任务状态 + 所属会话上下文（跨会话聚合，US-15 回锚需要 sessionId）。 */
data class TaskCenterItem(
    val sessionId: String,
    val title: String,
    val sessionTitle: String?,
    val task: EngineerTaskState,
)

/** 任务中心分区结果（spec US-13）：进行中（含待审批）+ 历史（封顶 [TaskCenterPartition.HISTORY_LIMIT] 条）。 */
data class TaskCenterList(
    val active: List<TaskCenterItem>,
    val history: List<TaskCenterItem>,
)

/**
 * 任务中心分区纯逻辑（spec US-13/14；测试决策首层接缝，对齐 EngineerTaskReducer 先例）。
 * 输入为已解析的任务——Room metadata 的 org.json 解析是平台边界，由调用方（TaskCenterViewModel）完成。
 */
object TaskCenterPartition {

    const val HISTORY_LIMIT = 50

    /** 进行中判据：未裁决且未达终态（resolution 裁决即终态化，US-9）。 */
    fun isActive(task: EngineerTaskState): Boolean =
        task.resolution == null && when (task.status) {
            EngineerTaskStatus.RUNNING,
            EngineerTaskStatus.AWAITING_CONTINUE,
            EngineerTaskStatus.AWAITING_DELIVER -> true
            EngineerTaskStatus.COMPLETED,
            EngineerTaskStatus.FAILED -> false
        }

    /** 待审批判据（US-14 高亮置顶 + 动作按钮）。 */
    fun isAwaitingApproval(task: EngineerTaskState): Boolean =
        task.resolution == null &&
            (task.status == EngineerTaskStatus.AWAITING_CONTINUE ||
                task.status == EngineerTaskStatus.AWAITING_DELIVER)

    /**
     * 分区：active = 审批中置顶、其余按开始时间倒序（US-13/14）；
     * history = 终态/已裁决按结束时间倒序、取前 [HISTORY_LIMIT] 条（US-13）。
     */
    fun partition(items: List<TaskCenterItem>): TaskCenterList {
        val (active, history) = items.partition { item -> isActive(item.task) }
        return TaskCenterList(
            active = active.sortedWith(
                compareByDescending<TaskCenterItem> { item -> isAwaitingApproval(item.task) }
                    .thenByDescending { item -> item.task.startedAtMs }
            ),
            history = history
                .sortedByDescending { item -> item.task.updatedAtMs }
                .take(HISTORY_LIMIT),
        )
    }
}
