package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.IosRenderHtmlBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS Chat「HTML 卡」Capability —— `render_html` 命令的 iOS 执行端（M5 B4，chat.yaml §13）。
 *
 * 在 CHAT 场景接收 [AgentCommand.RenderHtml]，委托 [bridge]（Swift `RenderHtmlBridge`）：
 * html 经 Swift 自有通道交给 ChatViewModel 落 HtmlCard 消息（Inline/Fullpage 双形态渲染）；
 * [AgentAction.TextReply] 的 summary 回传远程 LLM 做文字总结（sanitize 被拒时回传拒绝
 * 原因，引导 LLM 重新生成——语义对齐 Android 侧 observation）。
 *
 * 路由：`CapabilityRegistry.findCapabilityForCommand` 按 `supportedCommands()` 匹配——
 * 本能力仅声明 `render_html`，与 [IosChartCapability]（draw_chart）等无冲突。
 *
 * [PRIVACY]：html 为远程 LLM 生成的文本内容，端侧渲染，无媒体上传。
 */
class IosRenderHtmlCapability(
    private val bridge: IosRenderHtmlBridge? = null
) : BaseCapability() {

    private val tag = "PoLang:IosRenderHtmlCapability"

    override val name: String = "ios_render_html"
    override val description: String = "端侧渲染 HTML 组件卡片（Inline/Fullpage 双形态）为消息"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(COMMAND_RENDER_HTML)

    override fun isAvailable(): Boolean = bridge != null

    override fun getCommandDescription(command: String): String = when (command) {
        COMMAND_RENDER_HTML -> "渲染 HTML 组件卡片插入聊天（端侧 WebView，支持内联 CSS/JS）。参数: html/summary/display。"
        else -> super.getCommandDescription(command)
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> = try {
        when (command) {
            is AgentCommand.RenderHtml -> handleRenderHtml(command)
            else -> Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.METHOD_NOT_FOUND,
                    "IosRenderHtmlCapability 不支持此命令"
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(tag, "render_html failed", e)
        Result.success(
            AgentAction.Error(
                command.commandId,
                AgentErrorCode.INTERNAL_ERROR,
                "HTML 卡渲染失败：${e.message ?: "未知错误"}"
            )
        )
    }

    private suspend fun handleRenderHtml(command: AgentCommand.RenderHtml): Result<AgentAction> {
        val renderer = bridge
            ?: return Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    "HTML 卡渲染暂不可用（渲染桥未注入）"
                )
            )
        val summary = awaitRender(
            bridge = renderer,
            html = command.html,
            summary = command.summary,
            display = command.display
        )
        return Result.success(AgentAction.TextReply(command.commandId, summary))
    }

    /**
     * completion 回调转 suspend（对齐 [IosChartCapability.awaitRender] 范式）。
     * 异常绝不逃逸出 Kotlin 边界（signal 6 铁律）——桥异常时回退兜底 summary。
     */
    private suspend fun awaitRender(
        bridge: IosRenderHtmlBridge,
        html: String,
        summary: String?,
        display: String?
    ): String = suspendCancellableCoroutine { cont ->
        try {
            bridge.renderHtml(html, summary, display) { result ->
                if (cont.isActive) cont.resume(result)
            }
        } catch (t: Throwable) {
            Logger.e(tag, "renderHtml bridge threw", t)
            if (cont.isActive) cont.resume(fallbackSummary(summary))
        }
    }

    private fun fallbackSummary(summary: String?): String =
        summary?.takeIf { it.isNotBlank() } ?: "HTML 卡片已生成"

    companion object {
        private const val COMMAND_RENDER_HTML = "render_html"
    }
}
