package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.AppSliderTokens
import com.mamba.picme.core.designsystem.BadgeTokens
import com.mamba.picme.core.designsystem.IconSize
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.core.designsystem.StatusColor
import com.mamba.picme.core.designsystem.components.AppSlider
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.chat.a2ui.A2uiStyleMapper
import com.mamba.picme.features.chat.a2ui.BAR_CHART_SCROLL_BAR_WIDTH
import com.mamba.picme.features.chat.a2ui.BAR_CHART_SCROLL_THRESHOLD
import com.mamba.picme.features.chat.a2ui.BarChartBucketLabel
import com.mamba.picme.features.chat.a2ui.barChartBarHeight
import com.mamba.picme.features.chat.a2ui.barChartLabelInterval
import com.mamba.picme.features.common.chat.AgentMarkdown

/**
 * OpenUI 组件树 → Compose 递归渲染（openui P2 + M2/M3 二十一组件；与 OpenUiLibrary 一一对应）。
 *
 * 语义样式令牌（role/colorRole/spacing）：枚举解析与 colorRole 颜色映射只读复用
 * [A2uiStyleMapper]；字阶/间距阶梯自 M4 起走 OpenUI 专属 [OpenUiCardTokens]（A2UI 是
 * 对比实验对照组，样式冻结）。BarChart 几何与 PolangBarChartComponent 逐项对齐（同一
 * A2uiCardTokens + 柱高公式 barChartBarHeight + 标签抽稀 barChartLabelInterval + 选中高亮规则，
 * M4 起单系列柱色换色板首色）；Image 经 [photoResolver]
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
 * M3（2026-10-24，spec《openui-component-library-m3-design》）+7：
 * - Table：表头行 + 数据行（[MAX_TABLE_ROWS] 护栏），等宽列，列宽合计超容器宽时横滑；
 * - PieChart：Canvas 扇形 + 图例列（M4 起色板换 6 色相离散色，见 [OpenUiCardTokens.chartPalette]），
 *   [MAX_PIE_SLICES] 护栏；
 * - LineChart：Canvas 折线 + 数据点 + 横轴标签抽稀（barChartLabelInterval 先例），
 *   values 缺位 = null 断点断开折线，[MAX_LINE_SERIES] 护栏；
 * - Callout：tone（INFO/SUCCESS/WARNING/ERROR）→ StatusColor 色块 + Icon 映射 + 可选加粗 title；
 * - Markdown：复用 [AgentMarkdown]（mikepenz，全 app 唯一 Agent 正文 Markdown 入口）；
 * - Accordion：展开集是渲染期 remember 本地态（Tabs 同口径，键 = items 标题列表）；
 * - Switch：M3 Switch 配 token 色（AppSlider 红线只管滑杆，Switch 无既有封装），
 *   checked BINDABLE_BOOLEAN 与 CheckBox 同构（点按 → @Set 回写，裸字面量只读）。
 *
 * M4（2026-10-25，spec《openui-visual-m4-design》卡内视觉规范，A2UI 对照组零改动）：
 * - 字阶/间距/Divider/Image 圆角收口 OpenUI 专属 [OpenUiCardTokens]：字阶 20B/15SB/14/12，
 *   组件间距默认 md、Section（SUBHEAD/图表/表格）前 lg，Divider 0.5dp 弱色，Image 12dp 圆角；
 * - 图表色板弃「colorScheme 角色 + alpha 阶梯」同族色，换 [OpenUiCardTokens.chartPalette]
 *   6 色相离散色（蓝/橙/绿/紫/红/青）；BarChart 单系列用板首色（蓝），选中高亮沿用 primary；
 * - Tabs 弃 M3 TabRow/ScrollableTabRow，改自绘胶囊分段控件（surfaceVariant 圆角容器 +
 *   primary 选中胶囊），>4 页签横滑；选中态 remember 键 = 页签标题列表（M2 口径不变）；
 * - 空绑定兜底：Text/Markdown 含 Binding 且解析结果 trim 后以 `:`/`：` 结尾 → 丢组件不渲染。
 *
 * M4.2（2026-10-25 真机验收修复，非视觉项）：
 * - Image 无界宽上下文（HORIZONTAL List 横滑行内 fillMaxWidth 失效、aspectRatio 双维无界
 *   塌 0×0）落固定条目宽 [OpenUiCardTokens.horizontalImageWidth]；
 * - Row 同上下文 weight 分栏失效（无限宽约束下子项拿不到宽度）→ 无界宽时子项按内容
 *   自尺寸、不挂 weight（BoxWithConstraints 判界）；
 * - photoResolver 接线升级双通道（AppContainer）：Room 正 id 直查 + 未索引系统媒体负
 *   synthetic id 经 allMedia 反查（此前直查 DAO 致负 id 恒 null、Image 永久占位）。
 * - BarChart 点桶回写目标跟随 selected 的绑定变量（LLM 自定义变量名如 $month 时硬写
 *   $selected 会点了没反应）；未绑定才回落 [OpenUiContract.STATE_SELECTED]。
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
            Column(modifier = Modifier.padding(Spacing.lg)) {
                OpenUiGappedChildren(node, state, photoResolver, onAction)
            }
        }
        "Row" -> run {
            val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()
            // 含 VERTICAL Divider 时行高取内容本征高：fillMaxHeight 竖线在无限高约束下会
            // 撑到父级最大高度（长竖线穿卡），ChatScreen.kt:1564 的 IntrinsicSize.Min 先例
            val hasVerticalDivider = children.any { child ->
                (child as? OpenUiValue.ComponentVal)?.let { component ->
                    component.type == "Divider" &&
                        (component.props["axis"] as? OpenUiValue.Str)?.v == "VERTICAL"
                } == true
            }
            // M4.2：无界宽上下文（HORIZONTAL List 横滑行内）weight 分栏失效（无限宽约束
            // 下子项拿不到宽度），子项改按内容自尺寸——Image 等由自身 BoxWithConstraints
            // 兜底固定条目宽；有界上下文保持等宽分栏（2026-10-24 复杂图文排版事故修复口径）
            BoxWithConstraints(
                modifier = if (hasVerticalDivider) modifier.height(IntrinsicSize.Min) else modifier,
            ) {
                val weightsUsable = maxWidth != Dp.Infinity
                Row(
                    horizontalArrangement = Arrangement.spacedBy(node.spacingDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 子组件等宽分栏：无 weight 时首栏按内容占宽，后续栏被压成单字竖条；
                    // Divider 是分隔符不参与分栏
                    for (child in children) {
                        if (child is OpenUiValue.ComponentVal) {
                            val childModifier =
                                if (child.type == "Divider" || !weightsUsable) {
                                    Modifier
                                } else {
                                    Modifier.weight(1f)
                                }
                            OpenUiNodeRenderer(child, state, photoResolver, onAction, childModifier)
                        } else {
                            // null 字面量/被剔除元素：等宽空占位
                            Box(modifier = if (weightsUsable) Modifier.weight(1f) else Modifier)
                        }
                    }
                }
            }
        }
        "Column" -> Column(modifier = modifier) {
            OpenUiGappedChildren(node, state, photoResolver, onAction)
        }
        "Text" -> {
            // M2：text 升 BINDABLE_STRING——Binding 从 state 快照读；Str 直取，Num/Bool
            // 回退文本形态（Text($level) 绑 Slider 数字变量是 LLM 自然写法，review 🟡2）；
            // 解析不出丢该 Text 不炸卡；M4：空绑定兜底（冒号尾前缀）同口径丢组件
            val textProp = node.props["text"]
            val text = when (textProp) {
                is OpenUiValue.Str -> textProp.v
                is OpenUiValue.Binding -> state[textProp.name]?.let { bound -> openUiPrimitiveText(bound) }
                else -> null
            } ?: return
            if (isEmptyBindingFallback(text)) return
            val roleStr = (node.props["role"] as? OpenUiValue.Str)?.v
            val colorRole = A2uiStyleMapper.parseColorRole((node.props["colorRole"] as? OpenUiValue.Str)?.v)
            // DISPLAY（设计规范 §2：stat tile 大数字）：OpenUI 专属 role，A2uiTextRole 之外
            if (roleStr == "DISPLAY") {
                Text(
                    text = text,
                    style = OpenUiCardTokens.displayStyle,
                    color = A2uiStyleMapper.color(colorRole),
                    modifier = modifier,
                )
                return
            }
            val role = A2uiStyleMapper.parseTextRole(roleStr)
            Text(
                text = text,
                style = OpenUiCardTokens.textStyle(role),
                // CAPTION 未显式声明 colorRole 时弱色（M4 §1.1 字阶：CAPTION 12sp 弱色）
                color = if (role == A2uiTextRole.CAPTION && colorRole == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    A2uiStyleMapper.color(colorRole)
                },
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
        "Table" -> OpenUiTable(node, modifier)
        "PieChart" -> OpenUiPieChart(node, modifier)
        "LineChart" -> OpenUiLineChart(node, modifier)
        "Callout" -> OpenUiCallout(node, modifier)
        "Markdown" -> OpenUiMarkdown(node, state, modifier)
        "Accordion" -> OpenUiAccordion(node, state, photoResolver, onAction, modifier)
        "Switch" -> OpenUiSwitch(node, state, onAction, modifier)
        else -> Box(modifier = modifier) // 未知组件：空占位，不炸整卡
    }
}

/**
 * Card/Column 内容排布（M4 §1.1 间距阶梯）：组件间默认 [spacingDp]（md），Section 起头
 * （SUBHEAD 标题/图表/表格）前升为 [OpenUiCardTokens.sectionGap]（lg）。null 字面量渲染
 * 零尺寸空占位但不贡献间距；Text/Markdown 的确定性丢弃形态（绑定解析不出/空绑定冒号尾，
 * §1.4）布局前预过滤不占隙（review 🟡2：被丢组件前后各贡献一个 Spacer 会形成双倍空隙）。
 */
