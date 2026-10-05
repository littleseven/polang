@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mamba.picme.features.gallery.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.StatusColor
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.aesthetic.AestheticScoreWorker
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
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.util.permission.BackgroundScanGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer

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
    /** 「稍后」次钮出口（v4 设计稿：宿主切回整理 tab） */
    onLater: () -> Unit = {},
    /** 成果格导航出口（v4.1：成果=查看、阶段行=扫描控制） */
    onOpenGroup: () -> Unit = {},
    onOpenSelf: () -> Unit = {},
    onOpenCity: (String) -> Unit = {},
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
    var withFaceCount by remember { mutableIntStateOf(0) }
    var namedPersonCount by remember { mutableIntStateOf(0) }
    var cityCount by remember { mutableIntStateOf(0) }
    var groupPhotoCount by remember { mutableIntStateOf(0) }
    var selfPhotoCount by remember { mutableIntStateOf(0) }
    var cityChoices by remember { mutableStateOf<List<com.mamba.picme.data.local.CityGroupCount>>(emptyList()) }
    var showCitySheet by remember { mutableStateOf(false) }

    // 阶段操作底部弹层（v4：点按/长按阶段行弹出；2026-10-02 两选项点选即执行，全量二次确认移除）
    var stageSheetTarget by remember { mutableStateOf<TagStage?>(null) }

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
                withFaceCount = stats.withFace
                namedPersonCount = stats.namedPersonCount
                cityCount = stats.cityCount
                groupPhotoCount = stats.groupPhotoCount
                selfPhotoCount = stats.selfPhotoCount
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
        // 先检查再提醒（2026-10-01）：只报未确认过的缺失项，全部确认过则静默直启
        val issues = runCatching { BackgroundScanGuard.unacknowledgedIssues(context.applicationContext) }
            .getOrDefault(emptyList())
        if (issues.isNotEmpty()) {
            guardIssues = issues
            pendingStart = startAction
        } else {
            startAction()
        }
    }

    // 阶段操作：弹层选择「增量扫描 / 全量扫描」，点选即执行
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
                // 按项确认：仅静默当前缺失项，未来新项/回归项仍会提醒
                BackgroundScanGuard.acknowledgeIssues(
                    context.applicationContext,
                    guardIssues.map { issue -> issue.type }
                )
                guardIssues = emptyList()
                pendingStart = null
            }
        )
    }

    // ── 阶段操作弹层（点按阶段行弹出；两选项点选即执行）────────
    stageSheetTarget?.let { stage ->
        StageActionSheet(
            title = stageTitle(stage),
            onDismiss = { stageSheetTarget = null },
            onProcessNew = { startStage(stage, full = false) },
            onReprocessAll = { startStage(stage, full = true) }
        )
    }

    // 会话状态（v4 2026-10-02 用户重设计：progBar+top_bar 计数承载本轮，环=全库口径）
    val scanActive = isScanning
    val cardModel = sessionProgress?.let { value -> scanCardUiModel(value) }
    // 零失败终态回落空闲（同 v3 语义）；暂停态占位（isScanning 不含 PAUSED，须显式保留）
    val liveModel = cardModel?.takeIf { scanActive || it.isPaused || it.isTerminalWithFailures }
    // 本轮会话分数：活跃(含暂停/过渡)时驱动 progBar 填充与顶栏计数；空闲归零
    val sessionNarrative = liveModel?.narrative?.takeIf { it.total > 0 }
    // 本轮分数 = 任务域加权进度（sweep 连续、新增照片不回退）；老版本帧回退任务级计数
    val sessionFraction = liveModel?.let { sessionProgress?.weightedFraction }
        ?: sessionNarrative?.let { it.processed.toFloat() / it.total } ?: 0f
    // 环 = 库域加权完成度（唯一百分比）；Service 流缺帧时本地同公式构造（同口径，仅传输不同）
    val localLibrary = LibraryCompletion(
        totalPhotos = photoCount,
        remainingPass1 = remainingPass1,
        remainingPass3 = remainingPass3,
    )
    val libraryPct = libraryCompletion?.percentRounded() ?: localLibrary.percentRounded()
    val libraryFraction = libraryCompletion?.fraction ?: localLibrary.fraction
    val taggedCount = photoCount - remainingPass3
    val running = liveModel?.primaryAction == ScanCardAction.PAUSE
    val pausedState = liveModel?.isPaused == true
    val terminal = sessionProgress?.state == ScanSessionState.COMPLETED

    // 底部操作：v4 设计稿三态（idle 开始/稍后；running 暂停/停止；paused 继续/重新开始）
    fun restartScan() {
        coroutineScope.launch {
            context.startForegroundService(TagGenerationService.intentCancel(context))
            delay(800)
            refreshStats()
            startScanWithGuard {
                context.startForegroundService(TagGenerationService.intentScanIncremental(context))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // embedded（OrganizeHome 内 tab）：宿主胶囊条即页头；清零 insets 对齐 Dedup 套路
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ── 顶部 4dp 全宽本轮进度条（v4 设计稿 progBar：胶囊正下方）──
            ScanProgressBar(
                fraction = sessionFraction,
                running = running,
                paused = pausedState
            )

            // ── top_bar：标题 + 本轮计数（空闲只有标题）──
            ScanTopBar(
                counter = sessionNarrative?.let { "%,d / %,d".format(Locale.ROOT, it.processed, it.total) },
                subline = when {
                    sessionNarrative == null -> null
                    pausedState -> stringResource(R.string.tag_scan_top_paused)
                    sessionNarrative.etaMs != null ->
                        stringResource(R.string.tag_scan_top_eta, formatDuration(sessionNarrative.etaMs))
                    else -> stringResource(R.string.tag_scan_top_running)
                }
            )

            // ── content：环卡 + 附属互斥卡 + 分阶段列表 ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RingHeroCard(
                    percent = libraryPct,
                    fraction = libraryFraction,
                    labeledCount = taggedCount,
                    totalCount = photoCount,
                    running = running,
                    paused = pausedState,
                    terminalDone = terminal,
                    withFaceCount = withFaceCount,
                    peopleCount = personCount,
                    namedPersonCount = namedPersonCount,
                    cityCount = cityCount,
                    groupPhotoCount = groupPhotoCount,
                    selfPhotoCount = selfPhotoCount,
                    taggedCount = taggedCount,
                    onOpenFaces = onOpenFaces,
                    onOpenPeople = onOpenPeople,
                    onOpenTagged = onOpenTagged,
                    onOpenGroup = onOpenGroup,
                    onOpenSelf = onOpenSelf,
                    onOpenCityPicker = {
                        coroutineScope.launch {
                            cityChoices = runCatching {
                                AppDatabase.getDatabase(context).mediaDao().getCityGroups(60)
                            }.getOrDefault(emptyList())
                            showCitySheet = true
                        }
                    },
                    onSelfUnset = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.tag_result_self_unset))
                        }
                    }
                )

                if (showCitySheet) {
                    CityChooserSheet(
                        cities = cityChoices,
                        onDismiss = { showCitySheet = false },
                        onPick = { city ->
                            showCitySheet = false
                            onOpenCity(city)
                        }
                    )
                }

                // 附属互斥卡（非会话制美学打分 / 进程死亡对账）
                AnimatedVisibility(visible = aestheticProgress != null && liveModel == null) {
                    aestheticProgress?.let { AestheticProgressCard(it) }
                }
                AnimatedVisibility(visible = liveModel == null && aestheticProgress == null && scanInterrupted) {
                    InterruptedScanCard(
                        onResume = {
                            startScanWithGuard {
                                context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                            }
                        }
                    )
                }

                // ── 分阶段列表（点按/长按行=扫描控制弹层；查看职责在成果格）──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        val faceProgress = tagPassProgress(photoCount, remainingPass1)
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
                            onClick = { stageSheetTarget = TagStage.FACE },
                            onLongClick = { stageSheetTarget = TagStage.FACE }
                        )
                        StageRow(
                            icon = Icons.Rounded.Person,
                            iconTint = Color(0xFF9B8CFF),
                            title = stringResource(R.string.tag_pass_title_cluster),
                            description = stringResource(R.string.tag_pass_desc_cluster),
                            trailing = if (personCount > 0) "$personCount" else "—",
                            onClick = { stageSheetTarget = TagStage.PEOPLE },
                            onLongClick = { stageSheetTarget = TagStage.PEOPLE }
                        )
                        val contentProgress = tagPassProgress(photoCount, remainingPass3)
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
                            onClick = { stageSheetTarget = TagStage.CONTENT },
                            onLongClick = { stageSheetTarget = TagStage.CONTENT }
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
                            onClick = { stageSheetTarget = TagStage.QUALITY },
                            onLongClick = { stageSheetTarget = TagStage.QUALITY }
                        )
                    }
                }

                // ── 后台保活缺失项提示(非阻断,点击跳设置) ────────
                BackgroundScanGuardBanner()
            }

            // ── bottom_bar：主/次双钮（v4 设计稿，52dp r12 16sp SemiBold）──
            BottomActionBar(
                model = liveModel,
                running = running,
                onStart = {
                    refreshStats()
                    startScanWithGuard {
                        context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                    }
                },
                onPause = { context.startForegroundService(TagGenerationService.intentPause(context)) },
                onResume = { context.startForegroundService(TagGenerationService.intentResume(context)) },
                onStop = { context.startForegroundService(TagGenerationService.intentCancel(context)) },
                onRestart = ::restartScan,
                onRetryFailed = { context.startForegroundService(TagGenerationService.intentRetryFailed(context)) },
                onLater = onLater
            )
        }
    }
}

