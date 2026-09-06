package com.mamba.picme.features.common.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 悬浮底部 Tab 项
 *
 * @param icon 图标
 * @param label 文字标签（显示在图标下方）
 * @param contentDescription 无障碍描述（不显示于界面；为空时回退 label，供 ui-driver/TalkBack 定位）
 * @param selected 当前页高亮（图标着色 primary）；默认 false 保持既有 4 项行为不变
 * @param onClick 点击回调
 */
data class FloatingBottomTabItem(
    val icon: ImageVector,
    val label: String? = null,
    val contentDescription: String? = null,
    /** 当前页高亮（图标着色 primary）；默认 false 保持既有 4 项行为不变。 */
    val selected: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 悬浮底部 Tab 栏
 *
 * 用于相册首页底部，聚合 Camera / Chat / Model Center 等核心二级入口。
 * 采用圆角胶囊容器，悬浮于内容之上，避免与全宽底部导航栏竞争视觉重心。
 */
@Composable
fun FloatingBottomTab(
    items: List<FloatingBottomTabItem>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
                        .padding(horizontal = 16.dp, vertical = if (item.label.isNullOrBlank()) 10.dp else 4.dp)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription ?: item.label,
                        tint = if (item.selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    if (!item.label.isNullOrBlank()) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
