@file:Suppress("TooManyFunctions") // 待重构：ChatScreen 拆分为多个子文件以降低文件级函数数
@file:OptIn(ExperimentalLayoutApi::class)

package com.mamba.picme.features.chat

import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.domain.chat.ChatMessageType
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.domain.chat.LlmPerformance
import com.mamba.picme.domain.chat.ClaudeStepStatus
import com.mamba.picme.domain.chat.ClaudeStepUi
import com.mamba.picme.domain.chat.markdown.MarkdownTable
import com.mamba.picme.domain.chat.markdown.SegmentType
import com.mamba.picme.domain.chat.markdown.codeLineCount
import com.mamba.picme.domain.chat.markdown.extractCodeBody
import com.mamba.picme.domain.chat.markdown.parseMarkdownTable
import com.mamba.picme.domain.chat.markdown.previewCode
import com.mamba.picme.domain.chat.markdown.segmentMarkdown
import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import androidx.compose.material.icons.outlined.Menu
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.features.chat.ChatThreadSidebar
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.preferences.UserPreferencesRepository
import dev.jeziellago.compose.markdowntext.MarkdownText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.shadow
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
import com.mamba.picme.features.chat.capability.ChatRunScriptCapability
import com.mamba.picme.features.chat.capability.ChatMediaWriteCapability
import com.mamba.picme.agent.core.model.command.CommandRisk
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.chat.capability.ChatStartTagScanCapability
import com.mamba.picme.features.chat.components.ChatEmptyState
import com.mamba.picme.features.chat.components.ChatPhotoPickerSheet
import com.mamba.picme.features.chat.components.ChatRegistrationSheet
import com.mamba.picme.features.chat.components.EngineerTaskCard
import com.mamba.picme.features.chat.components.GuestNudgeBanner
import com.mamba.picme.features.chat.components.GachaCandidateStrip
import com.mamba.picme.features.chat.components.MediaResultsCarousel
import androidx.core.net.toUri
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.components.MediaPager
import com.mamba.picme.features.gallery.components.TRASH_TAG_PREVIEW_CHAT
import com.mamba.picme.features.gallery.components.TrashAuthEffects
import com.mamba.picme.domain.trash.PreviewTrashRouting
import com.mamba.picme.domain.trash.TrashOutcome
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import androidx.compose.runtime.mutableIntStateOf
import com.mamba.picme.agent.core.platform.voice.SherpaOnnxAsrEngine
import com.mamba.picme.features.camera.voice.SystemAsrEngine
import com.mamba.picme.features.camera.voice.PushToTalkEngine
import com.mamba.picme.features.camera.voice.createDefaultAsrEngine
import com.mamba.picme.features.settings.SettingsViewModel
import java.io.File

private const val TAG = "ChatScreen"

/** 任务中心回锚等待超时（US-15 兜底）：超时未锚定自动放弃并恢复自动滚底 */
private const val ANCHOR_TIMEOUT_MS = 8000L

/** 任务中心回锚请求（US-15）：目标会话 + 目标任务卡 + nonce（重复锚同卡也能触发新一次滚动）。 */
data class ChatTaskAnchor(val sessionId: String, val taskId: String, val nonce: Long)

