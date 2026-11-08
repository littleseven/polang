package com.mamba.picme.features.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R

/**
 * REASONING part（值域终态 2026-11-08，spec《chat-value-domain-ideal》§6.1）：
 * 可折叠「思考过程」块——默认折叠，展开后浅灰次要文字正文（样式对齐 iOS
 * reasoningItem：chevron + 标题行 13pt 次要色，展开态正文同色同号）。
 * 独立折叠块不进正文桶（flattener TYPE_REASONING 桶），防思考链以正文形态
 * 双显；生产开关默认关，本渲染件随历史回放/后续接线生效。
 *
 * [key] 为 item 级稳定锚（messageId:partId）——折叠态经 rememberSaveable 跨
 * 滚动回收保持（同 EngineerTaskCard 展开态口径）。
 */
@Composable
fun ReasoningPartBlock(
    key: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 150))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_reasoning_title),
                fontSize = 13.sp,
                color = tint,
            )
        }
        if (expanded && text.isNotEmpty()) {
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = tint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
