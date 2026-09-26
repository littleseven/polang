package com.mamba.picme.agent.core.intent

import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 意图路由器（Intent Router）——spec《意图路由契约与意图路由器》§3.2。
 *
 * 职责边界：专用 LLM 闭集分类（~1k token prompt、temperature=0、JSON 强校验），
 * 输出**意图**而非工具；路由策略（意图→命令）归 [ChatRoutingPolicy] 确定性代码。
 *
 * 可靠性工程（评审决议）：
 * - **本地信号门控**（[IntentRouterCore.shouldRoute]）：寒暄/明显开放问答直通 OPEN_QA，
 *   零额外延迟；门控只放行不拦截（误判代价 = 多走一次全量 agent，与现状同，无回退风险）。
 * - **pattern 捷径**（[IntentRouterCore.matchPatternShortcut]）：仅「看/找/看看/给我看…照片」
 *   最热句式零延迟短路；配负面样例（"看看照片里有没有糊的"是 ANALYZE 非 VIEW）防劫持。
 * - **1.5s 硬超时 + schema 失败重试 1 次 + 同路降级**：网络错/schema 仍败/超时/低置信
 *   一律落 OPEN_QA（现有完整 agent loop，优雅退化）。
 * - person 槽位只原样透传称谓/人物名，实体消歧在端侧（关系词表 + 搜索引擎人物解析）。
 *
 * 路由审计：每次判定（含降级/门控直通）经 [recorder] 落 turn 级审计（spec §3.7 前半），
 * 使「护栏前路由正确率」可观测（M2 验收指标）。
 */

/** 路由器消费的紧凑对话状态（初值 2 轮窗口的退化版：上轮 artifact + 搜索基数存在性）。 */
data class CompactChatState(
    val lastArtifact: UiArtifact?,
    val hasSearchBase: Boolean,
)

/** 路由器结构化输出（策略层消费；person 槽位为原词，消歧在端侧）。 */
data class RouterOutput(
    val deliverable: IntentId,
    val confidence: Double,
    val isRefinement: Boolean,
    val secondary: IntentId?,
    val person: String?,
    val fromMs: Long?,
    val toMs: Long?,
    val label: String?,
    val constraint: String?,
)

/** 路由路径（审计维度）：pattern 捷径 / LLM 路由器 / 门控直通 / 降级。 */
enum class RoutePath {
    PATTERN_SHORTCUT,
    LLM_ROUTER,
    GATED_PASSTHROUGH,
    DEGRADED_TIMEOUT,
    DEGRADED_SCHEMA,
    DEGRADED_NETWORK,
    DEGRADED_LOW_CONFIDENCE,
    DEGRADED_NO_EXECUTOR,
}

/** 一次路由判定的完整结果（供策略层 + 审计）。 */
data class RoutingResult(
    val output: RouterOutput,
    val path: RoutePath,
    val latencyMs: Long,
    val degradeReason: String? = null,
)

/** turn 级路由审计记录（polang_llm_log.db 第四张表 routing_audit_log）。 */
data class RoutingAuditRecord(
    val createdAt: Long,
    val traceId: String?,
    val path: String,
    val deliverable: String,
    val confidence: Double?,
    val isRefinement: Boolean,
    val latencyMs: Long,
    val degradeReason: String?,
)

/** 路由审计落库口（与 CommandExecutionRecorder 同范式：shared 定义接口，Android Room 实现注入）。 */
fun interface RoutingAuditRecorder {
    fun record(record: RoutingAuditRecord)
}

/** 路由器的纯函数内核（门控/pattern/解析/prompt 组装），commonTest 直接覆盖。 */
object IntentRouterCore {

    /** 置信度降级阈值（spec §9 初值 0.6，M2 期间按误路由率调）。 */
    const val CONFIDENCE_THRESHOLD = 0.6

    /** 路由器硬超时（spec §3.2：1.5s）。 */
    const val ROUTER_TIMEOUT_MS = 1500L

