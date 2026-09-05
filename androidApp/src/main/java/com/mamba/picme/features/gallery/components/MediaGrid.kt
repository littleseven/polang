package com.mamba.picme.features.gallery.components

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.core.image.ThumbnailCache
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

@Suppress("LongParameterList") // 拖拽批选回调集 + F3 header 槽；待重构：抽参数 holder
@Composable
fun MediaGrid(
    context: Context,
    groupedMedia: List<GroupedMedia>,
    selectedIds: List<Long>,
    isSelectionMode: Boolean,
    thumbnailPositions: Map<Long, Rect>,
    mediaById: Map<Long, MediaAsset>,
    thumbnailCache: ThumbnailCache? = null,
    onThumbnailPositioned: (Long, Rect) -> Unit,
    onMediaClick: (MediaAsset) -> Unit,
    onMediaLongClick: (MediaAsset) -> Unit,
    onDragSelectionStart: (MediaAsset) -> Unit,
    onDragSelectionItem: (MediaAsset) -> Unit,
    onDragSelectionEnd: () -> Unit,
    onGroupTitleClick: ((GroupedMedia) -> Unit)? = null,
    personNameMap: Map<String, String>? = null,
    /** 网格顶部全宽 header 槽（F3 回忆 carousel；null = 不占位） */
    header: (@Composable LazyGridItemScope.() -> Unit)? = null
) {
    var gridPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val selectedIdSet by remember { derivedStateOf { selectedIds.toSet() } }
    val currentThumbnailPositions by rememberUpdatedState(thumbnailPositions)

    fun resolveDraggedAsset(localPoint: Offset): MediaAsset? {
        val windowPoint = localPoint + gridPositionInWindow
        val hitId = currentThumbnailPositions.entries.firstOrNull { (_, rect) ->
            windowPoint.x in rect.left..rect.right && windowPoint.y in rect.top..rect.bottom
        }?.key
        return hitId?.let { mediaById[it] }
    }

    val gridState = rememberLazyGridState()

    // 预加载可视区域附近的缩略图到 ThumbnailCache（L1 LRU + L2 磁盘）
    if (thumbnailCache != null && groupedMedia.isNotEmpty()) {
        LaunchedEffect(gridState.firstVisibleItemIndex) {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@LaunchedEffect

            val totalItems = groupedMedia.sumOf { it.items.size }
            val firstVisible = visibleItems.first().index
            val lastVisible = visibleItems.last().index
            val pageSize = (lastVisible - firstVisible + 1).coerceAtLeast(1)
            val prefetchStart = (firstVisible - pageSize * 3).coerceAtLeast(0)
            val prefetchEnd = (lastVisible + pageSize * 3).coerceAtMost(totalItems - 1)

            val prefetchUris = buildList {
                var idx = 0
                for (group in groupedMedia) {
                    for (item in group.items) {
                        if (idx in prefetchStart..prefetchEnd) {
                            add(item.uri)
                        }
                        idx++
                    }
                }
            }
            thumbnailCache.preload(prefetchUris)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                gridPositionInWindow = coordinates.positionInWindow()
            }
            .pointerInput(isSelectionMode) {
                if (isSelectionMode) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            resolveDraggedAsset(offset)?.let(onDragSelectionStart)
                        },
                        onDragEnd = onDragSelectionEnd,
                        onDragCancel = onDragSelectionEnd,
                        onDrag = { change, _ ->
                            change.consume()
                            resolveDraggedAsset(change.position)?.let(onDragSelectionItem)
                        }
                    )
                } else {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            resolveDraggedAsset(offset)?.let(onDragSelectionStart)
                        },
                        onDragEnd = onDragSelectionEnd,
                        onDragCancel = onDragSelectionEnd,
                        onDrag = { change, _ ->
                            change.consume()
                            resolveDraggedAsset(change.position)?.let(onDragSelectionItem)
                        }
                    )
                }
            },
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (header != null) {
            item(key = "memories_header", span = { GridItemSpan(maxLineSpan) }) {
                header()
            }
        }
        groupedMedia.forEach { group ->
            val groupTitle = resolveGroupTitle(context, group, personNameMap)
            if (groupTitle.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    MediaGroupHeader(
                        title = groupTitle,
                        count = group.items.size,
                        onClick = { onGroupTitleClick?.invoke(group) }
                    )
                }
            }
            items(group.items, key = { item -> item.id }) { asset ->
                MediaItem(
                    asset = asset,
                    isSelected = selectedIdSet.contains(asset.id),
                    isSelectionMode = isSelectionMode,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        onThumbnailPositioned(
                            asset.id,
                            Rect(coords.positionInWindow(), coords.size.toSize())
                        )
                    },
                    onClick = { onMediaClick(asset) },
                    onLongClick = { onMediaLongClick(asset) }
                )
            }
        }
    }
}

private fun IntSize.toSize() = Size(width.toFloat(), height.toFloat())

private val ThumbnailCornerRadius: Dp = 2.dp

/** 缩略图加载/失败占位，使用主题 surface 色，避免在浅色主题下出现深色/黑色闪烁 */
@Composable
private fun thumbnailPlaceholderPainter(): Painter = ColorPainter(MaterialTheme.colorScheme.surface)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItem(
    asset: MediaAsset,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val alignment = remember(asset.faceFocusY) { faceAwareVerticalAlignment(asset.faceFocusY) }
    val mediaTypeLabel = remember(asset.type) {
        when (asset.type) {
            MediaType.VIDEO -> context.getString(R.string.media_type_video)
            MediaType.DOCUMENT -> context.getString(R.string.media_type_document)
            else -> context.getString(R.string.media_type_photo)
        }
    }
    val contentDescription = mediaTypeLabel
    val selectionStateDescription = when {
        isSelectionMode && isSelected -> context.getString(R.string.media_state_selected)
        isSelectionMode -> context.getString(R.string.media_state_unselected)
        else -> null
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ThumbnailCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                this.contentDescription = contentDescription
                selectionStateDescription?.let { this.stateDescription = it }
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(asset.uri)
                .size(360)
                // 关闭缩略图交叉淡入淡出：避免旧 Bitmap 在动画期间被回收/替换
                // 导致 "Canvas: trying to use a recycled bitmap" 崩溃
                .crossfade(false)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = alignment,
            placeholder = thumbnailPlaceholderPainter(),
            error = thumbnailPlaceholderPainter()
        )

        if (asset.type == MediaType.VIDEO) {
            Icon(
                Icons.Rounded.PlayCircle,
                contentDescription = context.getString(R.string.media_type_video),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
        }

        if (isSelectionMode) {
            SelectionOverlay(isSelected = isSelected)
        }
    }
}

@Composable
private fun SelectionOverlay(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isSelected) {
                    Color.Black.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                }
            )
            .padding(4.dp)
    ) {
        Icon(
            imageVector = if (isSelected) {
                Icons.Rounded.CheckCircle
            } else {
                Icons.Rounded.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
        )
    }
}
