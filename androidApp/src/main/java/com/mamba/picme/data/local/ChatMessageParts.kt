package com.mamba.picme.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.chat.LegacyChatTypeMigration
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
 * 裁决 (a)，2026-11-08 自 M1 标量主写接缝反转）。
 *
 * - **写侧**：所有插入统一走 [insertPartsMessage] / [insertPartsMessages] /
 *   [replacePartsMessage]——persist 点直建 `List<MessagePart>` 落 partsJson（权威列），
 *   type/content/metadata 经 [projectChatRow] 投影派生；
 * - **读侧**：[decodeParts] 直读 partsJson，损坏/空行降级原文 Text part（不丢消息）；
 * - **迁移**：[backfillChatMessageParts] / [migrateChatMessageTypes] 供
 *   MIGRATION_24_25 / 25_26（v28 聊天表重置后随 Task 4 出清）。
 */

/** 读侧：partsJson（权威列）直读；损坏/空行降级原文 Text part（fallbackText 语义，不丢消息）。 */
fun ChatMessageEntity.decodeParts(): List<MessagePart> =
    MessagePartsCodec.decode(partsJson) ?: fallbackText(content)

/** 行级兜底：保留 content 原文，UI 与回灌均有可读内容，消息不丢。 */
private fun fallbackText(content: String): List<MessagePart> =
    listOf(MessagePart.Text(partId = "p0", markdown = content, state = PartState.DONE))

/**
 * 实体 → LLM 回灌项序列（spec §6：UIMessage→ModelMessage 显式转换）。
 * 角色取自 Room v26 `role` 列，经 commonMain 单点 [roleOf] 派生（精确匹配 "user" → USER，
 * 其余 ASSISTANT）；data part 不进上下文（转换规则见 commonMain `toModelInput`）。
 * 退役横滑卡行（[LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES]，2026-11-08 横滑卡
 * 整体下线）不回灌：历史负载是资产 JSON 数组，进上下文只有 token 噪音（v28 重置后随 Task 4 删）。
 */
fun ChatMessageEntity.toModelInputItems(): List<ModelInputItem> =
    if (type in LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES) {
        emptyList()
    } else {
        decodeParts().toModelInput(roleOf(role), id)
    }

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
    /**
     * 行 type 覆写（投影值胜出的例外通道）：退役横滑卡行（data_media_results）parts
     * 物化为 Tombstone、投影 type 落 "text"，但回放过滤
     * （[LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES]，保留至 Task 4 v28 重置）
     * 按原 type 整行跳过——恢复路径显式覆写保住过滤语义。
     */
    val typeOverride: String? = null,
)

/** 批量插入（备份恢复 chunked 落库）。 */
suspend fun ChatMessageDao.insertPartsMessages(rows: List<PartsMessageRow>) =
    insertMessages(
        rows.map { row ->
            val projected = projectChatRow(row.parts, row.role)
            ChatMessageEntity(
                id = row.id,
                sessionId = row.sessionId,
                type = row.typeOverride ?: projected.type,
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

/**
 * v24→v25 迁移回填：逐行把 legacy (type, content, metadata) 转为 parts JSON 写入 partsJson。
 *
 * 转换器本身行级兜底（任何异常 → Text 原文），此处再套一层 runCatching 双保险：
 * 单行最坏结果是 partsJson = 原文 Text part，**迁移绝不丢消息**（spec §13）。
 *
 * 实现要点：
 * - UPDATE 复用单条预编译语句（compileStatement），避免每行重建 SQLite 语句对象；
 * - 游标遍历中 UPDATE 同表安全：扫描只读 id/type/content/metadata（id 为 PK），UPDATE 只写
 *   新增的非索引列 partsJson，不改变扫描列、PK 或表结构，行访问不受并发自写影响（每行恰好
 *   访问一次）。若未来回填需改扫描列/索引列，必须先物化 id 列表再回填。
 */
internal fun backfillChatMessageParts(database: SupportSQLiteDatabase) {
    val startedAt = System.currentTimeMillis()
    var rows = 0
    Logger.i(TAG, "MIGRATION_24_25 backfill chat_messages.partsJson started")
    val update = database.compileStatement("UPDATE `chat_messages` SET `partsJson` = ? WHERE `id` = ?")
    update.use {
        val cursor = database.query("SELECT `id`, `type`, `content`, `metadata` FROM `chat_messages`")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val type = it.getString(1)
                val content = it.getString(2)
                val metadata = if (it.isNull(3)) null else it.getString(3)
                val partsJson = runCatching {
                    // v24→v25 独立正确：彼时 type 仍是 legacy 13 值，legacy→parts 直映射
                    // （role 升格与 type 改写属 v26，见 [migrateChatMessageTypes]）
                    MessagePartsCodec.encode(LegacyChatTypeMigration.map(type, content, metadata).parts)
                }.getOrElse {
                    // 双保险兜底（理论不可达：转换器已行级兜底）：原文 Text part
                    MessagePartsCodec.encode(
                        listOf(
                            MessagePart.Text(
                                partId = "p0",
                                markdown = content,
                                state = PartState.DONE,
                            ),
                        ),
                    )
                }
                update.clearBindings()
                update.bindString(1, partsJson)
                update.bindString(2, id)
                update.executeUpdateDelete()
                rows++
            }
        }
    }
    Logger.i(TAG, "MIGRATION_24_25 backfill finished: $rows rows in ${System.currentTimeMillis() - startedAt}ms")
}

/**
 * MIGRATION_25_26 第二段：逐行把 legacy (type, content, metadata) 经 [LegacyChatTypeMigration]
 * 转为新 (type, role, partsJson) 一次写入。
 *
 * 必须先映射再写回（而非先改 type 再重编码）：user_image/user_image_text/agent_image
 * 同归 "image"，先改 type 会丢失区分信息。行级 runCatching 双保险：单行最坏结果 =
 * 原文 Text part（text/agent），迁移绝不丢消息。
 *
 * 游标遍历中 UPDATE 同表安全性同 backfillChatMessageParts：扫描只读 id/type/content/metadata，
 * UPDATE 写非索引列且不动 PK，每行恰好访问一次。
 */
internal fun migrateChatMessageTypes(database: SupportSQLiteDatabase) {
    val startedAt = System.currentTimeMillis()
    var rows = 0
    Logger.i(TAG, "MIGRATION_25_26 rewrite chat_messages type/role/partsJson started")
    val update = database.compileStatement("UPDATE `chat_messages` SET `type` = ?, `role` = ?, `partsJson` = ? WHERE `id` = ?")
    update.use {
        val cursor = database.query("SELECT `id`, `type`, `content`, `metadata` FROM `chat_messages`")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val type = it.getString(1)
                val content = it.getString(2)
                val metadata = if (it.isNull(3)) null else it.getString(3)
                val migrated = runCatching { LegacyChatTypeMigration.map(type, content, metadata) }.getOrElse {
                    LegacyChatTypeMigration.MigratedRow(
                        "text", "agent",
                        listOf(MessagePart.Text("p0", content, PartState.DONE)),
                    )
                }
                update.clearBindings()
                update.bindString(1, migrated.type)
                update.bindString(2, migrated.role)
                update.bindString(3, MessagePartsCodec.encode(migrated.parts))
                update.bindString(4, id)
                update.executeUpdateDelete()
                rows++
            }
        }
    }
    Logger.i(TAG, "MIGRATION_25_26 rewrite finished: $rows rows in ${System.currentTimeMillis() - startedAt}ms")
}

private const val TAG = "PoLang:Chat"
