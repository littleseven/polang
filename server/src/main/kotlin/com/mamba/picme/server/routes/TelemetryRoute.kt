package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.TelemetryEvents
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

@Serializable
data class TelemetryBatch(val events: List<TelemetryRecord>)

@Serializable
data class TelemetryRecord(val type: String, val payload: JsonObject? = null)

/** 模型下载终态事件（payload 契约见 androidApp `TelemetryReporter`） */
const val TELEMETRY_TYPE_MODEL_DOWNLOAD = "model_download"

/** 必备模型全部就绪事件（转化漏斗「模型关通过」节点） */
const val TELEMETRY_TYPE_MODELS_READY = "models_ready"

/** 扫描会话事件（phase = started | completed | cancelled） */
const val TELEMETRY_TYPE_GALLERY_SCAN = "gallery_scan"

/** 单请求批量上限：遥测客户端缓冲上限 200，服务端留一倍余量防恶意大包打爆 SQLite */
private const val TELEMETRY_BATCH_MAX = 400

fun Routing.telemetryRoute() {
    post("/telemetry") {
        val batch = call.receive<TelemetryBatch>()
        val now = System.currentTimeMillis()
        // deviceId 只落独立列（漏斗按设备串事件），不进 payload——payload 契约保持无标识
        val deviceId = call.request.headers[DEVICE_ID_HEADER]?.takeIf { it.isNotBlank() }?.take(128)
        val accepted = batch.events.take(TELEMETRY_BATCH_MAX)
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            accepted.forEach { ev ->
                TelemetryEvents.insert {
                    it[TelemetryEvents.type] = ev.type.take(64)
                    it[TelemetryEvents.payloadJson] = ev.payload?.toString()
                    it[TelemetryEvents.deviceId] = deviceId
                    it[TelemetryEvents.createdAt] = now
                }
            }
        }
        call.respond(mapOf("accepted" to accepted.size))
    }
}