/**
 * RingHeroCard（v4 2026-10-02 用户重设计 ringHero）：r16 sC 卡，200dp 仪表环（12dp 描边）
 * + 环心 48sp 大百分比 + 居中说明行。环=全库 AI 打标完成率（口径立法唯一百分比）；
 * 运行=绿弧+彗星动画，暂停=琥珀弧，空闲=灰数字。整卡可点跳「已打标照片」。
 */
@Suppress("LongParameterList") // 成果面板 6 格数据+出口聚合于锚点卡；拆分收益低于可读性损失
@Composable
private fun RingHeroCard(
    percent: Int,
    fraction: Float,
    labeledCount: Int,
    totalCount: Int,
    running: Boolean,
    paused: Boolean,
    terminalDone: Boolean,
    withFaceCount: Int,
    peopleCount: Int,
    namedPersonCount: Int,
    cityCount: Int,
    groupPhotoCount: Int,
    selfPhotoCount: Int,
    taggedCount: Int,
    onOpenFaces: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenTagged: () -> Unit,
    onOpenGroup: () -> Unit,
    onOpenSelf: () -> Unit,
    onOpenCityPicker: () -> Unit,
    onSelfUnset: () -> Unit
) {
    val accent = when {
        paused -> StatusColor.warningAmber
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusWord = stringResource(
        when {
            running -> R.string.tag_scan_status_running
            paused -> R.string.tag_scan_status_paused
            terminalDone -> R.string.tag_scan_status_done
            else -> R.string.tag_scan_status_ready
        }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RingDial(
                fraction = fraction.coerceIn(0f, 1f),
                accent = accent,
                animating = running,
                centerText = "$percent%"
            )
            Text(
                text = stringResource(
                    R.string.tag_scan_ring_hint,
                    "%,d".format(Locale.ROOT, labeledCount),
                    "%,d".format(Locale.ROOT, totalCount),
                    statusWord
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            // ── 扫描成果面板（v4.1）：环下分隔线 + 3×2 可点成果格（查看入口）──
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            ResultsRow(
                ResultCellModel(
                    count = "%,d".format(Locale.ROOT, withFaceCount),
                    labelRes = R.string.tag_result_faces_label,
                    dot = Color(0xFFFF7EB0),
                    onClick = onOpenFaces
                ),
                ResultCellModel(
                    count = "%,d".format(Locale.ROOT, peopleCount),
                    label = stringResource(R.string.tag_result_people_label, namedPersonCount),
                    dot = Color(0xFF9B8CFF),
                    onClick = onOpenPeople
                )
            )
            ResultsRow(
                ResultCellModel(
                    count = "%,d".format(Locale.ROOT, cityCount),
                    labelRes = R.string.tag_result_cities_label,
                    dot = Color(0xFF22D3EE),
                    onClick = onOpenCityPicker
                ),
                ResultCellModel(
                    count = "%,d".format(Locale.ROOT, groupPhotoCount),
                    labelRes = R.string.tag_result_group_label,
                    dot = Color(0xFF4ADE80),
                    onClick = onOpenGroup
                )
            )
            ResultsRow(
                ResultCellModel(
                    count = if (selfPhotoCount > 0) {
                        "%,d".format(Locale.ROOT, selfPhotoCount)
                    } else {
                        "—"
                    },
                    labelRes = R.string.tag_result_self_label,
                    dot = StatusColor.warningAmber,
                    onClick = if (selfPhotoCount > 0) onOpenSelf else onSelfUnset
                ),
                ResultCellModel(
                    count = "%,d".format(Locale.ROOT, taggedCount),
                    labelRes = R.string.tag_result_tags_label,
                    dot = MaterialTheme.colorScheme.primary,
                    onClick = onOpenTagged
                )
            )
        }
    }
}

/** 成果面板单行（两格横排，各占半宽）。 */
@Composable
private fun ResultsRow(left: ResultCellModel, right: ResultCellModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ResultCell(model = left, modifier = Modifier.weight(1f))
        ResultCell(model = right, modifier = Modifier.weight(1f))
    }
}

/** 成果格：20sp 数字 + [彩点+标签+chevron] 行，整格可点（查看对应结果页）。 */
private data class ResultCellModel(
    val count: String,
    val labelRes: Int? = null,
    val label: String? = null,
    val dot: Color,
    val onClick: () -> Unit
)

@Composable
private fun ResultCell(model: ResultCellModel, modifier: Modifier = Modifier) {
    val text = model.label ?: model.labelRes?.let { stringResource(it) }.orEmpty()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = model.onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = model.count,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(model.dot, CircleShape)
            )
            Text(
                text = text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** 城市选择弹层（成果面板「足迹城市」入口）：城市+张数列表，点选进该城市照片视图。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CityChooserSheet(
    cities: List<com.mamba.picme.data.local.CityGroupCount>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.tag_city_sheet_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            for (city in cities) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(city.city) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = city.city,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.tag_city_sheet_count, city.cnt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 仪表环（v4 ringWrap）：176dp 直径 12dp 描边；轨道=surfaceVariant，
 * 弧=accent；运行时 40° 彗星弧绕环（scan_animations 节奏立法 1.5s LinearEasing）。
 */
@Composable
private fun RingDial(
    fraction: Float,
    accent: Color,
    animating: Boolean,
    centerText: String
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val transition = rememberInfiniteTransition(label = "ringDial")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
        label = "comet"
    )
    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(176.dp)) {
            val strokePx = 12.dp.toPx()
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2, strokePx / 2)
            // 轨道整圈
            drawArc(
                color = trackColor,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = stroke
            )
            // 进度弧：顶部起顺时针（v4 设计稿 35%≈右上象限）
            drawArc(
                color = accent,
                startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                topLeft = topLeft, size = arcSize, style = stroke
            )
            // 彗星弧：运行中 40° 亮段绕环（显著动画，scan_animations 立法）
            if (animating) {
                drawArc(
                    color = Color.White.copy(alpha = 0.85f),
                    startAngle = sweep, sweepAngle = 40f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = stroke
                )
            }
        }
        Text(
            text = centerText,
            fontSize = 48.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            textAlign = TextAlign.Center
        )
    }
}

