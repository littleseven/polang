package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
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
        val transport = FakeTransport(failWith = java.net.SocketTimeoutException("timeout"))
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
}
