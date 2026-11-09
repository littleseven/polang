package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebViewLibsTest {

    private val echartsUrl = LocalWebViewLibs.libUrl("echarts")!!

    @Test
    fun `no meta declaration leaves html unchanged`() {
        val html = "<html><head></head><body><div>hi</div></body></html>"
        assertEquals(html, LocalWebViewLibs.injectDeclaredLibs(html))
        assertTrue(LocalWebViewLibs.declaredLibs(html).isEmpty())
    }

    @Test
    fun `echarts declaration injects script after head open tag`() {
        val html = """<html><head><meta name="polang-lib" content="echarts"></head><body></body></html>"""
        val out = LocalWebViewLibs.injectDeclaredLibs(html)
        val headEnd = out.indexOf("<head>") + "<head>".length
        assertTrue(out.substring(headEnd).startsWith("""<script src="$echartsUrl"></script>"""))
        // meta 声明保留在文档中（落库契约形态），script 为渲染期注入
        assertTrue(out.contains("""<meta name="polang-lib" content="echarts">"""))
    }

    @Test
    fun `declaration with attrs in any order and multiple names`() {
        val html = """<head></head><meta content="echarts" name='polang-lib'>"""
        assertEquals(listOf("echarts"), LocalWebViewLibs.declaredLibs(html))
    }

    @Test
    fun `unknown lib names are ignored`() {
        val html = """<meta name="polang-lib" content="jquery, echarts, react">"""
        assertEquals(listOf("echarts"), LocalWebViewLibs.declaredLibs(html))
    }

    @Test
    fun `duplicate declarations inject only once`() {
        val html = """<head><meta name="polang-lib" content="echarts"><meta name="polang-lib" content="echarts"></head>"""
        val out = LocalWebViewLibs.injectDeclaredLibs(html)
        assertEquals(1, out.split(echartsUrl).size - 1)
    }

    @Test
    fun `injection is idempotent on already injected html`() {
        val html = """<head><meta name="polang-lib" content="echarts"></head>"""
        val once = LocalWebViewLibs.injectDeclaredLibs(html)
        assertEquals(once, LocalWebViewLibs.injectDeclaredLibs(once))
    }

    @Test
    fun `fragment without head falls back to body or document start`() {
        val withBody = """<meta name="polang-lib" content="echarts"><body><div>x</div></body>"""
        val outBody = LocalWebViewLibs.injectDeclaredLibs(withBody)
        assertTrue(outBody.indexOf(echartsUrl) < outBody.indexOf("<body>"))

        val bare = """<meta name="polang-lib" content="echarts"><div>x</div>"""
        assertTrue(LocalWebViewLibs.injectDeclaredLibs(bare).startsWith("""<script src="$echartsUrl"></script>"""))
    }

    @Test
    fun `sanitizer keeps polang-lib meta while stripping script src`() {
        // 契约闭环：LLM 直接手写 script src 被剔除，meta 声明留存供渲染期展开
        val raw = """<meta name="polang-lib" content="echarts"><script src="https://polang-lib.invalid/echarts/echarts.min.js"></script>"""
        val out = (HtmlCardSanitizer.sanitize(raw) as HtmlCardSanitizer.Result.Ok).html
        assertTrue(out.contains("polang-lib"))
        assertFalse(out.contains("<script"))
    }
}
