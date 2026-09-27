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
    fun `negative synthetic media ids are rewritten intact`() {
        // 未落库系统媒体的合成 id 为负数（-(mediaStoreId*10+salt)），重写必须原样保留
        val html = """<img src="media://-10000211391">"""
        val out = LocalMediaWebViewAssets.rewriteMediaRefs(html)
        assertTrue(out.contains("""src="https://polang-media.invalid/media/-10000211391""""))
    }

    @Test
    fun `already rewritten urls are stable under re-rewrite`() {
        val once = LocalMediaWebViewAssets.rewriteMediaRefs("""<img src="media://42">""")
        assertEquals(once, LocalMediaWebViewAssets.rewriteMediaRefs(once))
    }

    // ── parseMediaId 边界（WebViewAssetLoader path handler 入参为 /media/ 前缀后的尾段）──

    @Test
    fun `parseMediaId parses plain and negative ids`() {
        assertEquals(123L, LocalMediaWebViewAssets.parseMediaId("123"))
        assertEquals(-10000211391L, LocalMediaWebViewAssets.parseMediaId("-10000211391"))
    }

    @Test
    fun `parseMediaId tolerates trailing slashes`() {
        assertEquals(123L, LocalMediaWebViewAssets.parseMediaId("123/"))
        assertEquals(123L, LocalMediaWebViewAssets.parseMediaId("123//"))
    }

    @Test
    fun `parseMediaId rejects extra leading path segments`() {
        // 多余前导路径段（契约外输入）安全降级 null
        assertEquals(null, LocalMediaWebViewAssets.parseMediaId("/123"))
        assertEquals(null, LocalMediaWebViewAssets.parseMediaId("a/123"))
    }

    @Test
    fun `parseMediaId rejects long overflow and non numeric input`() {
        assertEquals(null, LocalMediaWebViewAssets.parseMediaId("99999999999999999999"))
        assertEquals(null, LocalMediaWebViewAssets.parseMediaId("abc"))
        assertEquals(null, LocalMediaWebViewAssets.parseMediaId(""))
    }

    @Test
    fun `parseMediaId normalizes leading zeros`() {
        // 前导零按数值归一（锁定语义：007 与 7 指同一媒体）
        assertEquals(7L, LocalMediaWebViewAssets.parseMediaId("007"))
    }

    // ── parseMediaRefId（JS 运行时拼接 media:// URL 直通拦截的解析口）──

    @Test
    fun `parseMediaRefId parses runtime media urls`() {
        assertEquals(123L, LocalMediaWebViewAssets.parseMediaRefId("media://123"))
        assertEquals(-10000211391L, LocalMediaWebViewAssets.parseMediaRefId("media://-10000211391"))
        assertEquals(123L, LocalMediaWebViewAssets.parseMediaRefId("media://123/"))
    }

    @Test
    fun `parseMediaRefId rejects malformed urls`() {
        assertEquals(null, LocalMediaWebViewAssets.parseMediaRefId("media://abc"))
        assertEquals(null, LocalMediaWebViewAssets.parseMediaRefId("media://"))
        assertEquals(null, LocalMediaWebViewAssets.parseMediaRefId("media://99999999999999999999"))
        assertEquals(null, LocalMediaWebViewAssets.parseMediaRefId("https://polang-media.invalid/media/123"))
    }

    @Test
    fun `media host uses reserved invalid tld and prefix is derived`() {
        // 锁死纵深防线：白名单域必须是 RFC 2606 保留 TLD（拦截失效时 DNS 必失败），
        // URL 前缀必须由该域派生，二者漂移此处立即报警。
        assertTrue(LocalMediaWebViewAssets.MEDIA_HOST.endsWith(".invalid"))
        assertEquals("https://" + LocalMediaWebViewAssets.MEDIA_HOST, LocalMediaWebViewAssets.MEDIA_URL_PREFIX)
    }
}
