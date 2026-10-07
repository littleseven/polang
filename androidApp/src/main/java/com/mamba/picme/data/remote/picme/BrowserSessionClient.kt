package com.mamba.picme.data.remote.picme

import com.mamba.picme.agent.core.capability.BrowserTransport
import com.mamba.picme.domain.browser.BrowserActionRequest
import com.mamba.picme.domain.browser.BrowserActionResult
import com.mamba.picme.domain.browser.BrowserFrameResult
import com.mamba.picme.domain.browser.BrowserStatus
import com.mamba.picme.domain.browser.BrowserUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * picme-server 云端浏览器网关客户端（/v1/browser/open|action|frame|close，shared [BrowserTransport] 的 Android 实现）。
 * 域名结果由 JSON status 承载（原样解析）；HTTP 非 2xx / IO 异常 → [BrowserUnavailableException]。
 * 网关 429（每日配额）包装为 quota_exceeded 结构体，能力层据此走配额降级文案。
 * token 经 [tokenProvider] 调用时读取（账号登出/未登录 = null → 不可用降级；browser 路由
 * 只认 X-App-Token，guest X-Device-Id 不放行——见 server BrowserRoute/ownerTokenHash）。
 */
class BrowserSessionClient(
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : BrowserTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS) // 略大于网关 30s
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-Platform", "android")
                    .build()
            )
        }
        .build()

    override suspend fun open(url: String, wantFrame: Boolean): BrowserActionResult =
        post("/v1/browser/open", JSONObject().put("url", url).put("wantFrame", wantFrame).toString())

    override suspend fun action(request: BrowserActionRequest): BrowserActionResult =
        post("/v1/browser/action", json.encodeToString(BrowserActionRequest.serializer(), request))

    override suspend fun frame(sessionId: String): BrowserFrameResult = withContext(Dispatchers.IO) {
        val request = authedBuilder("$baseUrl/v1/browser/frame?sessionId=$sessionId").get().build()
        val body = execute(request)
        json.decodeFromString(BrowserFrameResult.serializer(), body)
    }

    override suspend fun close(sessionId: String): BrowserActionResult =
        post("/v1/browser/close", JSONObject().put("sessionId", sessionId).toString())

    private suspend fun post(path: String, bodyJson: String): BrowserActionResult = withContext(Dispatchers.IO) {
        val request = authedBuilder("$baseUrl$path")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val body = execute(request)
        json.decodeFromString(BrowserActionResult.serializer(), body)
    }

    private fun authedBuilder(url: String): Request.Builder {
        val token = tokenProvider() ?: throw BrowserUnavailableException("not logged in")
        return Request.Builder()
            .url(url)
            .header("X-App-Token", token)
            .apply { deviceIdProvider()?.let { header("X-Device-Id", it) } }
    }

    private suspend fun execute(request: Request): String {
        try {
            val call = client.newCall(request)
            // 阻塞 execute() 不响应协程取消：协程结束（含 dispatch 层超时取消）时 cancel 打断阻塞读，
            // 避免 IO 线程+连接残留到 readTimeout 35s（对齐 ClaudeChatClient.chat 先例）
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == HTTP_TOO_MANY_REQUESTS) {
                    // 网关 429 = 每日配额耗尽（body 为 {"error":"quota_exceeded",...} JSON，
                    // 含引号不可裸插值）——经 JSONObject 转义包装成结构体，能力层走配额降级文案
                    return JSONObject()
                        .put("status", BrowserStatus.QUOTA_EXCEEDED)
                        .put("reason", body)
                        .toString()
                }
                if (!resp.isSuccessful) throw BrowserUnavailableException("HTTP ${resp.code}")
                return body
            }
        } catch (e: CancellationException) {
            // 钉住穿透不变式：BrowserSessionCapability 依赖 CE 原样上抛（CommandExecutor 超时取消），
            // 不许被下面的 catch-all 折叠成 BrowserUnavailableException
            throw e
        } catch (e: BrowserUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw BrowserUnavailableException(e.message ?: "network error", e)
        }
    }

    companion object {
        /** 与 PoLangAuthClient/ClaudeChatClient 同源（彼处为各自 private 常量，无法跨类引用）。 */
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
