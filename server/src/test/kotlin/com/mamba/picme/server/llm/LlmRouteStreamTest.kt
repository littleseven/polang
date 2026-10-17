package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.defaultPrices
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
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

            val row = transaction(Db.instance) { LlmCallLogs.selectAll().single() }
            assertEquals("glm-5.2", row[LlmCallLogs.model])
            assertEquals("TestChan", row[LlmCallLogs.provider])
            assertEquals(12, row[LlmCallLogs.promptTokens])
            assertEquals(7, row[LlmCallLogs.completionTokens])
            assertEquals(19, row[LlmCallLogs.totalTokens])
            assertEquals(sseBody.toByteArray().size, row[LlmCallLogs.respBytes])
            assertEquals("ok", row[LlmCallLogs.status])
        } finally {
            server.stop()
        }
    }
}
