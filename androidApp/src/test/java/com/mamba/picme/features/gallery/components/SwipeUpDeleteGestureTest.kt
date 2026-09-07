package com.mamba.picme.features.gallery.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeUpDeleteGestureTest {

    private val pageHeight = 800f
    private val threshold = pageHeight * SwipeUpDeleteGesture.THRESHOLD_FRACTION // 200f

    @Test
    fun `vertical dominant only when abs dy exceeds abs dx`() {
        assertTrue(SwipeUpDeleteGesture.isVerticalDominant(dragX = 10f, dragY = -30f))
        assertTrue(SwipeUpDeleteGesture.isVerticalDominant(dragX = 0f, dragY = 5f))
        assertFalse(SwipeUpDeleteGesture.isVerticalDominant(dragX = 30f, dragY = -10f))
        // 相等不算主导：不抢 HorizontalPager 横滑
        assertFalse(SwipeUpDeleteGesture.isVerticalDominant(dragX = 20f, dragY = -20f))
    }

    @Test
    fun `shouldCommit requires upward offset beyond page height fraction`() {
        assertTrue(SwipeUpDeleteGesture.shouldCommit(offsetY = -threshold - 1f, pageHeight))
        assertTrue(SwipeUpDeleteGesture.shouldCommit(offsetY = -threshold - 0.001f, pageHeight))
        // 恰好阈值不触发（严格大于）
        assertFalse(SwipeUpDeleteGesture.shouldCommit(offsetY = -threshold, pageHeight))
        assertFalse(SwipeUpDeleteGesture.shouldCommit(offsetY = -threshold + 1f, pageHeight))
        // 向下位移永不触发
        assertFalse(SwipeUpDeleteGesture.shouldCommit(offsetY = 100f, pageHeight))
        assertFalse(SwipeUpDeleteGesture.shouldCommit(offsetY = 0f, pageHeight))
        // 布局未测量（高 0）不触发
        assertFalse(SwipeUpDeleteGesture.shouldCommit(offsetY = -100f, pageHeight = 0f))
    }

    @Test
    fun `followOffset only follows upward and clamps downward at zero`() {
        assertEquals(-50f, SwipeUpDeleteGesture.followOffset(current = 0f, dragY = -50f))
        assertEquals(-80f, SwipeUpDeleteGesture.followOffset(current = -30f, dragY = -50f))
        // 向下不跟：钳制到 0
        assertEquals(0f, SwipeUpDeleteGesture.followOffset(current = 0f, dragY = 40f))
        assertEquals(0f, SwipeUpDeleteGesture.followOffset(current = -30f, dragY = 40f))
    }

    @Test
    fun `flyOutTargetY flies above the screen`() {
        assertEquals(
            -pageHeight * SwipeUpDeleteGesture.FLY_OUT_DISTANCE_FACTOR,
            SwipeUpDeleteGesture.flyOutTargetY(pageHeight)
        )
    }
}
