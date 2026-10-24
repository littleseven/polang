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
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asToolsByClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun runnerWith(
        vararg responses: Pair<HttpStatusCode, String>,
        actionGate: (String, String) -> String? = { _, _ -> null },
    ): AgentRunner {
        val queue = ArrayDeque(responses.toList())
        val engine = MockEngine { request ->
            capturedRequests += request.url.fullPath to request.headers.entries().map { it.key to it.value }.toMap()
            val (status, body) = queue.removeFirst()
            respond(content = body, status = status, headers = headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        return AgentRunner(
            executorFactory = AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
            dispatcher = ServerToolDispatcher.default(),
            actionGate = actionGate,
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
    fun `render html turn appends html card event between action and observation`() = runBlocking {
        val session = newSession()
        val cards = mutableListOf<Pair<String, String>>()
        val emitted = mutableListOf<AgentEvent>()
        // 与生产接线同形态：sink 落 HtmlCard 事件并经同一 SSE 通道下行（AgentRoutes 写 kind=event 帧）
        val sink: suspend (String, String) -> Unit = { html, summary ->
            cards += html to summary
            val event = AgentEventRepository.append(
                session.id, AgentEventSource.ENVIRONMENT, AgentEventRole.TOOL,
                payload = AgentEventPayload.HtmlCard(html, summary),
            )
            emitted += event
        }
        val registry = ServerToolService.defaultRegistry(onHtmlCard = sink)
        // arguments 在外层 JSON 内是字符串值，内层引号须反斜杠转义（OpenAI wire 形态）
        val args = """{\"html\":\"<html>卡</html>\",\"summary\":\"周报\"}"""
        runnerWith(
            HttpStatusCode.OK to toolCallResponse("call_1", "render_html", args),
            HttpStatusCode.OK to chatResponse("已为你生成周报卡片"),
        ).runTurn(session, "出个周报卡", gatewayTarget, "sys", registry) { emitted += it }

        val events = AgentEventRepository.listBySession(session.id)
        assertEquals(
            listOf(
                AgentEventType.MESSAGE, AgentEventType.ACTION, AgentEventType.HTML_CARD,
                AgentEventType.OBSERVATION, AgentEventType.MESSAGE, AgentEventType.FINISH,
            ),
            events.map { it.type },
        )
        assertEquals(events.map { it.id }, emitted.map { it.id })
        val card = events[2].payload as AgentEventPayload.HtmlCard
        assertEquals("<html>卡</html>", card.html)
        assertEquals("周报", card.summary)
        assertEquals(AgentEventSource.ENVIRONMENT, events[2].source)
        assertEquals(AgentEventRole.TOOL, events[2].role)
        val observation = events[3].payload as AgentEventPayload.Observation
        // LLM 只见 summary 回执，HTML 本体不进上下文（Koog 字符串结果以 JsonPrimitive 带引号，既有约定）
        assertTrue(observation.content.contains("已渲染 HTML 卡片：周报"))
        assertFalse(observation.content.contains("<html>"))
        assertEquals(listOf("<html>卡</html>" to "周报"), cards)
    }

    @Test
    fun `pending render html action replay re-emits html card event`() = runBlocking {
        val session = newSession()
        val orphaned = AgentEventRepository.append(
            session.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action(
                "render_html", """{"html":"<p>重播</p>","summary":"旧卡"}""", reversible = false,
            ),
        )
        val cards = mutableListOf<Pair<String, String>>()
        runnerWith(HttpStatusCode.OK to chatResponse("继续"))
            .runTurn(
                session, "继续", gatewayTarget, "sys", ServerToolService.defaultRegistry(),
                onHtmlCard = { html, summary ->
                    cards += html to summary
                    AgentEventRepository.append(
                        session.id, AgentEventSource.ENVIRONMENT, AgentEventRole.TOOL,
                        payload = AgentEventPayload.HtmlCard(html, summary),
                    )
                },
            )

        // 崩溃恢复重放：卡片重新出现在事件流
        assertEquals(listOf("<p>重播</p>" to "旧卡"), cards)
        val events = AgentEventRepository.listBySession(session.id)
        val card = events.mapNotNull { it.payload as? AgentEventPayload.HtmlCard }.single()
        assertEquals("<p>重播</p>", card.html)
        val obs = events.mapNotNull { it.payload as? AgentEventPayload.Observation }
            .single { o -> o.actionEventId == orphaned.id }
        assertEquals("已渲染 HTML 卡片：旧卡", obs.content)
        assertTrue(AgentEventRepository.pendingActions(session.id).isEmpty())
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

    // ── render_ui 发射点门控（actionGate，2026-10-24）─────────────────────

    /**
     * 非法 render_ui 负载：不落 Action 事件（坏卡永不到端侧），工具体回 error 文本
     * 经 Observation 回灌 LLM，同轮可修正重试。
     */
    @Test
    fun `invalid render_ui payload is gated - no action event, error observation fed back`() = runBlocking {
        val session = newSession()
        val badArgs = buildJsonObject {
            put("surfaceId", "sf-bad00001")
            put(
                "messagesJson",
                "[{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"sf-bad00001\",\"catalogId\":\"https://wrong.example/x\"}}]",
            )
            put("fallbackSummary", "摘要")
        }.toString()
        val registry = ToolRegistry { tools(RenderUiToolService().asToolsByClass()) }

        runnerWith(
            HttpStatusCode.OK to toolCallResponse("call_1", "render_ui", jsonArgsEscape(badArgs)),
            HttpStatusCode.OK to chatResponse("收到错误，已修正"),
            actionGate = RenderUiValidator::gate,
        ).runTurn(session, "发张统计卡", gatewayTarget, "sys", registry)

        val events = AgentEventRepository.listBySession(session.id)
        assertFalse("被拒负载不得落 Action 事件", events.any { event -> event.type == AgentEventType.ACTION })
        val observation = events.single { event -> event.type == AgentEventType.OBSERVATION }
        val content = (observation.payload as AgentEventPayload.Observation).content
        // ctx.toolResult.toString() 对字符串结果带引号包装，不断言 startsWith
        assertTrue("Observation 应携带校验错误回灌 LLM，实际：$content", content.contains("error: render_ui 负载校验未通过"))
        assertTrue(content.contains("catalogId"))
    }

    /** 合法 render_ui 负载：门控放行，Action 事件正常落流下行。 */
    @Test
    fun `valid render_ui payload passes gate and emits action event`() = runBlocking {
        val session = newSession()
        val goodArgs = buildJsonObject {
            put("surfaceId", "sf-ok000001")
            put(
                "messagesJson",
                "[{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"sf-ok000001\",\"catalogId\":\"${RenderUiValidator.CATALOG_ID}\"}}," +
                    "{\"version\":\"v0.9\",\"updateComponents\":{\"surfaceId\":\"sf-ok000001\",\"components\":[{\"id\":\"root\",\"component\":\"Text\",\"text\":\"hi\"}]}}]",
            )
            put("fallbackSummary", "摘要")
        }.toString()
        val registry = ToolRegistry { tools(RenderUiToolService().asToolsByClass()) }

        runnerWith(
            HttpStatusCode.OK to toolCallResponse("call_1", "render_ui", jsonArgsEscape(goodArgs)),
            HttpStatusCode.OK to chatResponse("卡已发"),
            actionGate = RenderUiValidator::gate,
        ).runTurn(session, "发张统计卡", gatewayTarget, "sys", registry)

        val events = AgentEventRepository.listBySession(session.id)
        val action = events.single { event -> event.type == AgentEventType.ACTION }
        assertEquals("render_ui", (action.payload as AgentEventPayload.Action).tool)
        // Observation 与 Action 正常配对闭环
        val observation = events.single { event -> event.type == AgentEventType.OBSERVATION }
        assertEquals(action.id, (observation.payload as AgentEventPayload.Observation).actionEventId)
    }

    /** toolCallResponse 把 args 平铺进 JSON 字符串字段，内嵌引号/反斜杠须预转义。 */
    private fun jsonArgsEscape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
