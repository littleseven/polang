package com.mamba.picme.data.remote.picme

import com.mamba.picme.agent.core.capability.WebSearchTransport
import com.mamba.picme.agent.core.capability.WebSearchUnavailableException
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

/**
 * picme-server 网页搜索网关客户端（/v1/web-search，shared [WebSearchTransport] 的 Android 实现）。
 * 服务端持 DeepSeek key 调 Search API 并完成配图 URL 提取/瘦身，本类只做鉴权转发与错误映射。
 * HTTP 401/503/502 → [WebSearchUnavailableException]（能力层据此回结构化降级文本）。
 * token 经 [tokenProvider] 调用时读取；未登录时仅带 X-Device-Id（server 对 LLM 系路径放行访客）。
 */
class WebSearchClient(
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : WebSearchTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-Platform", "android")
                    .addHeader(APP_CHANNEL_HEADER, appChannelValue)
                    .build()
            )
        }
        .build()

    override suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().put("query", query).put("count", DEFAULT_COUNT).toString()
        val request = authedBuilder("$baseUrl/v1/web-search")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            val call = client.newCall(request)
            // 阻塞 execute() 不响应协程取消：协程结束时 cancel 打断阻塞读（对齐 BrowserSessionClient 先例）
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> body
                    resp.code == HTTP_TOO_MANY_REQUESTS ->
                        throw WebSearchUnavailableException("rate_limited")
                    else ->
                        throw WebSearchUnavailableException("HTTP ${resp.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebSearchUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw WebSearchUnavailableException(e.message ?: "network error", e)
        }
    }

    private fun authedBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .apply { tokenProvider()?.let { header("X-App-Token", it) } }
            .apply { deviceIdProvider()?.let { header("X-Device-Id", it) } }

    companion object {
        /** 与 BrowserSessionClient/PoLangAuthClient 同源。 */
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_COUNT = 5
    }
}
