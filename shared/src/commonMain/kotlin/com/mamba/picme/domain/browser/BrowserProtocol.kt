package com.mamba.picme.domain.browser

import kotlinx.serialization.Serializable

/**
 * 云端浏览器协议（端云同源 SSOT，spec §2/§3）：
 * App ↔ picme-server ↔ xuxing browser-agent-bridge 三段共用同一 JSON 形态，
 * 域名结果一律以 [BrowserActionResult.status] 承载（HTTP 仅表达传输/鉴权层失败）。
 */

/** 结构化状态闭集（spec §6 错误分层）。 */
object BrowserStatus {
    const val OK = "ok"
    const val ACTION_FAILED = "action_failed"
    const val POOL_EXHAUSTED = "pool_exhausted"
    const val SESSION_EXPIRED = "session_expired"
    const val BROWSER_UNAVAILABLE = "browser_unavailable"
    const val QUOTA_EXCEEDED = "quota_exceeded"
}

/** 动作枚举（wire 值 = 工具名去 browser_ 前缀）。 */
object BrowserAction {
    const val NAVIGATE = "navigate"
    const val CLICK = "click"
    const val TYPE = "type"
    const val EXTRACT = "extract"
    const val SCREENSHOT = "screenshot"
}

/**
 * 动作请求 DTO（端 → 云统一形态，借鉴 openmuse：click/type 三模式定位）。
 * 定位优先级：targetIndex（extract 返回的元素序号）> targetText（可见文本匹配）> selector（CSS，兜底）。
 * 不用字段传 null；App 侧 @Tool 参数的空串/-1 哨兵由能力层归一为 null。
 */
@Serializable
data class BrowserActionRequest(
    val sessionId: String,
    val action: String,
    val url: String? = null,
    val selector: String? = null,
    val targetText: String? = null,
    val targetIndex: Int? = null,
    val text: String? = null,
    val wantFrame: Boolean = false,
)

/** extract 返回的交互元素（LLM 凭 index/text 回指，不用猜盲 selector）。 */
@Serializable
data class BrowserElement(
    val index: Int,
    val tag: String,
    val text: String? = null,
    val href: String? = null,
    val type: String? = null,
)

/** open/action/close 统一响应（[textExtract]/[frameJpegBase64]/[elements] 按动作与 wantFrame 可选出现）。 */
@Serializable
data class BrowserActionResult(
    val status: String,
    val sessionId: String? = null,
    val currentUrl: String? = null,
    val pageTitle: String? = null,
    val textExtract: String? = null,
    val frameJpegBase64: String? = null,
    val elements: List<BrowserElement>? = null,
    val actionMs: Long? = null,
    val actionCount: Int? = null,
    val errorCode: String? = null,
    val reason: String? = null,
    val lastGoodFrame: String? = null,
)

/** watch 模式取帧响应（GET /v1/browser/frame）。 */
@Serializable
data class BrowserFrameResult(
    val status: String,
    val sessionId: String? = null,
    val currentUrl: String? = null,
    val pageTitle: String? = null,
    val frameJpegBase64: String? = null,
    val errorCode: String? = null,
    val reason: String? = null,
)

/** 传输层异常（网络失败/503）：能力层据此走 browser_unavailable 降级（spec §6）。 */
class BrowserUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
