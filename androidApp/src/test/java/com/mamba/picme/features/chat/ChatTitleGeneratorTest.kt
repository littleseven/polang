package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ChatTitleGenerator] 单元测试。
 *
 * 覆盖文本清理、截断、图片消息、兜底等边界场景。
 */
class ChatTitleGeneratorTest {

    private companion object {
        const val FALLBACK = "New Chat"
        const val IMAGE_TITLE = "Image Chat"
    }

    @Test
    fun `text message returns sanitized content`() {
        assertEquals(
            "帮我找下去年冬天的照片",
            ChatTitleGenerator.generateTitle(
                "text",
                "帮我找下去年冬天的照片",
                IMAGE_TITLE,
                FALLBACK
            )
        )
    }

    @Test
    fun `image message returns image title`() {
        assertEquals(
            IMAGE_TITLE,
            ChatTitleGenerator.generateTitle(
                "image",
                "file:///path/to/image.jpg",
                IMAGE_TITLE,
                FALLBACK
            )
        )
    }

    /**
     * 钉住 T6 行为变更：legacy user_image_text（现统一为 image）带正文时，
     * 首条消息标题取 imageTitle（图片优先），而非旧的正文兜底标题。
     */
    @Test
    fun `image with text first message still gets image title`() {
        assertEquals(
            IMAGE_TITLE,
            ChatTitleGenerator.generateTitle(
                "image",
                "帮我把这张照片调亮一点",
                IMAGE_TITLE,
                FALLBACK
            )
        )
    }

    @Test
    fun `unknown message type returns fallback`() {
        assertEquals(
            FALLBACK,
            ChatTitleGenerator.generateTitle(
                "unknown_type",
                "anything",
                IMAGE_TITLE,
                FALLBACK
            )
        )
    }

    @Test
    fun `trims edge punctuation`() {
        assertEquals(
            "帮我找下去年冬天的照片",
            ChatTitleGenerator.sanitizeTitle(
                "「帮我找下去年冬天的照片。」",
                FALLBACK
            )
        )
    }

    @Test
    fun `collapses whitespace and newlines`() {
        assertEquals(
            "帮我找 去年冬天的照片",
            ChatTitleGenerator.sanitizeTitle(
                "帮我找\n\n  去年冬天的照片",
                FALLBACK
            )
        )
    }

    @Test
    fun `truncates long content with ellipsis`() {
        val longInput = "请帮我找出所有去年夏天去海边旅行时拍摄的照片"
        assertEquals(
            21, // 20 个字符 + 省略号
            ChatTitleGenerator.sanitizeTitle(longInput, FALLBACK).length
        )
    }

    @Test
    fun `blank content returns fallback`() {
        assertEquals(FALLBACK, ChatTitleGenerator.sanitizeTitle("   ", FALLBACK))
    }

    @Test
    fun `only punctuation returns fallback`() {
        assertEquals(FALLBACK, ChatTitleGenerator.sanitizeTitle("。。。", FALLBACK))
    }
}
