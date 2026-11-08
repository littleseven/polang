package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.data.local.insertPartsMessage
import com.mamba.picme.data.local.withUpdatedParts
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsProjection
import com.mamba.picme.domain.repository.ChatImageStore
import org.json.JSONObject
import java.io.File

/**
 * 把一条 chat 编辑/优化结果消息保存进相册。
 *
 * 顺序保证「文件不先于消息重指向被删」：
 * 1. copyToGallery 得 content:// URI（失败则直接返回，不动文件、不改消息）；
 * 2. 更新消息 part：ref = contentUri、saved = true；消息级 metadata 记 savedAt；
 * 3. markSaved 删私有文件 + 行置 SAVED。
 *
 * 幂等：part.saved=true 时直接返回既有 content:// URI。
 */
class SaveChatEditResultUseCase(
    private val store: ChatImageStore,
    private val chatMessageDao: ChatMessageDao
) {
    suspend fun execute(messageId: String): Result<String> {
        val msg = chatMessageDao.getMessageById(messageId)
            ?: return Result.failure(IllegalStateException("消息不存在"))
        val media = msg.decodeParts().firstNotNullOfOrNull { part ->
            when (part) {
                is MessagePart.Image -> Triple(part.ref, part.saved, true)
                is MessagePart.EditResult -> part.ref?.let { Triple(it, part.saved, false) }
                else -> null
            }
        } ?: return Result.failure(IllegalStateException("消息无图片内容"))
        val (ref, saved, _) = media

        // 幂等：已保存直接返回既有 content:// URI
        if (saved && ref.startsWith("content://")) return Result.success(ref)

        if (!ref.startsWith("file://") || !File(ref.removePrefix("file://")).exists()) {
            return Result.failure(IllegalStateException("图片已过期，无法保存"))
        }
        val filePath = ref.removePrefix("file://")
        val contentUri = store.copyToGallery(filePath)
            ?: return Result.failure(IllegalStateException("保存到相册失败"))

        val newMeta = (
            MessagePartsProjection.slimMetadataJson(msg.metadata)
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?: JSONObject()
            ).apply { put("savedAt", System.currentTimeMillis()) }
        chatMessageDao.insertPartsMessage(
            msg.copy(metadata = newMeta.toString()).withUpdatedParts { parts ->
                parts.map { part ->
                    when (part) {
                        is MessagePart.Image -> part.copy(ref = contentUri, saved = true)
                        is MessagePart.EditResult -> part.copy(ref = contentUri, saved = true)
                        else -> part
                    }
                }
            },
        )
        store.markSaved(filePath)
        return Result.success(contentUri)
    }
}
