package com.mamba.picme.server.routes

import com.mamba.picme.server.browser.BrowserBridgeClient
import com.mamba.picme.server.browser.BrowserConcurrencyRegistry
import com.mamba.picme.server.browser.BrowserSessionStats
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-browser")

/**
 * 云端浏览器网关（spec §2/§6/§7）：
 * X-App-Token 鉴权（全局拦截器）→ 每日配额 peek（成功才 allow 计费）→ per-user 并发=1 →
 * 透传 xuxing bridge。bridge 域名结果（含 pool_exhausted/session_expired/action_failed）
 * 以 JSON status 原样透传给 App；bridge 不可达统一 503 browser_unavailable 且不计额度。
 */
fun Route.browserRoute(
    bridge: BrowserBridgeClient,
    dailyLimiter: RateLimiter,
    concurrency: BrowserConcurrencyRegistry,
    stats: BrowserSessionStats,
) {
    post("/v1/browser/open") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        if (!bridge.available) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        if (!dailyLimiter.peek(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "quota_exceeded", "tier" to "account"))
            return@post
        }
        if (!concurrency.tryAcquire(owner)) {
            // busy 自愈：App 崩后 bridge 会话已被回收但租约残存（历史实测锁死最长 15min），
            // 探测租约对应会话活性——仅 bridge 明确回 session_expired 才判死释放重取；
            // 真活 / 探测失败（bridge 不可达）/ 响应解析失败（如反代 502 HTML）/ 其它 status
            // 一律保守维持 BUSY，绝不误杀活会话。
            val leasedSessionId = concurrency.leaseSessionId(owner)
            val healed = if (leasedSessionId == null) {
                false // 极端中间态（tryAcquire 后 bind 前）：维持 BUSY
            } else {
                val dead = try {
                    probeStatus(bridge.sessionStatus(leasedSessionId).bodyAsText()) == "session_expired"
                } catch (e: Throwable) {
                    false
                }
                if (!dead) {
                    false
                } else {
                    concurrency.release(owner, leasedSessionId) // 条件释放：不误删并发重取的新租约
                    runCatching { stats.recordClose(leasedSessionId, "reaped_heal") }
                        .onFailure { logger.warn("browser stats recordClose failed: sessionId=$leasedSessionId", it) }
                    concurrency.tryAcquire(owner)
                }
            }
            if (!healed) {
                call.respondText(BUSY_USER_PAYLOAD, ContentType.Application.Json, HttpStatusCode.OK)
                return@post
            }
        }
        val upstream = try {
            bridge.open(call.receiveText())
        } catch (e: Throwable) {
            concurrency.release(owner)
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        val payload = upstream.bodyAsText()
        val sessionId = probeOkSessionId(payload)
        if (sessionId != null) {
            concurrency.bind(owner, sessionId)
            dailyLimiter.allow(owner) // 成功才计额度（spec §6：browser_unavailable 不计）
            // 统计写故障隔离：telemetry 挂不得破坏响应契约（租约已绑/额度已计）
            runCatching { stats.recordOpen(owner, sessionId) }
                .onFailure { logger.warn("browser stats recordOpen failed: sessionId=$sessionId", it) }
        } else {
            concurrency.release(owner)
        }
        call.respondText(payload, ContentType.Application.Json, upstream.status)
    }

    post("/v1/browser/action") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        val body = call.receiveText()
        val sessionId = probeSessionId(body) ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "sessionId required"))
            return@post
        }
        val upstream = try {
            bridge.action(sessionId, body)
        } catch (e: Throwable) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        val payload = upstream.bodyAsText()
        if (probeStatus(payload) == "session_expired") {
            concurrency.release(owner, sessionId) // 条件释放：不误删重新获取的新租约
            runCatching { stats.recordClose(sessionId, "expired") }
                .onFailure { logger.warn("browser stats recordClose failed: sessionId=$sessionId", it) }
        }
        call.respondText(payload, ContentType.Application.Json, upstream.status)
    }

    get("/v1/browser/frame") {
        call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@get
        }
        val sessionId = call.request.queryParameters["sessionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "sessionId required"))
            return@get
        }
        val upstream = try {
            bridge.frame(sessionId)
        } catch (e: Throwable) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@get
        }
        call.respondText(upstream.bodyAsText(), ContentType.Application.Json, upstream.status)
    }

    post("/v1/browser/close") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@post
        }
        val body = call.receiveText()
        val sessionId = probeSessionId(body) ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "sessionId required"))
            return@post
        }
        val upstream = try {
            bridge.close(sessionId)
        } catch (e: Throwable) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "browser_unavailable"))
            return@post
        }
        concurrency.release(owner, sessionId) // 条件释放：不误删重新获取的新租约
        runCatching { stats.recordClose(sessionId, "closed") }
            .onFailure { logger.warn("browser stats recordClose failed: sessionId=$sessionId", it) }
        call.respondText(upstream.bodyAsText(), ContentType.Application.Json, upstream.status)
    }

    // --- WebSocket 帧流代理（M2：App ↔ bridge 双向转发） ---
    // 认证已由全局拦截器完成（X-App-Token → ownerTokenHash）。
    // App 连接 wss://server/v1/browser/ws?sessionId=xxx，
    // 服务端用 Ktor WS client 连 bridge ws://bridge/ws?sessionId=xxx&token=bridgeToken，
    // 二进制 JPEG 帧 + 文本控制消息双向透传。
    val wsClient = HttpClient(CIO) { install(WebSockets) }

    webSocket("/v1/browser/ws") {
        val serverSession = this
        val owner = call.ownerTokenHash() ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val sessionId = call.request.queryParameters["sessionId"] ?: run {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "sessionId required"))
            return@webSocket
        }
        if (!bridge.available) {
            close(CloseReason(CloseReason.Codes.SERVICE_RESTART, "browser_unavailable"))
            return@webSocket
        }

        val bridgeWsUrl = bridge.wsUrl(sessionId)
        if (bridgeWsUrl == null) {
            close(CloseReason(CloseReason.Codes.SERVICE_RESTART, "browser_unavailable"))
            return@webSocket
        }

        logger.info("browser WS proxy: owner=$owner sessionId=$sessionId → $bridgeWsUrl")

        try {
            wsClient.webSocket(bridgeWsUrl) {
                val upstream = this

                // App → bridge：文本 action 消息透传（独立协程）
                val appToBridge = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        for (frame in serverSession.incoming) {
                            when (frame) {
                                is Frame.Text -> upstream.send(Frame.Text(frame.readText()))
                                is Frame.Binary -> upstream.send(Frame.Binary(true, frame.data))
                                is Frame.Close -> {
                                    upstream.close(frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "app closed"))
                                    return@launch
                                }
                                else -> {}
                            }
                        }
                    } catch (e: Throwable) {
                        logger.debug("browser WS app→bridge forward ended: ${e.message}")
                    }
                }

                // bridge → App：二进制 JPEG 帧 + 文本元数据
                try {
                    for (frame in upstream.incoming) {
                        when (frame) {
                            is Frame.Binary -> serverSession.send(Frame.Binary(true, frame.data))
                            is Frame.Text -> serverSession.send(Frame.Text(frame.readText()))
                            is Frame.Close -> {
                                serverSession.close(frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "bridge closed"))
                                return@webSocket
                            }
                            else -> {}
                        }
                    }
                } catch (e: Throwable) {
                    logger.debug("browser WS bridge→app forward ended: ${e.message}")
                }

                appToBridge.cancel()
            }
        } catch (e: Throwable) {
            logger.warn("browser WS proxy failed: sessionId=$sessionId, ${e.message}")
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "bridge connection failed"))
        }
    }
}

private const val BUSY_USER_PAYLOAD =
    """{"status":"pool_exhausted","errorCode":"user_concurrency","reason":"active browser session exists, close it first"}"""

private val probeJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class StatusProbe(val status: String? = null, val sessionId: String? = null)

/** open 响应里提取成功会话 id（status==ok 且 sessionId 非空）；解析失败按非 ok 处理。 */
private fun probeOkSessionId(payload: String): String? =
    runCatching { probeJson.decodeFromString<StatusProbe>(payload) }.getOrNull()
        ?.takeIf { it.status == "ok" && !it.sessionId.isNullOrBlank() }?.sessionId

/** 响应 payload 里解析 status 字段；解析失败返回 null。 */
private fun probeStatus(payload: String): String? =
    runCatching { probeJson.decodeFromString<StatusProbe>(payload) }.getOrNull()?.status

/** 请求体里宽松提取 sessionId 字段（不入库的轻量解析）。 */
private fun probeSessionId(payload: String): String? =
    runCatching { probeJson.decodeFromString<StatusProbe>(payload) }.getOrNull()?.sessionId
