package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.core.designsystem.BadgeTokens
import com.mamba.picme.core.designsystem.IconSize
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.core.designsystem.StatusColor
import com.mamba.picme.domain.chat.a2ui.A2uiTextRole
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.common.chat.AgentMarkdown

/**
 * OpenUI 官方组件库全量补全·内容/布局批次（M4a，2026-10-25，spec《openui-full-library-design》§3.1/§3.2）。
 *
 * 24 项 = 别名 3（Stack/Separator/MarkDownRenderer 渲染复用既有语义）+ 内容/布局 21
 * （CardHeader/InlineHeader/TextContent/TextCallout/CodeBlock/ImageBlock/ImageGallery/Tag/
 * TagBlock/Label/BoldText/IconText/ImageText/ImageTextLarge/EntityList/ListBlock/Steps/
 * Carousel/Buttons/IconButton/Modal）。样式 token 收口 [OpenUiCardTokens]（M4 口径）；
 * BINDABLE_* prop 从 state 快照解析（Binding 缺失/解析不出丢组件或只读展示，与 Text/
 * CheckBox 既有口径一致）；交互回写经 @Set(绑定变量, 值)；https 网络图 coil 渲染、非
 * https/加载中落 surfaceVariant 同形占位（半信任防御，不炸整卡）。dispatch 接线在
 * [OpenUiNodeRenderer] 统一收口，本文件不改动既有分支。
 */

/** 本批次负责的组件名集合（OpenUiNodeRenderer dispatch 接线依据）。 */
internal val openUiContentComponentNames: Set<String> = setOf(
    "Stack", "Separator", "MarkDownRenderer",
    "CardHeader", "InlineHeader", "TextContent", "TextCallout", "CodeBlock",
    "ImageBlock", "ImageGallery", "Tag", "TagBlock", "Label", "BoldText",
    "IconText", "ImageText", "ImageTextLarge", "EntityList", "ListBlock",
    "Steps", "Carousel", "Buttons", "IconButton", "Modal",
    // 经典组合版式批次（2026-10-31）：StatRow/StatItem/Banner 新增 + ListItem 实渲染化
    "StatRow", "StatItem", "Banner", "ListItem",
    // 相册 APP 组合方式批次（2026-10-31）：PhotoGrid/AvatarRow 新增
    "PhotoGrid", "AvatarRow",
)

/** 单组件数组/子项上限（MAX_LIST_CHILDREN=50 同口径：LLM 生成面无界，渲染端 take 兜底）。 */
private const val MAX_CONTENT_ITEMS = 50

private val CONTENT_BADGE_SIZE = 20.dp
private val CONTENT_ICON_BADGE_SIZE = 36.dp
private val CONTENT_LIST_IMAGE_SIZE = 48.dp

/** 内容/布局批次 dispatch：未知组件 → 空占位，不炸整卡（半信任输入防御既有口径）。 */
@Composable
internal fun OpenUiContentNode(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node.type) {
        "Stack" -> OpenUiContentStack(node, state, photoResolver, onAction, modifier)
        "Separator" -> OpenUiContentSeparator(node, modifier)
        "MarkDownRenderer" -> OpenUiContentMarkdownRenderer(node, state, modifier)
        "CardHeader" -> {
            val headerTitle = (node.props["title"] as? OpenUiValue.Str)?.v
            val headerActionLabel = (node.props["actionLabel"] as? OpenUiValue.Str)?.v
            OpenUiContentHeader(
                title = headerTitle,
                subtitle = (node.props["subtitle"] as? OpenUiValue.Str)?.v,
                modifier = modifier,
                // 2026-10-31 相册 APP 组合方式批次：分组标题尾部动作（「查看全部 >」形态）
                actionLabel = headerActionLabel,
                actions = headerActionsOrDefault(
                    headerActionLabel,
                    headerTitle,
                    contentActions(node.props["action"]),
                ),
                onAction = onAction,
            )
        }
        "InlineHeader" -> OpenUiContentHeader(
            title = (node.props["title"] as? OpenUiValue.Str)?.v,
            subtitle = (node.props["description"] as? OpenUiValue.Str)?.v,
            modifier = modifier,
        )
        "TextContent" -> OpenUiContentTextContent(node, state, modifier)
        "TextCallout" -> OpenUiContentTextCallout(node, modifier)
        "CodeBlock" -> OpenUiContentCodeBlock(node, modifier)
        "ImageBlock" -> OpenUiContentImageBlock(node, modifier)
        "ImageGallery" -> OpenUiContentImageGallery(node, modifier)
        "Tag" -> OpenUiContentTagNode(node, modifier)
        "TagBlock" -> OpenUiContentTagBlock(node, modifier)
        "Label" -> {
            val text = (node.props["text"] as? OpenUiValue.Str)?.v ?: return
            Text(
                text = text,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        }
        "BoldText" -> OpenUiContentBoldText(node, state, modifier)
        "IconText" -> OpenUiContentIconText(node, modifier)
        "ImageText" -> OpenUiContentImageText(node, photoResolver, onAction, modifier)
        "ImageTextLarge" -> OpenUiContentImageTextLarge(node, photoResolver, onAction, modifier)
        "EntityList" -> OpenUiContentEntityList(node, modifier)
        "ListBlock" -> OpenUiContentListBlock(node, onAction, modifier)
        "Steps" -> OpenUiContentSteps(node, modifier)
        "Carousel" -> OpenUiContentCarousel(node, state, photoResolver, onAction, modifier)
        "Buttons" -> OpenUiContentButtons(node, state, photoResolver, onAction, modifier)
        "IconButton" -> OpenUiContentIconButton(node, onAction, modifier)
        "Modal" -> OpenUiContentModal(node, state, photoResolver, onAction, modifier)
        // ── 经典组合版式批次（2026-10-31）──
        "StatRow" -> OpenUiContentStatRow(node, modifier)
        "StatItem" -> {
            // 元素形状保底：独立出现按单项统计渲染（缺 value 丢组件同 StatRow item 口径）
            val value = (node.props["value"] as? OpenUiValue.Str)?.v
            if (value == null) {
                Box(modifier = modifier)
            } else {
                OpenUiContentStatCell(
                    value = value,
                    label = (node.props["label"] as? OpenUiValue.Str)?.v,
                    modifier = modifier,
                )
            }
        }
        "Banner" -> OpenUiContentBanner(node, photoResolver, onAction, modifier)
        "ListItem" -> OpenUiContentListItem(node, photoResolver, onAction, modifier)
        // ── 相册 APP 组合方式批次（2026-10-31）──
        "PhotoGrid" -> OpenUiContentPhotoGrid(node, photoResolver, onAction, modifier)
        "AvatarRow" -> OpenUiContentAvatarRow(node, photoResolver, onAction, modifier)
        else -> Box(modifier = modifier)
    }
}

