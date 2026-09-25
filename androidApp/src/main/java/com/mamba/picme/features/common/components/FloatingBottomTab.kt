package com.mamba.picme.features.common.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mamba.picme.core.designsystem.BottomTabTokens

/**
 * 悬浮底部 Tab 项
 *
 * @param icon 图标
 * @param label 文字标签（显示在图标下方）
 * @param contentDescription 无障碍描述（不显示于界面；为空时回退 label，供 ui-driver/TalkBack 定位）
 * @param selected 当前页高亮（图标+标签着色 primary）；默认 false 保持既有行为不变
 * @param onClick 点击回调
 */
data class FloatingBottomTabItem(
    val icon: ImageVector,
    val label: String? = null,
    val contentDescription: String? = null,
    /** 当前页高亮（图标+标签着色 primary）；默认 false 保持既有行为不变。 */
    val selected: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 悬浮底部 Tab 栏（2026-09-26 形态回退：平底条现代感不足，悬浮胶囊恢复；
 * 配色保留微信系——选中 primary(#07C160) / 未选中 onSurfaceVariant(#888)）。
 *
 * 圆角胶囊（bottomTab.cornerRadius=28）、tonal/shadow 悬浮、surface 底；
 * label 参数保留渲染能力（当前调用方未传，纯图标）。
 */
@Composable
fun FloatingBottomTab(
    items: List<FloatingBottomTabItem>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(BottomTabTokens.cornerRadius),
        tonalElevation = BottomTabTokens.tonalElevation,
        shadowElevation = BottomTabTokens.shadowElevation,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = BottomTabTokens.containerPaddingH,
                vertical = BottomTabTokens.containerPaddingV
            )
        ) {
            items.forEach { item ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = item.onClick
                        )
                        .padding(
                            horizontal = BottomTabTokens.itemPaddingH,
                            vertical = if (item.label.isNullOrBlank()) {
                                BottomTabTokens.itemPaddingVIconOnly
                            } else {
                                BottomTabTokens.itemPaddingVWithLabel
                            }
                        )
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription ?: item.label,
                        tint = if (item.selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(BottomTabTokens.iconSize)
                    )
                    if (!item.label.isNullOrBlank()) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = BottomTabTokens.labelTopPadding)
                        )
                    }
                }
            }
        }
    }
}
