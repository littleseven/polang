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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.core.designsystem.StatusColor
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiValue

/**
 * OpenUI 卡块批次（M4d，spec《2026-10-25-openui-full-library-design》§3.5）七组件渲染。
 *
 * Block 壳共性：items* OBJECT_ARRAY ≥1（解析后空 → 丢组件不炸卡）、layout?=grid/carousel
 * （缺省 grid → FlowRow 双列，carousel → 横滑定宽条目）、action?（Block 级，点击卡片经
 * `actions.forEach(onAction)` 宿主动作通道）。Item 是对象形状不独立注册，这里直接解析
 * 字段渲染；组件数组槽（lhs/rhs/top/bottom/header/body/footer，COMPONENT_LIST）递归
 * [OpenUiNodeRenderer]——IconText/ImageText/Text/BoldText 等卡块内构件的样式由各自
 * 渲染分支负责（M4a/M4d 各司其职，渲染器位置无关）。
 *
 * 半信任输入防御（§6）：未知组件 → 空 Box 占位；item 缺必填字段剔除，全丢 → 空 Box；
 * items 上限 [MAX_BLOCK_ITEMS] 护栏；VisualCardBlock 题图双源——bgPhoto 相册投影 id
 * （2026-10-31 经典组合版式批次，端侧 id→uri + 人脸聚焦，与 bgImageSrc 二选一、
 * bgPhoto 优先）/ bgImageSrc 限 https（[PRIVACY] 同 Image src 口径），皆无 →
 * surfaceVariant 纯色底。
 */
internal val openUiCardBlockComponentNames: Set<String> = setOf(
    "SnippetCardBlock",
    "OverviewCardBlock",
    "ContextCardBlock",
    "CompositeCardBlock",
    "VisualCardBlock",
    "MetricIndicatorInline",
    "MetricIndicatorWithStrikethrough",
)

/** 单 Block items 上限（List MAX_LIST_CHILDREN 同口径：LLM 生成面无界，渲染端 take 兜底）。 */
private const val MAX_BLOCK_ITEMS = 20

/** VisualCardBlock 大卡高（spec §3.5：大图底 + 底部文字板）。 */
private val VISUAL_CARD_HEIGHT = 160.dp

@Composable
internal fun OpenUiCardBlockNode(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node.type) {
        "SnippetCardBlock" -> OpenUiSnippetCardBlock(node, state, photoResolver, onAction, modifier)
        "OverviewCardBlock" -> OpenUiOverviewCardBlock(node, state, photoResolver, onAction, modifier)
        "ContextCardBlock" -> OpenUiContextCardBlock(node, onAction, modifier)
        "CompositeCardBlock" -> OpenUiCompositeCardBlock(node, state, photoResolver, onAction, modifier)
        "VisualCardBlock" -> OpenUiVisualCardBlock(node, photoResolver, onAction, modifier)
        "MetricIndicatorInline" -> OpenUiMetricIndicator(node, withStrikethrough = false, modifier = modifier)
        "MetricIndicatorWithStrikethrough" -> OpenUiMetricIndicator(node, withStrikethrough = true, modifier = modifier)
        else -> Box(modifier = modifier) // 未知组件：空占位，不炸整卡
    }
}

// ── Block 壳（§3.5 共性） ──────────────────────────────────────────────

/**
 * Block 壳排布：grid（缺省）→ FlowRow 双列（weight 均分，间距 sm）；carousel → 横滑
 * 定宽条目（[OpenUiCardTokens.horizontalImageWidth]，Image M4.2 同径）。Block 级 action
 * 挂在每个 item 卡片上（点击卡 → 宿主动作通道）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OpenUiCardBlockShell(
    node: OpenUiValue.ComponentVal,
    items: List<T>,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier,
    itemContent: @Composable (T, Modifier) -> Unit,
) {
    val actions = openUiActionsOf(node.props["action"])
    val clickModifier = if (actions.isEmpty()) {
        Modifier
    } else {
        Modifier.clickable { actions.forEach(onAction) }
    }
    val carousel = (node.props["layout"] as? OpenUiValue.Str)?.v == "carousel"
    if (carousel) {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items.forEach { item ->
                itemContent(
                    item,
                    Modifier.width(OpenUiCardTokens.horizontalImageWidth).then(clickModifier),
                )
            }
        }
    } else {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            maxItemsInEachRow = 2,
        ) {
            items.forEach { item ->
                itemContent(item, Modifier.weight(1f).then(clickModifier))
            }
        }
    }
}

/** 组件数组槽递归（lhs/rhs/top/bottom/header/body/footer）：非组件元素空占位（OpenUiChildList 同口径）。 */
@Composable
private fun OpenUiCardBlockSlot(
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

private fun OpenUiValue.componentListProp(name: String): List<OpenUiValue>? =
    ((this as? OpenUiValue.Obj)?.v?.get(name) as? OpenUiValue.Arr)?.v

private fun OpenUiValue.stringProp(name: String): String? =
    ((this as? OpenUiValue.Obj)?.v?.get(name) as? OpenUiValue.Str)?.v

// ── SnippetCardBlock（迷你卡：左标签 + 右值） ────────────────────────────

private data class SnippetCardItemData(val lhs: List<OpenUiValue>, val rhs: List<OpenUiValue>)

@Composable
private fun OpenUiSnippetCardBlock(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val lhs = item.componentListProp("lhs")?.takeIf { list -> list.isNotEmpty() }
            ?: return@mapNotNull null // lhs 必填（schema required）：缺则丢该 item
        SnippetCardItemData(lhs = lhs, rhs = item.componentListProp("rhs").orEmpty())
    }.take(MAX_BLOCK_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    OpenUiCardBlockShell(node, items, onAction, modifier) { item, itemModifier ->
        Card(modifier = itemModifier.fillMaxWidth(), shape = AppShapes.card) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpenUiCardBlockSlot(item.lhs, state, photoResolver, onAction)
                }
                if (item.rhs.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column(horizontalAlignment = Alignment.End) {
                        OpenUiCardBlockSlot(item.rhs, state, photoResolver, onAction)
                    }
                }
            }
        }
    }
}

