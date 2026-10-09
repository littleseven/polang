package com.mamba.picme.server.browser

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * xuxing browser-agent-bridge 的 HTTP 客户端（spec §2）：
 * 域名结果由 bridge 以 JSON status 承载，本类只做透传 + X-Bridge-Token 注入；
 * 网络异常抛给调用方（路由层统一映射 503 browser_unavailable）。
 *
 * 响应体所有权：调用方在每条路径（含非 2xx / 错误路径）都必须消费响应体
 * （bodyAsText）或取消响应，否则连接不会归还连接池，最终耗尽。
 */
class BrowserBridgeClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val bridgeToken: String,
) {
    val available: Boolean get() = baseUrl.isNotBlank() && bridgeToken.isNotBlank()

    suspend fun open(body: String): HttpResponse = post("$baseUrl/session", body)

    suspend fun action(sessionId: String, body: String): HttpResponse =
        post("$baseUrl/session/$sessionId/action", body)

    suspend fun close(sessionId: String): HttpResponse =
        post("$baseUrl/session/$sessionId/close", "{}")

    suspend fun frame(sessionId: String): HttpResponse =
        httpClient.get("$baseUrl/session/$sessionId/frame") {
            header("X-Bridge-Token", bridgeToken)
        }

    /** 构造 bridge WebSocket URL（WS 代理用）；bridge 不可用时返回 null。 */
    fun wsUrl(sessionId: String): String? {
        if (!available) return null
        val wsBase = baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        return "$wsBase/ws?sessionId=$sessionId&token=$bridgeToken"
    }

    private suspend fun post(url: String, body: String): HttpResponse =
        httpClient.post(url) {
            header("X-Bridge-Token", bridgeToken)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}
