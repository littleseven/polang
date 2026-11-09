package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.ChatMessageType
import com.mamba.picme.domain.chat.ModelInputRole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePreviewPagesBuilderTest {

    private fun msg(
        id: String,
        imageUri: String? = null,
        type: ChatMessageType = ChatMessageType.IMAGE,
        saved: Boolean = false,
        role: ModelInputRole = ModelInputRole.ASSISTANT
    ) = ChatMessageUi(
        id = id,
        type = type,
        role = role,
        content = "",
        imageUri = imageUri,
        imageSaved = saved
    )

    @Test
    fun `filters to image-bearing messages and preserves order`() {
        val messages = listOf(
            msg("t1", type = ChatMessageType.TEXT, role = ModelInputRole.USER),
            msg("u1", imageUri = "content://a", type = ChatMessageType.IMAGE, role = ModelInputRole.USER),
            msg("a1", imageUri = "file://edited1", type = ChatMessageType.EDIT_RESULT),
            msg("t2", type = ChatMessageType.TEXT)
        )
        val pages = buildImagePreviewPages(messages)
        assertEquals(2, pages.size)
        assertEquals("u1", pages[0].messageId)
        assertEquals("a1", pages[1].messageId)
    }

    @Test
    fun `editable result flag only for agent image and edit result`() {
        // 纯 kind 口径（D2a）：agent 结果图（IMAGE + role=agent）与 EDIT_RESULT 可编辑，
        // 用户自己发的图（IMAGE + role=user）不算结果图
        val pages = buildImagePreviewPages(
            listOf(
                msg("u1", imageUri = "content://a", type = ChatMessageType.IMAGE, role = ModelInputRole.USER),
                msg("a1", imageUri = "file://e1", type = ChatMessageType.EDIT_RESULT),
                msg("a2", imageUri = "file://e2", type = ChatMessageType.IMAGE)
            )
        )
        assertEquals(false, pages[0].isEditableResult)
        assertEquals(true, pages[1].isEditableResult)
        assertEquals(true, pages[2].isEditableResult)
    }

    @Test
    fun `isSaved carried from message`() {
        val pages = buildImagePreviewPages(
            listOf(msg("a1", imageUri = "file://e1", type = ChatMessageType.EDIT_RESULT, saved = true))
        )
        assertTrue(pages[0].isSaved)
    }

    @Test
    fun `clicked index resolved within filtered list`() {
        val pages = buildImagePreviewPages(
            listOf(
                msg("u1", imageUri = "content://a", type = ChatMessageType.IMAGE, role = ModelInputRole.USER),
                msg("a1", imageUri = "file://e1", type = ChatMessageType.EDIT_RESULT),
                msg("a2", imageUri = "file://e2", type = ChatMessageType.IMAGE)
            )
        )
        assertEquals(1, indexOfPage(pages, "a1"))
    }

    @Test
    fun `clicked id not found yields index zero`() {
        val pages = buildImagePreviewPages(
            listOf(msg("u1", imageUri = "content://a", type = ChatMessageType.IMAGE, role = ModelInputRole.USER))
        )
        assertEquals(0, indexOfPage(pages, "missing"))
    }

    @Test
    fun `empty when no image messages`() {
        val pages = buildImagePreviewPages(listOf(msg("t1", type = ChatMessageType.TEXT, role = ModelInputRole.USER)))
        assertTrue(pages.isEmpty())
    }
}
