package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.reflect.asToolsByClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * render_html 服务端工具（summary-only 回执语义）：
 * - LLM 只收到 summary 回执，HTML 本体不回上下文；
 * - 完整 html+summary 经 onHtmlCard sink 下行（事件流 HtmlCard 载荷 → SSE）；
 * - html 超 128_000 字符（端侧 HtmlCardSanitizer 128KB 口径）拒绝且 sink 不触发；
 * - 直派器（pending 重放路径）同源可派，且支持 run 级 sink 重绑。
 */
class ServerRenderHtmlToolTest {

    private class RecordingSink {
        val calls = mutableListOf<Pair<String, String>>()
        val fn: suspend (String, String) -> Unit = { html, summary -> calls += html to summary }
    }

    @Test
    fun `default registry exposes render_html tool`() {
        val names = ServerToolService().asToolsByClass().map { tool -> tool.descriptor.name }.toSet()
        assertTrue(names.contains("render_html"))
    }

    @Test
    fun `receipt carries summary only and sink receives full html`() = runBlocking {
        val sink = RecordingSink()
        val service = ServerToolService(onHtmlCard = sink.fn)
        val html = "<html><body><h1>标题 \"引号\"\n换行</h1></body></html>"

        val receipt = service.renderHtml(html, "相册周报卡")

        assertEquals("已渲染 HTML 卡片：相册周报卡", receipt)
        assertFalse(receipt.contains("<html>"))
        assertEquals(listOf(html to "相册周报卡"), sink.calls)
    }

    @Test
    fun `oversized html is rejected and sink not triggered`() = runBlocking {
        val sink = RecordingSink()
        val service = ServerToolService(onHtmlCard = sink.fn)
        val html = "x".repeat(ServerToolService.MAX_HTML_CHARS + 1)

        val receipt = service.renderHtml(html, "太大")

        assertEquals("error: html_too_large", receipt)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `exactly max length html is accepted`() = runBlocking {
        val sink = RecordingSink()
        val service = ServerToolService(onHtmlCard = sink.fn)
        val html = "x".repeat(ServerToolService.MAX_HTML_CHARS)

        val receipt = service.renderHtml(html, "边界")

        assertTrue(receipt.startsWith("已渲染 HTML 卡片："))
        assertEquals(listOf(html to "边界"), sink.calls)
    }

    @Test
    fun `dispatcher dispatches render_html with parsed args and triggers sink`() = runBlocking {
        val sink = RecordingSink()
        val dispatcher = ServerToolDispatcher.default(onHtmlCard = sink.fn)

        assertTrue(dispatcher.contains("render_html"))
        val receipt = dispatcher.dispatch("render_html", """{"html":"<b>hi</b>","summary":"s"}""")

        assertEquals("已渲染 HTML 卡片：s", receipt)
        assertEquals(listOf("<b>hi</b>" to "s"), sink.calls)
    }

    @Test
    fun `dispatcher dispatch of oversized render_html does not trigger sink`() = runBlocking {
        val sink = RecordingSink()
        val dispatcher = ServerToolDispatcher.default(onHtmlCard = sink.fn)
        val html = "y".repeat(ServerToolService.MAX_HTML_CHARS + 1)

        val receipt = dispatcher.dispatch("render_html", """{"html":"$html","summary":"大"}""")

        assertEquals("error: html_too_large", receipt)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `withHtmlCardSink rebinds run scoped sink without touching base dispatcher`() = runBlocking {
        val sink = RecordingSink()
        val base = ServerToolDispatcher.default(clock = { 1L })
        val scoped = base.withHtmlCardSink(sink.fn)

        val receipt = scoped.dispatch("render_html", """{"html":"<i>x</i>","summary":"t"}""")
        assertEquals("已渲染 HTML 卡片：t", receipt)
        assertEquals(listOf("<i>x</i>" to "t"), sink.calls)
        // 其余 handler 原样共享
        assertTrue(scoped.dispatch("server_time", "{}").contains("server_time"))
        // base 的 render_html 仍是默认 no-op sink，不受重绑影响
        assertEquals("已渲染 HTML 卡片：q", base.dispatch("render_html", """{"html":"<u>y</u>","summary":"q"}"""))
        assertEquals(listOf("<i>x</i>" to "t"), sink.calls)
    }
}
