package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.core.designsystem.components.AppSlider
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.chat.a2ui.A2uiStyleMapper
import com.mamba.picme.features.chat.a2ui.BAR_CHART_SCROLL_BAR_WIDTH
import com.mamba.picme.features.chat.a2ui.BAR_CHART_SCROLL_THRESHOLD
import com.mamba.picme.features.chat.a2ui.BarChartBucketLabel
import com.mamba.picme.features.chat.a2ui.barChartBarHeight
import com.mamba.picme.features.chat.a2ui.barChartLabelInterval

/**
 * OpenUI 组件树 → Compose 递归渲染（openui P2 + M2 十四组件；与 OpenUiLibrary 一一对应）。
 *
 * 语义样式令牌（role/colorRole/spacing）只读复用 [A2uiStyleMapper]（两线同一套
 * Design Token 映射，保证双端/双线视觉一致）；BarChart 视觉与 PolangBarChartComponent
 * 逐项对齐（同一 A2uiCardTokens + 柱高公式 barChartBarHeight + 标签抽稀
 * barChartLabelInterval + primary 色 + 选中高亮规则）；Image 经 [photoResolver]
 * 渲染期端侧 id→uri 解析（[PRIVACY]：uri 不出端，spec《openui-image-component-design》D4）。
 *
 * M2（2026-10-24，spec《openui-component-library-m2-design》）：
 * - Text.text 升 BINDABLE_STRING：Binding 从 state 快照读，解析不出丢该 Text 不炸卡；
 * - Icon name → 图标映射用 openui 包内 [openUiIcon]（A2UI catalog v1 59 枚同口径；
 *   material3-a2ui 内建 A2uiIcon 是库内 internal 不可复用，故映射表自持并由测试钉住同源）；
 * - List VERTICAL 限高纵向滚动（[LIST_VERTICAL_MAX_HEIGHT]，规避聊天 LazyColumn 嵌套滚动）；
 * - Tabs 选中页签是渲染期 remember 本地态，不进 OpenUI state（纯 UI 导航无 LLM 语义）；
 * - CheckBox/ChoicePicker/Slider 交互经 @Set 写 state（渲染期构造 OpenUiAction，与 BarChart
 *   点选同机制）；Binding 缺失（LLM 传裸字面量）→ 只读展示不写入（BarChart 裸字面量口径）；
 * - Slider 渲染用 AppSlider（全 app 滑杆红线，禁裸 M3 Slider）。
 *
 * 半信任输入防御：未知组件/null 字面量 → 空 Box 占位，不炸整卡；BarChart buckets
 * 元素形态不符剔除、count 钳制非负（同 A2UI 侧口径）；Image 解析不到/加载中 →
 * surfaceVariant 占位 Box（同形防跳动）。
 */
@Composable
fun OpenUiNodeRenderer(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node.type) {
        "Card" -> Card(modifier = modifier.fillMaxWidth(), shape = AppShapes.card) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(node.spacingDp()),
            ) {
                OpenUiChildren(node, state, photoResolver, onAction)
            }
        }
        "Row" -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(node.spacingDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 子组件等宽分栏：无 weight 时首栏按内容占宽，后续栏被压成单字竖条
            // （2026-10-24 复杂图文排版真机事故）
            val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()
            for (child in children) {
                if (child is OpenUiValue.ComponentVal) {
                    OpenUiNodeRenderer(child, state, photoResolver, onAction, Modifier.weight(1f))
                } else {
                    Box(modifier = Modifier.weight(1f)) // null 字面量/被剔除元素：等宽空占位
                }
            }
        }
        "Column" -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(node.spacingDp()),
        ) {
            OpenUiChildren(node, state, photoResolver, onAction)
        }
        "Text" -> {
            // M2：text 升 BINDABLE_STRING——Binding 从 state 快照读；解析不出丢该 Text 不炸卡
            val text = when (val prop = node.props["text"]) {
                is OpenUiValue.Str -> prop.v
                is OpenUiValue.Binding -> (state[prop.name] as? OpenUiValue.Str)?.v
                else -> null
            } ?: return
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
        "Image" -> OpenUiPhotoImage(node, photoResolver, modifier)
        "Icon" -> OpenUiIcon(node, modifier)
        "Divider" -> OpenUiDivider(node, modifier)
        "List" -> OpenUiList(node, state, photoResolver, onAction, modifier)
        "Tabs" -> OpenUiTabs(node, state, photoResolver, onAction, modifier)
        "CheckBox" -> OpenUiCheckBox(node, state, onAction, modifier)
        "ChoicePicker" -> OpenUiChoicePicker(node, state, onAction, modifier)
        "Slider" -> OpenUiSlider(node, state, onAction, modifier)
        else -> Box(modifier = modifier) // 未知组件：空占位，不炸整卡
    }
}