// ── 共用解析工具 ─────────────────────────────────────────────────────

/** BINDABLE_STRING 解析（Text 分支同构）：Str 直取；Binding 从 state 快照读（Num/Bool 回退文本形态）。 */
private fun resolveContentBindableText(prop: OpenUiValue?, state: Map<String, OpenUiValue>): String? =
    when (prop) {
        is OpenUiValue.Str -> prop.v
        is OpenUiValue.Binding -> state[prop.name]?.let { bound -> openUiContentPrimitiveText(bound) }
        else -> null
    }

/** 原始值的文本形态（Num 整数不带小数点；OpenUiNodeRenderer.openUiPrimitiveText 同口径私有副本）。 */
private fun openUiContentPrimitiveText(value: OpenUiValue): String? = when (value) {
    is OpenUiValue.Str -> value.v
    is OpenUiValue.Num -> if (value.v % 1.0 == 0.0) value.v.toLong().toString() else value.v.toString()
    is OpenUiValue.Bool -> value.v.toString()
    else -> null
}

/** 空绑定兜底（OpenUiNodeRenderer.isEmptyBindingFallback 同口径私有副本）：冒号尾前缀 → 丢组件。 */
private fun isEmptyBindingFallback(resolved: String): Boolean {
    val trimmed = resolved.trim()
    return trimmed.endsWith(":") || trimmed.endsWith("：")
}

/** https 公网图白名单（[PRIVACY]：server validator 正则硬校验，渲染层仍防御；非 https → null 落占位）。 */
private fun httpsSrcOrNull(raw: String?): String? =
    raw?.takeIf { src -> src.startsWith("https://") }

