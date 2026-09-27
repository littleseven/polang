package com.mamba.picme.domain.chat

/**
 * 任务卡 live 状态挂载（ADR-016 M2，spec §5.2）：SSE/状态流更新走 TaskCard part
 * **同 id 原位覆写**，legacy 渲染字段 [ChatMessage.engineerTask] 自 part 投影
 * （M4 渲染切换前 UI 仍读它，二者同源防漂移）。
 *
 * 挂载点是 parts——持久化侧由 M1 双写保证 partsJson 随迁（persistEngineerTask 回写
 * metadata 时 withPartsJson 重算），内存侧高频 live 态由本函数覆写（500ms 节流在
 * 调用方 displayMessages 管线）。
 *
 * 纯函数：非任务卡消息 / 无 live 态 / 状态未变 → 原样返回（引用相等，零分配）。
 */

/** 消息内首个 TaskCard part 的 live 态原位覆写（无 TaskCard part 的消息不触碰）。 */
fun ChatMessage.overlayLiveTaskState(live: Map<String, EngineerTaskState>): ChatMessage {
    val index = parts.indexOfFirst { it is MessagePart.TaskCard }
    if (index < 0) return this
    val part = parts[index] as MessagePart.TaskCard
    val liveState = live[part.task.taskId] ?: return this
    if (liveState == part.task && engineerTask == liveState) return this
    val newPart = part.copy(task = liveState, state = liveState.status.toToolPartState())
    return copy(
        parts = parts.toMutableList().also { it[index] = newPart },
        // legacy 渲染字段自 part 投影：UI（M4 前）与 M2+ parts 消费方读到同一状态
        engineerTask = liveState,
    )
}
