package com.mamba.picme.features.chat.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 云端浏览器直播卡（spec §4，INLINE 形态）：
 * 最新帧大图 + 页面标题/URL + 最近 3 步动作流水；定格/失败渲染终态。
 * 帧来源 = 动作级截图（改状态工具动作带 wantFrame，经 delegate 回灌 live 态），INLINE 卡不发起推流；
 * WS 推流只挂全屏接管页（[BrowserFramePreviewOverlay] 打开期间，由调用侧驱动 watch/unwatch）。
 */
@Composable
fun BrowserLiveCard(
    part: MessagePart.BrowserLive,
    onOpenFullPreview: (String) -> Unit, // 传 base64 帧
    modifier: Modifier = Modifier,
) {
    val running = part.state != ToolPartState.OUTPUT_AVAILABLE && part.state != ToolPartState.OUTPUT_ERROR

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 外层 12dp 圆角对齐 HtmlCard/GachaCandidateStrip/EngineerTaskCard 现状
            // （AppShapes 无 12dp 全圆角档，待 spec 固化时评估是否提 token）
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 头部：标题 + URL + 状态行
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = part.pageTitle.ifBlank { stringResource(R.string.browser_live_title_default) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (part.currentUrl.isNotBlank()) {
                    Text(
                        text = part.currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = when (part.state) {
                    ToolPartState.OUTPUT_ERROR -> stringResource(R.string.browser_live_failed)
                    ToolPartState.OUTPUT_AVAILABLE -> stringResource(R.string.browser_live_done_summary, part.actionCount)
                    else -> stringResource(R.string.browser_live_running)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (part.state == ToolPartState.OUTPUT_ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 帧区：16:9，base64 后台解码（先例 ChartSvgImage.kt）
        val frame = part.frameJpegBase64
        if (frame != null) {
            val bitmap = rememberBrowserFrameBitmap(frame)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(AppShapes.card)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onOpenFullPreview(frame) },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.browser_live_view_full),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        // 动作流水（最近 3 步，新在尾）
        if (part.actions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                part.actions.forEach { entry ->
                    Text(
                        text = "· " + entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 错误原因
        part.errorReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 5s 无新帧判定为重连中（WS 断连/网络抖动期间给状态反馈；上游帧 500ms 节流，阈值留足裕量）。 */
private const val FRAME_STALE_MS = 5_000L

/**
 * 浏览器直播帧全屏交互接管（M2 takeover → M2.5 重构：三段布局 + 暂停 + 特殊键）：
 * - 顶栏：返回（左上，替代原右上 Close X）+ 状态胶囊（LIVE/已暂停/会话已结束/重连中 + 页面域名）
 *   + 暂停/继续 + 模式切换；圆形半透明按钮风格沿用现状。
 * - 帧区：ContentScale.Fit 居中；结束态叠 30% 黑 + 「会话已结束」水印，暂停态定格 + 「已暂停」水印。
 * - 底栏（仅 Interact 模式）：IME 附件行（⏎ Enter / Tab / Esc，仅键盘弹出可见）+ 输入行；
 *   imePadding 只挂底栏，键盘弹出帧区不重排；发送后清空文本不收键盘。
 * 两种模式——
 * - **Interact**（默认）：点按→clickAt（[mapOffsetToPage] 屏幕→CSS 页面坐标）、拖动→scroll（20px 节流）、
 *   双指捏合→自动切 Zoom 模式并完成本次缩放；帧外黑边无操作。
 * - **Zoom**：双指 1x~5x 缩放 / 单指平移 / 双击复位；底栏隐藏，首次进入显示一次性手势提示。
 * 系统返回/顶栏返回：Zoom 且 scale>1x 先复位，否则退出。
 * 暂停 = 客户端停流不停会话：帧定格在暂停时刻，action 全部拦截并 toast「已暂停」；
 * 暂停期间由调用侧（ChatScreen）经 [onPausedChanged] unwatch 停推流，恢复 re-watch。
 */
@Composable
fun BrowserFramePreviewOverlay(
    frame: String,
    sessionId: String,
    frameWidth: Int,
    frameHeight: Int,
    sessionRunning: Boolean,
    currentUrl: String?,
    onPausedChanged: (Boolean) -> Unit,
    onAction: (String, JSONObject) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val pausedToast = stringResource(R.string.browser_takeover_paused_toast)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isZoomMode by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var inputText by remember { mutableStateOf("") }
    var isPaused by remember { mutableStateOf(false) }
    // 暂停时刻定格帧（暂停期间 live 帧即使刷新也不消费）
    var pausedFrame by remember { mutableStateOf<String?>(null) }
    val displayFrame = pausedFrame ?: frame
    val bitmap = rememberBrowserFrameBitmap(displayFrame)

    // 重连启发式：running 且未暂停时，FRAME_STALE_MS 无新帧 → ↻ 重连中
    var lastFrameAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(frame) { lastFrameAt = System.currentTimeMillis() }
    var reconnecting by remember { mutableStateOf(false) }
    LaunchedEffect(sessionRunning, isPaused) {
        if (sessionRunning && !isPaused) {
            lastFrameAt = System.currentTimeMillis()
            while (true) {
                delay(1000)
                reconnecting = System.currentTimeMillis() - lastFrameAt > FRAME_STALE_MS
            }
        } else {
            reconnecting = false
        }
    }

    // Zoom 模式首次进入显示一次性手势提示（3.5s 自动消失；提前切走则下次进入再提示）
    var zoomHintShown by rememberSaveable { mutableStateOf(false) }
    var zoomHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isZoomMode) {
        if (isZoomMode && !zoomHintShown) {
            zoomHintShown = true
            zoomHintVisible = true
            delay(3500)
            zoomHintVisible = false
        }
    }

    // 暂停期间 action 拦截 + toast「已暂停」（2s 节流防 scroll 手势连续触发 toast 风暴）；
    // 结束态静默丢弃（定格画面可浏览，水印承担状态提示）。返回是否真正下发。
    var lastPausedToastAt by remember { mutableLongStateOf(0L) }
    val tryDispatch: (String, JSONObject) -> Boolean = { action, params ->
        when {
            isPaused -> {
                val now = System.currentTimeMillis()
                if (now - lastPausedToastAt > 2000) {
                    lastPausedToastAt = now
                    Toast.makeText(context, pausedToast, Toast.LENGTH_SHORT).show()
                }
                false
            }
            sessionRunning -> {
                onAction(action, params)
                true
            }
            else -> false
        }
    }

    // 发送输入：下发成功才清空文本；不收键盘（连续输入场景）
    val sendInput: () -> Unit = {
        val text = inputText
        if (text.isNotBlank() && tryDispatch("typeText", JSONObject().put("text", text))) {
            inputText = ""
        }
    }

    // 返回：Zoom 且 scale>1x 先复位，否则退出
    val exitOrResetZoom: () -> Unit = {
        if (isZoomMode && scale > 1.01f) {
            scale = 1f
            offset = Offset.Zero
        } else {
            onClose()
        }
    }
    BackHandler(onBack = exitOrResetZoom)

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val inputEnabled = sessionRunning && !isPaused
    val pageHost = remember(currentUrl) {
        currentUrl?.let { url -> runCatching { Uri.parse(url).host }.getOrNull() }
            ?.takeIf { host -> host.isNotBlank() }
    }
    val circleButtonModifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .background(Color.Black.copy(alpha = 0.5f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            val imageModifier = if (isZoomMode) {
                Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg)
                    .onSizeChanged { containerSize = it }
                    .pointerInput(sessionId) {
                        detectTapGestures { tapOffset ->
                            val page = mapOffsetToPage(
                                tapOffset, containerSize, frameWidth, frameHeight
                            )
                            if (page != null) {
                                tryDispatch("clickAt", JSONObject()
                                    .put("x", page.x.toInt())
                                    .put("y", page.y.toInt()))
                            }
                        }
                    }
                    .pointerInput(sessionId) {
                        // 单指拖动 → scroll（20px 节流，取反：手指下滑=内容跟随=页面上滚 wheel 负值）；
                        // 双指捏合 → 自动切 Zoom 模式并完成本次缩放
                        var accumulated = Offset.Zero
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f) {
                                isZoomMode = true
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                            } else {
                                accumulated += pan
                                if (accumulated.y > 20 || accumulated.y < -20) {
                                    tryDispatch("scroll", JSONObject()
                                        .put("dx", 0)
                                        .put("dy", -accumulated.y.toInt()))
                                    accumulated = Offset.Zero
                                }
                            }
                        }
                    }
            }

            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.browser_live_view_full),
                modifier = imageModifier,
                contentScale = ContentScale.Fit
            )
        }

        // 终态/暂停水印（无 pointerInput，不拦截帧手势）
        if (!sessionRunning) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f)))
            TakeoverWatermark(text = stringResource(R.string.browser_takeover_status_ended))
        } else if (isPaused) {
            TakeoverWatermark(text = stringResource(R.string.browser_takeover_status_paused))
        }

        // 顶栏：返回（左）+ 状态胶囊（中）+ 暂停/继续 + 模式切换（右）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            IconButton(onClick = exitOrResetZoom, modifier = circleButtonModifier) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                TakeoverStatusPill(
                    sessionRunning = sessionRunning,
                    isPaused = isPaused,
                    reconnecting = reconnecting,
                    pageHost = pageHost,
                )
            }
            if (sessionRunning) {
                IconButton(
                    onClick = {
                        val next = !isPaused
                        pausedFrame = if (next) displayFrame else null
                        isPaused = next
                        onPausedChanged(next)
                    },
                    modifier = circleButtonModifier
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = stringResource(
                            if (isPaused) R.string.browser_takeover_resume else R.string.browser_takeover_pause
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            IconButton(
                onClick = {
                    isZoomMode = !isZoomMode
                    if (!isZoomMode) { scale = 1f; offset = Offset.Zero }
                },
                modifier = circleButtonModifier
            ) {
                Icon(
                    imageVector = if (isZoomMode) Icons.Rounded.TouchApp else Icons.Rounded.ZoomIn,
                    contentDescription = if (isZoomMode) {
                        stringResource(R.string.browser_takeover_mode_interact)
                    } else {
                        stringResource(R.string.browser_takeover_mode_zoom)
                    },
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 底栏（仅 Interact 模式）：IME 附件行（仅键盘弹出可见）+ 输入行；
        // imePadding 只挂底栏 → 键盘弹出帧区不重排（输入行浮在黑底上）
        if (!isZoomMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (imeVisible && inputEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TakeoverKeyButton(label = stringResource(R.string.browser_takeover_key_enter)) {
                            tryDispatch("key", JSONObject().put("key", "Enter"))
                        }
                        TakeoverKeyButton(label = stringResource(R.string.browser_takeover_key_tab)) {
                            tryDispatch("key", JSONObject().put("key", "Tab"))
                        }
                        TakeoverKeyButton(label = stringResource(R.string.browser_takeover_key_esc)) {
                            tryDispatch("key", JSONObject().put("key", "Escape"))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { value -> inputText = value },
                        modifier = Modifier.weight(1f),
                        enabled = inputEnabled,
                        placeholder = {
                            Text(
                                stringResource(R.string.browser_takeover_input_hint),
                                color = if (inputEnabled) Color.Gray else Color.DarkGray
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendInput() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.Gray,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.Gray,
                            disabledBorderColor = Color.DarkGray,
                        )
                    )
                    TextButton(
                        onClick = sendInput,
                        enabled = inputEnabled && inputText.isNotBlank()
                    ) {
                        Text(
                            stringResource(R.string.browser_takeover_send),
                            color = if (inputEnabled) Color.White else Color.Gray
                        )
                    }
                }
            }
        }

        // Zoom 模式一次性手势提示
        if (zoomHintVisible) {
            Text(
                text = stringResource(R.string.browser_takeover_zoom_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(Spacing.xl)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        }
    }
}

/** 顶栏状态胶囊：● LIVE（绿）/ ⏸ 已暂停（黄）/ ■ 会话已结束（灰）/ ↻ 重连中（橙），后接页面域名。 */
@Composable
private fun TakeoverStatusPill(
    sessionRunning: Boolean,
    isPaused: Boolean,
    reconnecting: Boolean,
    pageHost: String?,
) {
    val (dotColor, labelRes) = when {
        !sessionRunning -> Color.Gray to R.string.browser_takeover_status_ended
        isPaused -> Color(0xFFFFC107) to R.string.browser_takeover_status_paused
        reconnecting -> Color(0xFFFF9800) to R.string.browser_takeover_status_reconnecting
        else -> Color(0xFF4CAF50) to R.string.browser_takeover_status_live
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(dotColor))
        Text(
            text = stringResource(labelRes),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        if (pageHost != null) {
            Text(
                text = "· $pageHost",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 帧区中央水印（结束态/暂停态）。 */
@Composable
private fun TakeoverWatermark(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        )
    }
}

/** IME 附件行特殊键按钮（点击不收键盘——TextButton 不持焦点，键盘保持弹出）。 */
@Composable
private fun TakeoverKeyButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 屏幕点按坐标 → CSS 页面坐标映射。
 * 帧以 ContentScale.Fit 渲染在 container 内，需计算实际渲染区域并映射回原始帧像素。
 * 返回 null 表示容器尚未测量或坐标在帧外。
 */
private fun mapOffsetToPage(
    tap: Offset,
    container: IntSize,
    frameW: Int,
    frameH: Int,
): Offset? {
    if (container.width == 0 || container.height == 0 || frameW == 0 || frameH == 0) return null

    // ContentScale.Fit: 帧等比缩放至容器内，保持宽高比
    val scale = minOf(
        container.width.toFloat() / frameW,
        container.height.toFloat() / frameH
    )
    val renderedW = frameW * scale
    val renderedH = frameH * scale
    val offsetX = (container.width - renderedW) / 2f
    val offsetY = (container.height - renderedH) / 2f

    // 点按坐标 → 帧内相对坐标
    val relX = tap.x - offsetX
    val relY = tap.y - offsetY
    if (relX < 0 || relY < 0 || relX > renderedW || relY > renderedH) return null

    // 映射回 CSS 页面坐标
    return Offset(relX / scale, relY / scale)
}

/** base64 JPEG → ImageBitmap，后台线程解码（先例 ChartSvgImage.kt:39-68）。 */
@Composable
private fun rememberBrowserFrameBitmap(frameBase64: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, frameBase64) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(frameBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}
