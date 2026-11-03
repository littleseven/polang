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

    // ── 2026-11-04 SOE 修复：Html 内嵌整页 HTML（转义密集大字面量）嗅探不爆栈 ──
    // 旧交替-星号字面量正则 GroupCurly 逐字符递归，数 KB 字面量即 StackOverflowError

    private fun hugeHtmlProgram(htmlChars: Int): String {
        val body = buildString {
            append("<html><body>")
            var i = 0
            while (length < htmlChars) {
                append("<div class=\\\"r\\\" data-i=\\\"").append(i % 10).append("\\\">x</div>")
                i++
            }
            append("</body></html>")
        }
        return "// summary: 勾股定理演示\nroot = Html(\"$body\")"
    }

    @Test
    fun `looksLikeProgram survives huge Html literal program`() {
        assertTrue(OpenUiProgramSummary.looksLikeProgram(hugeHtmlProgram(60_000)))
        // 未闭合字面量（流式截断形态）同样不崩；root = 在未闭合字面量之前仍可见 → true
        // （与旧正则同口径：剥离只针对完整字面量，未闭合尾段原样保留）
        val truncated = "// summary: t\nroot = Html(\"" + "x".repeat(60_000)
        assertTrue(OpenUiProgramSummary.looksLikeProgram(truncated))
    }

    @Test
    fun `extractFallbackSummary survives huge Html literal program`() {
        assertEquals("勾股定理演示", OpenUiProgramSummary.extractFallbackSummary(hugeHtmlProgram(60_000)))
    }

    @Test
    fun `extractFallbackSummary ignores unterminated literal`() {
        // 无后续引号 = 真未闭合：与旧正则「未闭合不匹配」同口径，落到空串
        assertEquals("", OpenUiProgramSummary.extractFallbackSummary("root = Markdown(\"未闭合正文"))
    }
}
