package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.defaultPrices
import com.mamba.picme.server.auth.APP_CHANNEL_HEADER
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.routes.DebugQuotaBypassKey
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 SSE 端到端：真实 CIO server 起路由，MockEngine 模拟上游 SSE，
 * 断言客户端收到透流内容且 usage 写入 llm_call_log。
 *
 * 2026-10-17：Ktor 全族升 3.3.3 后 testApplication 与 respondBytesWriter 流式写出
 * 存在时序差（usage 记账协程尚未落库断言即执行）；与 AgentRoutesBridgeTest 的
 * AC-M2-S1 同口径改用 embeddedServer（随机端口）+ CIO client。
 */
class LlmRouteStreamTest {

    private val sseBody =
        "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7,\"total_tokens\":19}}\n\n" +
            "data: [DONE]\n\n"

    @After
    fun tearDown() {
        ChannelRegistry.setActiveForTesting(null)
    }

    @Test
    fun `stream request proxies SSE and records usage`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        val account = AccountService.createOrRefresh("stream@x.com", 100)
        val engine = MockEngine {
            respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        ChannelRegistry.setActiveForTesting(
            ChannelConfig(
                1, "TestChan", "direct", "http://up.example/chat",
                AuthStyle.BEARER, "tok-abc",
                mapOf("deepseek-chat" to "glm-5.2"), "glm-5.2",
            ),
        )
        val proxy = LlmProxy(HttpClient(engine), maxTokensCap = 4096)

        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json() }
            // 复刻 Application.module 的 auth interceptor 最小行为：token → TokenHashKey
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { llmRoute(proxy, null, defaultPrices()) }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpClient(CIO).use { http ->
                val resp = http.post("http://127.0.0.1:$port/v1/chat/completions") {
                    header(APP_TOKEN_HEADER, account.token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"deepseek-chat","stream":true,"stream_options":{"include_usage":true}}""")
                }

                assertEquals(HttpStatusCode.OK, resp.status)
                assertEquals(sseBody, resp.bodyAsText())
            }

            // 流式 usage 记账在 body 透传完成后才落库（见类注释时序差）：断言前有界轮询等行出现，
            // 同 class  preceded 测试拖慢 post-body 写库时不再假阴性
            var row = transaction(Db.instance) { LlmCallLogs.selectAll().singleOrNull() }
            val deadline = System.currentTimeMillis() + 5_000
            while (row == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                row = transaction(Db.instance) { LlmCallLogs.selectAll().singleOrNull() }
            }
            assertTrue("usage row should be recorded", row != null)
            val usageRow = row!!
            assertEquals("glm-5.2", usageRow[LlmCallLogs.model])
            assertEquals("TestChan", usageRow[LlmCallLogs.provider])
            assertEquals(12, usageRow[LlmCallLogs.promptTokens])
            assertEquals(7, usageRow[LlmCallLogs.completionTokens])
            assertEquals(19, usageRow[LlmCallLogs.totalTokens])
            assertEquals(sseBody.toByteArray().size, usageRow[LlmCallLogs.respBytes])
            assertEquals("ok", usageRow[LlmCallLogs.status])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `debug channel skips account quota when bypass enabled`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        val account = AccountService.createOrRefresh("dbg-llm@x.com", 0) // 额度已尽（limit=0）
        val engine = MockEngine {
            respond(
                """{"id":"c1","object":"chat.completion","created":1730000000,"model":"glm-5.2",
                   "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                   "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}""".trimIndent(),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        ChannelRegistry.setActiveForTesting(
            ChannelConfig(
                1, "TestChan", "direct", "http://up.example/chat",
                AuthStyle.BEARER, "tok-abc",
                mapOf("deepseek-chat" to "glm-5.2"), "glm-5.2",
            ),
        )
        val proxy = LlmProxy(HttpClient(engine), maxTokensCap = 4096)

        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json() }
            // 复刻 Application.module：token → TokenHashKey；DEBUG_QUOTA_BYPASS 开 + debug 渠道头 → 豁免标记
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
                if (call.request.headers[APP_CHANNEL_HEADER] == "debug") {
                    call.attributes.put(DebugQuotaBypassKey, true)
                }
            }
            routing { llmRoute(proxy, null, defaultPrices()) }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpClient(CIO).use { http ->
                // 豁免：debug 头 → 200，额度计数不增
                val bypassed = http.post("http://127.0.0.1:$port/v1/chat/completions") {
                    header(APP_TOKEN_HEADER, account.token)
                    header(APP_CHANNEL_HEADER, "debug")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"deepseek-chat"}""")
                }
                assertEquals(HttpStatusCode.OK, bypassed.status)

                // 控制组：无 debug 头（env 开但头缺失）→ 照常 403
                val blocked = http.post("http://127.0.0.1:$port/v1/chat/completions") {
                    header(APP_TOKEN_HEADER, account.token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"deepseek-chat"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, blocked.status)
                assertTrue(blocked.bodyAsText().contains("quota_exceeded"))
            }
            // 豁免只跳扣减：额度计数保持 0；用量照记（一条 ok + 一条 blocked_quota）
            assertEquals(0, transaction(Db.instance) { Accounts.selectAll().single()[Accounts.llmCallsUsed] })
            assertEquals(2, transaction(Db.instance) { LlmCallLogs.selectAll().count() })
        } finally {
            server.stop()
        }
    }
}