@Composable
private fun OpenUiGappedChildren(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
) {
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty()
        .filter { child -> child !is OpenUiValue.ComponentVal || child.willRenderTextLike(state) }
    val gap = node.spacingDp()
    var first = true
    for (child in children) {
        if (child is OpenUiValue.ComponentVal) {
            if (!first) {
                Spacer(
                    modifier = Modifier.height(
                        if (child.isSectionStart()) maxOf(gap, OpenUiCardTokens.sectionGap) else gap
                    )
                )
            }
            first = false
            OpenUiNodeRenderer(child, state, photoResolver, onAction)
        } else {
            Box(modifier = Modifier) // null 字面量/被剔除元素：空占位
        }
    }
}

/**
 * Text/Markdown 可渲染性预判（与两处分支的丢弃口径严格同构）：Str 字面量恒渲染；
 * Binding 解析不出（state 缺/值形态不支持）或空绑定冒号尾（§1.4）→ 丢。
 */
private fun OpenUiValue.ComponentVal.willRenderTextLike(state: Map<String, OpenUiValue>): Boolean {
    if (type != "Text" && type != "Markdown") return true
    return when (val prop = props["text"]) {
        is OpenUiValue.Str -> !isEmptyBindingFallback(prop.v)
        is OpenUiValue.Binding -> {
            val resolved = state[prop.name]?.let { bound -> openUiPrimitiveText(bound) }
            resolved != null && !isEmptyBindingFallback(resolved)
        }
        else -> false
    }
}

