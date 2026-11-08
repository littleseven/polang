package com.mamba.picme.agent.core.intent

/**
 * 确定性路由策略层（spec §3.3）：意图 → 路由决策的**查表执行**，纯函数、可单测。
 *
 * 与 LLM 的分工（决策 D2）：[IntentRouter] 输出意图与槽位（语义理解），本层把意图
 * 映射为确定性决策（策略执行）——模型不再在互斥规则里自由选工具。
 *
 * 2026-11-08 原生横滑相册卡退役（spec《media-results-carousel-retirement》）：
 * VIEW_PHOTOS/REFINE_RESULTS 直执路径（DirectSearch/DirectRefine）整体删除——
 * 看照片交付物改为 LLM 回合内 search_media 取数 + render_html 出图集卡，
 * 全意图一律回落 [RouteDecision.FullAgentLoop] 携带契约工具子集（M3 分支化裁剪）。
 * 路由器永不拦截能力，混合长尾天然落回兜底。
 */
object ChatRoutingPolicy {

    /** 路由决策（策略层输出）。 */
    sealed interface RouteDecision {
        /**
         * 回落完整 agent loop（[reason] 进路由审计，供降级率/放行率统计）。
         *
         * [toolNames] = 意图级工具子集（M3 分支化落地，2026-10-17）：契约表 allowedTools +
         * 通用工具（finish/delay），引擎层据此裁剪 tool schema 与 prompt 清单段（每轮
         * 请求不再全量携带 43 个 schema，成本治理）；null = 完整工具面（OPEN_QA / 降级路径 /
         * secondary 双产出物等判定不可靠场景一律保守全量，路由器永不拦截能力）。
         */
        data class FullAgentLoop(val reason: String, val toolNames: Set<String>? = null) : RouteDecision
    }

    /**
     * 任何子集都保留的通用工具：finish（终止语义兜底——规则虽要求「不要调用 finish」，
     * 但裁掉 schema 后模型一旦旧习复发直接报未知工具错误，留它成本仅一条 schema）与 delay。
     */
    private val UNIVERSAL_TOOLS = setOf("finish", "delay")

    /**
     * 意图 → 工具子集（契约表 allowedTools 的消费点，原「M3 分支化时裁剪工具面的依据」落地）。
     * allowedTools 为空（OPEN_QA）→ null 保留完整工具面；forbiddenTools 由契约校验
     * （allowed∩forbidden=∅，ChatIntentContractTest）保证不会混入白名单。
     */
    internal fun toolSubsetFor(intent: IntentId): Set<String>? {
        val allowed = ChatIntentContract.of(intent).allowedTools
        if (allowed.isEmpty()) return null
        return allowed.toSet() + UNIVERSAL_TOOLS
    }

    /**
     * 查表分发：[RoutingResult] → [RouteDecision.FullAgentLoop]（唯一决策形态，
     * 直执路径已随横滑卡退役；返回具体类型免调用方 when 分派）。
     *
     * 非 LLM 判定路径（门控直通/降级）与 secondary 双产出物一律保守全量（toolNames=null）；
     * 单意图判定可靠时才携带契约工具子集（引擎层裁剪 schema）。
     */
    fun decide(result: RoutingResult): RouteDecision.FullAgentLoop {
        val output = result.output
        // 非 LLM 判定路径（门控直通/降级）一律回落完整 agent
        if (result.path != RoutePath.LLM_ROUTER &&
            result.path != RoutePath.PATTERN_SHORTCUT &&
            result.path != RoutePath.BROWSER_SHORTCUT
        ) {
            return RouteDecision.FullAgentLoop("path:${result.path}")
        }
        // 双产出物（secondary 非空）回落全量 agent 顺序处理（判定不可靠场景保守全量）
        if (output.secondary != null) {
            return RouteDecision.FullAgentLoop("secondary:${output.secondary}")
        }
        // 全意图（含 VIEW_PHOTOS/REFINE_RESULTS）回落 agent loop，携带契约工具子集；
        // 浏览器任务的 forbiddenTools 契约锚点见 ChatIntentContract.BROWSE_WEB。
        return RouteDecision.FullAgentLoop(
            reason = "intent:${output.deliverable}",
            toolNames = toolSubsetFor(output.deliverable),
        )
    }
}