@Composable
private fun OpenUiChildren(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
) {
    OpenUiChildList(
        (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty(),
        state,
        photoResolver,
        onAction,
    )
}

/** 渲染任意组件数组槽（children / Tabs 页签内容）：非组件元素（null 字面量/被剔除）空占位。 */
@Composable
private fun OpenUiChildList(
    children: List<OpenUiValue>,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
) {
    for (child in children) {
        if (child is OpenUiValue.ComponentVal) {
            OpenUiNodeRenderer(child, state, photoResolver, onAction)
        } else {
            Box(modifier = Modifier) // null 字面量/被剔除元素：空占位
        }
    }
}

/** Image（图文混排）：photo = #photos 投影元素的 id（渲染期端侧 id→uri，[PRIVACY] uri 不出端）。 */
@Composable
private fun OpenUiPhotoImage(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    modifier: Modifier = Modifier,
) {
    val photoId = (node.props["photo"] as? OpenUiValue.Num)?.v?.toLong()
    // aspect 宽高比（宽/高）：渲染期缺省 4:3，clamp 到 [0.5, 2.5]（schema 无默认字面量）
    val aspect = (node.props["aspect"] as? OpenUiValue.Num)?.v
        ?.let { raw -> raw.coerceIn(0.5, 2.5) } ?: (4.0 / 3.0)
    val frameModifier = modifier
        .fillMaxWidth()
        .aspectRatio(aspect.toFloat())
        .clip(AppShapes.card)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (photoId == null) {
        // 缺必填 photo（schema 校验已丢组件，此处仅防御兜底）：同形占位，不炸整卡
        Box(modifier = frameModifier)
        return
    }
    val photo by produceState<OpenUiPhoto?>(initialValue = null, photoId) {
        value = photoResolver.resolve(photoId)
    }
    val resolved = photo
    if (resolved == null) {
        // 解析中/解析失败（已删除/未索引）：同形同参 surfaceVariant 占位
        Box(modifier = frameModifier)
    } else {
        AsyncImage(
            model = resolved.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = faceAwareVerticalAlignment(resolved.faceFocusY),
            modifier = frameModifier,
        )
    }
}

private fun OpenUiValue.ComponentVal.spacingDp() =
    A2uiStyleMapper.spacing(
        A2uiStyleMapper.parseSpacingRole((props["spacing"] as? OpenUiValue.Str)?.v)
    )

/** List VERTICAL 限高（spec §2：约 320dp，规避聊天 LazyColumn 内嵌滚动的嵌套事故）。 */
private val LIST_VERTICAL_MAX_HEIGHT = 320.dp

/** Icon：name → Material 图标映射（A2UI catalog v1 59 枚同口径，见 [openUiIcon]）。 */
@Composable
private fun OpenUiIcon(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    // schema ENUM 域 + server 白名单双层已校验，null 仅防御兜底（丢组件不炸卡）
    val image = openUiIcon(name) ?: return
    Icon(
        imageVector = image,
        contentDescription = null,
        tint = A2uiStyleMapper.color(
            A2uiStyleMapper.parseColorRole((node.props["colorRole"] as? OpenUiValue.Str)?.v)
        ),
        modifier = modifier,
    )
}

/** Divider：axis=HORIZONTAL（默认）横线 / VERTICAL 竖线。 */
@Composable
private fun OpenUiDivider(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    if ((node.props["axis"] as? OpenUiValue.Str)?.v == "VERTICAL") {
        VerticalDivider(modifier = modifier)
    } else {
        HorizontalDivider(modifier = modifier.fillMaxWidth())
    }
}

/** List：VERTICAL（默认）限高纵向滚动列；HORIZONTAL 横滑行（MediaResultsCarousel 先例）。 */
@Composable
private fun OpenUiList(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()
    val spacing = node.spacingDp()
    if ((node.props["direction"] as? OpenUiValue.Str)?.v == "HORIZONTAL") {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            OpenUiChildList(children, state, photoResolver, onAction)
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = LIST_VERTICAL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            OpenUiChildList(children, state, photoResolver, onAction)
        }
    }
}

