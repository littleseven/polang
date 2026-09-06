package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateGrouperTest {

    private fun row(uri: String, md5: String? = null, phash: Long? = null) =
        DuplicateGrouper.HashInput(uri = uri, md5 = md5, phash = phash)

    @Test
    fun `same md5 forms exact group`() {
        val groups = DuplicateGrouper.group(
            listOf(row("a", md5 = "x"), row("b", md5 = "x"), row("c", md5 = "y"))
        )
        assertEquals(2, groups.getValue("a").exactGroupSize)
        assertEquals(2, groups.getValue("b").exactGroupSize)
        assertEquals(0, groups.getValue("c").exactGroupSize)
    }

    @Test
    fun `close phash forms similar group, far phash does not`() {
        // 汉明距离 ≤5 成簇（与去重 2.0 VISUAL 阈值一致）
        // 计划原文的 64bit 字面量超出 Long 范围，降 4bit 为 60bit 等价模式
        val base = 0b000011110000111100001111000011110000111100001111000011110000L
        val close = base xor 0b11L          // 距离 2
        val far = base xor 0b1111111111L    // 距离 10
        val groups = DuplicateGrouper.group(
            listOf(row("a", phash = base), row("b", phash = close), row("c", phash = far))
        )
        assertEquals(2, groups.getValue("a").similarGroupSize)
        assertEquals(2, groups.getValue("b").similarGroupSize)
        assertEquals(0, groups.getValue("c").similarGroupSize)
    }

    @Test
    fun `missing hashes are ignored`() {
        val groups = DuplicateGrouper.group(listOf(row("a"), row("b", md5 = "x")))
        assertEquals(0, groups.getValue("a").exactGroupSize)
        assertEquals(0, groups.getValue("a").similarGroupSize)
    }
}
