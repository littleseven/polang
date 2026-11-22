package com.mamba.picme.features.gallery.organize

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.gallery.dedup.formatBytes

/**
 * 整理中心类目详情页（F1）：AI 预选网格 + 批量回收站清理。
 * 三态：Loading → 网格（副行统计 + 3 列勾选缩略图 + 底部删除 CTA）→ trashed 完成态（可 Undo）。
 * 系统授权经 TrashSessionController.pendingRequest 以 StartIntentSenderForResult 拉起。
 * 网格/删除栏/完成态组件与来源详情页共享（OrganizeSharedComponents.kt）。
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
                    state.trashed -> OrganizeCleanedContent(
                        trashedCount = state.trashedCount,
                        trashedBytes = state.trashedBytes,
                        lastTrashedUris = state.lastTrashedUris,
                    )
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
    val sections = remember(state.items) { state.items.toSections() }
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
            // 三段分组（spec §6.3）：建议删除（HIGH 非保护）/ 请确认（MEDIUM+LOW）/ 珍贵保护
            if (sections.suggested.isNotEmpty()) {
                item(key = "header_suggested", span = { GridItemSpan(maxLineSpan) }) {
                    OrganizeSectionHeader(
                        text = stringResource(R.string.org_section_suggested, sections.suggested.size)
                    )
                }
                items(
                    items = sections.suggested,
                    key = { entry -> entry.item.uri },
                    contentType = { "organize_thumb" }
                ) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = emptySet(),
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
            if (sections.review.isNotEmpty()) {
                item(key = "header_review", span = { GridItemSpan(maxLineSpan) }) {
                    OrganizeSectionHeader(
                        text = stringResource(R.string.org_section_review, sections.review.size)
                    )
                }
                items(
                    items = sections.review,
                    key = { entry -> entry.item.uri },
                    contentType = { "organize_thumb" }
                ) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = emptySet(),
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
            if (sections.protectedItems.isNotEmpty()) {
                item(key = "header_protected", span = { GridItemSpan(maxLineSpan) }) {
                    OrganizeSectionHeader(
                        text = stringResource(
                            R.string.org_section_protected, sections.protectedItems.size
                        ),
                        isProtected = true,
                    )
                }
                items(
                    items = sections.protectedItems,
                    key = { entry -> entry.item.uri },
                    contentType = { "organize_thumb" }
                ) { entry ->
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