/** Tabs 页签数据（title + 组件数组内容）。 */
private data class OpenUiTab(val title: String, val children: List<OpenUiValue>)

/** Tabs：选中页签是渲染期 remember 本地态，不进 OpenUI state（纯 UI 导航，无 LLM 语义）。 */
@Composable
private fun OpenUiTabs(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = (node.props["tabs"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val title = (obj["title"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        OpenUiTab(title = title, children = (obj["children"] as? OpenUiValue.Arr)?.v.orEmpty())
    }
    if (tabs.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    // remember(node)：node 数据类相等（tabs 内容未变）时跨 state 回写重求值保持选中页签
    var selectedTab by remember(node) { mutableIntStateOf(0) }
    val safeIndex = selectedTab.coerceIn(0, tabs.size - 1)
    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = safeIndex) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == safeIndex,
                    onClick = { selectedTab = index },
                    text = { Text(text = tab.title) },
                )
            }
        }
        Column(modifier = Modifier.padding(top = Spacing.sm)) {
            OpenUiChildList(tabs[safeIndex].children, state, photoResolver, onAction)
        }
    }
}

/**
 * CheckBox：checked BINDABLE_BOOLEAN——Binding → state 快照读，点按 → @Set(绑定变量, !当前值)；
 * 裸 Bool 字面量 → 只读展示不写入（BarChart 裸字面量口径）。解析不出/类型不符 → 丢组件不炸卡。
 */
