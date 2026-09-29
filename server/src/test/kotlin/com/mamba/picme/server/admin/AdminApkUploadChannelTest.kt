package com.mamba.picme.server.admin

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.ApkUploads
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminApkUploadChannelTest {

    private val token = "test-admin-token"
    private val cos = CosService(AppConfig.load())
    private val balance = ChannelBalanceService(HttpClient(CIO))

    private fun apkMultipart(channel: String, versionCode: String, changelog: String) =
        MultiPartFormDataContent(
            formData {
                append("channel", channel)
                append("version", "1.0.42")
                append("versionCode", versionCode)
                append("changelog", changelog)
                append(
                    "apkfile",
                    "fake-apk-bytes".toByteArray(),
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"polang-debug.apk\"")
                    },
                )
            },
        )

    @Test
    fun `POST admin apk upload with X-Admin-Token header records channel in history`() = testApplication {
        TestDb.init(ApkUploads)
        application { routing { adminRoute(token, cos, balance) } }

        val resp = client.post("/admin/apk/upload") {
            header("X-Admin-Token", token)
            setBody(apkMultipart(channel = "debug", versionCode = "10042", changelog = "OTA 测试"))
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        // COS 未配置 → 上传失败，但渠道必须如实记入历史
        assertTrue(resp.bodyAsText().contains("\"ok\":false"))
        val row = transaction(Db.instance) { ApkUploads.selectAll().single() }
        assertEquals("debug", row[ApkUploads.channel])
        assertEquals("1.0.42", row[ApkUploads.version])
        assertEquals("failed", row[ApkUploads.status])
    }

    @Test
    fun `POST admin apk upload rejects invalid channel without writing history`() = testApplication {
        TestDb.init(ApkUploads)
        application { routing { adminRoute(token, cos, balance) } }

        val resp = client.post("/admin/apk/upload") {
            header("X-Admin-Token", token)
            setBody(apkMultipart(channel = "beta", versionCode = "10042", changelog = ""))
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"ok\":false"))
        assertTrue(resp.bodyAsText().contains("channel"))
        val count = transaction(Db.instance) { ApkUploads.selectAll().count() }
        assertEquals(0, count)
    }

    @Test
    fun `POST admin apk upload without any credential redirects to login`() = testApplication {
        TestDb.init(ApkUploads)
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val resp = c.post("/admin/apk/upload") {
            setBody(apkMultipart(channel = "debug", versionCode = "10042", changelog = ""))
        }

        assertEquals(HttpStatusCode.Found, resp.status)
    }
}
