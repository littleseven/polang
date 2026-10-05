package com.mamba.picme.server.config

import com.mamba.picme.server.analytics.Price
import com.mamba.picme.server.analytics.defaultPrices
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class AppConfig(
    val host: String,
    val port: Int,
    val dbPath: String,
    // Auth
    val freeLlmQuota: Int,
    val guestLlmQuota: Int,
    // LLM proxy
    val cloudflareAigUrl: String,
    val cloudflareAigToken: String,
    val tokenhubUrl: String,
    val tokenhubApiToken: String,
    val forceProvider: String,
    val maxTokensCap: Int,
    // Rate limit
    val rateLimitPerMin: Int,
    // Email
    val resendApiKey: String,
    val emailFrom: String,
    // COS
    val cosSecretId: String,
    val cosSecretKey: String,
    val cosRegion: String,
    val cosBucket: String,
    val cosPresignTtlMin: Int,
    // APK debug 轨国内镜像（北京轻量裸 IP + token 路径；空 = 全部走 cos.polang.net）
    val apkDebugPublicBase: String,
    // Admin 后台
    val adminToken: String,
    val llmPrices: Map<String, Price>,
    // GitHub issue 同步（用户上报问题）
    val githubToken: String,
    val githubIssueRepo: String,
) {
    companion object {
        fun load(): AppConfig = AppConfig(
            host = env("HOST", "127.0.0.1"),
            port = envInt("PORT", 8080),
            dbPath = env("DB_PATH", "picme.db"),
            freeLlmQuota = envInt("FREE_LLM_QUOTA", 1000),
            guestLlmQuota = envInt("GUEST_LLM_QUOTA", 100),
            // Cloudflare AI Gateway (DeepSeek)
            cloudflareAigUrl = env(
                "CLOUDFLARE_AIG_URL",
                "https://gateway.ai.cloudflare.com/v1/a7656feec717409a19fa5217f0f7b2f9/picme/compat/chat/completions",
            ),
            cloudflareAigToken = env("CLOUDFLARE_AIG_TOKEN", ""),
            // Tencent TokenHub
            tokenhubUrl = env("TOKENHUB_URL", "https://tokenhub.tencentmaas.com/v1/chat/completions"),
            tokenhubApiToken = env("TOKENHUB_API_TOKEN", ""),
            forceProvider = env("FORCE_PROVIDER", ""),
            maxTokensCap = envInt("MAX_TOKENS_CAP", 4096),
            rateLimitPerMin = envInt("RATE_LIMIT_PER_MIN", 20),
            // Email (Resend)
            resendApiKey = env("RESEND_API_KEY", ""),
            emailFrom = env("EMAIL_FROM", "noreply@polang.net"),
            // COS
            cosSecretId = env("COS_SECRET_ID", ""),
            cosSecretKey = env("COS_SECRET_KEY", ""),
            cosRegion = env("COS_REGION", "ap-hongkong"),
            cosBucket = env("COS_BUCKET", ""),
            cosPresignTtlMin = envInt("COS_PRESIGN_TTL_MIN", 60),
            apkDebugPublicBase = env("APK_DEBUG_PUBLIC_BASE", ""),
            // Admin
            adminToken = env("ADMIN_TOKEN", ""),
            llmPrices = parsePrices(System.getenv("LLM_PRICES_JSON")),
            // GitHub issue sync
            githubToken = env("GITHUB_TOKEN", ""),
            githubIssueRepo = env("GITHUB_ISSUE_REPO", "littleseven/polang"),
        )

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

        private fun envInt(key: String, default: Int): Int =
            System.getenv(key)?.toIntOrNull() ?: default

        /**
         * 解析 LLM_PRICES_JSON 覆盖默认单价。格式 {"model":{"in":1.5,"out":6.0}}，合并覆盖默认。
         * null/空/解析失败 → 走 defaultPrices()。
         */
        internal fun parsePrices(json: String?): Map<String, Price> {
            if (json.isNullOrBlank()) return defaultPrices()
            return try {
                val parsed = Json.parseToJsonElement(json).jsonObject
                defaultPrices().toMutableMap().apply {
                    parsed.forEach { (model, v) ->
                        val obj = v as? JsonObject ?: return@forEach
                        val inn = (obj["in"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        val out = (obj["out"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        if (inn != null && out != null) this[model] = Price(inn, out)
                    }
                }.toMap()
            } catch (e: Exception) {
                defaultPrices()
            }
        }
    }
}
