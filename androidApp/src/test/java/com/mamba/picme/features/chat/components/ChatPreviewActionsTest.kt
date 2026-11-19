package com.mamba.picme.features.chat.components

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.features.chat.ImagePreviewPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPreviewActionsTest {

    private fun page(
        rawUri: String,
        isEditableResult: Boolean = false,
        isSaved: Boolean = false,
    ) = ImagePreviewPage(
        messageId = "m1",
        rawUri = rawUri,
        isEditableResult = isEditableResult,
        isSaved = isSaved,
    )

    private fun asset(uri: String) = MediaAsset(
        id = 1L,
        uri = uri,
        type = MediaType.PHOTO,
        captureDate = 0L,
        fileName = "a.jpg",
    )

    @Test
    fun `gallery media page enables share and delete`() {
        val actions = resolveChatPreviewActions(page("content://media/1"), asset("content://media/1"))
        assertTrue(actions.canShare)
        assertTrue(actions.canDelete)
        assertTrue(actions.canEdit)
        assertFalse(actions.showSave)
    }

    @Test
    fun `unresolvable page hides share and delete but keeps edit`() {
        // AI 生成缓存图（file 路径，非图库媒体）：只保留编辑入口
        val actions = resolveChatPreviewActions(page("/data/cache/e1.jpg"), asset = null)
        assertFalse(actions.canShare)
        assertFalse(actions.canDelete)
        assertTrue(actions.canEdit)
        assertFalse(actions.showSave)
    }

    @Test
    fun `editable result page shows save entry`() {
        val actions = resolveChatPreviewActions(page("/data/cache/e1.jpg", isEditableResult = true), asset = null)
        assertTrue(actions.showSave)
        assertFalse(actions.canShare)
        assertFalse(actions.canDelete)
    }

    @Test
    fun `saved editable result keeps save entry in disabled state upstream`() {
        // isSaved 只影响按钮 enabled 态（组件层），动作面仍展示保存位
        val actions = resolveChatPreviewActions(
            page("/data/cache/e1.jpg", isEditableResult = true, isSaved = true),
            asset = null,
        )
        assertTrue(actions.showSave)
    }

    @Test
    fun `editable result that is also gallery media exposes full action set`() {
        // 已保存进图库的编辑结果再次被预览：三件套 + 保存位全集
        val actions = resolveChatPreviewActions(
            page("content://media/9", isEditableResult = true),
            asset("content://media/9"),
        )
        assertTrue(actions.canShare)
        assertTrue(actions.canEdit)
        assertTrue(actions.canDelete)
        assertTrue(actions.showSave)
    }
}
