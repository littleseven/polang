package com.mamba.picme.features.chat.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式预览客户端预算判定钉住（2026-11-11 性能收口）：
 * ≤ 预算继续逐帧解析、严格大于才停（边界等于预算仍解析）；预算值钉死 24k
 * （改常量须连带重审低端机逐帧全量重解析的可行性）。
 */
class OpenUiStreamingBudgetTest {

    @Test
    fun `under budget continues frame parsing`() {
        assertTrue(shouldContinuePreviewParsing(PREVIEW_PARSE_BUDGET_CHARS - 1))
    }

    @Test
    fun `exactly at budget boundary still parses`() {
        assertTrue(shouldContinuePreviewParsing(PREVIEW_PARSE_BUDGET_CHARS))
    }

    @Test
    fun `over budget stops frame parsing`() {
        assertFalse(shouldContinuePreviewParsing(PREVIEW_PARSE_BUDGET_CHARS + 1))
    }

    @Test
    fun `budget constant pinned at 24k`() {
        assertEquals(24_000, PREVIEW_PARSE_BUDGET_CHARS)
    }

    @Test
    fun `custom budget keeps inclusive comparison`() {
        assertTrue(shouldContinuePreviewParsing(programLength = 10, budgetChars = 10))
        assertFalse(shouldContinuePreviewParsing(programLength = 11, budgetChars = 10))
    }
}
