package com.mamba.picme.features.gallery.memories

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.domain.memories.Memory
import com.mamba.picme.domain.memories.MemoryMosaic
import com.mamba.picme.domain.memories.MemoryType
import com.mamba.picme.domain.memories.MosaicBlock
import com.mamba.picme.domain.memories.MosaicBlockType
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.components.MediaPager
import com.mamba.picme.features.gallery.components.TRASH_TAG_PREVIEW_MEMORY
import com.mamba.picme.features.gallery.components.TrashAuthEffects
import com.mamba.picme.domain.trash.TrashOutcome

/**
 * 回忆详情页（F3，2026-09-18 蒙德里安重设计）：顶栏（返回 + Memories + 分享图标）→
 * 整页一条竖向滚动长列表：16:9 头图（左下蒙层白字标题/副行，随列表滚动不钉顶）→
 * 元信息行（左 = 精选/全部胶囊分段开关，右 = 当前集合计数，内联随列表滚动）→
 * 蒙德里安拼贴网格（五种卡块按 memory.id 种子确定性洗牌、同向不相邻，[MemoryMosaic]
 * 纯函数；精选 = 美学分截 12，全部 = 全部命中时间降序）。分享集合跟随开关。
 * [memory] 为 null（id 已失效，如媒体清空后）时显示空态文案。
 *
 * 图片预览（2026-09-06 补齐）：封面与网格照片点击均打开全屏 [MediaPager]（初始页按 uri
 * 在 [assetsByUri] 反查后的预览集合中定位，未解析项自动剔除）；预览内删除/授权链路复用
 * [mediaViewModel]（写法同 ChatScreen 图片预览），删除后预览集合随媒体库流自动收缩，
 * 删空自动收起预览；系统返回键优先关闭预览再弹栈。
 */
