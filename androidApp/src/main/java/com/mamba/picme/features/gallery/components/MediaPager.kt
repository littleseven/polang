package com.mamba.picme.features.gallery.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.net.URLEncoder
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.TagTranslator
import com.mamba.picme.features.gallery.MediaViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Gallery"

@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // 待重构：MediaPager 抽 PagerState holder
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPager(
    assets: List<MediaAsset>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (MediaAsset) -> Unit,
    onStartOcr: (String) -> Unit,
    onDismissOcr: () -> Unit,
    ocrState: StateFlow<MediaViewModel.OcrResult?>,
    onNavigateToEditor: (MediaAsset) -> Unit,
    onAiOptimize: (MediaAsset) -> Unit,
    onIdPhoto: (MediaAsset) -> Unit = {},
    onReTag: suspend (Uri) -> String? = { null },
    onDescribeImage: suspend (Uri) -> String? = { null },
    onTriggerSummary: (Long) -> Unit = {},
    onPageViewed: (String) -> Unit = {},
    onDetectLandmarks: suspend (String) -> MediaViewModel.FaceLandmarkResult? = { null },
    debugUiEnabled: Boolean = false
) {
    key(initialIndex) {
        val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { assets.size })
        var showInfo by remember { mutableStateOf(false) }
        var showLandmarkOverlay by remember { mutableStateOf(false) }
        var currentPageZoomed by remember { mutableStateOf(false) }
        var showBarsVisible by remember { mutableStateOf(true) }
        var visionResult by remember { mutableStateOf<String?>(null) }
        var isVisionLoading by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val currentAsset = assets.getOrNull(pagerState.currentPage)

        val landmarkImageUri = currentAsset?.uri.orEmpty()
        val landmarkEnabled = showLandmarkOverlay && currentAsset?.type == MediaType.PHOTO
        var landmarkState by remember { mutableStateOf(FaceLandmarkDetectionState.IDLE) }
        LaunchedEffect(landmarkImageUri, landmarkEnabled) {
            if (!landmarkEnabled) {
                landmarkState = FaceLandmarkDetectionState.IDLE
            } else {
                landmarkState = FaceLandmarkDetectionState(
                    imageWidth = 0,
                    imageHeight = 0,
                    points106 = null,
                    isLoading = true,
                    noFace = false,
                    errorMessage = null
                )
                landmarkState = when (val result = onDetectLandmarks(landmarkImageUri)) {
                    is MediaViewModel.FaceLandmarkResult.Success -> FaceLandmarkDetectionState(
                        imageWidth = result.imageWidth,
                        imageHeight = result.imageHeight,
                        points106 = result.points106,
                        isLoading = false,
                        noFace = false,
                        errorMessage = null
                    )
                    MediaViewModel.FaceLandmarkResult.NoFace -> FaceLandmarkDetectionState(
                        imageWidth = 0,
                        imageHeight = 0,
                        points106 = null,
                        isLoading = false,
                        noFace = true,
                        errorMessage = null
                    )
                    is MediaViewModel.FaceLandmarkResult.Error -> FaceLandmarkDetectionState(
                        imageWidth = 0,
                        imageHeight = 0,
                        points106 = null,
                        isLoading = false,
                        noFace = false,
                        errorMessage = result.message
                    )
                    null -> FaceLandmarkDetectionState.IDLE
                }
            }
        }

        LaunchedEffect(pagerState.currentPage) {
            currentPageZoomed = false
            if (currentAsset?.type != MediaType.PHOTO) {
                showLandmarkOverlay = false
            }
            currentAsset?.let { asset -> onPageViewed(asset.uri) }
            // 按需触发 summary：批量用 ML Kit（无 summary），此处单张 tagger（默认 Florence-2）生成并缓存
            currentAsset?.let { asset -> onTriggerSummary(asset.id) }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                userScrollEnabled = !currentPageZoomed
            ) { pageIndex ->
                val asset = assets[pageIndex]
                if (asset.type == MediaType.VIDEO) {
                    VideoPlayer(uri = asset.uri, isActive = pageIndex == pagerState.settledPage)
                } else {
                    ZoomableImage(
                        uri = asset.uri,
                        onClick = {
                            Log.d(TAG, "Toggle bars visibility via click")
                            showBarsVisible = !showBarsVisible
                        },
                        onLongClick = {
                            Log.d("Gallery", "Trigger photo editor via long press")
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNavigateToEditor(asset)
                        },
                        onZoomStateChanged = { scale ->
                            if (pageIndex == pagerState.currentPage) {
                                currentPageZoomed = scale > 1.02f
                            }
                        }
                    )
                }
            }

            // 格式化日期
            val dateText = remember(currentAsset) {
                currentAsset?.captureDate?.let {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    sdf.format(Date(it))
                } ?: ""
            }

            // 更多菜单回调（菜单已从底部栏移至顶部栏，提取为局部 val 供顶部栏引用）
            val onStartVisionClick: () -> Unit = {
                val asset = assets.getOrNull(pagerState.currentPage)
                if (asset?.type == MediaType.PHOTO) {
                    Log.d("Gallery", "Trigger image understanding for asset: ${asset.id}")
                    visionResult = null
                    isVisionLoading = true
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching { onDescribeImage(asset.uri.toUri()) }
                            .getOrNull()
                        visionResult = result ?: context.getString(R.string.vision_failed)
                        isVisionLoading = false
                    }
                }
            }
            val onToggleLandmarksClick: () -> Unit = { showLandmarkOverlay = !showLandmarkOverlay }
            val onStartOcrClick: () -> Unit = {
                val selectedAsset = assets.getOrNull(pagerState.currentPage)
                Log.d("Gallery", "Trigger OCR via toolbar button for asset: ${selectedAsset?.id}")
                selectedAsset?.let { onStartOcr(it.uri) }
            }

            // Top Controls with animated visibility
            AnimatedVisibility(
                visible = showBarsVisible && !currentPageZoomed,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                mediaPagerTopControls(
                    onClose = onClose,
                    dateText = dateText,
                    onToggleInfo = {
                        Log.d("Gallery", "Toggle info visibility via top bar")
                        showInfo = !showInfo
                    },
                    onStartVision = onStartVisionClick,
                    onToggleLandmarks = onToggleLandmarksClick,
                    onStartOcr = onStartOcrClick,
                    showLandmarkAction = debugUiEnabled && currentAsset?.type == MediaType.PHOTO
                )
            }

            // Bottom Bar with animated visibility
            if (currentAsset?.type == MediaType.PHOTO) {
                AnimatedVisibility(
                    visible = showBarsVisible && !currentPageZoomed,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    mediaPagerBottomBar(
                    onShare = {
                        val selectedAsset = assets.getOrNull(pagerState.currentPage)
                        Log.d("Gallery", "Share media from pager: ${selectedAsset?.id}")
                        selectedAsset?.let { asset ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_STREAM, asset.uri.toUri())
                                type = if (asset.type == MediaType.VIDEO) "video/*" else "image/*"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, null))
                        }
                    },
                    onStartEdit = {
                        val asset = assets.getOrNull(pagerState.currentPage)
                        if (asset != null && asset.type == MediaType.PHOTO) {
                            Log.d(TAG, "Navigate to photo editor")
                            onNavigateToEditor(asset)
                        }
                    },
                    onStartIdPhoto = {
                        val asset = assets.getOrNull(pagerState.currentPage)
                        if (asset != null && asset.type == MediaType.PHOTO) {
                            onIdPhoto(asset)
                        }
                    },
                    onDelete = {
                        val selectedAsset = assets.getOrNull(pagerState.currentPage)
                        if (selectedAsset != null) {
                            Log.d("Gallery", "Request delete media: ${selectedAsset.id}")
                            onDelete(selectedAsset)
                        }
                    }
                )
                }
            }

            if (showLandmarkOverlay && currentAsset?.type == MediaType.PHOTO) {
                FaceLandmarkCanvasOverlay(state = landmarkState)
                FaceLandmarkFeedback(state = landmarkState)
            }

            // Photo Info Dialog (取代旧的 SourceInfoOverlay)
            if (showInfo && currentAsset != null && !showLandmarkOverlay) {
                PhotoInfoDialog(
                    asset = currentAsset,
                    onDismiss = { showInfo = false },
                    onReTag = onReTag
                )
            }

            // OCR Result Overlay
            OcrResultOverlay(
                ocrState = ocrState,
                onDismiss = {
                    Log.d("Gallery", "Dismiss OCR result overlay")
                    onDismissOcr()
                }
            )

            // Vision (图像理解) Result Overlay
            VisionResultOverlay(
                result = visionResult,
                isLoading = isVisionLoading,
                onDismiss = {
                    visionResult = null
                    isVisionLoading = false
                }
            )
        }
    }
}

