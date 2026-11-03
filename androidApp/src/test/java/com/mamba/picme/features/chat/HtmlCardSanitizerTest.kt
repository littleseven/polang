package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlCardSanitizerTest {

    @Test
    fun `blank html is rejected`() {
        assertTrue(HtmlCardSanitizer.sanitize("  ") is HtmlCardSanitizer.Result.Rejected)
    }

    @Test
    fun `oversize html is rejected`() {
        val big = "<div>" + "x".repeat(HtmlCardSanitizer.MAX_HTML_BYTES) + "</div>"
        assertTrue(HtmlCardSanitizer.sanitize(big) is HtmlCardSanitizer.Result.Rejected)
    }

    @Test
    fun `inline html css js pass through unchanged`() {
        val html = """<div style="color:red">hi</div><script>var a=1;document.title='t'+a;</script>"""
        val result = HtmlCardSanitizer.sanitize(html)
        assertTrue(result is HtmlCardSanitizer.Result.Ok)
        assertEquals(html, (result as HtmlCardSanitizer.Result.Ok).html)
    }

    @Test
    fun `external script tag is stripped`() {
        val html = """<div>a</div><script src="https://evil.com/x.js"></script><div>b</div>"""
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertFalse(out.contains("evil.com"))
        assertTrue(out.contains("<div>a</div><div>b</div>"))
    }

    @Test
    fun `meta refresh and iframe are stripped while link stylesheet allowed`() {
        // 2026-09-25 放开：<link> 远程样式保留（表现力优先）；meta refresh / iframe 仍剔除
        val html = """<link rel="stylesheet" href="https://cdn.example.com/a.css">""" +
            """<meta http-equiv="refresh" content="0;url=https://evil.com">""" +
            """<iframe src="https://evil.com"></iframe><p>ok</p>"""
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertTrue(out.contains("cdn.example.com"))
        assertFalse(out.contains("evil.com"))
        assertFalse(out.contains("<iframe"))
        assertTrue(out.contains("<p>ok</p>"))
    }

    @Test
    fun `remote img and anchor href are preserved`() {
        // 2026-09-25 放开：远程 img / <a> 外链保留（a 点击由卡片回调宿主打开落地页）
        val html = """<img src="https://cdn.example.com/a.png"><img src="data:image/png;base64,xx">""" +
            """<a href="https://example.com">x</a>"""
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertEquals(html, out)
    }

    @Test
    fun `css import and remote url are preserved`() {
        // 2026-09-25 放开：@import 与 CSS 远程 url() 保留（远程字体/背景图等表现力）
        val html = """<style>@import url("https://cdn.example.com/a.css");.x{background:url('https://cdn.example.com/b.png')}</style>"""
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertEquals(html, out)
    }

    // ── 2026-11-04 SOE 修复：懒惰点星整段剔除改手动扫描，大 tag 体/缺闭合不爆栈 ──

    @Test
    fun `external script with huge body is stripped without stack overflow`() {
        // 旧 .*?</script> 懒惰量词逐字符递归：带 src 且 tag 体数万字符时爆栈
        val body = "var x = 1;".repeat(10_000)
        val html = "<p>a</p><script src=\"https://cdn.example.com/lib.js>$body</script><p>b</p>"
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertEquals("<p>a</p><p>b</p>", out)
    }

    @Test
    fun `unclosed external script opener is kept (regex parity) and survives`() {
        // 无闭合 </script>：旧正则「无闭合不匹配」保留语义不变，且懒惰量词扫到全文尾不再爆栈
        val html = "<p>a</p><script src=\"https://cdn.example.com/lib.js>" + "x".repeat(60_000)
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertEquals(html, out)
    }

    @Test
    fun `dangerous blocks with huge body are stripped without stack overflow`() {
        // ASCII 填料控字节数（上限 128KB）：110KB 规模旧懒惰点星正则递归必爆栈
        val body = "<div>ab</div>".repeat(5_000)
        val html = "<p>a</p><iframe src=\"https://evil.com\">$body</iframe>" +
            "<form action=\"https://evil.com\">$body</form><p>b</p>"
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertEquals("<p>a</p><p>b</p>", out)
    }

    @Test
    fun `frameset matches its own closing tag not frame`() {
        // 长名优先：旧正则靠交替回溯命中 frameset，手动扫描须同口径
        val html = "<p>a</p><frameset><frame src=\"https://evil.com\"></frameset><p>b</p>"
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertEquals("<p>a</p><p>b</p>", out)
    }
}
