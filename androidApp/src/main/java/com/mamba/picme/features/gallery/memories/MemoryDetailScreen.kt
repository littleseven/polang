package com.mamba.picme.features.gallery.memories

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.domain.memories.Memory
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction

/** 品牌渐变（青玉）：详情页主按钮与 hub「Quick tidy up」同源。 */
private val memoryBrandGradient: Brush
    get() = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd),
    )

/**
 * 回忆详情页（F3，设计稿 memories/detail）：顶栏（返回 + Memories + 分享）→
 * 全宽 16:9 封面（左下蒙层白字标题/副行）→ 3 列精选网格（r8）→ 底部渐变「Share memory」。
 * [memory] 为 null（id 已失效，如媒体清空后）时显示空态文案。
 */
@Composable
fun MemoryDetailScreen(
    memory: Memory?,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AppTopBar(
            title = stringResource(R.string.memory_title),
            onBack = onNavigateBack,
            actions = {
                if (memory != null) {
                    AppTopBarAction(
                        icon = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.memory_share),
                        onClick = { shareMemoryPhotos(context, memory.itemUris) },
                    )
                }
            },
        )
        if (memory == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.memory_detail_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            MemoryCover(memory = memory)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(memory.itemUris, key = { _, uri -> uri }) { index, uri ->
                    MemoryGridItem(uri = uri, index = index)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(memoryBrandGradient)
                    .clickable { shareMemoryPhotos(context, memory.itemUris) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.memory_share),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

/** 全宽 16:9 封面：大图 + 底部黑色渐变蒙层 + 左下白字标题行/副行。 */
@Composable
private fun MemoryCover(memory: Memory) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(memory.coverUri)
                .size(1080)
                .crossfade(false)
                .build(),
            contentDescription = memory.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxSize(fraction = 0.5f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                text = memory.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.memory_best_shots, memory.itemUris.size) +
                    " · " + memory.subtitle,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
            )
        }
    }
}

/** 精选网格小卡：1:1 方图 r8。 */
@Composable
private fun MemoryGridItem(uri: String, index: Int) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surface)
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(uri)
            .size(360)
            .crossfade(false)
            .build(),
        contentDescription = stringResource(R.string.memory_photo_cd, index + 1),
        modifier = Modifier
            .aspectRatio(1f)
            .clip(AppShapes.small)
            .background(MaterialTheme.colorScheme.surface),
        contentScale = ContentScale.Crop,
        placeholder = placeholder,
        error = placeholder,
    )
}

/**
 * 分享整条回忆精选（多图）：ACTION_SEND_MULTIPLE + FLAG_GRANT_READ_URI_PERMISSION，
 * 写法同 Gallery 现有 shareMediaAssets（features/gallery/components/GalleryUtils.kt）。
 */
private fun shareMemoryPhotos(context: Context, uris: List<String>) {
    if (uris.isEmpty()) return
    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            ArrayList(uris.map { uri -> uri.toUri() }),
        )
        type = "image/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, null))
}
