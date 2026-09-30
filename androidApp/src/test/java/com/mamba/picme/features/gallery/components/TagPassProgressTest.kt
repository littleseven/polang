package com.mamba.picme.features.gallery.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPassProgressTest {

    @Test
    fun `partial progress computes processed as total minus remaining`() {
        // 100 张，待处理 20 → 已处理 80（不是 withFace=50，修正语义口径）
        val p = tagPassProgress(total = 100, remaining = 20)
        assertEquals(80, p.processed)
        assertEquals(20, p.remaining)
        assertEquals(0.8f, p.fraction, 1e-5f)
        assertFalse(p.isComplete)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `zero remaining with positive total is complete`() {
        val p = tagPassProgress(total = 100, remaining = 0)
        assertEquals(100, p.processed)
        assertEquals(1f, p.fraction, 1e-5f)
        assertTrue(p.isComplete)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `zero total is empty and never complete`() {
        val p = tagPassProgress(total = 0, remaining = 0)
        assertEquals(0, p.processed)
        assertEquals(0f, p.fraction, 1e-5f)
        assertTrue(p.isEmpty)
        assertFalse(p.isComplete)
    }

    @Test
    fun `remaining larger than total is clamped to total`() {
        val p = tagPassProgress(total = 10, remaining = 99)
        assertEquals(0, p.processed)
        assertEquals(10, p.remaining)
        assertEquals(0f, p.fraction, 1e-5f)
        assertFalse(p.isComplete)
    }

    @Test
    fun `negative inputs are clamped to zero`() {
        val p = tagPassProgress(total = -5, remaining = -3)
        assertEquals(0, p.total)
        assertEquals(0, p.remaining)
        assertEquals(0, p.processed)
        assertTrue(p.isEmpty)
    }

    @Test
    fun `percentRounded rounds half up and matches stage text`() {
        // 1000 张待处理 904 → 9.6% → 四舍五入 10（整数截断会给 9，同页圆环与阶段行将漂移）
        assertEquals(10, tagPassProgress(total = 1000, remaining = 904).percentRounded())
        assertEquals(80, tagPassProgress(total = 100, remaining = 20).percentRounded())
    }

    @Test
    fun `percentRounded of empty is zero and of complete is hundred`() {
        assertEquals(0, tagPassProgress(total = 0, remaining = 0).percentRounded())
        assertEquals(100, tagPassProgress(total = 100, remaining = 0).percentRounded())
    }
}
