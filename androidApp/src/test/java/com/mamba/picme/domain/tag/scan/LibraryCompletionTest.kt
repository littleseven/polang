package com.mamba.picme.domain.tag.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 口径立法契约测试（spec 2026-10-01-scan-progress-ux-redesign §4 / AC-1）：
 * 全 app 唯一对外百分比 = 库级 AI 打标完成率；LibraryCompletion 与 TagPassProgress
 * 同源同舍入，圆环/通知/任务中心三处渲染值必须相等。
 */
class LibraryCompletionTest {

    @Test
    fun `library completion percent equals pass progress percent for same input`() {
        val completion = LibraryCompletion(totalMedia = 1000, remainingPass3 = 904)
        assertEquals(tagPassProgress(1000, 904).percentRounded(), completion.percentRounded())
        assertEquals(10, completion.percentRounded())
    }

    @Test
    fun `fraction is pass progress fraction`() {
        val completion = LibraryCompletion(totalMedia = 100, remainingPass3 = 20)
        assertEquals(0.8f, completion.fraction, 1e-5f)
    }

    @Test
    fun `empty library yields zero percent and zero fraction`() {
        val completion = LibraryCompletion(totalMedia = 0, remainingPass3 = 0)
        assertEquals(0, completion.percentRounded())
        assertEquals(0f, completion.fraction, 1e-5f)
    }

    @Test
    fun `complete library yields hundred percent`() {
        val completion = LibraryCompletion(totalMedia = 500, remainingPass3 = 0)
        assertEquals(100, completion.percentRounded())
    }

    @Test
    fun `half midpoint rounds half up via double path`() {
        // 125/1000 = 12.5% → 13（与圆环口径一致，无整数截断漂移）
        assertEquals(13, LibraryCompletion(totalMedia = 1000, remainingPass3 = 875).percentRounded())
    }
}
