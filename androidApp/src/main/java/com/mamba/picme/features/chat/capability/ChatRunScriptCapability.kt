@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import java.lang.ref.WeakReference

/**
 * Chat 场景「执行 JS 脚本」Capability。
 *
 * 职责：在 CHAT 场景暴露 `run_gallery_script`，把命令回调给 [Delegate]（ChatViewModel），
 * 由其在端侧 QuickJS 沙箱执行（gallery.summary 等只读 handler），结果字符串回传给远程 LLM 做总结。
 */
class ChatRunScriptCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatRunScriptCapability? = null

        fun getInstance(): ChatRunScriptCapability =
            instance ?: synchronized(this) {
                instance ?: ChatRunScriptCapability().also { instance = it }
            }
    }

    private val tag = "ChatRunScriptCapability"

    override val name: String = "chat_run_script"
    override val description: String =
        "在端侧沙箱执行 JS（相册盘点/统计，取数只读；写操作走 capability.dispatch 经用户确认）。脚本须用 await bridge.callAsync('gallery.summary', {}) 等取数并在 JS 内做组合计算"

    interface Delegate {
        /** 在端侧沙箱执行 [code]，返回结果（JSON 文本，会作为 observation 回传远程 LLM）。 */
        suspend fun onRunScript(code: String, traceId: String?): String

        /**
         * 端侧渲染一张图表：用 Chart 生成器把 [labels]/[values] 画成 [type] 图（bar/line/pie），
         * 渲染结果作为 CHART 消息显示；返回 summary（回传 LLM 做文字总结）。
         */
        suspend fun onDrawChart(
            type: String,
            title: String,
            labels: List<String>,
            values: List<Double>,
            unit: String?,
            traceId: String?
        ): String

        /**
         * 端侧渲染一张 HTML 组件卡片：[html] 为自包含 HTML（内联 CSS/JS，禁外链/网络资源），
         * 清洗后作为 HTML_CARD 消息落库（离线 WebView 渲染，断网 + 零 JS 桥接）；
         * 返回 summary（回传 LLM 做文字总结）。
         */
        suspend fun onRenderHtml(
            html: String,
            summary: String?,
            traceId: String?
        ): String
    }

    private var delegateRef: WeakReference<Delegate>? = null

    fun bindDelegate(delegate: Delegate) {
        delegateRef = WeakReference(delegate)
        Logger.i(tag, "Delegate bound")
    }

    fun unbindDelegate() {
        delegateRef = null
        Logger.i(tag, "Delegate unbound")
    }

    override fun isAvailable(): Boolean = delegateRef?.get() != null

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf("run_gallery_script", "draw_chart", "render_html")

    override fun getCommandDescription(command: String): String = when (command) {
        "run_gallery_script" -> "执行 JS 脚本（端侧沙箱，取数只读；写操作经确认）。参数: code (string, JS 源码)。" +
            "所有 handler 均为异步，脚本须用 await bridge.callAsync(name, args) 取数据" +
            "（'gallery.summary'|'gallery.query'|'gallery.tags'|'gallery.timeline'|'gallery.intersect'|" +
            "'gallery.stats_by_tag'|'gallery.stats_by_city'|'media.meta'|'media.batch_meta'|" +
            "'face.cluster'|'tag.audit'|'tag.scan_status'；" +
            "bridge.call 已禁用），并在 JS 内组合计算。" +
            "写操作（删除/收藏/选中/记忆/人物关系）用 await bridge.callAsync('capability.dispatch', {method, params})：" +
            "method 支持 delete_media{ids:[...]}/favorite_media{id,favorite}/select_media{id,selected}/" +
            "remember_fact/forget_fact/recall_memory/" +
            "remember_person_relation{name,relation}/forget_person_relation{name}/query_person_relation{name?}" +
            "（写操作会弹窗等用户确认，拒绝或超时 Promise 会 reject，需 try/catch），其余 method 报错。" +
            "需要画图时 return Chart.timeline(...)（时间趋势，最省事）/ Chart.bar(...) / Chart.line(...) / " +
            "Chart.pie(...)——会自动渲染成图卡（勿手动输出 SVG，勿用 Markdown 表格画图）；" +
            "return 其它值则原样作为 observation 回传给你做文字总结。"
        "draw_chart" -> "画图表（柱状/折线/饼图）并渲染成真实图片。" +
            "参数: type(bar/line/pie)、title、labels(英文逗号分隔)、values(逗号分隔数值,与 labels 等长)、unit。" +
            "这是展示图表的唯一方式，禁止用文字/表格画图。"
        "render_html" -> "渲染自包含 HTML 组件卡片（离线 WebView，内联 CSS/JS）。" +
            "参数: html(自包含 HTML，禁外链/网络请求/跳转)、summary(一句话总结)。" +
            "仅当用户明确要求更丰富展示或交互组件时使用；统计图仍走 draw_chart。"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val delegate = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "脚本执行暂不可用（聊天页未激活）"
                )
            )
        return try {
            when (command) {
                is AgentCommand.ExecuteScript -> {
                    val result = delegate.onRunScript(command.code, context.traceId)
                    Result.success(
                        AgentAction.TextReply(
                            commandId = command.commandId,
                            message = result
                        )
                    )
                }
                is AgentCommand.DrawChart -> {
                    val result = delegate.onDrawChart(
                        type = command.type,
                        title = command.title,
                        labels = command.labels,
                        values = command.values,
                        unit = command.unit,
                        traceId = context.traceId
                    )
                    Result.success(
                        AgentAction.TextReply(
                            commandId = command.commandId,
                            message = result
                        )
                    )
                }
                is AgentCommand.RenderHtml -> {
                    val result = delegate.onRenderHtml(
                        html = command.html,
                        summary = command.summary,
                        traceId = context.traceId
                    )
                    Result.success(
                        AgentAction.TextReply(
                            commandId = command.commandId,
                            message = result
                        )
                    )
                }
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatRunScriptCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Run script failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "脚本执行失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }
}
