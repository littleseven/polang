package com.mamba.picme.data.remote.picme

import com.mamba.picme.data.remote.APP_CHANNEL_HEADER
import com.mamba.picme.data.remote.appChannelValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 遥测上报客户端：批量事件 POST /telemetry（访客口径，X-Device-Id 鉴权）。
 *
 * 事件为 JSONL 缓冲读出的完整行（{"type":..., "payload":{...}}），本类只负责封装批量与发送；
 * 失败返回 Result.failure 由调用方保留事件待重试（遥测永不影响主流程）。
 */
/** 遥测发送抽象：便于测试替换（生产实现 = [TelemetryClient]） */
interface TelemetrySender {
    suspend fun send(eventLines: List<String>, deviceId: String): Result<Unit>
}

class TelemetryClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) : TelemetrySender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-Platform", "android")
                    .addHeader(APP_CHANNEL_HEADER, appChannelValue)
                    .build(),
            )
        }
        .build()

    /**
     * @param eventLines 每行一个完整事件 JSON（type + payload），原样组包
     * @param deviceId   访客设备标识（X-Device-Id），服务端鉴权用，不入事件体
     */
    override suspend fun send(eventLines: List<String>, deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val events = JSONArray()
            eventLines.forEach { events.put(org.json.JSONObject(it)) }
            val body = """{"events":$events}"""
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/telemetry")
                .addHeader("X-Device-Id", deviceId)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
            }
            Unit
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
