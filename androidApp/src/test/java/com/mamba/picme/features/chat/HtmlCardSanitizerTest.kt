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
    fun `link and meta refresh and iframe are stripped`() {
        val html = """<link rel="stylesheet" href="https://cdn.example.com/a.css">""" +
            """<meta http-equiv="refresh" content="0;url=https://evil.com">""" +
            """<iframe src="https://evil.com"></iframe><p>ok</p>"""
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertFalse(out.contains("cdn.example.com"))
        assertFalse(out.contains("evil.com"))
        assertFalse(out.contains("<iframe"))
        assertTrue(out.contains("<p>ok</p>"))
    }

    @Test
    fun `remote attributes are blanked while inline kept`() {
        val html = """<img src="https://evil.com/a.png"><img src="data:image/png;base64,xx">""" +
            """<a href="https://evil.com">x</a>"""
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertFalse(out.contains("evil.com"))
        assertTrue(out.contains("data:image/png;base64,xx"))
    }

    @Test
    fun `css import and remote url are stripped`() {
        val html = """<style>@import url("https://evil.com/a.css");.x{background:url('https://evil.com/b.png')}</style>"""
        val out = (HtmlCardSanitizer.sanitize(html) as HtmlCardSanitizer.Result.Ok).html
        assertFalse(out.contains("evil.com"))
        assertFalse(out.contains("@import"))
    }
}
