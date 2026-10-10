package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class PoLangAuthClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("X-Platform", "android")
                .build())
        }
        .build()

    private val jsonMedia = "application/json".toMediaType()

    /**
     * 发送邮箱验证码。
     *
     * @param lang BCP-47 语言标签，决定验证码邮件语言；默认取 App 当前语言
     *             （MainActivity.setLocale 已同步 Locale.setDefault）。
     */
    suspend fun sendVerificationCode(
        email: String,
        lang: String = Locale.getDefault().toLanguageTag(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("email", email)
                .put("lang", lang)
                .toString()
            val req = Request.Builder()
                .url("$baseUrl/auth/email/send")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw PoLangAuthException(resp.code, errorBody(resp.body?.string()))
            }
        }
    }

    suspend fun verifyCode(email: String, code: String): Result<AuthResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("email", email)
                .put("code", code)
                .toString()
            val req = Request.Builder()
                .url("$baseUrl/auth/email/verify")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw PoLangAuthException(resp.code, errorBody(respBody))
            }
            val json = JSONObject(respBody)
            AuthResult(
                token = json.getString("token"),
                llmCallsUsed = json.optInt("llmCallsUsed", 0),
                llmCallsLimit = json.optInt("llmCallsLimit", 100),
            )
        }
    }

    suspend fun getQuota(token: String): Result<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/auth/quota")
                .header("X-App-Token", token)
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw PoLangAuthException(resp.code, errorBody(respBody))
            }
            val json = JSONObject(respBody)
            QuotaInfo(
                email = json.getString("email"),
                llmCallsUsed = json.optInt("llmCallsUsed", 0),
                llmCallsLimit = json.optInt("llmCallsLimit", 100),
            )
        }
    }

    suspend fun deleteAccount(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/auth/account")
                .header("X-App-Token", token)
                .delete()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw PoLangAuthException(resp.code, errorBody(resp.body?.string()))
            }
        }
    }

    suspend fun clearGuestData(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/guest/device")
                .header("X-Device-Id", deviceId)
                .delete()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw PoLangAuthException(resp.code, errorBody(resp.body?.string()))
            }
        }
    }

    private fun errorBody(raw: String?): String {
        return try {
            JSONObject(raw ?: "").optString("error", "unknown_error")
        } catch (_: Exception) {
            "unknown_error"
        }
    }

    data class AuthResult(
        val token: String,
        val llmCallsUsed: Int,
        val llmCallsLimit: Int,
    )

    data class QuotaInfo(
        val email: String,
        val llmCallsUsed: Int,
        val llmCallsLimit: Int,
    )

    class PoLangAuthException(val code: Int, val errorType: String) : Exception("HTTP $code: $errorType")

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
