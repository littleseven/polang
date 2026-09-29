package com.mamba.picme.features.gallery.components

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.request.ImageRequest
import kotlinx.coroutines.flow.collectLatest
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

private const val TAG = "PoLang:ZoomableImage"

/**
 * 长图判定阈值：高/宽 >= 2.5 视为长图（长截屏、文字长图），对齐系统相册「长图」口径。
 */
private const val LONG_IMAGE_ASPECT_RATIO = 2.5f

/** 普通图缩放上限（相对原图像素，Telephoto ZoomSpec 语义）。 */
private const val NORMAL_IMAGE_MAX_ZOOM = 4f

/** 长图缩放上限：FillWidth 基线下文字已可读，再放大 3x 看细节足够。 */
private const val LONG_IMAGE_MAX_ZOOM = 3f

/**
 * 纯函数：判定是否长图（高/宽 >= [LONG_IMAGE_ASPECT_RATIO]）。JVM 可测。
 */
fun isLongImage(width: Int, height: Int): Boolean =
    width > 0 && height.toFloat() / width >= LONG_IMAGE_ASPECT_RATIO

/**
 * 可缩放/平移的全屏图片，底座为 Telephoto ZoomableAsyncImage（区域解码 SubsamplingImage，
 * 长截屏放大后文字依然清晰；拖拽钳制以内容边界为准）。
 *
 * 长图模式（[isLongImage] 命中）：FillWidth 填充宽度 + 顶部对齐，单指竖直拖动即滚动阅读
 * （小米/华为相册长图形态），双指在 FillWidth 基线上继续放大，双击在最小/最大缩放间循环。
 *
 * 契约：
 * - [onZoomStateChanged] 回传缩放值，> 1f 表示处于放大态（调用方据此禁用外层 Pager 翻页）；
 *   长图基线（未放大）时值恒为 1f，竖直阅读滚动不影响翻页判定。
 * - [onLongImageDetected] 在图片尺寸到达后回传是否长图（调用方据此禁用上滑删除手势）。
 * - 点击/长按必须走 ZoomableAsyncImage 的参数（其手势层消费所有点按做双击检测，
 *   外层 Modifier.clickable/combinedClickable 收不到事件）。
 */
@Composable
fun ZoomableImage(
    uri: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onZoomStateChanged: (Float) -> Unit,
    onLongImageDetected: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var imageSize by remember(uri) { mutableStateOf(IntSize.Zero) }
    val longImage = isLongImage(imageSize.width, imageSize.height)

    // ZoomSpec 依图片形态决定；rememberZoomableState 每次重组都把 zoomSpec 写回 state
    // （dynamicZoomSpec 动态生效），尺寸到达后上限切换无感知。
    val zoomableState = rememberZoomableState(
        ZoomSpec(maxZoomFactor = if (longImage) LONG_IMAGE_MAX_ZOOM else NORMAL_IMAGE_MAX_ZOOM)
    )
    val imageState = rememberZoomableImageState(zoomableState)

    // 尺寸到达后回传长图形态；contentScale/alignment 由 ZoomableAsyncImage 参数驱动，
    // 其内部直接写入 zoomableState（0.19.0 无 setContentScale/setContentAlignment 方法）。
    LaunchedEffect(longImage) {
        onLongImageDetected(longImage)
    }

    // 缩放状态回传：zoomFraction ∈ [0,1]（null=未布局），映射为 1f + fraction，
    // 保持调用方「> 1f 即放大态」的旧契约。
    LaunchedEffect(uri) {
        snapshotFlow { zoomableState.zoomFraction }
            .collectLatest { fraction ->
                onZoomStateChanged(1f + (fraction ?: 0f))
            }
    }

    // 自定义 ImageRequest：挂 listener 探测原始宽高比（解码等比降采样，比例不失真）；
    // 显式给定屏幕尺寸——Telephoto 执行请求时优先用请求自带 sizeResolver
    // （request.defined.sizeResolver ?: canvasSize），且其 newBuilder 全字段拷贝保留 listener。
    val request = remember(uri) {
        val displayMetrics = context.resources.displayMetrics
        ImageRequest.Builder(context)
            .data(uri)
            .size(displayMetrics.widthPixels, displayMetrics.heightPixels)
            .listener(
                onSuccess = { _, result ->
                    val drawable = result.drawable
                    imageSize = IntSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
                },
                onError = { _, result ->
                    Log.w(TAG, "onError uri=$uri", result.throwable)
                },
            )
            .build()
    }

    ZoomableAsyncImage(
        model = request,
        contentDescription = null,
        // fillMaxSize 必需：SubSamplingImage 内部按 imageOrPreviewSize 自wrap尺寸，
        // 不撑满会把画布量成预览图大小（1:1 左上角绘制）；初始 size 未知时为 0
        // 还会导致 Telephoto canvasSize.first() 永不发射、请求挂死（黑屏）。
        modifier = modifier.fillMaxSize(),
        state = imageState,
        // 长图：填充宽度 + 顶部对齐（竖直拖动即阅读滚动）；普通图：Fit 居中。
        contentScale = if (longImage) ContentScale.FillWidth else ContentScale.Fit,
        alignment = if (longImage) Alignment.TopCenter else Alignment.Center,
        onClick = { onClick() },
        onLongClick = { onLongClick() },
    )
}
