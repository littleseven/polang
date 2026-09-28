package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
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

    private fun msg(metadata: String) = ChatMessageEntity(
        id = "m1", sessionId = "default", type = "tool_image_edit", role = "agent",
        content = "已提亮", timestamp = 1, modelUsed = "m", metadata = metadata
    )

    @Test
    fun `happy path copies to gallery, repoints metadata, marks saved`() = runTest {
        // useCase 会校验源文件存在，必须用真实临时文件
        val src = java.io.File(System.getProperty("java.io.tmpdir"), "polang_save_test_${System.nanoTime()}.jpg")
            .apply { writeBytes(ByteArray(10)) }
        try {
            val dao = mockk<ChatMessageDao>(relaxed = true)
            val store = mockk<ChatImageStore>(relaxed = true)
            coEvery { dao.getMessageById("m1") } returns msg("""{"imageUri":"file://${src.absolutePath}"}""")
            coEvery { store.copyToGallery(any()) } returns "content://media/external/images/media/77"

            val result = SaveChatEditResultUseCase(store, dao).execute("m1")

            assertTrue(result.isSuccess)
            assertEquals("content://media/external/images/media/77", result.getOrNull())
            coVerify { store.copyToGallery(src.absolutePath) }
            coVerify { store.markSaved(src.absolutePath) }
            coVerify {
                dao.insertMessage(match {
                    it.id == "m1" &&
                        it.metadata!!.contains("\"imageUri\":\"content://media/external/images/media/77\"") &&
                        it.metadata!!.contains("\"saved\":true")
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
            msg("""{"imageUri":"content://media/external/images/media/77","saved":true,"savedAt":123}""")

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
        coEvery { dao.getMessageById("m1") } returns msg("""{"imageUri":"file:///x/gone.jpg"}""")
        coEvery { store.copyToGallery(any()) } returns null // 文件已不在 → 复制失败

        val result = SaveChatEditResultUseCase(store, dao).execute("m1")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.insertMessage(any()) }
        coVerify(exactly = 0) { store.markSaved(any()) }
    }
}
