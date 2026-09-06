@file:OptIn(ExperimentalLayoutApi::class)

package com.mamba.picme.features.gallery.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.domain.aesthetic.AestheticScoreWorker
import com.mamba.picme.domain.tag.TagCategory
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.util.permission.BackgroundScanGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * TAG 生成精细控制子页面
 *
 * 显示三阶段混合管道的各阶段进度和数据库统计。
 * 语义编码已内联到人脸检测阶段，不再作为独立阶段显示。
 * 所有操作通过 TagGenerationService → TagScanOrchestrator 统一管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagGenerationControlScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTagViewer: () -> Unit = {},
    useOpencl: Boolean,
    onUseOpenclChange: (Boolean) -> Unit,
    /** 嵌入整理+扫描合并页（OrganizeHomeRoute）时为 true：顶栏不再内置状态栏避让（外层胶囊条统一避让）。 */
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    // ── 通过 AppContainer 观察 TAG 生成状态（Service 内部分发） ───
    val sessionProgress by app.container.tagGenerationSessionProgress.collectAsState()
    val isScanning by app.container.tagGenerationIsScanning.collectAsState()
    // 附属打分器（美学/人脸画质）进度：非会话制，null = 空闲；顶部进度卡优先显示活跃任务
    val aestheticProgress by app.container.aestheticScoreWorker.progress.collectAsState()
    val currentState = sessionProgress?.state
    val isRunning = currentState == ScanSessionState.RUNNING
    val isPausing = currentState == ScanSessionState.PAUSING
    val isPaused = currentState == ScanSessionState.PAUSED
    val isCancelling = currentState == ScanSessionState.CANCELLING
    val isCancelled = currentState == ScanSessionState.CANCELLED
    val snackbarHostState = remember { SnackbarHostState() }

    // 启动前台 Service（Intent 驱动，无外部 Handle）
    LaunchedEffect(Unit) {
        TagGenerationService.startForeground(context)
    }

    // 数据库统计（每次进入时刷新）
    var totalMedia by remember { mutableIntStateOf(0) }
    var withFace by remember { mutableIntStateOf(0) }
    var withLabels by remember { mutableIntStateOf(0) }
    var withSemantic by remember { mutableIntStateOf(0) }
    var personCount by remember { mutableIntStateOf(0) }
    var namedPersonCount by remember { mutableIntStateOf(0) }
    var embeddingCount by remember { mutableIntStateOf(0) }
    var remainingPass1 by remember { mutableIntStateOf(0) }
    var remainingPass3 by remember { mutableIntStateOf(0) }
    var photoCount by remember { mutableIntStateOf(0) }
    var aestheticScored by remember { mutableIntStateOf(0) }

    // 精细控制：类别 / 时间范围 / 模式
    var selectedCategories by remember { mutableStateOf(setOf<TagCategory>()) }
    var selectedTimeRange by remember { mutableStateOf(TimeRangePreset.ALL) }
    var fullRegenerateMode by remember { mutableStateOf(false) }

    // 阶段操作底部弹层 / 全量重处理二次确认
    var stageSheetTarget by remember { mutableStateOf<TagStage?>(null) }
    var pendingFullStage by remember { mutableStateOf<TagStage?>(null) }

    // 刷新统计：统一通过 TagScanOrchestrator.getDbStats(db) 获取，
    // 不依赖 Service/Orchestrator 实例，进入页面即可立即显示。
    fun refreshStats() {
        coroutineScope.launch {
            try {
                android.util.Log.d("TagGenControl", "refreshStats() called")
                val stats = TagScanOrchestrator.getDbStats(db)
                android.util.Log.d("TagGenControl", "stats=$stats")
                totalMedia = stats.totalMedia
                withFace = stats.withFace
                withLabels = stats.withLabels
                withSemantic = stats.withSemantic
                personCount = stats.personCount
                namedPersonCount = stats.namedPersonCount
                embeddingCount = stats.faceEmbeddingCount
                remainingPass1 = stats.remainingForPass1
                remainingPass3 = stats.remainingForPass3
                photoCount = stats.photoCount
                aestheticScored = stats.aestheticScoredCount
            } catch (e: Exception) {
                android.util.Log.e("TagGenControl", "refreshStats failed", e)
            }
        }
    }

    // 显示扫描完成通知
    LaunchedEffect(sessionProgress?.messages?.lastOrNull()?.text) {
        val msg = sessionProgress?.messages?.lastOrNull()?.text ?: return@LaunchedEffect
        if (sessionProgress?.state == ScanSessionState.COMPLETED) {
            refreshStats()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // 初始加载统计
    LaunchedEffect(Unit) {
        android.util.Log.d("TagGenControl", "initial refreshStats()")
        refreshStats()
    }

    // 轮询更新数据库累计统计（每秒刷新，不依赖 isScanning 状态，便于诊断）
    LaunchedEffect(Unit) {
        android.util.Log.d("TagGenControl", "poll loop started")
        while (true) {
            android.util.Log.d("TagGenControl", "poll tick, isScanning=${app.container.tagGenerationIsScanning.value}")
            refreshStats()
            delay(1000)
        }
    }

    // ── 后台保活引导(HyperOS 等会冻结后台进程,引导用户加白名单/开通知/自启动) ──
    var guardIssues by remember { mutableStateOf<List<BackgroundScanGuard.Issue>>(emptyList()) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun startScanWithGuard(startAction: () -> Unit) {
        val issues = runCatching { BackgroundScanGuard.diagnose(context.applicationContext) }
            .getOrDefault(emptyList())
        if (issues.isNotEmpty() && BackgroundScanGuard.shouldShowDialog(context.applicationContext)) {
            guardIssues = issues
            pendingStart = startAction
        } else {
            startAction()
        }
    }

    // 阶段操作：弹层选择「仅处理新增 / 全部重新处理」，全量需二次确认
    fun startStage(stage: TagStage, full: Boolean) {
        refreshStats()
        val intent = when (stage) {
            TagStage.FACE ->
                if (full) TagGenerationService.intentScanPass1Full(context)
                else TagGenerationService.intentScanPass1(context)
            TagStage.PEOPLE ->
                if (full) TagGenerationService.intentScanPass2Full(context)
                else TagGenerationService.intentScanPass2(context)
            TagStage.CONTENT ->
                if (full) TagGenerationService.intentScanPass3Full(context)
                else TagGenerationService.intentScanPass3(context)
            TagStage.QUALITY ->
                if (full) TagGenerationService.intentScoreAestheticFull(context)
                else TagGenerationService.intentScoreAesthetic(context)
        }
        context.startForegroundService(intent)
    }

    @Composable
    fun stageTitle(stage: TagStage): String = when (stage) {
        TagStage.FACE -> stringResource(R.string.tag_pass_title_face)
        TagStage.PEOPLE -> stringResource(R.string.tag_pass_title_cluster)
        TagStage.CONTENT -> stringResource(R.string.tag_pass_title_content)
        TagStage.QUALITY -> stringResource(R.string.tag_pass_title_aesthetic)
    }

    if (guardIssues.isNotEmpty()) {
        BackgroundScanGuardDialog(
            issues = guardIssues,
            onGoSettings = {
                val first = guardIssues.first()
                guardIssues = emptyList()
                pendingStart = null
                first.openFix(context)
            },
            onContinue = {
                val pending = pendingStart
                guardIssues = emptyList()
                pendingStart = null
                pending?.invoke()
            },
            onDontRemind = {
                BackgroundScanGuard.doNotShowAgain(context.applicationContext)
                guardIssues = emptyList()
                pendingStart = null
            }
        )
    }

    // ── 阶段操作弹层（点按阶段行弹出）+ 全量重处理二次确认 ────────
    stageSheetTarget?.let { stage ->
        StageActionSheet(
            title = stageTitle(stage),
            onDismiss = { stageSheetTarget = null },
            onProcessNew = { startStage(stage, full = false) },
            onReprocessAll = { pendingFullStage = stage }
        )
    }
    pendingFullStage?.let { stage ->
        AlertDialog(
            onDismissRequest = { pendingFullStage = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            title = { Text(stringResource(R.string.tag_stage_full_confirm_title)) },
            text = { Text(stringResource(R.string.tag_stage_full_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    startStage(stage, full = true)
                    pendingFullStage = null
                }) {
                    Text(stringResource(R.string.tag_stage_full_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFullStage = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.gallery_settings)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) },
                includeStatusBarPadding = !embedded
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Library stats（置顶：用户最关心的数据）──
            SectionHeader(title = stringResource(R.string.tag_section_library))
            StatsCard(
                totalMedia = totalMedia,
                withFace = withFace,
                withLabels = withLabels,
                withSemantic = withSemantic,
                personCount = personCount,
                namedPersonCount = namedPersonCount,
                embeddingCount = embeddingCount,
                onNavigateToTagViewer = onNavigateToTagViewer
            )

            // ── Scan ──
            SectionHeader(title = stringResource(R.string.tag_section_scan))

            // ── 当前任务进度卡片（统一槽位）─────────────────────
            // 扫描会话活跃时优先显示扫描进度（美学打分此时已被 Service 互斥取消，
            // 此处再兜底防竞态）；空闲时美学评分运行则显示打分进度，否则显示会话终态。
            // 美学评分非会话制（不进 TagScanOrchestrator），会话卡片只反映扫描本身。
            val scanActive = isScanning
            AnimatedVisibility(visible = aestheticProgress != null && !scanActive) {
                aestheticProgress?.let { AestheticProgressCard(it) }
            }
            AnimatedVisibility(visible = sessionProgress != null && (scanActive || aestheticProgress == null)) {
                sessionProgress?.let { ScanProgressCard(it) }
            }

            // ── 会话控制（扫描活跃时显示） ────────────────
            AnimatedVisibility(visible = isRunning || isPausing || isPaused) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when {
                            isRunning -> {
                                OutlinedButton(
                                    onClick = {
                                        context.startForegroundService(TagGenerationService.intentPause(context))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Pause, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.pause))
                                }
                            }
                            isPausing -> {
                                OutlinedButton(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Pause, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.tag_scan_pausing))
                                }
                            }
                            isPaused -> {
                                Button(
                                    onClick = {
                                        context.startForegroundService(TagGenerationService.intentResume(context))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.resume))
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                context.startForegroundService(TagGenerationService.intentCancel(context))
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Cancel, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.cancel))
                        }

                        if ((sessionProgress?.failed ?: 0) > 0) {
                            OutlinedButton(
                                onClick = {
                                    context.startForegroundService(TagGenerationService.intentRetryFailed(context))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }

            // ── 空闲时的扫描操作卡（大按钮区分 新增/全量）──
            AnimatedVisibility(visible = !isRunning && !isPausing && !isPaused) {
                ScanActionCard(
                    totalMedia = totalMedia,
                    pendingCount = remainingPass1 + remainingPass3,
                    lastSession = sessionProgress,
                    onScanNew = {
                        refreshStats()
                        startScanWithGuard {
                            context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                        }
                    },
                    onRescanAll = {
                        refreshStats()
                        startScanWithGuard {
                            context.startForegroundService(TagGenerationService.intentScanAll(context))
                        }
                    }
                )
            }

            // ── 分阶段（点按行弹出操作弹层，避免增量/全量误触）──
            SectionHeader(
                title = stringResource(R.string.tag_pass_control_title),
                hint = stringResource(R.string.tag_stages_hint)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    StageRow(
                        icon = Icons.Rounded.Face,
                        iconTint = Color(0xFFFF7EB0),
                        title = stringResource(R.string.tag_pass_title_face),
                        description = stringResource(R.string.tag_pass_desc_face),
                        trailing = stagePercentText(tagPassProgress(totalMedia, remainingPass1)),
                        onClick = { stageSheetTarget = TagStage.FACE }
                    )
                    StageRow(
                        icon = Icons.Rounded.Person,
                        iconTint = Color(0xFF9B8CFF),
                        title = stringResource(R.string.tag_pass_title_cluster),
                        description = stringResource(R.string.tag_pass_desc_cluster),
                        trailing = if (personCount > 0) "$personCount" else "—",
                        onClick = { stageSheetTarget = TagStage.PEOPLE }
                    )
                    StageRow(
                        icon = Icons.Rounded.Label,
                        iconTint = Color(0xFF22D3EE),
                        title = stringResource(R.string.tag_pass_title_content),
                        description = stringResource(R.string.tag_pass_desc_content),
                        trailing = stagePercentText(tagPassProgress(totalMedia, remainingPass3)),
                        onClick = { stageSheetTarget = TagStage.CONTENT }
                    )
                    StageRow(
                        icon = Icons.Rounded.Star,
                        iconTint = Color(0xFF4ADE80),
                        title = stringResource(R.string.tag_pass_title_aesthetic),
                        description = stringResource(R.string.tag_pass_desc_aesthetic),
                        trailing = stagePercentText(tagPassProgress(photoCount, photoCount - aestheticScored)),
                        onClick = { stageSheetTarget = TagStage.QUALITY }
                    )
                }
            }

            // ── 精细控制：按类别 / 时间范围重新生成 ──────────
            SectionHeader(title = stringResource(R.string.tag_fine_control_title))
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

                    Text(
                        stringResource(R.string.tag_select_categories),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CategoryChip(
                            label = stringResource(R.string.tag_category_face),
                            selected = TagCategory.FACE in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.FACE)
                            }
                        )
                        CategoryChip(
                            label = stringResource(R.string.tag_category_scene),
                            selected = TagCategory.SCENE in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.SCENE)
                            }
                        )
                        CategoryChip(
                            label = stringResource(R.string.tag_category_activity),
                            selected = TagCategory.ACTIVITY in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.ACTIVITY)
                            }
                        )
                        CategoryChip(
                            label = stringResource(R.string.tag_category_objects),
                            selected = TagCategory.OBJECTS in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.OBJECTS)
                            }
                        )
                        CategoryChip(
                            label = stringResource(R.string.tag_category_tags),
                            selected = TagCategory.TAGS in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.TAGS)
                            }
                        )
                        CategoryChip(
                            label = stringResource(R.string.tag_category_summary),
                            selected = TagCategory.SUMMARY in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.SUMMARY)
                            }
                        )
                    }

                    Text(
                        stringResource(R.string.tag_time_range),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TimeRangePreset.entries.forEach { preset ->
                            CategoryChip(
                                label = stringResource(preset.labelRes),
                                selected = selectedTimeRange == preset,
                                onClick = { selectedTimeRange = preset }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.tag_overwrite_existing),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.tag_overwrite_existing_desc),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TagSwitch(
                            checked = fullRegenerateMode,
                            onCheckedChange = { checked -> fullRegenerateMode = checked }
                        )
                    }

                    GradientPillButton(
                        text = stringResource(R.string.tag_regenerate_selected),
                        icon = Icons.Rounded.Tune,
                        height = 44.dp,
                        cornerRadius = 22.dp,
                        fontSize = 14.sp,
                        onClick = {
                            refreshStats()
                            val categories = selectedCategories.ifEmpty { TagCategory.ALL }
                            val startTimeMs = selectedTimeRange.startTimeMs
                            context.startForegroundService(
                                TagGenerationService.intentRegenerateCategories(
                                    context = context,
                                    categories = categories.map { it.name },
                                    startTimeMs = startTimeMs,
                                    fullMode = fullRegenerateMode
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── TAG 打标 GPU 加速（页尾单行卡，设计稿 gallery/tag_control CardTagAccel）──
            TagAccelCard(
                useOpencl = useOpencl,
                onUseOpenclChange = onUseOpenclChange
            )

            // ── 后台保活缺失项提示(非阻断,点击跳设置) ────────
            BackgroundScanGuardBanner()
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ScanProgressCard(progress: TagScanSessionProgress) {
    val isScanning = progress.state in setOf(
        ScanSessionState.RUNNING,
        ScanSessionState.PAUSING,
        ScanSessionState.CANCELLING
    )
    val stateText = when (progress.state) {
        ScanSessionState.RUNNING -> stringResource(R.string.tag_scan_state_running)
        ScanSessionState.PAUSING -> stringResource(R.string.tag_scan_state_pausing)
        ScanSessionState.PAUSED -> stringResource(R.string.tag_scan_state_paused)
        ScanSessionState.CANCELLING -> stringResource(R.string.tag_scan_state_cancelling)
        ScanSessionState.CANCELLED -> stringResource(R.string.tag_scan_state_cancelled)
        ScanSessionState.COMPLETED -> stringResource(R.string.tag_scan_state_completed)
        ScanSessionState.IDLE -> stringResource(R.string.tag_scan_state_idle)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (progress.state) {
                ScanSessionState.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
                ScanSessionState.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        Icons.Rounded.Info,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                val subtitle = when {
                    progress.state == ScanSessionState.CANCELLING -> stringResource(R.string.tag_scan_waiting_task)
                    progress.state == ScanSessionState.CANCELLED -> ""
                    progress.state == ScanSessionState.COMPLETED -> ""
                    progress.currentPass == null -> stringResource(R.string.tag_scan_preparing)
                    else -> passDisplayName(progress.currentPass)
                }
                Text(
                    text = if (subtitle.isNotEmpty()) "$stateText · $subtitle" else stateText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.tag_scan_progress_line,
                    progress.processed,
                    progress.total,
                    progress.pending,
                    progress.failed
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (progress.estimatedRemainingMs != null && isScanning) {
                Text(
                    stringResource(R.string.tag_scan_eta, formatDuration(progress.estimatedRemainingMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            if (progress.messages.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    progress.messages.last().text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun AestheticProgressCard(progress: AestheticScoreWorker.AestheticProgress) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.tag_aesthetic_running),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.tag_aesthetic_progress, progress.processed, progress.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun passDisplayName(pass: TagScanPass?): String = when (pass) {
    TagScanPass.FACE_DETECTION -> stringResource(R.string.tag_pass_step_face)
    TagScanPass.DBSCAN -> stringResource(R.string.tag_pass_step_cluster)
    TagScanPass.IMAGE_TAGGING -> stringResource(R.string.tag_pass_step_content)
    TagScanPass.MOBILE_CLIP_ENCODING -> stringResource(R.string.tag_pass_step_semantic)
    null -> stringResource(R.string.tag_scan_preparing)
}

/** 区块标题行：左侧标题 + 可选右侧提示（设计稿 11sp 分区标签）。 */
@Composable
private fun SectionHeader(title: String, hint: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hint != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 品牌渐变（青玉）：设计稿按钮/大数字同源。 */
private val tagBrandGradient: Brush
    get() = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
    )

/** 渐变胶囊按钮（设计稿 Scan new / Regenerate）：渐变底 + 白色图标与文字。 */
@Composable
private fun GradientPillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    height: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(tagBrandGradient)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = Color.White)
            Text(text, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/** 描边胶囊按钮（设计稿 Rescan all）：outlineVariant 描边 + 次级文字。 */
@Composable
private fun OutlinedPillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 设计稿开关：44×26 r13，关=surfaceVariant 底 + 次级圆点，开=品牌色底 + 白点。 */
@Composable
private fun TagSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (checked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
        )
    }
}

/** 空闲时的扫描操作卡：状态 chip + 进度轨道 + 「Scan new / Rescan all」大按钮（拉开词义防误触）。 */
@Composable
private fun ScanActionCard(
    totalMedia: Int,
    pendingCount: Int,
    lastSession: TagScanSessionProgress?,
    onScanNew: () -> Unit,
    onRescanAll: () -> Unit
) {
    val upToDate = pendingCount <= 0 && totalMedia > 0
    val chipColor = if (upToDate) Color(0xFF00E676) else Color(0xFFFFB020)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.tag_scan_status),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(chipColor.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(chipColor)
                    )
                    Text(
                        text = if (upToDate) {
                            stringResource(R.string.tag_scan_up_to_date)
                        } else {
                            stringResource(R.string.tag_scan_chip_pending, pendingCount)
                        },
                        fontSize = 11.sp,
                        color = chipColor
                    )
                }
            }
            val fraction = if (totalMedia > 0) {
                ((totalMedia - pendingCount).coerceAtLeast(0)).toFloat() / totalMedia
            } else {
                0f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(tagBrandGradient)
                )
            }
            // 上次会话已终结（完成/取消）时展示其结果，否则展示待处理概况
            val terminal = lastSession?.takeIf {
                it.state == ScanSessionState.COMPLETED || it.state == ScanSessionState.CANCELLED
            }
            Text(
                text = if (terminal != null) {
                    stringResource(
                        R.string.tag_scan_caption_done,
                        "%,d".format(Locale.ROOT, terminal.processed),
                        terminal.failed
                    )
                } else {
                    stringResource(R.string.tag_scan_idle_caption, totalMedia, pendingCount)
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GradientPillButton(
                    text = stringResource(R.string.tag_scan_incremental),
                    icon = Icons.Rounded.PlayArrow,
                    height = 40.dp,
                    cornerRadius = 20.dp,
                    fontSize = 13.sp,
                    onClick = onScanNew,
                    modifier = Modifier.weight(1f)
                )
                OutlinedPillButton(
                    text = stringResource(R.string.tag_scan_full),
                    icon = Icons.Rounded.Refresh,
                    onClick = onRescanAll,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * TAG 打标 GPU 加速单行卡（设计稿 gallery/tag_control `CardTagAccel`）：
 * icon + 标题/单行副标题 + CPU|GPU 分段控件。
 * 收口自原 GallerySettingsHeader 与休眠 GALLERY 分类，为该开关唯一 UI 入口。
 */
@Composable
private fun TagAccelCard(
    useOpencl: Boolean,
    onUseOpenclChange: (Boolean) -> Unit
) {
    val accent = Color(0xFFFFB020) // 同 tag-control.yaml status_orange 字面量（icon 块复用）
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Speed, null, modifier = Modifier.size(18.dp), tint = accent)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tag_gen_use_opencl_title),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.tag_gen_use_opencl_subtitle),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TagAccelSegmented(
                useOpencl = useOpencl,
                onUseOpenclChange = onUseOpenclChange
            )
        }
    }
}

/** CPU|GPU 分段控件：surfaceVariant 轨道 + 选中项胶囊（primary 14% 底 + SemiBold），替代原 chips+双说明。 */
@Composable
private fun TagAccelSegmented(
    useOpencl: Boolean,
    onUseOpenclChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TagAccelSegmentedOption(
            label = stringResource(R.string.device_preference_force_cpu),
            selected = !useOpencl,
            onClick = { onUseOpenclChange(false) }
        )
        TagAccelSegmentedOption(
            label = stringResource(R.string.device_preference_force_gpu),
            selected = useOpencl,
            onClick = { onUseOpenclChange(true) }
        )
    }
}

@Composable
private fun TagAccelSegmentedOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** 阶段行：图标 + 标题/描述 + 进度% + chevron，整行可点弹出操作弹层。 */
@Composable
private fun StageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    trailing: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = iconTint)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            trailing,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            Icons.Rounded.ChevronRight,
            null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 阶段操作底部弹层：两个大选项上下排开（单选圈示意推荐项），替代易误触的右侧堆叠小按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StageActionSheet(
    title: String,
    onDismiss: () -> Unit,
    onProcessNew: () -> Unit,
    onReprocessAll: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.tag_stage_sheet_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StageActionOption(
                title = stringResource(R.string.tag_stage_action_new),
                description = stringResource(R.string.tag_stage_action_new_desc),
                recommended = true,
                onClick = {
                    onDismiss()
                    onProcessNew()
                }
            )
            StageActionOption(
                title = stringResource(R.string.tag_stage_action_full),
                description = stringResource(R.string.tag_stage_action_full_desc),
                recommended = false,
                onClick = {
                    onDismiss()
                    onReprocessAll()
                }
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.Info,
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.tag_stage_full_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Cancel（设计稿 btnCancel：surfaceVariant 底 r22 通栏）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.cancel),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 弹层内的单个操作选项：推荐项高亮描边 + Recommended 徽章 + 单选圈；点按卡片直接执行。 */
@Composable
private fun StageActionOption(
    title: String,
    description: String,
    recommended: Boolean,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (recommended) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                1.dp,
                if (recommended) accent else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (recommended) {
                    Box(
                        modifier = Modifier
                            .height(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accent.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.tag_action_recommended),
                            fontSize = 10.sp,
                            color = accent
                        )
                    }
                }
            }
            Text(
                description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 单选圈（装饰性：推荐项为选中态）
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    2.dp,
                    if (recommended) accent else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (recommended) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
    }
}

/** 可独立操作的扫描阶段（点按行弹操作弹层）。 */
private enum class TagStage { FACE, PEOPLE, CONTENT, QUALITY }

private fun stagePercentText(progress: TagPassProgress): String =
    if (progress.isEmpty) "—" else "${(progress.fraction * 100).roundToInt()}%"

internal fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private enum class TimeRangePreset(@StringRes val labelRes: Int, private val startOffsetMs: Long) {
    ALL(R.string.tag_time_range_all, 0),
    DAYS_7(R.string.tag_time_range_days_7, 7 * 24 * 60 * 60 * 1000L),
    DAYS_30(R.string.tag_time_range_days_30, 30 * 24 * 60 * 60 * 1000L),
    DAYS_90(R.string.tag_time_range_days_90, 90 * 24 * 60 * 60 * 1000L);

    val startTimeMs: Long
        get() = if (startOffsetMs > 0) System.currentTimeMillis() - startOffsetMs else 0L
}

private fun Set<TagCategory>.toggle(category: TagCategory): Set<TagCategory> {
    return if (category in this) this - category else this + category
}

/** 设计稿 chip：h28 r14；选中=品牌色 14% 底 + 品牌色描边/文字，未选=outlineVariant 描边 + 次级文字。 */
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Suppress("LongParameterList") // 待重构：抽 stats 数据类
@Composable
private fun StatsCard(
    totalMedia: Int,
    withFace: Int,
    withLabels: Int,
    withSemantic: Int,
    personCount: Int,
    namedPersonCount: Int,
    embeddingCount: Int,
    onNavigateToTagViewer: () -> Unit
) {
    // 设计稿 gallery/tag_control_v2「Library stats」：渐变大数字 + 语义覆盖率圆环 + 2×2 指标瓦片
    // （阶段进度条已移至 Stages 列表行内，不再重复展示）
    val semanticPct = if (totalMedia > 0) withSemantic * 100 / totalMedia else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 头部：标题 + 标签查看入口（View tags + chevron）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.tag_stats_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigateToTagViewer() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tag_viewer_open_entry),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Rounded.ChevronRight,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Hero：渐变大数字 + 语义覆盖率圆环 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "%,d".format(Locale.ROOT, totalMedia),
                        style = TextStyle(
                            fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                            brush = tagBrandGradient
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.stats_hero_caption, semanticPct),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatsProgressRing(progress = semanticPct)
            }

            // ── 2×2 指标瓦片（设计稿 tile 阵列：数值 + 标签，无图标） ──
            Row(modifier = Modifier.fillMaxWidth()) {
                StatsMetricTile(
                    label = stringResource(R.string.tag_stats_with_face),
                    value = withFace,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                StatsMetricTile(
                    label = stringResource(R.string.tag_stats_embeddings),
                    value = embeddingCount,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatsMetricTile(
                    label = stringResource(R.string.tag_stats_people),
                    value = personCount,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                StatsMetricTile(
                    label = stringResource(R.string.tag_stats_named),
                    value = namedPersonCount,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 72dp 进度圆环（设计稿 ringSvg）：surfaceVariant 底环 + 品牌实色前景弧 + 中心百分比。 */
@Composable
private fun StatsProgressRing(progress: Int) {
    val sweep = 360f * (progress.coerceIn(0, 100) / 100f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (sweep > 0f) {
                drawArc(
                    color = accentColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = "$progress%",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 指标瓦片：数值 + 标签（surfaceVariant 底 r12 高 64，设计稿无图标）。 */
@Composable
private fun StatsMetricTile(
    label: String,
    value: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "%,d".format(Locale.ROOT, value),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
