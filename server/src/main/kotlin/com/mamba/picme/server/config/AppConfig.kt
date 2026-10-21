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
    // Browser bridge（browserBridgeUrl 空 = 未配置，路由 503 降级 browser_unavailable）
    val browserBridgeUrl: String,
    val browserBridgeToken: String,
    val browserDailyQuota: Int,
    // debug 包额度豁免总闸（默认关）：开时 X-App-Channel: debug 请求跳过三处额度扣减
    // （/v1/chat/completions 账号+访客、/agent 网关回合、/v1/browser/open 每日配额），用量照记
    val debugQuotaBypass: Boolean = false,
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
            // Browser bridge
            browserBridgeUrl = env("BROWSER_BRIDGE_URL", ""),
            browserBridgeToken = env("BROWSER_BRIDGE_TOKEN", ""),
            browserDailyQuota = envInt("BROWSER_DAILY_QUOTA", 20),
            debugQuotaBypass = parseEnvBool(System.getenv("DEBUG_QUOTA_BYPASS")),
        )

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

        private fun envInt(key: String, default: Int): Int =
            System.getenv(key)?.toIntOrNull() ?: default

        /** "true"/"1"（大小写不敏感）视为开；null/其它 → false（豁免默认关，显式开启才生效）。 */
        internal fun parseEnvBool(raw: String?): Boolean =
            raw != null && (raw.equals("true", ignoreCase = true) || raw == "1")

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
