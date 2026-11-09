package com.mamba.picme.data.local

import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.projectChatRow
import com.mamba.picme.domain.chat.roleOf
import com.mamba.picme.domain.chat.toModelInput
import java.util.UUID
import org.json.JSONObject

/**
 * chat_messages 表的 parts-first 持久接缝（D1a，spec《chat-value-domain-ideal》§4.1
 * 裁决 (a)，2026-11-08 自 M1 标量主写接缝反转；Room v28 起表即为此形态重建）。
 *
 * - **写侧**：所有插入统一走 [insertPartsMessage] / [insertPartsMessages] /
 *   [replacePartsMessage]——persist 点直建 `List<MessagePart>` 落 partsJson（权威列），
 *   type/content/metadata 经 [projectChatRow] 投影派生；
 * - **读侧**：[decodeParts] 直读 partsJson，损坏/空行降级原文 Text part（不丢消息）。
 */

/** 读侧：partsJson（权威列）直读；损坏/空行降级原文 Text part（fallbackText 语义，不丢消息）。 */
fun ChatMessageEntity.decodeParts(): List<MessagePart> =
    MessagePartsCodec.decode(partsJson) ?: fallbackText(content)

/** 行级兜底：保留 content 原文，UI 与回灌均有可读内容，消息不丢。 */
private fun fallbackText(content: String): List<MessagePart> =
    listOf(MessagePart.Text(partId = "p0", markdown = content, state = PartState.DONE))

/**
 * 实体 → LLM 回灌项序列（spec §6：UIMessage→ModelMessage 显式转换）。
 * 角色取自 Room `role` 列，经 commonMain 单点 [roleOf] 派生（精确匹配 "user" → USER，
 * 其余 ASSISTANT）；data part 不进上下文（转换规则见 commonMain `toModelInput`）。
 */
fun ChatMessageEntity.toModelInputItems(): List<ModelInputItem> =
    decodeParts().toModelInput(roleOf(role), id)

/** 任务卡状态读取（任务中心列表/审批回放/交付入口的统一读取口）。 */
fun ChatMessageEntity.taskCardStateOrNull(): EngineerTaskState? =
    decodeParts().filterIsInstance<MessagePart.TaskCard>().firstOrNull()?.task

/**
 * parts-first 单条插入（统一入口）：parts 直建落 partsJson（权威列），type/content/metadata
 * 经 [projectChatRow] 投影派生。REPLACE 语义：同 id 重插 = 事实上的覆写通道。
 *
 * @param extraMetadataJson 非 parts 派生的附加 metadata 键值（performance /
 *   claude_agent_state 等随行负载），与投影 metadata 合并（键冲突时投影值胜出）。
 * @return messageId
 */
suspend fun ChatMessageDao.insertPartsMessage(
    sessionId: String,
    role: ModelInputRole,
    parts: List<MessagePart>,
    modelUsed: String? = null,
    timestamp: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
    extraMetadataJson: String? = null,
): String {
    val projected = projectChatRow(parts, role)
    insertMessage(
        ChatMessageEntity(
            id = id,
            sessionId = sessionId,
            type = projected.type,
            role = role.roomValue(),
            content = projected.content,
            timestamp = timestamp,
            modelUsed = modelUsed,
            metadata = mergeMetadataJson(projected.metadata, extraMetadataJson),
            partsJson = MessagePartsCodec.encode(parts),
        ),
    )
    return id
}

/** parts-first 批量插入行（备份恢复路径：保 id/timestamp/modelUsed 原值逐行落库）。 */
data class PartsMessageRow(
    val id: String,
    val sessionId: String,
    val role: ModelInputRole,
    val parts: List<MessagePart>,
    val modelUsed: String? = null,
    val timestamp: Long,
    val extraMetadataJson: String? = null,
)

/** 批量插入（备份恢复 chunked 落库）。 */
suspend fun ChatMessageDao.insertPartsMessages(rows: List<PartsMessageRow>) =
    insertMessages(
        rows.map { row ->
            val projected = projectChatRow(row.parts, row.role)
            ChatMessageEntity(
                id = row.id,
                sessionId = row.sessionId,
                type = projected.type,
                role = row.role.roomValue(),
                content = projected.content,
                timestamp = row.timestamp,
                modelUsed = row.modelUsed,
                metadata = mergeMetadataJson(projected.metadata, row.extraMetadataJson),
                partsJson = MessagePartsCodec.encode(row.parts),
            )
        },
    )

/**
 * 覆写型 upsert（REPLACE 同 id 语义）：以 [parts] 重建行，保留原行 id/sessionId/role/
 * modelUsed/timestamp。metadata = 新投影 ∪ 原行非投影残留键——performance /
 * claude_agent_state 等随行负载存活，原 parts 投影出的键（旧 group / html_card /
 * engineer_task 等）随旧 parts 一并出清。
 */
suspend fun ChatMessageDao.replacePartsMessage(
    existing: ChatMessageEntity,
    parts: List<MessagePart>,
): String {
    val projected = projectChatRow(parts, roleOf(existing.role))
    insertMessage(
        ChatMessageEntity(
            id = existing.id,
            sessionId = existing.sessionId,
            type = projected.type,
            role = existing.role,
            content = projected.content,
            timestamp = existing.timestamp,
            modelUsed = existing.modelUsed,
            metadata = mergeMetadataJson(projected.metadata, survivingExtraMetadata(existing)),
            partsJson = MessagePartsCodec.encode(parts),
        ),
    )
    return existing.id
}

/** 原行 metadata 中不属于旧 parts 投影的残留键（附加负载随行存活）。 */
private fun survivingExtraMetadata(existing: ChatMessageEntity): String? {
    if (existing.metadata.isNullOrBlank()) return null
    val oldProjectionKeys = projectChatRow(existing.decodeParts(), roleOf(existing.role)).metadata
        ?.let { metadata -> runCatching { JSONObject(metadata).keys().asSequence().toList() } }
        ?.getOrNull()
        .orEmpty()
        .toSet()
    return runCatching {
        val survivors = JSONObject(existing.metadata)
        oldProjectionKeys.forEach(survivors::remove)
        if (survivors.length() == 0) null else survivors.toString()
    }.getOrNull()
}

/** 投影 metadata 与附加 metadata 合并（键冲突时投影值胜出；任一为空直返另一者）。 */
private fun mergeMetadataJson(projected: String?, extra: String?): String? {
    if (projected == null) return extra?.takeIf { it.isNotBlank() }
    if (extra.isNullOrBlank()) return projected
    return runCatching {
        val projectedJson = JSONObject(projected)
        val merged = JSONObject(extra)
        projectedJson.keys().forEach { key -> merged.put(key, projectedJson.get(key)) }
        merged.toString()
    }.getOrDefault(projected)
}

/** Room role 列线值（"user"/"agent"，[roleOf] 的逆）。 */
private fun ModelInputRole.roomValue(): String =
    if (this == ModelInputRole.USER) "user" else "agent"
