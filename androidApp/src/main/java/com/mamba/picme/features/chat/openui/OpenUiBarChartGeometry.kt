package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.core.designsystem.A2uiCardTokens

/**
 * 柱状图几何助手（自 features.chat.a2ui.PolangBarChartComponent 迁入，2026-11-05 A2UI 退役；
 * OpenUI BarChart/ColumnChart 渲染器共用，行为与旧 A2UI 侧逐项对齐）。
 */

/**
 * 柱高公式（2026-10-24 标签裁剪事故修复）：满高柱 = 绘图区高 - 标签预留高。
 * 内层 Column = 柱 Box + 标签 Text，Row 定高 barAreaHeight——柱若顶满整个绘图区，
 * 标签会被挤出 Row 边界裁掉（单桶图退化为无标签纯色块）。预留 barLabelHeight 后
 * 柱+标签恒不超高，任何桶的标签都可见。
 */
internal fun barChartBarHeight(count: Int, maxCount: Int): androidx.compose.ui.unit.Dp {
    val plotHeight = A2uiCardTokens.barAreaHeight - A2uiCardTokens.barLabelHeight
    return plotHeight * count.coerceAtLeast(0) / maxCount.coerceAtLeast(1)
}

/**
 * 标签抽稀间隔（2026-10-24 all_years 事故修复）：桶数超过 [MAX_UNTHINNED_LABELS] 后按
 * 「可见标签 ≤ [MAX_VISIBLE_LABELS]」取间隔，每第 N 桶才渲染标签（选中桶恒显示）。
 * 12 桶及以内（典型单年逐月图）行为不变；80 桶（全部年份逐月）从 80 坨叠字降到 8 个标签。
 */
internal fun barChartLabelInterval(bucketCount: Int): Int {
    if (bucketCount <= MAX_UNTHINNED_LABELS) return 1
    return (bucketCount + MAX_VISIBLE_LABELS - 1) / MAX_VISIBLE_LABELS
}

private const val MAX_UNTHINNED_LABELS = 12
private const val MAX_VISIBLE_LABELS = 8

/** 桶数阈值：超过后柱区切横滑（weight 分列在时间距吃光宽度后会归零——2026-10-24 all_years 二次事故）。 */
internal const val BAR_CHART_SCROLL_THRESHOLD = 24

/** 横滑模式单柱宽（间距仍走 token barGap）。 */
internal val BAR_CHART_SCROLL_BAR_WIDTH = 12.dp

/** 抽稀标签定宽：抽稀位邻居是空占位，宽出部分不重叠；不折行防逐字竖排。 */
internal val BAR_CHART_LABEL_WIDTH = 68.dp

/** 抽稀后的桶标签：定宽、单行、不折行、居中（柱列中心对齐由调用侧 Column 保证）。 */
@Composable
internal fun BarChartBucketLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.requiredWidth(BAR_CHART_LABEL_WIDTH),
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
    )
}
