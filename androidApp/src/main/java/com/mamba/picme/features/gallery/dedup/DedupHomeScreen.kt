package com.mamba.picme.features.gallery.dedup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.VersionRole
import com.mamba.picme.domain.organize.OrganizeCategory

private val KeepGreen = Color(0xFF4CAF50)

/** 去重 2.0 主页：单路由内 Config → Scanning → Results → Cleaned 四态切换；组详情与保留规则为内部弹层。 */
@Composable
fun DedupHomeRoute(
    viewModel: DedupViewModel,
    /** 离页回调：仅服务扫描态「后台运行」按钮（根页无返回箭头，2026-09-06 导航统一）。 */
    onNavigateBack: () -> Unit,
    /** 整理中心 hub「Quick tidy up」（F2 已点亮：MainActivity 导航 swipe_review 路由）。 */
    onQuickTidy: () -> Unit = {},
    /** 整理中心 hub 类目卡点击（Task 5 已点亮：MainActivity 导航 organize_category/{category} 路由）。 */
    onOpenCategory: (OrganizeCategory) -> Unit = {},
    /** 嵌入整理+扫描合并页（OrganizeHomeRoute）时为 true：顶栏不再内置状态栏避让（外层胶囊条统一避让）。 */
    embedded: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val pendingTrash by viewModel.pendingTrash.collectAsState()
    val pendingRestore by viewModel.pendingRestore.collectAsState()
    val partialTrashNotice by viewModel.partialTrashNotice.collectAsState()
    val policy by viewModel.policy.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showKeepRules by remember { mutableStateOf(false) }
    var detailGroupId by remember { mutableStateOf<String?>(null) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // 部分拒绝一次性提示（B5）：VM 置位 → 消费 → 弹 snackbar
    val partialTrashMessage = stringResource(R.string.dedup_partial_trash_notice)
    LaunchedEffect(partialTrashNotice) {
        if (partialTrashNotice) {
            viewModel.consumePartialTrashNotice()
            snackbarHostState.showSnackbar(partialTrashMessage)
        }
    }

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

    LaunchedEffect(pendingTrash) {
        pendingTrash?.let { pending ->
            trashLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }
    LaunchedEffect(pendingRestore) {
        pendingRestore?.let { pending ->
            restoreLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    // 单根 Box 包裹整页：HorizontalPager 会把 page 的多个根节点沿主轴顺序平铺
    // （MeasuredPage 按 child 宽度累加 offset），详情页/预览层若作为 Scaffold 的兄弟
    // 根节点会被排到屏外不可见（点击「没反应」的表象）；Gallery/Person 页同为 Box 单根模式
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DedupTopBar(
                state = uiState,
                onCancelScan = { viewModel.cancelScan() },
                onOpenKeepRules = { showKeepRules = true },
                embedded = embedded,
            )
        },
        bottomBar = {
            // 底部栏自行吞导航栏 inset（M3 Scaffold 约定 topBar/bottomBar 各管各的）：
            // 外层 MainActivity 全局 0 insets，不加则 CTA 被三键虚拟导航遮挡
            Box(modifier = Modifier.navigationBarsPadding()) {
            when (val state = uiState) {
                is DedupUiState.Scanning -> DedupScanningBottomBar(
                    paused = state.paused,
                    onPauseResume = {
                        if (state.paused) viewModel.resumeScan() else viewModel.pauseScan()
                    },
                    onRunBackground = onNavigateBack,
                )
                is DedupUiState.Results -> {
                    // 底部 CTA 与 VM 删除流同一口径（tabBatchUris，spec §12）：仅当前 Tab 的
                    // batchEligible 组参与批量删除（SCENE 由底部提示代替 CTA）
                    val batchUris = viewModel.tabBatchUris(state)
                    val tabGroups = state.groups.filter { group -> group.level == state.selectedTab }
                    DedupResultsBottomBar(
                        isSceneTab = state.selectedTab == DedupLevel.SCENE,
                        deleteCount = batchUris.size,
                        deleteBytes = viewModel.batchReclaimBytes(tabGroups, batchUris),
                        onDelete = { viewModel.deleteSelected() },
                    )
                }
                is DedupUiState.Cleaned -> DedupCleanedBottomBar(
                    remainingCount = state.remainingGroups.size,
                    onContinue = { viewModel.continueWithRemaining() },
                    onDone = { viewModel.resetToConfig() },
                )
                is DedupUiState.Config -> Unit
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
                is DedupUiState.Config -> {
                    // 看板流仅 Config 态订阅：Scanning/Results/Cleaned 态不再挂着
                    // 全量 Room+MediaStore 合并聚合
                    val board by viewModel.organizeBoard.collectAsState()
                    // TODO(Task 12): DedupHubContent 切 OrganizeBoard 签名后恢复调用
                    // （DedupHomeHub.kt 仍引旧 CategoryStat API，Task 11 不动该文件；
                    // 形参 board 届时消费，当前暂存仅承接新类型）
                    // DedupHubContent(
                    //     config = state.config,
                    //     policy = policy,
                    //     board = board,
                    //     onQuickTidy = onQuickTidy,
                    //     onOpenCategory = onOpenCategory,
                    //     onStartScan = { config -> viewModel.startScan(config) },
                    //     onOpenKeepRules = { showKeepRules = true },
                    // )
                }
                is DedupUiState.Scanning -> DedupScanningContent(
                    state = state,
                    onOpenGroupDetail = { groupId -> detailGroupId = groupId },
                )
                is DedupUiState.Results -> DedupResultsContent(
                    state = state,
                    onSmartSelectAll = { viewModel.smartSelectAll() },
                    onSelectTab = { level -> viewModel.selectTab(level) },
                    onOpenGroupDetail = { groupId -> detailGroupId = groupId },
                )
                is DedupUiState.Cleaned -> DedupCleanedContent(
                    state = state,
                    onUndoAll = { viewModel.undoTrash() },
                )
            }
        }
    }

    if (showKeepRules) {
        KeepRulesSheet(
            viewModel = viewModel,
            onDismiss = { showKeepRules = false },
        )
    }
    detailGroupId?.let { groupId ->
        DedupGroupDetailPage(
            groupId = groupId,
            viewModel = viewModel,
            onDismiss = { detailGroupId = null },
            onPreview = { index -> previewIndex = index },
            onOpenKeepRules = { showKeepRules = true },
        )
    }
    // 全屏对比预览覆盖层：晚于详情页组合置顶；组经 getGroup 随 uiState 重组实时取，
    // 预览中「保留这张」改选后保留徽标即时刷新
    previewIndex?.let { index ->
        detailGroupId?.let { groupId ->
            val previewGroup = viewModel.getGroup(groupId)
            if (previewGroup != null) {
                DedupGroupPreviewOverlay(
                    group = previewGroup,
                    initialIndex = index,
                    editable = uiState is DedupUiState.Results,
                    onKeep = { uri -> viewModel.setKeep(groupId, uri) },
                    onDismiss = { previewIndex = null },
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupTopBar(
    state: DedupUiState,
    onCancelScan: () -> Unit,
    onOpenKeepRules: () -> Unit,
    embedded: Boolean = false,
) {
    TopAppBar(
        // 嵌入合并页时状态栏避让由外层胶囊条统一处理，顶栏 inset 归零防双避让
        windowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
        title = {
            Text(
                text = stringResource(
                    when (state) {
                        is DedupUiState.Scanning -> R.string.dedup_scanning_title
                        // Config 态已升级为整理中心 hub（dedup_title 键保留不删，向后兼容）
                        is DedupUiState.Config -> R.string.org_title
                        else -> R.string.dedup_title
                    }
                )
            )
        },
        // 根页无返回箭头（2026-09-06 导航统一）；离页经悬浮底 bar / 系统返回（回相册页）
        actions = {
            when (state) {
                is DedupUiState.Scanning -> TextButton(onClick = onCancelScan) {
                    Text(stringResource(R.string.cancel))
                }
                // hub（Config）与 Results 均提供保留规则入口（Tune 图标），行为不变
                is DedupUiState.Config, is DedupUiState.Results -> IconButton(onClick = onOpenKeepRules) {
                    Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.dedup_keep_rule))
                }
                else -> Unit
            }
        }
    )
}

// ---------- Config ----------

// Config 态已升级为整理中心 hub：见 DedupHomeHub.kt（DedupHubContent / DedupScanOptions /
// DedupLevelRow 均迁往该文件；dedup_title 字符串键保留不删，向后兼容）。

// ---------- Scanning ----------

@Composable
private fun DedupScanningContent(
    state: DedupUiState.Scanning,
    onOpenGroupDetail: (String) -> Unit,
) {
    val progress = if (state.total > 0) {
        state.scanned.toFloat() / state.total.toFloat()
    } else {
        0f
    }
    val percent = (progress * 100).toInt()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.dedup_phase_format,
                            state.phaseIndex,
                            state.phaseCount,
                            stringResource(dedupLevelLabelRes(state.phase))
                        ),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.dedup_progress_percent, percent),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.dedup_scan_progress, state.scanned, state.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 三段阶段条：完成=绿 / 进行中=primary / 未开始=surfaceVariant
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(state.phaseCount) { index ->
                    val color = when {
                        index < state.phaseIndex - 1 -> KeepGreen
                        index == state.phaseIndex - 1 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                }
            }

            Text(
                text = stringResource(R.string.dedup_live_found, state.foundGroups.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = state.foundGroups,
                key = { group -> group.id }
            ) { group ->
                DedupScanningGroupRow(
                    group = group,
                    onClick = { onOpenGroupDetail(group.id) },
                    // 新组前插时平滑落位，避免整列瞬移跳闪
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

/**
 * 扫描中实时发现组：header（级别 badge + meta + chevron）+ 两张正方形大图预览。
 * 高度即最终态（与组详情/结果卡同量级），消除「紧凑小行 → 大卡」的高度跳变；
 * 双图权重等分保证任意组高度恒定，插入动画期间布局稳定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupScanningGroupRow(
    group: DedupGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LevelBadge(level = group.level)
                Text(
                    text = dedupGroupMetaText(group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.members.take(2).forEach { member ->
                    DedupThumb(
                        uri = member.uri,
                        isKept = member.uri == group.keepUri,
                        role = member.role,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                    )
                }
                // 单成员兜底：占位保持双格几何，组间高度一致
                if (group.members.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DedupScanningBottomBar(
    paused: Boolean,
    onPauseResume: () -> Unit,
    onRunBackground: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPauseResume,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = stringResource(
                    if (paused) R.string.dedup_resume else R.string.dedup_pause
                )
            )
        }
        TextButton(onClick = onRunBackground) {
            Text(stringResource(R.string.dedup_run_background))
        }
    }
}

// ---------- Results ----------

@Composable
private fun DedupResultsContent(
    state: DedupUiState.Results,
    onSmartSelectAll: () -> Unit,
    onSelectTab: (DedupLevel) -> Unit,
    onOpenGroupDetail: (String) -> Unit,
) {
    // Hero 为全局口径：全部 batchEligible 组合计（SCENE/未预选组不计入「可释放」）；
    // 底部 CTA 与全选 chip 为当前 Tab 口径（tabBatchUris，spec §12）
    val batchGroups = state.groups.filter { group -> group.batchEligible }
    val totalReclaim = batchGroups.sumOf { group -> group.reclaimBytes }
    val filteredGroups = state.groups.filter { group -> group.level == state.selectedTab }
    val selectedTabIndex = DedupLevel.entries.indexOf(state.selectedTab)

    Column(modifier = Modifier.fillMaxSize()) {
        // Hero 统计区：左侧大字「可释放 X」+ 副文「N 组重复照片」；右侧「全选本类」chip 单行不折
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.dedup_hero_reclaim,
                        formatBytes(totalReclaim)
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.dedup_hero_groups, batchGroups.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 「全选本类」chip 仅作用于当前 Tab；L3 场景相似不参与批量操作（spec §4），不展示
            if (state.selectedTab != DedupLevel.SCENE) {
                FilterChip(
                    selected = false,
                    onClick = onSmartSelectAll,
                    label = {
                        Text(
                            text = stringResource(R.string.dedup_smart_select_tab),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
            }
        }

        // 级别 tab：下划线式 ScrollableTabRow，按内容宽度排布，多语言超长时横向滚动
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent,
            edgePadding = 16.dp,
            // 覆盖默认 90.dp 最小宽，让 tab 贴合内容；48.dp 保住最小触控宽度
            minTabWidth = 48.dp,
            divider = {},
            indicator = {
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(selectedTabIndex = selectedTabIndex)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        ) {
            DedupLevel.entries.forEach { level ->
                DedupLevelTab(
                    level = level,
                    count = state.groups.count { group -> group.level == level },
                    selected = state.selectedTab == level,
                    onSelect = onSelectTab
                )
            }
        }

        // TabRow 与组列表之间的细分隔线
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )

        if (filteredGroups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.dedup_no_groups),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = filteredGroups,
                    key = { group -> group.id }
                ) { group ->
                    DedupGroupCard(
                        group = group,
                        policy = state.policy,
                        onOpenDetail = { onOpenGroupDetail(group.id) },
                        // tab 切换过滤时组卡平滑重排
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

/** 级别 tab：label 14sp + count 11sp 横排；选中态 primary 强调，未选中 onSurfaceVariant（count 再弱一档）。 */
@Composable
private fun DedupLevelTab(
    level: DedupLevel,
    count: Int,
    selected: Boolean,
    onSelect: (DedupLevel) -> Unit,
) {
    Tab(
        selected = selected,
        onClick = { onSelect(level) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(dedupLevelLabelRes(level)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    maxLines = 1
                )
            }
        },
        // 文字色显式指定；此处只为让涟漪/语义色跟随选中态
        selectedContentColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun DedupResultsBottomBar(
    isSceneTab: Boolean,
    deleteCount: Int,
    deleteBytes: Long,
    onDelete: () -> Unit,
) {
    // L3 场景相似不参与任何批量操作（spec §4 安全约束）、当前 Tab 无 batchEligible
    // 待删项（如全是未预选截图组）时：提示代替 CTA，不显示「删除本类 0 张」禁用按钮
    val hintRes = when {
        isSceneTab -> R.string.dedup_scene_batch_hint
        deleteCount == 0 -> R.string.dedup_tab_batch_empty
        else -> null
    }
    if (hintRes != null) {
        Text(
            text = stringResource(hintRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
        return
    }
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
            enabled = deleteCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = stringResource(
                    R.string.dedup_delete_cta_scoped,
                    deleteCount,
                    formatBytes(deleteBytes)
                )
            )
        }
        Text(
            text = stringResource(R.string.dedup_recycle_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- Cleaned ----------

@Composable
private fun DedupCleanedContent(
    state: DedupUiState.Cleaned,
    onUndoAll: () -> Unit,
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
            tint = KeepGreen
        )
        Text(
            text = stringResource(R.string.dedup_cleaned_title, state.deletedCount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.dedup_cleaned_sub, formatBytes(state.reclaimedBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    state.trashedUris.take(3).forEach { uri ->
                        DedupThumb(
                            uri = uri,
                            isKept = false,
                            role = VersionRole.UNKNOWN,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    val extra = state.trashedUris.size - 3
                    if (extra > 0) {
                        Text(
                            text = stringResource(R.string.dedup_more_count, extra),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = onUndoAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dedup_undo_all))
                }
            }
        }
    }
}

@Composable
private fun DedupCleanedBottomBar(
    remainingCount: Int,
    onContinue: () -> Unit,
    onDone: () -> Unit,
) {
    if (remainingCount > 0) {
        // 按类型细分删除后还有其他组：主操作是「继续整理」回 Results，「完成」降为次操作
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.dedup_continue_remaining, remainingCount))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.done))
                }
                // 查看回收站：V1 占位，后续接系统回收站入口
                TextButton(onClick = { }) {
                    Text(stringResource(R.string.dedup_view_recycle))
                }
            }
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onDone,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.done))
        }
        // 查看回收站：V1 占位，后续接系统回收站入口
        TextButton(onClick = { }) {
            Text(stringResource(R.string.dedup_view_recycle))
        }
    }
}

// ---------- 资源映射 ----------

internal fun dedupLevelIcon(level: DedupLevel): ImageVector = when (level) {
    DedupLevel.EXACT -> Icons.Rounded.ContentCopy
    DedupLevel.VISUAL -> Icons.Rounded.Image
    DedupLevel.SCENE -> Icons.Rounded.PhotoLibrary
}

internal fun dedupLevelLabelRes(level: DedupLevel): Int = when (level) {
    DedupLevel.EXACT -> R.string.dedup_level_exact
    DedupLevel.VISUAL -> R.string.dedup_level_visual
    DedupLevel.SCENE -> R.string.dedup_level_scene
}

internal fun dedupLevelDescRes(level: DedupLevel): Int = when (level) {
    DedupLevel.EXACT -> R.string.dedup_scale_exact_desc
    DedupLevel.VISUAL -> R.string.dedup_scale_visual_desc
    DedupLevel.SCENE -> R.string.dedup_scale_scene_desc
}
