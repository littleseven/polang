package com.mamba.picme.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.chat.LegacyChatTypeMigration
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.MessagePartsConverter
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.roleOf
import com.mamba.picme.domain.chat.toModelInput

/**
 * chat_messages 表的 parts 双写/双读接缝（ADR-016 M1，spec §6）。
 *
 * M1 期 parts 是 (type, content, metadata, role) 列的**纯函数**（转换收口
 * [MessagePartsConverter]，仅认新 8 值分类法；legacy 13→8 映射知识单点收口
 * [LegacyChatTypeMigration]，只服务迁移）：
 * - 写侧：所有插入统一走 [insertMessageWithParts] / [insertMessagesWithParts]，
 *   [withPartsJson] 每次从标量列现算重填（含 metadata 回写型 update，防 parts 漂移）；
 * - 读侧：[decodePartsOrLegacy] 优先 partsJson，缺失/损坏回标量列现算（不丢消息）；
 * - 迁移：[backfillChatMessageParts] 对存量行全量回填。
 *
 * M2 起流式管线在内存轨装配 parts（TurnPartsReducer，瞬态不落 Room，UI 仍读标量列）；
 * 持久化接缝反转（parts 为权威源、标量列投影）属 M4 渲染切换一并收口。
 */

/** 由新 8 值列现算 parts（纯函数，行失败转换器内部已兜底）。 */
fun ChatMessageEntity.deriveParts(): List<MessagePart> =
    MessagePartsConverter.toParts(type, content, metadata, roleOf(role))

/** 双写填充 partsJson（每次现算重填：metadata 回写型 update 不会让 parts 滞留旧值）。 */
fun ChatMessageEntity.withPartsJson(): ChatMessageEntity =
    copy(partsJson = MessagePartsCodec.encode(deriveParts()))

/** 读侧：优先 partsJson 解码；缺失/损坏回标量列现算（不丢消息）。 */
fun ChatMessageEntity.decodePartsOrLegacy(): List<MessagePart> =
    MessagePartsCodec.decode(partsJson) ?: deriveParts()

/**
 * 实体 → LLM 回灌项序列（spec §6：UIMessage→ModelMessage 显式转换）。
 * 角色取自 Room v26 `role` 列，经 commonMain 单点 [roleOf] 派生（精确匹配 "user" → USER，
 * 其余 ASSISTANT）；data part 不进上下文（转换规则见 commonMain `toModelInput`）。
 */
fun ChatMessageEntity.toModelInputItems(): List<ModelInputItem> =
    decodePartsOrLegacy().toModelInput(roleOf(role), id)

/** 插入单条消息（M1 起统一入口：标量列 + partsJson 双写）。 */
suspend fun ChatMessageDao.insertMessageWithParts(message: ChatMessageEntity) =
    insertMessage(message.withPartsJson())

/** 批量插入消息（备份恢复路径；逐条双写）。 */
suspend fun ChatMessageDao.insertMessagesWithParts(messages: List<ChatMessageEntity>) =
    insertMessages(messages.map { it.withPartsJson() })

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
