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
 *
 * GenUI 唯一化（2026-11-06）：恒 turn-native——终结消息落流前无条件经
 * [OpenUiTurnNative.ensureValidProgram]（非合法程序兜底 Markdown 包装），
 * content TextDelta 无条件透传为 program 前缀预览，ProgramGuard 无条件启用
 * （非法终结程序触发纠正回环）。故普通用例的终结文本一律给合法程序，
 * 避免误触纠正回环多打一次 LLM。
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

    /** 合法 OpenUI 程序作为终结文本的响应（JSON 内嵌引号转义），防误触程序守卫纠正回环。 */
    private fun programResponse(program: String) =
        chatResponse(program.replace("\\", "\\\\").replace("\"", "\\\""))

    private fun toolCallResponse(callId: String, tool: String, args: String) = """
        {"id":"chatcmpl-2","object":"chat.completion","created":1730000000,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$callId","type":"function","function":{"name":"$tool","arguments":"$args"}}]},
           "finish_reason":"tool_calls"}],
         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
    """.trimIndent()

    private fun runnerWith(
        vararg responses: Pair<HttpStatusCode, String>,
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
        val finish = runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"你好，我是云端 Agent\")"))
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
        // 合法程序经 ensureValidProgram（normalize 恒等于此类形态）原样落流
        assertEquals(AgentEventPayload.Message("root = Markdown(\"你好，我是云端 Agent\")"), events[1].payload)
        assertEquals(AgentEventPayload.Finish("done"), events[2].payload)
        // SSE 发射顺序 = 落流顺序
        assertEquals(events.map { it.id }, emitted.map { it.id })
    }

    @Test
    fun `final plain text is wrapped into markdown program before persist`() = runBlocking {
        val session = newSession()
        // 纯文本终结（非合法程序）：ProgramGuard 纠正一次后 MockEngine 队列给第二条纯文本，
        // 上限用尽放行 → ensureValidProgram 兜底 Markdown 包装（「端上永远收到合法树」）
        runnerWith(
            HttpStatusCode.OK to chatResponse("你好，我是云端 Agent"),
            HttpStatusCode.OK to chatResponse("你好，我是云端 Agent"),
        ).runTurn(session, "你好", gatewayTarget, "sys", ServerToolService.defaultRegistry())

        val events = AgentEventRepository.listBySession(session.id)
        val finalMessage = events
            .filter { event -> event.type == AgentEventType.MESSAGE && event.source == AgentEventSource.AGENT }
            .map { event -> event.payload as AgentEventPayload.Message }
            .last()
        assertEquals(
            "// summary: 文本回复\nroot = Markdown(\"你好，我是云端 Agent\")",
            finalMessage.content,
        )
    }

    @Test
    fun `turn usage accumulates llm tokens and fills elapsed on success`() = runBlocking {
        val session = newSession()
        val usage = TurnUsage()
        // 普通回合 = 单次 LLM 调用；假响应 usage 为 10/5
        // （MockEngine 不支持 SSE → 策略层回退一次性请求，本条钉的是非流式回退路径）
        runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"你好\")"))
            .runTurn(session, "你好", gatewayTarget, "sys", ServerToolService.defaultRegistry(), turnUsage = usage)
        assertEquals(10L, usage.promptTokens)
        assertEquals(5L, usage.completionTokens)
        assertTrue("elapsed 应已回填非负值", usage.elapsedMs >= 0)
    }

    /**
     * 流式路径的回合用量来自 End 帧 metaInfo——Koog 1.3.0 流式只触发 onLLMStreaming* 事件、
     * 不触发 onLLMCallCompleted（仅非流式 execute 路径发射），usage 只能在
     * onLLMStreamingFrameReceived 的 End 帧分支累加。回归 2026-11-03 云端回合性能行缺失
     * 事故（TurnUsage 恒 0 → done 帧 usage{0,0} → 端侧全 0 护栏丢弃）。
     */
    @Test
    fun `streaming turn accumulates usage from end frame metainfo`() = runBlocking {
        val session = newSession()
        val usage = TurnUsage()
        val sseServer = RawSseStubServer { _, _ ->
            MockLlmSse.streamingText("root = Markdown(\"你好\")") to "text/event-stream"
        }
        try {
            val target = AgentLlmTarget(
                baseUrl = "http://127.0.0.1:${sseServer.port}",
                apiKey = "k",
                model = "test-model",
                authStyle = AuthStyle.BEARER,
            )
            AgentRunner(
                executorFactory = AgentExecutorFactory(),
                dispatcher = ServerToolDispatcher.default(),
            ).runTurn(session, "hi", target, "sys", ServerToolService.defaultRegistry(), turnUsage = usage)

            assertEquals("流式 usage 应从 End 帧 metaInfo 累加", 10L, usage.promptTokens)
            assertEquals(5L, usage.completionTokens)
            assertTrue("elapsed 应已回填非负值", usage.elapsedMs >= 0)
            assertEquals(1, sseServer.callCount.get())
        } finally {
            sseServer.close()
        }
    }

    @Test
    fun `tool turn closes action with observation and leaves no pending`() = runBlocking {
        val session = newSession()
        runnerWith(
            HttpStatusCode.OK to toolCallResponse("call_1", "server_time", "{}"),
            HttpStatusCode.OK to programResponse("root = Markdown(\"现在是中午\")"),
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
        assertEquals(AgentEventPayload.Message("root = Markdown(\"现在是中午\")"), events[3].payload)
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

        runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"好的\")"))
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
        runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"好的\")"))
            .runTurn(session, "继续", gatewayTarget, "sys", ServerToolService.defaultRegistry())
        val obs = AgentEventRepository.listBySession(session.id)
            .mapNotNull { it.payload as? AgentEventPayload.Observation }.single()
        assertTrue(obs.content.startsWith("error"))
        assertTrue(AgentEventRepository.pendingActions(session.id).isEmpty())
    }

    /**
     * 退役工具的 pending 重放（GenUI 唯一化）：历史遗留 render_html pending 不再派发
     * （dispatcher 无 handler），以 error Observation 闭环，pending 不变式不受退役影响。
     */
    @Test
    fun `pending replay of retired render_html tool closes with error observation`() = runBlocking {
        val session = newSession()
        AgentEventRepository.append(
            session.id, AgentEventSource.AGENT, AgentEventRole.ASSISTANT,
            payload = AgentEventPayload.Action(
                "render_html", """{"html":"<p>旧卡</p>","summary":"旧卡"}""", reversible = false,
            ),
        )
        runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"继续\")"))
            .runTurn(session, "继续", gatewayTarget, "sys", ServerToolService.defaultRegistry())

        val obs = AgentEventRepository.listBySession(session.id)
            .mapNotNull { it.payload as? AgentEventPayload.Observation }.single()
        assertTrue(
            "退役工具应以 error 闭环，实际：${obs.content}",
            obs.content.contains("unknown server tool: render_html"),
        )
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
        runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"ok\")"))
            .runTurn(session, "hi", byok, "sys", ServerToolService.defaultRegistry())

        val (path, headers) = capturedRequests.single()
        assertTrue(path.endsWith("chat/completions"))
        assertEquals(listOf("Bearer sk-user-secret-key"), headers["Authorization"])
    }

    @Test
    fun `cf_aig target replaces authorization with cf-aig-authorization`() = runBlocking {
        val session = newSession()
        val cfTarget = gatewayTarget.copy(apiKey = "cf-token-123", authStyle = AuthStyle.CF_AIG)
        runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"ok\")"))
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
        runnerWith(HttpStatusCode.OK to programResponse("root = Markdown(\"ok\")"))
            .runTurn(session, "hi", target, "sys", ServerToolService.defaultRegistry())

        assertEquals("/v1/chat/completions", capturedRequests.single().first)
    }

    // ── content 流式预览（turn-native；真 CIO 回环：MockEngine 不支持 SSE）────────

    /**
     * GenUI 唯一化后 content TextDelta 无条件透传：经 OpenUiContentStreamTracker
     * 节流为 program 前缀旁路下行（不落事件流）。content 全长 49（两等分分片各 24
     * <minEmitChars=48）——仅尾帧触发首发，单帧即全文，对节流间隔零依赖
     * （多帧生长由 tracker 单测覆盖）。
     */
    @Test
    fun `content deltas are forwarded as program prefix unconditionally`() = runBlocking {
        val session = newSession()
        val content = "root = Card([Text(\"${"统计".repeat(13)}\")])"
        assertTrue("测试前置：半片须低于节流首发阈值", content.length / 2 < 48)
        assertTrue("测试前置：全文须达到节流首发阈值", content.length >= 48)
        val sseServer = RawSseStubServer { _, _ ->
            MockLlmSse.streamingText(content) to "text/event-stream"
        }
        try {
            val target = AgentLlmTarget(
                baseUrl = "http://127.0.0.1:${sseServer.port}",
                apiKey = "k",
                model = "test-model",
                authStyle = AuthStyle.BEARER,
            )
            val deltas = mutableListOf<String>()
            val finish = AgentRunner(
                executorFactory = AgentExecutorFactory(),
                dispatcher = ServerToolDispatcher.default(),
            ).runTurn(
                session, "出卡", target, "sys", ServerToolService.defaultRegistry(),
                onOpenUiProgramDelta = { deltas += it },
            )

            assertEquals(AgentEventPayload.Finish("done"), finish.payload)
            assertTrue("content delta 未透传", deltas.isNotEmpty())
            deltas.zipWithNext().forEach { (prev, next) ->
                assertTrue("前缀须单调生长", next.length > prev.length && next.startsWith(prev))
            }
            assertEquals("尾帧须等于完整 content", content, deltas.last())
            // 合法程序经 ensureValidProgram 原样落流
            val events = AgentEventRepository.listBySession(session.id)
            val finalMessage = events
                .filter { event -> event.type == AgentEventType.MESSAGE && event.source == AgentEventSource.AGENT }
                .map { event -> event.payload as AgentEventPayload.Message }
                .last()
            assertEquals(content, finalMessage.content)
            assertEquals(1, sseServer.callCount.get()) // 纯 content 回合：单次 LLM 调用
        } finally {
            sseServer.close()
        }
    }

    // ── 终结程序纠正回环（ProgramGuard，GenUI 唯一化后无条件启用）─────────────────

    /**
     * 终结程序非法 → 策略层带错误清单回环纠正（不落首轮坏答复），第二轮输出合法程序后
     * 正常 Finish。纠正 user 消息只存在于 Koog prompt 内，不落事件流。
     */
    @Test
    fun `invalid final program triggers correction round then finish`() = runBlocking {
        val session = newSession()
        val bodies = mutableListOf<String>()
        val sseServer = RawSseStubServer { body, n ->
            bodies += body
            val text = if (n == 1) "这不是程序" else "root = Card([Text(\"修正后\")])"
            MockLlmSse.streamingText(text) to "text/event-stream"
        }
        try {
            val target = AgentLlmTarget(
                baseUrl = "http://127.0.0.1:${sseServer.port}",
                apiKey = "k",
                model = "test-model",
                authStyle = AuthStyle.BEARER,
            )
            val events = mutableListOf<AgentEvent>()
            val finish = AgentRunner(
                executorFactory = AgentExecutorFactory(),
                dispatcher = ServerToolDispatcher.default(),
            ).runTurn(
                session, "出卡", target, "sys", ServerToolService.defaultRegistry(),
                onEvent = { events += it },
            )

            assertEquals(AgentEventPayload.Finish("done"), finish.payload)
            assertEquals("非法终结程序应触发一次纠正回环（共 2 次 LLM 调用）", 2, sseServer.callCount.get())
            assertTrue(
                "第二轮 prompt 应携带程序校验错误清单，实际请求数=${bodies.size}",
                bodies.getOrNull(1)?.contains("你上次的输出未通过校验") == true,
            )
            // 纠正消息落 prompt 不落事件流：首轮坏答复与纠正指令都不落流
            val messages = events
                .filter { it.source == AgentEventSource.AGENT }
                .map { it.payload }.filterIsInstance<AgentEventPayload.Message>()
            assertEquals("root = Card([Text(\"修正后\")])", messages.last().content)
            assertEquals(1, messages.count { it.content != "这不是程序" })
        } finally {
            sseServer.close()
        }
    }

    /**
     * 纠正上限（MAX_CORRECTIONS=1）用尽后仍非法：放行落流（防死循环），
     * 由 ensureValidProgram 兜底 Markdown 包装——「端上永远收到合法树」双保险。
     */
    @Test
    fun `persistently invalid program falls back to markdown wrap`() = runBlocking {
        val session = newSession()
        val sseServer = RawSseStubServer { _, n ->
            MockLlmSse.streamingText(if (n == 1) "坏程序" else "还是坏程序") to "text/event-stream"
        }
        try {
            val target = AgentLlmTarget(
                baseUrl = "http://127.0.0.1:${sseServer.port}",
                apiKey = "k",
                model = "test-model",
                authStyle = AuthStyle.BEARER,
            )
            val events = mutableListOf<AgentEvent>()
            val finish = AgentRunner(
                executorFactory = AgentExecutorFactory(),
                dispatcher = ServerToolDispatcher.default(),
            ).runTurn(
                session, "出卡", target, "sys", ServerToolService.defaultRegistry(),
                onEvent = { events += it },
            )

            assertEquals(AgentEventPayload.Finish("done"), finish.payload)
            assertEquals("纠正一次后放行（共 2 次 LLM 调用）", 2, sseServer.callCount.get())
            val final = events.map { it.payload }.filterIsInstance<AgentEventPayload.Message>().last()
            assertTrue("兜底包装缺失: ${final.content.take(80)}", final.content.startsWith("// summary:"))
            assertTrue(final.content.contains("root = Markdown("))
        } finally {
            sseServer.close()
        }
    }

    /**
     * 合法程序含「卡片已下发」话术不受干扰：程序校验通过即直接 Finish，单次 LLM 调用
     * 无纠正回环（GenUI 唯一化后假发卡守卫已退役——该线无发卡工具，
     * 发卡话术只是程序文本的一部分，不再单列纠正）。
     */
    @Test
    fun `valid program with card claim rhetoric finishes without correction round`() = runBlocking {
        val session = newSession()
        val program = "// summary: 卡片\nroot = Card([Text(\"卡片已下发 ✅\")])"
        val sseServer = RawSseStubServer { _, _ ->
            MockLlmSse.streamingText(program) to "text/event-stream"
        }
        try {
            val target = AgentLlmTarget(
                baseUrl = "http://127.0.0.1:${sseServer.port}",
                apiKey = "k",
                model = "test-model",
                authStyle = AuthStyle.BEARER,
            )
            val events = mutableListOf<AgentEvent>()
            val finish = AgentRunner(
                executorFactory = AgentExecutorFactory(),
                dispatcher = ServerToolDispatcher.default(),
            ).runTurn(
                session, "出卡", target, "sys", ServerToolService.defaultRegistry(),
                onEvent = { events += it },
            )

            assertEquals(AgentEventPayload.Finish("done"), finish.payload)
            assertEquals("合法程序不得触发纠正回环", 1, sseServer.callCount.get())
            val final = events.map { it.payload }.filterIsInstance<AgentEventPayload.Message>().last()
            assertTrue("终结程序应原样落流，实际：${final.content.take(80)}", final.content.contains("卡片已下发"))
        } finally {
            sseServer.close()
        }
    }
}
