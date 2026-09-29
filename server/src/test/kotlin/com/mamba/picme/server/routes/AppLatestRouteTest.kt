package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.cos.CosService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLatestRouteTest {

    private fun unconfiguredCos() = CosService(AppConfig.load())

    @Test
    fun `GET api app latest returns unavailable debug channel info when COS unconfigured`() = testApplication {
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { appLatestRoute(unconfiguredCos()) }
        }

        val resp = client.get("/api/app/latest?channel=debug")

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"available\":false"))
        assertTrue(body.contains("\"channel\":\"debug\""))
        assertTrue(body.contains("polang-debug.apk"))
    }

    @Test
    fun `GET api app latest returns unavailable release channel info when COS unconfigured`() = testApplication {
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { appLatestRoute(unconfiguredCos()) }
        }

        val resp = client.get("/api/app/latest?channel=release")

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"available\":false"))
        assertTrue(body.contains("\"channel\":\"release\""))
        assertTrue(body.contains("polang-release.apk"))
    }

    @Test
    fun `GET api app latest rejects missing channel`() = testApplication {
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { appLatestRoute(unconfiguredCos()) }
        }

        val resp = client.get("/api/app/latest")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `GET api app latest rejects invalid channel`() = testApplication {
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { appLatestRoute(unconfiguredCos()) }
        }

        val resp = client.get("/api/app/latest?channel=beta")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
