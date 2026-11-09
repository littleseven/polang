package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import com.mamba.picme.agent.core.js.JsEngine
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 云端 Agent `run_gallery_script` 工具的无头 JS 执行器（2026-11-08 补录）。
 *
 * 背景：云端工具目录（shared `CloudAgentToolCatalog`）原只有 summary/search/refine 三件，
 * 月度趋势等聚合明细在云端无取数通路，LLM 只能臆造数据画图（2026-11-08 真机事故）。
 * 本类把 chat 链路同款只读 JS 沙盒挂到云端工具桥上，补齐真实聚合通路。
 *
 * 与 chat 链路持久 JsRuntime 的差异（保持云端面只读）：
 * - handler 集 = `registerGalleryHandlers`（gallery/media/face/tag 只读 12 件，唯一注册点同源）；
 * - **不注册** `capability.dispatch` 写通路；
 * - 加载 Chart bootstrap（含 `Chart.echarts` 固定模板）——产物拦截同款 chat 链路：
 *   `return {html, summary}` 包装为产物信封（[wrapHtmlArtifact]），由 CloudAgentTurnRunner
 *   剥离上屏（HTML 不出端、不进 LLM 上下文），summary 回传云端 LLM 写分析；
 * - 普通 eval 结果不喂 UI——`return` 值整体 JSON 化回传云端 LLM（字符串原样返回），
 *   出端仍过 [DeviceToolExecutor] 的投影闸 + 32K 截断。
 *
 * 依赖以函数注入（[DeviceToolExecutor]/[CloudAgentGalleryTools] 范式），纯 JVM 可测；
 * 生产接线在 AppContainer（QuickJsEngine + applicationScope + registerGalleryHandlers）。
 * runtime 懒建后进程级常驻（AppContainer 单例语义），[evalMutex] 串行化 eval——
 * QuickJS 单上下文非线程安全，与 ChatViewModel.jsEvalMutex 同一护栏。
 *
 * [I18N]：图表卡兜底文案（读数行提示/默认系列名/读数分隔符）按应用语言五语本地化——
 * 每次 [runScript] 前把 [appLanguageTag] 写入 JS 侧 `globalThis.__polangLang`，
 * chart_bootstrap 的 CHART_STRINGS 表在每次发卡时解析，语言切换即时生效、runtime 无需重建。
 */
class CloudAgentScriptTools(
    private val engineFactory: () -> JsEngine,
    private val scope: CoroutineScope,
    private val registerHandlers: (JsRuntime) -> Unit,
    private val chartBootstrapJs: () -> String = { "" },
    private val evalTimeoutMs: Long = DEFAULT_EVAL_TIMEOUT_MS,
    private val appLanguageTag: () -> String = { "zh-CN" },
) {

    private val tag = "CloudAgentScriptTools"
    private val evalMutex = Mutex()
    private var runtime: JsRuntime? = null

    private fun getOrCreate(): JsRuntime =
        runtime ?: synchronized(this) {
            runtime ?: JsRuntime(
                engine = engineFactory(),
                scope = scope,
                source = "cloud_agent",
            ).also { rt ->
                registerHandlers(rt)
                // 注入 Chart 图表生成器（bar/line/pie/timeline SVG + echarts HTML 模板）。
                // 失败仅告警，不阻断脚本能力（对齐 ChatViewModel 注入语义）。
                runCatching { chartBootstrapJs() }
                    .onSuccess { src -> if (src.isNotBlank()) rt.eval(src) }
                    .onFailure { Logger.w(tag, "chart bootstrap eval failed", it) }
                Logger.i(tag, "cloud agent JsRuntime created with ${rt.handlerNames().size} handlers")
                runtime = rt
            }
        }

    /**
     * 执行云端下发的相册聚合脚本。evalAsync 按「async 函数体」语义执行（顶层 return/await 合法），
     * 返回 JS return 值：字符串原样、其余类型 JSON 化（JsValue.toJson）。
     * 失败回 `error: <msg>` 契约串（`error:` 前缀由 shared CloudAgentToolContract 收口），不抛业务异常。
     */
    suspend fun runScript(code: String): String {
        if (code.isBlank()) return "${CloudAgentToolContract.ERROR_PREFIX} empty script"
        val rt = getOrCreate()
        return try {
            evalMutex.withLock {
                // 每次执行前刷新语言标签（应用内切语言后新图即新文案；失败不阻断脚本）
                runCatching { rt.eval("globalThis.__polangLang=${JSONObject.quote(appLanguageTag())};") }
                    .onFailure { Logger.w(tag, "locale tag inject failed", it) }
                when (val result = rt.evalAsync(code, evalTimeoutMs, traceId = null)) {
                    is JsValue.Str -> result.value
                    else -> {
                        val obj = result as? JsValue.Obj
                        val html = obj?.entries?.get("html") as? JsValue.Str
                        val chart = obj?.entries?.get("chart") as? JsValue.Str
                        when {
                            // 产物拦截：{html, summary} → 信封（HTML 不出端，summary 回传 LLM）
                            html != null -> {
                                val summary = (obj.entries["summary"] as? JsValue.Str)?.value ?: ""
                                wrapHtmlArtifact(html.value, summary)
                            }
                            // SVG 图卡云端无渲染通路：拦截省 token（SVG 不回传）并引导改用 Chart.echarts
                            chart != null -> "${CloudAgentToolContract.ERROR_PREFIX} SVG chart 不支持云端渲染，" +
                                "请改用 Chart.echarts(cfg) 返回 {html, summary}"
                            else -> result.toJson()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "runScript eval failed", e)
            "${CloudAgentToolContract.ERROR_PREFIX} ${e.message ?: "script eval failed"}"
        }
    }

    companion object {
        /** 云端聚合脚本超时（chat 链路只读脚本 5s；月度/年度聚合计量更大放宽到 10s）。 */
        const val DEFAULT_EVAL_TIMEOUT_MS = 10_000L

        /** 产物信封标记键：CloudAgentTurnRunner 据此把 HTML 卡从工具结果中剥离上屏。 */
        const val ARTIFACT_KEY = "__artifact__"

        const val ARTIFACT_HTML_CARD = "html_card"

        fun wrapHtmlArtifact(html: String, summary: String): String =
            JSONObject()
                .put(ARTIFACT_KEY, ARTIFACT_HTML_CARD)
                .put("html", html)
                .put("summary", summary)
                .toString()

        /** 解析产物信封（html, summary）；非信封（普通 JSON/字符串结果）返回 null。 */
        fun parseHtmlArtifact(result: String): Pair<String, String>? =
            runCatching {
                val obj = JSONObject(result)
                if (obj.optString(ARTIFACT_KEY) != ARTIFACT_HTML_CARD) return null
                val html = obj.optString("html")
                if (html.isBlank()) return null
                html to obj.optString("summary")
            }.getOrNull()
    }
}
