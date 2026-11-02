package com.mamba.picme.server.websearch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchServiceTest {

    // ── extractImageUrls ──────────────────────────────────────────

    @Test
    fun `extractImageUrls 提取 https 图片并去重`() {
        val md = """
            正文 ![封面](https://cdn.example.com/a.jpg) 中间
            ![重复](https://cdn.example.com/a.jpg)
            ![非https](http://cdn.example.com/b.jpg)
            ![另一张](<https://cdn.example.com/c.png>)
        """.trimIndent()
        assertEquals(
            listOf("https://cdn.example.com/a.jpg", "https://cdn.example.com/c.png"),
            WebSearchService.extractImageUrls(md),
        )
    }

    @Test
    fun `extractImageUrls 超量截断`() {
        val md = (1..6).joinToString("\n") { "![图$it](https://cdn.example.com/$it.jpg)" }
        assertEquals(
            listOf(
                "https://cdn.example.com/1.jpg",
                "https://cdn.example.com/2.jpg",
                "https://cdn.example.com/3.jpg",
            ),
            WebSearchService.extractImageUrls(md),
        )
    }

    @Test
    fun `extractImageUrls 无图片返回空`() {
        assertEquals(emptyList<String>(), WebSearchService.extractImageUrls("纯文本 [链接](https://example.com)"))
    }

    // ── excerpt ───────────────────────────────────────────────────

    @Test
    fun `excerpt 去图片语法并折叠空白`() {
        val md = "第一段 ![图](https://cdn.example.com/a.jpg) 第二段\n\n  第三段"
        assertEquals("第一段 第二段 第三段", WebSearchService.excerpt(md))
    }

    @Test
    fun `excerpt 超长截断`() {
        val md = "字".repeat(1000)
        assertEquals(WebSearchService.MAX_CONTENT_EXCERPT_CHARS, WebSearchService.excerpt(md).length)
    }

    // ── parseResults ──────────────────────────────────────────────

    @Test
    fun `parseResults 解析并瘦身`() {
        val body = """
            {"results":[
              {"title":"标题A","url":"https://a.example.com","snippet":"摘要A",
               "content":"正文 ![配图](https://cdn.example.com/a.jpg) 后续",
               "site_name":"站点A","date":"2026-06-01T00:00:00+00:00","authority":3},
              {"title":"","url":"https://b.example.com","snippet":"无标题应被丢弃","content":""},
              {"title":"标题C","url":"https://c.example.com","snippet":"摘要C","content":null}
            ]}
        """.trimIndent()
        val results = WebSearchService.parseResults(body)
        assertEquals(2, results.size)
        val first = results[0]
        assertEquals("标题A", first.title)
        assertEquals(listOf("https://cdn.example.com/a.jpg"), first.images)
        assertEquals("正文 后续", first.contentExcerpt)
        assertEquals("站点A", first.siteName)
        assertEquals(emptyList<String>(), results[1].images)
    }

    @Test
    fun `parseResults 非法 JSON 与缺字段返回空`() {
        assertEquals(emptyList<WebSearchResult>(), WebSearchService.parseResults("not json"))
        assertEquals(emptyList<WebSearchResult>(), WebSearchService.parseResults("{}"))
    }

    // ── toClientJson ──────────────────────────────────────────────

    @Test
    fun `toClientJson 结构紧凑且空字段省略`() {
        val json = WebSearchService.toClientJson(
            listOf(
                WebSearchResult(
                    title = "标题", url = "https://a.example.com", snippet = "摘要",
                    siteName = null, date = null,
                    images = listOf("https://cdn.example.com/a.jpg"),
                    contentExcerpt = "",
                ),
            ),
        )
        assertEquals("ok", json["status"]?.toString()?.trim('"'))
        val result = json["results"]!!.toString()
        assertTrue(result.contains("images"))
        assertTrue(!result.contains("site_name"))
        assertTrue(!result.contains("content_excerpt"))
    }

    // ── WebSearchService（MockEngine）─────────────────────────────

    @Test
    fun `service 200 映射 Success`() = runBlocking {
        val service = WebSearchService(
            HttpClient(MockEngine {
                respond(
                    """{"results":[{"title":"t","url":"https://a.example.com","snippet":"s","content":"x ![i](https://cdn.example.com/i.jpg)"}]}""",
                    HttpStatusCode.OK,
                )
            }),
            apiKey = "k",
        )
        val outcome = service.search("q", 5)
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(1, results.size)
        assertEquals(listOf("https://cdn.example.com/i.jpg"), results[0].images)
    }

    @Test
    fun `service 402 映射 Unavailable insufficient_balance`() = runBlocking {
        val service = WebSearchService(
            HttpClient(MockEngine { respond("""{"error":{"message":"balance"}}""", HttpStatusCode.PaymentRequired) }),
            apiKey = "k",
        )
        assertEquals(SearchOutcome.Unavailable("insufficient_balance"), service.search("q", 5))
    }

    @Test
    fun `service 网络异常映射 Unavailable 不抛出`() = runBlocking {
        val service = WebSearchService(
            HttpClient(MockEngine { throw java.net.SocketTimeoutException("timeout") }),
            apiKey = "k",
        )
        val outcome = service.search("q", 5)
        assertTrue(outcome is SearchOutcome.Unavailable)
    }

    @Test
    fun `service 上游慢于 UPSTREAM_TIMEOUT_MS 映射 upstream_timeout（先于端侧 15s 放弃）`() = runTest {
        val service = WebSearchService(
            HttpClient(MockEngine {
                kotlinx.coroutines.delay(WebSearchService.UPSTREAM_TIMEOUT_MS + 60_000L)
                respond("""{"results":[]}""", HttpStatusCode.OK)
            }),
            apiKey = "k",
        )
        assertEquals(SearchOutcome.Unavailable("upstream_timeout"), service.search("q", 5))
    }
}