@Suppress("LongParameterList")
@Composable
fun MemoryDetailScreen(
    memory: Memory?,
    assetsByUri: Map<String, MediaAsset>,
    mediaViewModel: MediaViewModel,
    onNavigateToPhotoEditor: (uri: String, autoOptimize: Boolean) -> Unit,
    onNavigateToIDPhoto: (uri: String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    // 精选/全部开关：按 memory id 记忆，切回忆时重置回精选
    var showAll by remember(memory?.id) { mutableStateOf(false) }
    val displayUris = if (showAll) memory?.allItemUris.orEmpty() else memory?.itemUris.orEmpty()
    // 预览集合：displayUris 顺序经全库 uri 索引反查完整 MediaAsset（MediaPager 需要
    // id/type/captureDate 等字段）；媒体库变化（如预览内删除）随流重发自动收缩；
    // swipeTrashedUris 为上滑回收站删除授权成功后的本地即时收缩集
    var swipeTrashedUris by remember(memory?.id) { mutableStateOf<Set<String>>(emptySet()) }
    val previewAssets = remember(displayUris, assetsByUri, swipeTrashedUris) {
        displayUris.filter { uri -> uri !in swipeTrashedUris }.mapNotNull { uri -> assetsByUri[uri] }
    }
    // 全屏预览页索引（null = 关闭）；切回忆时重置，精选/全部开关切换也收起（索引口径已变）
    var previewIndex by remember(memory?.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(showAll) { previewIndex = null }
    // 预览集合删空（或回忆随媒体清空失效）→ 自动收起，避免停留在空 Pager
    LaunchedEffect(previewAssets.isEmpty()) {
        if (previewAssets.isEmpty()) previewIndex = null
    }

    // ── 预览内删除授权（API 29 恢复性删除 / API 30+ 批量删除请求），写法同 ChatScreen ──
    val deleteAuthRequest by mediaViewModel.deleteAuthRequest.collectAsState()
    val api29DeleteLauncher = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted API 29 delete permission")
                mediaViewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied API 29 delete permission")
                mediaViewModel.clearPendingRecoverable()
                mediaViewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }
    val deletePermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted delete permission")
                mediaViewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied delete permission")
                mediaViewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }
    LaunchedEffect(deleteAuthRequest) {
        deleteAuthRequest?.let { request ->
            when (request) {
                is MediaViewModel.DeleteAuthRequest.Api29 -> {
                    api29DeleteLauncher?.launch(
                        IntentSenderRequest.Builder(request.intentSender).build()
                    )
                }
                is MediaViewModel.DeleteAuthRequest.Api30 -> {
                    val intent = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        request.uris
                    )
                    deletePermissionLauncher?.launch(
                        IntentSenderRequest.Builder(intent).build()
                    )
                }
            }
            mediaViewModel.consumeDeleteAuthRequest()
        }
    }

    // ── 预览页上滑删除（回收站 30 天可恢复）：授权拉起 + Trashed outcome 后本地收缩预览集合 ──
    TrashAuthEffects(controller = mediaViewModel.trashController, tag = TRASH_TAG_PREVIEW_MEMORY)
    LaunchedEffect(Unit) {
        mediaViewModel.trashController.outcomes.collect { outcome ->
            if (outcome is TrashOutcome.Trashed && outcome.tag == TRASH_TAG_PREVIEW_MEMORY) {
                swipeTrashedUris = swipeTrashedUris + outcome.trashedUris.toSet()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                val mosaicBlocks = remember(memory.id, displayUris) {
                    MemoryMosaic.layout(memory.id, displayUris)
                }
                // 各卡块首张照片序号（无障碍 contentDescription 用）
                val blockStartIndices = remember(mosaicBlocks) {
                    var start = 0
                    mosaicBlocks.map { block ->
                        val current = start
                        start += block.uris.size
                        current
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item(key = "cover") {
                        MemoryCover(
                            memory = memory,
                            onClick = {
                                previewIndex = previewAssets
                                    .indexOfFirst { asset -> asset.uri == memory.coverUri }
                                    .takeIf { index -> index >= 0 }
                            },
                        )
                    }
                    item(key = "meta") {
                        MemoryMetaRow(
                            showAll = showAll,
                            count = displayUris.size,
                            onShowAllChange = { all -> showAll = all },
                        )
                    }
                    itemsIndexed(
                        mosaicBlocks,
                        key = { index, block -> "block_${index}_${block.uris.first()}" },
                    ) { index, block ->
                        MosaicBlockRow(
                            block = block,
                            startIndex = blockStartIndices[index],
                            onOpenUri = { uri ->
                                previewIndex = previewAssets
                                    .indexOfFirst { asset -> asset.uri == uri }
                                    .takeIf { found -> found >= 0 }
                            },
                        )
                    }
                }
            }
        }

        // 全屏图片预览覆盖层（内嵌 MediaPager，同 Gallery/Chat 宿主范式）
        val currentPreviewIndex = previewIndex
        if (currentPreviewIndex != null && previewAssets.isNotEmpty()) {
            BackHandler { previewIndex = null }
            MediaPager(
                assets = previewAssets,
                initialIndex = currentPreviewIndex.coerceIn(0, previewAssets.lastIndex),
                onClose = { previewIndex = null },
                onPageViewed = { uri -> mediaViewModel.markMediaViewed(uri) },
                onSwipeUpDelete = { asset ->
                    mediaViewModel.requestTrash(asset, TRASH_TAG_PREVIEW_MEMORY)
                },
                isTrashSupported = mediaViewModel.isTrashSupported,
                onDelete = { asset -> mediaViewModel.deleteMediaByIds(listOf(asset.id)) },
                onStartOcr = { uriString ->
                    mediaViewModel.recognizeTextFromCurrentImage(context, uriString.toUri())
                },
                onDismissOcr = { mediaViewModel.clearOcrResult() },
                ocrState = mediaViewModel.ocrState,
                onNavigateToEditor = { asset -> onNavigateToPhotoEditor(asset.uri, false) },
                onAiOptimize = { asset -> onNavigateToPhotoEditor(asset.uri, true) },
                onIdPhoto = { asset -> onNavigateToIDPhoto(asset.uri) },
            )
        }
    }
}