/** variant 小写（neutral/info/success/warning/danger）→ StatusColor；neutral/缺省 → 弱色。 */
@Composable
private fun contentToneColor(variant: String?): Color = when (variant) {
    "info" -> StatusColor.info
    "success" -> StatusColor.success
    "warning" -> StatusColor.warning
    "danger" -> StatusColor.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun contentToneIcon(variant: String?): String = when (variant) {
    "success" -> "check"
    "warning" -> "warning"
    "danger" -> "error"
    else -> "info"
}

/** ACTION prop → 动作列表（单 ActionVal 或数组形态都打平，主文件 openUiActionsOf 同径）。 */
private fun contentActions(prop: OpenUiValue?): List<OpenUiAction> = openUiActionsOf(prop)

/**
 * CardHeader 尾部动作兜底（2026-10-31 真机回归修复「查看全部点击无效」）：LLM 按黄金
 * 示例常只传 actionLabel 不传 action，若原样渲染则按钮呈可点态却无动作。actionLabel 在
 * 场且 action 缺省时，合成 @ToAssistant("actionLabel：title")——点击即以用户消息上行，
 * 让 LLM 展开完整分组；显式 action 永远优先（OpenUiHeaderActionTest 钉住）。
 */
internal fun headerActionsOrDefault(
    actionLabel: String?,
    title: String?,
    actions: List<OpenUiAction>,
): List<OpenUiAction> {
    if (actions.isNotEmpty() || actionLabel == null) return actions
    val text = if (title != null) "$actionLabel：$title" else actionLabel
    return listOf(OpenUiAction("ToAssistant", listOf(OpenUiValue.Str(text))))
}

/** 同形占位（https 图缺失/非法时防跳动，Image 解析失败占位同口径）。 */
@Composable
private fun OpenUiContentImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** https 网络图：coil AsyncImage + Crop + 12dp 圆角 + surfaceVariant 底；非法 src → 同形占位不炸。 */
@Composable
private fun OpenUiContentNetworkImage(
    src: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val valid = httpsSrcOrNull(src)
    val frame = modifier
        .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (valid == null) {
        Box(modifier = frame)
    } else {
        AsyncImage(
            model = valid,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = frame,
        )
    }
}

/**
 * 相册投影图（2026-10-30 图文组合固化：ImageText/ImageTextLarge 的 photo 键，与 Image
 * 组件同径）——端侧 id→uri（[PRIVACY] uri 不出端）+ 人脸聚焦对齐 + 点击 OpenPhoto 大图；
 * 解析中/解析失败（已删除/未索引）→ 同形 surfaceVariant 占位，不炸整卡。
 */
@Composable
private fun OpenUiContentPhotoImage(
    photoId: Long?,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo by produceState<OpenUiPhoto?>(initialValue = null, photoId) {
        value = photoId?.let { id -> photoResolver.resolve(id) }
    }
    val frame = modifier
        .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
        .background(MaterialTheme.colorScheme.surfaceVariant)
    val resolved = photo
    if (resolved == null) {
        Box(modifier = frame)
    } else {
        AsyncImage(
            model = resolved.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = faceAwareVerticalAlignment(resolved.faceFocusY),
            modifier = frame.clickable(
                // 点击看大图：宿主动作通道（OpenUrl 先例），ChatScreen 落 chat 内预览浮层
                onClickLabel = stringResource(R.string.cd_image_preview),
                role = Role.Image,
            ) {
                onAction(OpenUiAction("OpenPhoto", listOf(OpenUiValue.Str(resolved.uri))))
            },
        )
    }
}

/**
 * 2026-10-31 图片比例治理（布局时帧随图片真实比例）：通栏图决策链全组件统一为
 * 显式 aspect prop > 图片真实比例（相册元数据 / 网络图 intrinsic，加载成功采集、
 * 单帧过渡）> 组件缺省框（[fallbackAspect]）。帧=真实比例时 Crop 退化为无损铺满，
 * 竖图不再被缺省横框拦腰裁；解析中/加载中按缺省框占位（同形防跳动既有口径）。
 */

/** 相册投影自适应通栏图：帧 = frameAspectOf(propAspect, 元数据真实比例, fallback)。 */
@Composable
private fun OpenUiAdaptivePhotoImage(
    photoId: Long,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    propAspect: Double?,
    fallbackAspect: Float,
    modifier: Modifier = Modifier,
) {
    val photo by produceState<OpenUiPhoto?>(initialValue = null, photoId) {
        value = photoResolver.resolve(photoId)
    }
    val resolved = photo
    val frame = modifier
        .fillMaxWidth()
        .aspectRatio(frameAspectOf(propAspect, resolved?.aspectRatio, fallbackAspect.toDouble()).toFloat())
        .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (resolved == null) {
        Box(modifier = frame)
    } else {
        AsyncImage(
            model = resolved.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = faceAwareVerticalAlignment(resolved.faceFocusY),
            modifier = frame.clickable(
                onClickLabel = stringResource(R.string.cd_image_preview),
                role = Role.Image,
            ) {
                onAction(OpenUiAction("OpenPhoto", listOf(OpenUiValue.Str(resolved.uri))))
            },
        )
    }
}

/** 网络图自适应通栏帧：coil onSuccess 采 intrinsic 真实比例（remember 键=src，换图重置）。 */
@Composable
private fun OpenUiAdaptiveNetworkImage(
    src: String?,
    contentDescription: String?,
    propAspect: Double?,
    fallbackAspect: Float,
    modifier: Modifier = Modifier,
) {
    val valid = httpsSrcOrNull(src)
    var srcRatio by remember(valid) { mutableStateOf<Float?>(null) }
    val frame = modifier
        .fillMaxWidth()
        .aspectRatio(frameAspectOf(propAspect, srcRatio, fallbackAspect.toDouble()).toFloat())
        .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (valid == null) {
        Box(modifier = frame)
    } else {
        AsyncImage(
            model = valid,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onSuccess = { successState ->
                intrinsicAspectOf(successState.painter.intrinsicSize)?.let { srcRatio = it }
            },
            modifier = frame,
        )
    }
}

/** 序号圆点徽标（ListBlock number / Steps 共用：primary 底白字 12sp）。 */
@Composable
private fun OpenUiContentNumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(CONTENT_BADGE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

// ── 别名 3 ──────────────────────────────────────────────────────────

/**
 * Stack：direction=row→Row / 缺省 column→Column；wrap=true→FlowRow/FlowColumn；
 * gap 六档（none/xs/s/m/l/xl/2xl）映射既有 Spacing 阶梯；align→交叉轴、justify→主轴排布。
 * 2026-10-30 官方语义对齐：缺省 stretch（官方 CSS 缺省）——row 向 stretch 子项
 * fillMaxHeight、column 向 stretch 子项 fillMaxWidth；baseline row 向子项
 * alignByBaseline（column 向无对应概念兜底 start，文档化偏差）；
 * wrap=true（Flow*）不支持交叉轴修饰符，stretch/baseline 退化为容器基线。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenUiContentStack(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_CONTENT_ITEMS)
    val isRow = (node.props["direction"] as? OpenUiValue.Str)?.v == "row"
    val gap = stackGapDp((node.props["gap"] as? OpenUiValue.Str)?.v)
    val wrap = (node.props["wrap"] as? OpenUiValue.Bool)?.v ?: false
    val align = (node.props["align"] as? OpenUiValue.Str)?.v
    val justify = (node.props["justify"] as? OpenUiValue.Str)?.v
    if (isRow) {
        val crossAxis = when (align) {
            "center" -> Alignment.CenterVertically
            "end" -> Alignment.Bottom
            else -> Alignment.Top // start/stretch/baseline：stretch/baseline 由子项修饰符承担
        }
        val mainAxis = rowMainAxis(justify, gap)
        if (wrap) {
            FlowRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                OpenUiContentChildren(children, state, photoResolver, onAction)
            }
        } else {
            Row(
                modifier = modifier,
                horizontalArrangement = mainAxis,
                verticalAlignment = crossAxis,
            ) {
                OpenUiContentChildren(
                    children, state, photoResolver, onAction,
                    childModifier = when (align) {
                        "stretch" -> Modifier.fillMaxHeight()
                        "baseline" -> Modifier.alignByBaseline()
                        else -> Modifier
                    },
                )
            }
        }
    } else {
        val crossAxis = when (align) {
            "center" -> Alignment.CenterHorizontally
            "end" -> Alignment.End
            else -> Alignment.Start // start/stretch/baseline：stretch 由子项 fillMaxWidth 承担
        }
        val mainAxis = columnMainAxis(justify, gap)
        if (wrap) {
            FlowColumn(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                OpenUiContentChildren(children, state, photoResolver, onAction)
            }
        } else {
            Column(
                modifier = modifier,
                verticalArrangement = mainAxis,
                horizontalAlignment = crossAxis,
            ) {
                OpenUiContentChildren(
                    children, state, photoResolver, onAction,
                    childModifier = if (align == "stretch") Modifier.fillMaxWidth() else Modifier,
                )
            }
        }
    }
}

/** Stack.gap 枚举 → Spacing 阶梯（最小档 xs；2xl 超阶梯取 32dp）。 */
private fun stackGapDp(raw: String?): Dp = when (raw) {
    "none" -> 0.dp
    "xs" -> Spacing.xs
    "s" -> Spacing.sm
    "l" -> Spacing.lg
    "xl" -> Spacing.xl
    "2xl" -> 32.dp
    else -> Spacing.md // m / 缺省
}

private fun rowMainAxis(justify: String?, gap: Dp): Arrangement.Horizontal = when (justify) {
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap, Alignment.End)
    else -> Arrangement.spacedBy(gap)
}

private fun columnMainAxis(justify: String?, gap: Dp): Arrangement.Vertical = when (justify) {
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
    else -> Arrangement.spacedBy(gap)
}

/** 子组件递归渲染（null 字面量/被剔除元素空占位，OpenUiChildList 同口径）。 */
@Composable
private fun OpenUiContentChildren(
    children: List<OpenUiValue>,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    // 2026-10-30 官方语义对齐：Stack stretch/baseline 由父容器传入的交叉轴子项修饰符
    childModifier: Modifier = Modifier,
) {
    for (child in children) {
        if (child is OpenUiValue.ComponentVal) {
            OpenUiNodeRenderer(child, state, photoResolver, onAction, childModifier)
        } else {
            Box(modifier = Modifier)
        }
    }
}

/** Separator：orientation=vertical→VerticalDivider / 缺省 horizontal→HorizontalDivider（0.5dp 弱色线，M4 token）。 */
@Composable
private fun OpenUiContentSeparator(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = OpenUiCardTokens.dividerAlpha)
    if ((node.props["orientation"] as? OpenUiValue.Str)?.v == "vertical") {
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

/** MarkDownRenderer：textMarkdown BINDABLE_STRING（Markdown 同口径）；variant 收而不用。 */
@Composable
private fun OpenUiContentMarkdownRenderer(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    modifier: Modifier = Modifier,
) {
    val text = resolveContentBindableText(node.props["textMarkdown"], state) ?: return
    if (isEmptyBindingFallback(text)) return
    AgentMarkdown(
        content = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = modifier.fillMaxWidth(),
    )
}

// ── 内容/布局 21 ─────────────────────────────────────────────────────

/** CardHeader/InlineHeader 同渲染（官方语义即轻量标题对）：SUBHEAD 标题 + CAPTION 弱色副题。 */
@Composable
private fun OpenUiContentHeader(
    title: String?,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actions: List<OpenUiAction> = emptyList(),
    onAction: (OpenUiAction) -> Unit = {},
) {
    if (title == null) return
    // 2026-10-31 相册 APP 组合方式批次：actionLabel 在场 → 分组标题行（相册 APP 日期
    // 分组头「标题 + 查看全部 >」签名形态）；action 缺省由 headerActionsOrDefault 兜底
    // 合成 @ToAssistant 上行（点击即回话展开分组，不再渲染无效按钮）
    if (actionLabel == null) {
        Column(modifier = modifier) {
            Text(text = title, style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD))
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD))
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = actionLabel,
            style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(
                enabled = actions.isNotEmpty(),
                onClickLabel = actionLabel,
                role = Role.Button,
            ) {
                actions.forEach(onAction)
            },
        )
    }
}

