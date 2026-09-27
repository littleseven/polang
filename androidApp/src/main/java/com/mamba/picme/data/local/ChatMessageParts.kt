package com.mamba.picme.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.domain.chat.LegacyMessagePartsConverter
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.toModelInput

/**
 * chat_messages 表的 parts 双写/双读接缝（ADR-016 M1，spec §6）。
 *
 * M1 期 parts 是 legacy 列 (type, content, metadata) 的**纯函数**：
 * - 写侧：所有插入统一走 [insertMessageWithParts] / [insertMessagesWithParts]，
 *   [withPartsJson] 每次从 legacy 列现算重填（含 metadata 回写型 update，防 parts 漂移）；
 * - 读侧：[decodePartsOrLegacy] 优先 partsJson，缺失/损坏回 legacy 列现算（不丢消息）；
 * - 迁移：[backfillChatMessageParts] 对存量行全量回填。
 *
 * M2 起流式写入将以 parts 为权威源，本接缝随之反转（legacy 列由 parts 投影）。
 */

/** 由 legacy 列现算 parts（纯函数，转 single 行失败在转换器内部已降级 Text 兜底）。 */
fun ChatMessageEntity.deriveParts(): List<MessagePart> =
    LegacyMessagePartsConverter.toParts(type, content, metadata)

/** 双写填充 partsJson（每次现算重填：metadata 回写型 update 不会让 parts 滞留旧值）。 */
fun ChatMessageEntity.withPartsJson(): ChatMessageEntity =
    copy(partsJson = MessagePartsCodec.encode(deriveParts()))

/** 读侧：优先 partsJson 解码；缺失/损坏回 legacy 列现算（M1 双读过渡，不丢消息）。 */
fun ChatMessageEntity.decodePartsOrLegacy(): List<MessagePart> =
    MessagePartsCodec.decode(partsJson) ?: deriveParts()

/**
 * 实体 → LLM 回灌项序列（spec §6：UIMessage→ModelMessage 显式转换）。
 * 角色自 legacy type 前缀派生（user_* → USER，与 commonMain `ChatMessage.modelRole` 同口径）；
 * data part / 媒体块不进上下文（转换规则见 commonMain `toModelInput`）。
 */
fun ChatMessageEntity.toModelInputItems(): List<ModelInputItem> =
    decodePartsOrLegacy().toModelInput(
        if (type.startsWith("user_")) {
            ModelInputRole.USER
        } else {
            ModelInputRole.ASSISTANT
        },
    )

/** 插入单条消息（M1 起统一入口：legacy 列 + partsJson 双写）。 */
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
 */
internal fun backfillChatMessageParts(database: SupportSQLiteDatabase) {
    val cursor = database.query("SELECT `id`, `type`, `content`, `metadata` FROM `chat_messages`")
    cursor.use {
        while (it.moveToNext()) {
            val id = it.getString(0)
            val type = it.getString(1)
            val content = it.getString(2)
            val metadata = if (it.isNull(3)) null else it.getString(3)
            val partsJson = runCatching {
                MessagePartsCodec.encode(LegacyMessagePartsConverter.toParts(type, content, metadata))
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
            database.execSQL(
                "UPDATE `chat_messages` SET `partsJson` = ? WHERE `id` = ?",
                arrayOf(partsJson, id),
            )
        }
    }
}
