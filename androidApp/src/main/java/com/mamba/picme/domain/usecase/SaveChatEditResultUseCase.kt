package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.data.local.replacePartsMessage
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.repository.ChatImageStore
import java.io.File

/**
 * 把一条 chat 编辑/优化结果消息保存进相册（parts-first：结果图由 EditResult / agent
 * Image part 承载，保存即 part 原位改写 ref+saved 经 [replacePartsMessage] 覆写同 id 行）。
 *
 * 顺序保证「文件不先于消息重指向被删」：
 * 1. copyToGallery 得 content:// URI（失败则直接返回，不动文件、不改消息）；
 * 2. part 改写：ref = contentUri、saved = true；
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
        val parts = msg.decodeParts()
        val target = parts.firstOrNull { it is MessagePart.EditResult || it is MessagePart.Image }
            ?: return Result.failure(IllegalStateException("图片已过期，无法保存"))

        // 幂等：已保存直接返回既有 content:// URI
        if (target.isSavedPart()) {
            return Result.success(target.saveableRef?.takeIf { it.startsWith("content://") } ?: "")
        }

        val imageUri = target.saveableRef
        if (imageUri == null || !imageUri.startsWith("file://") || !File(imageUri.removePrefix("file://")).exists()) {
            return Result.failure(IllegalStateException("图片已过期，无法保存"))
        }
        val filePath = imageUri.removePrefix("file://")
        val contentUri = store.copyToGallery(filePath)
            ?: return Result.failure(IllegalStateException("保存到相册失败"))

        val savedPart = target.markSavedPart(contentUri)
        chatMessageDao.replacePartsMessage(msg, parts.map { part -> if (part === target) savedPart else part })
        store.markSaved(filePath)
        return Result.success(contentUri)
    }
}

/** 可保存的图载体 part（编辑结果 / agent 产物图）：ref 与 saved 的类型安全读取/改写。 */
private fun MessagePart.isSavedPart(): Boolean = when (this) {
    is MessagePart.EditResult -> saved
    is MessagePart.Image -> saved
    else -> false
}

private val MessagePart.saveableRef: String?
    get() = when (this) {
        is MessagePart.EditResult -> ref
        is MessagePart.Image -> ref
        else -> null
    }

private fun MessagePart.markSavedPart(contentUri: String): MessagePart = when (this) {
    is MessagePart.EditResult -> copy(ref = contentUri, saved = true)
    is MessagePart.Image -> copy(ref = contentUri, saved = true)
    else -> this
}
