package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ClaudeChatRoute 测试：SSE 透传（错误场景见 [ClaudeRouteErrorTest]）。
 * 诊断对话对所有已认证账号开放，不校验白名单。
 *
 * 2026-10-17：Ktor 全族升 3.3.3 后 testApplication 对 respondBytesWriter 流式响应
 * 不回放字节（断言收到空 body）；与 AgentRoutesBridgeTest 的 AC-M2-S1 同口径，
 * 改用真实 CIO server（随机端口）+ CIO client 读取完整 SSE 流。
 */
class ClaudeChatRouteTest {

    private val sseBody =
        "event: session\ndata: {\"sid\":\"s1\"}\n\n" +
            "event: assistant_text\ndata: {\"delta\":\"hi\"}\n\n" +
            "event: done\ndata: {}\n\n"

    @Test
    fun `已认证用户未在白名单仍可进入诊断对话`() = runBlocking {
        TestDb.init(Accounts, AiEngineerWhitelists)
        val upstream = okUpstream(sseBody, "text/event-stream")
        val token = seedClaudeToken()
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json(appJson) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { claudeChatRoute(upstream, null) }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpClient(CIO).use { http ->
                val resp = http.post("http://127.0.0.1:$port/v1/claude-chat") {
                    header(APP_TOKEN_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"hi"}""")
                }
                assertEquals(HttpStatusCode.OK, resp.status)
                assertEquals(sseBody, resp.bodyAsText())
            }
        } finally {
            server.stop()
        }
    }
}
