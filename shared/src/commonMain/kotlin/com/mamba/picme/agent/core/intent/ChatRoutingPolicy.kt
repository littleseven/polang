package com.mamba.picme.agent.core.intent

import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange

/**
 * 确定性路由策略层（spec §3.3）：意图 → 命令的**查表执行**，纯函数、可单测。
 *
 * 与 LLM 的分工（决策 D2）：[IntentRouter] 输出意图与槽位（语义理解），本层把意图
 * 映射为确定性命令（策略执行）——模型不再在互斥规则里自由选工具。
 *
 * M2 范围：仅 VIEW_PHOTOS / REFINE_RESULTS 直执（搜索命令是固定形态、无需生成）；
 * 其余意图（含 secondary 双产出物）一律回落 [RouteDecision.FullAgentLoop]——
 * 路由器永不拦截能力，混合长尾天然落回兜底（M3 分支化后再逐个接管）。
 */
object ChatRoutingPolicy {

    /** 路由决策（策略层输出）。 */
    sealed interface RouteDecision {
        /** 直执 search_media（必出横滑卡片：dispatch 链路与工具面同源）。 */
        data class DirectSearch(val query: String, val intent: SearchIntent?) : RouteDecision

        /** 直执 refine_media_search（在上一轮基数内取交集）。 */
        data class DirectRefine(val constraint: String, val intent: SearchIntent?) : RouteDecision

        /** 回落完整 agent loop（[reason] 进路由审计，供降级率/放行率统计）。 */
        data class FullAgentLoop(val reason: String) : RouteDecision
    }

    /**
     * 查表分发：[RoutingResult] × 紧凑对话状态 → [RouteDecision]。
     *
     * refine 判定规则（spec §3.3）：refine 语义的产生者是路由器（isRefinement），
     * 本层只消费不再猜；基数缺失时 refine 退化为 fresh SearchMedia（不再依赖模型记住上一轮）。
     *
     * [originalQuery] 为用户原文：直执搜索的 query 一律用它（引擎层 PersonQueryResolver
     * 会从原文再解析人物称谓兜底），路由器槽位只补结构化精度（person/fromMs/toMs/label）。
     */
    fun decide(result: RoutingResult, state: CompactChatState, originalQuery: String): RouteDecision {
        val output = result.output
        // 非 LLM 判定路径（门控直通/降级）一律回落完整 agent
        if (result.path != RoutePath.LLM_ROUTER && result.path != RoutePath.PATTERN_SHORTCUT) {
            return RouteDecision.FullAgentLoop("path:${result.path}")
        }
        // 双产出物（secondary 非空）M2 暂回落全量 agent 顺序处理（M3 分支化后顺序分发）
        if (output.secondary != null) {
            return RouteDecision.FullAgentLoop("secondary:${output.secondary}")
        }
        return when (output.deliverable) {
            IntentId.VIEW_PHOTOS ->
                if (output.isRefinement && state.hasSearchBase) {
                    RouteDecision.DirectRefine(
                        constraint = output.constraint ?: originalQuery,
                        intent = output.toSearchIntent(query = originalQuery),
                    )
                } else {
                    RouteDecision.DirectSearch(
                        query = originalQuery,
                        intent = output.toSearchIntent(query = originalQuery),
                    )
                }
            IntentId.REFINE_RESULTS ->
                if (state.hasSearchBase) {
                    RouteDecision.DirectRefine(
                        constraint = output.constraint ?: originalQuery,
                        intent = output.toSearchIntent(query = originalQuery),
                    )
                } else {
                    // 基数缺失 → 先 SearchMedia（spec §3.3）
                    RouteDecision.DirectSearch(
                        query = originalQuery,
                        intent = output.toSearchIntent(query = originalQuery),
                    )
                }
            else -> RouteDecision.FullAgentLoop("intent:${output.deliverable}")
        }
    }

    /**
     * 路由器槽位 → [SearchIntent]（person 原词透传，称谓消歧在引擎层
     * PersonQueryResolver / collectPersonMediaIds 完成）。
     */
    private fun RouterOutput.toSearchIntent(query: String): SearchIntent? {
        val hasTime = fromMs != null || toMs != null
        if (person == null && !hasTime && label == null) return null
        return SearchIntent(
            query = query,
            timeRange = if (hasTime) {
                TimeRange(startMs = fromMs ?: 0L, endMs = toMs ?: Long.MAX_VALUE)
            } else {
                null
            },
            keywords = label?.let { listOf(it) } ?: emptyList(),
            personName = person,
        )
    }
}
