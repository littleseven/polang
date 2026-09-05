package com.mamba.picme.features.gallery.swipe

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.core.designsystem.PoLangForcedDarkTheme
import com.mamba.picme.domain.swipe.SwipeCandidate
import com.mamba.picme.domain.swipe.SwipeReason
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.features.gallery.dedup.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 品牌渐变（青玉）：Done 态主按钮与整理中心 hub 同源。 */
private val swipeBrandGradient: Brush
    get() = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
    )

private const val IMAGE_LOAD_SIZE = 1080
private const val PRELOAD_AHEAD = 3
private const val SWIPE_THRESHOLD_FRACTION = 0.25f
private const val FLY_OUT_MS = 220
private const val FLY_OUT_DISTANCE_FACTOR = 1.5f

/**
 * 手势快速整理（F2）全屏页：右滑保留 / 左滑跳过 / 上滑删除（点按 = 跳过）。
 * 三态：Loading → Reviewing（大图卡 + 手势 + 顶栏进度/undo）→ Done（统计 + 再来一轮/整批恢复）。
 * 系统回收站授权经 TrashSessionController.pendingRequest 以 StartIntentSenderForResult 拉起
 * （同 OrganizeCategoryScreen 范式）。
 */
@Composable
fun SwipeReviewScreen(
    viewModel: SwipeReviewViewModel,
    onNavigateBack: () -> Unit,
) {
    PoLangForcedDarkTheme {
        val uiState by viewModel.uiState.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        SwipeTrashAuthEffects(viewModel = viewModel, snackbarHostState = snackbarHostState)

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                SwipeTopBar(
                    state = uiState,
                    onNavigateBack = onNavigateBack,
                    onUndo = { viewModel.undo() },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                when (val state = uiState) {
                    SwipeUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    is SwipeUiState.Reviewing -> SwipeReviewingContent(
                        state = state,
                        onDecide = { decision -> viewModel.decide(decision) },
                    )
                    is SwipeUiState.Done -> {
                        if (state.kept == 0 && state.deleted == 0 && state.skipped == 0) {
                            Text(
                                text = stringResource(R.string.swipe_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            SwipeDoneContent(
                                state = state,
                                onOneMoreRound = { viewModel.oneMoreRound() },
                                onUndoAll = { viewModel.undoAll() },
                                onBack = onNavigateBack,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 回收站授权拉起 + 一次性事件 snackbar（同 OrganizeCategoryScreen 范式）：
 * pendingRequest → StartIntentSenderForResult（isRestore 分流）；
 * partialNotice / errorEvent → 置位即消费并提示。
 */
@Composable
private fun SwipeTrashAuthEffects(
    viewModel: SwipeReviewViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val pendingRequest by viewModel.trashController.pendingRequest.collectAsState()
    val partialNotice by viewModel.trashController.partialNotice.collectAsState()
    val trashError by viewModel.trashController.errorEvent.collectAsState()

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

    // 部分拒绝一次性提示（置位 → 消费 → snackbar）
    val partialMessage = stringResource(R.string.org_partial_trash)
    LaunchedEffect(partialNotice) {
        if (partialNotice) {
            viewModel.trashController.consumePartialNotice()
            snackbarHostState.showSnackbar(partialMessage)
        }
    }

    // 回收站不可用（API<30）/ token 构建失败
    val unsupportedMessage = stringResource(R.string.org_trash_unsupported)
    LaunchedEffect(trashError) {
        if (trashError) {
            viewModel.trashController.consumeErrorEvent()
            snackbarHostState.showSnackbar(unsupportedMessage)
        }
    }
}

// ---------- 顶栏 ----------

/** 顶栏：返回 + 居中进度「N / M」+ 副行「X freed」+ 右 undo（无决策时禁用）。 */
@Composable
private fun SwipeTopBar(
    state: SwipeUiState,
    onNavigateBack: () -> Unit,
    onUndo: () -> Unit,
) {
    val reviewing = state as? SwipeUiState.Reviewing
    AppTopBar(
        centered = true,
        title = {
            if (reviewing != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            R.string.swipe_progress,
                            (reviewing.index + 1).coerceAtMost(reviewing.queue.size),
                            reviewing.queue.size,
                        ),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.swipe_freed, formatBytes(reviewing.freedBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(text = stringResource(R.string.swipe_title))
            }
        },
        navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) },
        actions = {
            if (reviewing != null) {
                AppTopBarAction(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.swipe_undo_cd),
                    enabled = reviewing.decisions.isNotEmpty(),
                    onClick = onUndo,
                )
            }
        },
    )
}

// ---------- Reviewing 态 ----------

@Composable
private fun SwipeReviewingContent(
    state: SwipeUiState.Reviewing,
    onDecide: (SwipeDecision) -> Unit,
) {
    val context = LocalContext.current

    // 预加载后续 3 张（与大图卡同为 1080 档）
    LaunchedEffect(state.index) {
        val loader = context.imageLoader
        for (ahead in 1..PRELOAD_AHEAD) {
            state.queue.getOrNull(state.index + ahead)?.let { next ->
                loader.enqueue(
                    ImageRequest.Builder(context).data(next.uri).size(IMAGE_LOAD_SIZE).build()
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val candidate = state.current
            if (candidate == null) {
                // 末张决策后 finish 在途（等待回收站授权结算），state 仍停在 Reviewing
                CircularProgressIndicator()
            } else {
                SwipeCard(candidate = candidate, index = state.index, onDecide = onDecide)
            }
        }
        SwipeHintRow()
    }
}

/**
 * 中央大图卡：跟手拖拽 + 松手超阈值（卡片宽 25%）飞出后落决策，未超阈值弹回。
 * 主位移轴定方向：水平主导 → 右 KEEP / 左 SKIP；垂直主导且向上 → DELETE（不与水平冲突）。
 */
@Composable
private fun SwipeCard(
    candidate: SwipeCandidate,
    index: Int,
    onDecide: (SwipeDecision) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    // 卡片切换（含 undo 回退）后归位
    LaunchedEffect(index) {
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }

    val reasonLabel = stringResource(swipeReasonLabelRes(candidate.reason))
    val cardDescription = reasonLabel + ", " + stringResource(R.string.media_type_photo)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = cardDescription }
            .pointerInput(index) {
                detectTapGestures(onTap = { onDecide(SwipeDecision.SKIP) })
            }
            .pointerInput(index) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount.x)
                            offsetY.snapTo(offsetY.value + dragAmount.y)
                        }
                    },
                    onDragCancel = { resetCardOffset(scope, offsetX, offsetY) },
                    onDragEnd = {
                        settleCardDrag(
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            scope = scope,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            onDecide = onDecide,
                        )
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = candidate.uri,
            transitionSpec = { fadeIn(tween(FLY_OUT_MS)) togetherWith fadeOut(tween(FLY_OUT_MS)) },
            label = "swipeCardImage",
        ) { uri ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .size(IMAGE_LOAD_SIZE)
                    // Coil 铁律：禁 crossfade，防 recycled bitmap 崩溃
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxSize(),
            )
        }
        SwipeReasonBadge(label = reasonLabel, modifier = Modifier.align(Alignment.TopStart))
    }
}

/** 左上角入列原因角标 capsule（surfaceVariant 底）。 */
@Composable
private fun SwipeReasonBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 底部三列手势 hint：← Skip / ↑ Delete / Keep →（Keep 用 primary 强调）。 */
@Composable
private fun SwipeHintRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.swipe_hint_skip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.swipe_hint_delete),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.swipe_hint_keep),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------- 手势结算（非 Composable，供手势回调调用） ----------

