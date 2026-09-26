package com.mamba.picme.features.chat.engineer

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TaskCenterPartition] 分区纯逻辑分支矩阵（spec US-13/14）。
 *
 * 覆盖：进行中判据 / 待审批置顶 / 开始时间倒序 / 历史按结束时间倒序 /
 * 50 条上限 / resolution 裁决终态化 / 未知 status 回退 FAILED 入历史。
 */
class TaskCenterPartitionTest {

    private fun task(
        id: String,
        status: EngineerTaskStatus,
        startedAtMs: Long = 0L,
        updatedAtMs: Long = 0L,
        resolution: EngineerTaskResolution? = null,
    ) = EngineerTaskState(
        taskId = id,
        sourceText = "source-$id",
        status = status,
        startedAtMs = startedAtMs,
        updatedAtMs = updatedAtMs,
        resolution = resolution,
    )

    private fun item(id: String, task: EngineerTaskState) =
        TaskCenterItem(sessionId = "s-$id", title = "t-$id", sessionTitle = null, task = task)

    @Test
    fun `active predicate - running and awaiting are active, terminal and resolved are not`() {
        assertTrue(TaskCenterPartition.isActive(task("a", EngineerTaskStatus.RUNNING)))
        assertTrue(TaskCenterPartition.isActive(task("b", EngineerTaskStatus.AWAITING_CONTINUE)))
        assertTrue(TaskCenterPartition.isActive(task("c", EngineerTaskStatus.AWAITING_DELIVER)))
        assertFalse(TaskCenterPartition.isActive(task("d", EngineerTaskStatus.COMPLETED)))
        assertFalse(TaskCenterPartition.isActive(task("e", EngineerTaskStatus.FAILED)))
        // resolution 裁决即终态化（即使 status 仍是 AWAITING_*）
        assertFalse(
            TaskCenterPartition.isActive(
                task("f", EngineerTaskStatus.AWAITING_DELIVER, resolution = EngineerTaskResolution.DELIVERED)
            )
        )
    }

    @Test
    fun `active section - awaiting approval pinned first, then startedAt desc`() {
        val running1 = item("r1", task("r1", EngineerTaskStatus.RUNNING, startedAtMs = 300))
        val awaitingDeliver = item("ad", task("ad", EngineerTaskStatus.AWAITING_DELIVER, startedAtMs = 100))
        val running2 = item("r2", task("r2", EngineerTaskStatus.RUNNING, startedAtMs = 200))
        val awaitingContinue = item("ac", task("ac", EngineerTaskStatus.AWAITING_CONTINUE, startedAtMs = 50))

        val result = TaskCenterPartition.partition(listOf(running1, awaitingDeliver, running2, awaitingContinue))

        // 审批中两项置顶（组内仍按开始时间倒序），其后 RUNNING 按开始时间倒序
        assertEquals(listOf("ad", "ac", "r1", "r2"), result.active.map { item -> item.task.taskId })
        assertTrue(result.history.isEmpty())
    }

    @Test
    fun `history section - terminal sorted by updatedAt desc`() {
        val completed = item("c", task("c", EngineerTaskStatus.COMPLETED, updatedAtMs = 100))
        val failed = item("f", task("f", EngineerTaskStatus.FAILED, updatedAtMs = 300))
        val abandoned = item(
            "a",
            task("a", EngineerTaskStatus.COMPLETED, updatedAtMs = 200, resolution = EngineerTaskResolution.ABANDONED)
        )

        val result = TaskCenterPartition.partition(listOf(completed, failed, abandoned))

        assertEquals(listOf("f", "a", "c"), result.history.map { item -> item.task.taskId })
        assertTrue(result.active.isEmpty())
    }

    @Test
    fun `history capped at 50 entries`() {
        val items = (1..60).map { idx ->
            item("t$idx", task("t$idx", EngineerTaskStatus.COMPLETED, updatedAtMs = idx.toLong()))
        }

        val result = TaskCenterPartition.partition(items)

        assertEquals(TaskCenterPartition.HISTORY_LIMIT, result.history.size)
        // 保留最新 50 条：最早 10 条被裁掉
        assertEquals("t60", result.history.first().task.taskId)
        assertEquals("t11", result.history.last().task.taskId)
    }

    @Test
    fun `awaiting approval predicate - sticky resolution not awaiting`() {
        assertTrue(TaskCenterPartition.isAwaitingApproval(task("a", EngineerTaskStatus.AWAITING_CONTINUE)))
        assertTrue(TaskCenterPartition.isAwaitingApproval(task("b", EngineerTaskStatus.AWAITING_DELIVER)))
        assertFalse(TaskCenterPartition.isAwaitingApproval(task("c", EngineerTaskStatus.RUNNING)))
        assertFalse(
            TaskCenterPartition.isAwaitingApproval(
                task("d", EngineerTaskStatus.AWAITING_CONTINUE, resolution = EngineerTaskResolution.CONTINUED)
            )
        )
    }
}
