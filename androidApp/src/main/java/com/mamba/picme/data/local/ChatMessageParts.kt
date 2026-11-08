package com.mamba.picme.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.LegacyChatTypeMigration
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.MessagePartsConverter
import com.mamba.picme.domain.chat.MessagePartsProjection
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.roleOf
import com.mamba.picme.domain.chat.toModelInput

/**
 * chat_messages 表的 parts 单一权威接缝（持久化接缝反转，ADR-016 收口，v28）。
 *
 * 反转后不变量：partsJson 是唯一内容权威；type 为写时投影列（[MessagePartsProjection]）。
 * 全部写入经 [chatMessageEntity] 构造或 [withUpdatedParts] 更新——投影与 partsJson 同点
 * 派生，物理上不存在漂移面；DAO 裸 insertMessage/insertMessages 仅供本文件两个入口调用。
 *
 * 读侧：[decodeParts] 直解 partsJson（part 级容错：未知/损坏原位 Unsupported，见
 * [MessagePartsCodec]）；legacy 列回退已随 content 列退役删除。
 * legacy 转换知识（[MessagePartsConverter]/[LegacyChatTypeMigration]）
 * 仅存用于 MIGRATION_27_28 修复回填与旧备份（v5-）恢复。
 */

/** 由 parts 构造实体（唯一构造入口：type 投影与 partsJson 同点派生，不可漂移）。 */
fun chatMessageEntity(
    id: String,
    sessionId: String,
    role: String,
    parts: List<MessagePart>,
    timestamp: Long = System.currentTimeMillis(),
    modelUsed: String? = null,
    metadata: String? = null,
): ChatMessageEntity = ChatMessageEntity(
    id = id,
    sessionId = sessionId,
    type = MessagePartsProjection.roomTypeOf(parts),
    role = role,
    timestamp = timestamp,
    modelUsed = modelUsed,
    metadata = metadata,
    partsJson = MessagePartsCodec.encode(parts),
)

/** 读侧：解 partsJson；空/整体损坏（迁移兜底后不应出现）合成 Unsupported 占位，不丢消息位。 */
fun ChatMessageEntity.decodeParts(): List<MessagePart> =
    MessagePartsCodec.decode(partsJson)
        ?: listOf(MessagePart.Unsupported(partId = "p0", originalType = type, summary = null))

/** 更新 parts（转换后投影重算；任务卡/HTML 卡测高/抽卡/OpenUI 交互态等回写统一入口）。 */
fun ChatMessageEntity.withUpdatedParts(transform: (List<MessagePart>) -> List<MessagePart>): ChatMessageEntity {
    val updated = transform(decodeParts())
    return copy(partsJson = MessagePartsCodec.encode(updated), type = MessagePartsProjection.roomTypeOf(updated))
}

/** 任务卡状态读取（任务中心列表/审批回放/交付入口的统一读取口）。 */
fun ChatMessageEntity.taskCardStateOrNull(): EngineerTaskState? =
    decodeParts().filterIsInstance<MessagePart.TaskCard>().firstOrNull()?.task

/**
 * 实体 → LLM 回灌项序列（spec §6：UIMessage→ModelMessage 显式转换）。
 * 角色取自 Room v26 `role` 列，经 commonMain 单点 [roleOf] 派生（精确匹配 "user" → USER，
 * 其余 ASSISTANT）；data part 不进上下文（转换规则见 commonMain `toModelInput`）。
 * 退役横滑卡行（[LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES]，2026-11-08 横滑卡
 * 整体下线）不回灌：历史负载是资产 JSON 数组，进上下文只有 token 噪音。
 */
fun ChatMessageEntity.toModelInputItems(): List<ModelInputItem> =
    if (type in LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES) {
        emptyList()
    } else {
        decodeParts().toModelInput(roleOf(role), id)
    }

/** 插入单条消息（统一入口；实体须经 [chatMessageEntity]/[withUpdatedParts] 产出）。 */
suspend fun ChatMessageDao.insertPartsMessage(message: ChatMessageEntity) =
    insertMessage(message)

/** 批量插入（备份恢复路径）。 */
suspend fun ChatMessageDao.insertPartsMessages(messages: List<ChatMessageEntity>) =
    insertMessages(messages)

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

/**
 * MIGRATION_27_28：逐行把 (legacy 列, partsJson) 双表示转正为 parts 单一权威行，
 * 写入重建后的 chat_messages_new。
 *
 * - 解码成功行：**partsJson 原文保留**（Unsupported 占位行的未知 payload 不随重编码丢失，
 *   codec 契约），只重算 type 投影 + 瘦身 metadata；
 * - 解码失败行（partsJson null/损坏）：经 [MessagePartsConverter] 自 legacy 列现算合成
 *   （行级兜底链与 24→25/25→26 同款：任何异常 → Text 原文，迁移绝不丢消息）；
 * - type 全量重算为投影（顺带消除双写期漂移行，如抽卡确认留下的 type=image 但
 *   content 滞留旧抽卡文案行——content 随列退役消失，漂移一并清算）。
 *
 * 游标遍历中 INSERT 异表安全：扫描只读旧表，写入新表，无自写并发问题。
 */
internal fun migrateChatMessagesToPartsSsot(database: SupportSQLiteDatabase) {
    val startedAt = System.currentTimeMillis()
    var rows = 0
    Logger.i(TAG, "MIGRATION_27_28 rewrite chat_messages to parts SSOT started")
    val insert = database.compileStatement(
        "INSERT INTO `chat_messages_new` (`id`, `sessionId`, `type`, `role`, `timestamp`, `modelUsed`, `metadata`, `partsJson`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    )
    insert.use {
        val cursor = database.query(
            "SELECT `id`, `sessionId`, `type`, `role`, `timestamp`, `modelUsed`, `metadata`, `content`, `partsJson` FROM `chat_messages`",
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val sessionId = it.getString(1)
                val legacyType = it.getString(2)
                val role = it.getString(3)
                val timestamp = it.getLong(4)
                val modelUsed = if (it.isNull(5)) null else it.getString(5)
                val metadata = if (it.isNull(6)) null else it.getString(6)
                val content = it.getString(7)
                val partsJson = if (it.isNull(8)) null else it.getString(8)
                val decoded = MessagePartsCodec.decode(partsJson)
                val newType: String
                val newPartsJson: String
                if (decoded != null) {
                    newType = MessagePartsProjection.roomTypeOf(decoded)
                    newPartsJson = partsJson!! // 原文保留（decoded != null 蕴含 partsJson 非空）
                } else {
                    val parts = runCatching {
                        MessagePartsConverter.toParts(legacyType, content, metadata, roleOf(role))
                    }.getOrElse {
                        listOf(MessagePart.Text("p0", content, PartState.DONE))
                    }
                    newType = MessagePartsProjection.roomTypeOf(parts)
                    newPartsJson = MessagePartsCodec.encode(parts)
                }
                insert.clearBindings()
                insert.bindString(1, id)
                insert.bindString(2, sessionId)
                insert.bindString(3, newType)
                insert.bindString(4, role)
                insert.bindLong(5, timestamp)
                if (modelUsed == null) insert.bindNull(6) else insert.bindString(6, modelUsed)
                val slim = MessagePartsProjection.slimMetadataJson(metadata)
                if (slim == null) insert.bindNull(7) else insert.bindString(7, slim)
                insert.bindString(8, newPartsJson)
                insert.executeInsert()
                rows++
            }
        }
    }
    Logger.i(TAG, "MIGRATION_27_28 rewrite finished: $rows rows in ${System.currentTimeMillis() - startedAt}ms")
}

private const val TAG = "PoLang:Chat"
