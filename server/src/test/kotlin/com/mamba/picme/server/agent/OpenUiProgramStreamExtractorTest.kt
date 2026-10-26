package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OpenUiProgramStreamExtractor] 单测：JSON 感知键定位、转义解码、断帧容错。
 * 黄金程序取自 render_ui_openui 工具描述同款结构（program 在前 fallbackSummary 在后）。
 *
 * 注意：args JSON 文本一律用普通字符串显式转义（\" 在 JSON 文本里是 \\ 字符），
 * 避免 raw string 收尾引号歧义（2026-10-26 三轮字面量事故）。
 */
class OpenUiProgramStreamExtractorTest {

    // JSON 文本：{"program": "root = Card([Text(\"相册总览\")])", "fallbackSummary": "统计卡"}
    private val fullArgs =
        "{\"program\": \"root = Card([Text(\\\"相册总览\\\")])\", \"fallbackSummary\": \"统计卡\"}"

    @Test
    fun `complete args json extracts full program`() {
        assertEquals(
            "root = Card([Text(\"相册总览\")])",
            OpenUiProgramStreamExtractor.extractProgramPrefix(fullArgs),
        )
    }

    @Test
    fun `truncated mid program yields decoded prefix`() {
        val cut = fullArgs.indexOf("Text")
        assertEquals(
            "root = Card([Text",
            OpenUiProgramStreamExtractor.extractProgramPrefix(fullArgs.substring(0, cut + 4)),
        )
    }

    @Test
    fun `program key not yet arrived yields null`() {
        assertNull(OpenUiProgramStreamExtractor.extractProgramPrefix("{\"pro"))
        assertNull(OpenUiProgramStreamExtractor.extractProgramPrefix(""))
    }

    @Test
    fun `opening quote arrived but no content yet yields empty prefix`() {
        assertEquals("", OpenUiProgramStreamExtractor.extractProgramPrefix("{\"program\": \""))
    }

    @Test
    fun `escape sequences decoded and mid escape cut tolerated`() {
        // JSON 文本：{"program": "a = \"x\ny\"", "fallbackSummary": "s"}
        val args = "{\"program\": \"a = \\\"x\\ny\\\"\", \"fallbackSummary\": \"s\"}"
        assertEquals("a = \"x\ny\"", OpenUiProgramStreamExtractor.extractProgramPrefix(args))
        // 反斜杠后断帧（JSON 文本 {"program": "a = \）：丢弃不完整转义
        assertEquals("a = ", OpenUiProgramStreamExtractor.extractProgramPrefix("{\"program\": \"a = \\"))
    }

    @Test
    fun `unicode escape decoded and incomplete unicode dropped`() {
        assertEquals(
            "中文",
            OpenUiProgramStreamExtractor.extractProgramPrefix("{\"program\": \"中\\u6587\"}"),
        )
        // \u 只有 3 位十六进制 → 不完整丢弃
        assertEquals(
            "中",
            OpenUiProgramStreamExtractor.extractProgramPrefix("{\"program\": \"中\\u658\""),
        )
    }

    @Test
    fun `program word inside other string value does not false match`() {
        val args = "{\"fallbackSummary\": \"program 统计\", \"program\": \"root = Card([])\"}"
        assertEquals("root = Card([])", OpenUiProgramStreamExtractor.extractProgramPrefix(args))
    }

    @Test
    fun `incremental feed equals one shot extraction`() {
        val extractor = OpenUiProgramStreamExtractor()
        var last: String? = null
        // 逐字符喂入模拟 token 增量
        fullArgs.forEach { ch -> extractor.feed(ch.toString())?.let { last = it } }
        assertEquals("root = Card([Text(\"相册总览\")])", last)
    }
}
