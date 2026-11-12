package com.mamba.picme.agent.core.remote.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LatestLlmWireTap] / [extractCachedTokens] 单测（2026-11-12 缺口清偿）：
 * latest 槽 + 序号、captureRequests 隐私开关、cached_tokens 逐请求清零与子串提取。
 */
class LatestLlmWireTapTest {

    @Test
    fun `latest slot keeps most recent request with monotonic seq`() {
        val tap = LatestLlmWireTap(captureRequests = true)
        tap.onRequest("https://a/v1/chat", "{}-1")
        tap.onRequest("https://a/v1/chat", "{}-2")
        val latest = tap.latestRequest()
        assertEquals("https://a/v1/chat", latest?.url)
        assertEquals("{}-2", latest?.bodyJson)
        assertTrue(latest!!.seq > 0)
    }

    @Test
    fun `captureRequests false retains nothing but still scans sse`() {
        val tap = LatestLlmWireTap(captureRequests = false)
        tap.onRequest("https://a", "SECRET-BODY")
        assertNull(tap.latestRequest(), "release 形态不得保留请求体（隐私红线）")
        tap.onSseDataLine("""data: {"usage":{"prompt_tokens_details":{"cached_tokens":7}}}""")
        assertEquals(7, tap.latestCachedTokens(), "SSE 指标扫描不受 captureRequests 影响")
    }

    @Test
    fun `cached tokens reset on each new request`() {
        val tap = LatestLlmWireTap(captureRequests = true)
        tap.onRequest("u", "b1")
        tap.onSseDataLine("""{"prompt_tokens_details":{"cached_tokens":7}}""")
        assertEquals(7, tap.latestCachedTokens())
        tap.onRequest("u", "b2")
        assertNull(tap.latestCachedTokens(), "usage 只属于当次请求——新请求应清零防跨请求误归因")
    }

    @Test
    fun `extractCachedTokens parses nested usage frame without regex`() {
        assertEquals(
            123,
            extractCachedTokens("""{"id":"x","usage":{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":123,"audio_tokens":0}}}"""),
        )
        assertEquals(456, extractCachedTokens("""{"cached_tokens": 456}"""), "冒号后带空白应可解析")
        assertEquals(0, extractCachedTokens("""{"cached_tokens":0}"""), "0 是合法命中值")
    }

    @Test
    fun `extractCachedTokens returns null when absent or non-numeric`() {
        assertNull(extractCachedTokens("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertNull(extractCachedTokens("""{"prompt_tokens_details":{"cached_tokens":null}}"""))
        assertNull(extractCachedTokens("""{"prompt_tokens_details":{"cached_tokens":true}}"""))
        assertNull(extractCachedTokens(""))
    }
}
