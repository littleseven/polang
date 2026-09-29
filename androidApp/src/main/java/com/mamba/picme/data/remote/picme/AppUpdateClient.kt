package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OTA 最新构建信息（GET /api/app/latest?channel=debug|release）。
 */
data class AppLatestInfo(
    val available: Boolean,
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sizeBytes: Long,
    val changelog: String,
    val updatedAt: String,
)

/**
 * OTA 版本检查客户端：公开免鉴权端点，仅拉取构建元数据。
 */
class AppUpdateClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取指定渠道的最新构建信息。网络/解析失败返回 Result.failure，
     * 调用方静默降级（更新检查永不影响主流程）。
     */
    suspend fun fetchLatest(channel: String): Result<AppLatestInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/app/latest?channel=$channel")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val json = JSONObject(respBody)
            AppLatestInfo(
                available = json.optBoolean("available", false),
                versionCode = json.optLong("versionCode", 0),
                versionName = json.optString("versionName", ""),
                url = json.optString("url", ""),
                sizeBytes = json.optLong("sizeBytes", 0),
                changelog = json.optString("changelog", ""),
                updatedAt = json.optString("updatedAt", ""),
            )
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
