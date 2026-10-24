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

    // ── 标签抽稀（2026-10-24 all_years 事故：~80 桶标签全量渲染叠成数字串）──

    @Test
    fun `labels are not thinned at or below twelve buckets`() {
        assertEquals(1, barChartLabelInterval(1))
        assertEquals(1, barChartLabelInterval(12))
    }

    @Test
    fun `labels thin above twelve buckets keeping at most eight visible`() {
        assertEquals(2, barChartLabelInterval(13))
        assertEquals(2, barChartLabelInterval(16))
        assertEquals(10, barChartLabelInterval(80)) // 全部年份逐月：80 桶 → 每 10 桶一个标签
        // 任意桶数下可见标签数 = ceil(count / interval) 不超过 9（含 0 号位必显示）
        listOf(13, 24, 48, 80, 200).forEach { count ->
            val interval = barChartLabelInterval(count)
            val visible = (0 until count).count { index -> index % interval == 0 }
            assert(visible <= 9) { "count=$count interval=$interval visible=$visible" }
        }
    }
}
