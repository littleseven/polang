package com.mamba.picme.agent.core.inference.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RemoteChatEngine.resolveToolSubset]（M3 意图级工具裁剪的纯计算部分）契约测试：
 * 命中/缺失拆分、key 排序确定性、两条保守全量回退（空交集 / 缺失过半）。
 */
class RemoteChatEngineToolSubsetTest {

    @Test
    fun `all requested tools available - full hit with empty missing`() {
        val resolution = RemoteChatEngine.resolveToolSubset(
            available = setOf("run_gallery_script", "draw_chart", "finish", "delay"),
            toolNames = setOf("run_gallery_script", "draw_chart", "finish", "delay"),
        )
        requireNotNull(resolution)
        assertEquals(setOf("run_gallery_script", "draw_chart", "finish", "delay"), resolution.names)
        assertTrue(resolution.missing.isEmpty())
    }

    @Test
    fun `minority missing - proceeds and records dropped names`() {
        // 3 缺 1（1*2 <= 3）：零星缺失不拦，missing 进日志（契约漂移信号）
        val resolution = RemoteChatEngine.resolveToolSubset(
            available = setOf("run_gallery_script", "get_gallery_summary"),
            toolNames = setOf("run_gallery_script", "get_gallery_summary", "finish"),
        )
        requireNotNull(resolution)
        assertEquals(setOf("run_gallery_script", "get_gallery_summary"), resolution.names)
        assertEquals(setOf("finish"), resolution.missing)
    }

    @Test
    fun `exactly half missing - still proceeds`() {
        // 2 缺 1（1*2 = 2 不 > 2）：半数为边界，不触发回退
        val resolution = RemoteChatEngine.resolveToolSubset(
            available = setOf("adjust_image"),
            toolNames = setOf("adjust_image", "finish"),
        )
        requireNotNull(resolution)
        assertEquals(setOf("adjust_image"), resolution.names)
        assertEquals(setOf("finish"), resolution.missing)
    }

    @Test
    fun `majority missing - fallback to full registry`() {
        // iOS 窄工具面场景：4 缺 3（3*2 > 4）→ null 保守全量，残缺的子集不如不裁
        val resolution = RemoteChatEngine.resolveToolSubset(
            available = setOf("get_gallery_summary"),
            toolNames = setOf("get_gallery_summary", "run_gallery_script", "draw_chart", "finish"),
        )
        assertNull(resolution)
    }

    @Test
    fun `empty intersection - fallback to full registry`() {
        val resolution = RemoteChatEngine.resolveToolSubset(
            available = setOf("search_media"),
            toolNames = setOf("finish", "delay"),
        )
        assertNull(resolution)
    }

    @Test
    fun `key is sorted and order-independent`() {
        val a = RemoteChatEngine.resolveToolSubset(
            available = setOf("b_tool", "a_tool", "c_tool"),
            toolNames = setOf("b_tool", "a_tool", "c_tool"),
        )
        val b = RemoteChatEngine.resolveToolSubset(
            available = setOf("c_tool", "b_tool", "a_tool"),
            toolNames = setOf("c_tool", "a_tool", "b_tool"),
        )
        requireNotNull(a)
        requireNotNull(b)
        assertEquals("a_tool,b_tool,c_tool", a.key)
        // 同子集不同输入顺序 → 同 key（prompt 缓存与 AIAgent 重建键的前提）
        assertEquals(a.key, b.key)
    }
}