private fun resetCardOffset(
    scope: CoroutineScope,
    offsetX: Animatable<Float, AnimationVector1D>,
    offsetY: Animatable<Float, AnimationVector1D>,
) {
    scope.launch {
        coroutineScope {
            launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
        }
    }
}

/** 松手结算：按主位移轴判方向，超阈值沿决策轴飞出后落决策，否则弹回。 */
private fun settleCardDrag(
    width: Float,
    height: Float,
    scope: CoroutineScope,
    offsetX: Animatable<Float, AnimationVector1D>,
    offsetY: Animatable<Float, AnimationVector1D>,
    onDecide: (SwipeDecision) -> Unit,
) {
    val threshold = width * SWIPE_THRESHOLD_FRACTION
    val dx = offsetX.value
    val dy = offsetY.value
    val decision = when {
        abs(dx) >= abs(dy) && dx > threshold -> SwipeDecision.KEEP
        abs(dx) >= abs(dy) && dx < -threshold -> SwipeDecision.SKIP
        abs(dy) > abs(dx) && dy < -threshold -> SwipeDecision.DELETE
        else -> null
    }
    if (decision == null) {
        resetCardOffset(scope, offsetX, offsetY)
        return
    }
    scope.launch {
        coroutineScope {
            // 飞出沿决策轴走直线，另一轴保持当前位移
            val targetX = when (decision) {
                SwipeDecision.KEEP -> width * FLY_OUT_DISTANCE_FACTOR
                SwipeDecision.SKIP -> -width * FLY_OUT_DISTANCE_FACTOR
                SwipeDecision.DELETE -> dx
            }
            val targetY = if (decision == SwipeDecision.DELETE) {
                -height * FLY_OUT_DISTANCE_FACTOR
            } else {
                dy
            }
            launch { offsetX.animateTo(targetX, tween(FLY_OUT_MS)) }
            launch { offsetY.animateTo(targetY, tween(FLY_OUT_MS)) }
        }
        onDecide(decision)
    }
}

