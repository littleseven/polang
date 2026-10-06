package com.mamba.picme.server.admin

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.BrowserSessions
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.db.LlmChannels
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminChannelsRoutesTest {

    private val token = "test-admin-token"
    private val cos = CosService(AppConfig.load())
    private val balance = ChannelBalanceService(HttpClient(CIO))
    private val cookieVal get() = AdminAuth.expectedCookieValue(token)

    @Before
    fun setUp() {
        // /admin 概览页查 Accounts/LlmCallLogs/AnonymousDevices，故一并建表；渠道测试只需 LlmChannels。
        TestDb.init(Accounts, LlmCallLogs, LlmChannels, AnonymousDevices, BrowserSessions)
        ChannelRegistry.setActiveForTesting(null)
    }

    private fun formBody(
        name: String = "DeepSeek 直连",
        kind: String = "direct",
        baseUrl: String = "https://api.deepseek.com/v1/chat/completions",
        authStyle: String = "bearer",
        apiToken: String = "sk-test-1234",
        modelMap: String = "deepseek-v4-flash=deepseek-v4-flash",
        enabled: String = "1",
        defaultModel: String = "deepseek-v4-flash",
    ) = "name=$name&kind=$kind&base_url=$baseUrl&auth_style=$authStyle" +
        "&api_token=$apiToken&model_map=$modelMap&enabled=$enabled&default_model=$defaultModel"

    @Test
    fun `channels page requires cookie`() = testApplication {
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        val r = c.get("/admin/channels")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/login", r.headers[HttpHeaders.Location])
    }

    @Test
    fun `create channel then it appears and token is masked`() = testApplication {
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        assertEquals(HttpStatusCode.Found, r.status)

        val page = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("DeepSeek 直连"))
        assertTrue("token 不得明文出现", !html.contains("sk-test-1234"))
        assertTrue("应显示掩码", html.contains("••••"))
        assertTrue("应显示默认模型", html.contains("deepseek-v4-flash"))
        assertTrue("非生效渠道删除按钮应有二次确认", html.contains("确定删除该渠道"))
        assertTrue("应有复制按钮", html.contains("复制"))
        // 编辑页回填默认模型
        val editHtml = c.get("/admin/channels/1/edit") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(editHtml.contains("deepseek-v4-flash"))
    }

    @Test
    fun `active row hides activate and delete buttons, keeps toggle`() = testApplication {
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        c.post("/admin/channels/1/activate") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        val html = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(html.contains("生效中"))
        assertFalse("生效行不应显示「设为生效」", html.contains("设为生效"))
        assertFalse("生效行不应显示「删除」", html.contains("删除"))
        assertTrue("应有「停用」toggle", html.contains("停用"))
    }

    @Test
    fun `activate sets channel active`() = testApplication {
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        c.post("/admin/channels/1/activate") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        val html = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(html.contains("生效中"))
    }

    @Test
    fun `delete active channel is rejected`() = testApplication {
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        c.post("/admin/channels/1/activate") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        c.post("/admin/channels/1/delete") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        val html = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue("生效渠道应仍存在", html.contains("DeepSeek 直连"))
    }

    @Test
    fun `nav has channels link`() = testApplication {
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        val html = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(html.contains("/admin/channels"))
    }

    @Test
    fun `token endpoint returns full token for copy button`() = testApplication {
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        val resp = c.get("/admin/channels/1/token") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue("端点应返回完整 token", resp.bodyAsText().contains("sk-test-1234"))
        // 未带 cookie → 跳登录（不泄露 token）
        val noAuth = c.get("/admin/channels/1/token")
        assertEquals(HttpStatusCode.Found, noAuth.status)
    }

    @Test
    fun `channels page shows consumption columns and balance header`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, LlmChannels)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1; it[Accounts.email] = "a@x.com"; it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"; it[Accounts.llmCallsUsed] = 0; it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1L
            }
            LlmChannels.insert {
                it[LlmChannels.name] = "DeepSeek 直连"; it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.deepseek.com/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"; it[LlmChannels.apiToken] = "sk-1"
                it[LlmChannels.modelMapJson] = "{}"; it[LlmChannels.enabled] = 1; it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = 1L; it[LlmChannels.updatedAt] = 1L
                it[LlmChannels.balanceUrl] = "https://api.deepseek.com/user/balance"
            }
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = 1; it[LlmCallLogs.model] = "deepseek-chat"
                it[LlmCallLogs.provider] = "DeepSeek 直连"; it[LlmCallLogs.totalTokens] = 200
                it[LlmCallLogs.costCny] = 1.5; it[LlmCallLogs.respBytes] = 0; it[LlmCallLogs.status] = "ok"
                it[LlmCallLogs.createdAt] = 2L
            }
        }
        ChannelRegistry.reload()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        assertTrue(html.contains("余额"))
        assertTrue(html.contains("消耗"))
        assertTrue(html.contains("refresh-balance")) // DeepSeek 渠道有 balance_url → 渲染刷新按钮
    }

    @Test
    fun `refresh balance redirects back to channels`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, LlmChannels)
        transaction(Db.instance) {
            LlmChannels.insert {
                it[LlmChannels.name] = "DeepSeek 直连"; it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.deepseek.com/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"; it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = "{}"; it[LlmChannels.enabled] = 1; it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = 1L; it[LlmChannels.updatedAt] = 1L
                it[LlmChannels.balanceUrl] = "https://api.deepseek.com/user/balance"
            }
        }
        ChannelRegistry.reload()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        // 没有真实上游 / 无 token → refresh 返回 false，但路由仍 302 回列表（不报错）
        val r = c.post("/admin/channels/1/refresh-balance") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/channels", r.headers[HttpHeaders.Location])
    }
}