// ── OverviewCardBlock（迷你卡：上标题 + 下指标） ─────────────────────────

private data class OverviewCardItemData(val top: List<OpenUiValue>, val bottom: List<OpenUiValue>)

@Composable
private fun OpenUiOverviewCardBlock(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val top = item.componentListProp("top")?.takeIf { list -> list.isNotEmpty() }
            ?: return@mapNotNull null // top 必填：缺则丢该 item
        OverviewCardItemData(top = top, bottom = item.componentListProp("bottom").orEmpty())
    }.take(MAX_BLOCK_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    OpenUiCardBlockShell(node, items, onAction, modifier) { item, itemModifier ->
        Card(modifier = itemModifier.fillMaxWidth(), shape = AppShapes.card) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                OpenUiCardBlockSlot(item.top, state, photoResolver, onAction)
                if (item.bottom.isNotEmpty()) {
                    OpenUiCardBlockSlot(item.bottom, state, photoResolver, onAction)
                }
            }
        }
    }
}

// ── ContextCardBlock（着色迷你卡：标题 + 正文） ──────────────────────────

private data class ContextCardItemData(val title: String, val body: String?)

@Composable
private fun OpenUiContextCardBlock(
    node: OpenUiValue.ComponentVal,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val title = item.stringProp("title") ?: return@mapNotNull null // title 必填
        ContextCardItemData(title = title, body = item.stringProp("body"))
    }.take(MAX_BLOCK_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    OpenUiCardBlockShell(node, items, onAction, modifier) { item, itemModifier ->
        Card(modifier = itemModifier.fillMaxWidth(), shape = AppShapes.card) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(containerColor)
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = item.title,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                item.body?.let { body ->
                    Text(
                        text = body,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                        color = contentColor,
                    )
                }
            }
        }
    }
}

// ── CompositeCardBlock（富卡：header + body 栈 + footer） ────────────────

private data class CompositeCardItemData(
    val header: List<OpenUiValue>,
    val body: List<OpenUiValue>,
    val footer: List<OpenUiValue>,
)

@Composable
private fun OpenUiCompositeCardBlock(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val body = item.componentListProp("body")?.takeIf { list -> list.isNotEmpty() }
            ?: return@mapNotNull null // body 必填：缺则丢该 item
        CompositeCardItemData(
            header = item.componentListProp("header").orEmpty(),
            body = body,
            footer = item.componentListProp("footer").orEmpty(),
        )
    }.take(MAX_BLOCK_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    OpenUiCardBlockShell(node, items, onAction, modifier) { item, itemModifier ->
        Card(modifier = itemModifier.fillMaxWidth(), shape = AppShapes.card) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (item.header.isNotEmpty()) {
                    OpenUiCardBlockSlot(item.header, state, photoResolver, onAction)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.xs),
                        thickness = OpenUiCardTokens.dividerThickness,
                        color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = OpenUiCardTokens.dividerAlpha),
                    )
                }
                OpenUiCardBlockSlot(item.body, state, photoResolver, onAction)
                if (item.footer.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.xs),
                        thickness = OpenUiCardTokens.dividerThickness,
                        color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = OpenUiCardTokens.dividerAlpha),
                    )
                    OpenUiCardBlockSlot(item.footer, state, photoResolver, onAction)
                }
            }
        }
    }
}

// ── VisualCardBlock（大图底 + 底部文字板；2026-10-31 +bgPhoto 相册投影题图） ──────────

