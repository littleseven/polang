package com.mamba.picme.server.analytics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 上游 OpenAI 兼容响应里 usage 的三件套 + prompt 缓存命中（prompt_tokens_details.cached_tokens）。 */
data class TokenUsage(val prompt: Int, val completion: Int, val total: Int, val cached: Int? = null)

/** 模型单价：¥ / 1M tokens，输入/输出分开。用于成本估算。 */
data class Price(val inPerMillion: Double, val outPerMillion: Double)

/**
 * 从上游 chat completion 响应体解析 usage。
 * 响应 stream=false，正常必带 usage；错误/异常响应返回 null。
 */
fun fromUpstreamBytes(bytes: ByteArray): TokenUsage? {
    return try {
        val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        (obj["usage"] as? JsonObject)?.let(::parseUsage)
    } catch (e: Exception) {
        null
    }
}

/**
 * 从 SSE 流式响应（tee 下来的完整文本，KB 级）解析 usage。
 * 客户端以 stream_options.include_usage=true 请求时，上游在末尾单独一帧
 * `data: {"usage":{...}}` 给出三件套；从尾部向前扫描，取首个带 usage 的帧。
 * 上游没给 usage / 帧解析失败 → null（调用方记告警并跳过计费，不影响请求）。
 */
fun fromSseStream(text: String): TokenUsage? {
    val normalized = text.replace("\r\n", "\n")
    for (frame in normalized.split("\n\n").asReversed()) {
        for (line in frame.lines()) {
            val t = line.trim()
            if (!t.startsWith("data:")) continue
            val data = t.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val obj = try {
                Json.parseToJsonElement(data).jsonObject
            } catch (e: Exception) {
                continue
            }
            val usage = (obj["usage"] as? JsonObject)?.let(::parseUsage)
            if (usage != null) return usage
        }
    }
    return null
}

private fun parseUsage(usage: JsonObject): TokenUsage? {
    val prompt = usage.int("prompt_tokens")
    val completion = usage.int("completion_tokens")
    val total = usage.int("total_tokens")
    if (prompt == null && completion == null && total == null) return null
    val p = prompt ?: 0
    val c = completion ?: 0
    // prompt 前缀缓存命中数（DeepSeek context caching 等）：上游不给则为 null，
    // 记账与展示据此区分「可观测命中」与「不可观测」（2026-10-17 成本治理可观测性补洞）。
    val cached = (usage["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens")
    return TokenUsage(p, c, total ?: (p + c), cached)
}

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

/** 按 [prices] 计算单次调用估算成本（¥）；未知模型或无 usage → 0。 */
fun costCny(usage: TokenUsage?, model: String, prices: Map<String, Price>): Double {
    if (usage == null) return 0.0
    val price = prices[model] ?: return 0.0
    return usage.prompt / 1_000_000.0 * price.inPerMillion +
        usage.completion / 1_000_000.0 * price.outPerMillion
}

/**
 * 把成本（¥）格式化为展示串。DeepSeek 等低成本模型单次调用常在 ¥0.001 量级，
 * %.2f 会四舍五入成 "0.00" 让计费看起来没生效；故 sub-cent 用 4 位小数，其余 2 位。
 * AdminViews 所有成本展示都走这里。
 */
fun formatCostCny(d: Double): String = when {
    d == 0.0 -> "0.00"
    d < 0.01 -> "%.4f".format(d)
    else -> "%.2f".format(d)
}

/**
 * 内置默认单价（¥ / 1M tokens）。**估算用**，实际随上游调价漂移，
 * 生产可用环境变量 LLM_PRICES_JSON 覆盖（见 AppConfig）。
 *
 * DeepSeek 2026-08-26 调价同步（官方 pricing 页，现役 V4 家族）：
 * 取**峰时**单价（UTC 周一~五 01:00-04:00 / 06:00-10:00 ≈ 北京时间工作日
 * 09:00-12:00 / 14:00-18:00；谷时一律半价），输入按 cache-miss 计
 * （usage 未拆分 cache 命中），USD→CNY 按 6.72 折算：
 * - deepseek-v4-flash: $0.44/$1.32 → ¥2.96/¥8.87
 * - deepseek-v4-pro:   $1.32/$3.96 → ¥8.87/¥26.61
 * `deepseek-chat`/`deepseek/deepseek-chat`（Cloudflare 网关上游名）现由
 * V4-Flash 档承载，按 flash 单价计。
 */
fun defaultPrices(): Map<String, Price> = mapOf(
    "deepseek/deepseek-chat" to Price(2.96, 8.87),
    "deepseek-chat" to Price(2.96, 8.87),
    "deepseek-v4-flash" to Price(2.96, 8.87),
    "deepseek-v4-flash-202605" to Price(2.96, 8.87),
    "deepseek-v4-pro" to Price(8.87, 26.61),
    "deepseek-v4-pro-202606" to Price(8.87, 26.61),
    "kimi-k2.6" to Price(4.0, 12.0),
    "kimi-k2.7-code" to Price(4.0, 12.0),
)