/**
 * Chat 二级页 — AI 对话入口
 *
 * 从相册首页通过 plus 菜单进入。页面提供返回按钮回到相册。
 * 布局：
 * - 顶部栏：返回 + 菜单 + 任务中心（角标） + 清空 + 新建（设置入口仅在相册首页，不在每个页面重复）
 * - 消息列表：LazyColumn 展示对话历史
 * - 输入区：ModelSelector + 输入框 + 发送按钮
 * - 快捷入口：相机 / 模型中心
 */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // 待重构：Top-level Compose screen，scaffold+list+input+sidebar，后续按区域拆子组件
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: (String) -> Unit = {},
    mediaViewModel: MediaViewModel,
    onNavigateToPhotoEditor: (uri: String, autoOptimize: Boolean) -> Unit = { _, _ -> },
    onNavigateToIDPhoto: (uri: String) -> Unit = {},
    /** 任务中心入口（US-12 顶栏任务图标） */
    onNavigateToTaskCenter: () -> Unit = {},
    /** 任务中心回锚请求（US-15）；非 null 时切会话并滚动锚定对应任务卡 */
    taskAnchor: ChatTaskAnchor? = null,
    onTaskAnchorConsumed: () -> Unit = {},
    /** 上报是否允许外层主页面 Pager 横滑（预览打开时禁用） */
    onHorizontalSwipeEnabledChange: (Boolean) -> Unit = {},
    /** 是否为当前激活的主页面 page（非激活时禁用内部 BackHandler，避免跨页抢占系统返回键） */
    isActivePage: Boolean = true,
    /** 后台用户任务活跃数（用户任务协议 §7）：与工程师活跃数合并进顶栏任务中心角标 */
    activeUserTaskCount: Int = 0
) {
    val context = LocalContext.current
    val messages by viewModel.displayMessages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val engineerActionInFlight by viewModel.engineerActionInFlight.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val threads by viewModel.filteredThreads.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val isGuestMode by viewModel.isGuestMode.collectAsState()
    val showRegistration by viewModel.showRegistrationSheet.collectAsState()
    val showGuestBanner by viewModel.showGuestBanner.collectAsState()
    val guestMessageCount by viewModel.guestMessageCount.collectAsState()
    val issueReportState by viewModel.issueReportState.collectAsState()
    val canDeliverClaude by viewModel.canDeliverClaude.collectAsState()
    val activeEngineerTaskCount by viewModel.activeEngineerTaskCount.collectAsState()
    val gachaSelections by viewModel.gachaSelections.collectAsState()
    val gachaRerolling by viewModel.gachaRerolling.collectAsState()
    // 抽卡条确认/换一组失败 toast 文案（闭包回调内无法取 stringResource，提前取）
    val gachaRerollUnavailableText = stringResource(R.string.chat_gacha_reroll_unavailable)
    val gachaConfirmFailedText = stringResource(R.string.chat_gacha_confirm_failed)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 任务中心回锚（US-15）两阶段自动滚底抑制：pending（等待锚定）+ hold（锚定后防流式
    // delta 拉走——条数增长（新回合/新消息）或用户主动拖拽才解除）
    var pendingTaskAnchor by remember { mutableStateOf<ChatTaskAnchor?>(null) }
    var anchorHold by remember { mutableStateOf<Pair<Long, Int>?>(null) }

    var isSidebarOpen by remember { mutableStateOf(false) }
    var showReportIssueDialog by remember { mutableStateOf(false) }
    // 图片预览状态（横滑翻页集合）
    var imagePreview by remember { mutableStateOf<ChatImagePreviewState?>(null) }
    var previewChartSvg by remember { mutableStateOf<String?>(null) }
    // HTML 卡片外链落地页预览状态（点击卡片内 <a> 链接打开）
    var previewLinkUrl by remember { mutableStateOf<String?>(null) }
    // 表格全屏预览状态（点击气泡内表格打开）
    var expandedTable by remember { mutableStateOf<MarkdownTable?>(null) }
    // 相册搜索结果预览状态
    var previewAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    // 已点删除但等待媒体库刷新确认的图片 ID
    var pendingDeletedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 任一全屏预览打开（照片轮播/图片/图表/表格/HTML 外链落地页）：禁外层横滑 + 隐藏顶栏 + 拦截返回键
    val anyPreviewOpen = previewAssets.isNotEmpty() || imagePreview != null ||
        previewChartSvg != null || expandedTable != null || previewLinkUrl != null

    // 上报外层 Pager 横滑使能：任一全屏预览打开时禁用，避免与内层预览滑动冲突
    LaunchedEffect(anyPreviewOpen) {
        onHorizontalSwipeEnabledChange(!anyPreviewOpen)
    }

    // 媒体库全量数据：用于感知删除完成并同步清理 preview/chat 消息
    val allMedia by mediaViewModel.allMedia.collectAsState()
    val deleteAuthRequest by mediaViewModel.deleteAuthRequest.collectAsState()

    // Android 10 (API 29) 恢复性删除权限请求 launcher
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

    // Android 11+ 删除权限请求 launcher
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

    // ChatViewModel 侧（JS capability.dispatch 删除）触发的系统授权请求，复用上面的 launcher
    val chatWriteDeleteAuthRequest by viewModel.deleteAuthRequest.collectAsState()
    LaunchedEffect(chatWriteDeleteAuthRequest) {
        chatWriteDeleteAuthRequest?.let { request ->
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
            viewModel.consumeDeleteAuthRequest()
        }
    }

    // ── 预览页上滑删除（回收站 30 天可恢复）：授权拉起 + Trashed outcome 后收缩预览列表 ──
    TrashAuthEffects(controller = mediaViewModel.trashController, tag = TRASH_TAG_PREVIEW_CHAT)
    LaunchedEffect(Unit) {
        mediaViewModel.trashController.outcomes.collect { outcome ->
            if (outcome is TrashOutcome.Trashed && outcome.tag == TRASH_TAG_PREVIEW_CHAT) {
                val trashed = outcome.trashedUris.toSet()
                // 聊天流内联结果图同步清理，防死图（同 pendingDeletedIds 确认管线口径）
                previewAssets.filter { it.uri in trashed }.forEach { asset ->
                    viewModel.removeMediaResultAsset(asset.id)
                }
                previewAssets = previewAssets.filter { it.uri !in trashed }
            }
        }
    }

    // 当媒体库刷新后发现 preview 中的某张图已被物理删除，同步清理 preview 列表和 chat 消息
    LaunchedEffect(allMedia) {
        if (allMedia.isEmpty()) return@LaunchedEffect
        val existingIds = allMedia.map { it.id }.toSet()

        // 确认 pending 删除已实际生效（物理文件 + Room 记录已清理）
        if (pendingDeletedIds.isNotEmpty()) {
            val confirmedRemoved = pendingDeletedIds.filter { it != 0L && it !in existingIds }.toSet()
            if (confirmedRemoved.isNotEmpty()) {
                confirmedRemoved.forEach { viewModel.removeMediaResultAsset(it) }
                pendingDeletedIds = pendingDeletedIds - confirmedRemoved
            }
        }

        // 兜底：其他途径导致 previewAssets 中的图片已不在媒体库时，也清理 preview
        if (previewAssets.isNotEmpty()) {
            val removedIds = previewAssets
                .map { it.id }
                .filter { it != 0L && it !in existingIds }
                .toSet()
            if (removedIds.isNotEmpty()) {
                previewAssets = previewAssets.filter { it.id in existingIds }
            }
        }
    }

    // chat 系 Capability 已在 PoLangApplication 注册到全局 CapabilityRegistry（唯一注册表），
    // 本页只负责 delegate 绑定/解绑（2026-07-29 单轨收敛，Compose CapabilityHost 已退役）

    // 绑定 ChatSearchCapability Delegate（chat 场景相册搜索执行器）
    DisposableEffect(Unit) {
        ChatSearchCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatSearchCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatGallerySummaryCapability Delegate
    DisposableEffect(Unit) {
        ChatGallerySummaryCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatGallerySummaryCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatRunScriptCapability Delegate（端侧 JS 执行）
    DisposableEffect(Unit) {
        ChatRunScriptCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatRunScriptCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatStartTagScanCapability Delegate
    DisposableEffect(Unit) {
        ChatStartTagScanCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatStartTagScanCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatMediaWriteCapability Delegate（JS 写通路：删除/收藏/选中）
    DisposableEffect(Unit) {
        ChatMediaWriteCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatMediaWriteCapability.getInstance().unbindDelegate() }
    }

    BackHandler(enabled = isActivePage && isSidebarOpen) {
        isSidebarOpen = false
    }

    // 预览打开时拦截系统返回键：关闭预览并回到 chat 页（保留横滑卡片），
    // 而非直接 pop 到相册（Gallery 为 startDestination，栈底为 [Gallery, Chat]）。
    // 与 GalleryScreen 的预览 BackHandler 行为对齐。
    BackHandler(enabled = isActivePage && anyPreviewOpen) {
        when {
            previewAssets.isNotEmpty() -> previewAssets = emptyList()
            imagePreview != null -> imagePreview = null
            previewChartSvg != null -> previewChartSvg = null
            expandedTable != null -> expandedTable = null
            previewLinkUrl != null -> previewLinkUrl = null
        }
    }

    // AI 优化命令触发后导航到编辑器
    val pendingOptimizeUri by viewModel.pendingAiOptimizeNavigation.collectAsState()
    LaunchedEffect(pendingOptimizeUri) {
        pendingOptimizeUri?.let { uri ->
            onNavigateToPhotoEditor(uri, true)
            viewModel.consumeAiOptimizeNavigation()
        }
    }

    // 自动滚动到底部：列表条数变化或最后一条内容变化时触发（支持流式打字效果）；
    // 回锚 pending/hold 期间跳过（plain read 在 effect 重跑时取最新值，抑制态不入 key 防自身触发滚动）
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        anchorHold?.let { hold -> if (messages.size > hold.second) anchorHold = null }
        if (pendingTaskAnchor == null && anchorHold == null && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 任务中心回锚（US-15）：先切到任务所属会话（会话加载是异步的，滚动由下一 effect 等消息就位）
    // 会话切换时清残留 hold：否则切到消息更少的会话后条数恒超不过 hold.second，
    // 自动滚底被持续抑制，只能靠用户拖拽自愈
    LaunchedEffect(currentSessionId) { anchorHold = null }
    LaunchedEffect(taskAnchor) {
        taskAnchor?.let { anchor ->
            pendingTaskAnchor = anchor
            if (viewModel.currentSessionId.value != anchor.sessionId) {
                viewModel.switchSession(anchor.sessionId)
            }
        }
    }
    // 锚定滚动：消息列表出现该任务卡（id == taskId）后瞬时滚动到位并消费请求；
    // 随后进入 hold 态——流式 delta 不再拉到底部，直到新消息到来或用户拖拽
    LaunchedEffect(pendingTaskAnchor?.nonce, messages) {
        val anchor = pendingTaskAnchor ?: return@LaunchedEffect
        val index = messages.indexOfFirst { msg -> msg.id == anchor.taskId }
        if (index >= 0) {
            listState.scrollToItem(index)
            anchorHold = anchor.nonce to messages.size
            pendingTaskAnchor = null
            onTaskAnchorConsumed()
        }
    }
    // 锚定兜底：8s 未等到任务卡（极端情况：消息被清理）自动放弃，恢复自动滚底
    LaunchedEffect(pendingTaskAnchor?.nonce) {
        val anchor = pendingTaskAnchor ?: return@LaunchedEffect
        delay(ANCHOR_TIMEOUT_MS)
        if (pendingTaskAnchor?.nonce == anchor.nonce) {
            pendingTaskAnchor = null
            onTaskAnchorConsumed()
        }
    }
    // 用户主动拖拽即解除锚定保持（恢复自动滚底）
    LaunchedEffect(anchorHold?.first) {
        if (anchorHold == null) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) anchorHold = null
        }
    }

    // 进入聊天页后，若已开启本地推理或语音功能，在蜂窝网络下检查 Tier 2 模型
    LaunchedEffect(Unit) {
        settingsViewModel.checkChatModelsOnCellular()
    }

    // 问题上报结果反馈
    LaunchedEffect(issueReportState) {
        when (val state = issueReportState) {
            is IssueReportState.Success -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.report_issue_success, state.issueId),
                    Toast.LENGTH_SHORT,
                ).show()
                showReportIssueDialog = false
                viewModel.resetIssueReportState()
            }
            is IssueReportState.Error -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.report_issue_error, state.message),
                    Toast.LENGTH_LONG,
                ).show()
                viewModel.resetIssueReportState()
            }
            else -> {}
        }
    }

    if (showReportIssueDialog) {
        ReportIssueDialog(
            state = issueReportState,
            isGuest = isGuestMode,
            onDismiss = { showReportIssueDialog = false },
            onSubmit = { category, title, description ->
                viewModel.submitIssueReport(category, title, description)
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 预览（照片轮播 / 图片 / 图表 / HTML 卡片 / 表格全屏）打开时隐藏 chat 顶栏，让覆盖层占满整屏
            if (!anyPreviewOpen) {
                ChatTopBar(
                    onNavigateBack = onNavigateBack,
                    onOpenSidebar = { isSidebarOpen = true },
                    onNewChat = { viewModel.newSession() },
                    onClearChat = { viewModel.clearChat() },
                    onReportIssue = { showReportIssueDialog = true },
                    activeTaskCount = activeEngineerTaskCount + activeUserTaskCount,
                    onNavigateToTaskCenter = onNavigateToTaskCenter,
                    isActivePage = isActivePage
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // 消息列表 / 空状态引导
                if (messages.isEmpty()) {
                    ChatEmptyState(
                        isGuestMode = isGuestMode,
                        onExampleClick = { text -> viewModel.sendMessage(text) },
                        onRegisterClick = { viewModel.openRegistrationSheet() },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                } else {
                    // 会话内存在任务卡 ⇒ 气泡上的 claude 审批按钮整体抑制（US-2 审批唯一入口）
                    val hasTaskCards = messages.any { msg -> msg.type == ChatMessageType.TASK_CARD }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            val mr = message.mediaResults
                            if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null) {
                                MediaResultsCarousel(
                                    mediaResults = mr,
                                    onCardClick = { index ->
                                        previewAssets = mr.assets
                                        previewIndex = index
                                    },
                                    onViewAll = {
                                        onNavigateToGallery(mr.query)
                                    },
                                    onFeedback = { mediaId, action ->
                                        viewModel.onMediaFeedback(mediaId, mr.query, action)
                                    }
                                )
                            } else if (message.type == ChatMessageType.CHART && message.chartSvg != null) {
                                val chartSvg = message.chartSvg!!
                                ChartSvgCard(svg = chartSvg, onClick = { previewChartSvg = chartSvg })
                            } else if (message.type == ChatMessageType.HTML_CARD && message.htmlContent != null) {
                                val html = message.htmlContent!!
                                // 卡片内元素直接交互；仅 <a> 外链点击打开全屏落地页；
                                // isListScrolling 用于滑动途中冻结卡片高度更新（防列表抖动）
                                HtmlCard(
                                    html = html,
                                    isListScrolling = listState.isScrollInProgress,
                                    onOpenLink = { url -> previewLinkUrl = url }
                                )
                            } else if (message.type == ChatMessageType.TASK_CARD && message.engineerTask != null) {
                                val task = message.engineerTask
                                if (task != null) {
                                    var taskExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
                                    EngineerTaskCard(
                                        task = task,
                                        expanded = taskExpanded,
                                        actionsEnabled = !isProcessing && task.taskId !in engineerActionInFlight,
                                        onViewAll = onNavigateToTaskCenter,
                                        onToggleExpand = { taskExpanded = !taskExpanded },
                                        onContinue = { viewModel.continueEngineerTask(task.taskId) },
                                        onAbandon = { viewModel.abandonEngineerTask(task.taskId) },
                                        onDeliver = { viewModel.deliverEngineerTask(task.taskId) },
                                        onSkipDeliver = { viewModel.skipEngineerDeliver(task.taskId) },
                                        onRetry = { viewModel.retryEngineerTask(task.taskId) },
                                    )
                                }
                            } else if (message.type == ChatMessageType.OPTIMIZE_CANDIDATES && message.optimizeCandidates != null) {
                                val group = message.optimizeCandidates!!
                                val selected = gachaSelections[message.id] ?: group.recommendedIndex
                                GachaCandidateStrip(
                                    group = group,
                                    interactive = message.gachaInteractive,
                                    selectedIndex = selected,
                                    rerolling = message.id in gachaRerolling,
                                    onSelect = { index ->
                                        viewModel.onOptimizeGachaSelection(message.id, index)
                                        // 点卡 = 选中 + 全屏预览该组候选（isEditableResult=false → 无保存按钮）
                                        val pages = group.candidates.mapIndexedNotNull { i, c ->
                                            c.thumbPath.takeIf { it.isNotBlank() }?.let { path ->
                                                ImagePreviewPage(
                                                    messageId = "${message.id}#$i",
                                                    rawUri = path,
                                                    isEditableResult = false,
                                                    isSaved = false
                                                )
                                            }
                                        }
                                        if (pages.isNotEmpty()) {
                                            val startAt = pages.indexOfFirst { it.messageId == "${message.id}#$index" }
                                                .coerceAtLeast(0)
                                            imagePreview = ChatImagePreviewState(pages = pages, initialIndex = startAt)
                                        }
                                    },
                                    onReroll = {
                                        viewModel.onOptimizeGachaReroll(message.id) { ok ->
                                            if (!ok) Toast.makeText(context, gachaRerollUnavailableText, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onConfirm = {
                                        viewModel.onOptimizeGachaConfirm(message.id, selected) { ok ->
                                            if (!ok) Toast.makeText(context, gachaConfirmFailedText, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            } else {
                                ChatMessageItem(
                                    message = message,
                                    onImageClick = { msg ->
                                        val pages = buildImagePreviewPages(messages)
                                        if (pages.isNotEmpty()) {
                                            val isEdit = msg.type == ChatMessageType.AGENT_IMAGE ||
                                                msg.type == ChatMessageType.AGENT_EDIT_RESULT
                                            if (isEdit) viewModel.touchEditImage(msg.imageUri)
                                            imagePreview = ChatImagePreviewState(
                                                pages = pages,
                                                initialIndex = indexOfPage(pages, msg.id)
                                            )
                                        }
                                    },
                                    onClaudeDeliver = { id, mode -> viewModel.confirmClaudeDeliver(id, mode) },
                                    onClaudeContinue = { viewModel.continueClaude() },
                                    canDeliverClaude = canDeliverClaude,
                                    suppressClaudeActions = hasTaskCards,
                                    onTableClick = { table -> expandedTable = table }
                                )
                            }
                        }
                    }
                }

                // 访客渐进引导 banner（累计 ≥20 条后常驻，可关闭）
                if (showGuestBanner) {
                    GuestNudgeBanner(
                        guestMessageCount = guestMessageCount,
                        onRegister = { viewModel.openRegistrationSheet() },
                        onUseOwnKey = { onNavigateToSettings() },
                        onDismiss = { viewModel.dismissGuestBanner() },
                    )
                }

                // 输入区
                ChatInputArea(
                    isProcessing = isProcessing,
                    onSendMessage = { text ->
                        viewModel.sendMessage(text)
                    },
                    mediaViewModel = mediaViewModel,
                    viewModel = viewModel,
                    onNavigateToPhotoEditor = onNavigateToPhotoEditor,
                )
            }

            // 侧边栏
            ChatThreadSidebar(
                visible = isSidebarOpen,
                threads = threads,
                currentSessionId = currentSessionId,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onThreadSelected = { sessionId ->
                    viewModel.switchSession(sessionId)
                    isSidebarOpen = false
                },
                onNewChat = {
                    viewModel.newSession()
                    isSidebarOpen = false
                },
                onRename = { sessionId, newTitle ->
                    viewModel.renameSession(sessionId, newTitle)
                },
                onDelete = { sessionId ->
                    viewModel.deleteSession(sessionId)
                },
                onDismiss = { isSidebarOpen = false }
            )

            // 图片全屏预览（横滑翻页）
            ChatImagePreviewOverlay(
                state = imagePreview,
                onSave = { messageId, onDone ->
                    viewModel.saveEditResult(messageId) { ok ->
                        if (ok) {
                            imagePreview = imagePreview?.let { s ->
                                s.copy(
                                    pages = s.pages.map { p ->
                                        if (p.messageId == messageId) p.copy(isSaved = true) else p
                                    }
                                )
                            }
                        }
                        onDone(ok)
                    }
                },
                onPageChanged = { page ->
                    if (page.isEditableResult) viewModel.touchEditImage(page.rawUri)
                },
                onDismiss = { imagePreview = null }
            )

            // 图表全屏预览
            ChartPreviewOverlay(
                svg = previewChartSvg,
                onDismiss = { previewChartSvg = null }
            )

            // HTML 卡片外链落地页（全屏内置浏览器）
            HtmlLinkPreviewOverlay(
                url = previewLinkUrl,
                onDismiss = { previewLinkUrl = null }
            )

            // 表格全屏预览（横屏旋转查看）
            TablePreviewOverlay(
                table = expandedTable,
                onDismiss = { expandedTable = null }
            )

            // 注册引导弹层（访客累计达阈值 / 试用额度用尽 / 空状态小字链接）
            if (showRegistration) {
                ChatRegistrationSheet(
                    guestMessageCount = guestMessageCount,
                    onDismiss = { viewModel.dismissRegistrationSheet() },
                    onUseOwnKey = {
                        onNavigateToSettings()
                        viewModel.dismissRegistrationSheet()
                    },
                    sendCode = viewModel::sendVerificationCode,
                    verifyCode = viewModel::verifyCode,
                )
            }

            // 相册搜索结果全屏预览（覆盖整屏；预览期间隐藏 chat 顶栏，避免顶栏透出）
            if (previewAssets.isNotEmpty()) {
                MediaPager(
                    assets = previewAssets,
                    initialIndex = previewIndex,
                    onClose = { previewAssets = emptyList() },
                    onPageViewed = { uri -> mediaViewModel.markMediaViewed(uri) },
                    onSwipeUpDelete = { asset ->
                        val route = mediaViewModel.requestTrash(asset, TRASH_TAG_PREVIEW_CHAT)
                        if (route == PreviewTrashRouting.Route.LEGACY_DELETE) {
                            // API<30 降级永久删除：记入 pendingDeletedIds，复用既有确认管线清理聊天内联图
                            pendingDeletedIds = pendingDeletedIds + asset.id
                        }
                    },
                    isTrashSupported = mediaViewModel.isTrashSupported,
                    onDelete = { asset ->
                        previewAssets = previewAssets.filter { it.id != asset.id }
                        pendingDeletedIds = pendingDeletedIds + asset.id
                        mediaViewModel.deleteMediaByIds(listOf(asset.id))
                    },
                    onStartOcr = { uriString ->
                        mediaViewModel.recognizeTextFromCurrentImage(context, uriString.toUri())
                    },
                    onDismissOcr = { mediaViewModel.clearOcrResult() },
                    ocrState = mediaViewModel.ocrState,
                    onNavigateToEditor = { asset -> onNavigateToPhotoEditor(asset.uri, false) },
                    onAiOptimize = { asset -> onNavigateToPhotoEditor(asset.uri, true) },
                    onIdPhoto = { asset -> onNavigateToIDPhoto(asset.uri) },
                    onReTag = { _ ->
                        // chat 页 photo info 暂用全量扫描(无 container 直拿 worker);相册页走单张 processSingleSync
                        context.startForegroundService(TagGenerationService.intentScanPass3Full(context))
                        null
                    }
                )
            }
        }
    }

    // ── JS 写操作确认（capability.dispatch 触发，删除/收藏/选中）──────────────
    val pendingWriteConfirmation by viewModel.pendingWriteConfirmation.collectAsState()
    pendingWriteConfirmation?.let { req ->
        val operationText = when (req.method) {
            "delete_media" -> stringResource(R.string.chat_write_confirm_delete, req.targetCount)
            "favorite_media" -> stringResource(R.string.chat_write_confirm_favorite, req.targetCount)
            "select_media" -> stringResource(R.string.chat_write_confirm_select, req.targetCount)
            else -> req.method
        }
        AlertDialog(
            onDismissRequest = { viewModel.resolveWriteConfirmation(false) },
            title = {
                Text(text = stringResource(R.string.chat_write_confirm_title))
            },
            text = {
                Column {
                    Text(text = operationText)
                    // 条目缩略图预览：让用户核实 LLM 选出的删除/操作目标
                    if (req.previewUris.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            req.previewUris.forEach { uri ->
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.chat_write_confirm_ai_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (req.risk == CommandRisk.DESTRUCTIVE) {
                        Text(
                            text = stringResource(R.string.chat_write_confirm_risk_destructive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.chat_write_confirm_risk_reversible),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.resolveWriteConfirmation(true) }) {
                    Text(text = stringResource(R.string.chat_write_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveWriteConfirmation(false) }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    // ── 聊天/语音/本地 LLM 模型下载提示 ────────────────────────────
    val showChatModelsPrompt by settingsViewModel.showChatModelsPrompt.collectAsState()
    val isChatBatchDownloading by settingsViewModel.isBatchDownloading.collectAsState()
    if (showChatModelsPrompt) {
        AlertDialog(
            onDismissRequest = { if (!isChatBatchDownloading) settingsViewModel.dismissChatModelsPrompt() },
            title = {
                Text(text = stringResource(R.string.chat_models_download_title))
            },
            text = {
                Text(text = stringResource(R.string.chat_models_download_message))
            },
            confirmButton = {
                Button(
                    onClick = { settingsViewModel.startChatModelsDownload() },
                    enabled = !isChatBatchDownloading
                ) {
                    Text(
                        text = if (isChatBatchDownloading) {
                            stringResource(R.string.chat_models_download_progress)
                        } else {
                            stringResource(R.string.chat_models_download_button)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { settingsViewModel.dismissChatModelsPrompt() },
                    enabled = !isChatBatchDownloading
                ) {
                    Text(text = stringResource(R.string.chat_models_download_later))
                }
            }
        )
    }
}

@Composable
private fun ChatTopBar(
    onNavigateBack: () -> Unit,
    onOpenSidebar: () -> Unit,
    onNewChat: () -> Unit,
    onClearChat: () -> Unit,
    onReportIssue: () -> Unit = {},
    /** 活动工程师任务数（任务中心入口角标，US-12；0 不显示角标） */
    activeTaskCount: Int = 0,
    onNavigateToTaskCenter: () -> Unit = {},
    isActivePage: Boolean = true
) {
    AppTopBar(
        title = {},
        navigationIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTopBarNavBack(onClick = onNavigateBack, enabled = isActivePage)
                AppTopBarAction(
                    icon = Icons.Outlined.Menu,
                    contentDescription = stringResource(R.string.cd_open_sidebar),
                    onClick = onOpenSidebar
                )
            }
        },
        actions = {
            BadgedBox(
                badge = {
                    if (activeTaskCount > 0) {
                        Badge {
                            Text(
                                text = if (activeTaskCount > 99) {
                                    stringResource(R.string.task_center_badge_overflow)
                                } else {
                                    activeTaskCount.toString()
                                }
                            )
                        }
                    }
                }
            ) {
                AppTopBarAction(
                    icon = Icons.Outlined.Assignment,
                    contentDescription = if (activeTaskCount > 0) {
                        stringResource(R.string.cd_task_center_active, activeTaskCount)
                    } else {
                        stringResource(R.string.cd_task_center)
                    },
                    onClick = onNavigateToTaskCenter
                )
            }
            AppTopBarAction(Icons.Outlined.BugReport, stringResource(R.string.report_issue_cd), onReportIssue)
            AppTopBarAction(Icons.Outlined.AddComment, stringResource(R.string.new_chat), onNewChat)
            AppTopBarAction(Icons.Outlined.DeleteSweep, stringResource(R.string.clear_chat), onClearChat)
        }
    )
}

@Composable
private fun ReportIssueDialog(
    state: IssueReportState,
    isGuest: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (category: String, title: String, description: String) -> Unit,
) {
    var category by remember { mutableStateOf("other") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val submitting = state is IssueReportState.Submitting
    val categories = listOf("crash", "bug", "ai", "other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_issue_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isGuest) {
                    Text(
                        text = stringResource(R.string.report_issue_guest_not_allowed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.report_issue_category_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(stringResource(categoryLabelRes(c))) },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.report_issue_title_label)) },
                    placeholder = { Text(stringResource(R.string.report_issue_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGuest,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.report_issue_description_label)) },
                    placeholder = { Text(stringResource(R.string.report_issue_description_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGuest,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(category, title, description) },
                enabled = !submitting && !isGuest && title.isNotBlank(),
            ) {
                if (submitting) {
                    // 保持按钮宽度稳定，仅显示 "提交中..."
                    Text(stringResource(R.string.report_issue_submit) + "…")
                } else {
                    Text(stringResource(R.string.report_issue_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.report_issue_cancel))
            }
        },
    )
}

@StringRes
private fun categoryLabelRes(category: String): Int = when (category) {
    "crash" -> R.string.report_issue_category_crash
    "bug" -> R.string.report_issue_category_bug
    "ai" -> R.string.report_issue_category_ai
    else -> R.string.report_issue_category_other
}

/** LRU 已清理的编辑结果图占位：灰框 + 图标 + 「图片已过期·不可见」。 */
@Composable
private fun ExpiredImagePlaceholder(height: androidx.compose.ui.unit.Dp = 180.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.chat_edit_image_expired),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 折叠阈值：超过此行数的代码块默认折叠。 */
private const val CODE_COLLAPSE_LINES = 12

/**
 * agent 气泡代码块（spec §3.2）：默认折叠前 [CODE_COLLAPSE_LINES] 行 + 「展开/收起」，
 * 横向滚动（长行不换行撑屏），可复制全文。代码体由 [extractCodeBody] 从围栏段提取。
 */
@Composable
private fun CodeBlock(raw: String) {
    val code = remember(raw) { extractCodeBody(raw) }
    val total = remember(code) { codeLineCount(code) }
    val expandable = total > CODE_COLLAPSE_LINES
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val shown = if (!expandable || expanded) code else previewCode(code, CODE_COLLAPSE_LINES)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Text(
            text = shown,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(end = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (expandable) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) stringResource(R.string.claude_code_collapse)
                        else stringResource(R.string.claude_code_expand_n, total),
                        fontSize = 12.sp,
                    )
                }
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.claude_code_copy),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (copied) {
                Text(
                    text = stringResource(R.string.claude_code_copied),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LaunchedEffect(copied) {
                    delay(1500)
                    copied = false
                }
            }
        }
    }
}

/** agent 文本分段渲染（流式与最终态共用）：MARKDOWN→MarkdownText、TABLE→Compose 网格表格、CODE→CodeBlock。 */
@Composable
private fun SegmentedAgentText(displayText: String, onTableClick: (MarkdownTable) -> Unit) {
    segmentMarkdown(displayText).forEach { segment ->
        when (segment.type) {
            SegmentType.TABLE -> AgentTable(raw = segment.text, onTableClick = onTableClick)
            SegmentType.MARKDOWN -> MarkdownText(
                markdown = segment.text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            )
            SegmentType.CODE -> CodeBlock(raw = segment.text)
        }
    }
}

/** 显示宽度单位：CJK 等全角字符计 2，其余计 1（配合等宽字体估算列宽）。 */
private fun displayUnits(text: String): Int = text.sumOf { ch -> if (ch.code > 0xFF) 2 else 1 }

/** 每列宽度估算上限（单位：显示宽度），超出后该列按份额分配宽度并换行。 */
private const val TABLE_MAX_COL_UNITS = 30

/** 超过估算总宽时，列数 ≤ 此值按份额铺满换行显示，否则横向滚动。 */
private const val TABLE_MAX_FIT_COLUMNS = 3

/** 每列内容宽度估算（显示单位，4..[TABLE_MAX_COL_UNITS]），气泡与全屏预览共用。 */
private fun tableColUnits(table: MarkdownTable): List<Int> =
    table.header.indices.map { col ->
        (listOf(table.header[col]) + table.rows.map { row -> row[col] })
            .maxOf { cell -> displayUnits(cell) }
            .coerceIn(4, TABLE_MAX_COL_UNITS)
    }

/**
 * GFM 表格的原生 Compose 渲染（不走 Markwon，规避位图抖动）：表头加粗带底色、完整网格线。
 * 宽度自适应：估算总宽 ≤ 气泡宽时按内容紧凑排；超宽且列数 ≤ [TABLE_MAX_FIT_COLUMNS] 时
 * 按内容比例铺满气泡宽、长文本换行完整显示；更多列才退化为横向滚动。
 * 点击经 [onTableClick] 上报，由屏幕层打开全屏预览。
 */
@Composable
private fun AgentTable(raw: String, onTableClick: (MarkdownTable) -> Unit) {
    val table = remember(raw) { parseMarkdownTable(raw) }
    if (table.header.isEmpty()) {
        Text(
            text = raw,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
        )
        return
    }
    val colUnits = remember(table) { tableColUnits(table) }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clickable { onTableClick(table) },
    ) {
        val estWidths = colUnits.map { (it * 8 + 18).dp }
        val fits = estWidths.fold(0.dp) { acc, w -> acc + w } <= maxWidth
        val useScroll = !fits && table.header.size > TABLE_MAX_FIT_COLUMNS
        val fixedWidths = if (fits || useScroll) estWidths else null
        Column(
            modifier = Modifier
                .border(1.dp, dividerColor, RoundedCornerShape(6.dp))
                .then(
                    when {
                        useScroll -> Modifier.horizontalScroll(rememberScrollState())
                        fixedWidths == null -> Modifier.fillMaxWidth()
                        else -> Modifier
                    },
                ),
        ) {
            TableGrid(table = table, fixedWidths = fixedWidths, colUnits = colUnits, dividerColor = dividerColor)
        }
    }
}

/** 表格网格主体：表头（加粗带底色）+ 数据行 + 行列网格线，气泡内与全屏预览共用。 */
@Composable
private fun TableGrid(
    table: MarkdownTable,
    fixedWidths: List<Dp>?,
    colUnits: List<Int>,
    dividerColor: Color,
) {
    TableRow(
        cells = table.header,
        bold = true,
        fixedWidths = fixedWidths,
        colUnits = colUnits,
        dividerColor = dividerColor,
        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    )
    HorizontalDivider(color = dividerColor)
    table.rows.forEachIndexed { rowIndex, row ->
        TableRow(
            cells = row,
            bold = false,
            fixedWidths = fixedWidths,
            colUnits = colUnits,
            dividerColor = dividerColor.copy(alpha = 0.5f),
            background = null,
        )
        if (rowIndex < table.rows.lastIndex) {
            HorizontalDivider(color = dividerColor.copy(alpha = 0.5f))
        }
    }
}

/**
 * 表格全屏预览（in-content 整屏覆盖层；顶栏由调用方在打开时隐藏，整屏留给表格）。
 * - App 全局锁竖屏：不转 Activity/系统方向，把内容在竖屏视口内旋转 90° 铺满屏幕，
 *   用户物理转动手机即可横屏查看，与 ChartPreviewOverlay 同一模式。
 * - 表格区域 16:9 居中，固定估算列宽 + 双向滚动；返回键 / 关闭键均可关闭。
 * - 旋转后内容左边贴屏幕顶部（状态栏）、右边贴屏幕底部（导航栏），按此方向让出安全区。
 */
@Composable
private fun TablePreviewOverlay(table: MarkdownTable?, onDismiss: () -> Unit) {
    if (table == null) return
    val density = LocalDensity.current
    val startInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val endInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val colUnits = remember(table) { tableColUnits(table) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 容器宽高互换后绕中心旋转 90° 铺满竖屏；必须用 requiredSize，普通 size 会被父约束截断
        Box(
            modifier = Modifier
                .requiredSize(width = maxHeight, height = maxWidth)
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = 90f },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = startInset, end = endInset, top = 12.dp, bottom = 12.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 16:9 仅作居中参照区域；边框紧贴表格网格（wrap content），
                    // 否则分隔线按区域全宽绘制会伸出表体、表头底色与网格错位
                    Box(
                        modifier = Modifier.aspectRatio(16f / 9f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .border(1.dp, dividerColor, RoundedCornerShape(6.dp)),
                        ) {
                            TableGrid(
                                table = table,
                                fixedWidths = colUnits.map { (it * 8 + 18).dp },
                                colUnits = colUnits,
                                dividerColor = dividerColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 表格一行：[fixedWidths] 非空按固定宽度排（紧凑/横滚模式），为 null 按 [colUnits] 份额铺满。 */
@Composable
private fun TableRow(
    cells: List<String>,
    bold: Boolean,
    fixedWidths: List<Dp>?,
    colUnits: List<Int>,
    dividerColor: Color,
    background: Color?,
) {
    Row(
        modifier = Modifier
            .then(if (background != null) Modifier.background(background) else Modifier)
            .then(if (fixedWidths == null) Modifier.fillMaxWidth() else Modifier)
            .height(IntrinsicSize.Min),
    ) {
        cells.forEachIndexed { col, cell ->
            if (col > 0) VerticalDivider(color = dividerColor)
            val cellModifier = if (fixedWidths != null) {
                Modifier.width(fixedWidths[col])
            } else {
                Modifier.weight(colUnits[col].toFloat())
            }
            TableCell(text = cell, modifier = cellModifier, bold = bold)
        }
    }
}

@Composable
private fun TableCell(text: String, modifier: Modifier, bold: Boolean) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else null,
    )
}

/**
 * claude agent 气泡的步骤列表（spec §6：tool_use↔tool_result 配对 + file_change 徽标）。
 * 每步 = 状态字形（⏳/✓/✗）+ 工具标签（file_change 本地化为「改文件」）+ detail（命令/路径/摘要）。
 */
@Composable
private fun truncationReasonLabel(reason: String): String = when (reason) {
    "max_turns" -> stringResource(R.string.claude_truncated_reason_max_turns)
    "phase_timeout" -> stringResource(R.string.claude_truncated_reason_timeout)
    else -> ""
}

@Composable
private fun ClaudeAgentSteps(steps: List<ClaudeStepUi>) {
    val fileLabel = stringResource(R.string.claude_file_change_label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val glyph: String
                val glyphColor: Color
                when (step.status) {
                    ClaudeStepStatus.RUNNING -> {
                        glyph = "⏳"; glyphColor = MaterialTheme.colorScheme.tertiary
                    }
                    ClaudeStepStatus.SUCCESS -> {
                        glyph = "✓"; glyphColor = MaterialTheme.colorScheme.primary
                    }
                    ClaudeStepStatus.FAILED -> {
                        glyph = "✗"; glyphColor = MaterialTheme.colorScheme.error
                    }
                }
                Text(text = glyph, color = glyphColor, fontSize = 14.sp)
                val label = if (step.tool == ClaudeAgentRenderer.FILE_CHANGE_TOOL) fileLabel else step.tool
                val text = if (step.detail.isBlank()) label else "$label: ${step.detail}"
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // 待重构：消息项多类型分支，抽分发器
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageItem(
    message: ChatMessageUi,
    onImageClick: (ChatMessageUi) -> Unit = {},
    onClaudeDeliver: (String, String) -> Unit = { _, _ -> },
    onClaudeContinue: () -> Unit = {},
    canDeliverClaude: Boolean = false,
    suppressClaudeActions: Boolean = false,
    onTableClick: (MarkdownTable) -> Unit = {},
) {
    val isUser = message.type == ChatMessageType.USER_TEXT ||
        message.type == ChatMessageType.USER_IMAGE ||
        message.type == ChatMessageType.USER_IMAGE_TEXT
    val isImage = message.type == ChatMessageType.AGENT_IMAGE || message.type == ChatMessageType.USER_IMAGE
    val isImageText = message.type == ChatMessageType.USER_IMAGE_TEXT
    val isEditResult = message.type == ChatMessageType.AGENT_EDIT_RESULT
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copySuccess = stringResource(R.string.copy_success)

    val isAgentText = !isUser && !isImage && !isImageText && !isEditResult

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (isAgentText) {
            // 豆包范式（设计稿 chat/conversation，2026-08-18 修订去头像）：AI 文本消息 = 通栏纯文本流
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                clipboardManager.setText(AnnotatedString(message.content))
                                Toast.makeText(context, copySuccess, Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
            ) {
                // claude agent 消息：文本以 claudeAgent.text 为准（流式期 content=""，text 累积 delta）。
                val displayText = message.claudeAgent?.text ?: message.content
                if (message.isStreaming) {
                    if (message.isThinking) {
                        TypingIndicator()
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column(modifier = Modifier.weight(1f)) {
                                SegmentedAgentText(displayText, onTableClick)
                            }
                            if (message.showCursor) {
                                BlinkCursor()
                            }
                        }
                    }
                } else {
                    SegmentedAgentText(displayText, onTableClick)
                }
                AgentMessageExtras(
                    message = message,
                    onClaudeDeliver = onClaudeDeliver,
                    onClaudeContinue = onClaudeContinue,
                    canDeliverClaude = canDeliverClaude,
                    suppressClaudeActions = suppressClaudeActions
                )
                message.performance?.let { perf ->
                    MessagePerformanceRow(perf, isUser = false)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = if (isImage || isImageText) 240.dp else 360.dp)
                    .clip(
                        if (isUser) {
                            RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
                        } else {
                            RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
                        }
                    )
                    .background(
                        if (isImage) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        } else if (isUser) {
                            // 用户气泡：微信绿 #95EC69（token userBubbleBg，v3.0）；文字走 userBubbleOn 深字
                            ChatBubbleTokens.userBubbleBg
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                        }
                    )
                    .padding(
                        horizontal = if (isImage || isImageText) 6.dp else 18.dp,
                        vertical = if (isImage || isImageText) 6.dp else 14.dp
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                clipboardManager.setText(AnnotatedString(message.content))
                                Toast.makeText(context, copySuccess, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
            ) {
                when {
                    isImageText -> {
                        // 用户「图 + 文字意图」：图在上、文字在下
                        AsyncImage(
                            model = message.imageUri,
                            contentDescription = stringResource(R.string.photo),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageClick(message) }
                        )
                        Text(
                            text = message.content,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    isImage -> {
                        // 显示图片（可点击进入全屏预览）；agent 生成的结果图过期则显示占位
                        val imgSrc = message.imageUri ?: message.content
                        if (message.type == ChatMessageType.AGENT_IMAGE && !chatImageIsLive(imgSrc)) {
                            ExpiredImagePlaceholder()
                        } else {
                            AsyncImage(
                                model = imgSrc,
                                contentDescription = stringResource(R.string.photo),
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onImageClick(message) }
                            )
                        }
                    }
                    isEditResult -> {
                        // 对话式编辑结果：图片 + 说明文字；结果图过期则显示占位
                        val imageUri = message.imageUri.orEmpty()
                        if (imageUri.isNotBlank()) {
                            if (!chatImageIsLive(imageUri)) {
                                ExpiredImagePlaceholder(height = 200.dp)
                            } else {
                                AsyncImage(
                                    model = imageUri,
                                    contentDescription = stringResource(R.string.photo),
                                    contentScale = ContentScale.FillHeight,
                                    modifier = Modifier
                                        .height(200.dp)
                                        .widthIn(max = 260.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onImageClick(message) }
                                )
                            }
                        }
                        if (message.content.isNotBlank()) {
                            Text(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                    isUser -> {
                        // 用户气泡文字：userBubbleOn 深字（对齐设计稿 $143:12；#95EC69 绿底上深字对比度充足）
                        Text(
                            text = message.content,
                            color = ChatBubbleTokens.userBubbleOn,
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        )
                    }
                }
                message.performance?.let { perf ->
                    MessagePerformanceRow(perf, isUser = true)
                }
            }
        }
    }
}

/** AI 消息附加区：claude 步骤列表 / 截断继续 / 交付按钮（从气泡内拆出，随去气泡范式迁移）。 */
@Composable
private fun AgentMessageExtras(
    message: ChatMessageUi,
    onClaudeDeliver: (String, String) -> Unit,
    onClaudeContinue: () -> Unit,
    canDeliverClaude: Boolean,
    suppressClaudeActions: Boolean,
) {
    // claude agent 步骤列表（tool_use↔tool_result 配对 + file_change 徽标）
    message.claudeAgent?.let { cs ->
        if (cs.steps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ClaudeAgentSteps(cs.steps)
        }
    }
    // 任务卡时代（US-2 审批唯一入口）：会话内存在 TASK_CARD 时，气泡上的截断继续/交付按钮整体抑制
    if (!suppressClaudeActions) {
        // 截断标识 + 继续（spec §3.4）：truncatedReason 粘滞，置位后只设不清。
        message.claudeAgent?.truncatedReason?.let { reason ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ⓘ " + stringResource(R.string.claude_truncated) +
                        " " + truncationReasonLabel(reason),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClaudeContinue) {
                    Text(stringResource(R.string.claude_continue), fontSize = 12.sp)
                }
            }
        }
        // claude 交付按钮：file_change 后出现，pending 时可选 push/pr/auto（spec §8）
        // 仅白名单账号展示写链路入口；非白名单只读诊断，不能改代码。
        message.claudeDeliver?.let { cd ->
            if (cd.pending && canDeliverClaude) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.claude_deliver_choose),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeliverModeButton(
                        label = stringResource(R.string.claude_deliver_mode_push),
                        onClick = { onClaudeDeliver(message.id, "push") }
                    )
                    DeliverModeButton(
                        label = stringResource(R.string.claude_deliver_mode_pr),
                        onClick = { onClaudeDeliver(message.id, "pr") }
                    )
                    DeliverModeButton(
                        label = stringResource(R.string.claude_deliver_mode_auto),
                        onClick = { onClaudeDeliver(message.id, "auto") }
                    )
                }
            }
        }
    }
}

/** 性能指标行（用户=白字@气泡内；AI=onSurface@文本流下，设计稿 PerfRow 11sp 55%）。 */
@Composable
private fun MessagePerformanceRow(perf: LlmPerformance, isUser: Boolean) {
    val metricTint = if (isUser) {
        ChatBubbleTokens.userBubbleOn.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    FlowRow(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PerformanceMetric(
            icon = Icons.AutoMirrored.Rounded.ShortText,
            value = "${perf.promptLen}",
            tint = metricTint
        )
        PerformanceMetric(
            icon = Icons.Rounded.ChatBubble,
            value = "${perf.decodeLen}",
            tint = metricTint
        )
        if (perf.prefillTimeMs > 0) {
            PerformanceMetric(
                icon = Icons.Rounded.Bolt,
                value = "${perf.prefillTimeMs}ms",
                tint = metricTint
            )
        }
        PerformanceMetric(
            icon = Icons.Rounded.Timer,
            value = "${perf.decodeTimeMs}ms",
            tint = metricTint
        )
        PerformanceMetric(
            icon = Icons.Rounded.Speed,
            value = "${String.format(Locale.ROOT, "%.1f", perf.decodeSpeed)}",
            tint = metricTint
        )
        if (perf.usedSandbox) {
            // 沙盒标记：与性能指标同风格的 Material 图标（10.dp，无数值标签）。
            // 裸图标比同行指标（图标+文字）矮，需显式垂直居中避免顶对齐错位。
            Icon(
                imageVector = Icons.Rounded.Terminal,
                contentDescription = null,
                tint = metricTint,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(10.dp)
            )
        }
    }
}

/**
 * 交付模式按钮：push / pr / auto 三档中的单个选项。
 * 使用轻量边框按钮，避免实心按钮在消息气泡中过于突兀。
 */
@Composable
private fun DeliverModeButton(
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = label, fontSize = 12.sp)
    }
}

/**
 * 单个性能指标：图标 + 数值，紧凑展示，避免一行文字过长。
 */
@Composable
private fun PerformanceMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(10.dp)
        )
        Text(
            text = value,
            color = tint,
            fontSize = 9.sp
        )
    }
}

/**
 * 输入模式枚举
 */
private enum class ChatInputMode {
    TEXT,
    VOICE
}

@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // 待重构：输入区抽 state holder 降复杂度
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputArea(
    isProcessing: Boolean,
    onSendMessage: (String) -> Unit,
    mediaViewModel: MediaViewModel,
    viewModel: ChatViewModel,
    onNavigateToPhotoEditor: (String, Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var selectedIntent by remember { mutableStateOf<ImageIntent?>(null) }
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { UserPreferencesRepository(context) }
    // 独立 chat 页默认文本输入模式（不继承/回写公共 AI chat 的语音偏好）
    var inputMode by remember { mutableStateOf(ChatInputMode.TEXT) }
    // AI 工程师模式（claude-tunnel）：状态在 ViewModel（进入时新建独立会话）
    val claudeMode by viewModel.claudeMode.collectAsState()

    // 语音为默认关闭的实验能力（2026-08-19）：语音模式 ≠ DISABLED 时才显示语音入口
    val voiceCommandMode by settingsRepository.voiceCommandModeFlow.collectAsState(
        initial = VoiceCommandMode.DISABLED
    )
    val voiceEnabled = voiceCommandMode != VoiceCommandMode.DISABLED
    // 开关关闭时，已处于语音输入态则强制回落文字模式
    LaunchedEffect(voiceEnabled) {
        if (!voiceEnabled && inputMode == ChatInputMode.VOICE) {
            inputMode = ChatInputMode.TEXT
        }
    }

    // 语音输入：按需加载本地 Sherpa-ONNX ASR 模型，未配置时回退到系统 ASR
    val localAsrModel by settingsRepository.localAsrModelFlow.collectAsState(initial = "")
    // 语音模型就绪状态：未就绪时输入区不显示语音入口（无内容时回退为禁用态发送按钮）
    var voiceModelReady by remember { mutableStateOf(false) }
    LaunchedEffect(context, localAsrModel, voiceEnabled) {
        voiceModelReady = if (!voiceEnabled) {
            false
        } else {
            withContext(Dispatchers.IO) {
                if (localAsrModel.isNotBlank()) {
                    val modelDir = context.filesDir.resolve("llm_models/$localAsrModel")
                    modelDir.exists() && modelDir.isDirectory &&
                        modelDir.walkTopDown().any { file -> file.name.endsWith(".onnx") } &&
                        File(modelDir, "tokens.txt").exists()
                } else {
                    ModelPathConfig.isAsrModelReady(context)
                }
            }
        }
    }
    var asrEngine by remember(context) {
        mutableStateOf<AsrEngine>(SystemAsrEngine(context))
    }
    LaunchedEffect(context, localAsrModel, voiceEnabled) {
        // 语音入口隐藏时不初始化 ASR 引擎（语音为非刚需，默认收敛）
        if (!voiceEnabled) return@LaunchedEffect
        val engine = withContext(Dispatchers.IO) {
            if (localAsrModel.isNotBlank()) {
                val modelDir = context.filesDir.resolve("llm_models/$localAsrModel")
                val isModelReady = modelDir.exists() && modelDir.isDirectory &&
                    modelDir.walkTopDown().any { it.name.endsWith(".onnx") } &&
                    File(modelDir, "tokens.txt").exists()

                if (isModelReady) {
                    val sherpa = SherpaOnnxAsrEngine(context, modelDir.absolutePath)
                    if (sherpa.isAvailable()) {
                        Logger.i(TAG, "Chat ASR using local model: $localAsrModel")
                        sherpa
                    } else {
                        Logger.w(TAG, "Local ASR init failed, falling back to system ASR")
                        SystemAsrEngine(context)
                    }
                } else {
                    Logger.w(TAG, "Local ASR model not ready: $localAsrModel")
                    SystemAsrEngine(context)
                }
            } else {
                Logger.d(TAG, "No local ASR model configured, using default ASR (system or downloaded model)")
                createDefaultAsrEngine(context)
            }
        }
        val previousEngine = asrEngine
        asrEngine = engine
        if (previousEngine !== engine) {
            previousEngine.release()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            asrEngine.release()
        }
    }

    // 豆包风格：大圆角输入卡（r28 + 细描边 + 阴影）统一包裹输入区域
    val inputCardShape = RoundedCornerShape(ChatBubbleTokens.inputCornerRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = inputCardShape,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.12f)
                )
                .clip(inputCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    inputCardShape
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            when (inputMode) {
                ChatInputMode.TEXT -> ChatTextInputMode(
                    text = text,
                    onTextChange = { text = it },
                    isProcessing = isProcessing,
                    claudeMode = claudeMode,
                    onToggleClaude = { if (claudeMode) viewModel.exitClaudeMode() else viewModel.enterClaudeMode() },
                    onSend = {
                        if (!isProcessing) {
                            val img = pendingImage
                            when {
                                img != null && selectedIntent == ImageIntent.EDIT -> {
                                    onNavigateToPhotoEditor(img.toString(), false)
                                    pendingImage = null
                                    selectedIntent = null
                                }
                                img != null -> {
                                    viewModel.sendImageWithIntent(
                                        uri = img.toString(),
                                        intent = selectedIntent ?: ImageIntent.UNDERSTAND,
                                        text = text.trim().takeIf { it.isNotBlank() }
                                    )
                                    pendingImage = null
                                    selectedIntent = null
                                    text = ""
                                    keyboardController?.hide()
                                }
                                text.isNotBlank() -> {
                                    if (claudeMode) viewModel.sendClaudeMessage(text.trim())
                                    else onSendMessage(text.trim())
                                    text = ""
                                    keyboardController?.hide()
                                }
                            }
                        }
                    },
                    voiceEnabled = voiceEnabled,
                    voiceModelReady = voiceModelReady,
                    onSwitchToVoice = {
                        inputMode = ChatInputMode.VOICE
                        keyboardController?.hide()
                    },
                    onShowPhotoPicker = { showPhotoPicker = true },
                    pendingImage = pendingImage,
                    selectedIntent = selectedIntent,
                    onSelectIntent = { selectedIntent = it },
                    onRemovePendingImage = {
                        pendingImage = null
                        selectedIntent = null
                    }
                )

                ChatInputMode.VOICE -> ChatVoiceInputMode(
                    onSwitchToText = {
                        inputMode = ChatInputMode.TEXT
                        keyboardController?.show()
                    },
                    onVoiceResult = { result ->
                        if (result.isNotBlank() && !isProcessing) {
                            onSendMessage(result.trim())
                        }
                    },
                    asrEngine = asrEngine,
                    scope = scope
                )
            }
        }
    }

    // 内置相册选取底部弹窗（可搜索 + 复用 MediaGrid）
    if (showPhotoPicker) {
        ChatPhotoPickerSheet(
            sheetState = sheetState,
            mediaViewModel = mediaViewModel,
            onImageSelected = { uri ->
                viewModel.stageImage(uri)?.let { persisted ->
                    pendingImage = Uri.parse(persisted)
                    selectedIntent = null
                }
                showPhotoPicker = false
            },
            onDismiss = { showPhotoPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageIntentChip(
    @StringRes labelRes: Int,
    intent: ImageIntent,
    selected: ImageIntent?,
    onSelect: (ImageIntent) -> Unit
) {
    FilterChip(
        selected = selected == intent,
        onClick = { onSelect(intent) },
        label = { Text(stringResource(labelRes), fontSize = 12.sp) }
    )
}

@Suppress("LongMethod", "LongParameterList") // 待重构：文本输入模式，抽 state holder
@Composable
private fun ChatTextInputMode(
    text: String,
    onTextChange: (String) -> Unit,
    isProcessing: Boolean,
    onSend: () -> Unit,
    /** 语音入口是否可用（2026-08-19：语音模式 ≠ DISABLED 才为 true，默认关闭） */
    voiceEnabled: Boolean,
    /** 语音依赖的 ASR 模型是否已下载就绪；未就绪时不显示语音入口 */
    voiceModelReady: Boolean,
    onSwitchToVoice: () -> Unit,
    onShowPhotoPicker: () -> Unit,
    claudeMode: Boolean = false,
    onToggleClaude: () -> Unit = {},
    pendingImage: Uri? = null,
    selectedIntent: ImageIntent? = null,
    onSelectIntent: (ImageIntent) -> Unit = {},
    onRemovePendingImage: () -> Unit = {}
) {
    val hasContent = text.isNotBlank() || pendingImage != null

    // 输入框内容区域（外层已由 ChatInputArea 统一包裹白色卡片）
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (pendingImage != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(pendingImage).size(256).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                )
                IconButton(
                    onClick = onRemovePendingImage,
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_remove_pending_image),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImageIntentChip(R.string.chat_intent_understand, ImageIntent.UNDERSTAND, selectedIntent, onSelectIntent)
                ImageIntentChip(R.string.chat_intent_find_similar, ImageIntent.FIND_SIMILAR, selectedIntent, onSelectIntent)
                ImageIntentChip(R.string.chat_intent_edit, ImageIntent.EDIT, selectedIntent, onSelectIntent)
            }
        }
        // 第一行：输入框（无独立边框，融入卡片）
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            val inputHint = stringResource(R.string.chat_input_hint)
            if (text.isEmpty()) {
                Text(
                    text = inputHint,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
            // 使用 BasicTextField 实现无边框输入
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = inputHint },
                maxLines = 5,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (hasContent && !isProcessing) onSend() })
            )
        }

        // 第二行：胶囊形功能按钮 + 圆形图标按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 左侧：胶囊形按钮组（占满右侧按钮之外的剩余宽度；胶囊过多或模型名过长时可横向滚动，绝不重叠/溢出卡片）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 模型切换胶囊已于 2026-08-22 移除（用户定稿：输入区只保留功能胶囊 + 语音/发送）

                // 图片选择胶囊按钮（claude 模式禁用：媒体不上传远程，ADR-008/§11）
                if (!claudeMode) {
                    CapsuleButton(
                        icon = Icons.Rounded.PhotoLibrary,
                        label = stringResource(R.string.gallery),
                        onClick = onShowPhotoPicker,
                        enabled = !isProcessing
                    )
                }

                // AI 工程师 toggle（二态）：激活后消息走 claude-tunnel 流式 agent chat
                CapsuleButton(
                    icon = Icons.Rounded.SmartToy,
                    label = stringResource(R.string.claude_icon_desc),
                    onClick = onToggleClaude,
                    enabled = !isProcessing,
                    isActive = claudeMode
                )
            }

            // 右侧：圆形图标按钮（语音 + 发送）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 语音切换按钮：surfaceContainerHigh 实底圆钮（豆包式）
                // 2026-08-19：语音默认关闭，仅语音模式 ≠ DISABLED 且 ASR 模型已下载就绪时显示
                val showVoiceEntry = voiceEnabled && voiceModelReady
                if (showVoiceEntry) {
                    CircularIconButton(
                        icon = Icons.Rounded.KeyboardVoice,
                        contentDescription = stringResource(R.string.cd_switch_to_voice),
                        onClick = onSwitchToVoice,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        container = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }

                // 发送按钮（有内容时显示；品牌渐变实底圆钮）
                if (hasContent && !isProcessing) {
                    CircularIconButton(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        onClick = onSend,
                        brandGradient = true
                    )
                } else if (voiceEnabled && !voiceModelReady) {
                    // 语音模型未下载且无内容：默认显示禁用态发送按钮（替代不可用的语音入口）
                    CircularIconButton(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        onClick = {},
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        container = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
            }
        }
    }
}

/**
 * 胶囊形按钮 — DeepSeek 风格（圆角长条，带图标+文字）
 */
@Composable
private fun CapsuleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isActive: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * 圆形图标按钮：默认透明底；可指定 [container]（如 surfaceContainerHigh 语音钮）
 * 或 [brandGradient]（豆包式渐变发送钮，白字+品牌色光晕）。
 */
@Composable
private fun CircularIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    container: Color? = null,
    brandGradient: Boolean = false,
) {
    val brandBrush = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .shadow(
                elevation = if (brandGradient) 4.dp else 0.dp,
                shape = CircleShape,
                ambientColor = ChatBubbleTokens.brandGradientStart.copy(alpha = 0.4f),
                spotColor = ChatBubbleTokens.brandGradientStart.copy(alpha = 0.4f)
            )
            .clip(CircleShape)
            .then(
                when {
                    brandGradient -> Modifier.background(brandBrush)
                    container != null -> Modifier.background(container)
                    else -> Modifier
                }
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (brandGradient) Color.White else tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ChatVoiceInputMode(
    onSwitchToText: () -> Unit,
    onVoiceResult: (String) -> Unit,
    asrEngine: AsrEngine,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var isCancelRecord by remember { mutableStateOf(false) }
    var discardResult by remember { mutableStateOf(false) }

    val pushToTalkEngine = remember(asrEngine) {
        PushToTalkEngine(asrEngine, scope, context)
    }

    val voiceUnavailableText = stringResource(R.string.voice_unavailable)
    val permissionDeniedText = stringResource(R.string.record_audio_permission_denied)

    val startRecording = {
        if (!asrEngine.isAvailable()) {
            Toast.makeText(context, voiceUnavailableText, Toast.LENGTH_SHORT).show()
        } else {
            discardResult = false
            isListening = true
            isCancelRecord = false
            Logger.d(TAG, "Chat push-to-talk started")
            pushToTalkEngine.start { result ->
                isListening = false
                isCancelRecord = false
                Logger.d(TAG, "Chat ASR result: '$result', discard=$discardResult")
                if (result.isNotBlank() && !discardResult) {
                    onVoiceResult(result)
                }
                discardResult = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, permissionDeniedText, Toast.LENGTH_SHORT).show()
        }
    }

    // 页面退出或切换到键盘时强制停止录音
    DisposableEffect(Unit) {
        onDispose {
            pushToTalkEngine.stop()
        }
    }

    // 语音输入内容区域（外层已由 ChatInputArea 统一包裹白色卡片）
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 单行布局：左侧键盘切换 + 中间按住说话 + 右侧可扩展
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 左侧：键盘切换按钮
            CircularIconButton(
                icon = Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.switch_to_keyboard),
                onClick = onSwitchToText
            )

            // 中间：按住说话按钮（占据剩余空间）
            val buttonBackground = when {
                isListening && !isCancelRecord -> MaterialTheme.colorScheme.primary
                isListening && isCancelRecord -> Color(0xFFE53935)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
            val buttonTextColor = if (isListening) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(buttonBackground)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    when {
                                        // 手指按下（仅初始 DOWN，避免 MOVE 事件重复触发）：检查麦克风权限并开始录音
                                        change.pressed && !change.previousPressed && !change.isConsumed && !isListening -> {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED

                                            if (hasPermission) {
                                                startRecording()
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                        // 手指抬起：停止录音
                                        !change.pressed -> {
                                            if (isListening) {
                                                if (isCancelRecord) {
                                                    discardResult = true
                                                }
                                                pushToTalkEngine.stop()
                                                isListening = false
                                                isCancelRecord = false
                                            }
                                        }
                                    }

                                    // 手指移动：移出按钮区域视为取消
                                    if (isListening && change.pressed) {
                                        val bounds = this@pointerInput.size
                                        val x = change.position.x
                                        val y = change.position.y
                                        val newCancel = x < 0 || x > bounds.width || y < 0 || y > bounds.height
                                        if (newCancel != isCancelRecord) {
                                            isCancelRecord = newCancel
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardVoice,
                        contentDescription = stringResource(R.string.cd_switch_to_voice),
                        tint = buttonTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = when {
                            isListening && !isCancelRecord -> stringResource(R.string.release_to_stop)
                            isListening && isCancelRecord -> stringResource(R.string.release_to_cancel)
                            else -> stringResource(R.string.hold_to_speak)
                        },
                        color = buttonTextColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 右侧：占位保持对称
            Box(modifier = Modifier.size(36.dp))
        }
    }
}

/** 全屏预览态：携带保存所需的 messageId / 类型 / 已保存标记。 */
/**
 * 聊天图片横滑预览状态：打开瞬间快照的翻页集合 + 初始页下标。
 */
data class ChatImagePreviewState(
    val pages: List<ImagePreviewPage>,
    val initialIndex: Int
)

/**
 * 图片预览横滑翻页集合中的一个页面。持有原始 uri 字符串（Uri 解析推迟到渲染时进行，
 * 使本模型为纯 Kotlin、可单测；touchEditImage 也接收原始字符串）。
 */
data class ImagePreviewPage(
    val messageId: String,
    val rawUri: String,
    val isEditableResult: Boolean, // AGENT_IMAGE / AGENT_EDIT_RESULT
    val isSaved: Boolean
)

/**
 * 从会话消息快照构建图片预览翻页集合：保留所有 [ChatMessageUi.imageUri] 非空的消息，
 * 保持原顺序。纯函数，便于单测（Uri 解析在渲染层 [resolvePreviewUri] 完成）。
 */
fun buildImagePreviewPages(messages: List<ChatMessageUi>): List<ImagePreviewPage> =
    messages.mapNotNull { msg ->
        val raw = msg.imageUri ?: return@mapNotNull null
        ImagePreviewPage(
            messageId = msg.id,
            rawUri = raw,
            isEditableResult = msg.type == ChatMessageType.AGENT_IMAGE ||
                msg.type == ChatMessageType.AGENT_EDIT_RESULT,
            isSaved = msg.imageSaved
        )
    }

/** 把 raw uri 字符串解析为最终 Uri（含 scheme 直接用，否则按 file:// 兜底）。 */
fun resolvePreviewUri(rawUri: String): Uri {
    val parsed = Uri.parse(rawUri)
    return if (parsed?.scheme != null) parsed else File(rawUri).toUri()
}

/** 返回 [messageId] 在 pages 中的下标；找不到返回 0（兜底定位到首页）。 */
fun indexOfPage(pages: List<ImagePreviewPage>, messageId: String): Int {
    val i = pages.indexOfFirst { it.messageId == messageId }
    return if (i >= 0) i else 0
}

// ChatMessageUi（→ typealias commonMain ChatMessage）/ ClaudeAgentState / OptimizeCandidateGroup /
// LlmPerformance / MediaResultsUi / ClaudeDeliverUi / ClaudeStepUi 等已下沉 commonMain
// （com.mamba.picme.domain.chat）；本文件经 ChatModelCommonMainShim typealias + 顶部 import 引用。

/**
 * 模型选项（chat 页仅远程：端侧文本 LLM 已移除，仅保留 Remote）
 */
sealed class ChatModelOption(@StringRes val labelRes: Int, val indicatorColor: Color) {
    data object Remote : ChatModelOption(R.string.chat_model_remote, Color(0xFF2196F3))
}

/**
 * 图表全屏预览（in-content 整屏覆盖层；顶栏由调用方在打开时隐藏，整屏留给图）。
 * - 图按 contain-fit 等比缩放，整图可见、清晰。
 * - 双指缩放（1x~5x）+ 单指拖动平移；缩放回到 ~1x 自动回正。
 * - 返回键 / 关闭键 均可关闭；关闭键位于视口右上，样式与图片预览页一致（含安全区内边距）。
 * - App 全局锁竖屏：宽图不再修改 Activity/系统方向，而是把内容在竖屏视口内
 *   旋转 90° 铺满屏幕，用户物理转动手机即可横屏查看。
 */
@Composable
private fun ChartPreviewOverlay(
    svg: String?,
    onDismiss: () -> Unit
) {
    if (svg == null) return
    val landscape = chartIsLandscape(svg)
    // 宽图旋转容器内无法直接用 insets padding（轴向随旋转错位），提前换算导航栏安全区
    val navBarBottomDp = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    var scale by remember(svg) { mutableStateOf(1f) }
    var offset by remember(svg) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 宽图：容器宽高互换后绕中心旋转 90° 铺满竖屏；手势与关闭键随旋转坐标系保持一致。
        // 必须用 requiredSize：普通 size 会被父约束截断成方形，导致旋转后无法铺满
        val chartModifier = if (landscape) {
            Modifier
                .requiredSize(width = maxHeight, height = maxWidth)
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = 90f }
        } else {
            Modifier.fillMaxSize()
        }
        // 宽图关闭键需落在图表 contain-fit 后的黑边区域（中心图区之外）：
        // 依图表宽高比算出两侧/上下黑边宽度，按钮优先居中放进侧边黑边
        val closeTopPad: Dp
        val closeEndPad: Dp
        if (landscape) {
            val containerW = maxHeight
            val containerH = maxWidth
            val aspect = chartAspect(svg)
            val fittedW: Dp
            val fittedH: Dp
            if (aspect >= containerW / containerH) {
                fittedW = containerW
                fittedH = containerW / aspect
            } else {
                fittedH = containerH
                fittedW = containerH * aspect
            }
            val sideBar = (containerW - fittedW) / 2
            val topBar = (containerH - fittedH) / 2
            val minBarForButton = 40.dp + 24.dp
            when {
                sideBar >= minBarForButton -> {
                    closeTopPad = 16.dp
                    closeEndPad = maxOf(navBarBottomDp + 16.dp, (sideBar - 40.dp) / 2)
                }
                topBar >= minBarForButton -> {
                    closeTopPad = maxOf(16.dp, (topBar - 40.dp) / 2)
                    closeEndPad = navBarBottomDp + 16.dp
                }
                else -> {
                    closeTopPad = 16.dp
                    closeEndPad = navBarBottomDp + 16.dp
                }
            }
        } else {
            closeTopPad = 0.dp
            closeEndPad = 0.dp
        }
        Box(modifier = chartModifier) {
            ChartSvgImage(
                svg = svg,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(svg) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            // 缩放回到 ~1x 时复位偏移，避免图被拖飞
                            offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 宽图：容器 end 边即屏幕底边，手动补导航栏安全区并避开图区；竖图与图片预览页一致
                    .then(
                        if (landscape) {
                            Modifier.padding(top = closeTopPad, end = closeEndPad)
                        } else {
                            Modifier.statusBarsPadding().padding(16.dp)
                        }
                    )
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/** 解析 SVG 的 width/height，判断是否为宽图（width > height）。 */
private fun chartIsLandscape(svg: String?): Boolean {
    if (svg == null) return false
    val w = Regex("""width="(\d+)"""").find(svg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val h = Regex("""height="(\d+)"""").find(svg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return w > 0 && h > 0 && w > h
}

/**
 * 图片全屏预览浮层：对编辑/优化结果额外提供「保存到相册」按钮。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatImagePreviewOverlay(
    state: ChatImagePreviewState?,
    onSave: (messageId: String, onDone: (Boolean) -> Unit) -> Unit,
    onPageChanged: (ImagePreviewPage) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val expiredToast = stringResource(R.string.chat_edit_save_expired_failed)
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        if (state == null) return@AnimatedVisibility
        val pages = state.pages
        val pagerState = rememberPagerState(
            initialPage = state.initialIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)),
            pageCount = { pages.size }
        )

        // 切页时：若是编辑/生成图则续期（LRU 回收）
        LaunchedEffect(pagerState.currentPage, pages.size) {
            pages.getOrNull(pagerState.currentPage)?.let(onPageChanged)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val page = pages[pageIndex]
                var scale by remember(page.messageId) { mutableStateOf(1f) }
                var offset by remember(page.messageId) { mutableStateOf(Offset.Zero) }
                AsyncImage(
                    model = resolvePreviewUri(page.rawUri),
                    contentDescription = stringResource(R.string.cd_image_preview),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .pointerInput(page.messageId) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )
            }

            // 关闭按钮（右上）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 页码指示器（关闭键左侧）
            Text(
                text = "${pagerState.currentPage + 1} / ${pages.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 72.dp, top = 22.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // 保存按钮（仅当前页为编辑/优化结果，底部居中）
            val currentPage = pages.getOrNull(pagerState.currentPage)
            if (currentPage?.isEditableResult == true) {
                val isSaved = currentPage.isSaved
                Button(
                    onClick = {
                        if (!isSaved) {
                            onSave(currentPage.messageId) { ok ->
                                if (!ok) Toast.makeText(context, expiredToast, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSaved,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(24.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (isSaved) R.string.chat_edit_saved_to_gallery else R.string.chat_edit_save_to_gallery
                        )
                    )
                }
            }
        }
    }
}
