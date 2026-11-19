package com.mamba.picme.features.chat.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.features.chat.ChatImagePreviewState
import com.mamba.picme.features.chat.ImagePreviewPage
import com.mamba.picme.features.chat.resolvePreviewUri

/**
 * 预览页动作可用性（显式枚举合法状态）：按当前页能否解析为图库媒体决定
 * 分享/删除入口显隐；编辑恒可用（PhotoEditor 支持 content/file 两种 uri）；
 * 保存沿用 [ImagePreviewPage.isEditableResult] 语义（AI 生成/编辑结果图）。
 */
data class ChatPreviewActions(
    val canShare: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val showSave: Boolean,
)

/** 纯函数：由当前页 + uri 反查结果推导底栏动作面（JVM 可测）。 */
fun resolveChatPreviewActions(page: ImagePreviewPage, asset: MediaAsset?): ChatPreviewActions =
    ChatPreviewActions(
        canShare = asset != null,
        canEdit = true,
        canDelete = asset != null,
        showSave = page.isEditableResult,
    )

/**
 * 图片全屏预览浮层（横滑翻页）：能力面对齐相册 MediaPager 三件套——
 * 底栏 分享/编辑/删除（删除走系统回收站，仅当前页可解析为图库媒体时可用）；
 * 编辑/优化结果页额外提供「保存到相册」（底栏上方居中）。
 *
 * 宿主负责：uri→MediaAsset 反查（[assetsByUri]）、删除授权拉起与 outcome 收缩、
 * 编辑导航；本组件不含业务编排。
 */
@Composable
fun ChatImagePreviewOverlay(
    state: ChatImagePreviewState?,
    assetsByUri: Map<String, MediaAsset>,
    onSave: (messageId: String, onDone: (Boolean) -> Unit) -> Unit,
    onShare: (MediaAsset) -> Unit,
    onEdit: (ImagePreviewPage) -> Unit,
    onDelete: (MediaAsset) -> Unit,
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
                // detectTapGestures 只消费单击（关闭），不消费拖动——
                // HorizontalPager 的横滑手势不再被 Box 的 clickable 拦截
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                    )
                },
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
                        // 2026-11-01 翻页修复：移除 detectTransformGestures（消费全部
                        // 指针事件导致 pager 无法翻页）。缩放功能暂停——翻页优先。
                        // 后续如需缩放：双击放大 + 仅在 scale>1 时消费 pan（不消费横滑起点）
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

            // 底部动作区：保存到相册（仅编辑/优化结果，底栏上方）+ 三件套底栏
            val currentPage = pages.getOrNull(pagerState.currentPage)
            val currentAsset = currentPage?.let { page -> assetsByUri[page.rawUri] }
            val actions = currentPage?.let { page -> resolveChatPreviewActions(page, currentAsset) }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                if (currentPage != null && actions?.showSave == true) {
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
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (isSaved) R.string.chat_edit_saved_to_gallery else R.string.chat_edit_save_to_gallery
                            )
                        )
                    }
                }
                if (actions != null && currentPage != null) {
                    chatPreviewBottomBar(
                        actions = actions,
                        onShare = { currentAsset?.let(onShare) },
                        onEdit = { onEdit(currentPage) },
                        onDelete = { currentAsset?.let(onDelete) },
                    )
                }
            }
        }
    }
}

/**
 * 预览底栏三件套（视觉对齐相册 MediaPager bottom bar：黑 85% 底 + icon+label 直排）：
 * 分享（Send）/ 编辑（AutoFixHigh）/ 删除（Delete）；按 [ChatPreviewActions] 显隐。
 */
@Composable
private fun chatPreviewBottomBar(
    actions: ChatPreviewActions,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (actions.canShare) {
                previewActionButton(
                    icon = Icons.AutoMirrored.Rounded.Send,
                    labelRes = R.string.send,
                    onClick = onShare
                )
            }
            if (actions.canEdit) {
                previewActionButton(
                    icon = Icons.Rounded.AutoFixHigh,
                    labelRes = R.string.edit,
                    onClick = onEdit
                )
            }
            if (actions.canDelete) {
                previewActionButton(
                    icon = Icons.Rounded.Delete,
                    labelRes = R.string.delete,
                    onClick = onDelete
                )
            }
        }
    }
}

/**
 * 底栏 icon+文本按钮（同 MediaPager pagerActionButton 形态）：裸 Column 直排不套
 * IconButton（圆形 Surface 会切长文案），defaultMinSize 保 48dp 触控目标。
 */
@Composable
private fun previewActionButton(
    icon: ImageVector,
    labelRes: Int,
    onClick: () -> Unit
) {
    val label = stringResource(labelRes)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = label }
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(22.dp)
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            softWrap = false
        )
    }
}
