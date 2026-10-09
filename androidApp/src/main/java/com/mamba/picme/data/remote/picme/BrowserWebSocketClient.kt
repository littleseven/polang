package com.mamba.picme.data.remote.picme

import android.util.Base64
import android.util.Log
import com.mamba.picme.domain.browser.BrowserStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 浏览器直播 WebSocket 客户端（M2 推流模式）：
 * 连接 wss://api.polang.net/v1/browser/ws?sessionId=xxx，接收二进制 JPEG 帧 + 文本元数据。
 *
 * 协议（bridge ws.js 同源）：
 *   Server → Client  binary: JPEG 帧字节
 *   Server → Client  text:    {"type":"frame_meta",seq,width,height,timestamp}
 *   Client → Server  text:    {"type":"action","actionId":"...","action":{...}}
 *
 * 帧以 [SharedFlow] 推送，UI 侧 collect 后更新 StateFlow。
 * 主动断开（[disconnect]/[disconnectAll]）不产生事件；非主动断开（网络/对端关闭）
 * 经 [disconnectEvents] 上报，UI 侧仅降级记录（HTTP 动作通道不受影响）。
 */
class BrowserWebSocketClient(
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /** 推流帧事件（binary JPEG 已转 base64 + 元数据）。 */
    data class FrameEvent(
        val sessionId: String,
        val frameJpegBase64: String,
        val seq: Int,
        val width: Int,
        val height: Int,
    )

    /** 连接断开事件（含原因，供 UI 决定是否回退轮询）。 */
    data class DisconnectEvent(
        val sessionId: String,
        val reason: String,
    )

    private val _frameEvents = MutableSharedFlow<FrameEvent>(extraBufferCapacity = 16)
    val frameEvents: SharedFlow<FrameEvent> = _frameEvents.asSharedFlow()

    private val _disconnectEvents = MutableSharedFlow<DisconnectEvent>(extraBufferCapacity = 4)
    val disconnectEvents: SharedFlow<DisconnectEvent> = _disconnectEvents.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接，禁用 read timeout
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val activeConnections = mutableMapOf<String, WebSocket>()

    /** 主动断开中的 sessionId（disconnect/disconnectAll 发起）；onClosed 不再上报断开事件。 */
    private val intentionalCloses = mutableSetOf<String>()

    /**
     * 连接指定会话的 WebSocket 推流。
     * 重复调用同 sessionId 会先断开旧连接。
     */
    fun connect(sessionId: String) {
        disconnect(sessionId) // 幂等：先清旧连接

        val token = tokenProvider() ?: run {
            Log.w(TAG, "WS connect failed: not logged in")
            emitDisconnect(sessionId, "not logged in")
            return
        }

        val wsUrl = buildWebSocketUrl(sessionId)
        Log.d(TAG, "WS connecting: $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .header("X-App-Token", token)
            .apply { deviceIdProvider()?.let { header("X-Device-Id", it) } }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS onOpen: session=$sessionId")
                activeConnections[sessionId] = webSocket
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // binary JPEG 帧
                Log.d(TAG, "WS frame: session=$sessionId, bytes=${bytes.size}")
                val base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                scope.launch {
                    _frameEvents.emit(
                        FrameEvent(
                            sessionId = sessionId,
                            frameJpegBase64 = base64,
                            seq = 0, // binary 帧不携带 seq，从后续 frame_meta 补
                            width = 0,
                            height = 0,
                        )
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // text 元数据 / 控制消息
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "frame_meta" -> {
                            // frame_meta 在 binary 帧之后到达，携带 seq/width/height
                            // 当前实现：binary 帧已单独推送，meta 仅作日志/调试用途
                            // 如需精确对齐 seq，可在此缓存 meta 等待下一帧
                        }
                        "error" -> {
                            val code = json.optString("code", "unknown")
                            val message = json.optString("message", "")
                            emitDisconnect(sessionId, "$code: $message")
                        }
                        "action_result" -> {
                            // takeover 动作结果，当前版本不处理（由 HTTP action 通道承载）
                        }
                    }
                } catch (_: Exception) {
                    // 非 JSON 文本忽略
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS onClosed: session=$sessionId, code=$code, reason=$reason")
                activeConnections.remove(sessionId)
                // 主动断开（unwatch/VM 销毁）不上报——否则会误触发降级处理
                if (intentionalCloses.remove(sessionId)) return
                emitDisconnect(sessionId, "closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS onFailure: session=$sessionId, error=${t.message}")
                activeConnections.remove(sessionId)
                if (intentionalCloses.remove(sessionId)) return
                val reason = t.message ?: response?.message ?: "unknown"
                emitDisconnect(sessionId, "failure: $reason")
            }
        }

        val ws = client.newWebSocket(request, listener)
        activeConnections[sessionId] = ws
    }

    /** 断开指定会话的 WebSocket 连接（主动断开，不上报 disconnectEvents）。 */
    fun disconnect(sessionId: String) {
        val ws = activeConnections.remove(sessionId) ?: return
        intentionalCloses.add(sessionId)
        ws.close(1000, "client disconnect")
    }

    /** 断开所有活跃连接（ViewModel onCleared 时调用；主动断开，不上报）。 */
    fun disconnectAll() {
        intentionalCloses.addAll(activeConnections.keys)
        activeConnections.values.forEach { it.close(1000, "client disconnect all") }
        activeConnections.clear()
    }

    /** 发送 takeover 动作（坐标点击/输入/滚动）到 bridge。 */
    fun sendAction(sessionId: String, actionId: String, action: JSONObject) {
        val ws = activeConnections[sessionId] ?: return
        val payload = JSONObject()
            .put("type", "action")
            .put("actionId", actionId)
            .put("action", action)
        ws.send(payload.toString())
    }

    private fun buildWebSocketUrl(sessionId: String): String {
        val wsBase = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$wsBase/v1/browser/ws?sessionId=$sessionId"
    }

    private fun emitDisconnect(sessionId: String, reason: String) {
        scope.launch {
            _disconnectEvents.emit(DisconnectEvent(sessionId, reason))
        }
    }

    companion object {
        private const val TAG = "PoLang:BrowserWS"
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
