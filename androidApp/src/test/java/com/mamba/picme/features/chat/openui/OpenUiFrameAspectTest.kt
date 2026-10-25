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
}
