package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolManifest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS chat prompt 的诚实性守卫（Phase 6.2 T3）：prompt 不得引用 iOS 未注册的能力，
 * 防止 LLM 幻觉调用不存在的工具。
 */
class IosChatPromptTest {

    private val prompt = IosChatPrompt.build(ChatToolManifest.buildDescriptors())

    @Test
    fun `prompt contains tool inventory for the 9 registered tools`() {
        // 2026-08-16 抽卡追齐：8 相册工具 + ai_optimize；2026-09-26 意图路由 M1：view_media 移出 chat 工具面（9aacc1fa4）；
        // 2026-11-08 横滑卡退役追齐（终审 🔴2）：补登 render_html（search/refine 描述指引的出卡工具必须在场）
        assertTrue(prompt.contains("可用工具（9）"), "应含 9 工具清单头，实际 prompt 头部：${prompt.take(200)}")
        assertTrue(prompt.contains("search_media"))
        assertTrue(prompt.contains("refine_media_search"))
        assertTrue(prompt.contains("delete_media"))
        assertTrue(prompt.contains("ai_optimize"))
        assertTrue(prompt.contains("render_html"))
    }

    @Test
    fun `prompt rules do not reference unregistered capabilities`() {
        // 只校验规则段（空清单 = 无工具描述混入）：render_html 的注册描述里合法提及
        // draw_chart/run_gallery_script（逐字节对齐 Android，一致性测试锁定），那是
        // 工具自身文档而非 prompt 规则教学，不在本守卫射程内
        val rulesOnly = IosChatPrompt.build(emptyList())
        val banned = listOf(
            // ai_optimize 已注册（2026-08-16 抽卡追齐），从禁用清单移除
            "run_gallery_script", "draw_chart", "capability.dispatch",
            "adjust_image", "edit_image",
            "remember_fact", "recall_memory", "remember_person_relation",
            "start_tag_scan", "bridge.callAsync",
        )
        for (token in banned) {
            assertFalse(rulesOnly.contains(token), "iOS prompt 规则段不应引用未注册能力：$token")
        }
    }

    @Test
    fun `prompt keeps multi turn refine discipline`() {
        assertTrue(prompt.contains("多轮窄化规则"))
        assertTrue(prompt.contains("fromMs"))
        assertTrue(prompt.contains("不要调用 finish"))
    }
}