/** TextContent 字阶映射（spec §3.2）：small→CAPTION、default→BODY、large/small-heavy→SUBHEAD、large-heavy→HEADLINE。 */
private fun textContentRole(raw: String?): A2uiTextRole = when (raw) {
    "small" -> A2uiTextRole.CAPTION
    "large", "small-heavy" -> A2uiTextRole.SUBHEAD
    "large-heavy" -> A2uiTextRole.HEADLINE
    else -> A2uiTextRole.BODY
}

/** TextContent：text BINDABLE_STRING + size→字阶（绑定解析不出/冒号尾丢组件，Text 同口径）。 */
@Composable
private fun OpenUiContentTextContent(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    modifier: Modifier = Modifier,
) {
    val text = resolveContentBindableText(node.props["text"], state) ?: return
    if (isEmptyBindingFallback(text)) return
    val role = textContentRole((node.props["size"] as? OpenUiValue.Str)?.v)
    Text(
        text = text,
        style = OpenUiCardTokens.textStyle(role),
        color = if (role == A2uiTextRole.CAPTION) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier,
    )
}

/** TextCallout：variant 小写映射 tone（danger→ERROR 红、neutral→弱色），渲染镜像 OpenUiCallout 模式。 */
@Composable
private fun OpenUiContentTextCallout(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v ?: return
    val description = (node.props["description"] as? OpenUiValue.Str)?.v
    val variant = (node.props["variant"] as? OpenUiValue.Str)?.v
    val toneColor = contentToneColor(variant)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(toneColor.copy(alpha = BadgeTokens.tagBackgroundAlpha))
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        openUiIcon(contentToneIcon(variant))?.let { image ->
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = toneColor,
                modifier = Modifier.size(IconSize.md),
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = toneColor,
            )
            description?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** CodeBlock：等宽字体 surfaceVariant 块 + 横滑；language 作弱色小字标注（LLM 内容串，零新增文案）。 */
@Composable
private fun OpenUiContentCodeBlock(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val code = (node.props["codeString"] as? OpenUiValue.Str)?.v ?: return
    val language = (node.props["language"] as? OpenUiValue.Str)?.v
    Column(modifier = modifier.fillMaxWidth()) {
        language?.let { lang ->
            Text(
                text = lang,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShapes.card)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.md),
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** ImageBlock：官方纯 src 形态（https 限）；2026-10-31 比例治理——帧随 intrinsic 真实比例，缺省 4:3 框。 */
@Composable
private fun OpenUiContentImageBlock(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val src = (node.props["src"] as? OpenUiValue.Str)?.v
    val alt = (node.props["alt"] as? OpenUiValue.Str)?.v
    OpenUiAdaptiveNetworkImage(
        src = src,
        contentDescription = alt,
        propAspect = null,
        fallbackAspect = 4f / 3f,
        modifier = modifier,
    )
}

/** ImageGallery 条目高（2026-10-31 比例治理：定高变宽经典画廊形态）：= 原条目宽 × 3/4（与原 4:3 条目视觉等高）。 */
private val GALLERY_IMAGE_HEIGHT = OpenUiCardTokens.horizontalImageWidth * 0.75f

/** ImageGallery：横滑定高变宽条目——各图帧随自身 intrinsic 真实比例（加载后采集，
 *  sanity 界 [0.3,3.0]，缺省 4:3 占位），不再统一 4:3 裁切；https 图仅展示不做大图页；
 *  details 作弱色小字注脚。 */
@Composable
private fun OpenUiContentImageGallery(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val images = (node.props["images"] as? OpenUiValue.Arr)?.v.orEmpty()
        .mapNotNull { item -> (item as? OpenUiValue.Obj)?.v }
        .take(MAX_CONTENT_ITEMS)
    if (images.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        images.forEach { obj ->
            val src = (obj["src"] as? OpenUiValue.Str)?.v
            val alt = (obj["alt"] as? OpenUiValue.Str)?.v
            val details = (obj["details"] as? OpenUiValue.Str)?.v
            val valid = httpsSrcOrNull(src)
            var srcRatio by remember(valid) { mutableStateOf<Float?>(null) }
            val frameAspect = frameAspectOf(null, srcRatio).toFloat()
            Column(modifier = Modifier.width(GALLERY_IMAGE_HEIGHT * frameAspect)) {
                val frame = Modifier
                    .fillMaxWidth()
                    .height(GALLERY_IMAGE_HEIGHT)
                    .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                if (valid == null) {
                    Box(modifier = frame)
                } else {
                    AsyncImage(
                        model = valid,
                        contentDescription = alt,
                        contentScale = ContentScale.Crop,
                        onSuccess = { successState ->
                            intrinsicAspectOf(successState.painter.intrinsicSize)?.let { srcRatio = it }
                        },
                        modifier = frame,
                    )
                }
                details?.let { text ->
                    Text(
                        text = text,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }
}

/** Tag 节点：小胶囊（tonal 圆角 50% + 内边距），variant→tone 色，icon 可选（12-16dp 随 size）。 */
@Composable
private fun OpenUiContentTagNode(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val text = (node.props["text"] as? OpenUiValue.Str)?.v ?: return
    val icon = (node.props["icon"] as? OpenUiValue.Str)?.v
    val size = (node.props["size"] as? OpenUiValue.Str)?.v
    val variant = (node.props["variant"] as? OpenUiValue.Str)?.v
    OpenUiContentTagChip(text = text, icon = icon, size = size, variant = variant, modifier = modifier)
}

/** Tag 胶囊本体（Tag / TagBlock 共用）。 */
@Composable
private fun OpenUiContentTagChip(
    text: String,
    icon: String?,
    size: String?,
    variant: String?,
    modifier: Modifier = Modifier,
) {
    val toneColor = contentToneColor(variant)
    val iconSize = when (size) {
        "sm" -> 12.dp
        "lg" -> 16.dp
        else -> 14.dp
    }
    val textRole = when (size) {
        "sm" -> A2uiTextRole.CAPTION
        "lg" -> A2uiTextRole.SUBHEAD
        else -> A2uiTextRole.BODY
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(toneColor.copy(alpha = BadgeTokens.tagBackgroundAlpha))
            .padding(horizontal = BadgeTokens.tagPaddingH, vertical = BadgeTokens.tagPaddingV),
        horizontalArrangement = Arrangement.spacedBy(BadgeTokens.tagDotLabelGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { name -> openUiIcon(name) }?.let { image ->
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = toneColor,
                modifier = Modifier.size(iconSize),
            )
        }
        Text(
            text = text,
            style = OpenUiCardTokens.textStyle(textRole),
            color = toneColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** TagBlock：字符串数组 FlowRow 平铺（元素取文本形态，非串元素剔除）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenUiContentTagBlock(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val tags = (node.props["tags"] as? OpenUiValue.Arr)?.v.orEmpty()
        .mapNotNull { value -> openUiContentPrimitiveText(value) }
        .take(MAX_CONTENT_ITEMS)
    if (tags.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tags.forEach { tag ->
            OpenUiContentTagChip(text = tag, icon = null, size = "md", variant = "neutral")
        }
    }
}

/** BoldText：value BINDABLE_STRING + Bold（官方卡块构件，本仓允许任意位置）；subtext 弱色小字。 */
@Composable
private fun OpenUiContentBoldText(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    modifier: Modifier = Modifier,
) {
    val value = resolveContentBindableText(node.props["value"], state) ?: return
    if (isEmptyBindingFallback(value)) return
    val subtext = (node.props["subtext"] as? OpenUiValue.Str)?.v
    val role = when ((node.props["size"] as? OpenUiValue.Str)?.v) {
        "xs" -> A2uiTextRole.CAPTION
        "md" -> A2uiTextRole.SUBHEAD
        "lg" -> A2uiTextRole.HEADLINE
        else -> A2uiTextRole.BODY
    }
    Column(modifier = modifier) {
        Text(
            text = value,
            style = OpenUiCardTokens.textStyle(role).copy(fontWeight = FontWeight.Bold),
        )
        subtext?.let { text ->
            Text(
                text = text,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** IconText：Row(icon 徽章圆底 + Column(title BODY + subtitle CAPTION 弱色))；layout=vertical→Column 居中。 */
@Composable
private fun OpenUiContentIconText(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v ?: return
    val subtitle = (node.props["subtitle"] as? OpenUiValue.Str)?.v
    val icon = (node.props["icon"] as? OpenUiValue.Str)?.v?.let { name -> openUiIcon(name) }
    val bold = (node.props["bold"] as? OpenUiValue.Bool)?.v ?: false
    val vertical = (node.props["layout"] as? OpenUiValue.Str)?.v == "vertical"
    val badge: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(CONTENT_ICON_BADGE_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            icon?.let { image ->
                Icon(
                    imageVector = image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        }
    }
    val texts: @Composable () -> Unit = {
        Text(
            text = title,
            style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
            fontWeight = if (bold) FontWeight.Bold else null,
        )
        subtitle?.let { text ->
            Text(
                text = text,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (vertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            badge()
            Column(horizontalAlignment = Alignment.CenterHorizontally) { texts() }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            badge()
            Column { texts() }
        }
    }
}

/** ImageText：Row(48dp 圆角图（imageSize 可覆盖，钳 24..96）+ 标题/副题列)；图双源——photo 相册投影优先，src https 兜底。 */
@Composable
private fun OpenUiContentImageText(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v ?: return
    val src = (node.props["src"] as? OpenUiValue.Str)?.v
    val subtitle = (node.props["subtitle"] as? OpenUiValue.Str)?.v
    val photoId = (node.props["photo"] as? OpenUiValue.Num)?.v?.toLong()
    val imageSize = ((node.props["imageSize"] as? OpenUiValue.Num)?.v ?: 48.0)
        .coerceIn(24.0, 96.0).dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photoId != null) {
            OpenUiContentPhotoImage(
                photoId = photoId,
                photoResolver = photoResolver,
                onAction = onAction,
                modifier = Modifier.size(imageSize),
            )
        } else {
            OpenUiContentNetworkImage(
                src = src,
                contentDescription = title,
                modifier = Modifier.size(imageSize),
            )
        }
        Column {
            Text(text = title, style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY))
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ImageTextLarge：Column(通栏图 + 粗标题 + 副题)；图双源——photo 相册投影优先，src https
 *  兜底、缺失落同形占位；2026-10-31 比例治理——帧随图片真实比例（元数据/intrinsic），缺省 16:9。 */
@Composable
private fun OpenUiContentImageTextLarge(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v ?: return
    val src = (node.props["src"] as? OpenUiValue.Str)?.v
    val subtitle = (node.props["subtitle"] as? OpenUiValue.Str)?.v
    val photoId = (node.props["photo"] as? OpenUiValue.Num)?.v?.toLong()
    Column(modifier = modifier.fillMaxWidth()) {
        if (photoId != null) {
            OpenUiAdaptivePhotoImage(
                photoId = photoId,
                photoResolver = photoResolver,
                onAction = onAction,
                propAspect = null,
                fallbackAspect = 16f / 9f,
            )
        } else {
            OpenUiAdaptiveNetworkImage(
                src = src,
                contentDescription = title,
                propAspect = null,
                fallbackAspect = 16f / 9f,
            )
        }
        Text(
            text = title,
            style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        subtitle?.let { text ->
            Text(
                text = text,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** EntityList：header（SUBHEAD/弱色）+ 双列 key/value 行（left BODY / right BODY 弱）+ footer（CAPTION 弱）。 */
@Composable
private fun OpenUiContentEntityList(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val rows = (node.props["rows"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val left = (obj["left"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        val right = (obj["right"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        left to right
    }.take(MAX_CONTENT_ITEMS)
    if (rows.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val headerLeft = (node.props["headerLeft"] as? OpenUiValue.Str)?.v
    val headerRight = (node.props["headerRight"] as? OpenUiValue.Str)?.v
    val footerLeft = (node.props["footerLeft"] as? OpenUiValue.Str)?.v
    val footerRight = (node.props["footerRight"] as? OpenUiValue.Str)?.v
    Column(modifier = modifier.fillMaxWidth()) {
        if (headerLeft != null || headerRight != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                headerLeft?.let { text ->
                    Text(text = text, style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD))
                }
                headerRight?.let { text ->
                    Text(
                        text = text,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        rows.forEach { (left, right) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = left, style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY))
                Text(
                    text = right,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (footerLeft != null || footerRight != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                footerLeft?.let { text ->
                    Text(
                        text = text,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                footerRight?.let { text ->
                    Text(
                        text = text,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class ContentListItem(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val actionLabel: String?,
    val actions: List<OpenUiAction>,
)

/** ListBlock：variant=number→序号圆点 / =image→48dp https 缩略图；action 存在行可点，actionLabel 尾部胶囊。 */
@Composable
private fun OpenUiContentListBlock(
    node: OpenUiValue.ComponentVal,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val title = (obj["title"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        ContentListItem(
            title = title,
            subtitle = (obj["subtitle"] as? OpenUiValue.Str)?.v,
            image = (obj["image"] as? OpenUiValue.Str)?.v,
            actionLabel = (obj["actionLabel"] as? OpenUiValue.Str)?.v,
            actions = contentActions(obj["action"]),
        )
    }.take(MAX_CONTENT_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val imageVariant = (node.props["variant"] as? OpenUiValue.Str)?.v == "image"
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (item.actions.isNotEmpty()) {
                            Modifier.clickable { item.actions.forEach(onAction) }
                        } else {
                            Modifier
                        }
                    ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (imageVariant) {
                    OpenUiContentNetworkImage(
                        src = item.image,
                        contentDescription = item.title,
                        modifier = Modifier.size(CONTENT_LIST_IMAGE_SIZE),
                    )
                } else {
                    OpenUiContentNumberBadge(index + 1)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.let { text ->
                        Text(
                            text = text,
                            style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item.actionLabel?.let { label ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary
                                    .copy(alpha = BadgeTokens.tagBackgroundAlpha)
                            )
                            .padding(
                                horizontal = BadgeTokens.tagPaddingH,
                                vertical = BadgeTokens.tagPaddingV,
                            ),
                    ) {
                        Text(
                            text = label,
                            style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Steps：纵向步骤条——序号圆点（primary 底白字，从 1 起）+ 标题/details；行间左侧 1dp 弱色连接线。 */
@Composable
private fun OpenUiContentSteps(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val title = (obj["title"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        title to (obj["details"] as? OpenUiValue.Str)?.v
    }.take(MAX_CONTENT_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = OpenUiCardTokens.dividerAlpha)
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, (title, details) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .width(OpenUiCardTokens.dividerThickness)
                                .height(Spacing.sm)
                                .background(lineColor)
                        )
                    }
                    OpenUiContentNumberBadge(index + 1)
                    if (index < items.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(OpenUiCardTokens.dividerThickness)
                                .height(Spacing.sm)
                                .background(lineColor)
                        )
                    }
                }
                Column(modifier = Modifier.padding(bottom = Spacing.sm)) {
                    Text(text = title, style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY))
                    details?.let { text ->
                        Text(
                            text = text,
                            style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Carousel：横滑定宽条目（horizontalImageWidth，List HORIZONTAL 同径），子组件递归渲染；variant 收而不用。 */
@Composable
private fun OpenUiContentCarousel(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_CONTENT_ITEMS)
    if (children.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        for (child in children) {
            if (child is OpenUiValue.ComponentVal) {
                Box(modifier = Modifier.width(OpenUiCardTokens.horizontalImageWidth)) {
                    OpenUiNodeRenderer(child, state, photoResolver, onAction)
                }
            }
        }
    }
}

/** Buttons：buttons 组件数组平铺（direction=row 默认 Row 8dp 间距 / column→Column），递归渲染。 */
@Composable
private fun OpenUiContentButtons(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttons = (node.props["buttons"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_CONTENT_ITEMS)
    if (buttons.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    if ((node.props["direction"] as? OpenUiValue.Str)?.v == "column") {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OpenUiContentChildren(buttons, state, photoResolver, onAction)
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OpenUiContentChildren(buttons, state, photoResolver, onAction)
        }
    }
}

/** IconButton：M3 IconButton 包 surfaceVariant 圆底；icon 走 59 枚举（解析不到丢组件）；action 逐个喂 onAction。 */
@Composable
private fun OpenUiContentIconButton(
    node: OpenUiValue.ComponentVal,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = (node.props["name"] as? OpenUiValue.Str)?.v ?: return
    val image = (node.props["icon"] as? OpenUiValue.Str)?.v?.let { icon -> openUiIcon(icon) } ?: return
    val actions = contentActions(node.props["action"])
    IconButton(
        onClick = { actions.forEach(onAction) },
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
    ) {
        Icon(
            imageVector = image,
            contentDescription = name,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.md),
        )
    }
}

/**
 * Modal：open BINDABLE_BOOLEAN——Binding 从 state 读 Bool，真值时弹 Dialog（标题 + children + 关闭行）；
 * X/遮罩关闭 → @Set(绑定变量, false)；裸字面量 true（LLM 未按约定传绑定，review 🔴4）→ 本地
 * remember 态兜底关闭，不渲染永久死弹窗；open=false/解析不出 → 零尺寸 Box。
 */
@Composable
private fun OpenUiContentModal(
    node: OpenUiValue.ComponentVal,
    state: Map<String, OpenUiValue>,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v ?: return
    val openProp = node.props["open"]
    val bindingName = (openProp as? OpenUiValue.Binding)?.name
    val boundOpen = when (openProp) {
        is OpenUiValue.Binding -> (state[openProp.name] as? OpenUiValue.Bool)?.v
        is OpenUiValue.Bool -> openProp.v
        else -> null
    } ?: false
    // 无绑定的字面量 true：关闭态只进本地 remember（写不进 state）；有绑定时本地态恒 false 不参与
    var locallyDismissed by remember(node) { mutableStateOf(false) }
    val open = boundOpen && (bindingName != null || !locallyDismissed)
    if (!open) {
        Box(modifier = modifier)
        return
    }
    val children = (node.props["children"] as? OpenUiValue.Arr)?.v.orEmpty().take(MAX_CONTENT_ITEMS)
    val close: () -> Unit = {
        if (bindingName != null) {
            onAction(
                OpenUiAction(
                    name = "Set",
                    args = listOf(OpenUiValue.Bool(false)),
                    target = bindingName,
                )
            )
        } else {
            locallyDismissed = true
        }
    }
    Dialog(onDismissRequest = close) {
        Card(shape = AppShapes.card) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = close, modifier = Modifier.size(IconSize.lg)) {
                        openUiIcon("close")?.let { image ->
                            Icon(
                                imageVector = image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(IconSize.sm),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(top = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    OpenUiContentChildren(children, state, photoResolver, onAction)
                }
            }
        }
    }
}

// ── 经典组合版式批次（2026-10-31，spec《2026-10-31-openui-classic-layouts-design》）────
// StatRow/Banner 新增 + ListItem 实渲染化：原语之上的固化经典构图，LLM 一行调用即得
// 正确版式（stat tile 构图不再靠 Row+Column+Text(DISPLAY) 拼装纪律）。

/** 单项统计格（StatRow 项 / StatItem 独立保底共用）：DISPLAY 大数字 + CAPTION 弱色标签居中。 */
@Composable
private fun OpenUiContentStatCell(value: String, label: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = OpenUiCardTokens.displayStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        label?.let { text ->
            Text(
                text = text,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** StatRow：经典统计行——2~4 项等分（weight 1f），item 缺 value 剔除、全丢 → 空占位。 */
@Composable
private fun OpenUiContentStatRow(node: OpenUiValue.ComponentVal, modifier: Modifier = Modifier) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val value = (obj["value"] as? OpenUiValue.Str)?.v ?: return@mapNotNull null
        value to (obj["label"] as? OpenUiValue.Str)?.v
    }.take(MAX_CONTENT_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    Row(modifier = modifier.fillMaxWidth()) {
        items.forEach { (value, label) ->
            OpenUiContentStatCell(value = value, label = label, modifier = Modifier.weight(1f))
        }
    }
}

/** Banner 缺省宽高比 16:9（显式 aspect 钳 [0.5, 2.5]，Image PROP_ASPECT 同口径）。 */
private const val BANNER_FALLBACK_ASPECT = 16f / 9f

/**
 * Banner：通栏题图横幅——图（photo 相册投影优先 / src https，二选一）+ 底部黑色渐变
 * 蒙层 + 左下白字标题/副题（VisualCardItem 有图形态的单卡版）；photo/src 皆无 →
 * surfaceVariant 纯色底 + 主题色文字（VisualCardItem 无图形态同口径）；photo 图点击
 * 看大图走 OpenPhoto 宿主动作（OpenUiContentPhotoImage 既有口径）。
 * 2026-10-31 比例治理：帧决策链 = 显式 aspect > 图片真实比例（相册元数据 / 网络图
 * intrinsic 加载后采集）> 16:9 缺省——竖构图题图不再被 16:9 横框拦腰裁。
 */
@Composable
private fun OpenUiContentBanner(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v ?: return
    val subtitle = (node.props["subtitle"] as? OpenUiValue.Str)?.v
    val photoId = (node.props["photo"] as? OpenUiValue.Num)?.v?.toLong()
    val httpsSrc = httpsSrcOrNull((node.props["src"] as? OpenUiValue.Str)?.v)
    val propAspect = (node.props["aspect"] as? OpenUiValue.Num)?.v
    val photo by produceState<OpenUiPhoto?>(initialValue = null, photoId) {
        value = photoId?.let { id -> photoResolver.resolve(id) }
    }
    var srcRatio by remember(httpsSrc) { mutableStateOf<Float?>(null) }
    val aspect = frameAspectOf(
        propAspect,
        photo?.aspectRatio ?: srcRatio,
        BANNER_FALLBACK_ASPECT.toDouble(),
    ).toFloat()
    val hasImage = photoId != null || httpsSrc != null
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val resolved = photo
        if (resolved != null) {
            AsyncImage(
                model = resolved.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = faceAwareVerticalAlignment(resolved.faceFocusY),
                modifier = Modifier.matchParentSize().clickable(
                    onClickLabel = stringResource(R.string.cd_image_preview),
                    role = Role.Image,
                ) {
                    onAction(OpenUiAction("OpenPhoto", listOf(OpenUiValue.Str(resolved.uri))))
                },
            )
        } else if (photoId == null && httpsSrc != null) {
            AsyncImage(
                model = httpsSrc,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                onSuccess = { successState ->
                    intrinsicAspectOf(successState.painter.intrinsicSize)?.let { srcRatio = it }
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        if (hasImage) {
            // 底部黑色渐变蒙层（文字可读性，VisualCardItem 同口径）：transparent → Black 0.7
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.md),
        ) {
            // 有图：白字压渐变蒙层；纯色底：跟随主题 onSurface（浅底白字不可读）
            val textColor = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurface
            val subColor = if (hasImage) {
                Color.White.copy(alpha = 0.85f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = title,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.SUBHEAD),
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * ListItem（2026-10-31 实渲染化，经典 ListTile 行）：前件三选一（photo 相册投影 48dp
 * 圆角图 / image https 48dp / icon 36dp 圆形徽章，优先级 photo > image > icon）+
 * 标题 BODY（≤2 行）/副题 CAPTION 弱色（≤1 行）+ 尾部 trailing 弱色小字 / actionLabel
 * 胶囊（ListBlock 同形）；action 存在整行可点（宿主动作通道）。
 */
@Composable
private fun OpenUiContentListItem(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (node.props["title"] as? OpenUiValue.Str)?.v ?: return
    val subtitle = (node.props["subtitle"] as? OpenUiValue.Str)?.v
    val image = (node.props["image"] as? OpenUiValue.Str)?.v
    val photoId = (node.props["photo"] as? OpenUiValue.Num)?.v?.toLong()
    val icon = (node.props["icon"] as? OpenUiValue.Str)?.v?.let { name -> openUiIcon(name) }
    val trailing = (node.props["trailing"] as? OpenUiValue.Str)?.v
    val actionLabel = (node.props["actionLabel"] as? OpenUiValue.Str)?.v
    val actions = contentActions(node.props["action"])
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (actions.isNotEmpty()) {
                    Modifier.clickable { actions.forEach(onAction) }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            photoId != null -> OpenUiContentPhotoImage(
                photoId = photoId,
                photoResolver = photoResolver,
                onAction = onAction,
                modifier = Modifier.size(CONTENT_LIST_IMAGE_SIZE),
            )
            image != null -> OpenUiContentNetworkImage(
                src = image,
                contentDescription = title,
                modifier = Modifier.size(CONTENT_LIST_IMAGE_SIZE),
            )
            icon != null -> Box(
                modifier = Modifier
                    .size(CONTENT_ICON_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.BODY),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let { text ->
            Text(
                text = text,
                style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actionLabel?.let { label ->
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary
                            .copy(alpha = BadgeTokens.tagBackgroundAlpha)
                    )
                    .padding(
                        horizontal = BadgeTokens.tagPaddingH,
                        vertical = BadgeTokens.tagPaddingV,
                    ),
            ) {
                Text(
                    text = label,
                    style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── 相册 APP 组合方式批次（2026-10-31）────────────────────────────────────
// 主流相册 APP（Google Photos / Apple 照片 / 小米相册）签名组合形态固化件：
// PhotoGrid = 方形照片网格（三家主视图签名）；AvatarRow = 人物圆形头像横滑条
//（Google Photos 人物条签名）。刻意绕过 §4.5 真实比例链——网格/头像的均匀
// 裁切本身就是设计语义（方形网格、圆形头像），人脸聚焦兜底构图。

/** PhotoGrid 单件照片数护栏（单卡图片上限同口径，防整卡同屏位图 OOM）。 */
private const val PHOTO_GRID_MAX = 12

/** PhotoGrid cell 间隙（相册 APP 网格密缝签名，2dp；不用间距 token）。 */
private val PHOTO_GRID_GAP = 2.dp

/**
 * PhotoGrid：方形照片网格固化件——cell 恒 1:1 Crop + 人脸聚焦 + 2dp 密缝，整网格
 * 12dp 圆角；photos 相册投影 id 数组端侧批量解析（[PRIVACY] uri 不出端），点击
 * OpenPhoto 大图（Image 同口径）；解析中/失败格落 surfaceVariant 同形占位，不炸整卡；
 * 末行缺格补空位保持列对齐（Grid 同口径）。
 */
@Composable
private fun OpenUiContentPhotoGrid(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ids = (node.props["photos"] as? OpenUiValue.Arr)?.v.orEmpty()
        .mapNotNull { item -> (item as? OpenUiValue.Num)?.v?.toLong() }
        .take(PHOTO_GRID_MAX)
    if (ids.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val columns = ((node.props["columns"] as? OpenUiValue.Num)?.v?.toInt() ?: 3).coerceIn(2, 4)
    val resolved by produceState<Map<Long, OpenUiPhoto>>(initialValue = emptyMap(), ids) {
        value = ids.mapNotNull { id -> photoResolver.resolve(id)?.let { photo -> id to photo } }
            .toMap()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OpenUiCardTokens.imageCornerRadius)),
        verticalArrangement = Arrangement.spacedBy(PHOTO_GRID_GAP),
    ) {
        ids.chunked(columns).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(PHOTO_GRID_GAP)) {
                rowIds.forEach { id ->
                    val photo = resolved[id]
                    val frame = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                    if (photo == null) {
                        Box(modifier = frame)
                    } else {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = faceAwareVerticalAlignment(photo.faceFocusY),
                            modifier = frame.clickable(
                                onClickLabel = stringResource(R.string.cd_image_preview),
                                role = Role.Image,
                            ) {
                                onAction(OpenUiAction("OpenPhoto", listOf(OpenUiValue.Str(photo.uri))))
                            },
                        )
                    }
                }
                repeat(columns - rowIds.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** AvatarRow 头像直径（Google Photos 人物条签名形态的卡内紧凑档）。 */
private val AVATAR_SIZE = 56.dp

/**
 * AvatarRow：人物圆形头像横滑条——56dp 圆形 Crop + 人脸聚焦 + CAPTION 名字居下；
 * items [{photo 相册投影 id*, label?}] 端侧批量解析（[PRIVACY] uri 不出端），点击
 * OpenPhoto 大图（Image 同口径）；解析中/失败项落同形占位，不炸整卡。
 */
@Composable
private fun OpenUiContentAvatarRow(
    node: OpenUiValue.ComponentVal,
    photoResolver: OpenUiPhotoResolver,
    onAction: (OpenUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (node.props["items"] as? OpenUiValue.Arr)?.v.orEmpty().mapNotNull { item ->
        val obj = (item as? OpenUiValue.Obj)?.v ?: return@mapNotNull null
        val photoId = (obj["photo"] as? OpenUiValue.Num)?.v?.toLong() ?: return@mapNotNull null
        photoId to (obj["label"] as? OpenUiValue.Str)?.v
    }.take(MAX_CONTENT_ITEMS)
    if (items.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val ids = items.map { item -> item.first }
    val resolved by produceState<Map<Long, OpenUiPhoto>>(initialValue = emptyMap(), ids) {
        value = ids.mapNotNull { id -> photoResolver.resolve(id)?.let { photo -> id to photo } }
            .toMap()
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEach { (id, label) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(AVATAR_SIZE + Spacing.sm),
            ) {
                val photo = resolved[id]
                val frame = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                if (photo == null) {
                    Box(modifier = frame)
                } else {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        alignment = faceAwareVerticalAlignment(photo.faceFocusY),
                        modifier = frame.clickable(
                            onClickLabel = stringResource(R.string.cd_image_preview),
                            role = Role.Image,
                        ) {
                            onAction(OpenUiAction("OpenPhoto", listOf(OpenUiValue.Str(photo.uri))))
                        },
                    )
                }
                label?.let { text ->
                    Text(
                        text = text,
                        style = OpenUiCardTokens.textStyle(A2uiTextRole.CAPTION),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }
}
