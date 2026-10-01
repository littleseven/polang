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
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.aesthetic.AestheticScoreWorker
import com.mamba.picme.domain.tag.TagCategory
import com.mamba.picme.domain.tag.scan.LibraryCompletion
import com.mamba.picme.domain.tag.scan.ScanCardAction
import com.mamba.picme.domain.tag.scan.ScanCardUiModel
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.ScanStage
import com.mamba.picme.domain.tag.scan.TagPassProgress
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.domain.tag.scan.formatDuration
import com.mamba.picme.domain.tag.scan.percentRounded
import com.mamba.picme.domain.tag.scan.scanCardUiModel
import com.mamba.picme.domain.tag.scan.tagPassProgress
import com.mamba.picme.domain.usertask.TagScanTaskAdapter
import com.mamba.picme.domain.usertask.UserTaskStatus
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.util.permission.BackgroundScanGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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
    onNavigateToTagViewer: () -> Unit = {},
    /** v3 统计区/阶段行跳转出口：主卡统计区→已打标照片；人脸行→含人脸照片；质量行→最佳照片 */
    onOpenTagged: () -> Unit = {},
    onOpenFaces: () -> Unit = {},
    onOpenBest: () -> Unit = {},
    /** 人物行→人物页（宿主切主页面） */
    onOpenPeople: () -> Unit = {},
    /** 嵌入整理+扫描合并页（OrganizeHomeRoute）时为 true：顶栏不再内置状态栏避让（外层胶囊条统一避让）；根页无返回箭头（2026-09-06 导航统一移除）。 */
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    // ── 通过 AppContainer 观察 TAG 生成状态（Service 内部分发） ───
    val sessionProgress by app.container.tagGenerationSessionProgress.collectAsState()
    val isScanning by app.container.tagGenerationIsScanning.collectAsState()
    // 库级 AI 打标完成率（口径立法唯一百分比）；无活跃会话时为 null
    val libraryCompletion by app.container.tagGenerationLibraryCompletion.collectAsState()
    // 进程死亡对账（FAILED + PROCESS_TERMINATED）驱动「扫描已中断」卡
    val userTasks by app.container.userTaskRegistry.tasks.collectAsState()
    val scanInterrupted = userTasks.any { task ->
        task.id == TagScanTaskAdapter.TASK_ID && task.status == UserTaskStatus.FAILED
    }
    // 附属打分器（美学/人脸画质）进度：非会话制，null = 空闲；顶部进度卡优先显示活跃任务
    val aestheticProgress by app.container.aestheticScoreWorker.progress.collectAsState()
    val currentState = sessionProgress?.state
    val snackbarHostState = remember { SnackbarHostState() }

    // 启动前台 Service（Intent 驱动，无外部 Handle）
    LaunchedEffect(Unit) {
        TagGenerationService.startForeground(context)
    }

    // 数据库统计（每次进入时刷新）；v3 出页指标（含人脸/Embedding/已命名）不再采集
    var totalMedia by remember { mutableIntStateOf(0) }
    var personCount by remember { mutableIntStateOf(0) }
    var remainingPass1 by remember { mutableIntStateOf(0) }
    var remainingPass3 by remember { mutableIntStateOf(0) }
    var photoCount by remember { mutableIntStateOf(0) }
    var aestheticScored by remember { mutableIntStateOf(0) }

    // 精细控制：类别 / 时间范围 / 模式
    var selectedCategories by remember { mutableStateOf(setOf<TagCategory>()) }
    var selectedTimeRange by remember { mutableStateOf(TimeRangePreset.ALL) }
    var fullRegenerateMode by remember { mutableStateOf(false) }

    // 阶段操作底部弹层 / 全量重处理二次确认 / 重新生成二级弹层（v3：管理动作入口）
    var stageSheetTarget by remember { mutableStateOf<TagStage?>(null) }
    var pendingFullStage by remember { mutableStateOf<TagStage?>(null) }
    var showRegenSheet by remember { mutableStateOf(false) }

    // 刷新统计：统一通过 TagScanOrchestrator.getDbStats(db) 获取，
    // 不依赖 Service/Orchestrator 实例，进入页面即可立即显示。
    fun refreshStats() {
        coroutineScope.launch {
            try {
                android.util.Log.d("TagGenControl", "refreshStats() called")
                val stats = TagScanOrchestrator.getDbStats(db)
                android.util.Log.d("TagGenControl", "stats=$stats")
                totalMedia = stats.totalMedia
                personCount = stats.personCount
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

    // ── 重新生成二级弹层（v3）：分阶段重处理 + 按类别/时间精细控制 ──────
    if (showRegenSheet) {
        RegenSheet(
            onDismiss = { showRegenSheet = false },
            onStageClick = { stage ->
                showRegenSheet = false
                stageSheetTarget = stage
            },
            selectedCategories = selectedCategories,
            onToggleCategory = { category -> selectedCategories = selectedCategories.toggle(category) },
            selectedTimeRange = selectedTimeRange,
            onSelectTimeRange = { preset -> selectedTimeRange = preset },
            fullRegenerateMode = fullRegenerateMode,
            onFullRegenerateModeChange = { checked -> fullRegenerateMode = checked },
            onRegenerate = {
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
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // embedded（OrganizeHome 内 tab）：胶囊条即页头，不再叠静态标题栏——
        // 2026-10-01 真机反馈「顶部一大段空白」根因 = AppTopBar 占位 + Scaffold
        // contentWindowInsets 二次叠加状态栏 inset；清零对齐 DedupHomeScreen embedded 套路
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!embedded) {
                AppTopBar(title = { Text(stringResource(R.string.gallery_settings)) })
            }
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
            // ── HeroCard（v3 单锚点主卡，spec tag-control.yaml §hero）──────
            // 空闲/运行同槽位互斥切换；统计区（标题+大数字+剩余行+圆环）可点跳「已打标照片」。
            // 零失败终态回落空闲态（叙述行显示上次会话统计）；美学打分/中断卡为同槽位互斥附属卡。
            val scanActive = isScanning
            val cardModel = sessionProgress?.let { value -> scanCardUiModel(value) }
            val showRunningHero = cardModel != null && (scanActive || cardModel.isTerminalWithFailures)
            HeroCard(
                model = if (showRunningHero) cardModel else null,
                library = libraryCompletion,
                totalMedia = totalMedia,
                remainingPass3 = remainingPass3,
                lastSession = sessionProgress,
                onOpenTagged = onOpenTagged,
                onPause = { context.startForegroundService(TagGenerationService.intentPause(context)) },
                onResume = { context.startForegroundService(TagGenerationService.intentResume(context)) },
                onCancel = { context.startForegroundService(TagGenerationService.intentCancel(context)) },
                onRetryFailed = { context.startForegroundService(TagGenerationService.intentRetryFailed(context)) },
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

            // 附属互斥卡（非会话制美学打分 / 进程死亡对账）
            AnimatedVisibility(visible = aestheticProgress != null && !scanActive) {
                aestheticProgress?.let { AestheticProgressCard(it) }
            }
            AnimatedVisibility(visible = cardModel == null && aestheticProgress == null && scanInterrupted) {
                InterruptedScanCard(
                    onResume = {
                        startScanWithGuard {
                            context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                        }
                    }
                )
            }

            // ── 分阶段（v3：点行=查看与数字匹配的内容页，浏览不锁定）──
            // 管理动作（重新处理）不在行上——收进「重新生成」二级（RegenSheet）。
            SectionHeader(
                title = stringResource(R.string.tag_pass_control_title),
                hint = stringResource(R.string.tag_stages_hint_view)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    val faceProgress = tagPassProgress(totalMedia, remainingPass1)
                    StageRow(
                        icon = Icons.Rounded.Face,
                        iconTint = Color(0xFFFF7EB0),
                        title = stringResource(R.string.tag_pass_title_face),
                        description = if (faceProgress.isEmpty) {
                            stringResource(R.string.tag_pass_desc_face)
                        } else {
                            stringResource(R.string.tag_pass_scope_face, faceProgress.processed, faceProgress.total)
                        },
                        trailing = stagePercentText(faceProgress),
                        onClick = onOpenFaces
                    )
                    StageRow(
                        icon = Icons.Rounded.Person,
                        iconTint = Color(0xFF9B8CFF),
                        title = stringResource(R.string.tag_pass_title_cluster),
                        description = stringResource(R.string.tag_pass_desc_cluster),
                        trailing = if (personCount > 0) "$personCount" else "—",
                        onClick = onOpenPeople
                    )
                    val contentProgress = tagPassProgress(totalMedia, remainingPass3)
                    StageRow(
                        icon = Icons.Rounded.Label,
                        iconTint = Color(0xFF22D3EE),
                        title = stringResource(R.string.tag_pass_title_content),
                        description = if (contentProgress.isEmpty) {
                            stringResource(R.string.tag_pass_desc_content)
                        } else {
                            stringResource(R.string.tag_pass_scope_content, contentProgress.processed, contentProgress.total)
                        },
                        trailing = stagePercentText(contentProgress),
                        onClick = onNavigateToTagViewer
                    )
                    val qualityProgress = tagPassProgress(photoCount, photoCount - aestheticScored)
                    StageRow(
                        icon = Icons.Rounded.Star,
                        iconTint = Color(0xFF4ADE80),
                        title = stringResource(R.string.tag_pass_title_aesthetic),
                        description = if (qualityProgress.isEmpty) {
                            stringResource(R.string.tag_pass_desc_aesthetic)
                        } else {
                            stringResource(R.string.tag_pass_scope_aesthetic, qualityProgress.processed, qualityProgress.total)
                        },
                        trailing = stagePercentText(qualityProgress),
                        onClick = onOpenBest
                    )
                }
            }

            // ── 重新生成入口（v3 单行卡）：管理动作收进二级（分阶段重处理 + 按条件）──
            // 扫描会话活跃（含暂停/过渡态）时整行隐藏，与行浏览解耦。
            AnimatedVisibility(visible = cardModel == null) {
                RegenEntryRow(onClick = { showRegenSheet = true })
            }

            // ── 后台保活缺失项提示(非阻断,点击跳设置) ────────
            BackgroundScanGuardBanner()
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * HeroCard（v3 单锚点主卡，spec tag-control.yaml §hero）：
 * model=null 空闲态（覆盖率标签 + 渐变大数字…改为平铺 primary 大数字 + 剩余行 + 圆环 + 双钮）；
 * model!=null 运行态（阶段名标题 + 任务级叙述行 + 圆环 + 会话操作，scanCardUiModel 纯渲染）。
 * 统计区（标题/大数字/剩余行 + 圆环）整体可点跳「已打标照片」；圆环=库级 AI 打标完成率唯一百分比。
 */
@Suppress("LongParameterList") // v3 单卡聚合空闲/运行双态数据；拆数据类收益低于可读性损失
@Composable
private fun HeroCard(
    model: ScanCardUiModel?,
    library: LibraryCompletion?,
    totalMedia: Int,
    remainingPass3: Int,
    lastSession: TagScanSessionProgress?,
    onOpenTagged: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
    onScanNew: () -> Unit,
    onRescanAll: () -> Unit,
) {
    val containerColor = when {
        model == null -> MaterialTheme.colorScheme.surfaceContainer
        model.isTerminalWithFailures -> MaterialTheme.colorScheme.errorContainer
        model.isPaused -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        model == null -> MaterialTheme.colorScheme.onSurface
        model.isTerminalWithFailures -> MaterialTheme.colorScheme.onErrorContainer
        model.isPaused -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    // 口径立法：圆环百分比空闲=Pass3 库级、运行=LibraryCompletion，同公式同舍入
    val taggedPct = library?.percentRounded()
        ?: tagPassProgress(totalMedia, remainingPass3).percentRounded()
    val trackFraction = library?.fraction
        ?: tagPassProgress(totalMedia, remainingPass3).fraction
    // 零失败终态回落空闲态时，剩余行换上次会话统计叙述
    val terminal = lastSession?.takeIf {
        it.state == ScanSessionState.COMPLETED || it.state == ScanSessionState.CANCELLED
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 统计区（可点 → 已打标照片视图）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTagged),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    if (model != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val active = model.primaryAction == ScanCardAction.PAUSE ||
                                    (model.primaryAction == ScanCardAction.NONE && !model.isTerminalWithFailures)
                            if (active) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = contentColor
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = scanCardTitle(model),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            )
                        }
                        // 叙述行：任务级进度只允许自然语言形态（第 x/y 张 · 约 N 分钟）
                        model.narrative?.let { narrative ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (narrative.etaMs != null) {
                                    stringResource(
                                        R.string.tag_scan_narrative,
                                        narrative.processed,
                                        narrative.total,
                                        formatDuration(narrative.etaMs)
                                    )
                                } else {
                                    stringResource(
                                        R.string.tag_scan_narrative_no_eta,
                                        narrative.processed,
                                        narrative.total
                                    )
                                },
                                fontSize = 12.sp,
                                color = contentColor.copy(alpha = 0.8f)
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.tag_stats_coverage_label),
                            fontSize = 12.sp,
                            color = contentColor.copy(alpha = 0.72f)
                        )
                        Text(
                            text = "%,d".format(Locale.ROOT, totalMedia),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                            // v3 换肤：平铺 primary（对齐 organize hero，弃渐变）
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (terminal != null) {
                                stringResource(
                                    R.string.tag_scan_caption_done,
                                    "%,d".format(Locale.ROOT, terminal.processed),
                                    terminal.failed
                                )
                            } else {
                                stringResource(R.string.tag_stats_remaining_line, remainingPass3)
                            },
                            fontSize = 12.sp,
                            color = contentColor.copy(alpha = 0.72f)
                        )
                    }
                }
                StatsProgressRing(progress = taggedPct)
                Icon(
                    Icons.Rounded.ChevronRight,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor.copy(alpha = 0.6f)
                )
            }

            // ── 库级完成率轨道（唯一百分比，与圆环同源）──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(contentColor.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(trackFraction)
                        .height(6.dp)
                        .background(
                            if (model == null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                contentColor
                            }
                        )
                )
            }

            // ── 操作钮（r12 圆角矩形，v3 弃胶囊；集由 scanCardUiModel 决定）──
            if (model == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.tag_scan_incremental),
                        icon = Icons.Rounded.PlayArrow,
                        onClick = onScanNew,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.tag_scan_full),
                        icon = Icons.Rounded.Refresh,
                        onClick = onRescanAll,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (model.primaryAction) {
                        ScanCardAction.PAUSE -> SecondaryActionButton(
                            text = stringResource(R.string.pause),
                            icon = Icons.Rounded.Pause,
                            onClick = onPause,
                            modifier = Modifier.weight(1f)
                        )
                        ScanCardAction.RESUME -> PrimaryActionButton(
                            text = stringResource(R.string.resume),
                            icon = Icons.Rounded.PlayArrow,
                            onClick = onResume,
                            modifier = Modifier.weight(1f)
                        )
                        ScanCardAction.RETRY_FAILED -> PrimaryActionButton(
                            text = stringResource(R.string.tag_scan_retry_failed_items),
                            icon = Icons.Rounded.Refresh,
                            onClick = onRetryFailed,
                            modifier = Modifier.weight(1f)
                        )
                        ScanCardAction.NONE -> SecondaryActionButton(
                            text = stringResource(
                                if (model.cancelEnabled) R.string.tag_scan_state_pausing
                                else R.string.tag_scan_state_cancelling
                            ),
                            icon = Icons.Rounded.Pause,
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (model.cancelEnabled) {
                        GhostCancelButton(
                            text = stringResource(R.string.cancel),
                            icon = Icons.Rounded.Cancel,
                            onClick = onCancel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 主操作钮（v3 设计稿 r12 h48）：primary 实底 + onPrimary 15sp。 */
@Composable
private fun PrimaryActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** 次操作钮（v3 设计稿 r12 h48）：surfaceContainerHigh 实底 + onSurface 15sp（dedup 同款）。 */
@Composable
private fun SecondaryActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(
                text,
                fontSize = 15.sp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }
    }
}

/** 幽灵取消钮（v3 设计稿运行态）：无底 + error 色文字（dedup 扫描中同款）。 */
@Composable
private fun GhostCancelButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 卡片标题：终态失败 > 暂停 > 过渡态 > 阶段名（全部整句文案键，无跨语拼接）。 */
@Composable
private fun scanCardTitle(model: ScanCardUiModel): String = when {
    model.isTerminalWithFailures ->
        stringResource(R.string.tag_scan_completed_with_failures, model.failedCount)
    model.isPaused ->
        stringResource(R.string.tag_scan_paused_title, scanStageShortName(model.stage))
    model.primaryAction == ScanCardAction.NONE && model.cancelEnabled ->
        stringResource(R.string.tag_scan_state_pausing)
    model.primaryAction == ScanCardAction.NONE ->
        stringResource(R.string.tag_scan_state_cancelling)
    else -> when (model.stage) {
        ScanStage.FACE -> stringResource(R.string.tag_scan_now_face)
        ScanStage.CLUSTER -> stringResource(R.string.tag_scan_now_cluster)
        ScanStage.CONTENT -> stringResource(R.string.tag_scan_now_content)
        ScanStage.SEMANTIC -> stringResource(R.string.tag_scan_now_semantic)
        ScanStage.PREPARING -> stringResource(R.string.tag_scan_now_preparing)
    }
}

/** 暂停标题插值用的阶段短名（复用 Stages 行标题键）。 */
@Composable
private fun scanStageShortName(stage: ScanStage): String = when (stage) {
    ScanStage.FACE -> stringResource(R.string.tag_pass_title_face)
    ScanStage.CLUSTER -> stringResource(R.string.tag_pass_title_cluster)
    ScanStage.CONTENT -> stringResource(R.string.tag_pass_title_content)
    ScanStage.SEMANTIC -> stringResource(R.string.tag_pass_step_semantic)
    ScanStage.PREPARING -> stringResource(R.string.tag_scan_preparing)
}

/** 进程死亡复活卡（spec §5.3）：对账 FAILED 后出现，主按钮增量续扫（天然断点续跑）。 */
@Composable
private fun InterruptedScanCard(onResume: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.tag_scan_interrupted_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tag_scan_interrupted_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.tag_scan_resume_breakpoint))
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

/** 阶段行：图标 + 标题/描述 + 进度% + chevron，整行可点（v3：跳转对应内容页）。 */
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
            // navigationBarsPadding：弹层内容须避让虚拟键（2026-10-01 真机实测取消钮下半截
            // 压进 y2517+ 导航区被手势条遮挡；同 DedupSheets 正典写法），之上再留 16dp 呼吸
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
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
                    // 自适配胶囊：行高 12sp + Center 对齐修正 CJK 行盒不对称——原定高 16dp 胶囊
                    // 下文字行盒(~17dp)更高且墨迹沉底，真机实测「推荐」比胶囊中心低 4dp 且贴边
                    // 被裁（2026-10-01 像素测量 y1809-1835 vs 胶囊 y1785-1835）
                    Text(
                        text = stringResource(R.string.tag_action_recommended),
                        fontSize = 10.sp,
                        color = accent,
                        style = LocalTextStyle.current.copy(
                            lineHeight = 12.sp,
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.None
                            )
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(accent.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
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

/** 重新生成入口单行卡（v3 设计稿 RegenEntry）：14sp 文字 + chevron，r16 surfaceContainer。 */
@Composable
private fun RegenEntryRow(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tag_regen_entry),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 重新生成二级弹层（v3）：① 分阶段重处理（点行 → StageActionSheet 两档，全量仍二次确认）
 * ② 按类别/时间范围精细控制（原页内整段移入，控件原样）。会话活跃时入口整行隐藏，本弹层不可达。
 * navigationBarsPadding：弹层内容避让虚拟键（同 StageActionSheet 正典写法）。
 */
@Suppress("LongParameterList") // 精细控制状态由页面持有，弹层纯受控渲染
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegenSheet(
    onDismiss: () -> Unit,
    onStageClick: (TagStage) -> Unit,
    selectedCategories: Set<TagCategory>,
    onToggleCategory: (TagCategory) -> Unit,
    selectedTimeRange: TimeRangePreset,
    onSelectTimeRange: (TimeRangePreset) -> Unit,
    fullRegenerateMode: Boolean,
    onFullRegenerateModeChange: (Boolean) -> Unit,
    onRegenerate: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.tag_fine_control_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ── ① 分阶段重处理 ──
            Text(
                stringResource(R.string.tag_pass_control_title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RegenStageRow(
                icon = Icons.Rounded.Face,
                iconTint = Color(0xFFFF7EB0),
                title = stringResource(R.string.tag_pass_title_face),
                onClick = { onStageClick(TagStage.FACE) }
            )
            RegenStageRow(
                icon = Icons.Rounded.Person,
                iconTint = Color(0xFF9B8CFF),
                title = stringResource(R.string.tag_pass_title_cluster),
                onClick = { onStageClick(TagStage.PEOPLE) }
            )
            RegenStageRow(
                icon = Icons.Rounded.Label,
                iconTint = Color(0xFF22D3EE),
                title = stringResource(R.string.tag_pass_title_content),
                onClick = { onStageClick(TagStage.CONTENT) }
            )
            RegenStageRow(
                icon = Icons.Rounded.Star,
                iconTint = Color(0xFF4ADE80),
                title = stringResource(R.string.tag_pass_title_aesthetic),
                onClick = { onStageClick(TagStage.QUALITY) }
            )

            // ── ② 按类别 / 时间范围 ──
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
                    onClick = { onToggleCategory(TagCategory.FACE) }
                )
                CategoryChip(
                    label = stringResource(R.string.tag_category_scene),
                    selected = TagCategory.SCENE in selectedCategories,
                    onClick = { onToggleCategory(TagCategory.SCENE) }
                )
                CategoryChip(
                    label = stringResource(R.string.tag_category_activity),
                    selected = TagCategory.ACTIVITY in selectedCategories,
                    onClick = { onToggleCategory(TagCategory.ACTIVITY) }
                )
                CategoryChip(
                    label = stringResource(R.string.tag_category_objects),
                    selected = TagCategory.OBJECTS in selectedCategories,
                    onClick = { onToggleCategory(TagCategory.OBJECTS) }
                )
                CategoryChip(
                    label = stringResource(R.string.tag_category_tags),
                    selected = TagCategory.TAGS in selectedCategories,
                    onClick = { onToggleCategory(TagCategory.TAGS) }
                )
                CategoryChip(
                    label = stringResource(R.string.tag_category_summary),
                    selected = TagCategory.SUMMARY in selectedCategories,
                    onClick = { onToggleCategory(TagCategory.SUMMARY) }
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
                        onClick = { onSelectTimeRange(preset) }
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
                    onCheckedChange = onFullRegenerateModeChange
                )
            }

            PrimaryActionButton(
                text = stringResource(R.string.tag_regenerate_selected),
                icon = Icons.Rounded.Tune,
                onClick = {
                    onDismiss()
                    onRegenerate()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 弹层内的阶段行（紧凑版）：图标芯片 + 标题 + chevron。 */
@Composable
private fun RegenStageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
        Text(
            title,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Rounded.ChevronRight,
            null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 可独立操作的扫描阶段（RegenSheet 内点行弹 StageActionSheet）。 */
private enum class TagStage { FACE, PEOPLE, CONTENT, QUALITY }

private fun stagePercentText(progress: TagPassProgress): String =
    if (progress.isEmpty) "—" else "${progress.percentRounded()}%"

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

/** 72dp 进度圆环（设计稿 ringSvg）：surfaceVariant 底环 + primary 实色前景弧 + 中心两行（百分比 + AI 打标微标签）。 */
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$progress%",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.tag_stats_ring_label),
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
