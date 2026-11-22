package com.mamba.picme.data.telemetry

import android.content.Context
import android.content.SharedPreferences
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.common.NetworkUtils
import com.mamba.picme.data.remote.picme.TelemetryClient
import com.mamba.picme.data.remote.picme.TelemetrySender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 遥测上报器：AI 相册转化漏斗（装机 → 模型下载齐 → 扫描启动 → 扫描完成）的事件采集。
 *
 * 事件类型（与 server TelemetryRoute / AdminQueries 同源契约，改动须双向同步）：
 * - [EVENT_TYPE_MODEL_DOWNLOAD]：单模型下载终态（completed/failed/cancelled）
 * - [EVENT_TYPE_MODELS_READY]：7 个必备模型首次全部就绪（每安装只报一次）
 * - [EVENT_TYPE_GALLERY_SCAN]：扫描会话 started/completed/cancelled
 *
 * 设计要点：
 * - **本地缓冲后上报**：下载/扫描失败往往发生在网络差的环境，失败事件恰恰是当下最发不出去的——
 *   直发会让「失败率」系统性偏低。事件先落 JSONL 缓冲，flush 成功才清除。
 * - **隐私最小化**：payload 只有模型 id/结果/字节数/耗时/网络类型/地区（Locale.country 粗粒度）
 *   /扫描计数等运行指标；不含任何用户内容、文件路径、账号标识。设备关联走 X-Device-Id 头
 *   （服务端漏斗分设备聚合用），payload 内不放。
 */
