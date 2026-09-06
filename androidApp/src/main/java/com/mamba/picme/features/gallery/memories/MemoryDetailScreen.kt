package com.mamba.picme.features.gallery.memories

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.domain.memories.Memory
import com.mamba.picme.domain.memories.MemoryType
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction

/**
 * 回忆详情页（F3，2026-09-06 升级，对标小米）：顶栏（返回 + Memories + 分享图标）→
 * 约屏高 55% 封面（左下蒙层白字标题/副行）→ 3 列网格（精选/全部跟随分段开关）→
 * 底部居中胶囊分段开关（精选 = 美学分截 12；全部 = 全部命中时间降序）。分享集合跟随开关。
 * [memory] 为 null（id 已失效，如媒体清空后）时显示空态文案。
 */
@Suppress("LongMethod") // 待重构：封面/网格/分段开关可抽子组合函数
@Composable
fun MemoryDetailScreen(
    memory: Memory?,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    // 精选/全部开关：按 memory id 记忆，切回忆时重置回精选
    var showAll by remember(memory?.id) { mutableStateOf(false) }
    val displayUris = if (showAll) memory?.allItemUris.orEmpty() else memory?.itemUris.orEmpty()
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
                        onClick = { shareMemoryPhotos(context, displayUris) },
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
                itemsIndexed(displayUris, key = { _, uri -> uri }) { index, uri ->
                    MemoryGridItem(uri = uri, index = index)
                }
            }
            // 精选/全部分段开关（对标小米「显示优选/全部显示」）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    listOf(false to R.string.memory_detail_best, true to R.string.memory_detail_all)
                        .forEach { pair ->
                            val isSelected = showAll == pair.first
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent,
                                    )
                                    .clickable { showAll = pair.first }
                                    .semantics {
                                        role = Role.Button
                                        selected = isSelected
                                    }
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = stringResource(pair.second),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                }
            }
        }
    }
}

/** 约屏高 55% 封面：大图 + 底部黑色渐变蒙层 + 左下白字标题行/副行。 */
@Composable
private fun MemoryCover(memory: Memory) {
    val title = memoryTitle(memory)
    // 副行后缀：ON_THIS_DAY/RECENT_HIGHLIGHTS 接原 subtitle；CITY 接旅程日期范围；
    // PERSON 不接（hitCount 与「N 张照片」重复计数）
    val subtitleSuffix = when (memory.type) {
        MemoryType.ON_THIS_DAY, MemoryType.RECENT_HIGHLIGHTS -> memorySubtitle(memory)
        MemoryType.CITY -> cityDateRange(memory)
        MemoryType.PERSON -> null
    }
    val coverHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(coverHeight),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(memory.coverUri)
                .size(1080)
                .crossfade(false)
                .build(),
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
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
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.memory_items_count, memory.hitCount) +
                    subtitleSuffix?.let { suffix -> " · $suffix" }.orEmpty(),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
            )
        }
    }
}

/** 网格小卡：1:1 方图 r8。 */
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
 * 分享当前展示集合（多图）：ACTION_SEND_MULTIPLE + FLAG_GRANT_READ_URI_PERMISSION，
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
