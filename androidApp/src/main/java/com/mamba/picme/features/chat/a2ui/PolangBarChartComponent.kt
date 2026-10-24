package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole

/**
 * polang 目录自研组件：月度照片计数柱状图（A2UI 线格式侧 name="BarChart"）。
 *
 * 数据全部经数据模型绑定（GenUI 只生成骨架不生成数据）：buckets=[{label,count}]，
 * selected=选中桶 label。桶点选经 dispatchAction("event"/selectBucket) 由
 * A2uiCardHost 的 A2uiActionInterceptor 消费（不上行，§3.4 不变式）。
 * 样式走 design token（MaterialTheme.colorScheme.primary），禁硬编码色值。
 *
 * 注意：[description] 会进 catalog schema 发给云端 LLM，与 server RenderUiToolService
 * 的 @LLMDescription 是内容对齐关系——改动须双向核对（同 A2uiCardContract 文件头约定）。
 */
class PolangBarChartComponent : A2uiComponent {

    override val name: String = NAME
    override val description: String =
        "月度照片计数柱状图。buckets 为 [{label: String, count: Int}] 列表，必须写数据绑定 " +
            "{\"path\":\"/buckets\"}（数据经 updateDataModel 下发，禁裸写数据）；selected 必须写数据绑定 " +
            "{\"path\":\"/selected\"}（端侧点选高亮回写该路径），禁止写裸字符串字面量。"

    override val properties: List<A2uiProperty<*>> = listOf(TITLE, TITLE_ROLE, BUCKETS, SELECTED)

    @Composable
    override fun A2uiComponentScope.isReady(properties: A2uiComponentProperties): Boolean =
        properties.bind(BUCKETS) != null

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val title = properties.bind(TITLE)
        val titleRole = A2uiStyleMapper.parseTextRole(properties.bind(TITLE_ROLE))
        val rawBuckets = properties.bind(BUCKETS)
        val selected = properties.bind(SELECTED)
        val buckets = rawBuckets.asBuckets()
        if (buckets.isEmpty()) {
            // 数据未到/类型错：isReady 已挡，这里防御性空渲染
            Box(modifier = modifier)
            return
        }
        val maxCount = buckets.maxOf { bucket -> bucket.count }.coerceAtLeast(1)
        val barColor = MaterialTheme.colorScheme.primary
        Column(modifier = modifier.fillMaxWidth()) {
            title?.let { text ->
                Text(text = text, style = A2uiStyleMapper.textStyle(titleRole ?: A2uiTextRole.SUBHEAD))
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(A2uiCardTokens.barAreaHeight),
                horizontalArrangement = Arrangement.spacedBy(A2uiCardTokens.barGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (bucket in buckets) {
                    val isSelected = bucket.label == selected
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barChartBarHeight(bucket.count, maxCount))
                                .background(
                                    if (isSelected) barColor
                                    else barColor.copy(alpha = A2uiCardTokens.barUnselectedAlpha)
                                )
                                .clickable {
                                    dispatchAction(
                                        mapOf(
                                            "event" to mapOf(
                                                "name" to EVENT_SELECT_BUCKET,
                                                "context" to mapOf("bucket" to bucket.label),
                                            )
                                        )
                                    )
                                }
                        )
                        Text(text = bucket.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    private data class Bucket(val label: String, val count: Int)

    private fun Any?.asBuckets(): List<Bucket> {
        val list = this as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val label = map["label"] as? String ?: return@mapNotNull null
            val count = (map["count"] as? Number)?.toInt() ?: return@mapNotNull null
            // count 钳制非负：数据模型是半信任输入，负高度会在 layout 期抛异常
            Bucket(label = label, count = count.coerceAtLeast(0))
        }
    }

    companion object {
        const val NAME = "BarChart"
        const val EVENT_SELECT_BUCKET = "selectBucket"

        private val TITLE = A2uiProperty.dynamicString("title", description = "图表标题")
        private val TITLE_ROLE = A2uiProperty.dynamicString(
            "titleRole",
            description = "标题语义层级：HEADLINE|SUBHEAD|BODY|CAPTION（默认 SUBHEAD）",
        )
        private val BUCKETS =
            A2uiProperty.dynamicValue(
                "buckets",
                required = true,
                description = "[{label,count}] 月桶列表，必须写数据绑定 {\"path\":\"/buckets\"}",
            )
        private val SELECTED = A2uiProperty.dynamicString(
            "selected",
            description = "选中桶 label，必须写数据绑定 {\"path\":\"/selected\"}（禁裸字符串）",
        )
    }
}

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
