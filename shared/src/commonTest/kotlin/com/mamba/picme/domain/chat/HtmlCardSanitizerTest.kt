package com.mamba.picme.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HtmlCardSanitizer 单测（chat.yaml §13 sanitize 契约锁定）：
 * 放行内联 script / 剔远程 script / 剔高危嵌入（含自闭合与整段）/ 剔 meta refresh /
 * 远程 img/CSS/a 放行 / blank 与 128KB 超限拒绝 / 常规 HTML round-trip 等值。
 */
class HtmlCardSanitizerTest {

    @Test
    fun inlineScriptIsKept() {
        val html = "<div>x</div><script>console.log(1)</script>"
        val result = HtmlCardSanitizer.sanitize(html)
        assertTrue(result is HtmlCardSanitizer.Result.Ok)
        assertEquals(html, (result as HtmlCardSanitizer.Result.Ok).html)
    }

    @Test
    fun externalScriptIsStripped() {
        val html = "<div>x</div><script src=\"https://evil.com/a.js\"></script><p>y</p>"
        val result = HtmlCardSanitizer.sanitize(html)
        assertTrue(result is HtmlCardSanitizer.Result.Ok)
        val out = (result as HtmlCardSanitizer.Result.Ok).html
        assertFalse(out.contains("evil.com"), "remote script must be stripped")
        assertTrue(out.contains("<div>x</div>") && out.contains("<p>y</p>"), "surrounding content kept")
    }

    @Test
    fun dangerousEmbedsAreStripped() {
        val html = "<iframe src=\"https://evil.com\"></iframe><object data=\"o\"></object><embed src=\"e\">"
        val result = HtmlCardSanitizer.sanitize(html)
        assertTrue(result is HtmlCardSanitizer.Result.Ok)
        val out = (result as HtmlCardSanitizer.Result.Ok).html
        assertFalse(out.contains("iframe") || out.contains("object") || out.contains("embed"), "dangerous embeds stripped")
    }

    @Test
    fun metaRefreshIsStripped() {
        val html = "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.com\"><p>stay</p>"
        val result = HtmlCardSanitizer.sanitize(html)
        assertTrue(result is HtmlCardSanitizer.Result.Ok)
        assertFalse((result as HtmlCardSanitizer.Result.Ok).html.contains("refresh"))
    }

    @Test
    fun remoteResourcesAndLinksAreKept() {
        val html = "<link rel=\"stylesheet\" href=\"https://cdn.example.com/s.css\">" +
            "<img src=\"https://cdn.example.com/i.png\"><a href=\"https://example.com\">go</a>"
        val result = HtmlCardSanitizer.sanitize(html)
        assertTrue(result is HtmlCardSanitizer.Result.Ok)
        assertEquals(html, (result as HtmlCardSanitizer.Result.Ok).html)
    }

    @Test
    fun blankIsRejected() {
        val result = HtmlCardSanitizer.sanitize("   ")
        assertTrue(result is HtmlCardSanitizer.Result.Rejected)
    }

    @Test
    fun oversizedHtmlIsRejected() {
        val big = "<div>" + "a".repeat(HtmlCardSanitizer.MAX_HTML_BYTES) + "</div>"
        val result = HtmlCardSanitizer.sanitize(big)
        assertTrue(result is HtmlCardSanitizer.Result.Rejected)
        assertTrue(
            (result as HtmlCardSanitizer.Result.Rejected).reason.contains("128KB"),
            "reason should mention the limit for LLM guidance",
        )
    }

    @Test
    fun plainHtmlPassesThroughUnchanged() {
        val html = "<h1>标题</h1><p>段落 <b>加粗</b></p><table><tr><td>1</td></tr></table>"
        val result = HtmlCardSanitizer.sanitize(html)
        assertTrue(result is HtmlCardSanitizer.Result.Ok)
        assertEquals(html, (result as HtmlCardSanitizer.Result.Ok).html)
    }
}