/** 16:9 头图（宽撑满、高 = 屏宽 × 9/16）：大图 + 底部黑色渐变蒙层 + 左下白字标题行/副行；点击打开全屏预览。 */
@Composable
private fun MemoryCover(memory: Memory, onClick: () -> Unit) {
    val title = memoryTitle(memory)
    // 副行后缀：ON_THIS_DAY/RECENT_HIGHLIGHTS 接原 subtitle；CITY 接旅程日期范围；
    // PERSON 不接（hitCount 与「N 张照片」重复计数）
    val subtitleSuffix = when (memory.type) {
        MemoryType.ON_THIS_DAY, MemoryType.RECENT_HIGHLIGHTS -> memorySubtitle(memory)
        MemoryType.CITY -> cityDateRange(memory)
        MemoryType.PERSON -> null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable(onClick = onClick),
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

/** 元信息行：左 = 精选/全部胶囊分段开关（内联随列表滚动，不再底部悬浮），右 = 当前展示集合计数。 */
@Composable
private fun MemoryMetaRow(showAll: Boolean, count: Int, onShowAllChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GRID_HORIZONTAL_PADDING.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
        ) {
            listOf(false to R.string.memory_detail_best, true to R.string.memory_detail_all)
                .forEach { pair ->
                    val isSelected = showAll == pair.first
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                            )
                            .clickable { onShowAllChange(pair.first) }
                            .semantics {
                                role = Role.Button
                                selected = isSelected
                            }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
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
        Text(
            text = stringResource(R.string.memory_items_count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 蒙德里安拼贴卡块行：五种卡块（2×2 大卡左/右、2×1 横幅左/右、三方卡行）；单元 = (屏宽-32-8)/3。 */
@Composable
private fun MosaicBlockRow(block: MosaicBlock, startIndex: Int, onOpenUri: (String) -> Unit) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cell = ((screenWidthDp - GRID_HORIZONTAL_PADDING * 2 - GRID_SPACING * 2) / 3f).dp
    val bigSize = cell * 2 + GRID_SPACING.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GRID_HORIZONTAL_PADDING.dp)
            .padding(bottom = GRID_SPACING.dp),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING.dp),
    ) {
        when (block.type) {
            MosaicBlockType.BIG_LEFT -> {
                MosaicTile(block.uris[0], startIndex, bigSize, bigSize, REQUEST_SIZE_LARGE, onOpenUri)
                Column(verticalArrangement = Arrangement.spacedBy(GRID_SPACING.dp)) {
                    MosaicTile(block.uris[1], startIndex + 1, cell, cell, REQUEST_SIZE_SMALL, onOpenUri)
                    MosaicTile(block.uris[2], startIndex + 2, cell, cell, REQUEST_SIZE_SMALL, onOpenUri)
                }
            }
            MosaicBlockType.BIG_RIGHT -> {
                Column(verticalArrangement = Arrangement.spacedBy(GRID_SPACING.dp)) {
                    MosaicTile(block.uris[0], startIndex, cell, cell, REQUEST_SIZE_SMALL, onOpenUri)
                    MosaicTile(block.uris[1], startIndex + 1, cell, cell, REQUEST_SIZE_SMALL, onOpenUri)
                }
                MosaicTile(block.uris[2], startIndex + 2, bigSize, bigSize, REQUEST_SIZE_LARGE, onOpenUri)
            }
            MosaicBlockType.BANNER_LEFT -> {
                MosaicTile(block.uris[0], startIndex, bigSize, cell, REQUEST_SIZE_LARGE, onOpenUri)
                MosaicTile(block.uris[1], startIndex + 1, cell, cell, REQUEST_SIZE_SMALL, onOpenUri)
            }
            MosaicBlockType.BANNER_RIGHT -> {
                MosaicTile(block.uris[0], startIndex, cell, cell, REQUEST_SIZE_SMALL, onOpenUri)
                MosaicTile(block.uris[1], startIndex + 1, bigSize, cell, REQUEST_SIZE_LARGE, onOpenUri)
            }
            MosaicBlockType.SQUARE_ROW -> {
                block.uris.forEachIndexed { offset, uri ->
                    MosaicTile(uri, startIndex + offset, cell, cell, REQUEST_SIZE_SMALL, onOpenUri)
                }
            }
        }
    }
}

/** 拼贴瓦片：定宽定高 r8；点击打开全屏预览（按 uri 定位页索引）。 */
@Composable
private fun MosaicTile(
    uri: String,
    index: Int,
    width: Dp,
    height: Dp,
    requestSize: Int,
    onOpenUri: (String) -> Unit,
) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surface)
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(uri)
            .size(requestSize)
            .crossfade(false)
            .build(),
        contentDescription = stringResource(R.string.memory_photo_cd, index + 1),
        modifier = Modifier
            .size(width, height)
            .clip(AppShapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpenUri(uri) },
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

private const val TAG = "PoLang:Memories"
private const val GRID_HORIZONTAL_PADDING = 16
private const val GRID_SPACING = 4
private const val REQUEST_SIZE_LARGE = 720
private const val REQUEST_SIZE_SMALL = 360
