package com.mamba.picme.features.chat

import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        val metadata = entity.metadata
        assertNotNull(metadata)
        val json = JSONObject(metadata!!)
        assertEquals(imageUri, json.getString("imageUri"))
        assertEquals(explanation, json.getString("explanation"))
        assertNotNull(json.getJSONArray("suggestions"))

        coVerify { chatSessionDao.touchSession(eq(sessionId), any()) }
    }
}
