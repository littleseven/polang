package com.mamba.picme.features.gallery.memories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Sell
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.MemoryPageTokens
import com.mamba.picme.domain.memories.Memory
import com.mamba.picme.domain.memories.MemoryType
import com.mamba.picme.features.common.components.FloatingBottomTab
import com.mamba.picme.features.common.components.FloatingBottomTabItem

/** 分区枚举 → 展示模型（内部）：标题资源 + 该分区回忆列表。 */
private data class MemorySection(
    val titleRes: Int,
    val memories: List<Memory>,
)

/** 按类型分区：时光（ON_THIS_DAY + RECENT_HIGHLIGHTS）→ 旅程（CITY）→ 人物（PERSON）；空分区剔除。 */
private fun buildSections(memories: List<Memory>): List<MemorySection> = listOf(
    MemorySection(
        R.string.memory_section_time,
        memories.filter { memory ->
            memory.type == MemoryType.ON_THIS_DAY || memory.type == MemoryType.RECENT_HIGHLIGHTS
        },
    ),
    MemorySection(
        R.string.memory_section_journey,
        memories.filter { memory -> memory.type == MemoryType.CITY },
    ),
    MemorySection(
        R.string.memory_section_people,
        memories.filter { memory -> memory.type == MemoryType.PERSON },
    ),
).filter { section -> section.memories.isNotEmpty() }

/**
 * Memory 独立 Pager 页（2026-09-06，对标小米相册）：顶栏（标题「回忆」+ 副标「端侧生成 · 私密」）→
 * 三分区大卡 feed（时光/旅程/人物，空分区不占位）→ 整页空态；长按大卡弹隐藏确认；
 * 底部悬浮 5 图标底 bar（回忆项 selected 高亮，出口与 GalleryScreen 同源）。
 *
 * @param isActivePage 预留参数：与其他 Pager 页签名对齐（本页无内部 BackHandler，暂未消费）。
 */
@Composable
fun MemoryScreen(
    memoriesViewModel: MemoriesViewModel,
    onNavigateToMemoryDetail: (String) -> Unit,
    // 底 bar 五个出口（与 GalleryScreen 同源）
    onNavigateToDedupHome: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToTagControl: () -> Unit,
    onNavigateToPeople: () -> Unit,
    isActivePage: Boolean,
) {
    val memories by memoriesViewModel.memories.collectAsStateWithLifecycle()
    var pendingHide by remember { mutableStateOf<Memory?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：大标题 + 副标（对齐相册页「相册」标题风格）
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.memory_privacy_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val sections = remember(memories) { buildSections(memories) }
            if (sections.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.memory_empty_page),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp), // 底 bar 悬浮遮挡预留
                ) {
                    items(sections, key = { section -> section.titleRes }) { section ->
                        MemorySectionRow(
                            section = section,
                            onMemoryClick = { memory -> onNavigateToMemoryDetail(memory.id) },
                            onMemoryLongClick = { memory -> pendingHide = memory },
                        )
                    }
                }
            }
        }

        // 悬浮底 bar：5 图标，回忆项 selected 高亮
        FloatingBottomTab(
            items = listOf(
                FloatingBottomTabItem(
                    icon = Icons.Outlined.BurstMode,
                    contentDescription = stringResource(R.string.gallery_cleanup),
                    onClick = onNavigateToDedupHome,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.ChatBubble,
                    contentDescription = stringResource(R.string.chat),
                    onClick = onNavigateToChat,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.Sell,
                    contentDescription = stringResource(R.string.tag_scan_control),
                    onClick = onNavigateToTagControl,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.AccountCircle,
                    contentDescription = stringResource(R.string.gallery_people_entry),
                    onClick = onNavigateToPeople,
                ),
                FloatingBottomTabItem(
                    icon = Icons.Outlined.Collections,
                    contentDescription = stringResource(R.string.tab_memories),
                    selected = true,
                    onClick = { /* 当前页，空操作 */ },
                ),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        )
        // 隐藏确认弹窗（从旧 MemoriesCarousel 平移，文案键不变）
        pendingHide?.let { memory ->
            AlertDialog(
                onDismissRequest = { pendingHide = null },
                title = { Text(stringResource(R.string.memory_hide)) },
                text = { Text(stringResource(R.string.memory_hide_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingHide = null
                            memoriesViewModel.hideMemory(memory.id)
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
}

/** 分区行：标题 + 大卡 LazyRow（横滑，内容两侧 16dp 内边距）。 */
@Composable
private fun MemorySectionRow(
    section: MemorySection,
    onMemoryClick: (Memory) -> Unit,
    onMemoryLongClick: (Memory) -> Unit,
) {
    Column(modifier = Modifier.padding(top = MemoryPageTokens.sectionTitleSpacing)) {
        Text(
            text = stringResource(section.titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = MemoryPageTokens.sectionHorizontalPadding),
        )
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = MemoryPageTokens.sectionHorizontalPadding,
                vertical = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(MemoryPageTokens.cardSpacing),
        ) {
            items(section.memories, key = { memory -> memory.id }) { memory ->
                MemoryBigCard(
                    memory = memory,
                    onClick = { onMemoryClick(memory) },
                    onLongClick = { onMemoryLongClick(memory) },
                )
            }
        }
    }
}

/** 竖版大卡（168×224 r16）：封面 + 底部渐变蒙层 + 左下大标题艺术字（17sp Bold）+ 副行（12sp 80%）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryBigCard(
    memory: Memory,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surface)
    val title = memoryTitle(memory)
    val subtitle = memorySubtitle(memory)
    Box(
        modifier = Modifier
            .width(MemoryPageTokens.cardWidth)
            .height(MemoryPageTokens.cardHeight)
            .clip(RoundedCornerShape(MemoryPageTokens.cardCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "$title · $subtitle" },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(memory.coverUri)
                .size(512)
                .crossfade(false) // 红线：关闭交叉淡入淡出防 recycled bitmap 崩溃
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
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
                .padding(12.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
