package com.mamba.picme.features.chat

import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.domain.chat.MessagePart
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelEditResultTest : ChatViewModelTestBase() {

    @Test
    fun `insertEditResultMessage persists tool_image_edit part`() = runTest {
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
        assertEquals(sessionId, entity.sessionId)
        assertEquals("remote_deepseek", entity.modelUsed)

        val part = entity.decodeParts().filterIsInstance<MessagePart.EditResult>().single()
        assertEquals(imageUri, part.ref)
        assertEquals(explanation, part.description)
        assertEquals(2, part.suggestions.size)
        assertFalse(part.saved)

        coVerify { chatSessionDao.touchSession(eq(sessionId), any()) }
    }
}
