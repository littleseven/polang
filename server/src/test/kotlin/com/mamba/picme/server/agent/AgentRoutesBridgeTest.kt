package com.mamba.picme.server.agent

import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AgentRoutesBridgeTest {

    @After
    fun tearDown() {
        ChannelRegistry.setActiveForTesting(null)
    }

    /** MockEngine 两轮：第一轮 tool_calls(search_media)，第二轮最终文本。 */
    private fun toolCallThenTextEngine(): MockEngine {
        val callCount = AtomicInteger(0)
        return MockEngine {
            val n = callCount.incrementAndGet()
            val body = if (n == 1) {
                """{"id":"c1","object":"chat.completion","created":0,"model":"glm-5.2","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"search_media","arguments":"{\"query\":\"猫\",\"person\":\"\",\"fromMs\":\"\",\"toMs\":\"\",\"hasFace\":\"\",\"display\":\"false\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
            } else {
                """{"id":"c2","object":"chat.completion","created":0,"model":"glm-5.2","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"找到 3 张猫的照片。"}}],"usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
    }

    private fun activateChannel() {
        ChannelRegistry.setActiveForTesting(
            ChannelConfig(
                1, "TestChan", "direct", "http://up.example/chat",
                AuthStyle.BEARER, "tok-abc",
                emptyMap(), "glm-5.2",
            ),
        )
    }

    @Test
    fun `tool-results with unknown requestId returns 409`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = runBlocking { AccountService.createOrRefresh("b409@x.com", 100) }
        val session = AgentSessionRepository.create(
            AccountService.sha256(account.token), AgentChannelKind.GATEWAY, "", "",
        )
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), AgentBridgeRegistry()) }
        }
        val resp = client.post("/agent/sessions/${session.id}/tool-results") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"request_id":"tr_ghost","ok":true,"result":"x"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertTrue(resp.bodyAsText().contains("tool_request_not_pending"))
    }

    @Test
    fun `tool-results ok=false normalizes to single error prefix`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = runBlocking { AccountService.createOrRefresh("errpfx@x.com", 100) }
        val session = AgentSessionRepository.create(
            AccountService.sha256(account.token), AgentChannelKind.GATEWAY, "", "",
        )
        val bridge = AgentBridgeRegistry()
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), bridge) }
        }
        // 端侧上行 result 已带 "error: " 前缀（DeviceToolExecutor 契约），云端不得二次包裹
        val deferred = bridge.register(session.id, "tr_err")
        val resp = client.post("/agent/sessions/${session.id}/tool-results") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"request_id":"tr_err","ok":false,"result":"error: unknown_tool:x"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("error: unknown_tool:x", deferred.await())
    }

    @Test
    fun `tool-results on other owner's session returns 404`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val owner = runBlocking { AccountService.createOrRefresh("owner@x.com", 100) }
        val stranger = runBlocking { AccountService.createOrRefresh("stranger@x.com", 100) }
        val session = AgentSessionRepository.create(
            AccountService.sha256(owner.token), AgentChannelKind.GATEWAY, "", "",
        )
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), AgentBridgeRegistry()) }
        }
        val resp = client.post("/agent/sessions/${session.id}/tool-results") {
            header(APP_TOKEN_HEADER, stranger.token)
            contentType(ContentType.Application.Json)
            setBody("""{"request_id":"tr_x","ok":true,"result":"x"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `AC-M2-S1 tool bridge round trip over SSE`() = runBlocking {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = AccountService.createOrRefresh("bridge@x.com", 100)
        activateChannel()
        val bridge = AgentBridgeRegistry()
        // Ktor 3.0.3 testApplication 引擎对 HTTP 响应全量缓冲（TestHttpClientEngine 只回
        // byteContent），客户端在服务端处理器结束前收不到任何字节——流式中途取 request_id
        // 的闭环在 testApplication 下结构性死锁。本用例改用真实 CIO server（随机端口）+
        // CIO client；SSE readAvailable 读取循环与 M1/设计稿口径一致。
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), bridge) }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpClient(CIO).use { http ->
                val sid = createSession(account.token)
                val frames = Collections.synchronizedList(mutableListOf<String>())
                val sseJob = launch(Dispatchers.IO) {
                    http.preparePost("http://127.0.0.1:$port/agent/sessions/$sid/messages") {
                        header(APP_TOKEN_HEADER, account.token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"找猫"}""")
                    }.execute { resp ->
                        val ch = resp.bodyAsChannel()
                        val sb = StringBuilder()
                        val buf = ByteArray(4096)
                        while (!ch.isClosedForRead) {
                            val n = ch.readAvailable(buf, 0, buf.size)
                            if (n == -1) break
                            if (n > 0) {
                                sb.append(String(buf, 0, n))
                                var idx = sb.indexOf("\n\n")
                                while (idx >= 0) {
                                    frames.add(sb.substring(0, idx))
                                    sb.delete(0, idx + 2)
                                    idx = sb.indexOf("\n\n")
                                }
                            }
                        }
                    }
                }
                // 等 tool_request 帧到达后回传结果
                val requestId = withTimeout(15_000) {
                    while (true) {
                        val hit = frames.firstOrNull { f -> f.contains("\"tool_request\"") }
                        if (hit != null) {
                            return@withTimeout kotlinx.serialization.json.Json
                                .parseToJsonElement(hit.removePrefix("data:").trim())
                                .jsonObject["request_id"]!!.jsonPrimitive.content
                        }
                        delay(50)
                    }
                }
                val trResp = http.post("http://127.0.0.1:$port/agent/sessions/$sid/tool-results") {
                    header(APP_TOKEN_HEADER, account.token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"request_id":"$requestId","ok":true,"result":"找到 3 张"}""")
                }
                assertEquals(HttpStatusCode.OK, trResp.status)
                withTimeout(15_000) { sseJob.join() }
                val all = frames.joinToString("\n")
                assertTrue(all.contains("\"observation\""))
                assertTrue(all.contains("找到 3 张"))
                assertTrue(all.contains("找到 3 张猫的照片。"))
                assertTrue(frames.last().contains("\"done\""))
                assertEquals(0, bridge.pendingCount())
            }
        } finally {
            server.stop(500, 2_000)
        }
    }

    @Test
    fun `AC-M2-S2 device timeout yields error observation and done`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = runBlocking { AccountService.createOrRefresh("timeout@x.com", 100) }
        activateChannel()
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            // 缩短设备超时注入（生产默认 120s 不动）
            routing { agentRoute(newRunner(toolCallThenTextEngine()), AgentBridgeRegistry(), deviceTimeoutMs = 300) }
        }
        val sid = createSession(account.token)
        // 不回传 tool-results，让设备工具超时；MockEngine 第二轮照常给最终文本
        val resp = client.post("/agent/sessions/$sid/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"找猫"}""")
        }
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"tool_request\""))
        assertTrue(body.contains("device_tool_timeout"))
        assertTrue(body.contains("\"done\""))
    }

    private fun newRunner(engine: MockEngine): AgentRunner = AgentRunner(
        // 与 M1 AgentRunnerTest.runnerWith 同款的 MockEngine 注入缝
        AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
        ServerToolDispatcher.default(),
    )

    private suspend fun createSession(token: String): String {
        // 走 Repository 直建（路由建会话另有 M1 测试覆盖）
        return AgentSessionRepository.create(
            AccountService.sha256(token), AgentChannelKind.GATEWAY, "", "",
        ).id
    }
}
