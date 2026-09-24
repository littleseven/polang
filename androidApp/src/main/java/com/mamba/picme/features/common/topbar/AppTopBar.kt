package com.mamba.picme.features.common.topbar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.TopBarTokens

// 图标/间距/高度全部收敛到 TopBarTokens（SSOT），此处不设私有常量
private val TopBarButtonSize = TopBarTokens.buttonSize
private val TopBarIconSize = TopBarTokens.iconSize
private val TopBarSpacing = TopBarTokens.spacing
private val TopBarHorizontalPadding = TopBarTokens.horizontalPadding
private val TopBarHeight = TopBarTokens.height

/**
 * 槽位式主力 topbar（自建紧凑版，微信式：48dp 高、17sp SemiBold 居中标题、底 hairline、内置状态栏 + 刘海避让）。
 *
 * 不再使用 Material3 [androidx.compose.material3.TopAppBar]（其高度写死 64dp），
 * 改为自建 [Row]，保证所有核心页顶栏的高度 / 字号 / 状态栏与刘海避让一致。
 *
 * - 内置 [Modifier.statusBarsPadding] + [Modifier.displayCutoutPadding]，调用方无需再单独避让状态栏 / 刘海；
 *   状态栏避让作用于内部 [Row]，使状态栏区域仍由外层 surface 背景填充，视觉无缝；
 *   仅当顶栏外层已处理状态栏 insets 时，通过 [includeStatusBarPadding] = false 关闭状态栏避让；
 * - 标题统一 17sp / SemiBold，通过 [LocalTextStyle] 注入，调用方传普通 [Text] 即可继承；
 * - [centered] 默认 true（微信式居中，叠加层实现屏幕真中心，不受左右图标数量影响）；
 *   显式传 false 保持左对齐（标题在流内、避让返回键）；
 * - 底部一条 outlineVariant hairline（微信式导航分隔），[showHairline] = false 可关。
 */
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = true,
    /** 是否内置状态栏避让（默认开启）；仅当顶栏外层已处理状态栏 insets 时关闭 */
    includeStatusBarPadding: Boolean = true,
    /** 底部 hairline（默认开启，微信式）；照片沉浸页可关 */
    showHairline: Boolean = true
) {
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = TopBarTokens.titleFontSize.value.sp,
        fontWeight = TopBarTokens.titleFontWeight
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (includeStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
                    .displayCutoutPadding()
                    .height(TopBarHeight)
                    .padding(horizontal = TopBarHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                navigationIcon()
                if (!centered) {
                    CompositionLocalProvider(LocalTextStyle provides titleStyle) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            title()
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TopBarSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
            if (centered) {
                CompositionLocalProvider(LocalTextStyle provides titleStyle) {
                    Box(
                        modifier = Modifier.matchParentSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        title()
                    }
                }
            }
        }
        if (showHairline) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 便捷重载：文字标题 + 可选返回键 + 操作。 */
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = true,
    /** 是否内置状态栏避让（默认开启）；仅当顶栏外层已处理状态栏 insets 时关闭 */
    includeStatusBarPadding: Boolean = true,
    /** 底部 hairline（默认开启，微信式）；照片沉浸页可关 */
    showHairline: Boolean = true
) {
    AppTopBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                AppTopBarNavBack(onClick = onBack)
            }
        },
        actions = actions,
        centered = centered,
        includeStatusBarPadding = includeStatusBarPadding,
        showHairline = showHairline
    )
}

/** 标准操作图标 —— 一致性执行点。锁死 36dp 按钮 + 22dp 字形。 */
@Composable
fun AppTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(TopBarButtonSize)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: LocalContentColor.current,
            modifier = Modifier.size(TopBarIconSize)
        )
    }
}

/**
 * 标准返回键 —— 锁死 AutoMirrored.Outlined.ArrowBack + 36/22。
 *
 * 同时注册 [BackHandler]，使系统返回键（虚拟导航栏返回 / 手势返回）与顶栏返回箭头
 * 共享同一个 [onClick] 回调，按构造保证两路返回语义完全一致。规则收敛在此处，
 * 所有使用返回箭头的页面（含未来新增）自动对齐，无需逐页适配。
 *
 * 当页面内还存在更具体的 [BackHandler]（如多选 / 预览 / 详情态）时，那些 handler
 * 在内容区组合、晚于顶栏，优先级更高，会先消费返回事件——与既有行为一致。
 */
@Composable
fun AppTopBarNavBack(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String = stringResource(R.string.back)
) {
    BackHandler(enabled = enabled, onBack = onClick)
    IconButton(onClick = onClick, modifier = Modifier.size(TopBarButtonSize)) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
            modifier = Modifier.size(TopBarIconSize)
        )
    }
}
