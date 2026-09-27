package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalMediaWebViewAssets.rewriteMediaRefs] 纯函数单测：
 * LLM 契约 `media://{id}` → 白名单域 URL 的渲染前重写规则。
 */
class LocalMediaWebViewAssetsTest {

    @Test
    fun `img and video media refs are rewritten to whitelist domain urls`() {
        val html = """<img src="media://123"><video src="media://456" poster="media://789"></video>"""
        val out = LocalMediaWebViewAssets.rewriteMediaRefs(html)
        assertTrue(out.contains("""src="https://polang-media.invalid/media/123""""))
        assertTrue(out.contains("""src="https://polang-media.invalid/media/456""""))
        assertTrue(out.contains("""poster="https://polang-media.invalid/media/789""""))
        assertFalse(out.contains("media://"))
    }

    @Test
    fun `html without media refs is returned unchanged`() {
        val html = """<div><img src="https://example.com/a.png"></div>"""
        assertEquals(html, LocalMediaWebViewAssets.rewriteMediaRefs(html))
    }

    @Test
    fun `non numeric media refs are left untouched`() {
        val html = """<img src="media://abc"><img src="media://">"""
        assertEquals(html, LocalMediaWebViewAssets.rewriteMediaRefs(html))
    }

    @Test
    fun `id followed by letters is not half matched`() {
        val html = """<img src="media://123abc">"""
        assertEquals(html, LocalMediaWebViewAssets.rewriteMediaRefs(html))
    }

    @Test
    fun `already rewritten urls are stable under re-rewrite`() {
        val once = LocalMediaWebViewAssets.rewriteMediaRefs("""<img src="media://42">""")
        assertEquals(once, LocalMediaWebViewAssets.rewriteMediaRefs(once))
    }
}
