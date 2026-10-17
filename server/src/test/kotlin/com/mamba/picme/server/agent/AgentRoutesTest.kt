package com.mamba.picme.server.agent

import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.util.TestDb
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
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

    private fun ApplicationTestBuilder.installAgentApp(runner: AgentRunner) {
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(runner, AgentBridgeRegistry()) }
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
}
