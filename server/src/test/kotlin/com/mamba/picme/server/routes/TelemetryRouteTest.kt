package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.TelemetryEvents
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryRouteTest {

    @Test
    fun `POST telemetry inserts each event row`() = testApplication {
        TestDb.init(TelemetryEvents)
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { telemetryRoute() }
        }

        val resp = client.post("/telemetry") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"events":[""" +
                    """{"type":"$TELEMETRY_TYPE_MODEL_DOWNLOAD","payload":{"modelId":"a","outcome":"completed"}},""" +
                    """{"type":"$TELEMETRY_TYPE_MODEL_DOWNLOAD","payload":{"modelId":"b","outcome":"failed","errorClass":"http"}},""" +
                    """{"type":"noop"}]}""",
            )
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        transaction(Db.instance) {
            assertEquals(3L, TelemetryEvents.selectAll().count())
            assertEquals(2L, TelemetryEvents.selectAll().where { TelemetryEvents.type eq TELEMETRY_TYPE_MODEL_DOWNLOAD }.count())
        }
    }

    @Test
    fun `POST telemetry caps oversized batch`() = testApplication {
        TestDb.init(TelemetryEvents)
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { telemetryRoute() }
        }

        val events = (1..500).joinToString(",") { """{"type":"spam"}""" }
        val resp = client.post("/telemetry") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[$events]}""")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(400L, transaction(Db.instance) { TelemetryEvents.selectAll().count() })
    }
}
