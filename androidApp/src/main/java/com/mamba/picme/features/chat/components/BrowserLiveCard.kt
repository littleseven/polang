package com.mamba.picme.features.chat.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 云端浏览器直播卡（spec §4，INLINE 形态，M2 WebSocket 推流）：
 * 最新帧大图 + 页面标题/URL + 最近 3 步动作流水；会话中经 [onWatchSession] 启动 WS 推流，
 * 离开组合经 [onUnwatchSession] 断开；定格/失败渲染终态。点按帧进全屏预览（[BrowserFramePreviewOverlay]）。
 */
@Composable
fun BrowserLiveCard(
    part: MessagePart.BrowserLive,
    onWatchSession: (String) -> Unit,
    onUnwatchSession: (String) -> Unit,
    onOpenFullPreview: (String) -> Unit, // 传 base64 帧
    modifier: Modifier = Modifier,
) {
    // WS 推流：仅会话进行中连接；离开组合（划出视口）经 DisposableEffect 断开
    // （LaunchedEffect 取消不会执行 else 分支，单靠它会在卡片滚出视口后泄漏 WS 连接）
    val running = part.state != ToolPartState.OUTPUT_AVAILABLE && part.state != ToolPartState.OUTPUT_ERROR
    LaunchedEffect(part.sessionId, running) {
        if (running) {
            onWatchSession(part.sessionId)
        } else {
            onUnwatchSession(part.sessionId)
        }
    }
    DisposableEffect(part.sessionId) {
        onDispose { onUnwatchSession(part.sessionId) }
    }

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

/**
 * 浏览器直播帧全屏交互接管（M2 takeover）：
 * 两种模式——
 * - **Interact**（默认）：点按→clickAt、拖动→scroll、底部输入框→typeText；
 *   坐标经 [mapOffsetToPage] 从屏幕映射到 CSS 页面坐标。
 * - **Zoom**：双指 1x~5x 缩放/拖动（原预览行为，供检查细节）。
 * 模式经右上按钮切换；右上另有关闭按钮。黑底/白图标沿用全屏预览惯例。
 */
@Composable
fun BrowserFramePreviewOverlay(
    frame: String,
    sessionId: String,
    frameWidth: Int,
    frameHeight: Int,
    onAction: (String, JSONObject) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    val bitmap = rememberBrowserFrameBitmap(frame)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isZoomMode by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var inputText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            ),
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
                                onAction("clickAt", JSONObject()
                                    .put("x", page.x.toInt())
                                    .put("y", page.y.toInt()))
                            }
                        }
                    }
                    .pointerInput(sessionId) {
                        var accumulated = Offset.Zero
                        detectDragGestures(
                            onDragStart = { accumulated = Offset.Zero },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulated += dragAmount
                                // 每 20px 发送一次 scroll，避免消息风暴；
                                // 取反：手指下滑（dy>0）= 内容跟随手指 = 页面上滚（wheel 负值）
                                if (accumulated.y > 20 || accumulated.y < -20) {
                                    onAction("scroll", JSONObject()
                                        .put("dx", 0)
                                        .put("dy", -accumulated.y.toInt()))
                                    accumulated = Offset.Zero
                                }
                            }
                        )
                    }
            }

            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.browser_live_view_full),
                modifier = imageModifier,
                contentScale = ContentScale.Fit
            )
        }

        // 顶部工具栏：关闭 + 模式切换
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // 模式切换
            IconButton(
                onClick = {
                    isZoomMode = !isZoomMode
                    if (!isZoomMode) { scale = 1f; offset = Offset.Zero }
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = if (isZoomMode) Icons.Rounded.PlayArrow else Icons.Rounded.ZoomIn,
                    contentDescription = if (isZoomMode) {
                        stringResource(R.string.browser_takeover_mode_interact)
                    } else {
                        stringResource(R.string.browser_takeover_mode_zoom)
                    },
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            // 关闭
            IconButton(
                onClick = onClose,
                modifier = Modifier
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

        // 底部输入区（仅 Interact 模式）
        if (!isZoomMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(Spacing.md)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.browser_takeover_input_hint), color = Color.Gray) },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.Gray,
                    )
                )
                TextButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onAction("typeText", JSONObject().put("text", inputText))
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank()
                ) {
                    Text(stringResource(R.string.browser_takeover_send), color = Color.White)
                }
            }
        }
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
