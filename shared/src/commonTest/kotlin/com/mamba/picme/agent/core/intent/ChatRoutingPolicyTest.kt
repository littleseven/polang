package com.mamba.picme.agent.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 确定性路由策略层测试（spec §3.3）：回落规则与契约工具子集。
 *
 * 2026-11-08 原生横滑相册卡退役（spec《media-results-carousel-retirement》）：
 * VIEW_PHOTOS/REFINE_RESULTS 直执路径（DirectSearch/DirectRefine）整体删除，
 * 全意图一律回落 [ChatRoutingPolicy.RouteDecision.FullAgentLoop] 携带契约工具子集——
 * 看照片交付物改为 LLM 回合内 search_media 取数 + render_html 出图集卡。
 */
class ChatRoutingPolicyTest {

    private fun routerOutput(
        deliverable: IntentId,
        confidence: Double = 0.9,
        isRefinement: Boolean = false,
        secondary: IntentId? = null,
        person: String? = null,
        constraint: String? = null,
        hasFace: Boolean? = null,
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
        hasFace = hasFace,
    )

    private fun result(
        output: RouterOutput,
        path: RoutePath = RoutePath.LLM_ROUTER,
    ) = RoutingResult(output, path, latencyMs = 10)

    // ── 全意图回落 agent loop（直执已退役）───────────────────────────────

    @Test
    fun `view photos falls back to agent loop carrying gallery card subset`() {
        // 直执退役后的替代覆盖：看照片不再直执 search_media，回落 agent loop
        // 由 LLM 回合内取数 + render_html 出卡；子集带齐取数/出卡工具。
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, person = "儿子")),
        )
        val loop = assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
        assertEquals("intent:VIEW_PHOTOS", loop.reason)
        assertEquals(
            setOf("search_media", "refine_media_search", "list_person_relations", "render_html", "finish", "delay"),
            loop.toolNames,
        )
    }

    @Test
    fun `refine results falls back to agent loop carrying refine subset`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.REFINE_RESULTS, isRefinement = true, constraint = "4月的")),
        )
        val loop = assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
        assertEquals(
            setOf("refine_media_search", "search_media", "render_html", "finish", "delay"),
            loop.toolNames,
        )
    }

    @Test
    fun `pattern shortcut view photos also falls back to agent loop`() {
        // pattern 捷径命中 VIEW_PHOTOS 同样不直执（捷径只产意图，策略层无差别回落）
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, confidence = 1.0), RoutePath.PATTERN_SHORTCUT),
        )
        assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
    }

    // ── 回落规则 ────────────────────────────────────────────────────

    @Test
    fun `secondary output falls back to full agent loop`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, secondary = IntentId.DRAW_CHART)),
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
            )
            assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision, "path=$path 应回落")
        }
    }

    @Test
    fun `non-gallery intents fall back to full agent loop`() {
        listOf(
            IntentId.ANALYZE_STATS, IntentId.DRAW_CHART, IntentId.EDIT_IMAGE,
            IntentId.RENDER_RICH_HTML, IntentId.MEMORY, IntentId.NAVIGATE,
            IntentId.SETTINGS, IntentId.OPEN_QA,
        ).forEach { intent ->
            val decision = ChatRoutingPolicy.decide(
                result(routerOutput(intent)),
            )
            assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision, "intent=$intent 应回落")
        }
    }

    // ── BROWSE_WEB（浏览器任务走 agent loop，绝不落相册工具子集）────────────

    @Test
    fun `browse web routes to full agent loop with intent reason`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.BROWSE_WEB)),
        )
        val loop = assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
        assertTrue("BROWSE_WEB" in loop.reason, "reason 应含 BROWSE_WEB: ${loop.reason}")
    }

    @Test
    fun `browser shortcut path dispatches by intent not bounced by path check`() {
        // BROWSER_SHORTCUT 与 LLM_ROUTER/PATTERN_SHORTCUT 同权：reason 记 intent 而非 path，
        // 审计不失真，未来直执扩展不被 path 检查挡
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.BROWSE_WEB, confidence = 1.0), RoutePath.BROWSER_SHORTCUT),
        )
        val loop = assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
        assertEquals("intent:BROWSE_WEB", loop.reason)
    }

    // ── 意图级工具子集（M3，2026-10-17：契约 allowedTools 消费点）────────────

    @Test
    fun `browse web loop carries browser tool subset plus universal tools`() {
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.BROWSE_WEB)),
        )
        val loop = assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
        assertEquals(
            setOf(
                "browser_open", "browser_navigate", "browser_click", "browser_type",
                "browser_extract", "browser_screenshot", "browser_close",
                "finish", "delay",
            ),
            loop.toolNames,
        )
        // forbiddenTools（search_media/refine_media_search/run_gallery_script）不得混入
        assertTrue("run_gallery_script" !in loop.toolNames!!)
        assertTrue("search_media" !in loop.toolNames)
    }

    @Test
    fun `single-intent fallbacks carry contract subset`() {
        val expected = mapOf(
            // 看照片/窄化：横滑卡退役后的新子集（取数 + render_html 出图集卡）
            IntentId.VIEW_PHOTOS to setOf(
                "search_media", "refine_media_search", "list_person_relations", "render_html",
            ),
            IntentId.REFINE_RESULTS to setOf("refine_media_search", "search_media", "render_html"),
            IntentId.ANALYZE_STATS to setOf("run_gallery_script", "get_gallery_summary"),
            IntentId.DRAW_CHART to setOf("run_gallery_script", "draw_chart", "get_gallery_summary"),
            IntentId.EDIT_IMAGE to setOf("adjust_image", "edit_image", "ai_optimize"),
            IntentId.RENDER_RICH_HTML to setOf("run_gallery_script", "render_html", "draw_chart", "search_media"),
            IntentId.MEMORY to setOf(
                "remember_fact", "forget_fact", "recall_memory",
                "remember_person_relation", "forget_person_relation", "list_person_relations",
                "recall_chat_history",
            ),
            IntentId.NAVIGATE to setOf("navigate_to", "go_back", "launch_app", "open_system_settings"),
            IntentId.SETTINGS to setOf(
                "change_theme", "change_language", "toggle_setting", "download_model", "switch_face_engine",
            ),
        )
        expected.forEach { (intent, tools) ->
            val decision = ChatRoutingPolicy.decide(
                result(routerOutput(intent)),
            )
            val loop = assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision, "intent=$intent")
            assertEquals(tools + setOf("finish", "delay"), loop.toolNames, "intent=$intent 子集")
        }
    }

    @Test
    fun `open qa keeps full tool surface`() {
        // OPEN_QA allowedTools 为空（契约：M3 再按使用率裁剪）→ null = 完整工具面
        val decision = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.OPEN_QA)),
        )
        val loop = assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(decision)
        assertNull(loop.toolNames)
    }

    @Test
    fun `secondary or degraded paths keep full tool surface`() {
        // secondary 双产出物：判定不可靠，保守全量
        val secondary = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.VIEW_PHOTOS, secondary = IntentId.DRAW_CHART)),
        )
        assertNull(assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(secondary).toolNames)
        // 降级路径：无可靠意图，保守全量
        val degraded = ChatRoutingPolicy.decide(
            result(routerOutput(IntentId.BROWSE_WEB), RoutePath.DEGRADED_TIMEOUT),
        )
        assertNull(assertIs<ChatRoutingPolicy.RouteDecision.FullAgentLoop>(degraded).toolNames)
    }
}
