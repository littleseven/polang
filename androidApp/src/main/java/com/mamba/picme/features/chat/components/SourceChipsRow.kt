package com.mamba.picme.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mamba.picme.domain.chat.MessagePart

/** 引用 chip 的渲染模型（序号 + 标签 + 落地页 URL；文档引用无 URL 不可点）。 */
internal data class SourceChipUi(
    val id: String,
    val ordinal: Int,
    val label: String,
    val url: String?,
)

/**
 * 自 [anchor]（flattener 聚合 item 锚定的 run 首个 source part）起取**连续**
 * SourceUrl/SourceDocument parts（多段 run 各自成 item，渲染只取本段，防御性
 * break 于首个非 source part）——聚合口径与 ChatListFlattener/iOS sourceChips 一致。
 *
 * URL chip 仅放行 http/https（Intent.ACTION_VIEW 前向防线——数据源为 web_search
 * 结果，非 web scheme 不点火）；[MessagePart.SourceDocument] 无 URL，显示
 * filename/title 且不可点（iOS 对等口径）。
 */
internal fun sourceChipsOf(anchor: MessagePart, parts: List<MessagePart>): List<SourceChipUi> {
    val start = parts.indexOfFirst { it === anchor }
    if (start < 0) return emptyList()
    val chips = ArrayList<SourceChipUi>()
    for (part in parts.subList(start, parts.size)) {
        when (part) {
            is MessagePart.SourceUrl -> chips += SourceChipUi(
                id = part.partId,
                ordinal = chips.size + 1,
                label = part.url.toUri().host ?: part.url,
                url = part.url.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            )
            is MessagePart.SourceDocument -> chips += SourceChipUi(
                id = part.partId,
                ordinal = chips.size + 1,
                label = part.filename ?: part.title,
                url = null,
            )
            else -> break
        }
    }
    return chips
}

/**
 * SOURCE_CHIPS item（值域终态 2026-11-08，spec《chat-value-domain-ideal》§6.2）：
 * flattener 把消息内连续 SourceUrl/SourceDocument parts 聚合为单条引用 chips 行——
 * 序号 + 域名横排（样式对齐 iOS sourceChipsItem：11sp 半粗序号 + 12sp 域名、
 * surfaceVariant 底 16dp 圆角胶囊、横 8 竖 6 流式排布）；点击经 [onOpenUrl]
 * 打开落地页（Intent/系统浏览器）。正文 markdown 图链行为不变（图=配图，
 * chip=来源列表，职责不同）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceChipsRow(
    anchor: MessagePart,
    parts: List<MessagePart>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = remember(anchor, parts) { sourceChipsOf(anchor, parts) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        chips.forEach { chip ->
            key(chip.id) {
                SourceChipPill(chip = chip, onOpenUrl = onOpenUrl)
            }
        }
    }
}

@Composable
private fun SourceChipPill(chip: SourceChipUi, onOpenUrl: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (chip.url != null) {
                    Modifier.clickable { onOpenUrl(chip.url) }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = chip.ordinal.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = chip.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}
