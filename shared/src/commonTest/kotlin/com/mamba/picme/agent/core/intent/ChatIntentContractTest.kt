package com.mamba.picme.agent.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 意图契约表不变式校验（spec §3.1：allowed∩forbidden=∅、闭集完备、组合型原料集）。
 * 契约表是路由 SSOT——本测试保证 L41/L62 类规则矛盾在设计上无法再发生。
 */
class ChatIntentContractTest {

    @Test
    fun `contract validate passes`() {
        assertEquals(emptyList(), ChatIntentContract.validate())
    }

    @Test
    fun `intent set is complete over IntentId`() {
        assertEquals(IntentId.entries.toSet(), ChatIntentContract.intents.map { it.id }.toSet())
    }

    @Test
    fun `no intent has overlapping allowed and forbidden tools`() {
        ChatIntentContract.intents.forEach { def ->
            val overlap = def.allowedTools intersect def.forbiddenTools.toSet()
            assertTrue(overlap.isEmpty(), "${def.id} allowed∩forbidden 非空: $overlap")
        }
    }

    @Test
    fun `view photos forbids run_gallery_script`() {
        // 事故链防线（D1/D3）：看照片意图禁止脚本路径拿 ids 谎称已展示
        val def = ChatIntentContract.of(IntentId.VIEW_PHOTOS)
        assertTrue("run_gallery_script" in def.forbiddenTools)
        assertTrue("search_media" in def.allowedTools)
        // 原生横滑卡退役（2026-11-08）：交付物 = render_html 图集卡（media://{id} 引用）
        assertTrue("render_html" in def.allowedTools)
        assertEquals(UiArtifact.RICH_HTML_CARD, def.uiContract)
    }

    @Test
    fun `refine results delivers rich html card`() {
        // 窄化后重出图集卡：refine 取交集 → render_html 重渲染（横滑卡时代 MEDIA_RESULTS_CARD 已删）
        val def = ChatIntentContract.of(IntentId.REFINE_RESULTS)
        assertEquals(UiArtifact.RICH_HTML_CARD, def.uiContract)
        assertTrue("refine_media_search" in def.allowedTools)
        assertTrue("render_html" in def.allowedTools)
    }

    @Test
    fun `browse web forbids gallery search tools`() {
        // 防相册误路由锚点：浏览器意图严禁落到 search_media / refine_media_search / 脚本取数
        val def = ChatIntentContract.of(IntentId.BROWSE_WEB)
        assertTrue("browser_open" in def.allowedTools)
        assertTrue("search_media" in def.forbiddenTools)
        assertTrue("refine_media_search" in def.forbiddenTools)
        assertTrue("run_gallery_script" in def.forbiddenTools)
        assertEquals(UiArtifact.TEXT_ONLY, def.uiContract)
    }
}
