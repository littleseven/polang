package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTML 卡双形态分流判定（[HtmlCardDisplay]）单测。对齐 spec《HTML 卡双形态》§4 混合判定 /
 * §9.4 测高异常降级；判定函数为纯 Kotlin，覆盖 LLM 误判两方向与 = 阈值边界。
 * （2026-11-08 parts 单一权威：HtmlCardMeta 持久化迁入 partsJson，kotlinx codec 覆盖见
 * shared MessagePartsCodecTest，原 org.json metadata serde 段随函数删除移除。）
 */
class HtmlCardDisplayTest {

    private val threshold = 800 // 1.0 × 可用屏高（CSS px ≈ dp）

    // ---- 分流判定：display 声明优先 ----

    @Test
    fun `declared fullpage wins even for short content`() {
        // LLM 误判方向一：内容偏短但声明 fullpage → 尊重声明
        assertEquals(
            HtmlCardDisplayMode.FULLPAGE,
            HtmlCardDisplay.resolveDisplayMode("fullpage", measuredHeightPx = 200, fullpageThresholdPx = threshold)
        )
    }

    @Test
    fun `declared fullpage wins without measurement`() {
        assertEquals(
            HtmlCardDisplayMode.FULLPAGE,
            HtmlCardDisplay.resolveDisplayMode("fullpage", measuredHeightPx = 0, fullpageThresholdPx = threshold)
        )
    }

    @Test
    fun `declared display is case and whitespace tolerant`() {
        assertEquals(
            HtmlCardDisplayMode.FULLPAGE,
            HtmlCardDisplay.resolveDisplayMode(" FullPage ", measuredHeightPx = 100, fullpageThresholdPx = threshold)
        )
    }

    // ---- 分流判定：端侧测高兜底 ----

    @Test
    fun `undeclared long content over threshold is forced fullpage`() {
        // LLM 误判方向二：未声明 fullpage 但内容超一屏 → 端侧强制预览形态
        assertEquals(
            HtmlCardDisplayMode.FULLPAGE,
            HtmlCardDisplay.resolveDisplayMode(null, measuredHeightPx = 801, fullpageThresholdPx = threshold)
        )
    }

    @Test
    fun `declared inline long content over threshold is forced fullpage`() {
        assertEquals(
            HtmlCardDisplayMode.FULLPAGE,
            HtmlCardDisplay.resolveDisplayMode("inline", measuredHeightPx = 5000, fullpageThresholdPx = threshold)
        )
    }

    @Test
    fun `measured height exactly at threshold stays inline`() {
        // 边界：> 阈值才转预览，= 阈值仍 inline（spec §4「测高 > 1.0 × 可用屏高」）
        assertEquals(
            HtmlCardDisplayMode.INLINE,
            HtmlCardDisplay.resolveDisplayMode(null, measuredHeightPx = threshold, fullpageThresholdPx = threshold)
        )
    }

    @Test
    fun `short content under threshold stays inline`() {
        assertEquals(
            HtmlCardDisplayMode.INLINE,
            HtmlCardDisplay.resolveDisplayMode(null, measuredHeightPx = 799, fullpageThresholdPx = threshold)
        )
    }

    // ---- 降级链 §9.4：测高异常按 inline 处理，不触发强制预览 ----

    @Test
    fun `abnormal measurement falls back to inline`() {
        assertEquals(
            HtmlCardDisplayMode.INLINE,
            HtmlCardDisplay.resolveDisplayMode(null, measuredHeightPx = 0, fullpageThresholdPx = threshold)
        )
        assertEquals(
            HtmlCardDisplayMode.INLINE,
            HtmlCardDisplay.resolveDisplayMode(null, measuredHeightPx = -1, fullpageThresholdPx = threshold)
        )
    }

    @Test
    fun `unusable threshold falls back to inline`() {
        assertEquals(
            HtmlCardDisplayMode.INLINE,
            HtmlCardDisplay.resolveDisplayMode(null, measuredHeightPx = 9999, fullpageThresholdPx = 0)
        )
    }

    @Test
    fun `unknown display value treated as undeclared`() {
        assertEquals(
            HtmlCardDisplayMode.INLINE,
            HtmlCardDisplay.resolveDisplayMode("banner", measuredHeightPx = 100, fullpageThresholdPx = threshold)
        )
        assertEquals(
            HtmlCardDisplayMode.FULLPAGE,
            HtmlCardDisplay.resolveDisplayMode("banner", measuredHeightPx = 5000, fullpageThresholdPx = threshold)
        )
    }

    @Test
    fun `isDeclaredFullpage matches resolver semantics`() {
        assertEquals(true, HtmlCardDisplay.isDeclaredFullpage("fullpage"))
        assertEquals(true, HtmlCardDisplay.isDeclaredFullpage(" FULLPAGE"))
        assertEquals(false, HtmlCardDisplay.isDeclaredFullpage("inline"))
        assertEquals(false, HtmlCardDisplay.isDeclaredFullpage(null))
    }
}
