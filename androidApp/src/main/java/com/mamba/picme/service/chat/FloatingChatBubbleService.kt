package com.mamba.picme.service.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.contentColorFor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.mamba.picme.MainActivity
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.domain.agent.capability.SystemCapability
import com.mamba.picme.domain.chat.ChatMessageType
import com.mamba.picme.features.chat.ChatMessageUi
import com.mamba.picme.features.chat.ChatModelOption
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.common.chat.AgentMarkdown
import kotlinx.coroutines.launch

/**
 * 全局悬浮聊天气泡服务
 *
 * Phase 3：在应用外提供可拖拽的 AI 聊天入口。
 *
 * 职责：
 * - 以 Foreground Service 形式持有悬浮窗（气泡 + 可展开面板）
 * - 复用 [ChatViewModel] 与 Agent 编排器，保持与 Chat 页面一致的体验
 * - 注册系统 Capability，使用户可通过自然语言控制本机设置
 *
 * 注意：
 * - 需要 [Settings.ACTION_MANAGE_OVERLAY_PERMISSION] 权限
 * - 需要 [android.Manifest.permission.FOREGROUND_SERVICE] 与 dataSync 类型
 */
class FloatingChatBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleContainer: FrameLayout? = null
    private var panelView: ComposeView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private val chatViewModel: ChatViewModel by lazy { createChatViewModel() }

    private val serviceLifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private var isPanelExpanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        registerCapabilities()
        initWindowParams()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                if (!isPanelExpanded) showBubble()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeBubble()
        removePanel()
        serviceLifecycleOwner.destroy()
        Logger.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    /**
     * 注册全局 Capability，确保悬浮窗外也能执行系统命令。
     */
    private fun registerCapabilities() {
        val orchestrator = com.mamba.picme.agent.core.facade.AgentOrchestrator.getInstance()
        orchestrator.registerCapability(SystemCapability(this))
        Logger.d(TAG, "Registered system capability for floating chat")
    }

    /**
     * 直接构造 [ChatViewModel]。Service 没有 LifecycleOwner，因此不通过 ViewModelProvider 创建。
     */
    private fun createChatViewModel(): ChatViewModel {
        val app = application as PoLangApplication
        val factory = app.container.createChatViewModelFactory()
        @Suppress("UNCHECKED_CAST")
        val vm = (factory as androidx.lifecycle.ViewModelProvider.Factory)
            .create(ChatViewModel::class.java)
        return vm
    }

    // region 悬浮窗参数

    @Suppress("DEPRECATION")
    private fun initWindowParams() {
        val bubbleSize = dpToPx(BUBBLE_SIZE_DP)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - bubbleSize - dpToPx(16)
            y = screenHeight() - bubbleSize - dpToPx(120)
        }

        panelParams = WindowManager.LayoutParams(
            (screenWidth() * 0.85).toInt(),
            (screenHeight() * 0.65).toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    // endregion

    // region 气泡视图

    private fun showBubble() {
        if (bubbleContainer != null) return

        val container = FrameLayout(this).apply {
            setOnTouchListener(BubbleTouchListener())
            // Compose 会沿着 parent 查找 Window root 的 LifecycleOwner，
            // 因此必须在根 FrameLayout 上设置，而不仅仅是内部的 ComposeView。
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setContent {
                MaterialTheme {
                    FloatingBubbleContent()
                }
            }
        }

        container.addView(composeView)
        bubbleContainer = container

        try {
            windowManager.addView(container, bubbleParams)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add bubble", e)
        }
    }

    private fun removeBubble() {
        bubbleContainer?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to remove bubble", e)
            }
            bubbleContainer = null
        }
    }

    private fun updateBubblePosition(deltaX: Float, deltaY: Float) {
        bubbleParams.x = (bubbleParams.x + deltaX).toInt()
        bubbleParams.y = (bubbleParams.y + deltaY).toInt()
        clampBubbleToScreen()
        try {
            windowManager.updateViewLayout(bubbleContainer, bubbleParams)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to update bubble position", e)
        }
    }

    private fun clampBubbleToScreen() {
        val bubbleSize = dpToPx(BUBBLE_SIZE_DP)
        val maxX = screenWidth() - bubbleSize
        val maxY = screenHeight() - bubbleSize
        bubbleParams.x = bubbleParams.x.coerceIn(0, maxX)
        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)
    }

    /**
     * 气泡触摸监听：处理拖拽与点击展开面板。
     */
    private inner class BubbleTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var isDragging = false

        override fun onTouch(view: View?, event: MotionEvent?): Boolean {
            event ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (kotlin.math.abs(dx) > DRAG_THRESHOLD_PX || kotlin.math.abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        bubbleParams.x = (initialX + dx).toInt()
                        bubbleParams.y = (initialY + dy).toInt()
                        clampBubbleToScreen()
                        windowManager.updateViewLayout(bubbleContainer, bubbleParams)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        expandPanel()
                    }
                    return true
                }
            }
            return false
        }
    }

    // endregion

    // region 面板视图

    private fun expandPanel() {
        if (isPanelExpanded) return
        removeBubble()
        isPanelExpanded = true

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setContent {
                MaterialTheme {
                    FloatingChatPanel(
                        viewModel = chatViewModel,
                        onClose = ::collapsePanel
                    )
                }
            }
        }
        panelView = composeView

        try {
            windowManager.addView(composeView, panelParams)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add panel", e)
            isPanelExpanded = false
            showBubble()
        }
    }

    private fun collapsePanel() {
        removePanel()
        isPanelExpanded = false
        showBubble()
    }

    private fun removePanel() {
        panelView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to remove panel", e)
            }
            panelView = null
        }
    }

    // endregion

    // region 通知与前台服务

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_chat_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.floating_chat_notification_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingChatBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_chat_notification_title))
            .setContentText(getString(R.string.floating_chat_notification_content))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .addAction(0, getString(R.string.floating_chat_stop), stopPending)
            .build()
    }

    // endregion

    // region 工具方法

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun screenWidth(): Int {
        return resources.displayMetrics.widthPixels
    }

    private fun screenHeight(): Int {
        return resources.displayMetrics.heightPixels
    }

    // endregion

    companion object {
        private const val TAG = "FloatingChatBubbleService"
        private const val CHANNEL_ID = "picme_floating_chat"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.mamba.picme.action.STOP_FLOATING_CHAT"
        private const val BUBBLE_SIZE_DP = 56
        private const val DRAG_THRESHOLD_PX = 15

        /**
         * 检查是否已获得悬浮窗权限。
         */
        fun canDrawOverlays(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        /**
         * 启动悬浮聊天服务。调用前请确保已获得 [SYSTEM_ALERT_WINDOW] 权限。
         */
        fun start(context: Context) {
            val intent = Intent(context, FloatingChatBubbleService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * 停止悬浮聊天服务。
         */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingChatBubbleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * 打开悬浮窗权限设置页。
         */
        fun openOverlayPermissionSettingsIntent(context: Context): Intent {
            return Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        /**
         * 判断当前服务是否正在运行。
         */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            return manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == FloatingChatBubbleService::class.java.name }
        }
    }
}

// region Compose UI

@Composable
private fun FloatingBubbleContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.ChatBubble,
            contentDescription = stringResource(R.string.floating_chat_title),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun FloatingChatPanel(
    viewModel: ChatViewModel,
    onClose: () -> Unit
) {
    val messages by viewModel.displayMessages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
            .imePadding(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PanelHeader(
                currentModel = currentModel,
                isProcessing = isProcessing,
                onClose = onClose
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    FloatingChatMessageItem(message = message)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    placeholder = { Text(stringResource(R.string.floating_chat_input_hint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            inputText = ""
                            viewModel.sendMessage(text)
                        }
                    },
                    enabled = inputText.trim().isNotEmpty() && !isProcessing
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_send)
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    currentModel: ChatModelOption,
    isProcessing: Boolean,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.floating_chat_title),
                style = MaterialTheme.typography.titleMedium
            )
            if (isProcessing) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ModelIndicatorChip(currentModel = currentModel)
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close)
                )
            }
        }
    }
}

@Composable
private fun ModelIndicatorChip(currentModel: ChatModelOption) {
    // chat 仅远程：模型指示为静态标签（本地切换已移除）
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ChatBubbleTokens.brandGradientStart.copy(alpha = ChatBubbleTokens.capsuleActiveAlpha),
        contentColor = ChatBubbleTokens.brandGradientStart
    ) {
        Text(
            text = stringResource(currentModel.labelRes),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun FloatingChatMessageItem(message: ChatMessageUi) {
    val isUser = message.type == ChatMessageType.USER_TEXT ||
        message.type == ChatMessageType.USER_IMAGE
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = contentColorFor(bubbleColor)
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bubbleColor,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(
                                context,
                                context.getString(R.string.copy_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    color = textColor,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                AgentMarkdown(
                    content = message.content,
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

// endregion
