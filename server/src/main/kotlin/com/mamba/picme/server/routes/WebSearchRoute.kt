package com.mamba.picme.server.routes

import com.mamba.picme.server.analytics.UsageRecorder
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.websearch.SearchExecutor
import com.mamba.picme.server.websearch.SearchOutcome
import com.mamba.picme.server.websearch.WebSearchService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `POST /v1/web-search`——主聊天 `web_search` 工具的服务端出口（DeepSeek Search 代理）。
 *
 * 鉴权与 /v1/chat/completions 同口径（X-App-Token 账号 或 X-Device-Id 访客，
 * 访客放行在 Application 拦截器 isLlmPath 分支已含本路径）；限流复用全局 per-IP
 * 令牌桶。每次成功搜索在 llm_call_log 记一行（model="deepseek-web-search"），
 * 账号维度可在管理后台调用流水观测。
 *
 * [executor] 为 null（未配置 DEEPSEEK_SEARCH_API_KEY）时 503 降级，端侧工具回不可用文案。
 */
fun Route.webSearchRoute(
    executor: SearchExecutor?,
    rateLimiter: RateLimiter?,
) {
    post("/v1/web-search") {
        val clientIp = call.request.headers["X-Forwarded-For"]?.substringBefore(",")
            ?: call.request.headers["X-Real-IP"]
            ?: "unknown"
        if (rateLimiter != null && !rateLimiter.allow(clientIp)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded"))
            return@post
        }

        val tokenHash = call.attributes.getOrNull(TokenHashKey)
        val deviceId = call.attributes.getOrNull(DeviceIdKey)
        val platform = call.attributes.getOrNull(PlatformKey)
        if (tokenHash == null && deviceId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }

        val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
        val query = (body["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty() || query.length > MAX_QUERY_CHARS) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "bad_request", "message" to "query must be 1..$MAX_QUERY_CHARS chars"),
            )
            return@post
        }
        val count = (body["count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: DEFAULT_COUNT

        val exec = executor
            ?: return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "web_search_unavailable", "reason" to "not_configured"),
            )

        val started = System.currentTimeMillis()
        when (val outcome = exec.search(query, count)) {
            is SearchOutcome.Success -> {
                tokenHash?.let { AccountService.idForTokenHash(it) }?.let { accountId ->
                    UsageRecorder.log(
                        accountId = accountId,
                        model = USAGE_MODEL,
                        provider = "deepseek",
                        usage = null,
                        respBytes = 0,
                        status = "ok",
                        latencyMs = (System.currentTimeMillis() - started).toInt(),
                        prices = emptyMap(),
                        deviceId = deviceId,
                        platform = platform,
                    )
                }
                call.respond(WebSearchService.toClientJson(outcome.results))
            }
            is SearchOutcome.Unavailable -> call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to "web_search_unavailable", "reason" to outcome.reason),
            )
        }
    }
}

/** 与 DeepSeek Search API query 上限一致（1~128 Unicode 字符）。 */
private const val MAX_QUERY_CHARS = 128
private const val DEFAULT_COUNT = 5

/** llm_call_log 记账用模型名（usage=null、prices 空 → cost 0；按次成本见官方 5 元/千次口径）。 */
internal const val USAGE_MODEL = "deepseek-web-search"
