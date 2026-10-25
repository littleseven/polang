package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.chat.a2ui.barChartLabelInterval
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OpenUI 图表批次渲染器（M4c，2026-10-25 全量补全，spec《2026-10-25-openui-full-library-design》§3.4）：
 * AreaChart / HorizontalBarChart / RadarChart / RadialChart / SingleStackedBarChart / ScatterChart。
 *
 * 样式口径与主文件三图一致：标题 SUBHEAD（[OpenUiCardTokens.textStyle]）、标签 CAPTION 弱色、
 * 配色 [OpenUiCardTokens.chartPalette] 按 series/slice/dataset 序号轮转、图例 = 色点 + label。
 * 防御式解析（半信任输入）：数据全缺/解析不出 → 空 Box 占位不炸卡；护栏常量沿用既有档
 * （series ≤ [MAX_CHART_SERIES]、labels ≤ [MAX_CHART_LABELS]、slices ≤ [MAX_CHART_SLICES]）。
 *
 * Series/Slice/ScatterSeries/Point 是 OBJECT_ARRAY 元素形状，不独立渲染（独立出现 → 空占位）。
 * ScatterChart.datasets[].points 按 schema 实为嵌套数组 [[x,y],...]/[[x,y,z],...]（library 注释：
 * schema 不支持 OBJECT_ARRAY 嵌套 OBJECT_ARRAY），兼容解析 {x,y,z} 对象形。
 */

/** 本文件承接渲染的图表组件集合（dispatch 接线按此集合分流）。 */
internal val openUiChartComponentNames: Set<String> = setOf(
    "AreaChart",
    "HorizontalBarChart",
    "RadarChart",
    "RadialChart",
    "SingleStackedBarChart",
    "ScatterChart",
)

@Composable
internal fun OpenUiChartNode(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node.type) {
        "AreaChart" -> OpenUiAreaChart(node, modifier)
        "HorizontalBarChart" -> OpenUiHorizontalBarChart(node, modifier)
        "RadarChart" -> OpenUiRadarChart(node, modifier)
        "RadialChart" -> OpenUiRadialChart(node, modifier)
        "SingleStackedBarChart" -> OpenUiSingleStackedBarChart(node, modifier)
        "ScatterChart" -> OpenUiScatterChart(node, modifier)
        else -> Box(modifier = modifier) // 未知组件：空占位，不炸整卡
    }
}

// ── 共享数据形状与解析（命名区别于主文件 Bucket/PieSlice/LineSeries） ──────────

private data class SeriesData(val name: String, val values: List<Double?>)

private data class SliceData(val category: String, val value: Double)

private data class ScatterPoint(val x: Double, val y: Double, val z: Double?)

private data class ScatterDataset(val name: String, val points: List<ScatterPoint>)

private const val MAX_CHART_SERIES = 4
private const val MAX_CHART_LABELS = 200
private const val MAX_CHART_SLICES = 12
private const val MAX_SCATTER_DATASETS = 4
private const val MAX_SCATTER_POINTS = 200

/** 原始值文本形态（Num 整数不带小数点；与主文件 openUiPrimitiveText 同口径，文件级私有自持）。 */
private fun chartPrimitiveText(value: OpenUiValue): String? = when (value) {
    is OpenUiValue.Str -> value.v
    is OpenUiValue.Num -> if (value.v % 1.0 == 0.0) value.v.toLong().toString() else value.v.toString()
    is OpenUiValue.Bool -> value.v.toString()
    else -> null
}

