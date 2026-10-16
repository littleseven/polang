package com.mamba.picme.server.agent

/**
 * 事件流 → LLM 视图投影（ADR-018 决策 1 的 Condenser 最小实现；OpenHands「Condenser
 * 投影历史给模型」同构，策略可替换）。
 *
 * M1 规则（测试钉死，见 AgentProjectionTest）：
 * - Message 事件按 role 列直映射，保持事件序；
 * - Action / Observation / Finish 不进跨轮视图（当轮工具循环由 Koog 策略在内存承载；
 *   跨轮时工具细节已由 assistant 的总结性 Message 覆盖）；
 * - 最新一条 Condensation 投影为紧跟原始 system 之后的一条 system 消息，
 *   其覆盖范围（id <= upToEventId）内的 Message 不再重复投影。
 */

/** 投影产物：与 Koog Message 解耦的中间表示（纯 Kotlin，便于单测不拉 Koog）。 */
sealed class ProjectedMessage {
    abstract val content: String

    data class System(override val content: String) : ProjectedMessage()
    data class User(override val content: String) : ProjectedMessage()
    data class Assistant(override val content: String) : ProjectedMessage()
}

object AgentProjection {

    fun project(events: List<AgentEvent>): List<ProjectedMessage> {
        val latestCondensation = events
            .lastOrNull { it.payload is AgentEventPayload.Condensation }
            ?.payload as? AgentEventPayload.Condensation

        val out = ArrayList<ProjectedMessage>(events.size)
        // 原始 system 消息恒保留（人格/约束不被压缩），摘要紧随其后
        val leadingSystems = events.filter {
            it.payload is AgentEventPayload.Message && it.role == AgentEventRole.SYSTEM
        }
        leadingSystems.forEach { out += ProjectedMessage.System((it.payload as AgentEventPayload.Message).content) }
        if (latestCondensation != null) {
            out += ProjectedMessage.System(latestCondensation.summary)
        }

        val coveredUpTo = latestCondensation?.upToEventId ?: 0L
        events.forEach { e ->
            val payload = e.payload
            if (payload !is AgentEventPayload.Message) return@forEach
            if (e.role == AgentEventRole.SYSTEM) return@forEach // 已在头部处理
            if (e.id <= coveredUpTo) return@forEach
            out += when (e.role) {
                AgentEventRole.USER -> ProjectedMessage.User(payload.content)
                AgentEventRole.ASSISTANT -> ProjectedMessage.Assistant(payload.content)
                // tool/system 角色的 Message 不投影（M1 无此用例；防御）
                else -> return@forEach
            }
        }
        return out
    }
}
