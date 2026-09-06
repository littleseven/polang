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
    fun `exact group key is the shared md5, set only for group members`() {
        // 组标识供 hub 聚合按组扣 keeper：成员 = 组内共享 MD5；非成员/未成组为 null
        val groups = DuplicateGrouper.group(
            listOf(row("a", md5 = "x"), row("b", md5 = "x"), row("c", md5 = "y"), row("d"))
        )
        assertEquals("x", groups.getValue("a").exactGroupKey)
        assertEquals("x", groups.getValue("b").exactGroupKey)
        assertEquals(null, groups.getValue("c").exactGroupKey)
        assertEquals(null, groups.getValue("d").exactGroupKey)
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

    @Test
    fun `exact subset of similar and transitive chaining forms one cluster`() {
        // 验算（python3 复核）：base 为 56bit 模式，b=base^0b1111 距 4，c=b^0b11110000 距 4，
        // base^c=0b11111111 距 8（>5 不直接相连，经 b 链式并入）；d 的 phash=base 距 a 为 0
        val base = 0b11110000111100001111000011110000111100001111000011110000L
        val b = base xor 0b1111L
        val c = b xor 0b11110000L
        val groups = DuplicateGrouper.group(
            listOf(
                row("a", phash = base),
                row("b", phash = b),
                row("c", phash = c),
                row("d", md5 = "x", phash = base),
                row("e", md5 = "x"),  // 与 d 组成 exact 组，无 phash 不进 similar 簇
            )
        )
        // 链式传递：a/b/c/d 并为一簇 size=4
        assertEquals(4, groups.getValue("a").similarGroupSize)
        assertEquals(4, groups.getValue("b").similarGroupSize)
        assertEquals(4, groups.getValue("c").similarGroupSize)
        // exact ⊂ similar：d 同时进 exact 组（d/e 同 md5，size=2）与 similar 簇（size=4）
        assertEquals(2, groups.getValue("d").exactGroupSize)
        assertEquals(4, groups.getValue("d").similarGroupSize)
        // a 无 md5，不进 exact 组
        assertEquals(0, groups.getValue("a").exactGroupSize)
    }
}