/** Section 起头判定（M4 §1.1）：SUBHEAD 标题 / 图表 / 表格。 */
private fun OpenUiValue.ComponentVal.isSectionStart(): Boolean =
    type == "BarChart" || type == "PieChart" || type == "LineChart" || type == "Table" ||
        (type == "Text" &&
            A2uiStyleMapper.parseTextRole((props["role"] as? OpenUiValue.Str)?.v) == A2uiTextRole.SUBHEAD)

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
    BoxWithConstraints(modifier = modifier) {
        // M4.2：无界宽上下文（HORIZONTAL List 横滑行内 fillMaxWidth 失效、aspectRatio
        // 双维无界塌成 0×0）落固定条目宽；有界上下文保持铺满
        val frameModifier = (
            if (maxWidth != Dp.Infinity) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(OpenUiCardTokens.horizontalImageWidth)
            }
            )
            .aspectRatio(aspect.toFloat())
            .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius)) // M4 §1.1：直角 → 12dp
            .background(MaterialTheme.colorScheme.surfaceVariant)
        if (photoId == null) {
            // 缺必填 photo（schema 校验已丢组件，此处仅防御兜底）：同形占位，不炸整卡
            Box(modifier = frameModifier)
            return@BoxWithConstraints
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
}

private fun OpenUiValue.ComponentVal.spacingDp() =
    OpenUiCardTokens.spacing(
        A2uiStyleMapper.parseSpacingRole((props["spacing"] as? OpenUiValue.Str)?.v)
    )

