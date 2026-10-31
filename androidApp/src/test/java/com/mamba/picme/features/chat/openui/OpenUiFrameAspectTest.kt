package com.mamba.picme.features.chat.openui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Image 框宽高比决策链钉住（2026-10-25 review 🟡1）：
 * LLM 显式 aspect 走半信任窄 clamp [0.5, 2.5]；元数据真实比例是端侧可信数据，
 * 只套防脏数据宽界 [0.3, 3.0]——9:19.5 竖屏截图（0.46）不得被夹（夹到 0.5 仍拦腰裁）。
 */
class OpenUiFrameAspectTest {

    @Test
    fun `explicit prop wins over metadata`() {
        assertEquals(1.5, frameAspectOf(1.5, 0.46f), 1e-9)
    }

    @Test
    fun `llm prop clamps to semi-trusted bound`() {
        assertEquals(0.5, frameAspectOf(0.1, null), 1e-9)
        assertEquals(2.5, frameAspectOf(9.9, null), 1e-9)
    }

    @Test
    fun `metadata keeps tall screenshot ratio unclamped by llm bound`() {
        // 9:19.5 ≈ 0.4615：低于 LLM clamp 下限 0.5，但元数据路径必须原样保留
        assertEquals(9.0 / 19.5, frameAspectOf(null, (9.0 / 19.5).toFloat()), 1e-6)
    }

    @Test
    fun `metadata garbage falls back within sanity bound`() {
        assertEquals(0.3, frameAspectOf(null, 0.001f), 1e-9)
        assertEquals(3.0, frameAspectOf(null, 100f), 1e-9)
    }

    @Test
    fun `neither source falls back to 4_3`() {
        assertEquals(4.0 / 3.0, frameAspectOf(null, null), 1e-9)
    }

    // ── 2026-10-31 比例治理：fallback 参数化（Banner/ImageTextLarge 16:9 缺省框） ──

    @Test
    fun `custom fallback used when no prop and no metadata`() {
        assertEquals(16.0 / 9.0, frameAspectOf(null, null, 16.0 / 9.0), 1e-9)
    }

    @Test
    fun `metadata beats custom fallback`() {
        // 竖构图照片（3:4=0.75）在 16:9 缺省框组件里也必须按真实比例成帧
        assertEquals(0.75, frameAspectOf(null, 0.75f, 16.0 / 9.0), 1e-9)
    }

    @Test
    fun `explicit prop beats metadata with custom fallback`() {
        assertEquals(2.0, frameAspectOf(2.0, 0.75f, 16.0 / 9.0), 1e-9)
    }

    @Test
    fun `network intrinsic ratio walks metadata path`() {
        // intrinsic 与相册元数据同径（端侧可信数据）：竖图 0.46 不被 LLM 窄 clamp 夹
        assertEquals(0.46, frameAspectOf(null, 0.46f, 16.0 / 9.0), 1e-6)
    }
}
