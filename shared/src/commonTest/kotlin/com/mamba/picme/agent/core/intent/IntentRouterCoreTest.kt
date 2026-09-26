package com.mamba.picme.agent.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 路由器纯函数内核测试：门控 / pattern 捷径（含负面样例防劫持）/ 容错解析。
 */
class IntentRouterCoreTest {

    // ── 本地信号门控 ────────────────────────────────────────────────

    @Test
    fun `gating passes gallery-domain queries`() {
        assertTrue(IntentRouterCore.shouldRoute("找照片"))
        assertTrue(IntentRouterCore.shouldRoute("帮我统计一下截图数量"))
        assertTrue(IntentRouterCore.shouldRoute("打开相机"))
    }

    @Test
    fun `gating passes through open chit-chat`() {
        assertFalse(IntentRouterCore.shouldRoute("今天天气怎么样"))
        assertFalse(IntentRouterCore.shouldRoute("你好"))
        assertFalse(IntentRouterCore.shouldRoute("给我讲个笑话"))
    }

    @Test
    fun `gating requires media noun co-occurrence for bare view verbs`() {
        // 单字动词（看/找/搜/画）不与媒体名词共现时不放行——防「含常用动词即路由」
        assertFalse(IntentRouterCore.shouldRoute("你看这事怎么办"))
        assertFalse(IntentRouterCore.shouldRoute("帮我找个理由"))
        assertTrue(IntentRouterCore.shouldRoute("看下我儿子的照片")) // 照片=强信号
        assertTrue(IntentRouterCore.shouldRoute("找一下视频")) // 找+视频 共现
    }

    // ── pattern 捷径 ────────────────────────────────────────────────

    @Test
    fun `pattern shortcut hits hottest view phrases`() {
        listOf("看下我儿子的照片", "给我看去年夏天的照片", "找一下合照", "搜照片").forEach { query ->
            val hit = IntentRouterCore.matchPatternShortcut(query)
            assertNotNull(hit, "应命中捷径: $query")
            assertEquals(IntentId.VIEW_PHOTOS, hit.deliverable)
            assertEquals(1.0, hit.confidence)
        }
    }

    @Test
    fun `pattern shortcut rejects negative morphemes`() {
        // 负面样例（分析/计数/画图/疑问语素）必须归路由器，防捷径劫持语义
        listOf(
            "看看照片里有没有糊的",
            "照片有多少张",
            "把照片画成图",
            "照片清晰度怎么样",
        ).forEach { query ->
            assertNull(IntentRouterCore.matchPatternShortcut(query), "不应命中捷径: $query")
        }
    }

    @Test
    fun `pattern shortcut rejects queries without media noun or view verb`() {
        assertNull(IntentRouterCore.matchPatternShortcut("今天天气怎么样"))
        assertNull(IntentRouterCore.matchPatternShortcut("照片")) // 无动词
        assertNull(IntentRouterCore.matchPatternShortcut("看一下")) // 无媒体名词
    }

    // ── 路由器输出容错解析 ──────────────────────────────────────────

    @Test
    fun `parse valid router output`() {
        val raw = """{"deliverable":"VIEW_PHOTOS","confidence":0.95,"isRefinement":false,""" +
            """"secondary":null,"person":"儿子","fromMs":null,"toMs":null,"label":null,"constraint":null}"""
        val parsed = IntentRouterCore.parseRouterOutput(raw)
        assertNotNull(parsed)
        assertEquals(IntentId.VIEW_PHOTOS, parsed.deliverable)
        assertEquals(0.95, parsed.confidence)
        assertEquals("儿子", parsed.person)
        assertFalse(parsed.isRefinement)
        assertNull(parsed.secondary)
    }

    @Test
    fun `parse tolerates surrounding noise`() {
        val raw = """好的，分类结果如下：{"deliverable":"OPEN_QA","confidence":0.9} 完毕"""
        val parsed = IntentRouterCore.parseRouterOutput(raw)
        assertNotNull(parsed)
        assertEquals(IntentId.OPEN_QA, parsed.deliverable)
    }

    @Test
    fun `parse rejects invalid enum`() {
        val raw = """{"deliverable":"FLY_TO_MOON","confidence":0.9}"""
        assertNull(IntentRouterCore.parseRouterOutput(raw))
    }

    @Test
    fun `parse rejects invalid secondary enum`() {
        // secondary 拼错按 schema 失败处理（重试/降级），不吞为单产出物
        val raw = """{"deliverable":"VIEW_PHOTOS","confidence":0.9,"secondary":"DRAW_CHRT"}"""
        assertNull(IntentRouterCore.parseRouterOutput(raw))
    }

    @Test
    fun `parse rejects out-of-range confidence`() {
        assertNull(IntentRouterCore.parseRouterOutput("""{"deliverable":"OPEN_QA","confidence":1.5}"""))
        assertNull(IntentRouterCore.parseRouterOutput("""{"deliverable":"OPEN_QA","confidence":-0.1}"""))
    }

    @Test
    fun `parse rejects missing confidence`() {
        assertNull(IntentRouterCore.parseRouterOutput("""{"deliverable":"OPEN_QA"}"""))
    }

    @Test
    fun `parse rejects non-json`() {
        assertNull(IntentRouterCore.parseRouterOutput("VIEW_PHOTOS"))
        assertNull(IntentRouterCore.parseRouterOutput(""))
    }
}
