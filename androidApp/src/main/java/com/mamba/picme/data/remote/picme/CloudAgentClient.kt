package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.APP_CHANNEL_HEADER
import com.mamba.picme.data.remote.appChannelValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 云端 Agent SSE 帧（spec §3：M1 event/done/error + M2 新增 tool_request）。 */
sealed interface CloudFrame {
    /** M1 事件帧：event 为 agent_event 行 JSON（id/type/source/role/payload/created_at）。 */
    data class Event(val event: JSONObject) : CloudFrame

    /** 工具桥下行帧（传输层信号，不落事件流）。 */
    data class ToolRequest(
        val requestId: String,
        val tool: String,
        val args: JSONObject,
        val timeoutMs: Long,
    ) : CloudFrame

    data class Done(val sessionStatus: String) : CloudFrame
    data class Error(val message: String) : CloudFrame
}

/** SSE block → CloudFrame 解析。纯逻辑，无法识别一律 null 容错跳过（ClaudeSseParser 先例）。 */
object CloudSseParser {
    fun parseBlock(block: String): CloudFrame? {
        val data = block.lines().firstOrNull { line -> line.startsWith("data:") }
            ?.removePrefix("data:")?.trim() ?: return null
        val json = try {
            JSONObject(data)
        } catch (_: Throwable) {
            return null
        }
        return when (json.optString("kind")) {
            "event" -> json.optJSONObject("event")?.let { event -> CloudFrame.Event(event) }
            "tool_request" -> json.optString("request_id").takeIf { rid -> rid.isNotBlank() }?.let { rid ->
                CloudFrame.ToolRequest(
                    requestId = rid,
                    tool = json.optString("tool"),
                    args = json.optJSONObject("args") ?: JSONObject(),
                    timeoutMs = json.optLong("timeout_ms", 120_000L),
                )
            }
            "done" -> CloudFrame.Done(json.optString("session_status"))
            "error" -> CloudFrame.Error(json.optString("message"))
            else -> null
        }
    }
}

/** 云端 Agent 客户端抽象（ViewModel 单测 fake 注入点）。 */
interface CloudAgentApi {
    suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String): Result<JSONObject>
    suspend fun getEvents(token: String, sessionId: String, after: Long = 0L, limit: Int = 500): Result<List<JSONObject>>
    suspend fun finish(token: String, sessionId: String): Result<Unit>
    suspend fun postMessage(
        token: String,
        sessionId: String,
        content: String,
        byokApiKey: String?,
        genUiFormat: String,
        onFrame: (CloudFrame) -> Unit,
    ): Result<Unit>
    suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit>
}

/**
 * 云端 Agent 客户端（ADR-018 决策 3/4，M2）。OkHttp + X-App-Token + org.json（ClaudeChatClient 同款）。
 * BYOK 零留存锚点：[byokApiKey] 只在 [postMessage] 的 X-User-Api-Key 头出现——
 * 不进 URL、不进 body、不进日志；其余方法连参数都没有。
 *
 * [deviceIdProvider]：同步快照 provider（BrowserSessionClient 先例），未注册访客试用/调试页
 * 免登录双通道共用 X-Device-Id 头；未预热完成前返回 null 仅少带头，不影响已登录主链路。
 */
class CloudAgentClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val deviceIdProvider: () -> String? = { null },
) : CloudAgentApi {

    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接不超时（服务端 120s 工具超时兜底）
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-Platform", "android")
                    .addHeader(APP_CHANNEL_HEADER, appChannelValue)
                    .apply { deviceIdProvider()?.let { id -> addHeader("X-Device-Id", id) } }
                    .build(),
            )
        }
        .build()
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-Platform", "android")
                    .addHeader(APP_CHANNEL_HEADER, appChannelValue)
                    .apply { deviceIdProvider()?.let { id -> addHeader("X-Device-Id", id) } }
                    .build(),
            )
        }
        .build()
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("channel", channel)
                    .put("model", model)
                    .put("byok_base_url", byokBaseUrl)
                    .toString()
                val text = post("$baseUrl/agent/sessions", token, body)
                JSONObject(text)
            }
        }

    override suspend fun getEvents(token: String, sessionId: String, after: Long, limit: Int): Result<List<JSONObject>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/agent/sessions/$sessionId/events?after=$after&limit=$limit")
                    .header("X-App-Token", token)
                    .get()
                    .build()
                val text = apiClient.newCall(req).execute().use { resp ->
                    val t = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $t")
                    t
                }
                val arr = JSONObject(text).getJSONArray("events")
                (0 until arr.length()).map { idx -> arr.getJSONObject(idx) }
            }
        }

    override suspend fun finish(token: String, sessionId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                post("$baseUrl/agent/sessions/$sessionId/finish", token, "{}")
                Unit
            }
        }

    override suspend fun postMessage(
        token: String,
        sessionId: String,
        content: String,
        byokApiKey: String?,
        genUiFormat: String,
        onFrame: (CloudFrame) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 不用 runCatching：协程取消必须穿透（停止按钮语义，ClaudeChatClient.chat 先例）
        try {
            val body = JSONObject().put("content", content).toString()
            Logger.d("CloudAgent", "postMessage session=${sessionId.take(8)} genUiFormat=$genUiFormat")
            val req = Request.Builder()
                .url("$baseUrl/agent/sessions/$sessionId/messages")
                .header("X-App-Token", token)
                .apply { if (!byokApiKey.isNullOrBlank()) header("X-User-Api-Key", byokApiKey) }
                // GenUI 线格式分流头（openui P2）：server 按头二选一注册 render_ui/render_ui_openui
                .apply { if (genUiFormat.isNotBlank()) header("X-GenUI-Format", genUiFormat) }
                .post(body.toRequestBody(jsonMedia))
                .build()
            val call = sseClient.newCall(req)
            // readTimeout=0 的 SSE 阻塞读不响应协程取消：协程结束时 cancel 打断 source.read()
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
                }
                val source = resp.body?.byteStream() ?: throw RuntimeException("empty body")
                val sb = StringBuilder()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n == -1) break
                    sb.append(String(buf, 0, n))
                    while (true) {
                        val idx = sb.indexOf("\n\n")
                        if (idx == -1) break
                        val block = sb.substring(0, idx)
                        sb.delete(0, idx + 2)
                        CloudSseParser.parseBlock(block)?.let(onFrame)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("request_id", requestId)
                    .put("ok", ok)
                    .put("result", result)
                    .toString()
                post("$baseUrl/agent/sessions/$sessionId/tool-results", token, body)
                Unit
            }
        }

    private fun post(url: String, token: String, jsonBody: String): String {
        val req = Request.Builder()
            .url(url)
            .header("X-App-Token", token)
            .post(jsonBody.toRequestBody(jsonMedia))
            .build()
        return apiClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
            text
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