private data class VisualCardItemData(
    val body: String,
    val bgImageSrc: String?,
    val bgPhoto: Long?,
    val tag: String?,
)

@Composable
private fun OpenUiVisualCardBlock(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val body = item.stringProp("body") ?: return@mapNotNull null // body 必填
        VisualCardItemData(
            body = body,
            bgImageSrc = item.stringProp("bgImageSrc"),
            bgPhoto = ((item as? OpenUiValue.Obj)?.v?.get("bgPhoto") as? OpenUiValue.Num)?.v?.toLong(),
            tag = item.stringProp("tag"),
        )
    }.take(MAX_BLOCK_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    OpenUiCardBlockShell(node, items, onAction, modifier) { item, itemModifier ->
        OpenUiVisualCardItem(item, photoResolver, itemModifier)
    }
}

@Composable
private fun OpenUiVisualCardItem(
    item: VisualCardItemData,
    photoResolver: OpenUiPhotoResolver,
    modifier: Modifier = Modifier,
) {
    // bgImageSrc 限 https（[PRIVACY]：Image src 同口径）；bgPhoto（2026-10-31）= 相册投影 id，
    // 端侧 id→uri 解析（uri 不出端），与 bgImageSrc 二选一、bgPhoto 优先；皆无/解析中 →
    // surfaceVariant 纯色底
    val httpsSrc = item.bgImageSrc?.takeIf { src -> src.startsWith("https://") }
    val resolvedPhoto by produceState<OpenUiPhoto?>(initialValue = null, item.bgPhoto) {
        value = item.bgPhoto?.let { id -> photoResolver.resolve(id) }
    }
    val photoUri = resolvedPhoto?.uri
    val photoFocusY = resolvedPhoto?.faceFocusY
    val hasImage = item.bgPhoto != null || httpsSrc != null
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(VISUAL_CARD_HEIGHT)
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (photoUri != null) {
            AsyncImage(
                model = photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = faceAwareVerticalAlignment(photoFocusY),
                modifier = Modifier.matchParentSize(),
            )
        } else if (item.bgPhoto == null && httpsSrc != null) {
            AsyncImage(
                model = httpsSrc,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (hasImage) {
            // 底部黑色渐变蒙层（文字可读性）：transparent → Black 0.7
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.7f),
                        )
                    )
            )
        }
        item.tag?.let { tag ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.sm)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            ) {
                Text(
                    text = tag,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = item.body,
            style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD).copy(fontWeight = FontWeight.Bold),
            // 有图（photo/https）：白字压渐变蒙层；纯色底：跟随主题 onSurface（浅底白字不可读）
            color = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.md),
        )
    }
}

// ── MetricIndicatorInline / MetricIndicatorWithStrikethrough ────────────

/**
 * 行内指标：value（displayStyle 大数字）+ subtext?（CAPTION 弱色）+ trend
 * （schema 扁平形态 trendDirection∈{up,down} + trendValue 数字；up→↑ success /
 * down→↓ error，未知方向不渲染箭头）。[withStrikethrough] 形态前置 previousValue?
 * 删除线（CAPTION 弱色）。value 必填缺失 → 丢组件。
 */
@Composable
private fun OpenUiMetricIndicator(
    node: OpenUiValue.ComponentVal,
    withStrikethrough: Boolean,
    modifier: Modifier = Modifier,
) {
    val value = (node.props["value"] as? OpenUiValue.Str)?.v
    if (value == null) {
        Box(modifier = modifier)
        return
    }
    val subtext = (node.props["subtext"] as? OpenUiValue.Str)?.v
    val previousValue = if (withStrikethrough) {
        (node.props["previousValue"] as? OpenUiValue.Str)?.v
    } else {
        null
    }
    val trendDirection = (node.props["trendDirection"] as? OpenUiValue.Str)?.v
    val trendValue = (node.props["trendValue"] as? OpenUiValue.Num)?.v
    val trendColor = when (trendDirection) {
        "up" -> StatusColor.success
        "down" -> StatusColor.error
        else -> null // 未知方向：不渲染箭头
    }
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        if (previousValue != null) {
            Text(
                text = previousValue,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().padding(end = Spacing.xs),
            )
        }
        Text(
            text = value,
            style = OpenUiCardTokens.displayStyle,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
        if (subtext != null) {
            Text(
                text = subtext,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline().padding(start = Spacing.xs),
            )
        }
        if (trendColor != null) {
            Text(
                text = buildString {
                    append(if (trendDirection == "up") "↑" else "↓")
                    trendValue?.let { number ->
                        append(" ")
                        append(openUiCardBlockNumberText(number))
                    }
                },
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = trendColor,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().padding(start = Spacing.xs),
            )
        }
    }
}

/** 趋势数值文本形态（整数不带小数点，OpenUiNodeRenderer openUiPrimitiveText 同口径）。 */
private fun openUiCardBlockNumberText(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