    /**
     * 本地信号门控：相册域信号命中才放行给路由器（含 pattern）。
     * 只放行不拦截——未命中 = 直通 OPEN_QA（零成本，行为与现状一致）。
     *
     * 信号分两层（review 决议，防「含常用动词即放行」导致门控形同虚设）：
     * - 强信号（媒体名词/相册域词/应用域动作词）：命中即放行；
     * - 弱信号（单字动词 看/找/搜/画）：必须与媒体名词共现才放行
     *   （"你看这事怎么办"不进路由器，"看下我儿子的照片"经「照片」强信号已放行）。
     */
    fun shouldRoute(query: String): Boolean =
        GALLERY_STRONG_SIGNALS.any { signal -> query.contains(signal) } ||
            (
                VIEW_VERBS.any { verb -> query.contains(verb) } &&
                    MEDIA_NOUNS.any { noun -> query.contains(noun) }
                )

    /**
     * pattern 捷径：仅「看/找/搜 + … + 照片/图片/合照」最热句式短路（零延迟零成本）。
     * 返回 null = 不命中，归路由器。负面样例（分析/计数/画图语素）显式排除，防捷径劫持语义。
     */
    fun matchPatternShortcut(query: String): RouterOutput? {
        val trimmed = query.trim()
        if (trimmed.length > 40) return null // 长句语义开放，归路由器
        if (NEGATIVE_MORPHEMES.any { trimmed.contains(it) }) return null
        if (MEDIA_NOUNS.none { trimmed.contains(it) }) return null
        if (VIEW_VERBS.none { trimmed.contains(it) }) return null
        return RouterOutput(
            deliverable = IntentId.VIEW_PHOTOS,
            confidence = 1.0,
            isRefinement = false,
            secondary = null,
            person = null,
            fromMs = null,
            toMs = null,
            label = null,
            constraint = null,
        )
    }

