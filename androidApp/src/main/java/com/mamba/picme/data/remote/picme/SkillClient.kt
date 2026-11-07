package com.mamba.picme.data.remote.picme

import com.mamba.picme.agent.core.skill.SkillTransport
import com.mamba.picme.data.remote.APP_CHANNEL_HEADER
import com.mamba.picme.data.remote.appChannelValue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * picme-server Skill 中心客户端（/v1/skills，shared [SkillTransport] 的 Android 实现，
 * 2026-11-08 端侧 load_skill 执行器）。
 *
 * 服务端直读 `server/skills/` 磁盘（生产 SSOT），本类只做鉴权转发与「失败即 null」
 * 的错误映射（SkillRepository 据此回落本地缓存，stale-while-revalidate）。
 * token 经 [tokenProvider] 调用时读取；未登录时仅带 X-Device-Id（skill 是全局公共资产，
 * server 对 LLM 系路径放行访客，与 /v1/web-search 同口径）。
 */
class SkillClient(
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : SkillTransport {

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

    override suspend fun fetchManifestJson(): String? = get("$baseUrl/v1/skills")

    override suspend fun fetchSkillJson(name: String): String? = get("$baseUrl/v1/skills/$name")

    /** GET 并返回响应 body；非 2xx/网络失败/空 body 一律 null（调用方回落缓存）。 */
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val request = authedBuilder(url).get().build()
        try {
            val call = client.newCall(request)
            // 阻塞 execute() 不响应协程取消：协程结束时 cancel 打断阻塞读（对齐 WebSearchClient 先例）
            currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            call.execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun authedBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .apply { tokenProvider()?.let { header("X-App-Token", it) } }
            .apply { deviceIdProvider()?.let { header("X-Device-Id", it) } }

    companion object {
        /** 与 WebSearchClient/BrowserSessionClient/PoLangAuthClient 同源。 */
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
