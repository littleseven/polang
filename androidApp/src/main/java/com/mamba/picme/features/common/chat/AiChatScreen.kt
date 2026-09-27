package com.mamba.picme.features.common.chat

import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.mamba.picme.core.designsystem.PoLangForcedDarkTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.agent.core.platform.voice.AudioRecorder
import com.mamba.picme.agent.core.platform.voice.InputAudioDevice
import com.mamba.picme.features.camera.voice.VoiceCommandCoordinator
import kotlinx.coroutines.launch
import android.app.Activity
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.ui.text.TextStyle

private const val TAG = "Voice"

private tailrec fun Context.findActivityForWindow(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityForWindow()
    else -> null
}

/**
 * AI Chat Screen - 统一聊天界面组件
 * 
 * 功能特性：
 * 1. 非模态浮层设计，不遮挡页面预览和底层交互
 * 2. 折叠/展开功能，节省屏幕空间
 * 3. 文字 + 语音输入切换
 * 4. 支持多种消息类型（UserText、AgentText、PlanPreview、PlanProgress、PlanResult）
 * 5. 优雅的动画效果
 * 6. 拖拽把手设计
 * 
 * 使用示例：
 * ```kotlin
 * AiChatScreen(
 *     visible = chatState.isVisible,
 *     onVisibleChange = { chatState.isVisible = it },
 *     messages = chatState.messages,
 *     isProcessing = chatState.isProcessing,
 *     onSendMessage = { input -> /* handle send */ },
 *     onCommand = { command -> /* execute command */ }
 * )
 * ```
 */
@Composable
fun AiChatScreen(
    visible: Boolean,
    messages: List<AgentMessage>,
    isProcessing: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onSendMessage: (String) -> Unit,
    onCommand: (AiAgentCommand) -> Unit,
    modifier: Modifier = Modifier,
    consumedBottomInset: Dp = 0.dp,
    autoExecutePlans: Boolean = true,
    onPlanConfirm: () -> Unit = {},
    onPlanCancel: () -> Unit = {},
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    val context = LocalContext.current

    DisposableEffect(visible, context) {
        if (!visible) {
            return@DisposableEffect onDispose {}
        }
        val activity = context.findActivityForWindow() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        onDispose {
            window.setSoftInputMode(previousSoftInputMode)
        }
    }

    BackHandler(enabled = visible) {
        onVisibleChange(false)
    }

    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomDp = with(density) { imeBottomPx.toDp() }
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val hiddenPanelBottomPadding = (navBottomDp - consumedBottomInset).coerceAtLeast(0.dp)
    val panelBottomPadding = if (imeBottomPx > 0) imeBottomDp else hiddenPanelBottomPadding

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = panelBottomPadding)
            ) {
                AiChatScreenContent(
                    messages = messages,
                    isProcessing = isProcessing,
                    onSendMessage = onSendMessage,
                    onCommand = onCommand,
                    autoExecutePlans = autoExecutePlans,
                    onDismiss = { onVisibleChange(false) },
                    onPlanConfirm = onPlanConfirm,
                    onPlanCancel = onPlanCancel,
                    voiceCoordinator = voiceCoordinator
                )
            }
        }
    }
}

@Composable
private fun AiChatScreenContent(
    messages: List<AgentMessage>,
    isProcessing: Boolean,
    onSendMessage: (String) -> Unit,
    onCommand: (AiAgentCommand) -> Unit,
    autoExecutePlans: Boolean,
    onDismiss: () -> Unit,
    onPlanConfirm: () -> Unit,
    onPlanCancel: () -> Unit,
    modifier: Modifier = Modifier,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    var isExpanded by remember { mutableStateOf(true) }

    // ADR-016 M4（spec §8 收口）：面板为相机/相册上的恒黑浮层，浅色 scheme 下派生色
    // 在黑底上不可见——强制深色 scheme 对齐视觉场景（对齐相机页先例）；气泡原硬编码
    // Color.White/DarkGray 同步收口为 colorScheme 语义色。面板底色保持纯黑不动
    //（spec 只点名气泡；改 background 会引起相机页浮层视觉回归）
    PoLangForcedDarkTheme {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header with drag handle
        AiChatHeader(
            isExpanded = isExpanded,
            onToggleExpand = { isExpanded = !isExpanded },
            onClose = onDismiss
        )

        // Expandable content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Messages list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages) { message ->
                        ChatBubble(
                            message = message,
                            onConfirm = onPlanConfirm,
                            onCancel = onPlanCancel
                        )
                    }
                }

                // Input bar
                ChatInputBar(
                    isProcessing = isProcessing,
                    onSend = onSendMessage,
                    onCommand = onCommand,
                    voiceCoordinator = voiceCoordinator
                )
            }
        }
    }
    }
}

@Composable
private fun AiChatHeader(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 180f,
        label = "expandRotation"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.ai_agent),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Expand/Collapse button
            IconButton(
                onClick = onToggleExpand,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                    contentDescription = if (isExpanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    // Drag handle
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onToggleExpand() }
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        )
    }
}

@Composable
private fun ChatBubble(
    message: AgentMessage,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    when (message) {
        is AgentMessage.UserText -> UserTextBubble(message, modifier)
        is AgentMessage.AgentText -> AgentTextBubble(message, modifier)
        is AgentMessage.CommandExecution -> CommandExecutionBubble(message, modifier)
        is AgentMessage.PlanPreview -> PlanPreviewBubble(message, modifier, onConfirm, onCancel)
        is AgentMessage.PlanProgress -> PlanProgressBubble(message, modifier)
        is AgentMessage.PlanResult -> PlanResultBubble(message, modifier)
    }
}

@Composable
private fun UserTextBubble(
    message: AgentMessage.UserText,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copySuccessText = stringResource(R.string.copy_success)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = message.content,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(context, copySuccessText, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
        )
    }
}

@Composable
private fun AgentTextBubble(
    message: AgentMessage.AgentText,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copySuccessText = stringResource(R.string.copy_success)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        AgentMarkdown(
            content = message.content,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(context, copySuccessText, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
        )
    }
}

@Composable
private fun PlanPreviewBubble(
    message: AgentMessage.PlanPreview,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    // Check if this is a plan with actions
    // ⚠️ 耦合隐患：content 里的「执行计划」文案由 chat_cmd_execute_plan（命令名映射）产出，
    // 这里必须读同一资源做 contains，保证同 locale 下生产/判断一致（见 P1-C i18n 报告）。
    val planKeyword = stringResource(R.string.chat_cmd_execute_plan)
    val hasActions = message.content.contains("📋") || message.content.contains(planKeyword)
    
    if (hasActions && (onConfirm != null || onCancel != null)) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = onConfirm != null
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.cd_confirm),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        enabled = onCancel != null
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = message.content,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun PlanProgressBubble(
    message: AgentMessage.PlanProgress,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message.content,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PlanResultBubble(
    message: AgentMessage.PlanResult,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message.content,
            color = MaterialTheme.colorScheme.tertiary,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun CommandExecutionBubble(
    message: AgentMessage.CommandExecution,
    modifier: Modifier = Modifier
) {
    val statusColor = when (message.status) {
        AgentMessage.CommandExecution.Status.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentMessage.CommandExecution.Status.RUNNING -> MaterialTheme.colorScheme.primary
        AgentMessage.CommandExecution.Status.SUCCESS -> Color(0xFF4CAF50)
        AgentMessage.CommandExecution.Status.FAILED -> Color(0xFFE53935)
    }

    val statusIcon = when (message.status) {
        AgentMessage.CommandExecution.Status.PENDING -> Icons.Rounded.RadioButtonUnchecked
        AgentMessage.CommandExecution.Status.RUNNING -> Icons.Rounded.Sync
        AgentMessage.CommandExecution.Status.SUCCESS -> Icons.Rounded.CheckCircle
        AgentMessage.CommandExecution.Status.FAILED -> Icons.Rounded.Error
    }

    val commandIcon = message.commandIcon
        // 兜底：无图标时显示一个通用命令图标
        ?: Icons.AutoMirrored.Rounded.ShortText

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 执行状态图标
            Icon(
                imageVector = statusIcon,
                contentDescription = when (message.status) {
                    AgentMessage.CommandExecution.Status.PENDING -> stringResource(R.string.cd_status_pending)
                    AgentMessage.CommandExecution.Status.RUNNING -> stringResource(R.string.cd_status_running)
                    AgentMessage.CommandExecution.Status.SUCCESS -> stringResource(R.string.cd_status_success)
                    AgentMessage.CommandExecution.Status.FAILED -> stringResource(R.string.cd_status_failed)
                },
                tint = statusColor,
                modifier = Modifier.size(18.dp)
            )

            // 命令类型图标（替代原本冗长的 commandName 文字）
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = commandIcon,
                    contentDescription = message.commandName,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
                // 批量执行时显示小序号徽章
                if (message.total > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp)
                            .size(14.dp)
                            .background(statusColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${message.index}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 命令详情（参数等）
            if (message.detail.isNotBlank()) {
                Text(
                    text = message.detail,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * 输入模式枚举
 */
private enum class InputMode {
    TEXT,
    VOICE
}

@Composable
private fun ChatInputBar(
    isProcessing: Boolean,
    onSend: (String) -> Unit,
    onCommand: (AiAgentCommand) -> Unit,
    modifier: Modifier = Modifier,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { UserPreferencesRepository(context) }
    val savedInputMode by settingsRepository.chatInputModeFlow.collectAsState(initial = "text")
    // 语音为默认关闭的实验能力（2026-08-19）：语音模式 ≠ DISABLED 时才显示语音入口
    val voiceCommandMode by settingsRepository.voiceCommandModeFlow.collectAsState(
        initial = VoiceCommandMode.DISABLED
    )
    val voiceEnabled = voiceCommandMode != VoiceCommandMode.DISABLED
    var inputMode by remember(savedInputMode, voiceEnabled) {
        mutableStateOf(
            if (savedInputMode == "text" || !voiceEnabled) InputMode.TEXT else InputMode.VOICE
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (inputMode) {
            InputMode.TEXT -> TextInputMode(
                text = text,
                onTextChange = { text = it },
                isProcessing = isProcessing,
                onSend = {
                    if (text.isNotBlank() && !isProcessing) {
                        onSend(text.trim())
                        text = ""
                        keyboardController?.hide()
                    }
                },
                onSwitchToVoice = {
                    inputMode = InputMode.VOICE
                    keyboardController?.hide()
                    scope.launch {
                        settingsRepository.updateChatInputMode("voice")
                    }
                },
                voiceEnabled = voiceEnabled,
                voiceCoordinator = voiceCoordinator
            )
            InputMode.VOICE -> VoiceInputMode(
                onSwitchToText = {
                    inputMode = InputMode.TEXT
                    keyboardController?.show()
                    scope.launch {
                        settingsRepository.updateChatInputMode("text")
                    }
                },
                onVoiceResult = { result ->
                    if (result.isNotBlank()) {
                        onSend(result)
                    }
                },
                context = context,
                voiceCoordinator = voiceCoordinator
            )
        }
    }
}

/**
 * 检测当前音频输入设备类型（广播驱动，无需轮询）
 */
@Composable
private fun rememberInputAudioDevice(voiceCoordinator: VoiceCommandCoordinator?): InputAudioDevice {
    var device by remember { mutableStateOf<InputAudioDevice>(InputAudioDevice.BuiltInMic) }
    val context = LocalContext.current

    // 初始检测
    LaunchedEffect(Unit) {
        val initialDevice = AudioRecorder(context).currentInputDevice
        Logger.i(TAG, "Initial input device: ${initialDevice.label}")
        device = initialDevice
    }

    // 注册系统广播监听耳机连接/断开
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", 0)
                        val hasMic = intent.getIntExtra("microphone", 0) == 1
                        Logger.i(TAG, "Headset plug event: state=$state, hasMic=$hasMic")
                        device = AudioRecorder(context).currentInputDevice
                    }
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                        Logger.i(TAG, "Bluetooth headset event: state=$state")
                        device = AudioRecorder(context).currentInputDevice
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        Logger.d(TAG, "Audio device broadcast receiver registered")

        onDispose {
            context.unregisterReceiver(receiver)
            Logger.d(TAG, "Audio device broadcast receiver unregistered")
        }
    }

    return device
}

@Composable
private fun TextInputMode(
    text: String,
    onTextChange: (String) -> Unit,
    isProcessing: Boolean,
    onSend: () -> Unit,
    onSwitchToVoice: () -> Unit,
    /** 语音入口是否可用（2026-08-19：语音模式 ≠ DISABLED 才为 true，默认关闭） */
    voiceEnabled: Boolean,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    // 语音关闭时不注册耳机广播监听（2026-08-19：rememberInputAudioDevice 仅在 voiceEnabled 分支内调用）
    val hasContent = text.isNotBlank()

    // 单行布局：语音按钮 + 输入框 + 发送按钮，全部在同一行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 左侧：语音切换按钮（2026-08-19：语音默认关闭，仅语音模式 ≠ DISABLED 时显示）
        if (voiceEnabled) {
            // 仅语音开启时才注册耳机广播监听
            val inputDevice = rememberInputAudioDevice(voiceCoordinator)
            val isHeadsetConnected = inputDevice is InputAudioDevice.BluetoothSco ||
                inputDevice is InputAudioDevice.WiredHeadset
            Box {
                IconButton(
                    onClick = onSwitchToVoice,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardVoice,
                        contentDescription = stringResource(R.string.cd_switch_to_voice),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 耳机连接状态小标记
                if (isHeadsetConnected) {
                    HeadsetBadge(
                        device = inputDevice,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }

        // 中间：输入框（占据剩余空间）
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    stringResource(R.string.ai_agent_input_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp, max = 120.dp),
            minLines = 1,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { onSend() }
            ),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
            )
        )

        // 右侧：发送按钮
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (hasContent && !isProcessing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    }
                )
                .clickable(enabled = hasContent && !isProcessing) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = stringResource(R.string.chat_send),
                tint = if (hasContent && !isProcessing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun VoiceInputMode(
    onSwitchToText: () -> Unit,
    onVoiceResult: (String) -> Unit,
    context: Context,
    voiceCoordinator: VoiceCommandCoordinator? = null
) {
    var isListening by remember { mutableStateOf(false) }
    var isCancelRecord by remember { mutableStateOf(false) }

    // 检查语音协调器是否可用
    // 修复P0-2：如果voiceCoordinator为null，添加诊断日志
    LaunchedEffect(voiceCoordinator) {
        if (voiceCoordinator == null) {
            Logger.w(TAG, "VoiceInputMode: voiceCoordinator is NULL! This should not happen.")
        } else {
            Logger.d(TAG, "VoiceInputMode: voiceCoordinator is available: $voiceCoordinator")
        }
    }
    val isVoiceAvailable = voiceCoordinator != null
    val voiceUnavailableText = stringResource(R.string.voice_unavailable)

    LaunchedEffect(isListening, isCancelRecord) {
    }

    // 单行布局：键盘切换 + 按住说话按钮 + 可扩展区域
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 左侧：键盘切换按钮
        IconButton(
            onClick = onSwitchToText,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.switch_to_keyboard),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        }

        // 中间：按住说话按钮（占据剩余空间）
        val buttonBackground = when {
            isListening && !isCancelRecord -> MaterialTheme.colorScheme.primary
            isListening && isCancelRecord -> Color(0xFFE53935)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(buttonBackground)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            Logger.d(TAG, "Pointer event: ${event.type}, changes=${event.changes.size}")
                            event.changes.forEach { change ->
                                Logger.d(TAG, "Change: pressed=${change.pressed}, consumed=${change.isConsumed}, pos=${change.position}")
                                when {
                                    // 手指按下（仅初始 DOWN，避免 MOVE 事件重复触发 toast）
                                    change.pressed && !change.previousPressed && !change.isConsumed && !isListening -> {
                                        Logger.d(TAG, "Finger DOWN detected -> voiceCoordinator=${voiceCoordinator != null}, isVoiceAvailable=$isVoiceAvailable")
                                        if (!isVoiceAvailable) {
                                            Logger.w(TAG, "Voice coordinator not available")
                                            Handler(Looper.getMainLooper()).post {
                                                Toast.makeText(
                                                    context,
                                                    voiceUnavailableText,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            return@forEach
                                        }
                                        isListening = true
                                        isCancelRecord = false
                                        Logger.d(TAG, "Using VoiceCommandCoordinator for push-to-talk")
                                        voiceCoordinator?.startPushToTalk(
                                            onResult = { result ->
                                                Logger.i(TAG, "VoiceCoordinator result: '$result'")
                                                isListening = false
                                                isCancelRecord = false
                                                if (result.isNotBlank()) {
                                                    onVoiceResult(result)
                                                }
                                            },
                                            // Chat 面板自己处理 LLM，避免与 VoiceCommandCoordinator 内部处理重复
                                            processAsCommand = false
                                        )
                                    }
                                    // 手指抬起
                                    !change.pressed -> {
                                        Logger.d(TAG, "Finger UP detected -> isListening=$isListening")
                                        if (isListening) {
                                            isListening = false
                                            voiceCoordinator?.stopPushToTalk()
                                            isCancelRecord = false
                                        }
                                    }
                                }
                                // ACTION_MOVE: 检测是否移出按钮区域以取消
                                if (isListening && change.pressed) {
                                    val bounds = this@pointerInput.size
                                    val x = change.position.x
                                    val y = change.position.y
                                    val newCancel = x < 0 || x > bounds.width || y < 0 || y > bounds.height
                                    if (newCancel != isCancelRecord) {
                                        Logger.d(TAG, "Cancel state changed: $isCancelRecord -> $newCancel")
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
            Text(
                text = when {
                    !isVoiceAvailable -> stringResource(R.string.voice_unavailable)
                    isListening -> stringResource(R.string.release_to_stop)
                    else -> stringResource(R.string.hold_to_speak)
                },
                color = if (isListening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }

        // 右侧：占位保持对称（可选，当前留空保持简洁）
        // 语音模式下暂无额外功能按钮，保持简洁
        Box(modifier = Modifier.size(36.dp))
    }
}

/**
 * 耳机状态小标记
 * 在语音输入按钮右上角显示一个小圆点/图标，指示当前音频输入设备
 */
@Composable
private fun HeadsetBadge(
    device: InputAudioDevice,
    modifier: Modifier = Modifier
) {
    val tintColor = when (device) {
        is InputAudioDevice.BluetoothSco -> Color(0xFF4FC3F7) // 浅蓝色表示蓝牙
        is InputAudioDevice.WiredHeadset -> Color(0xFF81C784) // 浅绿色表示有线
        is InputAudioDevice.BuiltInMic -> Color.Transparent
    }

    Box(
        modifier = modifier
            .padding(top = 2.dp, end = 2.dp)
            .size(14.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = when (device) {
                is InputAudioDevice.BluetoothSco -> stringResource(R.string.asr_input_device_bluetooth)
                is InputAudioDevice.WiredHeadset -> stringResource(R.string.asr_input_device_wired)
                is InputAudioDevice.BuiltInMic -> stringResource(R.string.asr_input_device_builtin)
            },
            tint = tintColor,
            modifier = Modifier.size(10.dp)
        )
    }
}
