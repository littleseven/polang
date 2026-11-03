package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.util.TestDb
import com.mamba.picme.server.websearch.SearchOutcome
import com.mamba.picme.server.websearch.WebSearchResult
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchRouteTest {

    private val okExecutor = com.mamba.picme.server.websearch.SearchExecutor { _, _ ->
        SearchOutcome.Success(
            listOf(
                WebSearchResult(
                    title = "标题", url = "https://a.example.com", snippet = "摘要",
                    siteName = "站点", date = null,
                    images = listOf("https://cdn.example.com/a.jpg"),
                    contentExcerpt = "摘录",
                ),
            ),
        )
    }

    private fun TestApplicationBuilder.app(
        executor: com.mamba.picme.server.websearch.SearchExecutor? = okExecutor,
    ) {
        TestDb.init(Accounts, LlmCallLogs)
        application {
            install(ContentNegotiation) { json(appJson) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers["X-App-Token"]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { webSearchRoute(executor, rateLimiter = null) }
        }
    }

    private fun seedToken(email: String = "u@x.com"): String = runBlocking {
        AccountService.createOrRefresh(email, 100).token
    }

    @Test
    fun `无 token 返回 401`() = testApplication {
        app()
        val resp = client.post("/v1/web-search") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":"花"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `空 query 返回 400`() = testApplication {
        app()
        val token = seedToken()
        val resp = client.post("/v1/web-search") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"query":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `未配置 executor 返回 503`() = testApplication {
        app(executor = null)
        val token = seedToken()
        val resp = client.post("/v1/web-search") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"query":"花"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("web_search_unavailable", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `成功返回紧凑结果 JSON`() = testApplication {
        app()
        val token = seedToken()
        val resp = client.post("/v1/web-search") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"query":"花","count":5}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("ok", json["status"]?.jsonPrimitive?.content)
        val results = json["results"]!!.jsonArray
        assertEquals(1, results.size)
        val first = results[0].jsonObject
        assertEquals("标题", first["title"]?.jsonPrimitive?.content)
        assertEquals("https://cdn.example.com/a.jpg", first["images"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `上游不可用映射 502`() = testApplication {
        app(executor = { _, _ -> SearchOutcome.Unavailable("rate_limited") })
        val token = seedToken()
        val resp = client.post("/v1/web-search") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"query":"花"}""")
        }
        assertEquals(HttpStatusCode.BadGateway, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("web_search_unavailable", json["error"]?.jsonPrimitive?.content)
        assertTrue(json["reason"]?.jsonPrimitive?.content == "rate_limited")
    }
}
