package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.EmailService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.EmailVerifications
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Play 审核演示账号旁路（env PLAY_REVIEW_EMAIL / PLAY_REVIEW_CODE 双非空启用）：
 * 审核邮箱不发真实验证码邮件，固定码经 /auth/email/verify 直接换 token。
 */
class PlayReviewAuthRouteTest {

    private val reviewEmail = "play-review@polang.net"
    private val reviewCode = "Gp7x2Kq9Zm4w"

    /** apiKey 留空：sendVerificationCode 直接 false（dev 分支），不触网。 */
    private fun emailService() = EmailService(HttpClient(CIO), apiKey = "")

    private fun Application.authRouting(
        reviewEmail: String = this@PlayReviewAuthRouteTest.reviewEmail,
        reviewCode: String = this@PlayReviewAuthRouteTest.reviewCode,
    ) {
        install(ContentNegotiation) { json() }
        routing { authRoute(emailService(), reviewEmail, reviewCode) }
    }

    @Test
    fun `send for review email skips verification creation and email`() = testApplication {
        TestDb.init(Accounts, EmailVerifications)
        application { authRouting() }

        val resp = client.post("/auth/email/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$reviewEmail"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"dev\":true"))
        assertEquals(0L, transaction(Db.instance) { EmailVerifications.selectAll().count() })
    }

    @Test
    fun `verify accepts fixed review code and issues token`() = testApplication {
        TestDb.init(Accounts, EmailVerifications)
        application { authRouting() }

        val resp = client.post("/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$reviewEmail","code":"$reviewCode"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val token = Json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        assertTrue(token.startsWith(AccountService.TOKEN_PREFIX))
        val row = transaction(Db.instance) {
            Accounts.selectAll().where { Accounts.email eq reviewEmail }.single()
        }
        assertEquals("active", row[Accounts.status])
    }

    @Test
    fun `verify accepts review email case-insensitively`() = testApplication {
        TestDb.init(Accounts, EmailVerifications)
        application { authRouting() }

        val resp = client.post("/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"PLAY-REVIEW@POLANG.NET","code":"$reviewCode"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `verify rejects wrong code for review email`() = testApplication {
        TestDb.init(Accounts, EmailVerifications)
        application { authRouting() }

        val resp = client.post("/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$reviewEmail","code":"wrong-code"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertEquals(0L, transaction(Db.instance) { Accounts.selectAll().count() })
    }

    @Test
    fun `bypass disabled when env not configured`() = testApplication {
        TestDb.init(Accounts, EmailVerifications)
        application { authRouting(reviewEmail = "", reviewCode = "") }

        val sendResp = client.post("/auth/email/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$reviewEmail"}""")
        }
        assertEquals(HttpStatusCode.OK, sendResp.status)
        assertEquals(1L, transaction(Db.instance) { EmailVerifications.selectAll().count() })

        val verifyResp = client.post("/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$reviewEmail","code":"$reviewCode"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, verifyResp.status)
    }

    @Test
    fun `normal email flow still works with bypass configured`() = testApplication {
        TestDb.init(Accounts, EmailVerifications)
        application { authRouting() }

        val email = "user@example.com"
        client.post("/auth/email/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }
        val code = transaction(Db.instance) {
            EmailVerifications.selectAll().where { EmailVerifications.email eq email }
                .single()[EmailVerifications.code]
        }

        val resp = client.post("/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","code":"$code"}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `review code does not unlock other emails`() = testApplication {
        TestDb.init(Accounts, EmailVerifications)
        application { authRouting() }

        val resp = client.post("/auth/email/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"someone-else@example.com","code":"$reviewCode"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
