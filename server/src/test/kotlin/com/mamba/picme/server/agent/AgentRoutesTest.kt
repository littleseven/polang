package com.mamba.picme.server.agent

import com.mamba.picme.server.auth.APP_CHANNEL_HEADER
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.routes.DebugQuotaBypassKey
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.util.TestDb
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 云端 Agent 路由面端到端（ADR-018：独立会话域 + BYOK 透传零留存）。
 * MockEngine 假 LLM；auth interceptor 复刻 Application.module 最小行为（token → TokenHashKey）。
 */
class AgentRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        ChannelRegistry.setActiveForTesting(null)
    }

    private fun chatResponse(text: String) = """
        {"id":"chatcmpl-1","object":"chat.completion","created":1730000000,"model":"m",
         "choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
    """.trimIndent()

    private fun mockRunner(vararg responses: Pair<HttpStatusCode, String>): AgentRunner {
        val queue = ArrayDeque(responses.toList())
        val engine = MockEngine {
            val (status, body) = queue.removeFirst()
            respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        return AgentRunner(
            executorFactory = AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
            dispatcher = ServerToolDispatcher.default(),
        )
    }

    private fun ApplicationTestBuilder.installAgentApp(
        runner: AgentRunner,
        roundGate: AgentRoundGate = AgentRoundGate(),
        debugBypass: Boolean = false,
    ) {
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
                // 复刻 Application.module：DEBUG_QUOTA_BYPASS 开 + debug 渠道头 → 豁免标记
                if (debugBypass && call.request.headers[APP_CHANNEL_HEADER] == "debug") {
                    call.attributes.put(DebugQuotaBypassKey, true)
                }
            }
            routing { agentRoute(runner, AgentBridgeRegistry(), roundGate = roundGate) }
        }
    }

    private fun activateGateway() {
        ChannelRegistry.setActiveForTesting(
            ChannelConfig(
                1, "TestChan", "gateway", "https://gw.local/v1/chat/completions",
                AuthStyle.BEARER, "gw-token",
                mapOf("deepseek-chat" to "deepseek-chat"), "deepseek-chat",
            ),
        )
    }

    @Test
    fun `create session requires auth`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        installAgentApp(mockRunner())
        val resp = client.post("/agent/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `create gateway session and list returns it`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("gw@x.com", 100) }
        installAgentApp(mockRunner())

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        val list = client.get("/agent/sessions") { header(APP_TOKEN_HEADER, account.token) }
        val sessions = json.parseToJsonElement(list.bodyAsText()).jsonObject["sessions"]!!.jsonArray
        assertEquals(1, sessions.size)
        assertEquals(sessionId, sessions[0].jsonObject["session_id"]!!.jsonPrimitive.content)
        assertEquals("running", sessions[0].jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create byok session without base url returns 400`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("byok@x.com", 100) }
        installAgentApp(mockRunner())
        val resp = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"byok"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `message streams event frames and done, events persisted`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("sse@x.com", 100) }
        activateGateway()
        installAgentApp(mockRunner(HttpStatusCode.OK to chatResponse("你好")))

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"你好"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(ContentType.Text.EventStream.withoutParameters(), resp.contentType()?.withoutParameters())

        val frames = resp.bodyAsText().split("\n\n").filter { it.isNotBlank() }
        // 3 个事件帧（user msg / assistant msg / finish）+ 1 个 done 帧
        assertEquals(4, frames.size)
        assertTrue(frames.take(3).all { it.startsWith("data: {\"kind\":\"event\"") })
        assertTrue(frames[3].contains("\"kind\":\"done\""))

        val events = runBlocking { AgentEventRepository.listBySession(sessionId) }
        assertEquals(
            listOf(AgentEventType.MESSAGE, AgentEventType.MESSAGE, AgentEventType.FINISH),
            events.map { it.type },
        )
        // 网关通道消耗配额
        val used = transaction(Db.instance) {
            Accounts.selectAll().single()[Accounts.llmCallsUsed]
        }
        assertEquals(1, used)
    }

    @Test
    fun `events endpoint paged and ownership enforced`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val mine = runBlocking { AccountService.createOrRefresh("me@x.com", 100) }
        val other = runBlocking { AccountService.createOrRefresh("other@x.com", 100) }
        activateGateway()
        installAgentApp(mockRunner(HttpStatusCode.OK to chatResponse("hi")))

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, mine.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content
        client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, mine.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }

        val events = client.get("/agent/sessions/$sessionId/events") { header(APP_TOKEN_HEADER, mine.token) }
        assertEquals(HttpStatusCode.OK, events.status)
        assertEquals(3, json.parseToJsonElement(events.bodyAsText()).jsonObject["events"]!!.jsonArray.size)

        // after 分页
        val firstId = json.parseToJsonElement(events.bodyAsText()).jsonObject["events"]!!
            .jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        val paged = client.get("/agent/sessions/$sessionId/events?after=$firstId") { header(APP_TOKEN_HEADER, mine.token) }
        assertEquals(2, json.parseToJsonElement(paged.bodyAsText()).jsonObject["events"]!!.jsonArray.size)

        // 他人会话不可见
        val stolen = client.get("/agent/sessions/$sessionId/events") { header(APP_TOKEN_HEADER, other.token) }
        assertEquals(HttpStatusCode.NotFound, stolen.status)
    }

    @Test
    fun `message to finished session returns 409`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("fin@x.com", 100) }
        installAgentApp(mockRunner())
        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        val finish = client.post("/agent/sessions/$sessionId/finish") { header(APP_TOKEN_HEADER, account.token) }
        assertEquals(HttpStatusCode.OK, finish.status)

        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `gateway message without active channel returns 503`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("nochan@x.com", 100) }
        installAgentApp(mockRunner())
        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content
        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
    }

    @Test
    fun `gateway message over quota returns 403`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("quota@x.com", 0) }
        activateGateway()
        installAgentApp(mockRunner())
        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content
        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(resp.bodyAsText().contains("quota_exceeded"))
    }

    @Test
    fun `gateway message from debug channel skips quota when bypass enabled`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("dbg@x.com", 0) } // 额度已尽
        activateGateway()
        val engine = MockEngine {
            respond(chatResponse("ok"), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val runner = AgentRunner(
            executorFactory = AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
            dispatcher = ServerToolDispatcher.default(),
        )
        installAgentApp(runner, debugBypass = true)
        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content
        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            header(APP_CHANNEL_HEADER, "debug")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"done\""))
        // 豁免只跳扣减：额度计数保持 0
        val used = transaction(Db.instance) { Accounts.selectAll().single()[Accounts.llmCallsUsed] }
        assertEquals(0, used)
    }

    @Test
    fun `debug channel header without bypass switch still returns 403`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("dbgoff@x.com", 0) }
        activateGateway()
        installAgentApp(mockRunner(), debugBypass = false)
        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content
        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            header(APP_CHANNEL_HEADER, "debug") // 头可伪造：env 关（未打标）时照常 403
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(resp.bodyAsText().contains("quota_exceeded"))
    }

    @Test
    fun `byok message uses user key upstream and key is never persisted`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("guard@x.com", 100) }
        val capturedAuth = mutableListOf<String?>()
        val engine = MockEngine { request ->
            capturedAuth += request.headers["Authorization"]
            respond(chatResponse("ok"), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val runner = AgentRunner(
            executorFactory = AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
            dispatcher = ServerToolDispatcher.default(),
        )
        installAgentApp(runner)

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"byok","byok_base_url":"https://user-llm.local/v1/"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            header("X-User-Api-Key", "sk-user-secret-AAA")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("Bearer sk-user-secret-AAA", capturedAuth.single())

        // ── 零留存守卫（ADR-018 决策 4 不变式）──
        // key 不得出现在任何持久化表
        val leaked = transaction(Db.instance) {
            val inEvents = AgentEvents.selectAll().any { it[AgentEvents.payloadJson].contains("sk-user-secret-AAA") }
            val inSessions = AgentSessions.selectAll().any {
                it[AgentSessions.model].contains("sk-user-secret-AAA") ||
                    it[AgentSessions.byokBaseUrl].contains("sk-user-secret-AAA")
            }
            inEvents || inSessions
        }
        assertFalse("BYOK key leaked into DB", leaked)
        // BYOK 不消耗网关配额、不写计费日志
        val used = transaction(Db.instance) { Accounts.selectAll().single()[Accounts.llmCallsUsed] }
        assertEquals(0, used)
        assertEquals(0, transaction(Db.instance) { LlmCallLogs.selectAll().count() })
    }

    @Test
    fun `byok message without key returns 400`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("nokey@x.com", 100) }
        installAgentApp(mockRunner())
        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"byok","byok_base_url":"https://user-llm.local/v1/"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content
        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("byok_key_required"))
    }

    @Test
    fun `round gate busy returns 409 and quota is not charged`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("busy@x.com", 100) }
        activateGateway()
        // 测试内手动占位，模拟该用户已有在跑轮（避免真并发，client 并发语义不稳）
        val gate = AgentRoundGate()
        assertTrue(gate.tryAcquire(AccountService.sha256(account.token)))
        installAgentApp(mockRunner(), roundGate = gate)

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertTrue(resp.bodyAsText().contains("round_in_flight"))
        // busy 拒绝在配额扣减之前，不消耗配额
        val used = transaction(Db.instance) { Accounts.selectAll().single()[Accounts.llmCallsUsed] }
        assertEquals(0, used)
    }

    @Test
    fun `round gate busy 409 carries retry_after hint`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("busyhint@x.com", 100) }
        activateGateway()
        val gate = AgentRoundGate()
        assertTrue(gate.tryAcquire(AccountService.sha256(account.token)))
        installAgentApp(mockRunner(), roundGate = gate)

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("round_in_flight", body["error"]!!.jsonPrimitive.content)
        val retryAfter = body["retry_after"]!!.jsonPrimitive.content.toLong()
        assertTrue("retry_after should be positive, got $retryAfter", retryAfter > 0)
    }

    @Test
    fun `round gate released after round, next message succeeds`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("relay@x.com", 100) }
        activateGateway()
        installAgentApp(
            mockRunner(
                HttpStatusCode.OK to chatResponse("第一轮"),
                HttpStatusCode.OK to chatResponse("第二轮"),
            ),
        )

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        repeat(2) { round ->
            val resp = client.post("/agent/sessions/$sessionId/messages") {
                header(APP_TOKEN_HEADER, account.token)
                contentType(ContentType.Application.Json)
                setBody("""{"content":"第${round + 1}轮"}""")
            }
            assertEquals("round ${round + 1} should pass the gate", HttpStatusCode.OK, resp.status)
            assertTrue(resp.bodyAsText().contains("\"kind\":\"done\""))
        }
        val used = transaction(Db.instance) { Accounts.selectAll().single()[Accounts.llmCallsUsed] }
        assertEquals(2, used)
    }

    @Test
    fun `client disconnect releases round gate and does not mark session error`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("disc@x.com", 100) }
        activateGateway()

        // MockEngine 挂起：模拟 LLM 长时间不返回（客户端等不及断开）
        val latch = CountDownLatch(1)
        val engine = MockEngine { _ ->
            latch.await(10, TimeUnit.SECONDS)
            respond("{}", HttpStatusCode.OK)
        }
        val runner = AgentRunner(
            executorFactory = AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
            dispatcher = ServerToolDispatcher.default(),
        )
        val gate = AgentRoundGate()
        installAgentApp(runner, roundGate = gate)

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

        // 独立线程发 SSE 请求 → 等 LLM 挂起 → 客户端超时断开
        val disconnectLatch = CountDownLatch(1)
        val thread = Thread {
            runBlocking {
                try {
                    client.post("/agent/sessions/$sessionId/messages") {
                        header(APP_TOKEN_HEADER, account.token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"hi"}""")
                        timeout { requestTimeoutMillis = 300 }
                    }
                } catch (_: Throwable) {
                    // 客户端超时断开，预期行为
                }
                disconnectLatch.countDown()
            }
        }
        thread.start()
        // 等 MockEngine 的 latch.await 被命中（确保服务端进入 LLM 调用）
        Thread.sleep(200)
        disconnectLatch.await(5, TimeUnit.SECONDS)

        // 断开后：Koog loop 阻塞在 MockEngine 的 latch.await 上，
        // 客户端超时断开 → Ktor 取消承载协程 → withTimeoutOrNull 协作取消 →
        // finally 释放租约。轮询等待协程取消传递（需一两个 dispatch 周期）。
        val deadline = System.currentTimeMillis() + 5_000
        while (gate.activeCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("round gate should be released after client disconnect", 0, gate.activeCount())

        // 会话不应被置为 ERROR（断线不是 LLM/服务端错误）
        val session = AgentSessionRepository.find(sessionId)
        assertTrue("session should not be ERROR after disconnect", session?.status != AgentSessionStatus.ERROR)

        // 配额不应回退（LLM 调用已发起）
        val used = transaction(Db.instance) { Accounts.selectAll().single()[Accounts.llmCallsUsed] }
        assertEquals("quota should not be reverted on disconnect", 1, used)

        // 下一轮应能通过闸
        latch.countDown() // 释放 MockEngine 让后续请求能完成
        val resp2 = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"second"}""")
        }
        assertEquals(HttpStatusCode.OK, resp2.status)
    }

    @Test
    fun `llm failure emits error frame, marks session error and reverts quota`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("fail@x.com", 100) }
        activateGateway()
        installAgentApp(mockRunner(HttpStatusCode.InternalServerError to """{"error":"upstream down"}"""))

        val created = client.post("/agent/sessions") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"deepseek-chat","channel":"gateway"}""")
        }
        val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content
        val resp = client.post("/agent/sessions/$sessionId/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status) // SSE 通道 200，错误经帧传递
        assertTrue(resp.bodyAsText().contains("\"kind\":\"error\""))

        val session = runBlocking { AgentSessionRepository.find(sessionId)!! }
        assertEquals(AgentSessionStatus.ERROR, session.status)
        // 配额回退
        val used = transaction(Db.instance) { Accounts.selectAll().single()[Accounts.llmCallsUsed] }
        assertEquals(0, used)
        // Finish(error) 已落流
        val last = runBlocking { AgentEventRepository.listBySession(sessionId).last() }
        assertTrue((last.payload as AgentEventPayload.Finish).reason.startsWith("error"))
    }

    // ── GenUI 线格式分流：openui 头 = 回合原生（turn-native T8）──────────────

    /** 捕获请求体 JSON 的注册工具名集与 system prompt（messages[0]）。 */
    private fun toolNamesAndSystemPrompt(requestBody: String): Pair<Set<String>, String> {
        val root = json.parseToJsonElement(requestBody).jsonObject
        val toolNames = root["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }.toSet()
        val systemMessage = root["messages"]!!.jsonArray.first {
            it.jsonObject["role"]!!.jsonPrimitive.content == "system"
        }
        return toolNames to systemMessage.jsonObject["content"]!!.jsonPrimitive.content
    }

    /**
     * openui 头语义升级（回合原生）：不注册任何 GenUI 发卡工具（render_ui_openui 退役）
     * 且 render_html 从服务端工具中过滤；system prompt 换用 turn-native 契约
     * （「你的整个回复就是一颗 OpenUI Lang 1.0 程序」）。
     * 缺头（A2UI 回退闸）：照旧注册 render_ui、prompt 为 DEFAULT_SYSTEM_PROMPT（零回归）。
     * 经 RawSseStubServer 捕获 LLM 请求体钉住（BYOK 指向本地假 LLM，真实 SSE 链路）。
     */
    @Test
    fun `openui header registers no genui tools and uses turn-native prompt`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents, LlmCallLogs)
        val account = runBlocking { AccountService.createOrRefresh("openui@x.com", 100) }
        val capturedBodies = mutableListOf<String>()
        val sseServer = RawSseStubServer { body, n ->
            capturedBodies += body
            // 第 1 轮（openui 头）回合法程序避免程序守卫纠正回环；第 2 轮（缺头）回纯文本
            val text = if (n == 1) {
                "// summary: 打招呼\nroot = Markdown(\"你好\")"
            } else {
                "好的，已收到"
            }
            MockLlmSse.streamingText(text) to "text/event-stream"
        }
        try {
            val runner = AgentRunner(
                executorFactory = AgentExecutorFactory(),
                dispatcher = ServerToolDispatcher.default(),
            )
            installAgentApp(runner)

            val created = client.post("/agent/sessions") {
                header(APP_TOKEN_HEADER, account.token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"test-model","channel":"byok",""" +
                        """"byok_base_url":"http://127.0.0.1:${sseServer.port}"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, created.status)
            val sessionId = json.parseToJsonElement(created.bodyAsText()).jsonObject["session_id"]!!.jsonPrimitive.content

            // ── 断言一：X-GenUI-Format: openui → 回合原生（无发卡工具 + turn-native prompt）
            val openuiResp = client.post("/agent/sessions/$sessionId/messages") {
                header(APP_TOKEN_HEADER, account.token)
                header("X-User-Api-Key", "sk-stub")
                header(GENUI_FORMAT_HEADER, GENUI_FORMAT_OPENUI)
                contentType(ContentType.Application.Json)
                setBody("""{"content":"你好"}""")
            }
            assertEquals(HttpStatusCode.OK, openuiResp.status)
            assertTrue(openuiResp.bodyAsText().contains("\"kind\":\"done\""))

            assertEquals("合法程序零纠正回环，单次 LLM 调用", 1, capturedBodies.size)
            val (openuiTools, openuiPrompt) = toolNamesAndSystemPrompt(capturedBodies.single())
            assertFalse("turn-native 不得注册 render_ui_openui", "render_ui_openui" in openuiTools)
            assertFalse("turn-native 不得注册 render_ui", "render_ui" in openuiTools)
            assertFalse("turn-native render_html 退役（Html 组件入树）", "render_html" in openuiTools)
            assertTrue("其余服务端工具保留", "server_time" in openuiTools)
            assertTrue(
                "system prompt 应为 turn-native 契约，实际：${openuiPrompt.take(80)}",
                openuiPrompt.contains("你的整个回复就是一颗 OpenUI Lang 1.0 程序"),
            )

            // ── 断言二：缺头（A2UI 回退闸）零回归
            val a2uiResp = client.post("/agent/sessions/$sessionId/messages") {
                header(APP_TOKEN_HEADER, account.token)
                header("X-User-Api-Key", "sk-stub")
                contentType(ContentType.Application.Json)
                setBody("""{"content":"在吗"}""")
            }
            assertEquals(HttpStatusCode.OK, a2uiResp.status)
            assertTrue(a2uiResp.bodyAsText().contains("\"kind\":\"done\""))

            assertEquals(2, capturedBodies.size)
            val (a2uiTools, a2uiPrompt) = toolNamesAndSystemPrompt(capturedBodies[1])
            assertTrue("A2UI 线照旧注册 render_ui", "render_ui" in a2uiTools)
            assertTrue("A2UI 线 render_html 保留", "render_html" in a2uiTools)
            assertFalse("A2UI 线不得注册 render_ui_openui", "render_ui_openui" in a2uiTools)
            assertTrue(
                "A2UI 线 prompt 应为默认契约（零回归），实际：${a2uiPrompt.take(80)}",
                a2uiPrompt.startsWith("你是 PoLang 云端 Agent。用中文简洁回答"),
            )
            assertFalse(
                "A2UI 线不得出现 turn-native prompt",
                a2uiPrompt.contains("你的整个回复就是一颗 OpenUI Lang 1.0 程序"),
            )
        } finally {
            sseServer.close()
        }
    }
}