class TelemetryReporter(
    private val appContext: Context,
    private val deviceIdProvider: () -> String?,
    private val appVersion: String,
    private val channel: String,
    private val client: TelemetrySender = TelemetryClient(),
    private val buffer: TelemetryEventBuffer = TelemetryEventBuffer(
        File(appContext.filesDir, "telemetry/pending_events.jsonl"),
    ),
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("telemetry_flags", Context.MODE_PRIVATE),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val networkTypeProvider: () -> String = { defaultNetworkType(appContext) },
    private val networkAvailableProvider: () -> Boolean = { NetworkUtils.isNetworkAvailable(appContext) },
    private val regionProvider: () -> String = {
        java.util.Locale.getDefault().country.uppercase().ifBlank { "unknown" }
    },
    private val timeZoneProvider: () -> String = { java.util.TimeZone.getDefault().id },
) {

    private val flushing = AtomicBoolean(false)

    // ── 模型下载事件 ──────────────────────────────────────────

    fun reportModelCompleted(modelId: String, bytes: Long, durationMs: Long, resumed: Boolean) =
        reportModelTerminal(modelId, "completed", null, bytes, durationMs, resumed)

    fun reportModelFailed(modelId: String, bytes: Long, durationMs: Long, errorClass: String, resumed: Boolean) =
        reportModelTerminal(modelId, "failed", errorClass, bytes, durationMs, resumed)

    fun reportModelCancelled(modelId: String, bytes: Long, durationMs: Long, resumed: Boolean) =
        reportModelTerminal(modelId, "cancelled", null, bytes, durationMs, resumed)

    private fun reportModelTerminal(
        modelId: String,
        outcome: String,
        errorClass: String?,
        bytes: Long,
        durationMs: Long,
        resumed: Boolean,
    ) = recordEvent(EVENT_TYPE_MODEL_DOWNLOAD, JSONObject()
        .put("modelId", modelId)
        .put("outcome", outcome)
        .put("bytes", bytes)
        .put("durationMs", durationMs)
        .put("resumed", resumed)
        .apply { if (errorClass != null) put("errorClass", errorClass) })

    /** 必备模型首次全部就绪（漏斗「过下载关」节点）；prefs 旗标保证每安装只报一次 */
    fun reportModelsReadyOnce(requiredCount: Int) {
        if (prefs.getBoolean(KEY_MODELS_READY_REPORTED, false)) return
        prefs.edit().putBoolean(KEY_MODELS_READY_REPORTED, true).apply()
        recordEvent(EVENT_TYPE_MODELS_READY, JSONObject().put("requiredCount", requiredCount))
    }

    // ── 扫描会话事件 ──────────────────────────────────────────

    fun reportScanStarted(total: Int) =
        recordEvent(EVENT_TYPE_GALLERY_SCAN, JSONObject().put("phase", "started").put("total", total))

    fun reportScanTerminal(phase: String, processed: Int, total: Int, failed: Int, durationMs: Long) =
        recordEvent(EVENT_TYPE_GALLERY_SCAN, JSONObject()
            .put("phase", phase)
            .put("processed", processed)
            .put("total", total)
            .put("failed", failed)
            .put("durationMs", durationMs))

    // ── 通用骨架 ──────────────────────────────────────────────

    /** 记录事件：业务 payload + 公共上下文字段（地区/网络/版本），落缓冲并异步 flush */
    private fun recordEvent(type: String, payload: JSONObject) {
        runCatching {
            payload
                .put("networkType", networkTypeProvider())
                .put("region", regionProvider())
                .put("tz", timeZoneProvider())
                .put("appVersion", appVersion)
                .put("channel", channel)
                .put("platform", "android")
            buffer.append(JSONObject().put("type", type).put("payload", payload).toString())
        }.onFailure { Logger.w(TAG, "buffer telemetry event failed", it) }
        scope.launch { flushPending() }
    }

    /**
     * 上报缓冲中的事件。网络不可用或无 deviceId（服务端访客闸前提）时保留事件待下次。
     * App 启动时由 PoLangApplication 主动调一次，回收上次运行残留的事件。
     */
    suspend fun flushPending() {
        if (!flushing.compareAndSet(false, true)) return
        try {
            if (!networkAvailableProvider()) return
            val deviceId = deviceIdProvider()?.takeIf { it.isNotBlank() } ?: return
            val pending = buffer.readAll()
            if (pending.isEmpty()) return
            val chunk = pending.take(FLUSH_BATCH_SIZE)
            client.send(chunk, deviceId)
                .onSuccess { buffer.removeFirst(chunk.size) }
                .onFailure { Logger.w(TAG, "telemetry flush failed, keep ${pending.size} events", it) }
        } finally {
            flushing.set(false)
        }
    }

    companion object {
        private const val TAG = "TelemetryReporter"

        const val EVENT_TYPE_MODEL_DOWNLOAD = "model_download"
        const val EVENT_TYPE_MODELS_READY = "models_ready"
        const val EVENT_TYPE_GALLERY_SCAN = "gallery_scan"

        private const val KEY_MODELS_READY_REPORTED = "models_ready_reported"

        /** 单次 flush 最大批量（服务端单请求上限 400，留足余量） */
        private const val FLUSH_BATCH_SIZE = 50

        private fun defaultNetworkType(context: Context): String = when {
            NetworkUtils.isWifiConnected(context) -> "wifi"
            NetworkUtils.isCellularConnected(context) -> "cellular"
            else -> "other"
        }
    }
}

/**
 * 遥测事件 JSONL 文件缓冲：append/readAll/removeFirst，容量上限 [maxEvents]，超出丢最旧。
 * 所有方法 synchronized；单文件重写量级极小（200 条 × ~300B），无性能顾虑。
 */
class TelemetryEventBuffer(
    private val file: File,
    private val maxEvents: Int = 200,
) {

    @Synchronized
    fun append(line: String) {
        file.parentFile?.mkdirs()
        val lines = readLines().toMutableList()
        lines.add(line)
        val trimmed = if (lines.size > maxEvents) lines.subList(lines.size - maxEvents, lines.size) else lines
        writeLines(trimmed)
    }

    @Synchronized
    fun readAll(): List<String> = readLines()

    @Synchronized
    fun removeFirst(n: Int) {
        val lines = readLines()
        writeLines(if (n >= lines.size) emptyList() else lines.subList(n, lines.size))
    }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun writeLines(lines: List<String>) {
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
