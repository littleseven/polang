package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.insertMessageWithParts
import com.mamba.picme.domain.repository.ChatImageStore
import org.json.JSONObject
import java.io.File

/**
 * 把一条 chat 编辑/优化结果消息保存进相册。
 *
 * 顺序保证「文件不先于消息重指向被删」：
 * 1. copyToGallery 得 content:// URI（失败则直接返回，不动文件、不改消息）；
 * 2. 更新消息 metadata：imageUri = contentUri、saved=true、savedAt；
 * 3. markSaved 删私有文件 + 行置 SAVED。
 *
 * 幂等：metadata.saved=true 时直接返回既有 content:// URI。
 */
class SaveChatEditResultUseCase(
    private val store: ChatImageStore,
    private val chatMessageDao: ChatMessageDao
) {
    suspend fun execute(messageId: String): Result<String> {
        val msg = chatMessageDao.getMessageById(messageId)
            ?: return Result.failure(IllegalStateException("消息不存在"))
        val meta = msg.metadata?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

        // 幂等：已保存直接返回既有 content:// URI
        if (meta.optBoolean("saved", false)) {
            val existing = meta.optString("imageUri").takeIf { it.startsWith("content://") } ?: ""
            return Result.success(existing)
        }

        val imageUri = meta.optString("imageUri")
        if (!imageUri.startsWith("file://") || !File(imageUri.removePrefix("file://")).exists()) {
            return Result.failure(IllegalStateException("图片已过期，无法保存"))
        }
        val filePath = imageUri.removePrefix("file://")
        val contentUri = store.copyToGallery(filePath)
            ?: return Result.failure(IllegalStateException("保存到相册失败"))

        meta.put("imageUri", contentUri)
        meta.put("saved", true)
        meta.put("savedAt", System.currentTimeMillis())
        chatMessageDao.insertMessageWithParts(msg.copy(metadata = meta.toString()))
        store.markSaved(filePath)
        return Result.success(contentUri)
    }
}
