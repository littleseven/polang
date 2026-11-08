package com.mamba.picme.features.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

/**
 * 前向兼容/退役兜底卡（MessagePart.Tombstone 的渲染，2026-11-08 值域终态自
 * UnsupportedPartCard 改名转正）：
 * 旧版本读到新版本写入的未知 part 类型（或字段损坏/已退役鉴别值）时原位显示——
 * 块序与消息其余 part 不受影响，升级新版本后同一消息自动恢复为正式卡片。
 * [summary] 为 payload 携带的可读摘要（存在时优先展示），缺省落升级提示。
 */
@Composable
fun TombstonePartCard(
    summary: String?,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.chat_unsupported_part_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
                Text(
                    text = summary ?: stringResource(R.string.chat_unsupported_part_upgrade),
                    style = MaterialTheme.typography.bodySmall,
                    color = tint.copy(alpha = 0.7f),
                )
            }
        }
    }
}