// ZoomableImage 已抽出至本包 ZoomableImage.kt（相册查看器与去重对比预览共享）

@Composable
private fun OcrResultOverlay(
    ocrState: StateFlow<MediaViewModel.OcrResult?>,
    onDismiss: () -> Unit
) {
    val result by ocrState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    AnimatedVisibility(
        visible = result != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onDismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .heightIn(max = 500.dp)
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* Stop propagation */ }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (val ocrResult = result) {
                        null -> {}
                        MediaViewModel.OcrResult.Loading -> {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.ocr_progress),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        is MediaViewModel.OcrResult.Success -> {
                            Log.d("Gallery", "OCR Result Displayed")
                            // Header with title, close button and actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.ocr_recognize),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.ocr_char_count, ocrResult.text.length),
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        maxLines = 1
                                    )
                                    IconButton(
                                        onClick = { onDismiss() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.close),
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Divider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )

                            val scrollState = rememberScrollState()
                            // Scrollable text content with constrained height
                            Text(
                                text = ocrResult.text,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 4.dp),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = Color.Black,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            // Action buttons at bottom
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        Log.d("Gallery", "OCR Copy text action")
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        clipboardManager.setText(AnnotatedString(ocrResult.text))
                                        Toast.makeText(context, context.getString(R.string.ocr_copy_success), Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.ocr_copy),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Button(
                                    onClick = {
                                        Log.d("Gallery", "OCR Share text action")
                                        val sendIntent: Intent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, ocrResult.text)
                                            type = "text/plain"
                                        }
                                        val shareIntent = Intent.createChooser(sendIntent, null)
                                        context.startActivity(shareIntent)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.ocr_share),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        is MediaViewModel.OcrResult.Error -> {
                            Log.e("Gallery", "OCR Result Error: ${ocrResult.message}")
                            Icon(
                                imageVector = Icons.Rounded.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = ocrResult.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisionResultOverlay(
    result: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AnimatedVisibility(
        visible = result != null || isLoading,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onDismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .heightIn(max = 500.dp)
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* Stop propagation */ }
                    )
            ) {
                if (isLoading) {
                    // Loading 状态使用紧凑居中布局，避免在大容器中内容偏上
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 48.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.vision_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else if (result != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.vision_result_title),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = { onDismiss() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.close),
                                    tint = Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        val scrollState = rememberScrollState()
                        MarkdownText(
                            markdown = result,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 4.dp),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = Color.Black,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(result))
                                    Toast.makeText(context, context.getString(R.string.ocr_copied), Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.ocr_copy),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Button(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, result)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, null))
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.ocr_share),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod", "LongParameterList") // 待重构：顶部控制栏抽子组件
@Composable
private fun mediaPagerTopControls(
    onClose: () -> Unit,
    dateText: String,
    onToggleInfo: () -> Unit,
    onStartVision: () -> Unit,
    onToggleLandmarks: () -> Unit,
    onStartOcr: () -> Unit,
    showLandmarkAction: Boolean,
    modifier: Modifier = Modifier
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Back + Date
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = {
                        Log.d("Gallery", "Close MediaPager")
                        onClose()
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White
                    )
                }

                if (dateText.isNotEmpty()) {
                    Text(
                        text = dateText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Right: Info + 更多（图像理解/OCR/人脸关键点）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onToggleInfo,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent
                    )
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.image_info),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Transparent
                        )
                    ) {
                        Icon(
                            Icons.Rounded.MoreHoriz,
                            contentDescription = stringResource(R.string.more),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.95f))
                    ) {
                        // 图像理解
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(stringResource(R.string.image_understand), color = Color.White, fontSize = 14.sp)
                                }
                            },
                            onClick = {
                                showMoreMenu = false
                                onStartVision()
                            }
                        )
                        // OCR 文字识别
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.TextSnippet,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(stringResource(R.string.ocr_text_recognition), color = Color.White, fontSize = 14.sp)
                                }
                            },
                            onClick = {
                                showMoreMenu = false
                                onStartOcr()
                            }
                        )
                        // 人脸关键点
                        if (showLandmarkAction) {
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(
                                            Icons.Rounded.Face,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(stringResource(R.string.landmark_overlay), color = Color.White, fontSize = 14.sp)
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onToggleLandmarks()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod") // 待重构：底部操作栏抽子组件
@Composable
private fun mediaPagerBottomBar(
    onShare: () -> Unit,
    onStartEdit: () -> Unit,
    onStartIdPhoto: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {

    Surface(
        modifier = modifier.fillMaxWidth(),
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
            pagerActionButton(
                icon = Icons.AutoMirrored.Rounded.Send,
                labelRes = R.string.send,
                onClick = onShare
            )
            pagerActionButton(
                icon = Icons.Rounded.AutoFixHigh,
                labelRes = R.string.edit,
                onClick = onStartEdit
            )
            pagerActionButton(
                icon = Icons.Rounded.Badge,
                labelRes = R.string.id_photo_action,
                onClick = onStartIdPhoto
            )
            pagerActionButton(
                icon = Icons.Rounded.Delete,
                labelRes = R.string.delete,
                onClick = onDelete
            )
        }
    }
}

/**
 * 预览页底栏 icon+文本按钮（gallery-grid.yaml §18 bottom_bar，Ardot gallery/viewer-bottombar-preview）。
 *
 * 无容器形状：M3 [IconButton] 的圆形 Surface 会把长文案两端切弧（EN "ID Photo" 的
 * 字尾被圆弧吃掉，2026-08-23 真机实证）——改为裸 Column 直排，宽度 hug 文本、
 * [Modifier.defaultMinSize] 保 48dp 触控目标，任何语言不裁字。
 */
@Composable
private fun pagerActionButton(
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

@Suppress("LongMethod", "CyclomaticComplexMethod") // 待重构：照片信息弹窗抽字段渲染器
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoInfoDialog(
    asset: MediaAsset,
    onDismiss: () -> Unit,
    onReTag: suspend (Uri) -> String?
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    var infoBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 解析标签（按当前界面语言翻译）
    val locale = configuration.locales[0]
    val appLanguage = remember(locale) { locale.toAppLanguage() }
    val tagTranslator = remember(context) { TagTranslator(BilingualVocab.loadFromAssets(context)) }
    var tags by remember(asset.id) {
        mutableStateOf(
            parseTagsGrouped(
                labels = asset.labels,
                translator = tagTranslator,
                lang = appLanguage,
                scenePrefix = context.getString(R.string.tag_scene_prefix),
                activityPrefix = context.getString(R.string.tag_activity_prefix),
                summaryPrefix = context.getString(R.string.tag_summary_prefix)
            )
        )
    }

    // 加载图片缩略图（用于信息弹窗预览）
    LaunchedEffect(asset.uri) {
        if (asset.type != MediaType.PHOTO) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = context.contentResolver.openInputStream(asset.uri.toUri())?.use {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 4
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeStream(it, null, opts)
                }
                infoBitmap = bitmap
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load bitmap for info: ${e.message}")
            }
        }
    }

    // 格式化拍摄日期
    val unknownDate = stringResource(R.string.unknown)
    val dateStr = remember(asset.captureDate, unknownDate) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.format(Date(asset.captureDate))
        } catch (e: Exception) { unknownDate }
    }

    // 人物分组名（media_assets.faceId 存 personId 字符串；已命名分组显示「名（ID: x）」，未命名仅 ID）
    var personGroupName by remember(asset.faceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(asset.faceId) {
        val personId = asset.faceId?.toLongOrNull() ?: return@LaunchedEffect
        personGroupName = withContext(Dispatchers.IO) {
            runCatching {
                AppDatabase.getDatabase(context).personDao().getPerson(personId)?.name
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onDismiss() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
            modifier = Modifier
                .widthIn(max = 400.dp)
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* stop propagation */ }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.image_info),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = {
                                Toast.makeText(context, context.getString(R.string.retag_in_progress), Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val resultJson = onReTag(asset.uri.toUri())
                                    if (resultJson != null) {
                                        tags = parseTagsGrouped(
                                            labels = resultJson,
                                            translator = tagTranslator,
                                            lang = appLanguage,
                                            scenePrefix = context.getString(R.string.tag_scene_prefix),
                                            activityPrefix = context.getString(R.string.tag_activity_prefix),
                                            summaryPrefix = context.getString(R.string.tag_summary_prefix)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.regenerate_tag),
                                tint = Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 图片预览（带人脸框）
                if (infoBitmap != null) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            Image(
                                bitmap = infoBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 基本信息
                InfoRow(stringResource(R.string.media_info_file_name), asset.fileName)
                InfoRow(
                    stringResource(R.string.media_info_type),
                    stringResource(
                        if (asset.type == MediaType.PHOTO) {
                            R.string.media_type_photo
                        } else {
                            R.string.media_type_video
                        }
                    )
                )
                InfoRow(stringResource(R.string.media_info_capture_date), dateStr)
                if (asset.duration != null && asset.duration!! > 0) {
                    InfoRow(stringResource(R.string.media_info_duration), stringResource(R.string.media_info_duration_seconds, asset.duration!! / 1000))
                }
                if (asset.source != null) {
                    InfoRow(stringResource(R.string.media_info_source), asset.source!!.replaceFirstChar { it.uppercase() })
                }
                val locName = asset.locationName
                if (!locName.isNullOrBlank()) {
                    LocationInfoRow(
                        label = stringResource(R.string.media_info_location),
                        locationName = locName,
                        lat = asset.latitude,
                        lon = asset.longitude
                    )
                }
                asset.aestheticScore?.let { score ->
                    InfoRow(stringResource(R.string.media_info_aesthetic_score), "%.1f / 10".format(score))
                }

                // 人脸信息
                if (asset.hasFace) {
                    Divider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = Color.White.copy(alpha = 0.1f)
                    )
                    Text(
                        text = stringResource(R.string.media_info_face_section),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    InfoRow(stringResource(R.string.media_info_contains_face), stringResource(R.string.yes))
                    if (asset.faceId != null) {
                        val groupLabel = personGroupName?.takeIf { it.isNotBlank() }
                            ?.let { "$it（ID: ${asset.faceId}）" }
                            ?: "ID: ${asset.faceId}"
                        InfoRow(stringResource(R.string.media_info_person_group), groupLabel)
                    }
                    asset.faceQualityScore?.let { score ->
                        InfoRow(stringResource(R.string.media_info_face_quality), "%.0f%%".format(score * 100))
                    }
                }

                // 标签
                if (tags.totalCount > 0) {
                    Divider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = Color.White.copy(alpha = 0.1f)
                    )
                    Text(
                        text = stringResource(R.string.tag_label_title, tags.totalCount),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                    // 摘要(第一行)
                    if (tags.summary.isNotBlank()) {
                        Text(
                            text = tags.summary,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        )
                    }
                    // 场景 / 活动 / 对象(第二行起)
                    val metaTags = buildList {
                        if (tags.scene.isNotBlank()) add(tags.scene)
                        if (tags.activity.isNotBlank()) add(tags.activity)
                        addAll(tags.objects)
                    }
                    if (metaTags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            metaTags.forEach { tag ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    // tags(新起一行,放最后)
                    if (tags.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tags.tags.forEach { tag ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // OCR 文本
                if (!asset.ocrText.isNullOrBlank()) {
                    Divider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = Color.White.copy(alpha = 0.1f)
                    )
                    Text(
                        text = stringResource(R.string.media_info_ocr_text),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    Text(
                        text = asset.ocrText!!.take(200),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 解析 labels JSON 为人可读的标签列表，并按当前语言翻译 */
private fun Locale.toAppLanguage(): AppLanguage = when (this.language) {
    "en" -> AppLanguage.ENGLISH
    "zh" -> if (this.country == "TW" || this.country == "HK") AppLanguage.TRADITIONAL_CHINESE else AppLanguage.CHINESE
    "es" -> AppLanguage.SPANISH
    "fr" -> AppLanguage.FRENCH
    else -> AppLanguage.CHINESE
}


/** 照片信息弹窗用的分组标签(按字段分行渲染)。 */
private data class ParsedTags(
    val summary: String = "",
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList()
) {
    val totalCount: Int
        get() = (if (summary.isNotBlank()) 1 else 0) +
            (if (scene.isNotBlank()) 1 else 0) +
            (if (activity.isNotBlank()) 1 else 0) +
            objects.size + tags.size
}

/**
 * 解析 labels(JSON Object 或 Array)为分组标签,按当前语言翻译。
 *
 * Object(Pass3):summary / scene / activity / objects / tags 各字段;
 * Array(旧):全部归入 tags。供 PhotoInfoDialog 分行渲染。
 */
private fun parseTagsGrouped(
    labels: String?,
    translator: TagTranslator,
    lang: AppLanguage,
    scenePrefix: String,
    activityPrefix: String,
    summaryPrefix: String
): ParsedTags {
    if (labels.isNullOrBlank()) return ParsedTags()
    return try {
        val trimmed = labels.trim()
        when {
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                ParsedTags(
                    summary = obj.optString("summary").takeIf { it.isNotBlank() }
                        ?.let { summaryPrefix.format(translator.display(it, lang)) }.orEmpty(),
                    scene = obj.optString("scene").takeIf { it.isNotBlank() }
                        ?.let { scenePrefix.format(translator.display(it, lang)) }.orEmpty(),
                    activity = obj.optString("activity").takeIf { it.isNotBlank() }
                        ?.let { activityPrefix.format(translator.display(it, lang)) }.orEmpty(),
                    objects = obj.optJSONArray("objects")
                        ?.let { arr -> (0 until arr.length()).map { translator.display(arr.getString(it), lang) } }
                        ?: emptyList(),
                    tags = obj.optJSONArray("tags")
                        ?.let { arr -> (0 until arr.length()).map { translator.display(arr.getString(it), lang) } }
                        ?: emptyList()
                )
            }
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                ParsedTags(tags = (0 until arr.length()).map { translator.display(arr.getString(it), lang) })
            }
            else -> ParsedTags()
        }
    } catch (e: Exception) {
        ParsedTags()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = Color.White,
            modifier = Modifier.weight(0.65f)
        )
    }
}

/** 构造 geo: intent URI，label 用 UTF-8 百分号编码（纯 JVM，便于单测）。 */
fun buildGeoUri(lat: Double, lon: Double, label: String): String {
    val encoded = URLEncoder.encode(label, "UTF-8")
    return "geo:$lat,$lon?q=$lat,$lon($encoded)"
}

/** 位置信息行：展示地名，有坐标时可点击跳用户自装的地图 App。 */
@Composable
private fun LocationInfoRow(label: String, locationName: String, lat: Double?, lon: Double?) {
    val context = LocalContext.current
    val canOpenMap = lat != null && lon != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(
                if (canOpenMap) Modifier.clickable { openMapApp(context, lat!!, lon!!, locationName) }
                else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(0.35f))
        Text(
            text = if (canOpenMap) "$locationName  ›" else locationName,
            fontSize = 13.sp,
            color = Color.White,
            modifier = Modifier.weight(0.65f)
        )
    }
}

private fun openMapApp(context: Context, lat: Double, lon: Double, label: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildGeoUri(lat, lon, label)))
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.no_map_app), Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun VideoPlayer(uri: String, isActive: Boolean) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = isActive
        }
    }

    LaunchedEffect(isActive) {
        exoPlayer.playWhenReady = isActive
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