/**
 * 空绑定兜底（M4 §1.4，M4.1 放宽）：解析结果 trim 后以 `:`/`：` 结尾（绑定值为空只剩
 * 「当前选中：」式前缀）→ 丢该组件不渲染。不再限 prop 形态：Str+Binding 拼接写法
 * （Text("当前选中: " + $selected)，黄金示例同款）eval 后槽位已是 Str、无法回溯绑定
 * 来源。已知误伤面：静态冒号尾文本（如「备注：」）同丢（review 🔵6 记录在案，可接受）。
 */
private fun isEmptyBindingFallback(resolved: String): Boolean {
    val trimmed = resolved.trim()
    return trimmed.endsWith(":") || trimmed.endsWith("：")
}

/** List VERTICAL 限高（spec §2：约 320dp，规避聊天 LazyColumn 内嵌滚动的嵌套滚动事故）。 */
private val LIST_VERTICAL_MAX_HEIGHT = 320.dp

/** 单 List 子项上限 / 单 Tabs 页签上限（review 🟡4：LLM 生成面无界，渲染端 take 兜底）。 */
private const val MAX_LIST_CHILDREN = 50
private const val MAX_TABS = 8

/** Icon：name → Material 图标映射（A2UI catalog v1 59 枚同口径，见 [openUiIcon]）。 */
@Composable
private fun OpenUiIcon(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    // schema ENUM 域 + server 白名单双层已校验，null 仅防御兜底（丢组件不炸卡）
    val image = openUiIcon(name) ?: return
    // 设计规范 §1.1 职责分离：装饰 Icon 无 colorRole 时弱色（onSurfaceVariant），
    // primary 只留强调性图标（行为色面积收敛）
    val colorRole = A2uiStyleMapper.parseColorRole((node.props["colorRole"] as? OpenUiValue.Str)?.v)
    Icon(
        imageVector = image,
        contentDescription = null,
        tint = colorRole?.let { role -> A2uiStyleMapper.color(role) }
            ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Divider：axis=HORIZONTAL（默认）横线 / VERTICAL 竖线；M4 §1.1：0.5dp 弱色线。 */
@Composable
private fun OpenUiDivider(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = OpenUiCardTokens.dividerAlpha)
    if ((node.props["axis"] as? OpenUiValue.Str)?.v == "VERTICAL") {
        VerticalDivider(
            modifier = modifier,
            thickness = OpenUiCardTokens.dividerThickness,
            color = dividerColor,
        )
    } else {
        HorizontalDivider(
            modifier = modifier.fillMaxWidth(),
            thickness = OpenUiCardTokens.dividerThickness,
            color = dividerColor,
        )
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
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_LIST_CHILDREN)
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

/**
 * Tabs：选中页签是渲染期 remember 本地态，不进 OpenUI state（纯 UI 导航，无 LLM 语义）。
 * M4 §1.3：弃 M3 TabRow/ScrollableTabRow（亮绿指示条刺眼），改自绘胶囊分段控件。
 */
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
    }.take(MAX_TABS)
    if (tabs.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    // 键 = 页签标题列表而非整颗 node：页签内容含 state 派生值时，@Set 回写重求值会改变
    // node 相等性，以 node 为键会把选中页签重置回 0（review 🟡3）
    var selectedTab by remember(tabs.map { tab -> tab.title }) { mutableIntStateOf(0) }
    val safeIndex = selectedTab.coerceIn(0, tabs.size - 1)
    Column(modifier = modifier.fillMaxWidth()) {
        OpenUiTabsCapsuleRow(
            titles = tabs.map { tab -> tab.title },
            selectedIndex = safeIndex,
            onSelect = { index -> selectedTab = index },
        )
        Column(modifier = Modifier.padding(top = Spacing.sm)) {
            OpenUiChildList(tabs[safeIndex].children, state, photoResolver, onAction)
        }
    }
}

/**
 * Tabs 胶囊分段控件（M4 §1.3）：圆角容器（surfaceVariant 底）+ 选中胶囊（primary 底
 * onPrimary 字）+ 未选中（onSurfaceVariant 字）；≤4 页签等分宽，>4 页签横滑
 * （weight 在无限宽约束下崩，Table 同口径）。
 */
@Composable
private fun OpenUiTabsCapsuleRow(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val scrollable = titles.size > 4
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(OpenUiCardTokens.tabsContainerPadding)
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    ) {
        titles.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Box(
                modifier = (if (scrollable) Modifier else Modifier.weight(1f))
                    // review 🔵3：自绘胶囊补齐 M3 Tab 内建的触达高度与选中语义（TalkBack 可读「已选中」）
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .selectable(selected = selected, role = Role.Tab) { onSelect(index) }
                    .padding(
                        horizontal = OpenUiCardTokens.tabsItemPaddingH,
                        vertical = OpenUiCardTokens.tabsItemPaddingV,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    val toggle: (Boolean) -> Unit = { newValue ->
        bindingName?.let { name ->
            onAction(
                OpenUiAction(
                    name = "Set",
                    args = listOf(OpenUiValue.Bool(newValue)),
                    target = name,
                )
            )
        }
    }
    // 整行 toggleable（label 也可点，扩大热区 + 无障碍，review 🔵3）；Checkbox 本体挂 null 防双击
    Row(
        modifier = modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = toggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
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
    // 拖动态跨重组保持；外部写入（其他组件 @Set 同变量 / 重开卡重种）经
    // LaunchedEffect 回写同步 thumb（review 🟡3）；拖动期 resolved 不变（Set 只在拖动结束发）
    var dragValue by remember(node) { mutableStateOf(resolved.toFloat().coerceIn(min, max)) }
    LaunchedEffect(resolved) { dragValue = resolved.toFloat().coerceIn(min, max) }
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

/** BarChart：几何与 PolangBarChartComponent 逐项对齐；M4 起单系列柱色换色板首色（蓝）；点桶 = @Set(selected 绑定变量, label)。 */
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
    val selectedProp = node.props["selected"]
    // M4.2：点桶回写目标跟随 selected 的绑定变量（LLM 会自定义变量名，如 $month——
    // 2026-10-25 真机事故：硬写 $selected 而卡读 $month → 点了没反应）；未绑定回落 $selected
    val selectedTarget = (selectedProp as? OpenUiValue.Binding)?.name ?: OpenUiContract.STATE_SELECTED
    val selected = when (selectedProp) {
        is OpenUiValue.Binding -> (state[selectedProp.name] as? OpenUiValue.Str)?.v
        is OpenUiValue.Str -> selectedProp.v
        else -> null
    }
    val maxCount = buckets.maxOf { bucket -> bucket.count }.coerceAtLeast(1)
    // M4 §1.2：单系列柱色 = 色板首色（蓝）；选中高亮沿用 primary 语义
    val barColor = OpenUiCardTokens.chartSingleSeries
    val selectedBarColor = MaterialTheme.colorScheme.primary
    // 多桶抽稀：与 PolangBarChartComponent 同一 barChartLabelInterval（all_years ~80 桶
    // 全量标签叠成数字串的事故在 A2UI 侧已修，此处保持逐项对齐）
    val labelInterval = barChartLabelInterval(buckets.size)
    // 超多桶横滑模式（同 PolangBarChartComponent：桶数过阈 weight 列归零，柱体隐身）
    val scrollMode = buckets.size > BAR_CHART_SCROLL_THRESHOLD
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let { text ->
            Text(text = text, style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD))
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
                    // 设计规范 §3.1：满高柱道（track）+ 圆角柱端——稀疏数据也有结构感
                    Box(contentAlignment = Alignment.BottomCenter) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barChartBarHeight(maxCount, maxCount))
                                .clip(
                                    RoundedCornerShape(
                                        topStart = OpenUiCardTokens.barCornerRadius,
                                        topEnd = OpenUiCardTokens.barCornerRadius,
                                    )
                                )
                                .background(
                                    MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = OpenUiCardTokens.barTrackAlpha)
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barChartBarHeight(bucket.count, maxCount))
                                .clip(
                                    RoundedCornerShape(
                                        topStart = OpenUiCardTokens.barCornerRadius,
                                        topEnd = OpenUiCardTokens.barCornerRadius,
                                    )
                                )
                                .background(
                                    if (isSelected) selectedBarColor
                                    else barColor.copy(alpha = A2uiCardTokens.barUnselectedAlpha)
                                )
                                .clickable {
                                    onAction(
                                        OpenUiAction(
                                            name = "Set",
                                            args = listOf(OpenUiValue.Str(bucket.label)),
                                            target = selectedTarget,
                                        )
                                    )
                                }
                        )
                    }
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

// ── M3 七组件（2026-10-24，spec《openui-component-library-m3-design》§4.3） ──────

/** 原始值单元格/标签的文本形态（Num 整数不带小数点，同 Text 绑定回退口径；null/其他 → null）。 */
private fun openUiPrimitiveText(value: OpenUiValue): String? = when (value) {
    is OpenUiValue.Str -> value.v
    is OpenUiValue.Num -> if (value.v % 1.0 == 0.0) value.v.toLong().toString() else value.v.toString()
    is OpenUiValue.Bool -> value.v.toString()
    else -> null
}

private const val MAX_TABLE_ROWS = 50
private val TABLE_CELL_MIN_WIDTH = 88.dp

/** Table：表头行 + 数据行（[MAX_TABLE_ROWS] 护栏）；等宽列，列宽合计超容器宽时横滑；缺 cell 渲染空串。 */
@Composable
private fun OpenUiTable(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val columns = (node.props["columns"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        ((item as? OpenUiValue.Obj)?.v?.get("label") as? OpenUiValue.Str)?.v
    }
    // 扁平元素（schema 合法的非嵌套行，LLM 笔误模式）剔除而非渲染成整行空单元格
    val rows = (node.props["rows"] as? OpenUiValue.Arr)?.v.orEmpty()
        .mapNotNull { rowValue -> (rowValue as? OpenUiValue.Arr)?.v }
        .take(MAX_TABLE_ROWS)
    if (columns.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 等宽列；列宽合计放得下就均铺满宽，放不下定宽横滑（weight 在无限宽约束下会崩）
        val fitsWidth = TABLE_CELL_MIN_WIDTH * columns.size <= maxWidth
        Column(
            modifier = if (fitsWidth) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Row {
                columns.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (fitsWidth) Modifier.weight(1f) else Modifier.width(TABLE_CELL_MIN_WIDTH),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))
            rows.forEach { cells ->
                Row(modifier = Modifier.padding(vertical = Spacing.xs)) {
                    columns.forEachIndexed { index, _ ->
                        Text(
                            text = cells.getOrNull(index)?.let { cell -> openUiPrimitiveText(cell) } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (fitsWidth) Modifier.weight(1f) else Modifier.width(TABLE_CELL_MIN_WIDTH),
                        )
                    }
                }
            }
        }
    }
}

private data class PieSlice(val label: String, val value: Double)

private const val MAX_PIE_SLICES = 12
private val PIE_CHART_SIZE = 120.dp

/** PieChart：Canvas 扇形（-90° 起顺时针）+ 图例列（色点 + label + value）；value 钳非负（BarChart 同口径）。 */
@Composable
private fun OpenUiPieChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val slices = (node.props["slices"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val label = (obj["label"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val value = (obj["value"] as? OpenUiValue.Num)?.v ?: return@mapNotNull null
        PieSlice(label = label, value = value.coerceAtLeast(0.0))
    }.take(MAX_PIE_SLICES)
    val total = slices.sumOf { slice -> slice.value }
    if (slices.isEmpty() || total <= 0.0) {
        Box(modifier = modifier)
        return
    }
    val palette = OpenUiCardTokens.chartPalette
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let { text ->
            Text(text = text, style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD))
        }
        Row(
            modifier = Modifier.padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 设计规范 §3.2：donut 环形（弃实心饼）+ 中心总值（数据文本无文案，[I18N]）
            Box(
                modifier = Modifier.size(PIE_CHART_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                val ringWidth = OpenUiCardTokens.pieRingWidth
                Canvas(modifier = Modifier.matchParentSize()) {
                    var startAngle = -90f
                    val stroke = Stroke(width = ringWidth.toPx())
                    // 描边以弧形路径为中心向两侧各扩一半：内缩半描边，环外缘恰贴画布边界
                    val inset = stroke.width / 2
                    val arcTopLeft = Offset(inset, inset)
                    val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                    slices.forEachIndexed { index, slice ->
                        val sweep = (slice.value / total * 360.0).toFloat()
                        if (sweep > 0f) {
                            drawArc(
                                color = palette[index % palette.size],
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = stroke,
                            )
                        }
                        startAngle += sweep
                    }
                }
                Text(
                    text = openUiPrimitiveText(OpenUiValue.Num(total)) ?: "",
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
                        Text(text = slice.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = openUiPrimitiveText(OpenUiValue.Num(slice.value)) ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

private data class LineSeries(val name: String, val values: List<Double?>)

private const val MAX_LINE_SERIES = 4
private const val MAX_LINE_LABELS = 200

/**
 * LineChart：Canvas 折线 + 数据点 + 横轴标签抽稀（barChartLabelInterval 先例）；
 * values 与 labels 按位对齐，缺位补 null 断点（断开折线不连线）。
 * 点按槽中心布点（与标签行 weight Box 中心严格对齐，BarChart 桶列同径）；
 * min==max 平线居中（normalized=0.5，防贴底边裁点）。
 */
@Composable
private fun OpenUiLineChart(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val labels = (node.props["labels"] as? OpenUiValue.Arr)?.v.orEmpty()
        .take(MAX_LINE_LABELS)
        .map { value -> openUiPrimitiveText(value) ?: "" }
    val series = (node.props["series"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val name = (obj["name"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val rawValues = (obj["values"] as? OpenUiValue.Arr)?.v.orEmpty()
        LineSeries(
            name = name,
            values = (0 until labels.size).map { index ->
                (rawValues.getOrNull(index) as? OpenUiValue.Num)?.v
            },
        )
    }.filter { serie -> serie.values.any { value -> value != null } }.take(MAX_LINE_SERIES)
    if (labels.isEmpty() || series.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val palette = OpenUiCardTokens.chartPalette
    val allValues = series.flatMap { serie -> serie.values.filterNotNull() }
    val minValue = allValues.min()
    val maxValue = allValues.max()
    val range = if (maxValue > minValue) maxValue - minValue else 1.0
    val labelInterval = barChartLabelInterval(labels.size)
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let { text ->
            Text(text = text, style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD))
        }
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
            series.forEachIndexed { seriesIndex, serie ->
                val color = palette[seriesIndex % palette.size]
                var previous: Offset? = null
                serie.values.forEachIndexed { index, value ->
                    if (value == null) {
                        previous = null // null 断点：断开折线
                    } else {
                        val point = pointAt(index, value)
                        previous?.let { from ->
                            drawLine(color = color, start = from, end = point, strokeWidth = 2.dp.toPx())
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
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                series.forEachIndexed { index, serie ->
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
                        Text(text = serie.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Callout：tone → StatusColor 色块容器 + Icon 映射（INFO=info/SUCCESS=check/WARNING=warning/
 * ERROR=error）+ 可选 title 加粗行。
 */
@Composable
private fun OpenUiCallout(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val text = (node.props["text"] as? OpenUiValue.Str)?.v ?: return
    val title = (node.props["title"] as? OpenUiValue.Str)?.v
    val tone = (node.props["tone"] as? OpenUiValue.Str)?.v ?: "INFO"
    val toneColor = when (tone) {
        "SUCCESS" -> StatusColor.success
        "WARNING" -> StatusColor.warning
        "ERROR" -> StatusColor.error
        else -> StatusColor.info
    }
    val iconName = when (tone) {
        "SUCCESS" -> "check"
        "WARNING" -> "warning"
        "ERROR" -> "error"
        else -> "info"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(toneColor.copy(alpha = BadgeTokens.tagBackgroundAlpha))
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        openUiIcon(iconName)?.let { image ->
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = toneColor,
                modifier = Modifier.size(IconSize.md),
            )
        }
        Column {
            title?.let { heading ->
                Text(
                    text = heading,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = toneColor,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Markdown：复用 [AgentMarkdown]（mikepenz，全 app 唯一 Agent 正文 Markdown 入口，禁新引
 * markdown 依赖）；text BINDABLE_STRING——Binding 从 state 快照读，解析不出丢该组件不炸卡
 * （同 Text 口径）；M4：空绑定兜底（冒号尾前缀）同 Text 口径丢组件。
 */
@Composable
private fun OpenUiMarkdown(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    modifier: Modifier = Modifier,
) {
    val textProp = node.props["text"]
    val text = when (textProp) {
        is OpenUiValue.Str -> textProp.v
        is OpenUiValue.Binding -> state[textProp.name]?.let { bound -> openUiPrimitiveText(bound) }
        else -> null
    } ?: return
    if (isEmptyBindingFallback(text)) return
    AgentMarkdown(
        content = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = modifier.fillMaxWidth(),
    )
}

private data class AccordionItem(val title: String, val children: List<OpenUiValue>)

/** Accordion 分区数护栏（Tabs MAX_TABS/List MAX_LIST_CHILDREN 同口径：LLM 生成面无界，渲染端 take 兜底）。 */
private const val MAX_ACCORDION_ITEMS = 12

/**
 * Accordion：展开集是渲染期 remember 本地态，不进 OpenUI state（纯 UI 导航，Tabs 同口径）；
 * 键 = items 标题列表（Tabs review 🟡3：node 相等性随 state 派生值变化会把展开态重置）。
 */
@Composable
private fun OpenUiAccordion(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val title = (obj["title"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        AccordionItem(title = title, children = (obj["children"] as? OpenUiValue.Arr)?.v.orEmpty())
    }.take(MAX_ACCORDION_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    var expanded by remember(items.map { item -> item.title }) { mutableStateOf(emptySet<Int>()) }
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val isExpanded = index in expanded
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (isExpanded) expanded - index else expanded + index
                    }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (isExpanded) {
                Column(modifier = Modifier.padding(bottom = Spacing.xs)) {
                    OpenUiChildList(item.children, state, photoResolver, onAction)
                }
            }
        }
    }
}

/**
 * Switch：checked BINDABLE_BOOLEAN（CheckBox 同构）——Binding → state 快照读，点按 →
 * @Set(绑定变量, !当前值)；裸 Bool 字面量只读展示不写入。M3 Switch 配 token 色
 * （AppSlider 红线只管滑杆，Switch 无既有封装）。
 */
@Composable
private fun OpenUiSwitch(
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
    val toggle: (Boolean) -> Unit = { newValue ->
        bindingName?.let { name ->
            onAction(
                OpenUiAction(
                    name = "Set",
                    args = listOf(OpenUiValue.Bool(newValue)),
                    target = name,
                )
            )
        }
    }
    // 整行 toggleable（同 CheckBox：扩大热区 + 无障碍）；Switch 本体挂 null 防双击
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = toggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = AppSliderTokens.thumbColor,
            ),
        )
    }
}
