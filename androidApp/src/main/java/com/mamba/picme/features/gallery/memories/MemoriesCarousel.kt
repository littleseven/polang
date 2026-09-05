package com.mamba.picme.features.gallery.memories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.ChatCarouselTokens
import com.mamba.picme.domain.memories.Memory

/** 设计稿横向外边距：MediaGrid contentPadding(2dp) 之外补 14dp，对齐分组标题的 16dp 页边距。 */
private val CarouselHorizontalInset = 14.dp

/**
 * 相册首页顶部「回忆」carousel（F3，设计稿 memories/carousel）：
 * 标题行（Memories + On-device · private）+ 横滑小卡（120×150 r12，封面 + 底部渐变蒙层 +
 * 白字 title/subtitle）。点按进详情；长按弹确认后隐藏（持久化到 memory_hidden_ids）。
 */
@Composable
fun MemoriesCarousel(
    memories: List<Memory>,
    onMemoryClick: (Memory) -> Unit,
    onHide: (Memory) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingHide by remember { mutableStateOf<Memory?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CarouselHorizontalInset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.memory_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.memory_privacy_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = CarouselHorizontalInset),
            horizontalArrangement = Arrangement.spacedBy(ChatCarouselTokens.cardSpacing),
        ) {
            items(memories, key = { memory -> memory.id }) { memory ->
                MemoryCard(
                    memory = memory,
                    onClick = { onMemoryClick(memory) },
                    onLongClick = { pendingHide = memory },
                )
            }
        }
    }

    pendingHide?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingHide = null },
            title = { Text(stringResource(R.string.memory_hide)) },
            text = { Text(stringResource(R.string.memory_hide_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingHide = null
                        onHide(memory)
                    },
                ) {
                    Text(stringResource(R.string.memory_hide))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHide = null }) {
                    Text(stringResource(R.string.memory_hide_cancel))
                }
            },
        )
    }
}

/** 单张回忆小卡：封面图 + 底部黑色渐变蒙层 + 白字两行（title 13sp / subtitle 11sp 70%）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryCard(
    memory: Memory,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surface)
    val title = memoryTitle(memory)
    val subtitle = memorySubtitle(memory)
    Box(
        modifier = Modifier
            .width(ChatCarouselTokens.cardWidth)
            .height(ChatCarouselTokens.cardHeight)
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "$title · $subtitle" },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(memory.coverUri)
                .size(360)
                // 关闭交叉淡入淡出：避免旧 Bitmap 在动画期间被回收导致 recycled bitmap 崩溃
                .crossfade(false)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
        )
        // 底部渐变蒙层（transparent → 黑 60%），保证白字在亮封面上可读
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(ChatCarouselTokens.cardHeight / 2)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
