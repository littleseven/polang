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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.gallery.dedup.formatBytes

/**
 * 来源详情页（按 App 浏览媒体 + 回收站删除，Agent 清理 M1 Task 7.5）：与类目详情页同构，
 * 差异 = 无 AI 预选（selected 默认空集，全靠用户手选）+ 两段分组（全部媒体日期降序 /
 * 珍贵保护不可选）。删除走 TrashSessionController（系统确认 + undo）。
 */
@Suppress("LongMethod") // 待重构：三态分支（Loading/网格/完成态）可抽子组合函数（同类目页）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeSourceScreen(
    viewModel: OrganizeSourceViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
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

    val partialMessage = stringResource(R.string.org_partial_trash)
    LaunchedEffect(partialNotice) {
        if (partialNotice) {
            viewModel.trashController.consumePartialNotice()
            snackbarHostState.showSnackbar(partialMessage)
        }
    }

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
                title = {
                    Text(
                        stringResource(
                            R.string.org_source_detail_title,
                            stringResource(appSourceLabelRes(viewModel.source))
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 底部栏自行吞导航栏 inset（外层 MainActivity 全局 0 insets）
            Box(modifier = Modifier.navigationBarsPadding()) {
                when (val state = uiState) {
                    is OrganizeSourceUiState.Ready -> when {
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
                    OrganizeSourceUiState.Loading -> Unit
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
                OrganizeSourceUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
                is OrganizeSourceUiState.Ready -> when {
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
                    else -> OrganizeSourceGridContent(
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
private fun OrganizeSourceGridContent(
    state: OrganizeSourceUiState.Ready,
    onToggle: (String) -> Unit,
) {
    val sections = remember(state.items) { state.items.toSourceSections() }
    Column(modifier = Modifier.fillMaxSize()) {
        // 副行：左「N items · X MB」（该来源全量）；无 AI 预选，右侧不出选中计数
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
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 两段分组：全部媒体（日期降序）/ 珍贵保护（不可选，VM toggle 静默拒绝）
            if (sections.recentFirst.isNotEmpty()) {
                item(key = "header_recent", span = { GridItemSpan(maxLineSpan) }) {
                    OrganizeSectionHeader(
                        text = stringResource(R.string.org_section_all_media, sections.recentFirst.size)
                    )
                }
                items(
                    items = sections.recentFirst,
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
