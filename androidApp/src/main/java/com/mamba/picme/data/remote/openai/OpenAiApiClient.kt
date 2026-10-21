package com.mamba.picme.data.remote.openai

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.APP_CHANNEL_HEADER
import com.mamba.picme.data.remote.appChannelValue
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 API 客户端工厂
 *
 * 创建配置好的 Retrofit 实例和 OpenAiApiService。
 * 默认连接 PoLang Server (api.polang.net)，也支持 BYOK 直连其他 OpenAI 兼容接口。
 *
 * @param apiKey API Key（以 Bearer 方式认证，BYOK 模式使用）
 * @param baseUrl API 基础地址，默认 https://api.polang.net/
 * @param enableLogging 是否启用 HTTP 请求日志
 * @param gatewayToken X-App-Token 认证值（PoLang Server 共享密钥）
 */
class OpenAiApiClient(
    private val apiKey: String = "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val enableLogging: Boolean = false,
    private val gatewayToken: String? = null
) {

    companion object {
        private const val TAG = "OpenAi"
        private const val DEFAULT_BASE_URL = "https://api.polang.net/"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 30L
    }

    val service: OpenAiApiService by lazy { createService() }

    private fun createService(): OpenAiApiService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/json")

                // 用户自定义 API Key（如直接调用 OpenAI/Kimi 等）
                if (apiKey.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                // PoLang Server 认证头（X-App-Token，与 SCF / BuildConfig 共用同一个值）
                gatewayToken?.takeIf { it.isNotBlank() }?.let { token ->
                    requestBuilder.addHeader("X-App-Token", token)
                }

                requestBuilder.addHeader("X-Platform", "android")
                // 构建渠道头（debug 包额度豁免配套，见 AppChannel.kt）
                requestBuilder.addHeader(APP_CHANNEL_HEADER, appChannelValue)

                chain.proceed(requestBuilder.build())
            }

        if (enableLogging) {
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                Logger.d(TAG, message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            clientBuilder.addInterceptor(loggingInterceptor)
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(MoshiConverterFactory.create())
            .build()

        Logger.i(TAG, "OpenAiApiClient initialized, baseUrl=$baseUrl")
        return retrofit.create(OpenAiApiService::class.java)
    }


}
