package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.browser.BrowserBridgeClient
import com.mamba.picme.server.browser.BrowserConcurrencyRegistry
import com.mamba.picme.server.browser.BrowserSessionStats
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.BrowserSessions
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRouteTest {

    private fun bridgeReturning(payload: String): BrowserBridgeClient =
        BrowserBridgeClient(
            HttpClient(MockEngine {
                respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }),
            "http://bridge",
            "tok",
        )

    private fun TestApplicationBuilder.app(
        bridge: BrowserBridgeClient,
        concurrency: BrowserConcurrencyRegistry,
        limiter: RateLimiter,
    ) {
        TestDb.init(Accounts, BrowserSessions)
        application {
            install(ContentNegotiation) { json(appJson) }
            install(WebSockets)
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { browserRoute(bridge, limiter, concurrency, BrowserSessionStats()) }
        }
    }

    @Test
    fun `open proxies to bridge and binds concurrency on ok`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        val bridge = bridgeReturning("""{"status":"ok","sessionId":"s-1","currentUrl":"https://example.com"}""")
        app(bridge, concurrency, RateLimiter(20, 86_400_000L))
        val resp = client.post("/v1/browser/open") {
            header(APP_TOKEN_HEADER, "test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com","wantFrame":true}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"sessionId\":\"s-1\""))
        assertEquals(1, concurrency.activeCount())
    }

    @Test
    fun `open rejected when user already has active session`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        // 预置租约：owner 与测试拦截器注入的 TokenHashKey 一致（sha256(token)）
        concurrency.tryAcquire(AccountService.sha256("test-token"))
        app(bridgeReturning("{}"), concurrency, RateLimiter(20, 86_400_000L))
        val resp = client.post("/v1/browser/open") {
            header(APP_TOKEN_HEADER, "test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("pool_exhausted"))
    }

    @Test
    fun `open rejected over daily quota`() = testApplication {
        val limiter = RateLimiter(0, 86_400_000L) // 配额 0
        app(bridgeReturning("{}"), BrowserConcurrencyRegistry(), limiter)
        val resp = client.post("/v1/browser/open") {
            header(APP_TOKEN_HEADER, "test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
    }

    @Test
    fun `open with non-ok bridge result neither charges quota nor holds lease`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        // 配额 1：若非 ok 也计费，第二次必定 429
        val limiter = RateLimiter(1, 86_400_000L)
        app(bridgeReturning("""{"status":"pool_exhausted","errorCode":"pool"}"""), concurrency, limiter)
        repeat(2) {
            val resp = client.post("/v1/browser/open") {
                header(APP_TOKEN_HEADER, "test-token")
                contentType(ContentType.Application.Json)
                setBody("""{"url":"https://example.com"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
        assertEquals(0, concurrency.activeCount())
    }

    @Test
    fun `open with unreachable bridge returns 503 and neither charges quota nor holds lease`() = testApplication {
        val concurrency = BrowserConcurrencyRegistry()
        val limiter = RateLimiter(1, 86_400_000L)
        val bridge = BrowserBridgeClient(
            HttpClient(MockEngine { throw java.net.ConnectException("refused") }),
            "http://bridge",
            "tok",
        )
        app(bridge, concurrency, limiter)
        repeat(2) {
            val resp = client.post("/v1/browser/open") {
                header(APP_TOKEN_HEADER, "test-token")
                contentType(ContentType.Application.Json)
                setBody("""{"url":"https://example.com"}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        }
        assertEquals(0, concurrency.activeCount())
    }

    // --- WebSocket 帧流代理（M2 /v1/browser/ws） ---

    /** 连接 WS 代理并断言服务端以指定 code/message 关闭（拒绝路径共用）。 */
    private suspend fun HttpClient.expectWsClose(path: String, token: String?, code: Short, message: String) {
        webSocket(path, {
            token?.let { header(APP_TOKEN_HEADER, it) }
        }) {
            // test-host WS 客户端不投递 Frame.Close（incoming 直接关闭），
            // 服务端关闭原因从 closeReason Deferred 读取
            try {
                @Suppress("UNUSED_EXPRESSION")
                incoming.receive()
            } catch (_: ClosedReceiveChannelException) {
                // 拒绝路径：服务端立即 close，incoming 关闭属预期
            }
            val reason = closeReason.await()
            assertEquals(code, reason?.code)
            assertEquals(message, reason?.message)
        }
    }

    @Test
    fun `ws proxy closes with unauthorized when app token missing`() = testApplication {
        app(bridgeReturning("{}"), BrowserConcurrencyRegistry(), RateLimiter(20, 86_400_000L))
        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.expectWsClose(
            "/v1/browser/ws?sessionId=x",
            token = null,
            code = CloseReason.Codes.VIOLATED_POLICY.code,
            message = "unauthorized",
        )
    }

    @Test
    fun `ws proxy closes with cannot_accept when sessionId missing`() = testApplication {
        app(bridgeReturning("{}"), BrowserConcurrencyRegistry(), RateLimiter(20, 86_400_000L))
        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.expectWsClose(
            "/v1/browser/ws",
            token = "test-token",
            code = CloseReason.Codes.CANNOT_ACCEPT.code,
            message = "sessionId required",
        )
    }

    @Test
    fun `ws proxy closes with browser_unavailable when bridge not configured`() = testApplication {
        // baseUrl/token 皆为空 → available=false（MockEngine 不会被触达）
        val bridge = BrowserBridgeClient(HttpClient(MockEngine { respond("{}") }), "", "")
        app(bridge, BrowserConcurrencyRegistry(), RateLimiter(20, 86_400_000L))
        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.expectWsClose(
            "/v1/browser/ws?sessionId=s-1",
            token = "test-token",
            code = CloseReason.Codes.SERVICE_RESTART.code,
            message = "browser_unavailable",
        )
    }

    @Test
    fun `ws proxy relays text and binary frames between app and bridge`() = testApplication {
        // 假 bridge（真实端口）：Text 回 "echo:"+原文，Binary 原样回；记录收到的帧
        val bridgeReceived = CopyOnWriteArrayList<ByteArray>()
        val bridgeServer = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                bridgeReceived.add(frame.data)
                                send(Frame.Text("echo:" + frame.readText()))
                            }
                            is Frame.Binary -> {
                                bridgeReceived.add(frame.data)
                                send(Frame.Binary(true, frame.data))
                            }
                            else -> {}
                        }
                    }
                }
            }
        }.start(wait = false)
        try {
            // Ktor 3.0.3：resolvedConnectors() 是 suspend 成员（3.1+ 才有 engineResolvedConnectors 扩展）
            val port = bridgeServer.engine.resolvedConnectors().first().port
            val bridge = BrowserBridgeClient(
                HttpClient(MockEngine { respond("{}") }),
                "http://127.0.0.1:$port",
                "tok",
            )
            app(bridge, BrowserConcurrencyRegistry(), RateLimiter(20, 86_400_000L))
            val wsClient = createClient { install(ClientWebSockets) }
            wsClient.webSocket("/v1/browser/ws?sessionId=s-1", { header(APP_TOKEN_HEADER, "test-token") }) {
                send(Frame.Text("ping"))
                val echoed = incoming.receive() as Frame.Text
                assertEquals("echo:ping", echoed.readText())

                send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                val binary = incoming.receive() as Frame.Binary
                assertTrue(binary.data.contentEquals(byteArrayOf(1, 2, 3)))
            }
            // bridge 侧按序收到代理转发的 Text + Binary（echo 已回，收到必然先于断言）
            assertEquals(2, bridgeReceived.size)
            assertTrue(bridgeReceived[0].contentEquals("ping".toByteArray()))
            assertTrue(bridgeReceived[1].contentEquals(byteArrayOf(1, 2, 3)))
        } finally {
            bridgeServer.stop(1000, 2000)
        }
    }
}
