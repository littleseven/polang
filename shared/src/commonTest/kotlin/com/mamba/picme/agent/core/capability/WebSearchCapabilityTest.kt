package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.domain.chat.MessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 钉住 WebSearchCapability 的降级不变式（与 BrowserSessionCapabilityTest 同构）：
 * 一切失败映射结构化文本交 LLM 降级，不穿透 ReAct 链；CancellationException 例外原样 rethrow。
 */
class WebSearchCapabilityTest {

    private class FakeTransport(
        var result: String = """{"status":"ok","results":[]}""",
        var failWith: Exception? = null,
    ) : WebSearchTransport {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): String {
            queries += query
            failWith?.let { throw it }
            return result
        }
    }

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `search success passes through server json`() = runTest {
        val transport = FakeTransport(result = """{"status":"ok","results":[{"title":"t"}]}""")
        val cap = WebSearchCapability(transport)
        val result = cap.execute(AgentCommand.WebSearch(query = " polka 海报 "), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertEquals("""{"status":"ok","results":[{"title":"t"}]}""", action.message)
        assertEquals(listOf(" polka 海报 "), transport.queries)
    }

    @Test
    fun `unavailable exception maps to WEB_SEARCH_UNAVAILABLE degradation`() = runTest {
        val transport = FakeTransport(failWith = WebSearchUnavailableException("not_configured"))
        val cap = WebSearchCapability(transport)
        val result = cap.execute(AgentCommand.WebSearch(query = "q"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.startsWith("[WEB_SEARCH_UNAVAILABLE]"))
        assertTrue(action.message.contains("not_configured"))
    }

    @Test
    fun `generic exception maps to WEB_SEARCH_FAILED degradation`() = runTest {
        val transport = FakeTransport(failWith = IllegalStateException("timeout"))
        val cap = WebSearchCapability(transport)
        val result = cap.execute(AgentCommand.WebSearch(query = "q"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.startsWith("[WEB_SEARCH_FAILED]"))
    }

    @Test
    fun `cancellation rethrows through the chain`() = runTest {
        val transport = FakeTransport(failWith = CancellationException("cancelled"))
        val cap = WebSearchCapability(transport)
        assertFailsWith<CancellationException> {
            cap.execute(AgentCommand.WebSearch(query = "q"), context, null)
        }
    }

    @Test
    fun `supported commands is web_search only`() {
        assertEquals(listOf("web_search"), WebSearchCapability(FakeTransport()).supportedCommands())
    }

    // ── source 抽取（值域终态 §6.2，capability 层纯函数）────────

    @Test
    fun `extract sources takes at most five url-bearing results`() {
        val payload = """
            {"status":"ok","results":[
              {"title":"t1","url":"https://a.com","snippet":"s","images":["https://img/1.jpg"]},
              {"title":"t2","url":"https://b.com"},
              {"title":"t3","url":"https://c.com"},
              {"title":"t4","url":"https://d.com"},
              {"title":"t5","url":"https://e.com"},
              {"title":"t6","url":"https://f.com"},
              {"title":"t7","url":"https://g.com"}
            ]}
        """.trimIndent()
        val sources = extractWebSearchSources(payload)
        assertEquals(5, sources.size)
        // ≤5 截断保序；title/url 语义保留，images/snippet 不进 chip（图链归正文 markdown）
        assertEquals(
            MessagePart.SourceUrl(partId = "", sourceId = "https://a.com", url = "https://a.com", title = "t1"),
            sources.first(),
        )
        assertEquals("https://e.com", sources.last().url)
    }

    @Test
    fun `extract sources skips entries without url and keeps title optional`() {
        val payload = """
            {"status":"ok","results":[
              {"title":"无链接结果","snippet":"s"},
              {"title":"","url":"https://blank-title.com"},
              {"url":"https://no-title.com"}
            ]}
        """.trimIndent()
        val sources = extractWebSearchSources(payload)
        assertEquals(
            listOf(
                MessagePart.SourceUrl("", "https://blank-title.com", "https://blank-title.com", null),
                MessagePart.SourceUrl("", "https://no-title.com", "https://no-title.com", null),
            ),
            sources,
        )
    }

    @Test
    fun `extract sources returns empty for degraded or malformed payload`() {
        // 降级文本（[WEB_SEARCH_*] 前缀）与损坏 payload：抽取是纯增益通道，不抛不影响 LLM 路径
        assertEquals(emptyList(), extractWebSearchSources("[WEB_SEARCH_UNAVAILABLE] 网页搜索暂不可用：not_configured"))
        assertEquals(emptyList(), extractWebSearchSources("[WEB_SEARCH_FAILED] 网页搜索失败：timeout"))
        assertEquals(emptyList(), extractWebSearchSources("""{"broken":"""))
        assertEquals(emptyList(), extractWebSearchSources("""{"status":"error","message":"upstream"}"""))
    }

    @Test
    fun `extract sources consumes the same payload the transport produced`() = runTest {
        // 端到端口径钉：transport 返回原文 → capability 原样 TextReply → 抽取同一份原文
        val transport = FakeTransport(
            result = """{"status":"ok","results":[{"title":"t","url":"https://a.com","images":["https://i/1.jpg"]}]}""",
        )
        val cap = WebSearchCapability(transport)
        val action = assertIs<AgentAction.TextReply>(
            cap.execute(AgentCommand.WebSearch(query = "q"), context, null).getOrThrow(),
        )
        assertEquals(
            listOf(MessagePart.SourceUrl("", "https://a.com", "https://a.com", "t")),
            extractWebSearchSources(action.message),
        )
    }
}
