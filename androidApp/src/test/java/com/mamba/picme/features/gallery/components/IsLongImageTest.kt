package com.mamba.picme.features.gallery.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsLongImageTest {

    @Test
    fun `height width ratio at or above 2_5 is long image`() {
        // 长截屏典型比例
        assertTrue(isLongImage(width = 1080, height = 1080 * 4))
        // 恰好阈值
        assertTrue(isLongImage(width = 100, height = 250))
        assertTrue(isLongImage(width = 2, height = 5))
    }

    @Test
    fun `ratio below 2_5 is not long image`() {
        // 普通照片/竖图
        assertFalse(isLongImage(width = 1080, height = 1920))
        assertFalse(isLongImage(width = 1080, height = 1080))
        // 横图
        assertFalse(isLongImage(width = 1920, height = 1080))
        // 阈值之下
        assertFalse(isLongImage(width = 100, height = 249))
    }

    @Test
    fun `zero or invalid dimensions are not long image`() {
        // 尺寸未到达（IntSize.Zero）时必须判 false，避免误进长图模式
        assertFalse(isLongImage(width = 0, height = 0))
        assertFalse(isLongImage(width = 0, height = 500))
    }
}
