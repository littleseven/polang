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
 * - 加载 Chart bootstrap（含 `Chart.echarts` 固定模板）——产物为**注册制**（2026-11-09）：
 *   JS 侧 `Chart.echarts` 调用即把 {html, summary} 推入 `globalThis.__polangArtifacts`，
 *   本类每次执行前清空、执行后统一回收（[drainArtifacts]），与脚本 return 形状彻底解耦
 *   （LLM 把图卡结果包进别的对象返回也不丢卡；原 return 递归剥除 html 后作为 stats
 *   随信封上行 LLM，HTML 不出端、不进 LLM 上下文）；
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
     * 执行云端下发的相册聚合脚本。evalAsync 按「async 函数体」语义执行（顶层 return/await 合法）。
     * 分发优先级：注册表产物（[drainArtifacts] 非空）→ 顶层 {html} 兜底（bootstrap 未加载的
     * 兼容路径）→ 顶层 {chart} SVG 拦截引导 → 原样回传（字符串原样、其余 JsValue.toJson）。
     * 失败回 `error: <msg>` 契约串（`error:` 前缀由 shared CloudAgentToolContract 收口），不抛业务异常。
     */
    suspend fun runScript(code: String): String {
        if (code.isBlank()) return "${CloudAgentToolContract.ERROR_PREFIX} empty script"
        val rt = getOrCreate()
        return try {
            evalMutex.withLock {
                // 每次执行前刷新语言标签 + 清空产物注册表（应用内切语言后新图即新文案；
                // 注册表清空保证 drain 只回收本轮产物。失败不阻断脚本）
                runCatching {
                    rt.eval(
                        "globalThis.__polangLang=${JSONObject.quote(appLanguageTag())};" +
                            "globalThis.__polangArtifacts=[];",
                    )
                }.onFailure { Logger.w(tag, "pre-run inject failed", it) }
                when (val result = rt.evalAsync(code, evalTimeoutMs, traceId = null)) {
                    is JsValue.Str -> result.value
                    else -> {
                        val cards = drainArtifacts(rt)
                        val obj = result as? JsValue.Obj
                        when {
                            // 注册制产物：调用即发卡，与 return 形状解耦；
                            // 原 return 剥除 html 后作为 stats 随信封上行（保住 LLM 要的统计）
                            cards.isNotEmpty() ->
                                wrapHtmlArtifact(cards, statsJsonOf(result))
                            // 兼容兜底：bootstrap 未加载时脚本自组 {html, summary} 仍发卡
                            obj?.entries?.get("html") is JsValue.Str -> {
                                val html = obj.entries.getValue("html") as JsValue.Str
                                val summary = (obj.entries["summary"] as? JsValue.Str)?.value ?: ""
                                wrapHtmlArtifact(listOf(html.value to summary), stats = null)
                            }
                            // SVG 图卡云端无渲染通路：拦截省 token（SVG 不回传）并引导改用 Chart.echarts
                            obj?.entries?.get("chart") is JsValue.Str ->
                                "${CloudAgentToolContract.ERROR_PREFIX} SVG chart 不支持云端渲染，" +
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

    /** 回收本轮注册的图卡产物（html, summary）；注册表缺失/异常按无产物处理。 */
    private fun drainArtifacts(rt: JsRuntime): List<Pair<String, String>> =
        runCatching {
            val raw = rt.eval("JSON.stringify(globalThis.__polangArtifacts||[])")
            val arr = org.json.JSONArray((raw as? JsValue.Str)?.value ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val html = item.optString("html")
                if (html.isBlank()) null else html to item.optString("summary")
            }
        }.onFailure { Logger.w(tag, "drain artifacts failed", it) }
            .getOrDefault(emptyList())

    /**
     * 原 return 值递归剥除 html 后的 JSON（stats 上行用）：剥掉 `html` 键、及值为「含 html
     * 的 Obj」的 `chart` 键（2026-11-09 真机事故形状：{chart: {html, summary}, total...}），
     * 保证完整 HTML 绝不随 stats 上行 LLM。清洗后为空对象/非容器则返回 null。
     */
    private fun statsJsonOf(result: JsValue): String? =
        when (val cleaned = result.stripHtml()) {
            is JsValue.Obj -> cleaned.toJson().takeIf { it != "{}" }
            JsValue.Null -> null
            else -> cleaned.toJson()
        }

    private fun JsValue.stripHtml(): JsValue = when (this) {
        is JsValue.Obj -> {
            val kept = entries.mapNotNull { (key, value) ->
                when {
                    key == "html" -> null
                    key == "chart" && value is JsValue.Obj &&
                        value.entries["html"] is JsValue.Str -> null
                    else -> key to value.stripHtml()
                }
            }
            if (kept.size == entries.size) this else JsValue.Obj(kept.toMap())
        }
        is JsValue.Arr -> JsValue.Arr(items.map { it.stripHtml() })
        else -> this
    }

    companion object {
        /** 云端聚合脚本超时（chat 链路只读脚本 5s；月度/年度聚合计量更大放宽到 10s）。 */
        const val DEFAULT_EVAL_TIMEOUT_MS = 10_000L

        /** 产物信封标记键：CloudAgentTurnRunner 据此把 HTML 卡从工具结果中剥离上屏。 */
        const val ARTIFACT_KEY = "__artifact__"

        const val ARTIFACT_HTML_CARD = "html_card"

        /**
         * 产物信封（注册制，2026-11-09）：`cards` 数组承载本轮全部图卡（调用即发卡，一稿可多卡）；
         * 兼容单字段 `html`/`summary`（取最后一卡）保留给旧解析方；
         * `stats` = 原 return 剥除 html 后的统计小对象，随信封上行 LLM 写分析。
         */
        fun wrapHtmlArtifact(cards: List<Pair<String, String>>, stats: String?): String =
            JSONObject()
                .put(ARTIFACT_KEY, ARTIFACT_HTML_CARD)
                .put(
                    "cards",
                    org.json.JSONArray(
                        cards.map { (html, summary) ->
                            JSONObject().put("html", html).put("summary", summary)
                        },
                    ),
                )
                .put("html", cards.last().first)
                .put("summary", cards.last().second)
                .apply {
                    if (!stats.isNullOrBlank() && stats != "{}" && stats != "null") {
                        put("stats", runCatching { JSONObject(stats) }.getOrElse { stats })
                    }
                }
                .toString()

        /** 解析后的产物信封：cards = 本轮全部图卡（html, summary）；stats = 清洗后的统计 JSON。 */
        data class HtmlArtifactResult(
            val cards: List<Pair<String, String>>,
            val stats: String?,
        )

        /** 解析产物信封；非信封（普通 JSON/字符串结果）或空卡返回 null。兼容无 cards 数组的旧信封。 */
        fun parseHtmlArtifact(result: String): HtmlArtifactResult? =
            runCatching {
                val obj = JSONObject(result)
                if (obj.optString(ARTIFACT_KEY) != ARTIFACT_HTML_CARD) return null
                val cardsArray = obj.optJSONArray("cards")
                val cards = if (cardsArray != null) {
                    (0 until cardsArray.length()).mapNotNull { i ->
                        val item = cardsArray.optJSONObject(i) ?: return@mapNotNull null
                        val html = item.optString("html")
                        if (html.isBlank()) null else html to item.optString("summary")
                    }
                } else {
                    val html = obj.optString("html")
                    if (html.isBlank()) emptyList() else listOf(html to obj.optString("summary"))
                }
                if (cards.isEmpty()) return null
                val stats = obj.opt("stats")?.takeIf { it != JSONObject.NULL }?.toString()
                HtmlArtifactResult(cards, stats)
            }.getOrNull()
    }
}
