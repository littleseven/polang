package com.mamba.picme.features.chat.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 云端浏览器直播卡（spec §4，INLINE 形态）：
 * 最新帧大图 + 页面标题/URL + 最近 3 步动作流水；会话中 1s 节拍轮询帧（watch 模式），
 * 定格/失败渲染终态。点按帧进全屏预览（[BrowserFramePreviewOverlay]）。
 */
@Composable
fun BrowserLiveCard(
    part: MessagePart.BrowserLive,
    onPollFrame: (String) -> Unit,
    onOpenFullPreview: (String) -> Unit, // 传 base64 帧
    modifier: Modifier = Modifier,
) {
    // watch 模式：仅会话进行中轮询；离开组合（划出视口）自动取消
    val running = part.state != ToolPartState.OUTPUT_AVAILABLE && part.state != ToolPartState.OUTPUT_ERROR
    LaunchedEffect(part.sessionId, running) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            onPollFrame(part.sessionId)
            delay(1000)
        }
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
 * 浏览器直播帧全屏预览（单帧版）：base64 后台解码 + 双指 1x~5x 缩放/拖动 +
 * 右上关闭按钮 + BackHandler。手势代码逐段对齐 [ChatScreen] 的 ChatImagePreviewOverlay
 * 单页形态（数据源由 uri 换 base64），不引入新交互；黑底/白图标沿用全屏预览惯例。
 */
@Composable
fun BrowserFramePreviewOverlay(
    frame: String,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    val bitmap = rememberBrowserFrameBitmap(frame)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
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
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.browser_live_view_full),
                modifier = Modifier
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
                    },
                contentScale = ContentScale.Fit
            )
        }

        // 关闭按钮（右上）
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(Spacing.lg)
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
