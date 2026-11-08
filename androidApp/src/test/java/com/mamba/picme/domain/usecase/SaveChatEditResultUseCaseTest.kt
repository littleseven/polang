package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.repository.ChatImageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveChatEditResultUseCaseTest {

    @After
    fun tearDown() = unmockkAll()

    /** 编辑结果行（parts-first：EditResult part 落 partsJson）。 */
    private fun msg(part: MessagePart.EditResult) = ChatMessageEntity(
        id = "m1", sessionId = "default", type = "tool_image_edit", role = "agent",
        content = "已提亮", timestamp = 1, modelUsed = "m",
        partsJson = MessagePartsCodec.encode(listOf(part)),
    )

    @Test
    fun `happy path copies to gallery, repoints part ref, marks saved`() = runTest {
        // useCase 会校验源文件存在，必须用真实临时文件
        val src = java.io.File(System.getProperty("java.io.tmpdir"), "polang_save_test_${System.nanoTime()}.jpg")
            .apply { writeBytes(ByteArray(10)) }
        try {
            val dao = mockk<ChatMessageDao>(relaxed = true)
            val store = mockk<ChatImageStore>(relaxed = true)
            coEvery { dao.getMessageById("m1") } returns msg(MessagePart.EditResult("p0", "file://${src.absolutePath}", "已提亮"))
            coEvery { store.copyToGallery(any()) } returns "content://media/external/images/media/77"

            val result = SaveChatEditResultUseCase(store, dao).execute("m1")

            assertTrue(result.isSuccess)
            assertEquals("content://media/external/images/media/77", result.getOrNull())
            coVerify { store.copyToGallery(src.absolutePath) }
            coVerify { store.markSaved(src.absolutePath) }
            coVerify {
                dao.insertMessage(match { entity ->
                    val saved = entity.decodeParts().single() as MessagePart.EditResult
                    entity.id == "m1" && saved.saved &&
                        saved.ref == "content://media/external/images/media/77" &&
                        entity.metadata!!.contains("\"imageUri\":\"content://media/external/images/media/77\"") &&
                        entity.metadata.contains("\"saved\":true")
                })
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun `idempotent when already saved returns existing content uri and does not copy again`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        coEvery { dao.getMessageById("m1") } returns
            msg(MessagePart.EditResult("p0", "content://media/external/images/media/77", "已提亮", saved = true))

        val result = SaveChatEditResultUseCase(store, dao).execute("m1")

        assertTrue(result.isSuccess)
        assertEquals("content://media/external/images/media/77", result.getOrNull())
        coVerify(exactly = 0) { store.copyToGallery(any()) }
        coVerify(exactly = 0) { store.markSaved(any()) }
    }

    @Test
    fun `fails when source file evicted and does not mutate message`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        coEvery { dao.getMessageById("m1") } returns msg(MessagePart.EditResult("p0", "file:///x/gone.jpg", "已提亮"))
        coEvery { store.copyToGallery(any()) } returns null // 文件已不在 → 复制失败

        val result = SaveChatEditResultUseCase(store, dao).execute("m1")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.insertMessage(any()) }
        coVerify(exactly = 0) { store.markSaved(any()) }
    }

    @Test
    fun `fails for message without saveable image part`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        coEvery { dao.getMessageById("m1") } returns ChatMessageEntity(
            id = "m1", sessionId = "default", type = "text", role = "agent",
            content = "纯文本", timestamp = 1,
            partsJson = MessagePartsCodec.encode(listOf(MessagePart.Text("p0", "纯文本"))),
        )

        val result = SaveChatEditResultUseCase(store, dao).execute("m1")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.insertMessage(any()) }
    }
}
