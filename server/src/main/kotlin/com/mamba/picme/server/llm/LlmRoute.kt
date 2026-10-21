package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.Price
import com.mamba.picme.server.analytics.UsageRecorder
import com.mamba.picme.server.analytics.fromSseStream
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.GuestService
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.routes.DeviceIdKey
import com.mamba.picme.server.routes.PlatformKey
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.routes.isDebugQuotaBypass
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

private val logger = LoggerFactory.getLogger("picme-llm")

fun Route.llmRoute(
    proxy: LlmProxy,
    rateLimiter: RateLimiter?,
    prices: Map<String, Price>,
) {
    listOf("/v1/chat/completions", "/chat/completions").forEach { path ->
        post(path) {
            val clientIp = call.request.clientIp()

            // 限流优先：命中限流前不做任何 DB / body 解析，避免洪水请求打满单连接 SQLite
            // （HikariCP maximumPoolSize=1）。命中限流直接 429，不写 llm_call_log——
            // 限流命中数见服务日志（CallLogging）；后台「blocked」统计仍含 blocked_quota。
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

            val body = call.receive<JsonObject>()
            val requestedModel = (body["model"] as? JsonPrimitive)?.content ?: ""
            val isGuest = tokenHash == null
            val accountId = tokenHash?.let { AccountService.idForTokenHash(it) }
            val guestLlmQuota = SettingsService.snapshot().guestLlmQuota
            // debug 包豁免（DEBUG_QUOTA_BYPASS + X-App-Channel: debug）：跳过扣减，用量照记；
            // 未扣减则错误回滚也须跳过（revert 与 increment 严格配对）
            val quotaBypass = call.isDebugQuotaBypass()

            // Quota check — account OR guest（各只增量一次）
            if (!quotaBypass) {
                if (isGuest) {
                    val guest = GuestService.checkAndIncrementQuota(deviceId!!, guestLlmQuota, platform)
                    if (!guest.allowed) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "quota_exceeded", "tier" to "guest", "message" to "guest quota used up"),
                        )
                        return@post
                    }
                } else if (!AccountService.checkAndIncrementQuota(tokenHash)) {
                    accountId?.let {
                        UsageRecorder.log(it, requestedModel, "", null, 0, "blocked_quota", null, prices, deviceId = deviceId, platform = platform)
                    }
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "quota_exceeded", "tier" to "account", "message" to "free quota used up"),
                    )
                    return@post
                }
            }

            val started = System.currentTimeMillis()
            val result = proxy.forward(clientIp, body)
            val latencyMs = (System.currentTimeMillis() - started).toInt()
            when (result) {
                is ProxyResult.Success -> {
                    accountId?.let {
                        UsageRecorder.log(
                            accountId = it,
                            model = result.model,
                            provider = result.provider,
                            usage = result.usage,
                            respBytes = result.bytes.size,
                            status = "ok",
                            latencyMs = latencyMs,
                            prices = prices,
                            deviceId = deviceId,
                            platform = platform,
                        )
                    }
                    if (isGuest) {
                        call.response.headers.append(
                            "X-Guest-Remaining",
                            GuestService.remainingReadOnly(deviceId!!, guestLlmQuota).toString(),
                        )
                    }
                    call.respondBytes(result.bytes, ContentType.Application.Json, result.status)
                }
                is ProxyResult.Streaming -> {
                    // 响应头先发：访客剩余额度必须在写 body 前 append
                    if (isGuest) {
                        call.response.headers.append(
                            "X-Guest-Remaining",
                            GuestService.remainingReadOnly(deviceId!!, guestLlmQuota).toString(),
                        )
                    }
                    // 逐 chunk 透传上游 SSE，同时 tee 到内存累积器（KB 级）供流末解析 usage
                    val tee = ByteArrayOutputStream()
                    call.respondBytesWriter(ContentType.Text.EventStream, result.status) {
                        val buf = ByteArray(8 * 1024)
                        try {
                            while (true) {
                                val n = result.channel.readAvailable(buf, 0, buf.size)
                                if (n == -1) break
                                if (n > 0) {
                                    writeFully(buf, 0, n)
                                    flush()
                                    tee.write(buf, 0, n)
                                }
                            }
                        } catch (e: Throwable) {
                            result.channel.cancel(e)
                            throw e
                        }
                    }
                    val streamLatencyMs = (System.currentTimeMillis() - started).toInt()
                    val usage = fromSseStream(tee.toString(Charsets.UTF_8.name()))
                    if (usage == null) {
                        logger.warn(
                            "No usage frame in SSE stream, provider={}, model={}, account={}",
                            result.provider, result.model, accountId,
                        )
                    }
                    accountId?.let {
                        UsageRecorder.log(
                            accountId = it,
                            model = result.model,
                            provider = result.provider,
                            usage = usage,
                            respBytes = tee.size(),
                            status = "ok",
                            latencyMs = streamLatencyMs,
                            prices = prices,
                            deviceId = deviceId,
                            platform = platform,
                        )
                    }
                }
                is ProxyResult.Error -> {
                    // LLM call failed — revert quota increment（豁免请求未扣减，跳过回滚保持配对）
                    if (!quotaBypass) {
                        if (isGuest) GuestService.revertQuota(deviceId!!) else AccountService.revertQuota(tokenHash)
                    }
                    accountId?.let {
                        UsageRecorder.log(it, requestedModel, "", null, 0, result.logStatus, null, prices, deviceId = deviceId, platform = platform)
                    }
                    call.respond(result.status, result.body)
                }
            }
        }
    }
}

private fun ApplicationRequest.clientIp(): String =
    headers["X-Forwarded-For"]?.substringBefore(",")
        ?: headers["X-Real-IP"]
        ?: "unknown"
