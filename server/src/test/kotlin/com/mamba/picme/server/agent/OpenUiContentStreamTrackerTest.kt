package com.mamba.picme.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenUiContentStreamTrackerTest {

    private var now = 0L
    private fun tracker() = OpenUiContentStreamTracker(
        minEmitChars = 10,
        minEmitIntervalMs = 100,
        now = { now },
    )

    @Test
    fun `accumulates deltas and emits throttled prefix`() {
        val t = tracker()
        assertNull(t.onTextDelta("root = "))           // 7 < 10 不发射
        val emitted = t.onTextDelta("Card([Text(\"hi\")])")
        assertEquals("root = Card([Text(\"hi\")])", emitted) // 累计 ≥10 发射全量前缀
    }

    @Test
    fun `round start resets buffer`() {
        val t = tracker()
        t.onTextDelta("root = Card([Text(\"hi\")])")
        t.onRoundStart()
        assertNull(t.onTextDelta("short"))             // 重置后重新累计
        assertEquals("short_long_enough", t.onTextDelta("_long_enough"))
    }

    @Test
    fun `interval throttling suppresses rapid emits`() {
        val t = tracker()
        t.onTextDelta("0123456789")                    // 首发
        now = 50                                        // 距首发 < 100ms
        assertNull(t.onTextDelta("abcdefghij"))         // 间隔抑制
        now = 200
        assertEquals("0123456789abcdefghij", t.onTextDelta(""))
    }

    @Test
    fun `no re-emit when prefix not advanced`() {
        val t = tracker()
        t.onTextDelta("0123456789")
        now = 200
        assertNull(t.onTextDelta(""))                   // 前缀未推进不重复发
    }
}