@Composable
private fun OpenUiCheckBox(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = (node.props["label"] as? OpenUiValue.Str)?.v ?: return
    val checkedProp = node.props["checked"]
    val bindingName = (checkedProp as? OpenUiValue.Binding)?.name
    val checked = when (checkedProp) {
        is OpenUiValue.Binding -> (state[checkedProp.name] as? OpenUiValue.Bool)?.v
        is OpenUiValue.Bool -> checkedProp.v
        else -> null
    } ?: return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = { newValue ->
                bindingName?.let { name ->
                    onAction(
                        OpenUiAction(
                            name = "Set",
                            args = listOf(OpenUiValue.Bool(newValue)),
                            target = name,
                        )
                    )
                }
            },
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * ChoicePicker：单选 chips 行；selected BINDABLE_STRING——Binding → state 快照读（未解析 =
 * 无选中态），点选 → @Set(绑定变量, value)；裸字面量只读展示不写入。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenUiChoicePicker(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = (node.props["options"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val value = (obj["value"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        label to value
    }
    if (options.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val label = (node.props["label"] as? OpenUiValue.Str)?.v
    val selectedProp = node.props["selected"]
    val bindingName = (selectedProp as? OpenUiValue.Binding)?.name
    val selected = when (selectedProp) {
        is OpenUiValue.Binding -> (state[selectedProp.name] as? OpenUiValue.Str)?.v
        is OpenUiValue.Str -> selectedProp.v
        else -> null
    }
    Column(modifier = modifier.fillMaxWidth()) {
        label?.let { text ->
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            options.forEach { (optionLabel, optionValue) ->
                FilterChip(
                    selected = optionValue == selected,
                    onClick = {
                        bindingName?.let { name ->
                            onAction(
                                OpenUiAction(
                                    name = "Set",
                                    args = listOf(OpenUiValue.Str(optionValue)),
                                    target = name,
                                )
                            )
                        }
                    },
                    label = { Text(text = optionLabel) },
                )
            }
        }
    }
}

/**
 * Slider：value BINDABLE_NUMBER（AppSlider 渲染，全 app 滑杆红线）——拖动期本地态，
 * 拖动结束 → @Set(绑定变量, 数值)；裸数字字面量只读展示不写入。解析不出 → 丢组件不炸卡。
 */
@Composable
private fun OpenUiSlider(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = (node.props["label"] as? OpenUiValue.Str)?.v
    val min = ((node.props["min"] as? OpenUiValue.Num)?.v ?: 0.0).toFloat()
    val maxRaw = ((node.props["max"] as? OpenUiValue.Num)?.v ?: 100.0).toFloat()
    val max = if (maxRaw > min) maxRaw else min + 1f // min>=max 是 LLM 笔误：钳成非空区间不炸卡
    val valueProp = node.props["value"]
    val bindingName = (valueProp as? OpenUiValue.Binding)?.name
    val resolved = when (valueProp) {
        is OpenUiValue.Binding -> (state[valueProp.name] as? OpenUiValue.Num)?.v
        is OpenUiValue.Num -> valueProp.v
        else -> null
    } ?: return
    // remember(node)：props 含 Binding 引用（回写后不变），拖动态跨 state 回写重求值保持
    var dragValue by remember(node) { mutableStateOf(resolved.toFloat().coerceIn(min, max)) }
    Column(modifier = modifier.fillMaxWidth()) {
        label?.let { text ->
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
        AppSlider(
            value = dragValue,
            onValueChange = { newValue -> dragValue = newValue },
            valueRange = min..max,
            onValueChangeFinished = {
                bindingName?.let { name ->
                    onAction(
                        OpenUiAction(
                            name = "Set",
                            args = listOf(OpenUiValue.Num(dragValue.toDouble())),
                            target = name,
                        )
                    )
                }
            },
        )
    }
}

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
    // 多桶抽稀：与 PolangBarChartComponent 同一 barChartLabelInterval（all_years ~80 桶
    // 全量标签叠成数字串的事故在 A2UI 侧已修，此处保持逐项对齐）
    val labelInterval = barChartLabelInterval(buckets.size)
    // 超多桶横滑模式（同 PolangBarChartComponent：桶数过阈 weight 列归零，柱体隐身）
    val scrollMode = buckets.size > BAR_CHART_SCROLL_THRESHOLD
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let { text ->
            Text(text = text, style = MaterialTheme.typography.titleSmall)
        }
        Row(
            modifier = (
                if (scrollMode) {
                    Modifier.horizontalScroll(rememberScrollState())
                } else {
                    Modifier.fillMaxWidth()
                }
                ).height(A2uiCardTokens.barAreaHeight),
            horizontalArrangement = Arrangement.spacedBy(A2uiCardTokens.barGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEachIndexed { index, bucket ->
                val isSelected = bucket.label == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (scrollMode) {
                        Modifier.width(BAR_CHART_SCROLL_BAR_WIDTH)
                    } else {
                        Modifier.weight(1f)
                    },
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
                                onAction(
                                    OpenUiAction(
                                        name = "Set",
                                        args = listOf(OpenUiValue.Str(bucket.label)),
                                        target = OpenUiContract.STATE_SELECTED,
                                    )
                                )
                            }
                    )
                    // 抽稀位也占满标签高：柱底对齐依赖「柱+标签」恒高（barChartBarHeight KDoc）
                    if (index % labelInterval == 0 || isSelected) {
                        if (labelInterval > 1) {
                            BarChartBucketLabel(bucket.label)
                        } else {
                            Text(text = bucket.label, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Box(modifier = Modifier.height(A2uiCardTokens.barLabelHeight))
                    }
                }
            }
        }
    }
}
