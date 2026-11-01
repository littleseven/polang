package com.mamba.picme.features.chat.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiProgramSummaryTest {

    @Test
    fun `looksLikeProgram detects root statement`() {
        assertTrue(OpenUiProgramSummary.looksLikeProgram("// summary: x\nroot = Card([Text(\"hi\")])"))
        assertTrue(OpenUiProgramSummary.looksLikeProgram("card = Card([Text(\"hi\")])")) // Card 兜底 entry
        assertFalse(OpenUiProgramSummary.looksLikeProgram("今天天气不错"))
        assertFalse(OpenUiProgramSummary.looksLikeProgram("root 这个词出现在句子里但不是声明"))
    }

    @Test
    fun `extractFallbackSummary prefers summary comment`() {
        val program = "// summary: 上周共 327 张照片\nroot = Card([Text(\"其它\")])"
        assertEquals("上周共 327 张照片", OpenUiProgramSummary.extractFallbackSummary(program))
    }

    @Test
    fun `extractFallbackSummary falls back to first text literal`() {
        val program = "root = Card([Markdown(\"# 相册报告\n正文内容\")])"
        assertEquals("# 相册报告 正文内容", OpenUiProgramSummary.extractFallbackSummary(program))
    }

    @Test
    fun `extractFallbackSummary empty when nothing extractable`() {
        assertEquals("", OpenUiProgramSummary.extractFallbackSummary("root = Card([Divider()])"))
    }

    @Test
    fun `summary truncated to 80 chars`() {
        val long = "长".repeat(100)
        val program = "// summary: $long\nroot = Card([Text(\"x\")])"
        assertEquals(80, OpenUiProgramSummary.extractFallbackSummary(program).length)
    }
}
