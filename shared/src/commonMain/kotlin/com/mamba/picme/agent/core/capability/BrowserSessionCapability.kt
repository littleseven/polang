package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.domain.browser.BrowserAction
import com.mamba.picme.domain.browser.BrowserActionRequest
import com.mamba.picme.domain.browser.BrowserActionResult
import com.mamba.picme.domain.browser.BrowserFrameResult
import com.mamba.picme.domain.browser.BrowserStatus
import com.mamba.picme.domain.browser.BrowserUnavailableException
import kotlin.concurrent.Volatile

/** 浏览器传输层（commonMain 无 HTTP 手段，组合根注入平台实现；测试注入 fake）。 */
interface BrowserTransport {
    suspend fun open(url: String, wantFrame: Boolean): BrowserActionResult
    suspend fun action(request: BrowserActionRequest): BrowserActionResult
    suspend fun frame(sessionId: String): BrowserFrameResult
    suspend fun close(sessionId: String): BrowserActionResult
}

/** UI 侧会话事件出口（androidApp ChatViewModel 实现；帧只走本通道，不回灌 LLM）。 */
interface BrowserSessionDelegate {
    fun onBrowserSessionStarted(sessionId: String, url: String, title: String, frameJpegBase64: String?)
    fun onBrowserSessionAction(sessionId: String, action: String, selector: String?, text: String?, url: String, title: String, frameJpegBase64: String?)
    fun onBrowserSessionFrame(sessionId: String, url: String, title: String, frameJpegBase64: String)
    fun onBrowserSessionFailed(sessionId: String, reason: String)
    fun onBrowserSessionClosed(sessionId: String, finalFrameJpegBase64: String?, actionCount: Int)
}

/**
 * 云端浏览器能力（spec §2/§3/§6）。
 *
 * 帧策略（端侧决策，LLM 无感）：open/navigate/click/type/screenshot 带 wantFrame，
 * extract/close 不带；连续帧走 [BrowserTransport.frame]（watch 模式轮询）。
 *
 * 降级不变式：一切失败（池满/会话过期/不可达/动作失败）都映射为 TextReply 结构化文本
 * 交 LLM 降级处理，不抛异常穿透 ReAct 链。
 */
