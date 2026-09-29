package com.mamba.picme.server.routes

import com.mamba.picme.server.cos.CosService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class AppLatestResponse(
    val available: Boolean,
    val channel: String,
    val versionCode: Long = 0,
    val versionName: String = "",
    val url: String = "",
    val sizeBytes: Long = 0,
    val changelog: String = "",
    val updatedAt: String = "",
)

fun Routing.appLatestRoute(cosService: CosService) {
    get("/api/app/latest") {
        val channel = call.request.queryParameters["channel"]
        if (!CosService.isValidChannel(channel)) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "bad_request", "message" to "channel must be debug or release"),
            )
            return@get
        }
        val info = cosService.getApkInfo(channel!!)
        call.respond(
            AppLatestResponse(
                available = info.exists,
                channel = channel,
                versionCode = info.versionCode,
                versionName = info.version,
                url = info.publicUrl,
                sizeBytes = info.sizeBytes,
                changelog = info.changelog,
                updatedAt = info.lastModified,
            ),
        )
    }
}
