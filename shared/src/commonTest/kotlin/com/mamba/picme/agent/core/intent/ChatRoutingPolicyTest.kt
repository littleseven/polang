package com.mamba.picme.agent.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 确定性路由策略层测试（spec §3.3）：直执判定、refine 基数规则、回落规则。
 */
class ChatRoutingPolicyTest {

    private fun routerOutput(
        deliverable: IntentId,
        confidence: Double = 0.9,
        isRefinement: Boolean = false,
        secondary: IntentId? = null,
        person: String? = null,
        constraint: String? = null,
    ) = RouterOutput(
        deliverable = deliverable,
        confidence = confidence,
        isRefinement = isRefinement,
        secondary = secondary,
        person = person,
        fromMs = null,
        toMs = null,
        label = null,
        constraint = constraint,
    )

    private fun result(
        output: RouterOutput,
        path: RoutePath = RoutePath.LLM_ROUTER,
    ) = RoutingResult(output, path, latencyMs = 10)

    private val noBase = CompactChatState(lastArtifact = null, hasSearchBase = false)
    private val withBase = CompactChatState(lastArtifact = UiArtifact.MEDIA_RESULTS_CARD, hasSearchBase = true)

    // ── 直执判定 ────────────────────────────────────────────────────

    @Test
    fun `view photos routes to direct search with original query`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, person = "儿子")),
            noBase,
            originalQuery = "看下我儿子的照片",
        )
        val direct = assertIs<ChatRoutingPolicy.RouteDecision.DirectSearch>(decision)
        // query 一律用用户原文（引擎层 PersonQueryResolver 从原文兜底解析称谓）
        assertEquals("看下我儿子的照片", direct.query)
        // person 槽位补结构化精度
        assertEquals("儿子", direct.intent?.personName)
    }

    @Test
    fun `pattern shortcut result also direct-executes`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, confidence = 1.0), RoutePath.PATTERN_SHORTCUT),
            noBase,
            originalQuery = "给我看去年夏天的照片",
        )
        val direct = assertIs<ChatRoutingPolicy.RouteDecision.DirectSearch>(decision)
        assertEquals("给我看去年夏天的照片", direct.query)
        // pattern 捷径无槽位 → intent 为 null
        assertNull(direct.intent)
    }

    // ── refine 基数规则 ─────────────────────────────────────────────

    @Test
    fun `refine intent with search base routes to direct refine`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.REFINE_RESULTS, isRefinement = true, constraint = "4月的")),
            withBase,
            originalQuery = "只要4月的",
        )
        val direct = assertIs<ChatRoutingPolicy.RouteDecision.DirectRefine>(decision)
        assertEquals("4月的", direct.constraint)
    }

    @Test
    fun `refine intent without search base degrades to fresh search`() {
        // 基数缺失 → refine 退化为 SearchMedia（spec §3.3：不再依赖模型记住上一轮）
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.REFINE_RESULTS, isRefinement = true, constraint = "4月的")),
            noBase,
            originalQuery = "只要4月的",
        )
        assertIs<ChatRoutingPolicy.RouteDecision.DirectSearch>(decision)
    }

    @Test
    fun `view photos marked refinement with base routes to direct refine`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, isRefinement = true)),
            withBase,
            originalQuery = "换成夜景的",
        )
        assertIs<ChatRoutingPolicy.RouteDecision.DirectRefine>(decision)
    }

    // ── 回落规则 ────────────────────────────────────────────────────

    @Test
    fun `secondary output falls back to full agent loop`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, secondary = IntentId.DRAW_CHART)),
            noBase,
            originalQuery = "找照片并画图",
        )
        assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
    }

    @Test
    fun `degraded paths fall back to full agent loop`() {
        listOf(
            RoutePath.GATED_PASSTHROUGH,
            RoutePath.DEGRADED_TIMEOUT,
            RoutePath.DEGRADED_SCHEMA,
            RoutePath.DEGRADED_NETWORK,
            RoutePath.DEGRADED_LOW_CONFIDENCE,
            RoutePath.DEGRADED_NO_EXECUTOR,
        ).forEach { path ->
            val decision = ChatRoutingPolicy.decide(
                result(routerOutput(IntentId.VIEW_PHOTOS), path),
                withBase,
                originalQuery = "看照片",
            )
            assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision, "path=$path 应回落")
        }
    }

    @Test
    fun `non-direct intents fall back to full agent loop`() {
        listOf(
            IntentId.ANALYZE_STATS, IntentId.DRAW_CHART, IntentId.EDIT_IMAGE,
            IntentId.RENDER_RICH_HTML, IntentId.MEMORY, IntentId.NAVIGATE,
            IntentId.SETTINGS, IntentId.OPEN_QA,
        ).forEach { intent ->
            val decision = ChatRoutingPolicy.decide(
                result(routerOutput(intent)),
                withBase,
                originalQuery = "任意输入",
            )
            assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision, "intent=$intent 应回落")
        }
    }
}
