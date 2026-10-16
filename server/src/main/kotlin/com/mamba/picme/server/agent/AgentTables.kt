package com.mamba.picme.server.agent

import org.jetbrains.exposed.sql.Table

/**
 * 云端 Agent 会话表（ADR-018 决策 1：agent_session 为云端会话域 SSOT）。
 * seq 自增主键保证同毫秒创建也有确定新旧序；id 是对外公开标识（URL/客户端引用用 uuid）。
 */
object AgentSessions : Table("agent_session") {
    val seq = long("seq").autoIncrement()
    val id = varchar("id", 64).uniqueIndex()
    val tokenHash = varchar("token_hash", 64)
    val status = varchar("status", 24)
    val channelKind = varchar("channel_kind", 16)
    val model = varchar("model", 128)
    /** BYOK 通道的上游 API 根（创建时固化；非敏感。key 永不落库，逐请求透传）。 */
    val byokBaseUrl = varchar("byok_base_url", 256).default("")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val finishedAt = long("finished_at").nullable()
    override val primaryKey = PrimaryKey(seq)
    init { index(isUnique = false, tokenHash) }
}

/**
 * 云端 Agent 事件流表：不可变、类型化、只追加。
 * id 自增即事件序；(session_id, id) 索引支撑按会话顺序回放。
 */
object AgentEvents : Table("agent_event") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 64)
    val type = varchar("type", 24)
    // 属性名避 Exposed ColumnSet.source 成员冲突；DB 列名仍为 source
    val eventSource = varchar("source", 16)
    val role = varchar("role", 16)
    val parentId = long("parent_id").nullable()
    val payloadJson = text("payload_json")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index(isUnique = false, sessionId, id) }
}
