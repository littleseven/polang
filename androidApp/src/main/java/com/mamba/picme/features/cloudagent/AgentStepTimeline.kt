package com.mamba.picme.features.cloudagent

import com.mamba.picme.domain.chat.AgentStepStatus
import com.mamba.picme.domain.chat.AgentStepUi

/**
 * 云端 Agent 回合的步骤时间线纯 reducer：
 * 消费 Task 1 的 StepStarted/StepFinished 事件，折叠为有序步骤列表供 UI 快照。
 *
 * 单线程契约：调用方（ChatViewModel / CloudAgentChatViewModel）漏斗主线程消费，内部不做同步。
 * 内部按 stepId 记录 startedAtMs，finish 时计算耗时回填 [AgentStepUi.durationMs]
 * （spec §5.5 结构化日志的耗时来源）。
 */
class AgentStepTimeline {

    private val _steps = mutableListOf<AgentStepUi>()
    private val startedAtMs = mutableMapOf<Long, Long>()

    /** 只读快照（内部 MutableList 的拷贝）。 */
    val steps: List<AgentStepUi>
        get() = _steps.toList()

    /** 一步开始；同 id 重复 start 幂等忽略；内部记 startedAtMs。 */
    fun start(stepId: Long, label: String) {
        if (_steps.any { step -> step.stepId == stepId }) return
        startedAtMs[stepId] = System.currentTimeMillis()
        _steps.add(AgentStepUi(stepId, label, AgentStepStatus.RUNNING))
    }

    /**
     * 一步结束：未知 id → null（静默忽略——孤儿 observation 与上一回合 pending 重放正常存在）；
     * 命中 RUNNING 步 → SUCCESS/FAILED，回填耗时并返回更新后步骤（供日志取耗时）；
     * 终态步骤再 finish → null。
     */
    fun finish(stepId: Long, ok: Boolean): AgentStepUi? {
        val index = _steps.indexOfFirst { step -> step.stepId == stepId }
        if (index < 0) return null
        val current = _steps[index]
        if (current.status != AgentStepStatus.RUNNING) return null
        val durationMs = startedAtMs.remove(stepId)?.let { start -> System.currentTimeMillis() - start }
        val updated = current.copy(
            status = if (ok) AgentStepStatus.SUCCESS else AgentStepStatus.FAILED,
            durationMs = durationMs,
        )
        _steps[index] = updated
        return updated
    }

    fun clear() {
        _steps.clear()
        startedAtMs.clear()
    }
}
