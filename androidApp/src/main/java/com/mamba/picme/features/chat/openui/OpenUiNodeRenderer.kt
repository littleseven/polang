package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.chat.a2ui.A2uiStyleMapper

/**
 * OpenUI 组件树 → Compose 递归渲染（openui P2；与 OpenUiLibrary 六组件一一对应）。
 *
 * 语义样式令牌（role/colorRole/spacing）只读复用 [A2uiStyleMapper]（两线同一套
 * Design Token 映射，保证双端/双线视觉一致）；BarChart 视觉与 PolangBarChartComponent
 * 逐项对齐（同一 A2uiCardTokens + primary 色 + 选中高亮规则）。
 *
 * 半信任输入防御：未知组件/null 字面量 → 空 Box 占位，不炸整卡；BarChart buckets
 * 元素形态不符剔除、count 钳制非负（同 A2UI 侧口径）。
 */
@Composable
fun OpenUiNodeRenderer(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node.type) {
        "Card" -> Card(modifier = modifier.fillMaxWidth(), shape = AppShapes.card) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(node.spacingDp()),
            ) {
                OpenUiChildren(node, state, onAction)
            }
        }
        "Row" -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(node.spacingDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenUiChildren(node, state, onAction)
        }
        "Column" -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(node.spacingDp()),
        ) {
            OpenUiChildren(node, state, onAction)
        }
        "Text" -> {
            val text = (node.props["text"] as? OpenUiValue.Str)?.v ?: return
            Text(
                text = text,
                style = A2uiStyleMapper.textStyle(
                    A2uiStyleMapper.parseTextRole((node.props["role"] as? OpenUiValue.Str)?.v)
                ),
                color = A2uiStyleMapper.color(
                    A2uiStyleMapper.parseColorRole((node.props["colorRole"] as? OpenUiValue.Str)?.v)
                ),
                modifier = modifier,
            )
        }
        "Button" -> {
            val label = (node.props["label"] as? OpenUiValue.Str)?.v ?: return
            val actions = (node.props["action"] as? OpenUiValue.ActionVal)?.actions.orEmpty()
            Button(onClick = { actions.forEach(onAction) }, modifier = modifier) {
                Text(text = label)
            }
        }
        "BarChart" -> OpenUiBarChart(node, state, onAction, modifier)
        else -> Box(modifier = modifier) // 未知组件：空占位，不炸整卡
    }
}

@Composable
private fun OpenUiChildren(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
) {
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()
    for (child in children) {
        when (child) {
            is OpenUiValue.ComponentVal -> OpenUiNodeRenderer(child, state, onAction)
            else -> Box(modifier = Modifier) // null 字面量/被剔除元素：空占位
        }
    }
}

private fun OpenUiValue.ComponentVal.spacingDp() =
    A2uiStyleMapper.spacing(
        A2uiStyleMapper.parseSpacingRole((props["spacing"] as? OpenUiValue.Str)?.v)
    )

private data class Bucket(val label: String, val count: Int)

/** BarChart：视觉与 PolangBarChartComponent 逐项对齐；点桶 = @Set($selected, label)。 */
@Composable
private fun OpenUiBarChart(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val buckets = (node.props["buckets"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val count = (obj["count"] as? OpenUiValue.Num)?.v?.toInt() ?: return@mapNotNull null
        Bucket(label = label, count = count.coerceAtLeast(0))
    }
    if (buckets.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    // selected 是 BINDABLE_STRING：Binding → 从 state 快照读；Str 字面量 → 直接用（LLM 意图预选）
    val selected = when (val prop = node.props["selected"]) {
        is OpenUiValue.Binding -> (state[prop.name] as? OpenUiValue.Str)?.v
        is OpenUiValue.Str -> prop.v
        else -> null
    }
    val maxCount = buckets.maxOf { bucket -> bucket.count }.coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let { text ->
            Text(text = text, style = MaterialTheme.typography.titleSmall)
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
                            .height(A2uiCardTokens.barAreaHeight * bucket.count / maxCount)
                            .background(
                                if (isSelected) barColor
                                else barColor.copy(alpha = A2uiCardTokens.barUnselectedAlpha)
                            )
                            .clickable {
                                onAction(
                                    OpenUiAction(
                                        name = "Set",
                                        args = listOf(OpenUiValue.Str(bucket.label)),
                                        target = OpenUiContract.STATE_SELECTED,
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