    /**
     * 解析路由器 LLM 输出（容错：截取首个 JSON 对象、忽略未知字段、枚举/类型失败返回 null）。
     * null = schema 校验失败（调用方重试 1 次后降级）。
     */
    fun parseRouterOutput(raw: String): RouterOutput? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val dto = runCatching {
            ROUTER_JSON.decodeFromString<RouterOutputDto>(raw.substring(start, end + 1))
        }.getOrNull() ?: return null
        val deliverable = runCatching { IntentId.valueOf(dto.deliverable) }.getOrNull() ?: return null
        // secondary 枚举非法同样判 schema 失败（触发重试/降级）——吞掉会把双产出物请求
        // 静默降级为单产出物直执（"找照片并画图"只出卡片不出图），比回落 OPEN_QA 更糟。
        val secondary = dto.secondary?.let { value ->
            runCatching { IntentId.valueOf(value) }.getOrNull() ?: return null
        }
        val confidence = dto.confidence
        if (confidence == null || confidence < 0.0 || confidence > 1.0) return null
        return RouterOutput(
            deliverable = deliverable,
            confidence = confidence,
            isRefinement = dto.isRefinement ?: false,
            secondary = secondary,
            person = dto.person?.trim()?.ifBlank { null },
            fromMs = dto.fromMs,
            toMs = dto.toMs,
            label = dto.label?.trim()?.ifBlank { null },
            constraint = dto.constraint?.trim()?.ifBlank { null },
        )
    }

    /** 路由器 system prompt（闭集分类契约；~1k token 级，非 34k 全量规则）。 */
    const val ROUTER_SYSTEM_PROMPT: String = """你是意图路由器。把用户的话分类到唯一「最终交付物」意图并抽取槽位，只输出 JSON，不要输出任何其它文字。
意图闭集（deliverable 只能取其一）：
- VIEW_PHOTOS：看/找/搜索照片或图片（交付物=照片横滑卡片）
- REFINE_RESULTS：在上一轮搜索结果上加条件窄化（"只要4月的""换成夜景""再去掉截图"）
- ANALYZE_STATS：统计/盘点/数量/质量分析类（"有多少照片""盘点一下""有没有糊的"），交付物=文字结论
- DRAW_CHART：明确要求画图/图表/趋势图
- EDIT_IMAGE：修图/美颜/调色/优化某张图片
- RENDER_RICH_HTML：明确要求做 HTML 卡片/可交互组件/报告页
- MEMORY：记住/忘掉/查询事实或人物关系（"记住小宝是我儿子""我女儿是谁"）
- NAVIGATE：明确口令跳转页面（"打开相机""去设置""返回"）
- SETTINGS：改设置（主题/语言/开关/下载模型）
- OPEN_QA：其它一切（闲聊/知识问答/无法确定一律归此）
规则：
- 按最终交付物分类，不按动作分类；"找照片并画图"主交付物取用户强调的那个，另一个填 secondary。
- isRefinement=true 仅当本轮明显在上一轮搜索结果上窄化（有指代/加条件）。
- 槽位：person=人物名或称谓原词（如"儿子""大宝"，不做消歧）；fromMs/toMs=毫秒时间戳（按当前日期换算"上个月""去年"等）；label=场景/标签词；constraint=窄化条件原文。没有则为 null。
- confidence：0~1，不确定就低分（低分会自动回落完整助手，不要硬猜）。
只输出一行 JSON：{"deliverable":"...","confidence":0.0,"isRefinement":false,"secondary":null,"person":null,"fromMs":null,"toMs":null,"label":null,"constraint":null}
示例：
用户"看下我儿子的照片" → {"deliverable":"VIEW_PHOTOS","confidence":0.95,"isRefinement":false,"secondary":null,"person":"儿子","fromMs":null,"toMs":null,"label":null,"constraint":null}
用户"只要4月的"（上一轮有搜索卡片） → {"deliverable":"REFINE_RESULTS","confidence":0.9,"isRefinement":true,"secondary":null,"person":null,"fromMs":null,"toMs":null,"label":null,"constraint":"4月的"}
用户"看看照片里有没有糊的" → {"deliverable":"ANALYZE_STATS","confidence":0.8,"isRefinement":false,"secondary":null,"person":null,"fromMs":null,"toMs":null,"label":"糊","constraint":null}
用户"今天天气怎么样" → {"deliverable":"OPEN_QA","confidence":0.95,"isRefinement":false,"secondary":null,"person":null,"fromMs":null,"toMs":null,"label":null,"constraint":null}"""

    /** 路由器 user payload：当前日期 + 紧凑对话状态 + 用户原文。 */
    fun buildUserPayload(query: String, today: String, state: CompactChatState): String =
        "当前日期：$today\n" +
            "对话状态：上一轮交付物=${state.lastArtifact?.name ?: "无"}；搜索基数=${if (state.hasSearchBase) "有" else "无"}\n" +
            "用户：$query"

    private val ROUTER_JSON = Json { ignoreUnknownKeys = true }

    // 门控强信号：媒体名词 + 相册域词 + 应用域动作词（命中即放行）
    private val GALLERY_STRONG_SIGNALS = listOf(
        "照片", "图片", "相册", "视频", "合照", "截图", "图表",
        "统计", "盘点", "几张", "趋势", "分布", "记住", "忘掉", "忘记",
        "打开", "返回", "设置", "主题", "语言", "删除", "分享", "收藏",
        "优化", "美颜", "磨皮", "瘦脸", "美白", "滤镜", "调亮", "调暗",
    )

    // pattern 捷径的媒体名词与观看动词（最热句式）；弱信号层共用（需与媒体名词共现）
    private val MEDIA_NOUNS = listOf("照片", "图片", "合照", "视频", "相册", "截图")
    private val VIEW_VERBS = listOf("看", "找", "搜", "画")

    // 捷径负面语素：命中则语义非「看照片」（分析/计数/画图/疑问），归路由器
    private val NEGATIVE_MORPHEMES = listOf(
        "有没有", "多少", "几", "趋势", "统计", "分布", "盘点", "画", "糊", "清晰", "吗", "什么",
    )

    /** 路由器输出的 JSON DTO（宽容解析：字段全可空，缺省即缺省）。 */
    @Serializable
    internal data class RouterOutputDto(
        val deliverable: String,
        val confidence: Double? = null,
        val isRefinement: Boolean? = null,
        val secondary: String? = null,
        val person: String? = null,
        val fromMs: Long? = null,
        val toMs: Long? = null,
        val label: String? = null,
        val constraint: String? = null,
    )
}

