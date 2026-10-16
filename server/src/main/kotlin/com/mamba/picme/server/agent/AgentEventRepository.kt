package com.mamba.picme.server.agent

import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 事件流仓储（ADR-018 决策 1）：只追加，无 update/delete。
 * pending 计算在 Kotlin 侧（payload 为 JSON，不做 SQL join）——
 * 单会话事件量有限，且单连接 SQLite 上避免复杂查询。
 */
object AgentEventRepository {

    /** 追加事件，返回带事件序 id 的完整记录。type 列由载荷类型导出。 */
    suspend fun append(
        sessionId: String,
        source: AgentEventSource,
        role: AgentEventRole,
        parentId: Long? = null,
        payload: AgentEventPayload,
    ): AgentEvent = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val now = System.currentTimeMillis()
        val id = AgentEvents.insert {
            it[AgentEvents.sessionId] = sessionId
            it[AgentEvents.type] = payload.type().wire
            it[AgentEvents.eventSource] = source.wire
            it[AgentEvents.role] = role.wire
            it[AgentEvents.parentId] = parentId
            it[AgentEvents.payloadJson] = AgentEventPayloadCodec.encode(payload)
            it[AgentEvents.createdAt] = now
        } get AgentEvents.id
        AgentEvent(id, sessionId, payload.type(), source, role, parentId, payload, now)
    }

    /** 会话事件顺序回放；afterId 分页（严格大于），limit 截断。 */
    suspend fun listBySession(sessionId: String, afterId: Long = 0, limit: Int = 200): List<AgentEvent> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AgentEvents.selectAll()
                .where { (AgentEvents.sessionId eq sessionId) and (AgentEvents.id greater afterId) }
                .orderBy(AgentEvents.id to SortOrder.ASC)
                .limit(limit)
                .map { it.toEvent() }
        }

    /**
     * Pending action 不变式：没有匹配 Observation 的 Action 视为 pending。
     * 恢复时按追加序（id ASC）优先重放。Observation 只认本会话内的匹配。
     */
    suspend fun pendingActions(sessionId: String): List<AgentEvent> {
        val events = listBySession(sessionId, limit = Int.MAX_VALUE)
        val observed = events.mapNotNull { (it.payload as? AgentEventPayload.Observation)?.actionEventId }.toSet()
        return events.filter { it.type == AgentEventType.ACTION && it.id !in observed }
    }

    private fun ResultRow.toEvent(): AgentEvent = AgentEvent(
        id = this[AgentEvents.id],
        sessionId = this[AgentEvents.sessionId],
        type = AgentEventType.fromWire(this[AgentEvents.type]),
        source = AgentEventSource.fromWire(this[AgentEvents.eventSource]),
        role = AgentEventRole.fromWire(this[AgentEvents.role]),
        parentId = this[AgentEvents.parentId],
        payload = AgentEventPayloadCodec.decode(this[AgentEvents.payloadJson]),
        createdAt = this[AgentEvents.createdAt],
    )
}
