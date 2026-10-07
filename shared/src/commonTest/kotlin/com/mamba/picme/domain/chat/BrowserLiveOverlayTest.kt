package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [overlayLiveBrowserState]（browser-vnc 直播卡 spec §4）钉桩：
 * 同 sessionId 原位覆写（partId 恒定）/ 无 live 态引用相等零分配 / 非直播卡消息不触碰。
 */
class BrowserLiveOverlayTest {

    private fun msgWith(part: MessagePart): ChatMessage =
        ChatMessage(
            id = "m-1",
            type = ChatMessageType.AGENT_TEXT,
            content = "",
            role = ModelInputRole.ASSISTANT,
            timestamp = 0L,
            parts = listOf(part),
        )

    @Test
    fun `live state overwrites matching session part in place`() {
        val base = MessagePart.BrowserLive(partId = "call-1", sessionId = "s-1", state = ToolPartState.INPUT_AVAILABLE)
        val live = base.copy(pageTitle = "A", frameJpegBase64 = "Zg==", actions = listOf(BrowserActionEntry("打开 a.com")))
        val out = msgWith(base).overlayLiveBrowserState(mapOf("s-1" to live))
        val part = out.parts.single() as MessagePart.BrowserLive
        assertEquals("A", part.pageTitle)
        assertEquals("Zg==", part.frameJpegBase64)
        assertEquals("call-1", part.partId) // partId 不动（LazyColumn key 恒定）
    }

    @Test
    fun `no live state returns same instance`() {
        val base = MessagePart.BrowserLive(partId = "call-1", sessionId = "s-1")
        val msg = msgWith(base)
        assertSame(msg, msg.overlayLiveBrowserState(emptyMap()))
        assertSame(msg, msg.overlayLiveBrowserState(mapOf("s-2" to base.copy(sessionId = "s-2"))))
    }

    @Test
    fun `same content live state returns same instance after partId normalization`() {
        // Task 16 约定 live 条目 partId=""；内容相同（仅 partId 不同）→ 归一化后相等，
        // 原样返回零分配（钉住等值短路在归一化口径下生效）
        val base = MessagePart.BrowserLive(partId = "call-1", sessionId = "s-1", state = ToolPartState.INPUT_AVAILABLE, pageTitle = "A")
        val msg = msgWith(base)
        assertSame(msg, msg.overlayLiveBrowserState(mapOf("s-1" to base.copy(partId = ""))))
    }

    @Test
    fun `non browser messages untouched`() {
        val msg = msgWith(MessagePart.Text(partId = "txt-0", markdown = "hi"))
        assertTrue(
            msg.overlayLiveBrowserState(mapOf("s-1" to MessagePart.BrowserLive(partId = "x", sessionId = "s-1")))
                .parts.single() is MessagePart.Text,
        )
    }
}