/** labels + series[{name, values}] 双数组解析（LineChart 同径：按位对齐，缺位补 null 断点）。 */
private fun parseSeriesData(node: OpenUiValue.ComponentVal): Pair<List<String>, List<SeriesData>> {
    val labels = (node.props["labels"] as? OpenUiValue.Arr)?.v.orEmpty()
        .take(MAX_CHART_LABELS)
        .map { value -> chartPrimitiveText(value) ?: "" }
    val series = (node.props["series"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val name = (obj["name"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val rawValues = (obj["values"] as? OpenUiValue.Arr)?.v.orEmpty()
        SeriesData(
            name = name,
            values = (0 until labels.size).map { index ->
                (rawValues.getOrNull(index) as? OpenUiValue.Num)?.v
            },
        )
    }.filter { serie -> serie.values.any { value -> value != null } }.take(MAX_CHART_SERIES)
    return labels to series
}

/** slices[{category, value}] 解析（value 钳非负，BarChart/PieChart 同口径）。 */
private fun parseSliceData(node: OpenUiValue.ComponentVal): List<SliceData> =
    (node.props["slices"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val category = (obj["category"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val value = (obj["value"] as? OpenUiValue.Num)?.v ?: return@mapNotNull null
        SliceData(category = category, value = value.coerceAtLeast(0.0))
    }.take(MAX_CHART_SLICES)

@Composable
private fun OpenUiChartTitle(title: String?) {
    title?.let { text ->
        Text(text = text, style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD))
    }
}

/** 图例行（PieChart 图例同径）：色点 + CAPTION 弱色 label。 */
@Composable
private fun OpenUiChartLegend(
    entries: List<Pair<Color, String>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        entries.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(Spacing.sm)
                        .clip(CircleShape)
                        .background(entry.first),
                )
                Text(
                    text = entry.second,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── AreaChart ────────────────────────────────────────────────────────────────

/**
 * AreaChart：结构复刻 LineChart（labels 抽稀、null 断点、点按槽中心布点、平线居中），
 * 折线下方填充路径 palette 色 × 0.2 alpha（多系列各自填充、先填后线防遮线）；
 * variant=step 阶梯折线（水平→垂直两段），natural≈linear（spec 口径）。
 */
@Composable
private fun OpenUiAreaChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val (labels, series) = parseSeriesData(node)
    if (labels.isEmpty() || series.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val stepVariant = (node.props["variant"] as? OpenUiValue.Str)?.v == "step"
    val palette = OpenUiCardTokens.chartPalette
    val allValues = series.flatMap { serie -> serie.values.filterNotNull() }
    val minValue = allValues.min()
    val maxValue = allValues.max()
    val range = if (maxValue > minValue) maxValue - minValue else 1.0
    val labelInterval = barChartLabelInterval(labels.size)
    Column(modifier = modifier.fillMaxWidth()) {
        OpenUiChartTitle(title)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(A2uiCardTokens.barAreaHeight),
        ) {
            val pointRadius = 3.dp.toPx()
            fun pointAt(index: Int, value: Double): Offset {
                val normalized =
                    if (maxValue > minValue) (value - minValue) / range else 0.5
                return Offset(
                    x = (index + 0.5f) * size.width / labels.size,
                    y = pointRadius + (size.height - pointRadius * 2) * (1f - normalized.toFloat()),
                )
            }
            // 先铺各系列填充：逐连续段（null 断点切分）闭合成底边多边形
            series.forEachIndexed { seriesIndex, serie ->
                val fillColor = palette[seriesIndex % palette.size].copy(alpha = 0.2f)
                var segment = mutableListOf<Offset>()
                fun flushSegment() {
                    if (segment.isNotEmpty()) {
                        val path = Path()
                        path.moveTo(segment.first().x, size.height)
                        segment.forEach { point -> path.lineTo(point.x, point.y) }
                        path.lineTo(segment.last().x, size.height)
                        path.close()
                        drawPath(path = path, color = fillColor)
                        segment = mutableListOf()
                    }
                }
                serie.values.forEachIndexed { index, value ->
                    if (value == null) {
                        flushSegment()
                    } else {
                        segment.add(pointAt(index, value))
                    }
                }
                flushSegment()
            }
            // 后画折线与数据点（与 LineChart 同几何）
            series.forEachIndexed { seriesIndex, serie ->
                val color = palette[seriesIndex % palette.size]
                var previous: Offset? = null
                serie.values.forEachIndexed { index, value ->
                    if (value == null) {
                        previous = null // null 断点：断开折线
                    } else {
                        val point = pointAt(index, value)
                        previous?.let { from ->
                            if (stepVariant) {
                                drawLine(color = color, start = from, end = Offset(point.x, from.y), strokeWidth = 2.dp.toPx())
                                drawLine(color = color, start = Offset(point.x, from.y), end = point, strokeWidth = 2.dp.toPx())
                            } else {
                                drawLine(color = color, start = from, end = point, strokeWidth = 2.dp.toPx())
                            }
                        }
                        drawCircle(color = color, radius = pointRadius, center = point)
                        previous = point
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(A2uiCardTokens.barLabelHeight)) {
            labels.forEachIndexed { index, label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (index % labelInterval == 0) {
                        Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        if (series.size > 1) {
            OpenUiChartLegend(
                entries = series.mapIndexed { index, serie ->
                    palette[index % palette.size] to serie.name
                },
            )
        }
    }
}

// ── HorizontalBarChart ───────────────────────────────────────────────────────

private val HBAR_LABEL_WIDTH = 72.dp
private val HBAR_BAR_HEIGHT = 14.dp
private val HBAR_GROUP_GAP = 2.dp

/**
 * HorizontalBarChart：每行 = 左标签列（CAPTION 弱色定宽右对齐）+ 条道（surfaceVariant 圆角）
 * + 填充条（palette 色，长按值/全局 max 占比）。variant=grouped（默认，每 label 每 series 一条）/
 * stacked（每 label 单条分段堆叠，weight 分段）；series>1 加图例行。
 */
@Composable
private fun OpenUiHorizontalBarChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val (labels, series) = parseSeriesData(node)
    if (labels.isEmpty() || series.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val stacked = (node.props["variant"] as? OpenUiValue.Str)?.v == "stacked"
    val palette = OpenUiCardTokens.chartPalette
    val globalMax = series.flatMap { serie -> serie.values.filterNotNull() }.max().coerceAtLeast(1.0)
    val barShape = RoundedCornerShape(HBAR_BAR_HEIGHT / 2)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OpenUiChartTitle(title)
        labels.forEachIndexed { labelIndex, label ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(HBAR_LABEL_WIDTH),
                )
                if (stacked) {
                    // 单条分段堆叠：段宽 weight = 值占比
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(HBAR_BAR_HEIGHT)
                            .clip(barShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                            series.forEachIndexed { seriesIndex, serie ->
                                val value = serie.values.getOrNull(labelIndex) ?: 0.0
                                if (value > 0.0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(value.toFloat())
                                            .fillMaxHeight()
                                            .background(palette[seriesIndex % palette.size]),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // grouped：每 series 一条，长按值/全局 max 占比
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(HBAR_GROUP_GAP),
                    ) {
                        series.forEachIndexed { seriesIndex, serie ->
                            val value = (serie.values.getOrNull(labelIndex) ?: 0.0).coerceAtLeast(0.0)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(HBAR_BAR_HEIGHT)
                                    .clip(barShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth((value / globalMax).toFloat().coerceIn(0f, 1f))
                                        .clip(barShape)
                                        .background(palette[seriesIndex % palette.size]),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (series.size > 1) {
            OpenUiChartLegend(
                entries = series.mapIndexed { index, serie ->
                    palette[index % palette.size] to serie.name
                },
            )
        }
    }
}

// ── RadarChart ───────────────────────────────────────────────────────────────

private val RADAR_CHART_SIZE = 200.dp

/**
 * RadarChart：Canvas 蛛网——labels.size 根轴辐线 + 3 圈同心多边形网格（弱色描边）
 * + 每 series 多边形（palette 描边 2dp + 填充 alpha 0.25f）；值按全局 max 归一，null 折 0。
 * 轴数 < 3 多边形退化 → 空占位。
 */
@Composable
private fun OpenUiRadarChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val (labels, series) = parseSeriesData(node)
    if (labels.size < 3 || series.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val palette = OpenUiCardTokens.chartPalette
    val globalMax = series.flatMap { serie -> serie.values.filterNotNull() }.max().coerceAtLeast(1.0)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Column(modifier = modifier.fillMaxWidth()) {
        OpenUiChartTitle(title)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(RADAR_CHART_SIZE)) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = minOf(size.width, size.height) / 2 * 0.85f
                val axisCount = labels.size
                fun axisPoint(axisIndex: Int, fraction: Float): Offset {
                    val angle = Math.toRadians(-90.0 + axisIndex * 360.0 / axisCount)
                    return Offset(
                        x = center.x + radius * fraction * cos(angle).toFloat(),
                        y = center.y + radius * fraction * sin(angle).toFloat(),
                    )
                }
                // 轴辐线
                for (axisIndex in 0 until axisCount) {
                    drawLine(
                        color = gridColor,
                        start = center,
                        end = axisPoint(axisIndex, 1f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                // 3 圈同心多边形网格
                for (ring in 1..3) {
                    val fraction = ring / 3f
                    val ringPath = Path()
                    for (axisIndex in 0 until axisCount) {
                        val point = axisPoint(axisIndex, fraction)
                        if (axisIndex == 0) ringPath.moveTo(point.x, point.y) else ringPath.lineTo(point.x, point.y)
                    }
                    ringPath.close()
                    drawPath(path = ringPath, color = gridColor, style = Stroke(width = 1.dp.toPx()))
                }
                // 数据多边形
                series.forEachIndexed { seriesIndex, serie ->
                    val color = palette[seriesIndex % palette.size]
                    val dataPath = Path()
                    for (axisIndex in 0 until axisCount) {
                        val value = serie.values.getOrNull(axisIndex) ?: 0.0
                        val fraction = (value / globalMax).toFloat().coerceIn(0f, 1f)
                        val point = axisPoint(axisIndex, fraction)
                        if (axisIndex == 0) dataPath.moveTo(point.x, point.y) else dataPath.lineTo(point.x, point.y)
                    }
                    dataPath.close()
                    drawPath(path = dataPath, color = color.copy(alpha = 0.25f))
                    drawPath(path = dataPath, color = color, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
        if (series.size > 1) {
            OpenUiChartLegend(
                entries = series.mapIndexed { index, serie ->
                    palette[index % palette.size] to serie.name
                },
            )
        }
    }
}

// ── RadialChart ──────────────────────────────────────────────────────────────

private val RADIAL_CHART_SIZE = 200.dp
private val RADIAL_RING_WIDTH = 10.dp
private val RADIAL_RING_GAP = 4.dp

/**
 * RadialChart：Canvas 同心环弧——每 slice 一环（外→内），strokeWidth [RADIAL_RING_WIDTH]
 * 圆头，弧长 = value/maxValue × 360°（非占比归一，绝对值口径），palette 色；下方图例
 * （色点 + category + 值）。
 */
@Composable
private fun OpenUiRadialChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val slices = parseSliceData(node)
    val maxValue = slices.maxOfOrNull { slice -> slice.value } ?: 0.0
    if (slices.isEmpty() || maxValue <= 0.0) {
        Box(modifier = modifier)
        return
    }
    val palette = OpenUiCardTokens.chartPalette
    Column(modifier = modifier.fillMaxWidth()) {
        OpenUiChartTitle(title)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(RADIAL_CHART_SIZE)) {
                val strokePx = RADIAL_RING_WIDTH.toPx()
                val gapPx = RADIAL_RING_GAP.toPx()
                val outerRadius = (minOf(size.width, size.height) - strokePx) / 2
                val center = Offset(size.width / 2, size.height / 2)
                slices.forEachIndexed { index, slice ->
                    val radius = outerRadius - index * (strokePx + gapPx)
                    if (radius > 0f) {
                        val sweep = (slice.value / maxValue * 360.0).toFloat().coerceAtMost(360f)
                        if (sweep > 0f) {
                            drawArc(
                                color = palette[index % palette.size],
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = Offset(center.x - radius, center.y - radius),
                                size = Size(radius * 2, radius * 2),
                                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(top = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            slices.forEachIndexed { index, slice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(Spacing.sm)
                            .clip(CircleShape)
                            .background(palette[index % palette.size]),
                    )
                    Text(text = slice.category, style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = chartPrimitiveText(OpenUiValue.Num(slice.value)) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── SingleStackedBarChart ────────────────────────────────────────────────────

private val SINGLE_STACK_BAR_HEIGHT = 20.dp

/**
 * SingleStackedBarChart：单条水平堆叠条（高 [SINGLE_STACK_BAR_HEIGHT] 圆角，Row weight =
 * 值占比，palette 色段）+ 图例行（色点 + category + 占比%，纯数字无文案 [I18N]）。
 */
@Composable
private fun OpenUiSingleStackedBarChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val slices = parseSliceData(node)
    val total = slices.sumOf { slice -> slice.value }
    if (slices.isEmpty() || total <= 0.0) {
        Box(modifier = modifier)
        return
    }
    val palette = OpenUiCardTokens.chartPalette
    Column(modifier = modifier.fillMaxWidth()) {
        OpenUiChartTitle(title)
        Row(
            modifier = Modifier
                .padding(top = Spacing.xs)
                .fillMaxWidth()
                .height(SINGLE_STACK_BAR_HEIGHT)
                .clip(RoundedCornerShape(SINGLE_STACK_BAR_HEIGHT / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            slices.forEachIndexed { index, slice ->
                if (slice.value > 0.0) {
                    Box(
                        modifier = Modifier
                            .weight(slice.value.toFloat())
                            .fillMaxHeight()
                            .background(palette[index % palette.size]),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(top = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            slices.forEachIndexed { index, slice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(Spacing.sm)
                            .clip(CircleShape)
                            .background(palette[index % palette.size]),
                    )
                    Text(text = slice.category, style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "${(slice.value / total * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── ScatterChart ─────────────────────────────────────────────────────────────

private val SCATTER_CHART_HEIGHT = 200.dp
private val SCATTER_Y_LABEL_MAX_WIDTH = 40.dp

/**
 * ScatterChart：Canvas 带 padding 坐标区——x/y 轴 min..max 线性映射 + 弱色轴线，每 dataset
 * palette 色圆点（半径 4dp；z 存在按 zMin..zMax 映射 3..6dp）；xLabel/yLabel CAPTION 弱色
 * （轴下方/左侧）；datasets>1 加图例行。
 * points 元素兼容两形：嵌套数组 [x,y]/[x,y,z]（schema 实际形态）与 {x,y,z} 对象（防御）。
 */
@Composable
private fun OpenUiScatterChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val xLabel = (node.props["xLabel"] as? OpenUiValue.Str)?.v
    val yLabel = (node.props["yLabel"] as? OpenUiValue.Str)?.v
    val datasets = (node.props["datasets"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val name = (obj["name"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val points = (obj["points"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { pointValue ->
            when (pointValue) {
                is OpenUiValue.Arr -> {
                    val coords = pointValue.v.mapNotNull { coord -> (coord as? OpenUiValue.Num)?.v }
                    if (coords.size >= 2) ScatterPoint(coords[0], coords[1], coords.getOrNull(2)) else null
                }
                is OpenUiValue.Obj -> {
                    val x = (pointValue.v["x"] as? OpenUiValue.Num)?.v
                    val y = (pointValue.v["y"] as? OpenUiValue.Num)?.v
                    if (x != null && y != null) {
                        ScatterPoint(x, y, (pointValue.v["z"] as? OpenUiValue.Num)?.v)
                    } else {
                        null
                    }
                }
                else -> null
            }
        }.take(MAX_SCATTER_POINTS)
        if (points.isEmpty()) null else ScatterDataset(name = name, points = points)
    }.take(MAX_SCATTER_DATASETS)
    if (datasets.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val palette = OpenUiCardTokens.chartPalette
    val allPoints = datasets.flatMap { dataset -> dataset.points }
    val xMin = allPoints.minOf { point -> point.x }
    val xMax = allPoints.maxOf { point -> point.x }
    val yMin = allPoints.minOf { point -> point.y }
    val yMax = allPoints.maxOf { point -> point.y }
    val xRange = if (xMax > xMin) xMax - xMin else 1.0
    val yRange = if (yMax > yMin) yMax - yMin else 1.0
    val zValues = allPoints.mapNotNull { point -> point.z }
    val zMin = zValues.minOrNull()
    val zMax = zValues.maxOrNull()
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Column(modifier = modifier.fillMaxWidth()) {
        OpenUiChartTitle(title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            yLabel?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = SCATTER_Y_LABEL_MAX_WIDTH)
                        .padding(end = Spacing.xs),
                )
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(SCATTER_CHART_HEIGHT),
            ) {
                val inset = 8.dp.toPx()
                val plotWidth = size.width - inset * 2
                val plotHeight = size.height - inset * 2
                fun pointAt(point: ScatterPoint): Offset = Offset(
                    x = inset + (((point.x - xMin) / xRange).toFloat() * plotWidth),
                    y = inset + ((1f - ((point.y - yMin) / yRange).toFloat()) * plotHeight),
                )
                // 弱色轴线（左 + 下）
                drawLine(
                    color = axisColor,
                    start = Offset(inset, inset),
                    end = Offset(inset, size.height - inset),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = axisColor,
                    start = Offset(inset, size.height - inset),
                    end = Offset(size.width - inset, size.height - inset),
                    strokeWidth = 1.dp.toPx(),
                )
                datasets.forEachIndexed { datasetIndex, dataset ->
                    val color = palette[datasetIndex % palette.size]
                    dataset.points.forEach { point ->
                        val radiusDp = if (point.z != null && zMin != null && zMax != null && zMax > zMin) {
                            3f + ((point.z - zMin) / (zMax - zMin)).toFloat() * 3f
                        } else {
                            4f
                        }
                        drawCircle(color = color, radius = radiusDp.dp.toPx(), center = pointAt(point))
                    }
                }
            }
        }
        xLabel?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            )
        }
        if (datasets.size > 1) {
            OpenUiChartLegend(
                entries = datasets.mapIndexed { index, dataset ->
                    palette[index % palette.size] to dataset.name
                },
            )
        }
    }
}
