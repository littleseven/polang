package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [overlayLiveTaskState] 任务卡 live 态挂载（ADR-016 M2，spec §5.2）钉桩：
 * TaskCard part 同 id 原位覆写 + legacy engineerTask 字段自 part 投影（同源防漂移）；
 * 无操作路径引用相等原样返回（零分配）。
 */
class TaskCardOverlayTest {

    private fun task(
        taskId: String = "t-1",
        status: EngineerTaskStatus = EngineerTaskStatus.RUNNING,
    ) = EngineerTaskState(
        taskId = taskId,
        sourceText = "修 bug",
        status = status,
        startedAtMs = 1,
        updatedAtMs = 2,
    )

    private fun taskMessage(part: MessagePart = MessagePart.TaskCard("p0", "t-1", ToolPartState.INPUT_AVAILABLE, task())) =
        ChatMessage(
            id = "m-task",
            type = ChatMessageType.TASK_CARD,
            content = "修 bug",
            engineerTask = task(),
            parts = listOf(
                MessagePart.Text("p-text", "占位", PartState.DONE),
                part,
            ),
        )

    @Test
    fun `message without task card part is returned as-is`() {
        val message = ChatMessage(id = "m", type = ChatMessageType.AGENT_TEXT, content = "x")
        assertSame(message, message.overlayLiveTaskState(mapOf("t-1" to task())))
    }

    @Test
    fun `task card without live state is returned as-is`() {
        val message = taskMessage()
        assertSame(message, message.overlayLiveTaskState(emptyMap()))
        assertSame(message, message.overlayLiveTaskState(mapOf("other" to task("other"))))
    }

    @Test
    fun `unchanged live state is returned as-is`() {
        val message = taskMessage()
        assertSame(message, message.overlayLiveTaskState(mapOf("t-1" to task())))
    }

    @Test
    fun `live state overwrites the part in place and projects legacy field`() {
        val message = taskMessage()
        val live = task(status = EngineerTaskStatus.COMPLETED)
        val overlaid = message.overlayLiveTaskState(mapOf("t-1" to live))

        // parts 原位覆写：块序与其他 part 不动
        assertEquals(2, overlaid.parts.size)
        assertEquals(message.parts[0], overlaid.parts[0])
        val card = overlaid.parts[1]
        assertIs<MessagePart.TaskCard>(card)
        assertEquals(live, card.task)
        assertEquals("p0", card.partId)
        // 状态机投影随迁：COMPLETED → OUTPUT_AVAILABLE
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, card.state)
        // legacy 渲染字段自 part 投影（M4 前 UI 仍读它，二者同源）
        assertEquals(live, overlaid.engineerTask)
    }

    @Test
    fun `failed live state projects output error on the part`() {
        val message = taskMessage()
        val live = task(status = EngineerTaskStatus.FAILED).copy(errorSummary = "编译失败")
        val overlaid = message.overlayLiveTaskState(mapOf("t-1" to live))
        val card = overlaid.parts[1] as MessagePart.TaskCard
        assertEquals(ToolPartState.OUTPUT_ERROR, card.state)
        assertEquals("编译失败", card.task.errorSummary)
    }

    @Test
    fun `original message is not mutated`() {
        val message = taskMessage()
        message.overlayLiveTaskState(mapOf("t-1" to task(status = EngineerTaskStatus.COMPLETED)))
        assertEquals(EngineerTaskStatus.RUNNING, (message.parts[1] as MessagePart.TaskCard).task.status)
        assertEquals(EngineerTaskStatus.RUNNING, message.engineerTask?.status)
    }
}
