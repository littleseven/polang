package com.mamba.picme.features.chat.a2ui

import com.mamba.picme.core.designsystem.A2uiCardTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BarChart 柱高公式契约（2026-10-24 标签裁剪事故）：
 * 满高柱（count==maxCount）的高度必须是 绘图区高 - 标签高，
 * 给柱下月份标签留出固定空间——否则内层 Column(柱+标签) 总高超出定高 Row，
 * 标签被挤出裁掉（单桶图退化成无标签纯绿砖）。
 */
class PolangBarChartComponentLayoutTest {

    @Test
    fun `tallest bar reserves label height below the plot area`() {
        assertEquals(
            A2uiCardTokens.barAreaHeight - A2uiCardTokens.barLabelHeight,
            barChartBarHeight(count = 21, maxCount = 21),
        )
    }

    @Test
    fun `half count bar takes half of the plot height`() {
        assertEquals(
            (A2uiCardTokens.barAreaHeight - A2uiCardTokens.barLabelHeight) / 2,
            barChartBarHeight(count = 10, maxCount = 20),
        )
    }

    @Test
    fun `zero count bar has zero height`() {
        assertEquals(
            androidx.compose.ui.unit.Dp(0f),
            barChartBarHeight(count = 0, maxCount = 21),
        )
    }

    @Test
    fun `label height token stays positive and below plot area`() {
        assert(A2uiCardTokens.barLabelHeight > androidx.compose.ui.unit.Dp(0f))
        assert(A2uiCardTokens.barLabelHeight < A2uiCardTokens.barAreaHeight)
    }
}
