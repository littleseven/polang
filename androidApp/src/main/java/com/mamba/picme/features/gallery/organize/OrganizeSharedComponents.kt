package com.mamba.picme.features.gallery.organize

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.organize.ProtectReason
import com.mamba.picme.features.gallery.dedup.formatBytes

/**
 * 整理中心详情页共享组件（类目详情页 / 来源详情页同构复用）：网格缩略图、段标题、
 * 底部删除 CTA、完成态内容与 Undo 栏。品牌渐变与 CleanGreen 同源收口。
 */

/** 品牌渐变（青玉）：完成态大数字 / Undo 主按钮与 hub 同源。 */
internal val orgBrandGradient: Brush
    get() = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
    )

internal val CleanGreen = Color(0xFF4CAF50)

/** 分段标题行（GridItemSpan 整行）；protected 段用 tertiary 色强调价值保护。 */
@Composable
internal fun OrganizeSectionHeader(text: String, isProtected: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = if (isProtected) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/** 网格缩略图：右上角选中勾（primary 实色圆 + 白 ✓；未选中为描边空心圈）；受保护项左下角盾牌角标。 */
@Composable
internal fun OrganizeThumb(
    item: OrganizeItem,
    selected: Boolean,
    protectReasons: Set<ProtectReason>,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val typeLabel = context.getString(
        if (item.isVideo) R.string.media_type_video else R.string.media_type_photo
    )
    val selectionState = context.getString(
        if (selected) R.string.media_state_selected else R.string.media_state_unselected
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = typeLabel
                stateDescription = selectionState
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri)
                .size(360)
                // Coil 铁律：禁 crossfade，防 recycled bitmap 崩溃
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxSize()
        )
        if (protectReasons.isNotEmpty()) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = stringResource(R.string.org_protected_badge),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(16.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(2.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black.copy(alpha = 0.2f)
                    }
                )
                .border(
                    width = 1.5.dp,
                    color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.8f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** 底部删除 CTA：error 色通栏按钮 + 30 天保留 caption。 */
@Composable
internal fun OrganizeDeleteBar(
    selectedCount: Int,
    selectedBytes: Long,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = stringResource(
                    R.string.org_move_to_trash,
                    selectedCount,
                    formatBytes(selectedBytes)
                )
            )
        }
        Text(
            text = stringResource(R.string.org_trash_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 完成态：✅ 圆标 + All clean! + 回收预览卡（前 3 缩略图 + 多余计数）；Undo 在底部栏。
 * 字段入参（非 UiState 对象）：类目/来源两详情页共用，状态类型互不依赖。
 */
@Suppress("LongMethod") // 待重构：预览卡可抽子组合函数
@Composable
internal fun OrganizeCleanedContent(
    trashedCount: Int,
    trashedBytes: Long,
    lastTrashedUris: List<String>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = CleanGreen
        )
        Text(
            text = stringResource(R.string.org_cleaned_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.org_cleaned_meta, trashedCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.org_cleaned_freed, formatBytes(trashedBytes)),
            style = TextStyle(
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                brush = orgBrandGradient
            )
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dedup_recycle_bin),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.dedup_auto_clear),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    lastTrashedUris.take(3).forEach { uri ->
                        CleanedThumb(uri = uri)
                    }
                    val extra = lastTrashedUris.size - 3
                    if (extra > 0) {
                        Text(
                            text = stringResource(R.string.dedup_more_count, extra),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 完成态回收预览缩略图（已回收 uri 仍可经 Coil 直读，与 DedupCleanedContent 同口径）。 */
@Composable
private fun CleanedThumb(uri: String) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(uri)
            .size(360)
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** 完成态底部 Undo 渐变主按钮（v1 省略「View trash」文本钮）。 */
@Composable
internal fun OrganizeUndoBar(onUndo: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(orgBrandGradient)
            .clickable(onClick = onUndo),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.org_undo),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}
