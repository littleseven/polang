package com.mamba.picme.features.gallery.organize

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.organize.ProtectReason
import com.mamba.picme.features.gallery.dedup.formatBytes

/** 品牌渐变（青玉）：完成态大数字 / Undo 主按钮与 hub 同源。 */
private val orgBrandGradient: Brush
    get() = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
    )

private val CleanGreen = Color(0xFF4CAF50)

/**
 * 整理中心类目详情页（F1）：AI 预选网格 + 批量回收站清理。
 * 三态：Loading → 网格（副行统计 + 3 列勾选缩略图 + 底部删除 CTA）→ trashed 完成态（可 Undo）。
 * 系统授权经 TrashSessionController.pendingRequest 以 StartIntentSenderForResult 拉起。
 */
@Suppress("LongMethod") // 待重构：三态分支（Loading/网格/完成态）可抽子组合函数
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeCategoryScreen(
    viewModel: OrganizeCategoryViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val aiPreselectEnabled by viewModel.aiPreselectEnabled.collectAsState()
    val pendingRequest by viewModel.trashController.pendingRequest.collectAsState()
    val partialNotice by viewModel.trashController.partialNotice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        viewModel.onTrashResult(result.resultCode == Activity.RESULT_OK)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        viewModel.onRestoreResult(result.resultCode == Activity.RESULT_OK)
    }

    // 授权拉起：token 即 IntentSender（DedupTrashBackend 生产适配），isRestore 分流 launcher
    LaunchedEffect(pendingRequest) {
        pendingRequest?.let { pending ->
            val request = IntentSenderRequest.Builder(pending.token as IntentSender).build()
            if (pending.isRestore) {
                restoreLauncher.launch(request)
            } else {
                trashLauncher.launch(request)
            }
        }
    }

    // 部分拒绝一次性提示（同 DedupHomeScreen 模式：置位 → 消费 → snackbar）
    val partialMessage = stringResource(R.string.org_partial_trash)
    LaunchedEffect(partialNotice) {
        if (partialNotice) {
            viewModel.trashController.consumePartialNotice()
            snackbarHostState.showSnackbar(partialMessage)
        }
    }

    // 回收站不可用（API<30）/ token 构建失败：errorEvent 一次性置位 → 消费 → snackbar
    val trashError by viewModel.trashController.errorEvent.collectAsState()
    val unsupportedMessage = stringResource(R.string.org_trash_unsupported)
    LaunchedEffect(trashError) {
        if (trashError) {
            viewModel.trashController.consumeErrorEvent()
            snackbarHostState.showSnackbar(unsupportedMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(organizeCategoryLabelRes(viewModel.category))) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    val ready = uiState as? OrganizeCategoryUiState.Ready
                    if (ready != null && !ready.trashed) {
                        TextButton(onClick = { viewModel.setAiPreselect(!aiPreselectEnabled) }) {
                            Text(
                                text = stringResource(
                                    if (aiPreselectEnabled) {
                                        R.string.org_ai_preselect_on
                                    } else {
                                        R.string.org_ai_preselect_off
                                    }
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            // 底部栏自行吞导航栏 inset（外层 MainActivity 全局 0 insets）
            Box(modifier = Modifier.navigationBarsPadding()) {
                when (val state = uiState) {
                    is OrganizeCategoryUiState.Ready -> when {
                        state.trashed -> OrganizeUndoBar(onUndo = { viewModel.undoLastTrash() })
                        state.items.isNotEmpty() -> OrganizeDeleteBar(
                            selectedCount = state.selected.size,
                            selectedBytes = state.items
                                .filter { entry -> entry.item.uri in state.selected }
                                .sumOf { entry -> entry.item.sizeBytes },
                            onDelete = { viewModel.deleteSelected() },
                        )
                        else -> Unit
                    }
                    OrganizeCategoryUiState.Loading -> Unit
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (val state = uiState) {
                OrganizeCategoryUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
                is OrganizeCategoryUiState.Ready -> when {
                    state.trashed -> OrganizeCleanedContent(state = state)
                    state.items.isEmpty() -> Text(
                        text = stringResource(R.string.org_empty_category),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> OrganizeGridContent(
                        state = state,
                        onToggle = { uri -> viewModel.toggle(uri) },
                    )
                }
            }
        }
    }
}

// ---------- 网格态 ----------

@Composable
private fun OrganizeGridContent(
    state: OrganizeCategoryUiState.Ready,
    onToggle: (String) -> Unit,
) {
    val sections = state.items.toSections()
    Column(modifier = Modifier.fillMaxSize()) {
        // 副行：左「N items · X MB」（该类目全量）+ 右「N AI-preselected」（当前选中）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.org_cat_meta,
                    state.items.size,
                    formatBytes(state.items.sumOf { entry -> entry.item.sizeBytes })
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.org_ai_preselected, state.selected.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 三段分组（spec §6.3）：建议删除（HIGH 非保护）/ 请确认（MEDIUM+LOW 非保护）/ 珍贵保护
            if (sections.suggested.isNotEmpty()) {
                item(key = "header_suggested", span = { GridItemSpan(3) }) {
                    OrganizeSectionHeader(
                        text = stringResource(R.string.org_section_suggested, sections.suggested.size)
                    )
                }
                items(items = sections.suggested, key = { entry -> entry.item.uri }) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = emptySet(),
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
            if (sections.review.isNotEmpty()) {
                item(key = "header_review", span = { GridItemSpan(3) }) {
                    OrganizeSectionHeader(
                        text = stringResource(R.string.org_section_review, sections.review.size)
                    )
                }
                items(items = sections.review, key = { entry -> entry.item.uri }) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = emptySet(),
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
            if (sections.protectedItems.isNotEmpty()) {
                item(key = "header_protected", span = { GridItemSpan(3) }) {
                    OrganizeSectionHeader(
                        text = stringResource(
                            R.string.org_section_protected, sections.protectedItems.size
                        ),
                        isProtected = true,
                    )
                }
                items(items = sections.protectedItems, key = { entry -> entry.item.uri }) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = entry.protectReasons,
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
        }
    }
}

/** 三段分组标题行（GridItemSpan 整行）；protected 段用 tertiary 色强调价值保护。 */
@Composable
private fun OrganizeSectionHeader(text: String, isProtected: Boolean = false) {
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
private fun OrganizeThumb(
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
private fun OrganizeDeleteBar(
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

// ---------- 完成态 ----------

/** 完成态：✅ 圆标 + All clean! + 回收预览卡（前 3 缩略图 + 多余计数）；Undo 在底部栏。 */
@Suppress("LongMethod") // 待重构：预览卡可抽子组合函数
@Composable
private fun OrganizeCleanedContent(state: OrganizeCategoryUiState.Ready) {
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
            text = stringResource(R.string.org_cleaned_meta, state.trashedCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.org_cleaned_freed, formatBytes(state.trashedBytes)),
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
                    state.lastTrashedUris.take(3).forEach { uri ->
                        CleanedThumb(uri = uri)
                    }
                    val extra = state.lastTrashedUris.size - 3
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
private fun OrganizeUndoBar(onUndo: () -> Unit) {
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
