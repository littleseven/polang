package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.HtmlCardMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

/**
 * HTML 卡双形态分流判定（[HtmlCardDisplay]）与 metadata serde（[HtmlCardMeta.toJson] /
 * [parseHtmlCardMeta]）单测。对齐 spec《HTML 卡双形态》§4 混合判定 / §9.4 测高异常降级 /
 * §10 持久化；判定函数为纯 Kotlin，覆盖 LLM 误判两方向与 = 阈值边界。
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

    // ---- metadata serde（Room metadata `html_card` key）----

    @Test
    fun `meta json roundtrip preserves all fields`() {
        val meta = HtmlCardMeta(
            display = "fullpage",
            displayMode = HtmlCardDisplayMode.FULLPAGE,
            measuredHeightPx = 2048,
            summary = "月度相册报告"
        )
        val metadata = JSONObject().put("html_card", meta.toJson()).toString()
        assertEquals(meta, parseHtmlCardMeta(metadata))
    }

    @Test
    fun `meta json roundtrip with defaults omits absent fields`() {
        val meta = HtmlCardMeta()
        val metadata = JSONObject().put("html_card", meta.toJson()).toString()
        assertEquals(meta, parseHtmlCardMeta(metadata))
        // 缺省字段不写入（紧凑 JSON）
        assertEquals("{}", JSONObject(metadata).getJSONObject("html_card").toString())
    }

    @Test
    fun `parse returns null for blank or corrupted metadata`() {
        assertNull(parseHtmlCardMeta(null))
        assertNull(parseHtmlCardMeta(""))
        assertNull(parseHtmlCardMeta("not json"))
        assertNull(parseHtmlCardMeta("""{"other":{}}"""))
    }

    @Test
    fun `unknown displayMode enum value degrades to undecided`() {
        // 旧版本读到未来新枚举值：回退 null（待重新判定），不崩溃不锁死形态
        val metadata = """{"html_card":{"display":"inline","displayMode":"FUTURE_MODE","measuredHeightPx":100}}"""
        val meta = parseHtmlCardMeta(metadata)
        assertEquals("inline", meta?.display)
        assertNull(meta?.displayMode)
        assertEquals(100, meta?.measuredHeightPx)
    }

    @Test
    fun `persisted merge keeps sibling keys intact`() {
        // 模拟 persistHtmlCardDisplayMode 的合并语义：html_card 之外的 metadata key 不受影响
        val root = JSONObject("""{"claude_agent_state":{"text":"x"},"html_card":{"display":"inline","summary":"s"}}""")
        val card = root.getJSONObject("html_card")
        card.put("displayMode", HtmlCardDisplayMode.INLINE.name)
        card.put("measuredHeightPx", 320)
        val meta = parseHtmlCardMeta(root.toString())
        assertEquals(
            HtmlCardMeta(
                display = "inline",
                displayMode = HtmlCardDisplayMode.INLINE,
                measuredHeightPx = 320,
                summary = "s"
            ),
            meta
        )
        assertEquals("x", root.getJSONObject("claude_agent_state").getString("text"))
    }
}
