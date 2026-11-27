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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * AG-UI v2 端点端到端测试（spec《2026-11-28-ag-ui-migration-design》§9）：
 * 真实 CIO server + CIO client 全回合（两轮 LLM：tool_calls → 终结文本），
 * 断言 AG-UI 标准帧序与协议不变式；v1 路径由 AgentRoutesBridgeTest 钉住。
 */
class AgentRoutesV2Test {

    @After
    fun tearDown() {
        ChannelRegistry.setActiveForTesting(null)
    }

    /** 与 AgentRoutesBridgeTest 同款两轮 MockEngine：tool_calls(search_media) → 终结文本。 */
    private fun toolCallThenTextEngine(): MockEngine {
        val callCount = AtomicInteger(0)
        return MockEngine { _ ->
            val n = callCount.incrementAndGet()
            val body = if (n == 1) {
                """{"id":"c1","object":"chat.completion","created":0,"model":"glm-5.2","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"search_media","arguments":"{\"query\":\"猫\",\"person\":\"\",\"fromMs\":\"\",\"toMs\":\"\",\"hasFace\":\"\",\"mediaType\":\"photo\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
            } else {
                """{"id":"c2","object":"chat.completion","created":0,"model":"glm-5.2","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"找到 3 张猫的照片。"}}],"usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
    }

    @Test
    fun `v2 messages on unknown session returns 404`() = testApplication {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = runBlocking { AccountService.createOrRefresh("v2-404@x.com", 100) }
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { agentRoute(newRunner(toolCallThenTextEngine()), AgentBridgeRegistry()) }
        }
        val resp = client.post("/agent/v2/sessions/no-such/messages") {
            header(APP_TOKEN_HEADER, account.token)
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hi"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `v2 ag-ui round trip emits standard event sequence`() = runBlocking {
        TestDb.init(Accounts, AgentSessions, AgentEvents)
        val account = AccountService.createOrRefresh("v2@x.com", 100)
        ChannelRegistry.setActiveForTesting(
            ChannelConfig(1, "TestChan", "direct", "http://up.example/chat", AuthStyle.BEARER, "tok-abc", emptyMap(), "glm-5.2"),
        )
        val bridge = AgentBridgeRegistry()
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
                val sid = AgentSessionRepository.create(
                    AccountService.sha256(account.token), AgentChannelKind.GATEWAY, "", "",
                ).id
                val frames = Collections.synchronizedList(mutableListOf<JsonObject>())
                val sseJob = launch(Dispatchers.IO) {
                    http.preparePost("http://127.0.0.1:$port/agent/v2/sessions/$sid/messages") {
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
                                    val block = sb.substring(0, idx)
                                    sb.delete(0, idx + 2)
                                    idx = sb.indexOf("\n\n")
                                    frames.add(
                                        Json.parseToJsonElement(block.removePrefix("data:").trim()).jsonObject,
                                    )
                                }
                            }
                        }
                    }
                }
                // 等 CUSTOM device_tool_request 帧到达后回传结果（桥闭环）
                val requestId = withTimeout(15_000) {
                    while (true) {
                        val hit = frames.firstOrNull { f ->
                            f["type"]?.jsonPrimitive?.content == "CUSTOM" &&
                                f["name"]?.jsonPrimitive?.content == "device_tool_request"
                        }
                        if (hit != null) {
                            return@withTimeout hit["value"]!!.jsonObject["request_id"]!!.jsonPrimitive.content
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

                fun types() = frames.map { it["type"]!!.jsonPrimitive.content }

                // ① run 边界：首帧 RUN_STARTED，threadId=sessionId
                assertEquals("RUN_STARTED", types().first())
                assertEquals(sid, frames.first()["threadId"]!!.jsonPrimitive.content)
                assertNotNull(frames.first()["runId"]!!.jsonPrimitive.content)

                // ② 用户消息 triad（role=user）
                val userStart = frames.first { f ->
                    f["type"]?.jsonPrimitive?.content == "TEXT_MESSAGE_START" &&
                        f["role"]?.jsonPrimitive?.content == "user"
                }
                assertTrue(userStart["messageId"]!!.jsonPrimitive.content.startsWith("evt-"))

                // ③ 工具调用三段式 + act-{eventId} 锚
                val tcStart = frames.first { f -> f["type"]?.jsonPrimitive?.content == "TOOL_CALL_START" }
                assertEquals("search_media", tcStart["toolCallName"]!!.jsonPrimitive.content)
                val tcId = tcStart["toolCallId"]!!.jsonPrimitive.content
                assertTrue(tcId.startsWith("act-"))
                val tcArgs = frames.first { f -> f["type"]?.jsonPrimitive?.content == "TOOL_CALL_ARGS" }
                assertEquals(tcId, tcArgs["toolCallId"]!!.jsonPrimitive.content)
                assertTrue(tcArgs["delta"]!!.jsonPrimitive.content.contains("\"query\":\"猫\""))

                // ④ 工具结果配对（桥回传 找到 3 张；observation content 为 Koog toolResult
                // toString 原文、String 结果带 JSON 引号——v1 事件帧同口径，sink 不做改写）
                val tcResult = frames.first { f -> f["type"]?.jsonPrimitive?.content == "TOOL_CALL_RESULT" }
                assertEquals(tcId, tcResult["toolCallId"]!!.jsonPrimitive.content)
                assertTrue(tcResult["content"]!!.jsonPrimitive.content.contains("找到 3 张"))

                // ⑤ 终结消息含 ensureValidProgram 兜底包装后的正文
                val assistantText = frames.filter { f -> f["type"]?.jsonPrimitive?.content == "TEXT_MESSAGE_CONTENT" }
                    .joinToString("") { f -> f["delta"]!!.jsonPrimitive.content }
                assertTrue(assistantText.contains("找到 3 张猫的照片。"))

                // ⑥ 收尾 RUN_FINISHED，metadata 携 done 帧全量数据
                assertEquals("RUN_FINISHED", types().last())
                val meta = frames.last()["metadata"]!!.jsonObject
                assertTrue(meta.containsKey("elapsed_ms"))
                assertTrue(meta.containsKey("prompt_tokens"))
                assertTrue(meta.containsKey("completion_tokens"))
                assertTrue(meta.containsKey("openui_lang_version"))

                // ⑦ 协议不变式：TEXT_MESSAGE_START/END 全配对（user + assistant + 流式块）
                assertEquals(
                    types().count { it == "TEXT_MESSAGE_START" },
                    types().count { it == "TEXT_MESSAGE_END" },
                )
                // ⑧ 无 v1 帧泄漏（kind 字段绝迹）
                assertTrue(frames.none { it.containsKey("kind") })
                assertEquals(0, bridge.pendingCount())
            }
        } finally {
            server.stop(500, 2_000)
        }
    }

    private fun newRunner(engine: MockEngine): AgentRunner = AgentRunner(
        AgentExecutorFactory(KtorKoogHttpClient.Factory(HttpClient(engine))),
        ServerToolDispatcher.default(),
    )
}
