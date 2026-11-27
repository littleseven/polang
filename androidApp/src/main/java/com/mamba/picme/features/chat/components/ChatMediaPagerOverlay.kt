package com.mamba.picme.features.chat.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.features.chat.ChatImagePreviewState
import com.mamba.picme.features.chat.ImagePreviewPage
import com.mamba.picme.features.chat.resolvePreviewUri
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.components.MediaPager
import java.io.File
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "PoLang:Chat"

/**
 * 占位 [MediaAsset] 的 id：Room `media_assets` 主键恒 ≥ 1，0 即「未入册」的
 * chat 私有图（用户发送图 / AI 编辑与优化结果，存 app 私有目录，不在 MediaStore）。
 * 宿主回调（删除/证件照/summary 等依赖 Room 行的动作）以此为守卫依据。
 */
const val CHAT_PLACEHOLDER_ASSET_ID = 0L

/**
 * 为未入册的 chat 私有图合成占位 [MediaAsset]（JVM 可测的字段推导）：
 * uri 归一化为 file://（Coil/分享需要带 scheme），日期取文件 mtime（取不到为 0）。
 */
fun placeholderMediaAsset(rawUri: String, nowMs: Long = System.currentTimeMillis()): MediaAsset {
    val resolved = resolvePreviewUri(rawUri)
    val file = File(resolved.path ?: rawUri)
    return MediaAsset(
        id = CHAT_PLACEHOLDER_ASSET_ID,
        uri = resolved.toString(),
        type = MediaType.PHOTO,
        captureDate = if (file.exists()) file.lastModified() else nowMs,
        fileName = file.name.ifBlank { rawUri.substringAfterLast('/') },
    )
}

/**
 * chat 私有图分享：复制到 `cacheDir/share/`（既有 FileProvider `${applicationId}.ota.provider`
 * 已覆盖该目录，同清理战报分享卡先例）后发 ACTION_SEND；失败静默记日志不穿透。
 */
fun shareChatLocalImage(context: Context, uriString: String) {
    runCatching {
        val source = File(Uri.parse(uriString).path ?: error("no path: $uriString"))
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(shareDir, source.name)
        if (!target.exists() || target.length() != source.length()) {
            source.copyTo(target, overwrite = true)
        }
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.ota.provider",
            target,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, contentUri)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, null))
    }.onFailure { error ->
        Logger.w(TAG, "shareChatLocalImage failed: ${error.message}")
    }
}

/**
 * chat 图片全屏预览（2026-11-27 统一）：复用相册 [MediaPager] 组件，与相册页预览
 * 同一页面——缩放/长图/上滑删除/信息面板/OCR/图像理解/证件照全量继承。
 *
 * 桥接职责（本组件唯一增量）：
 * - pages → assets：[assetsByUri]（Room uri 反查）命中用完整实体，未命中（chat 私有
 *   file 图）经 [placeholderMediaAsset] 合成占位（id = [CHAT_PLACEHOLDER_ASSET_ID]）；
 * - chat 专属语义外挂：编辑/优化结果页「保存到相册」按钮（叠于 MediaPager 底栏上方）、
 *   切页 [onPageChanged]（LRU 续期，经 onPageViewed 回传 uri 按列表序反定位页）。
 *
 * 宿主负责：删除授权拉起与 outcome 收缩、编辑/证件照导航、分享分发（占位图走
 * [shareChatLocalImage]）、对依赖 Room 行的回调按 `asset.id` 守卫。
 */
@Suppress("LongMethod", "LongParameterList") // 镜像 MediaPager 参数面 + chat 专属三参，适配器组件
@Composable
fun ChatMediaPagerOverlay(
    state: ChatImagePreviewState?,
    assetsByUri: Map<String, MediaAsset>,
    isTrashSupported: Boolean,
    ocrState: StateFlow<MediaViewModel.OcrResult?>,
    onSave: (messageId: String, onDone: (Boolean) -> Unit) -> Unit,
    onShare: (MediaAsset) -> Unit,
    onEdit: (MediaAsset) -> Unit,
    onAiOptimize: (MediaAsset) -> Unit,
    onIdPhoto: (MediaAsset) -> Unit,
    onDelete: (MediaAsset) -> Unit,
    onSwipeUpDelete: (MediaAsset) -> Unit,
    onStartOcr: (String) -> Unit,
    onDismissOcr: () -> Unit,
    onTriggerSummary: (Long) -> Unit,
    onPageViewed: (String) -> Unit,
    onReTag: suspend (Uri) -> String?,
    onDescribeImage: suspend (Uri) -> String?,
    onPageChanged: (ImagePreviewPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val expiredToast = stringResource(R.string.chat_edit_save_expired_failed)
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        if (state == null) return@AnimatedVisibility
        val pages = state.pages
        // pages → MediaAsset（保序 1:1）：Room 命中用完整实体，未命中合成占位
        val assets = remember(pages, assetsByUri) {
            pages.map { page -> assetsByUri[page.rawUri] ?: placeholderMediaAsset(page.rawUri) }
        }
        // 当前页下标：MediaPager 内部持有 pagerState，经 onPageViewed 回传 uri 按
        // 列表序反定位（assets 与 pages 1:1 同序）。初值仅在浮层重组（新打开）时生效，
        // onPageViewed 组合即回调会立即校正；保存/删除收缩不换 remember 键防跳页
        var currentIndex by remember { mutableStateOf(state.initialIndex) }
        val currentPage = pages.getOrNull(currentIndex)

        Box(modifier = Modifier.fillMaxSize()) {
            MediaPager(
                assets = assets,
                initialIndex = state.initialIndex.coerceIn(0, assets.lastIndex.coerceAtLeast(0)),
                onClose = onDismiss,
                onDelete = onDelete,
                onSwipeUpDelete = onSwipeUpDelete,
                isTrashSupported = isTrashSupported,
                onStartOcr = onStartOcr,
                onDismissOcr = onDismissOcr,
                ocrState = ocrState,
                onNavigateToEditor = onEdit,
                onAiOptimize = onAiOptimize,
                onIdPhoto = onIdPhoto,
                onReTag = onReTag,
                onDescribeImage = onDescribeImage,
                onTriggerSummary = onTriggerSummary,
                onPageViewed = { uri ->
                    val index = assets.indexOfFirst { asset -> asset.uri == uri }
                    if (index >= 0) {
                        currentIndex = index
                        pages.getOrNull(index)?.let(onPageChanged)
                    }
                    onPageViewed(uri)
                },
                onShare = onShare,
            )

            // chat 专属：编辑/优化结果「保存到相册」（MediaPager 无此动作，叠于底栏上方）
            if (currentPage != null && currentPage.isEditableResult) {
                val isSaved = currentPage.isSaved
                Button(
                    onClick = {
                        if (!isSaved) {
                            onSave(currentPage.messageId) { ok ->
                                if (!ok) {
                                    Toast.makeText(context, expiredToast, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = !isSaved,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 72.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (isSaved) {
                                R.string.chat_edit_saved_to_gallery
                            } else {
                                R.string.chat_edit_save_to_gallery
                            },
                        ),
                    )
                }
            }
        }
    }
}
