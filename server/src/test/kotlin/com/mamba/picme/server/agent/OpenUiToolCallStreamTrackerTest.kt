package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OpenUiToolCallStreamTracker] 单测：name 首帧归属、id/index 归组、节流与补尾。
 * 假时钟注入控制节流窗口。args JSON 片段一律普通字符串显式转义（同提取器测试口径）。
 */
class OpenUiToolCallStreamTrackerTest {

    private var clock = 0L

    private fun tracker() = OpenUiToolCallStreamTracker(
        minEmitChars = 10,
        minEmitIntervalMs = 100,
        now = { clock },
    )

    @Test
    fun `first delta carries name and subsequent frames attribute by id`() {
        val t = tracker()
        // 首帧 name + 少量 content（前缀 4 字符 < 阈值 10）不发射
        assertNull(t.onDelta(id = "call_1", name = "render_ui_openui", content = "{\"program\": \"root", index = null))
        // 后续帧无 name 按 id 归组；首发不受时间窗限制（hasEmitted=false）
        val emitted = t.onDelta(id = "call_1", name = null, content = " = Card([Text(\\\"hi\\\")])", index = null)
        assertEquals("root = Card([Text(\"hi\")])", emitted)
    }

    @Test
    fun `other tools are ignored even on same index`() {
        val t = tracker()
        assertNull(t.onDelta(id = "call_1", name = "search_media", content = "{\"query\": \"猫\"}", index = null))
        clock += 1000
        assertNull(t.onDelta(id = "call_1", name = null, content = ", more", index = null))
    }

    @Test
    fun `throttle suppresses emission until both char and time thresholds pass`() {
        val t = tracker()
        // 首发：前缀 10 字符达阈值且首发免时间窗
        assertEquals(
            "0123456789",
            t.onDelta(id = "c", name = "render_ui_openui", content = "{\"program\": \"0123456789", index = null),
        )
        // 字符够（+14）但时间不够 → 不发射
        assertNull(t.onDelta(id = "c", name = null, content = "abcdefghijklmn", index = null))
        // 时间过窗 → 发射（含本次喂入的 x）
        clock += 150
        assertEquals("0123456789abcdefghijklmnx", t.onDelta(id = "c", name = null, content = "x", index = null))
    }

    @Test
    fun `onComplete flushes tail regardless of throttle`() {
        val t = tracker()
        t.onDelta(id = "c", name = "render_ui_openui", content = "{\"program\": \"0123456789", index = null)
        // 尾片 +4 字符不足阈值被节流；闭引号与后续字段到来后 onComplete 补尾
        t.onDelta(id = "c", name = null, content = "tail\", \"fallbackSummary\": \"s\"}", index = null)
        assertEquals("0123456789tail", t.onComplete(id = "c", index = null))
        // 再补无推进 → null
        assertNull(t.onComplete(id = "c", index = null))
    }

    @Test
    fun `nameless first frame still tracked as fallback stream`() {
        val t = tracker()
        // 防御：首帧无 name（协议异常）登记为未知流，不发射也不崩溃
        assertNull(t.onDelta(id = null, name = null, content = "{\"program\": \"root = Card([])\"}", index = 0))
    }

    @Test
    fun `id-less follow-up frames attribute by index alias`() {
        val t = tracker()
        // 真实提供商形态（2026-10-26 真机实证）：首帧带 id/name，后续帧 id 缺失只带 index
        assertNull(t.onDelta(id = "call_1", name = "render_ui_openui", content = "{\"program\": \"root", index = 0))
        val emitted = t.onDelta(id = null, name = null, content = " = Card([Text(\\\"hi\\\")])", index = 0)
        assertEquals("root = Card([Text(\"hi\")])", emitted)
        // onComplete 同样 id 缺失 → index 别名解析后补尾
        clock += 1000
        t.onDelta(id = null, name = null, content = ", tail", index = 0)
        assertEquals("root = Card([Text(\"hi\")]), tail", t.onComplete(id = null, index = 0))
    }

    @Test
    fun `id-less frames without index attribute to the single open stream`() {
        val t = tracker()
        assertNull(t.onDelta(id = "call_1", name = "render_ui_openui", content = "{\"program\": \"root", index = null))
        val emitted = t.onDelta(id = null, name = null, content = " = Card([Text(\\\"hi\\\")])", index = null)
        assertEquals("root = Card([Text(\"hi\")])", emitted)
    }

    @Test
    fun `id-less frames without index are dropped when multiple streams open`() {
        val t = tracker()
        // 两个流都登记后，无 id 无 index 的帧无法安全归属 → 丢弃（宁丢不串流）
        assertNull(t.onDelta(id = "call_1", name = "search_media", content = "{\"query\": \"a\"}", index = 0))
        assertNull(t.onDelta(id = "call_2", name = "render_ui_openui", content = "{\"program\": \"root", index = 1))
        assertNull(t.onDelta(id = null, name = null, content = " = Card([])", index = null))
        // 带 index 的帧仍按别名正确归组
        val emitted = t.onDelta(id = null, name = null, content = " = Card([Text(\\\"hi\\\")])", index = 1)
        assertEquals("root = Card([Text(\"hi\")])", emitted)
    }
}
