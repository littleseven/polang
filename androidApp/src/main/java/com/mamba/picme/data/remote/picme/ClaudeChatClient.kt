package com.mamba.picme.data.remote.picme

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

/** SSE 文本 → ClaudeEvent 解析（spec §6）。纯逻辑，单测覆盖。 */
object ClaudeSseParser {
    fun parse(sse: String): List<ClaudeEvent> {
        val events = mutableListOf<ClaudeEvent>()
        val blocks = sse.split("\n\n")
        for (block in blocks) {
            var type: String? = null
            var data: String? = null
            for (line in block.split("\n")) {
                when {
                    line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data = line.removePrefix("data:").trim()
                }
            }
            val t = type ?: continue
            val json = try {
                JSONObject(data ?: "{}")
            } catch (_: Throwable) {
                continue
            }
            val ev = when (t) {
                "session" -> json.optString("sid").takeIf { it.isNotBlank() }?.let { ClaudeEvent.Session(it) }
                "assistant_text" -> ClaudeEvent.AssistantText(json.optString("delta"))
                "tool_use" -> ClaudeEvent.ToolUse(
                    json.optString("tool"),
                    json.optJSONObject("input") ?: JSONObject(),
                )
                "tool_result" -> ClaudeEvent.ToolResult(json.optBoolean("ok"), json.optString("summary"))
                "file_change" -> ClaudeEvent.FileChange(json.optString("path"), json.optString("action"))
                "cost" -> ClaudeEvent.Cost(json.optInt("turns", 0), json.optInt("cents", 0))
                "error" -> ClaudeEvent.Error(
                    message = json.optString("message"),
                    truncated = json.optBoolean("truncated", false),
                    reason = json.optString("reason").takeIf { it.isNotBlank() },
                )
                // requestId 为空无法回传 tool result → 丢弃（对齐 session 分支 blank sid 先例）
                "app_tool_request" -> json.optString("requestId").takeIf { it.isNotBlank() }?.let { rid ->
                    ClaudeEvent.AppToolRequest(
                        rid,
                        json.optString("tool"),
                        json.optJSONObject("args") ?: JSONObject(),
                    )
                }
                "done" -> ClaudeEvent.Done(
                    turns = json.optInt("turns", 0),
                    truncated = json.optBoolean("truncated", false),
                    reason = json.optString("reason").takeIf { it.isNotBlank() },
                )
                else -> null
            }
            ev?.let { events.add(it) }
        }
        return events
    }
}

/**
 * claude-tunnel chat 客户端。OkHttp + X-App-Token + org.json，
 * 加 SSE 流式读：POST /v1/claude-chat，逐 chunk 累积，按双换行切事件，回调 onEvent。
 */
class ClaudeChatClient(private val baseUrl: String = DEFAULT_BASE_URL) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接，不超时（靠网关 CT_PHASE_TIMEOUT 兜底）
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("X-Platform", "android")
                .build())
        }
        .build()
    /** 交付用（普通 JSON 请求，非 SSE）：有 readTimeout，避免 gateway push 挂起时 app 无限阻塞。 */
    private val deliverClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("X-Platform", "android")
                .build())
        }
        .build()
    private val jsonMedia = "application/json".toMediaType()

    /** 流式 chat：onEvent 在 IO 线程回调每个 §6 事件；返回 session 事件给的 sid（多轮用）。 */
    suspend fun chat(
        token: String,
        message: String,
        sid: String? = null,
        onEvent: (ClaudeEvent) -> Unit,
    ): Result<String?> = withContext(Dispatchers.IO) {
        // 不用 runCatching：协程取消必须穿透（否则被折叠成 Result.failure，调用方会当成
        // 网络失败产「推理失败」气泡）；任务卡「停止」依赖取消沿调用链传播（2026-09-27 H2）
        try {
            val body = JSONObject().put("message", message).also {
                sid?.takeIf { s -> s.isNotBlank() }?.let { s -> it.put("sid", s) }
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/claude-chat")
                .header("X-App-Token", token)
                .post(body.toRequestBody(jsonMedia))
                .build()
            val call = client.newCall(req)
            // readTimeout=0 的 SSE 阻塞读不响应协程取消：协程结束（含取消）时 cancel 打断 source.read()
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            var sessionSid: String? = null
            // use：无论成败都关闭 Response，让连接归还连接池（对齐 postToolResult 先例）
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
                }
                val source = resp.body?.byteStream() ?: throw RuntimeException("empty body")
                val sb = StringBuilder()
                val buf = ByteArray(4 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n == -1) break
                    sb.append(String(buf, 0, n))
                    while (true) {
                        val idx = sb.indexOf("\n\n")
                        if (idx == -1) break
                        val block = sb.substring(0, idx)
                        sb.delete(0, idx + 2)
                        for (ev in ClaudeSseParser.parse(block + "\n\n")) {
                            if (ev is ClaudeEvent.Session) sessionSid = ev.sid
                            onEvent(ev)
                        }
                    }
                }
            }
            Result.success(sessionSid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // 显式复刻 runCatching 语义（唯一差异：取消穿透），宽捕获即其本意
            Result.failure(e)
        }
    }

    /**
     * 交付当前 session 的改动（spec §8）：POST /v1/claude-deliver → server 反代到网关 /deliver。
     * gateway MVP 仅 push：在 workdir commit + push `claude-chat/<sid>`，返回 {ok, branch}。
     * [mode] 透传供网关二期 pr/auto 使用（当前网关忽略）。
     */
    suspend fun deliver(token: String, sid: String, mode: String = "push"): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("sid", sid).put("mode", mode).toString()
                val req = Request.Builder()
                    .url("$baseUrl/v1/claude-deliver")
                    .header("X-App-Token", token)
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                val resp = deliverClient.newCall(req).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
                JSONObject(text)
            }
        }

    /**
     * 查询 AI 工程师模式可用性与代码交付权限：GET /v1/claude-engineer/available。
     * 已认证账号恒为 available；canDeliver 取决于 ai_engineer_whitelist。
     */
    suspend fun engineerAvailability(token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/claude-engineer/available")
                .header("X-App-Token", token)
                .get()
                .build()
            val resp = deliverClient.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
            JSONObject(text).optBoolean("canDeliver", false)
        }
    }

    /**
     * App tool 结果回传（spec §5）：POST /v1/claude-tool-result → server 反代网关 /tool-result。
     * [payload] 为 AppToolExecutor 采集+脱敏后的 JSON。
     */
    suspend fun postToolResult(token: String, requestId: String, payload: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("requestId", requestId)
                    .put("payload", payload)
                    .toString()
                val req = Request.Builder()
                    .url("$baseUrl/v1/claude-tool-result")
                    .header("X-App-Token", token)
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                val resp = deliverClient.newCall(req).execute()
                // use：无论成败都 close，让连接归还连接池（错误路径 body.string() 本身会关闭）
                resp.use {
                    if (!it.isSuccessful) {
                        throw RuntimeException("HTTP ${it.code}: ${it.body?.string().orEmpty()}")
                    }
                }
                Unit
            }
        }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
