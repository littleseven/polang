package com.mamba.picme.server.agent

import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** 会话生命周期状态机（ADR-018 决策 1）。FINISHED/ERROR 为终态（落 finished_at）。 */
enum class AgentSessionStatus(val wire: String) {
    RUNNING("running"),
    PAUSED("paused"),
    WAITING_CONFIRMATION("waiting_confirmation"),
    FINISHED("finished"),
    ERROR("error");

    val isTerminal: Boolean get() = this == FINISHED || this == ERROR

    companion object {
        fun fromWire(wire: String): AgentSessionStatus =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown agent session status: $wire")
    }
}

/** 推理通道（ADR-017 决策 2 双通道）：网关（配额计费）| BYOK 透传（零留存）。 */
enum class AgentChannelKind(val wire: String) {
    GATEWAY("gateway"),
    BYOK("byok");

    companion object {
        fun fromWire(wire: String): AgentChannelKind =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown agent channel kind: $wire")
    }
}

data class AgentSession(
    val id: String,
    val tokenHash: String,
    val status: AgentSessionStatus,
    val channelKind: AgentChannelKind,
    val model: String,
    val byokBaseUrl: String,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
)

object AgentSessionRepository {

    suspend fun create(
        tokenHash: String,
        channelKind: AgentChannelKind,
        model: String,
        byokBaseUrl: String = "",
    ): AgentSession =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            AgentSessions.insert {
                it[AgentSessions.id] = id
                it[AgentSessions.tokenHash] = tokenHash
                it[AgentSessions.status] = AgentSessionStatus.RUNNING.wire
                it[AgentSessions.channelKind] = channelKind.wire
                it[AgentSessions.model] = model
                it[AgentSessions.byokBaseUrl] = byokBaseUrl
                it[AgentSessions.createdAt] = now
                it[AgentSessions.updatedAt] = now
                it[AgentSessions.finishedAt] = null
            }
            AgentSession(id, tokenHash, AgentSessionStatus.RUNNING, channelKind, model, byokBaseUrl, now, now, null)
        }

    suspend fun find(id: String): AgentSession? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AgentSessions.selectAll().where { AgentSessions.id eq id }.firstOrNull()?.toSession()
    }

    /** 更新状态；终态（FINISHED/ERROR）落 finished_at。返回 false = 会话不存在。 */
    suspend fun updateStatus(id: String, status: AgentSessionStatus): Boolean =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val now = System.currentTimeMillis()
            val rows = AgentSessions.update({ AgentSessions.id eq id }) {
                it[AgentSessions.status] = status.wire
                it[AgentSessions.updatedAt] = now
                if (status.isTerminal) it[AgentSessions.finishedAt] = now
            }
            rows > 0
        }

    /** 我的会话列表，最新在前（seq 单调，同毫秒也有确定序）。 */
    suspend fun listByOwner(tokenHash: String): List<AgentSession> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AgentSessions.selectAll().where { AgentSessions.tokenHash eq tokenHash }
                .orderBy(AgentSessions.seq to SortOrder.DESC)
                .map { it.toSession() }
        }

    private fun ResultRow.toSession(): AgentSession = AgentSession(
        id = this[AgentSessions.id],
        tokenHash = this[AgentSessions.tokenHash],
        status = AgentSessionStatus.fromWire(this[AgentSessions.status]),
        channelKind = AgentChannelKind.fromWire(this[AgentSessions.channelKind]),
        model = this[AgentSessions.model],
        byokBaseUrl = this[AgentSessions.byokBaseUrl],
        createdAt = this[AgentSessions.createdAt],
        updatedAt = this[AgentSessions.updatedAt],
        finishedAt = this[AgentSessions.finishedAt],
    )
}