// ---------- Done 态 ----------

/** 完成态：✓ 圆标 + Round complete + 三数字统计卡 + 渐变主按钮（再来一轮）+ 文本钮（整批恢复/返回）。 */
@Composable
private fun SwipeDoneContent(
    state: SwipeUiState.Done,
    onOneMoreRound: () -> Unit,
    onUndoAll: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.swipe_done_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.swipe_done_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        SwipeDoneStatsCard(state = state)
        Spacer(modifier = Modifier.weight(1f))
        SwipeGradientButton(
            text = stringResource(R.string.swipe_done_more),
            onClick = onOneMoreRound,
        )
        if (state.deleted > 0 && state.trashedUris.isNotEmpty()) {
            TextButton(onClick = onUndoAll, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.org_undo))
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.swipe_done_back))
        }
    }
}

/** 统计卡：45 Kept / 38 Deleted（error 粉）/ 3 Skipped + 「Space freed this round X」高亮数值。 */
@Composable
private fun SwipeDoneStatsCard(state: SwipeUiState.Done) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SwipeDoneStat(
                    value = state.kept,
                    label = stringResource(R.string.swipe_done_kept),
                    modifier = Modifier.weight(1f),
                )
                SwipeDoneStat(
                    value = state.deleted,
                    label = stringResource(R.string.swipe_done_deleted),
                    valueColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                SwipeDoneStat(
                    value = state.skipped,
                    label = stringResource(R.string.swipe_done_skipped),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 「Space freed this round 45 MB」：数值段加粗 + primary 高亮（其余随 onSurfaceVariant）
            val freed = formatBytes(state.freedBytes)
            val fullText = stringResource(R.string.swipe_done_freed, freed)
            val accent = MaterialTheme.colorScheme.primary
            val annotated = remember(fullText, freed, accent) {
                buildAnnotatedString {
                    append(fullText)
                    val start = fullText.indexOf(freed)
                    if (start >= 0) {
                        addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold, color = accent),
                            start,
                            start + freed.length,
                        )
                    }
                }
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SwipeDoneStat(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 渐变主按钮（与整理中心 OrganizeUndoBar 同造型）。 */
@Composable
private fun SwipeGradientButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(swipeBrandGradient)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

// ---------- 资源映射 ----------

private fun swipeReasonLabelRes(reason: SwipeReason): Int = when (reason) {
    SwipeReason.SCREENSHOT -> R.string.swipe_reason_screenshot
    SwipeReason.BLURRY -> R.string.swipe_reason_blurry
    SwipeReason.LOW_QUALITY_PORTRAIT -> R.string.swipe_reason_portrait
    SwipeReason.RECENT -> R.string.swipe_reason_recent
}
