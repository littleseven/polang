package com.mamba.picme.features.chat.openui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Row.weights 值域防御钉住（2026-11-01 review 🔴1）：weights 经 schema（PRIMITIVE_ARRAY）
 * 只保证「数字数组」，零/负值/NaN/Infinity 直通 Compose Modifier.weight() 会在 measure 期
 * 抛 IllegalArgumentException（require(weight > 0.0)）击穿渲染层——非法槽位一律回退 1f 等宽。
 */
class OpenUiRowWeightsTest {

    @Test
    fun `non-positive NaN and infinite weights fall back to equal split`() {
        listOf(0f, -1f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .forEach { raw ->
                assertEquals("权重 $raw 应回退 1f", 1f, openUiSafeWeight(raw))
            }
    }

    @Test
    fun `positive finite weights pass through unchanged`() {
        assertEquals(2f, openUiSafeWeight(2f))
        assertEquals(0.5f, openUiSafeWeight(0.5f))
        assertEquals(1f, openUiSafeWeight(1f))
    }
}
