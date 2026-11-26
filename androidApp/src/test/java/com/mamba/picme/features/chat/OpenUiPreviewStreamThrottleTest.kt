package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地程序预览写入节流器（Task 13）：镜像 server OpenUiContentStreamTracker 的
 * 48 字符/80ms 发射粒度（假时钟确定性验证），快照语义差异面（首帧直通 /
 * 非连续直通 / flushTail 保尾帧）单独钉住——T8 latch 可观测性与收尾判定
 * 拿最新完整文本的契约依赖这两个直通面。
 */
class OpenUiPreviewStreamThrottleTest {

    private var fakeNow = 0L

    private fun newThrottle() = OpenUiPreviewStreamThrottle(now = { fakeNow })

    @Test
    fun `first snapshot passes through unthrottled`() {
        val throttle = newThrottle()
        val first = "// summary: 已为你整理好照片"
        assertEquals(first, throttle.onTextSnapshot(first))
    }

    @Test
    fun `rapid continuous growth frames are compressed to first frame`() {
        val throttle = newThrottle()
        val program = "// summary: 已为你整理好照片\nroot = Markdown(\"正文" + "x".repeat(200)
        var emittedCount = 0
        var latest: String = program
        // 100 帧同步连发（时钟不推进 = 间隔条件恒不满足），每帧 +2 字符
        repeat(100) { index ->
            latest = program.substring(0, 40 + (index + 1) * 2)
            if (throttle.onTextSnapshot(latest) != null) emittedCount++
        }
        // 仅首帧直通：增量档即便攒满 48 字符也被 80ms 间隔压制
        assertEquals(1, emittedCount)
        // 尾帧未因节流丢失：flushTail 补发最新累计
        assertEquals(latest, throttle.flushTail())
    }

    @Test
    fun `char growth and interval both passing emits`() {
        val throttle = newThrottle()
        val first = "// summary: " + "a".repeat(48)
        assertEquals(first, throttle.onTextSnapshot(first))
        fakeNow += 100
        val grown = first + "b".repeat(50)
        assertEquals(grown, throttle.onTextSnapshot(grown))
    }

    @Test
    fun `interval alone does not emit without enough char growth`() {
        val throttle = newThrottle()
        val first = "// summary: " + "a".repeat(48)
        throttle.onTextSnapshot(first)
        fakeNow += 100
        val slightlyGrown = first + "b".repeat(10)
        assertNull(throttle.onTextSnapshot(slightlyGrown))
    }

    @Test
    fun `char growth alone does not emit without interval`() {
        val throttle = newThrottle()
        val first = "// summary: " + "a".repeat(48)
        throttle.onTextSnapshot(first)
        fakeNow += 10
        val grown = first + "b".repeat(50)
        assertNull(throttle.onTextSnapshot(grown))
    }

    @Test
    fun `non-continuous snapshot resets baseline and passes through`() {
        val throttle = newThrottle()
        val first = "// summary: 旧一轮文本"
        assertEquals(first, throttle.onTextSnapshot(first))
        // 快照回退/替换（非当前累计的连续扩展）：重置基准直通（T8 latch 可观测性）
        val replacement = "// summary: 新一轮"
        assertEquals(replacement, throttle.onTextSnapshot(replacement))
    }

    @Test
    fun `identical snapshot is a no-op`() {
        val throttle = newThrottle()
        val first = "// summary: 相同快照"
        throttle.onTextSnapshot(first)
        assertNull(throttle.onTextSnapshot(first))
    }

    @Test
    fun `flushTail returns null when nothing new to flush`() {
        val throttle = newThrottle()
        assertNull(throttle.flushTail())
        val first = "// summary: 已发射"
        throttle.onTextSnapshot(first)
        assertNull(throttle.flushTail())
    }

    @Test
    fun `reset clears state so next snapshot passes through`() {
        val throttle = newThrottle()
        val first = "// summary: 上一轮"
        throttle.onTextSnapshot(first)
        throttle.reset()
        assertNull(throttle.flushTail())
        val next = "root = Markdown(\"新一轮\")"
        assertEquals(next, throttle.onTextSnapshot(next))
    }

    @Test
    fun `default throttle mirrors server tracker granularity`() {
        // 48 字符 / 80ms：与 server OpenUiContentStreamTracker 同粒度（镜像契约钉住）
        assertEquals(48, OpenUiPreviewStreamThrottle.MIN_EMIT_CHARS)
        assertEquals(80L, OpenUiPreviewStreamThrottle.MIN_EMIT_INTERVAL_MS)
        val throttle = OpenUiPreviewStreamThrottle()
        assertNotNull(throttle.onTextSnapshot("// summary: 默认时钟"))
    }
}
