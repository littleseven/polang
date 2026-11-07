package com.mamba.picme.features.cloudagent

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.domain.chat.AgentStepStatus
import com.mamba.picme.domain.chat.AgentStepUi
import com.mamba.picme.domain.chat.HtmlCardMeta
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.features.chat.HtmlCard
import com.mamba.picme.features.chat.HtmlCardSanitizer
import com.mamba.picme.features.chat.HtmlFullpageContent
import com.mamba.picme.features.chat.HtmlFullpageViewer
import com.mamba.picme.features.chat.HtmlLinkPreviewOverlay
import com.mamba.picme.features.chat.components.MediaResultsCarousel
import com.mamba.picme.features.chat.openui.OpenUiCardHost
import com.mamba.picme.features.common.chat.AgentMarkdown
import com.mamba.picme.features.debug.openui.QrCodeScanner
import kotlinx.coroutines.launch

/**
 * 云端 Agent 最小聊天页（spec §5，调试入口实验页——豁免 PARITY 固化，转正时再入 08-UI-SPECS）。
 * 顶栏通道标记（网关/BYOK）+ 消息气泡列表 + 输入框 + 发送/停止。
 * 顶栏右侧两个 OpenUI 调试入口（2026-11-07 自 Debug 页迁入）：扫码直达加载、合一调试页
 * （[OpenUiDebugOverlay]：手输/历史/全组件画廊）；加载产物以 OPENUI_CARD 内存消息上屏。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CloudAgentChatScreen(
    viewModel: CloudAgentChatViewModel,
    onNavigateBack: () -> Unit,
    onGoRemoteModelSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.prepare() }

    // OpenUI 调试入口态：扫码直达（成功注入消息流，失败 Toast）+ 合一调试页 Overlay
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openUiLoader = rememberOpenUiPreviewLoader()
    var showScanner by remember { mutableStateOf(false) }
    var showOpenUiOverlay by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val parseErrorText = stringResource(R.string.debug_qr_error_parse)
    val noTokenText = stringResource(R.string.debug_qr_error_no_token)

    fun handleQuickScan(raw: String) {
        scope.launch {
            val toastText = when (val result = openUiLoader.load(raw)) {
                is OpenUiLoadResult.Ok -> {
                    viewModel.injectOpenUiPreview(openUiPreviewLabel(result.entry), result.entry.program)
                    null
                }
                OpenUiLoadResult.ParseError -> parseErrorText
                OpenUiLoadResult.NoToken -> noTokenText
                is OpenUiLoadResult.Failure -> result.message
            }
            toastText?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏（statusBarsPadding 适配状态栏/刘海）：返回 + 标题 + 通道胶囊 + OpenUI 调试入口
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.cloud_agent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            (state as? CloudAgentChatViewModel.UiState.Chat)?.let { chat ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = stringResource(
                            if (chat.channel == "byok") R.string.cloud_agent_channel_byok
                            else R.string.cloud_agent_channel_gateway,
                        ),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    if (cameraPermission.status.isGranted) {
                        showScanner = true
                    } else {
                        cameraPermission.launchPermissionRequest()
                    }
                },
            ) {
                Icon(
                    Icons.Rounded.QrCodeScanner,
                    contentDescription = stringResource(R.string.debug_qr_scan),
                )
            }
            // contentDescription 用协议标识符（OpenUI 豁免口径同调试页徽标）
            IconButton(onClick = { showOpenUiOverlay = true }) {
                Icon(Icons.Rounded.Widgets, contentDescription = "OpenUI")
            }
        }

        when (val s = state) {
            CloudAgentChatViewModel.UiState.Preparing ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            CloudAgentChatViewModel.UiState.NeedByokConsent ->
                AlertDialog(
                    onDismissRequest = onNavigateBack,
                    title = { Text(stringResource(R.string.cloud_agent_byok_dialog_title)) },
                    text = { Text(stringResource(R.string.cloud_agent_byok_dialog_message)) },
                    confirmButton = {
                        TextButton(onClick = onGoRemoteModelSettings) {
                            Text(stringResource(R.string.cloud_agent_byok_dialog_go_settings))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.useGatewayInstead() }) {
                            Text(stringResource(R.string.cloud_agent_byok_dialog_use_gateway))
                        }
                    },
                )
            is CloudAgentChatViewModel.UiState.Failed ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (s.reason == "not_logged_in") stringResource(R.string.cloud_agent_not_logged_in)
                        else s.reason,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            is CloudAgentChatViewModel.UiState.Chat -> ChatContent(s, viewModel)
        }
    }

    // 扫码直达加载（成功注入 OPENUI_CARD 消息流；失败 Toast 提示）
    if (showScanner) {
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            QrCodeScanner(
                onResult = { text ->
                    showScanner = false
                    handleQuickScan(text)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // OpenUI 合一调试页（手输加载 / 预览历史 / 全组件画廊）
    if (showOpenUiOverlay) {
        OpenUiDebugOverlay(
            onLoaded = { label, program -> viewModel.injectOpenUiPreview(label, program) },
            onDismiss = { showOpenUiOverlay = false },
        )
    }
}

@Composable
private fun ChatContent(chat: CloudAgentChatViewModel.UiState.Chat, viewModel: CloudAgentChatViewModel) {
    val listState = rememberLazyListState()
    val steps by viewModel.steps.collectAsState()
    val context = LocalContext.current
    // 扫码预览卡取数接缝：真实 LocalHandleStore / PhotoResolver——本机产生的
    // local://scan/... handle 可解析出真实照片（扫码相册预览核心诉求）；跨设备会话的
    // handle 本机不存在 → 解析 null 自然走既有降级（[PRIVACY] uri 不出端，同主聊天页口径）
    val openUiHandleLookup = remember {
        (context.applicationContext as PoLangApplication).container.localHandleLookup
    }
    val openUiPhotoResolver = remember {
        (context.applicationContext as PoLangApplication).container.openUiPhotoResolver
    }
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.size - 1)
    }
    // H5 卡全屏查看器 / 外链落地页的页内态（调试页不落 Room，display 终判走 HtmlCard 进程级缓存）
    var fullpageHtmlCard by remember { mutableStateOf<HtmlFullpageContent?>(null) }
    var previewLinkUrl by remember { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chat.messages, key = { message -> message.id }) { message ->
                when (message.role) {
                    CloudAgentChatViewModel.Role.USER -> Bubble(text = message.text, isUser = true)
                    CloudAgentChatViewModel.Role.ASSISTANT -> Bubble(text = message.text, isUser = false)
                    CloudAgentChatViewModel.Role.TOOL -> Text(
                        text = stringResource(R.string.cloud_agent_tool_call, message.text),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CloudAgentChatViewModel.Role.ERROR -> Text(
                        text = message.text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    CloudAgentChatViewModel.Role.MEDIA -> message.media?.let { media ->
                        // 调试页图集卡：复用主聊天页同款横滑卡组件；点击动作从简（实验页豁免 PARITY）
                        MediaResultsCarousel(
                            mediaResults = media,
                            onCardClick = { },
                        )
                    }
                    CloudAgentChatViewModel.Role.HTML_CARD -> message.htmlCard?.let { card ->
                        // 渲染对象是不可信云端产物：渲染前过清洗器；Rejected 降级纯文本，不白屏不崩溃
                        when (val sanitized = remember(message.id) { HtmlCardSanitizer.sanitize(card.html) }) {
                            is HtmlCardSanitizer.Result.Ok -> HtmlCard(
                                html = sanitized.html,
                                meta = HtmlCardMeta(summary = card.summary.ifBlank { null }),
                                cacheKey = "cloud_html_${message.id}",
                                isListScrolling = listState.isScrollInProgress,
                                onOpenLink = { url -> previewLinkUrl = url },
                                onOpenFullpage = {
                                    fullpageHtmlCard = HtmlFullpageContent(
                                        html = sanitized.html,
                                        title = card.summary.ifBlank { null },
                                    )
                                },
                            )
                            is HtmlCardSanitizer.Result.Rejected -> Text(
                                text = stringResource(R.string.cloud_agent_html_card_blocked) +
                                    if (card.summary.isNotBlank()) "\n${card.summary}" else "",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    CloudAgentChatViewModel.Role.OPENUI_CARD -> message.openUiCard?.let { card ->
                        // OpenUI 调试预览卡：扫码/画廊注入的最终程序，走与 Chat 同管线真实渲染器；
                        // 取数接缝用真实实现——本机 handle 直出照片，跨设备 handle 解析 null 走降级
                        Column {
                            Text(
                                text = card.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OpenUiCardHost(
                                part = MessagePart.OpenUiCard(
                                    partId = "cloud-preview-${message.id}",
                                    program = card.program,
                                    fallbackSummary = "OpenUI preview degraded",
                                ),
                                handleLookup = openUiHandleLookup,
                                photoResolver = openUiPhotoResolver,
                                onViewStateChange = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            if (chat.inFlight) {
                item {
                    // 有步骤渲染时间线（同主聊天页视觉），尚未有步骤时回退 thinking 占位
                    if (steps.isEmpty()) {
                        Text(
                            text = stringResource(R.string.cloud_agent_thinking),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        AgentStepsTimeline(steps)
                    }
                }
            }
        }
        var input by remember { mutableStateOf("") }
        // 输入行：navigationBarsPadding + imePadding 适配手势导航条与键盘推起
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { value -> input = value },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.cloud_agent_input_hint)) },
                maxLines = 3,
            )
            if (chat.inFlight) {
                IconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.cloud_agent_stop))
                }
            } else {
                IconButton(
                    onClick = {
                        viewModel.send(input.trim())
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.cloud_agent_send))
                }
            }
        }
    }
        // H5 卡全屏查看器（z 序在落地页之下——查看器内 <a> 点击叠落地页）
        HtmlFullpageViewer(
            content = fullpageHtmlCard,
            onOpenLink = { url -> previewLinkUrl = url },
            onDismiss = { fullpageHtmlCard = null },
        )
        // H5 卡外链落地页（全屏内置浏览器）
        HtmlLinkPreviewOverlay(
            url = previewLinkUrl,
            onDismiss = { previewLinkUrl = null },
        )
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isUser) {
                    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    AgentMarkdown(
                        content = text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

/**
 * 云端 agent 回合步骤时间线（瞬态，仅 inFlight 区渲染；调用方保证 steps 非空）。
 * 与主聊天页 ChatScreen 同款视觉（调试页自持一份，不跨 features 包引用私有 composable）：
 * 每步 = 状态图标（RUNNING 12dp 旋转指示 / ✓ / ⚠）+ 13sp 步骤标签；FAILED 标签追加失败提示文案。
 */
@Composable
private fun AgentStepsTimeline(steps: List<AgentStepUi>) {
    val failedHint = stringResource(R.string.cloud_agent_step_failed_hint)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (step.status) {
                    AgentStepStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    AgentStepStatus.SUCCESS -> Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                    AgentStepStatus.FAILED -> Text(
                        text = "⚠",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
                val label = if (step.status == AgentStepStatus.FAILED) {
                    "${step.label} — $failedHint"
                } else {
                    step.label
                }
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
