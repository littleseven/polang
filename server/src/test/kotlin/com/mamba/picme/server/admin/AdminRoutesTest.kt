package com.mamba.picme.server.admin

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.db.ServerSettings
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminRoutesTest {

    private val token = "test-admin-token"
    private val cos = CosService(AppConfig.load())
    private val balance = ChannelBalanceService(HttpClient(CIO))
    private val cookieVal get() = AdminAuth.expectedCookieValue(token)

    private fun seed() {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1
                it[Accounts.email] = "a@x.com"
                it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = 1
                it[LlmCallLogs.model] = "deepseek-chat"
                it[LlmCallLogs.provider] = "CLOUDFLARE"
                it[LlmCallLogs.promptTokens] = 10
                it[LlmCallLogs.completionTokens] = 5
                it[LlmCallLogs.totalTokens] = 15
                it[LlmCallLogs.costCny] = 0.2
                it[LlmCallLogs.respBytes] = 100
                it[LlmCallLogs.status] = "ok"
                it[LlmCallLogs.createdAt] = 1_700_000_001_000L
            }
        }
    }

    @Test
    fun `full admin auth and view flow`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        // 1. no cookie → redirect to login
        val r1 = c.get("/admin")
        assertEquals(HttpStatusCode.Found, r1.status)
        assertEquals("/admin/login", r1.headers[HttpHeaders.Location])

        // 2. login page reachable
        val r2 = c.get("/admin/login")
        assertEquals(HttpStatusCode.OK, r2.status)
        assertTrue(r2.bodyAsText().contains("PoLang 管理后台"))

        // 3. wrong password → 401
        val r3 = c.post("/admin/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=wrong")
        }
        assertEquals(HttpStatusCode.Unauthorized, r3.status)

        // 4. correct password → 302 redirect to /admin + 安全 cookie 标志
        val r4 = c.post("/admin/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=$token")
        }
        assertEquals(HttpStatusCode.Found, r4.status)
        val setCookie = r4.headers[HttpHeaders.SetCookie]
        assertTrue("cookie 应含 HttpOnly", setCookie?.contains("HttpOnly") == true)
        assertTrue("cookie 应含 SameSite=Lax", setCookie?.contains("SameSite=Lax") == true)

        // 5. valid cookie → overview 200
        val r5 = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r5.status)
        val ovHtml = r5.bodyAsText()
        assertTrue(ovHtml.contains("概览"))
        assertTrue(ovHtml.contains("今日调用"))

        // 6. users page lists the seeded email and lifecycle action forms
        val r6 = c.get("/admin/users") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r6.status)
        val usersHtml = r6.bodyAsText()
        assertTrue(usersHtml.contains("a@x.com"))
        assertTrue(usersHtml.contains("/admin/users/1/revoke"))
        assertTrue(usersHtml.contains("/admin/users/1/delete"))
        assertTrue(usersHtml.contains("badge-active"))

        // 7. user detail
        val r7 = c.get("/admin/users/1") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r7.status)
        assertTrue(r7.bodyAsText().contains("deepseek-chat"))

        // 8. unknown user → 404
        val r8 = c.get("/admin/users/999") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.NotFound, r8.status)

        // 9. traffic page
        val r9 = c.get("/admin/traffic") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r9.status)
        assertTrue(r9.bodyAsText().contains("每日明细"))

        // 10. revoke user
        val r10 = c.post("/admin/users/1/revoke") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r10.status)
        assertEquals("/admin/users", r10.headers[HttpHeaders.Location])

        // 11. unrevoke user
        val r11 = c.post("/admin/users/1/unrevoke") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r11.status)

        // 12. delete user (immediate purge)
        val r12 = c.post("/admin/users/1/delete") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r12.status)
        assertEquals("/admin/users", r12.headers[HttpHeaders.Location])
    }

    @Test
    fun `disabled admin token returns 503`() = testApplication {
        seed()
        application { routing { adminRoute("", cos, balance) } } // 空 token → 禁用
        val c = createClient { followRedirects = false }
        val r = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, "anything") }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
    }

    @Test
    fun `devices page lists anonymous devices raw and delete by id`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1
                it[Accounts.email] = "a@x.com"
                it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = 5
                it[AnonymousDevices.deviceId] = "abcdef1234567890"
                it[AnonymousDevices.llmCallsUsed] = 7
                it[AnonymousDevices.createdAt] = 1_700_000_000_000L
                it[AnonymousDevices.lastSeenAt] = 1_700_000_001_000L
                it[AnonymousDevices.platform] = "android"
            }
        }
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        // 列表
        val list = c.get("/admin/devices") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, list.status)
        val html = list.bodyAsText()
        assertTrue(html.contains("未注册设备"))
        assertTrue(html.contains("注册用户 (1)")) // 二级 Tab 计数
        assertTrue(html.contains("未注册设备 (1)")) // 二级 Tab 计数
        assertTrue(html.contains("abcdef••••7890")) // 掩码
        assertTrue(html.contains("7 / 100")) // 额度
        assertTrue(html.contains("/admin/devices/5/delete"))
        assertTrue(html.contains("平台")) // 表头
        assertTrue(html.contains("android")) // platform 值

        // raw 返回完整 device_id（cookie 鉴权）
        val raw = c.get("/admin/devices/5/raw") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, raw.status)
        assertTrue(raw.bodyAsText().contains("\"device_id\":\"abcdef1234567890\""))

        // 未知 id → 404
        val nf = c.get("/admin/devices/999/raw") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.NotFound, nf.status)

        // 删除 → 重定向回列表
        val del = c.post("/admin/devices/5/delete") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, del.status)
        assertEquals("/admin/devices", del.headers[HttpHeaders.Location])
    }

    @Test
    fun `devices page filters by platform`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1
                it[Accounts.email] = "a@x.com"
                it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = 5
                it[AnonymousDevices.deviceId] = "android_device_001"
                it[AnonymousDevices.llmCallsUsed] = 3
                it[AnonymousDevices.createdAt] = 1_700_000_000_000L
                it[AnonymousDevices.lastSeenAt] = 1_700_000_001_000L
                it[AnonymousDevices.platform] = "android"
            }
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = 6
                it[AnonymousDevices.deviceId] = "ios_device_000002"
                it[AnonymousDevices.llmCallsUsed] = 5
                it[AnonymousDevices.createdAt] = 1_700_000_000_000L
                it[AnonymousDevices.lastSeenAt] = 1_700_000_002_000L
                it[AnonymousDevices.platform] = "ios"
            }
        }
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        // 无筛选：2 台
        val all = c.get("/admin/devices") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        val allHtml = all.bodyAsText()
        assertTrue(allHtml.contains("未注册设备 (2)"))
        // device_id 掩码：take(6)+••••+takeLast(4)
        assertTrue(allHtml.contains("androi••••_001"))
        assertTrue(allHtml.contains("ios_de••••0002"))

        // 筛选 android：只显示 android 设备
        val android = c.get("/admin/devices?platform=android") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        val androidHtml = android.bodyAsText()
        assertTrue(androidHtml.contains("androi••••_001"))
        assertTrue(!androidHtml.contains("ios_de••••0002"))

        // 筛选 ios：只显示 ios 设备
        val ios = c.get("/admin/devices?platform=ios") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        val iosHtml = ios.bodyAsText()
        assertTrue(iosHtml.contains("ios_de••••0002"))
        assertTrue(!iosHtml.contains("androi••••_001"))
    }

    @Test
    fun `reset user quota zeroes used and redirects to detail`() = testApplication {
        seed()
        transaction(Db.instance) {
            Accounts.update({ Accounts.id eq 1 }) {
                with(SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed + 20 }
            }
        }
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/users/1/reset-quota") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/users/1", r.headers[HttpHeaders.Location])

        val used = transaction(Db.instance) {
            Accounts.selectAll().where { Accounts.id eq 1 }.single()[Accounts.llmCallsUsed]
        }
        assertEquals(0, used)
    }

    @Test
    fun `set user limit updates limit and redirects`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/users/1/limit") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("limit=250")
        }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/users/1", r.headers[HttpHeaders.Location])
        val limit = transaction(Db.instance) {
            Accounts.selectAll().where { Accounts.id eq 1 }.single()[Accounts.llmCallsLimit]
        }
        assertEquals(250, limit)
    }

    @Test
    fun `reset guest device quota redirects to devices`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1; it[Accounts.email] = "a@x.com"; it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"; it[Accounts.llmCallsUsed] = 0; it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = 5; it[AnonymousDevices.deviceId] = "abcdef1234567890"
                it[AnonymousDevices.llmCallsUsed] = 9; it[AnonymousDevices.createdAt] = 1L; it[AnonymousDevices.lastSeenAt] = 2L
            }
        }
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/devices/5/reset-quota") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/devices", r.headers[HttpHeaders.Location])
        val used = transaction(Db.instance) {
            AnonymousDevices.selectAll().where { AnonymousDevices.id eq 5 }.single()[AnonymousDevices.llmCallsUsed]
        }
        assertEquals(0, used)
    }

    @Test
    fun `settings page round-trips free and guest quota`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices, ServerSettings, AiEngineerWhitelists)
        SettingsService.load()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val get = c.get("/admin/settings") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, get.status)
        assertTrue(get.bodyAsText().contains("额度默认值"))

        val post = c.post("/admin/settings") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("free_llm_quota=888&guest_llm_quota=66&browser_daily_quota=33")
        }
        assertEquals(HttpStatusCode.Found, post.status)
        assertEquals("/admin/settings", post.headers[HttpHeaders.Location])
        assertEquals(888, SettingsService.snapshot().freeLlmQuota)
        assertEquals(66, SettingsService.snapshot().guestLlmQuota)
        assertEquals(33, SettingsService.snapshot().browserDailyQuota)
    }

    @Test
    fun `overview and traffic honor days and metric query params and clamp invalid`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        // overview 接受 days=14&metric=cost
        val ov = c.get("/admin?days=14&metric=cost") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, ov.status)
        val ovHtml = ov.bodyAsText()
        assertTrue(ovHtml.contains("近 14 天 · 成本 ¥"))
        assertTrue(ovHtml.contains("ctrl active"))

        // traffic 接受 days=90&metric=tokens
        val tr = c.get("/admin/traffic?days=90&metric=tokens") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, tr.status)
        assertTrue(tr.bodyAsText().contains("近 90 天，UTC+8）· Token"))

        // 非法值回落默认（days=3 / metric=foo），仍 200、不崩
        val bad = c.get("/admin/traffic?days=3&metric=foo") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, bad.status)
        assertTrue(bad.bodyAsText().contains("近 30 天，UTC+8）· 调用数"))
    }
}
