package com.mamba.picme.features.chat

import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.domain.chat.MessagePart
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelEditResultTest : ChatViewModelTestBase() {

    @Test
    fun `insertEditResultMessage persists tool_image_edit with metadata`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        val sessionId = "session-edit"
        val imageUri = "content://media/external/images/media/42"
        val explanation = "✅ 已调亮并加滤镜"

        vm.insertEditResultMessage(sessionId, imageUri, explanation, "remote_deepseek")
        advanceUntilIdle()

        val slot = slot<com.mamba.picme.data.local.ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }

        val entity = slot.captured
        assertEquals("tool_image_edit", entity.type)
        assertEquals("agent", entity.role)
        assertEquals(explanation, entity.content)
        assertEquals(sessionId, entity.sessionId)
        assertEquals("remote_deepseek", entity.modelUsed)

        // parts-first：EditResult part 直建（description/ref/suggestions 由 part 承载）
        val part = entity.decodeParts().single() as MessagePart.EditResult
        assertEquals(imageUri, part.ref)
        assertEquals(explanation, part.description)
        assertEquals(2, part.suggestions.size)

        // 投影 metadata 键与 converter 解析键对齐（explanation 由 content 列承载，不再冗余落键）
        val json = JSONObject(entity.metadata!!)
        assertEquals(imageUri, json.getString("imageUri"))
        assertTrue(json.getJSONArray("suggestions").length() == 2)

        coVerify { chatSessionDao.touchSession(eq(sessionId), any()) }
    }
}