/** 顶部 4dp 全宽本轮进度条（v4 progBar）：轨道 surfaceVariant，填充=本轮会话分数；
 *  暂停=琥珀；运行=绿+56dp 流光带循环扫过填充段。 */
@Composable
private fun ScanProgressBar(
    fraction: Float,
    running: Boolean,
    paused: Boolean
) {
    val accent = if (paused) StatusColor.warningAmber else MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "progShimmer")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1100)),
        label = "shimmer"
    )
    val fillFraction = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fillFraction)
                .height(4.dp)
                .background(accent)
        )
        if (running && fillFraction > 0.02f) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .height(4.dp)
                    .clipToBounds()
            ) {
                val travel = maxWidth + 56.dp
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(56.dp)
                        .offset(x = -56.dp + travel * shimmer)
                        .background(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent)
                            )
                        )
                )
            }
        }
    }
}

/** 顶栏（v4 top_bar）：48dp surface 底，「扫描整理」15sp SemiBold + 本轮计数列。 */
@Composable
private fun ScanTopBar(counter: String?, subline: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.tag_scan_page_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        if (counter != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = counter,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (subline != null) {
                    Text(
                        text = subline,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** 底部操作条（v4 bottom_bar）：主/次双钮 52dp r12 16sp SemiBold；次钮=描边幽灵样式。
 *  idle=开始扫描/稍后；running=暂停扫描/停止；paused=继续扫描/重新开始；
 *  过渡态=禁用占位；终态失败=重试失败项/停止。 */
@Suppress("LongParameterList")
@Composable
private fun BottomActionBar(
    model: ScanCardUiModel?,
    running: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onRetryFailed: () -> Unit,
    onLater: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when {
            model == null -> {
                BottomPrimaryButton(text = stringResource(R.string.tag_scan_btn_start), onClick = onStart, modifier = Modifier.weight(1f))
                BottomOutlineButton(text = stringResource(R.string.tag_scan_btn_later), onClick = onLater, modifier = Modifier.weight(1f))
            }
            model.isTerminalWithFailures -> {
                BottomPrimaryButton(text = stringResource(R.string.tag_scan_retry_failed_items), onClick = onRetryFailed, modifier = Modifier.weight(1f))
                BottomOutlineButton(text = stringResource(R.string.tag_scan_btn_stop), onClick = onStop, modifier = Modifier.weight(1f))
            }
            model.isPaused -> {
                BottomPrimaryButton(text = stringResource(R.string.tag_scan_btn_resume_scan), onClick = onResume, modifier = Modifier.weight(1f))
                BottomOutlineButton(text = stringResource(R.string.tag_scan_btn_restart), onClick = onRestart, modifier = Modifier.weight(1f))
            }
            model.primaryAction == ScanCardAction.PAUSE -> {
                BottomPrimaryButton(text = stringResource(R.string.tag_scan_btn_pause_scan), onClick = onPause, modifier = Modifier.weight(1f))
                BottomOutlineButton(text = stringResource(R.string.tag_scan_btn_stop), onClick = onStop, modifier = Modifier.weight(1f))
            }
            else -> {
                // 过渡态（pausing/cancelling）：禁用占位保持布局稳定
                BottomPrimaryButton(
                    text = stringResource(
                        if (model.cancelEnabled) R.string.tag_scan_state_pausing
                        else R.string.tag_scan_state_cancelling
                    ),
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f)
                )
                BottomOutlineButton(text = stringResource(R.string.tag_scan_btn_stop), onClick = onStop, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 主操作钮（v4 设计稿 btn_primary）：52dp r12，primary 实底 + onPrimary 16sp SemiBold。 */
@Composable
private fun BottomPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** 次操作钮（v4 设计稿 btn_secondary）：52dp r12，outlineVariant 描边 + 透明底 + 16sp SemiBold。 */
@Composable
private fun BottomOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
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

/** 阶段行：图标 + 标题/描述 + 进度% + chevron。点按/长按=扫描控制弹层（v4.1 查看职责移交成果格）。 */
@Composable
private fun StageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    trailing: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp)
            .height(60.dp),
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

/** 阶段操作底部弹层：增量/全量两个大选项上下排开（单选圈示意推荐项），点选即执行。 */
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

/** 可独立操作的扫描阶段（RegenSheet 内点行弹 StageActionSheet）。 */
private enum class TagStage { FACE, PEOPLE, CONTENT, QUALITY }

private fun stagePercentText(progress: TagPassProgress): String =
    if (progress.isEmpty) "—" else "${progress.percentRounded()}%"
