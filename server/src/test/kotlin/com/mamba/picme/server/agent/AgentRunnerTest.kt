package com.mamba.picme.server.agent

import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.fullPath
import ai.koog.http.client.ktor.KtorKoogHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * AgentRunner 端到端（Koog loop × 事件流 × MockEngine 假 LLM）。
 * 钉死：事件序列、Action↔Observation 配对、pending 重放、BYOK/CF_AIG 鉴权头形态。
 */
class AgentRunnerTest {

    private val capturedRequests = mutableListOf<Pair<String, Map<String, List<String>>>>()

    @Before
    fun setUp() {
        TestDb.init(AgentSessions, AgentEvents)
        capturedRequests.clear()
    }

    private fun chatResponse(text: String) = """
        {"id":"chatcmpl-1","object":"chat.completion","created":1730000000,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
    """.trimIndent()

    private fun toolCallResponse(callId: String, tool: String, args: String) = """
        {"id":"chatcmpl-2","object":"chat.completion","created":1730000000,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$callId","type":"function","function":{"name":"$tool","arguments":"$args"}}]},
           "finish_reason":"tool_calls"}],
         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
    """.trimIndent()

    private fun runnerWith(vararg responses: Pair<HttpStatusCode, String>): AgentRunner {
        val queue = ArrayDeque(responses.toList())
        val engine = MockEngine { request ->
            capturedRequests += request.url.fullPath to request.headers.entries().map { it.key to it.value }.toMap()
            val (status, body) = queue.removeFirst()
            respond(content = body, status = status, headers = headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        return AgentRunner(
            executorFactory = AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
            dispatcher = ServerToolDispatcher.default(),
        )
    }

    private suspend fun newSession(kind: AgentChannelKind = AgentChannelKind.GATEWAY): AgentSession =
        AgentSessionRepository.create("hashA", kind, "test-model")

    private val gatewayTarget = AgentLlmTarget(
        baseUrl = "https://mock.local/v1/",
        apiKey = "gateway-token",
        model = "test-model",
        authStyle = AuthStyle.BEARER,
    )

    @Test
    fun `plain turn appends user message assistant message and finish in order`() = runBlocking {
        val session = newSession()
        val emitted = mutableListOf<AgentEvent>()
        val finish = runnerWith(HttpStatusCode.OK to chatResponse("你好，我是云端 Agent"))
            .runTurn(session, "你好", gatewayTarget, "sys", ServerToolService.defaultRegistry()) { emitted += it }

        assertEquals(AgentEventType.FINISH, finish.type)
        val events = AgentEventRepository.listBySession(session.id)
        assertEquals(
            listOf(AgentEventType.MESSAGE, AgentEventType.MESSAGE, AgentEventType.FINISH),
            events.map { it.type },
        )
        assertEquals(AgentEventSource.USER, events[0].source)
        assertEquals(AgentEventPayload.Message("你好"), events[0].payload)
        assertEquals(AgentEventSource.AGENT, events[1].source)
        assertEquals(AgentEventPayload.Message("你好，我是云端 Agent"), events[1].payload)
        assertEquals(AgentEventPayload.Finish("done"), events[2].payload)
        // SSE 发射顺序 = 落流顺序
        assertEquals(events.map { it.id }, emitted.map { it.id })
    }

    @Test
    fun `tool turn closes action with observation and leaves no pending`() = runBlocking {
        val session = newSession()
        runnerWith(
            HttpStatusCode.OK to toolCallResponse("call_1", "server_time", "{}"),
            HttpStatusCode.OK to chatResponse("现在是中午"),
        ).runTurn(session, "几点了", gatewayTarget, "sys", ServerToolService.defaultRegistry())

        val events = AgentEventRepository.listBySession(session.id)
        assertEquals(
            listOf(
                AgentEventType.MESSAGE, AgentEventType.ACTION,
                AgentEventType.OBSERVATION, AgentEventType.MESSAGE, AgentEventType.FINISH,
            ),
            events.map { it.type },
        )
        val action = events[1].payload as AgentEventPayload.Action
        assertEquals("server_time", action.tool)
        assertEquals(false, action.reversible)
        val observation = events[2].payload as AgentEventPayload.Observation
        assertEquals(events[1].id, observation.actionEventId)
        assertEquals(AgentEventSource.ENVIRONMENT, events[2].source)
        assertEquals(AgentEventRole.TOOL, events[2].role)
        assertTrue(observation.content.isNotBlank())
        // pending 不变式：工具闭环后无 pending
        assertTrue(AgentEventRepository.pendingActions(session.id).isEmpty())
        assertEquals(AgentEventPayload.Message("现在是中午"), events[3].payload)
    }

    @Test
    fun `llm error appends error finish event and throws`() = runBlocking {
        val session = newSession()
        try {
            runnerWith(HttpStatusCode.InternalServerError to """{"error":"boom"}""")
                .runTurn(session, "hi", gatewayTarget, "sys", ServerToolService.defaultRegistry())
            fail("should throw on LLM error")
        } catch (e: Exception) {
            // expected
        }
        val events = AgentEventRepository.listBySession(session.id)
        val last = events.last()
        assertEquals(AgentEventType.FINISH, last.type)
        assertTrue((last.payload as AgentEventPayload.Finish).reason.startsWith("error"))
    }

    @Test
    fun `pending action from crashed turn is replayed before new llm call`() = runBlocking {
        val session = newSession()
        // 模拟崩溃现场：上一轮 Action 未等到 Observation
        val orphaned = AgentEventRepository.append(
            session.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action("server_time", "{}", reversible = false),
        )
        assertEquals(listOf(orphaned.id), AgentEventRepository.pendingActions(session.id).map { it.id })

        runnerWith(HttpStatusCode.OK to chatResponse("好的"))
            .runTurn(session, "继续", gatewayTarget, "sys", ServerToolService.defaultRegistry())

        // pending 被重放闭环
        assertTrue(AgentEventRepository.pendingActions(session.id).isEmpty())
        val obs = AgentEventRepository.listBySession(session.id)
            .mapNotNull { it.payload as? AgentEventPayload.Observation }
            .firstOrNull { it.actionEventId == orphaned.id }
        assertTrue(obs != null && !obs.revert && obs.content.isNotBlank())
    }

    @Test
    fun `pending replay of unknown tool closes action with error observation`() = runBlocking {
        val session = newSession()
        AgentEventRepository.append(
            session.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action("ghost_tool", "{}", reversible = false),
        )
        runnerWith(HttpStatusCode.OK to chatResponse("好的"))
            .runTurn(session, "继续", gatewayTarget, "sys", ServerToolService.defaultRegistry())
        val obs = AgentEventRepository.listBySession(session.id)
            .mapNotNull { it.payload as? AgentEventPayload.Observation }.single()
        assertTrue(obs.content.startsWith("error"))
        assertTrue(AgentEventRepository.pendingActions(session.id).isEmpty())
    }

    @Test
    fun `byok target sends user key as bearer and hits user base url`() = runBlocking {
        val session = newSession(AgentChannelKind.BYOK)
        val byok = AgentLlmTarget(
            baseUrl = "https://byok.local/api/",
            apiKey = "sk-user-secret-key",
            model = "user-model",
            authStyle = AuthStyle.BEARER,
        )
        runnerWith(HttpStatusCode.OK to chatResponse("ok"))
            .runTurn(session, "hi", byok, "sys", ServerToolService.defaultRegistry())

        val (path, headers) = capturedRequests.single()
        assertTrue(path.endsWith("chat/completions"))
        assertEquals(listOf("Bearer sk-user-secret-key"), headers["Authorization"])
    }

    @Test
    fun `cf_aig target replaces authorization with cf-aig-authorization`() = runBlocking {
        val session = newSession()
        val cfTarget = gatewayTarget.copy(apiKey = "cf-token-123", authStyle = AuthStyle.CF_AIG)
        runnerWith(HttpStatusCode.OK to chatResponse("ok"))
            .runTurn(session, "hi", cfTarget, "sys", ServerToolService.defaultRegistry())

        val (_, headers) = capturedRequests.single()
        assertEquals(listOf("Bearer cf-token-123"), headers["cf-aig-authorization"])
        assertTrue(headers["Authorization"].isNullOrEmpty())
    }

    @Test
    fun `resolver channel endpoint produces exact upstream path (no double v1)`() = runBlocking {
        // 2026-10-17 真机遛狗实踩：完整 endpoint 直接当 Koog baseUrl 会拼出 /v1/v1/chat/completions
        val session = newSession()
        val channel = ChannelConfig(
            3, "DeepSeek", "direct", "https://api.deepseek.com/v1/chat/completions",
            AuthStyle.BEARER, "tok-abc", emptyMap(), "test-model",
        )
        val target = AgentTargetResolver.fromChannel(channel, "test-model")!!
        runnerWith(HttpStatusCode.OK to chatResponse("ok"))
            .runTurn(session, "hi", target, "sys", ServerToolService.defaultRegistry())

        assertEquals("/v1/chat/completions", capturedRequests.single().first)
    }
}