class BrowserSessionCapability(
    private val transport: BrowserTransport,
) : BaseCapability() {

    @Volatile
    private var delegate: BrowserSessionDelegate? = null

    fun setDelegate(value: BrowserSessionDelegate?) {
        delegate = value
    }

    override val name: String = "browser_session"
    override val description: String = "云端浏览器：打开网页、点击、输入、提取正文，过程画面回传直播卡"

    override fun supportedCommands(): List<String> = listOf(
        "browser_open", "browser_navigate", "browser_click", "browser_type",
        "browser_extract", "browser_screenshot", "browser_close",
    )

    override suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction> {
        val reply = try {
            when (command) {
                is AgentCommand.BrowserOpen -> onOpen(command)
                is AgentCommand.BrowserNavigate -> onAction(
                    BrowserActionRequest(sessionId = command.sessionId, action = BrowserAction.NAVIGATE, url = command.url, wantFrame = true)
                )
                is AgentCommand.BrowserClick -> onAction(
                    BrowserActionRequest(
                        sessionId = command.sessionId, action = BrowserAction.CLICK, wantFrame = true,
                        selector = command.selector.ifEmpty { null },
                        targetText = command.targetText.ifEmpty { null },
                        targetIndex = command.targetIndex.takeIf { it >= 0 },
                    )
                )
                is AgentCommand.BrowserType -> onAction(
                    BrowserActionRequest(
                        sessionId = command.sessionId, action = BrowserAction.TYPE, wantFrame = true,
                        selector = command.selector.ifEmpty { null },
                        targetText = command.targetText.ifEmpty { null },
                        targetIndex = command.targetIndex.takeIf { it >= 0 },
                        text = command.text,
                    )
                )
                is AgentCommand.BrowserExtract -> onAction(
                    BrowserActionRequest(sessionId = command.sessionId, action = BrowserAction.EXTRACT, wantFrame = false)
                )
                is AgentCommand.BrowserScreenshot -> onAction(
                    BrowserActionRequest(sessionId = command.sessionId, action = BrowserAction.SCREENSHOT, wantFrame = true)
                )
                is AgentCommand.BrowserClose -> onClose(command.sessionId)
                else -> return Result.success(
                    AgentAction.Error(command.commandId, AgentErrorCode.METHOD_NOT_FOUND, "BrowserSessionCapability 不支持此命令")
                )
            }
        } catch (e: BrowserUnavailableException) {
            degradation(BrowserStatus.BROWSER_UNAVAILABLE, e.message ?: "network error")
        }
        return Result.success(AgentAction.TextReply(commandId = command.commandId, message = reply))
    }

    private suspend fun onOpen(command: AgentCommand.BrowserOpen): String {
        val r = transport.open(command.url, wantFrame = true)
        if (r.status != BrowserStatus.OK || r.sessionId == null) return degradation(r.status, r.reason)
        delegate?.onBrowserSessionStarted(r.sessionId, r.currentUrl ?: command.url, r.pageTitle ?: "", r.frameJpegBase64)
        return "浏览器会话已开始 sessionId=${r.sessionId}，当前页面：${r.pageTitle ?: ""}（${r.currentUrl ?: command.url}）"
    }

    private suspend fun onAction(request: BrowserActionRequest): String {
        val r = transport.action(request)
        if (r.status != BrowserStatus.OK) {
            delegate?.onBrowserSessionFailed(request.sessionId, r.reason ?: r.status)
            return degradation(r.status, r.reason)
        }
        delegate?.onBrowserSessionAction(request.sessionId, request.action, request.selector, request.text, r.currentUrl ?: "", r.pageTitle ?: "", r.frameJpegBase64)
        return if (request.action == BrowserAction.EXTRACT) {
            val elementsHint = r.elements?.takeIf { it.isNotEmpty() }?.let { list ->
                "\n可交互元素（click/type 优先用 index 回指）：\n" +
                    list.joinToString("\n") { e ->
                        "[${e.index}] <${e.tag}> ${e.text ?: ""}${e.href?.let { h -> " -> $h" } ?: ""}${e.type?.let { t -> " (type=$t)" } ?: ""}"
                    }
            } ?: ""
            "页面正文：\n${r.textExtract ?: ""}$elementsHint"
        } else {
            "已执行 ${request.action}，当前页面：${r.pageTitle ?: ""}（${r.currentUrl ?: ""}）"
        }
    }

    private suspend fun onClose(sessionId: String): String {
        val r = transport.close(sessionId)
        delegate?.onBrowserSessionClosed(sessionId, r.lastGoodFrame, r.actionCount ?: 0)
        return "浏览器会话已结束，共执行 ${r.actionCount ?: 0} 个动作"
    }

    /** 结构化降级文本：状态码在前（机读），人话在后（LLM 组织致歉/替代方案）。 */
    private fun degradation(status: String, reason: String?): String = when (status) {
        BrowserStatus.POOL_EXHAUSTED ->
            "[$status] 云端浏览器资源繁忙：${reason ?: "pool full"}。请改用纯文本回答，并告知用户稍后再试。"
        BrowserStatus.SESSION_EXPIRED ->
            "[$status] 浏览器会话已过期或被回收：${reason ?: ""}。如需继续请重新 browser_open。"
        BrowserStatus.QUOTA_EXCEEDED ->
            "[$status] 今日浏览器会话额度已用完。请改用纯文本回答。"
        BrowserStatus.BROWSER_UNAVAILABLE ->
            "[$status] 云端浏览器暂不可达：${reason ?: ""}。请改用纯文本回答。"
        else ->
            "[${BrowserStatus.ACTION_FAILED}] 浏览器操作失败：${reason ?: "unknown"}。可重试一次或换策略。"
    }
}