/**
 * 意图路由器实例（挂在 RemoteChatEngine chat 入口，PrivacyGuard 之后、agent loop 之前）。
 *
 * [executorBundleProvider] 供给当前远程配置的 Koog 执行器包（与 chat agent 同源配置，
 * 可独立换强模型）；为 null（远程未配置等）时全部流量直通 OPEN_QA。
 */
class IntentRouter(
    private val executorBundleProvider: () -> RemoteModelFactory.KoogExecutorBundle?,
) {
    private val tag = "IntentRouter"

    companion object {
        /** 路由审计落库口（组合根注入 Room 实现；未注入时仅打日志）。 */
        @Volatile
        var recorder: RoutingAuditRecorder? = null

        /** llm_call_log 里路由器调用的 source 标识（与 chat 主链路区分）。 */
        const val RECORD_SOURCE = "chat-intent-router"
    }

    /**
     * 路由判定：门控 → pattern 捷径 → LLM 闭集分类（1.5s 硬超时 + schema 重试 1 次）
     * → 失败分类同路降级 OPEN_QA。任何路径都会落审计记录。
     */
    suspend fun route(
        query: String,
        state: CompactChatState,
        today: String,
        traceId: String?,
    ): RoutingResult {
        val started = Clock.System.now().toEpochMilliseconds()

        // ① 本地信号门控：寒暄/开放问答直通，零额外延迟
        if (!IntentRouterCore.shouldRoute(query)) {
            return RoutingResult(openQaOutput(), RoutePath.GATED_PASSTHROUGH, 0)
                .also { result -> audit(result, traceId) }
        }

        // ② pattern 捷径：最热句式零延迟短路
        IntentRouterCore.matchPatternShortcut(query)?.let { shortcut ->
            return RoutingResult(shortcut, RoutePath.PATTERN_SHORTCUT, 0)
                .also { result -> audit(result, traceId) }
        }

        // ③ LLM 闭集分类
        val bundle = executorBundleProvider()
        if (bundle == null) {
            return RoutingResult(openQaOutput(), RoutePath.DEGRADED_NO_EXECUTOR, 0, "executor unavailable")
                .also { result -> audit(result, traceId) }
        }
        repeat(2) { attempt ->
            // 单次尝试 1.5s 硬超时；schema 重试不共享预算（最坏 2×1.5s 才降级，spec §3.2 注记）
            val raw = runCatching {
                withTimeout(IntentRouterCore.ROUTER_TIMEOUT_MS) {
                    callRouterLlm(bundle, query, state, today, traceId)
                }
            }.getOrElse { error ->
                val path = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    RoutePath.DEGRADED_TIMEOUT
                } else {
                    RoutePath.DEGRADED_NETWORK
                }
                Logger.w(tag, "router call failed (attempt $attempt): ${error.message}")
                return RoutingResult(
                    openQaOutput(), path,
                    Clock.System.now().toEpochMilliseconds() - started,
                    error.message,
                ).also { result -> audit(result, traceId) }
            }
            val parsed = IntentRouterCore.parseRouterOutput(raw)
            if (parsed != null) {
                val latency = Clock.System.now().toEpochMilliseconds() - started
                // 低置信同路降级（spec §3.2）
                if (parsed.confidence < IntentRouterCore.CONFIDENCE_THRESHOLD) {
                    return RoutingResult(
                        openQaOutput(), RoutePath.DEGRADED_LOW_CONFIDENCE, latency,
                        "confidence=${parsed.confidence}",
                    ).also { result -> audit(result, traceId) }
                }
                return RoutingResult(parsed, RoutePath.LLM_ROUTER, latency)
                    .also { result -> audit(result, traceId) }
            }
            Logger.w(tag, "router schema invalid (attempt $attempt): ${raw.take(120)}")
        }
        // schema 重试仍败 → 降级（degradeReason 只记分类标签：模型原始输出可能夹带用户原话
        // 片段（constraint/person 槽位），routing_audit_log 不落用户输入内容——隐私红线）
        return RoutingResult(
            openQaOutput(), RoutePath.DEGRADED_SCHEMA,
            Clock.System.now().toEpochMilliseconds() - started,
            "schema invalid after retry",
        ).also { result -> audit(result, traceId) }
    }

    /** 路由器 LLM 单次调用（temperature=0，复用 chat 同源 executor/model，输出取 Text parts）。 */
    private suspend fun callRouterLlm(
        bundle: RemoteModelFactory.KoogExecutorBundle,
        query: String,
        state: CompactChatState,
        today: String,
        traceId: String?,
    ): String {
        val params = bundle.baseParams.copy(temperature = 0.0, maxTokens = 256)
        val routerPrompt = prompt(id = "polang-intent-router", params = params) {
            system(IntentRouterCore.ROUTER_SYSTEM_PROMPT)
            user(IntentRouterCore.buildUserPayload(query, today, state))
        }
        val started = Clock.System.now().toEpochMilliseconds()
        // Koog 1.3.0：execute 返回单个 Message.Assistant，文本在 parts（ResponsePart）里
        val response = try {
            bundle.executor.execute(routerPrompt, bundle.model, emptyList())
        } catch (e: Exception) {
            // 失败同样落 llm_call_log（降级率需与推理侧错误详情交叉，spec §3.7）
            recordRouterCall(bundle, started, traceId, success = false, text = null, error = e.message)
            throw e
        }
        val text = response.parts
            .filterIsInstance<MessagePart.Text>()
            .joinToString("") { part -> part.text }
        // 推理层审计（llm_call_log）：路由器调用与 chat 主链路同表不同 source，traceId 串联
        recordRouterCall(bundle, started, traceId, success = true, text = text, error = null)
        return text
    }

    private fun recordRouterCall(
        bundle: RemoteModelFactory.KoogExecutorBundle,
        started: Long,
        traceId: String?,
        success: Boolean,
        text: String?,
        error: String?,
    ) {
        runCatching {
            RemoteModelFactory.recorder?.record(
                LlmCallRecord(
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    source = RECORD_SOURCE,
                    model = bundle.model.id,
                    success = success,
                    latencyMs = Clock.System.now().toEpochMilliseconds() - started,
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    requestJson = "",
                    responseJson = if (RemoteModelFactory.captureContent) text?.take(512) else null,
                    errorMessage = error?.take(200),
                    traceId = traceId,
                )
            )
        }
    }

    private fun openQaOutput(): RouterOutput = RouterOutput(
        deliverable = IntentId.OPEN_QA,
        confidence = 0.0,
        isRefinement = false,
        secondary = null,
        person = null,
        fromMs = null,
        toMs = null,
        label = null,
        constraint = null,
    )

    private fun audit(result: RoutingResult, traceId: String?) {
        Logger.i(
            tag,
            "route: path=${result.path} deliverable=${result.output.deliverable} " +
                "confidence=${result.output.confidence} latency=${result.latencyMs}ms " +
                "degrade=${result.degradeReason}"
        )
        runCatching {
            recorder?.record(
                RoutingAuditRecord(
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    traceId = traceId,
                    path = result.path.name,
                    deliverable = result.output.deliverable.name,
                    confidence = result.output.confidence,
                    isRefinement = result.output.isRefinement,
                    latencyMs = result.latencyMs,
                    degradeReason = result.degradeReason,
                )
            )
        }
    }
}
