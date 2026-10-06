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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
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
}
