package com.mamba.picme.features.debug.openui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.mamba.picme.core.common.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 二维码扫码预览（debug 专用）：CameraX Preview + ImageAnalysis 逐帧 zxing 解码，
 * 命中一次即回调 [onResult] 并停解析（页面侧负责解绑/收起）。
 *
 * 选 zxing core 纯 JVM 解码而非 GMS Code Scanner——国行 HyperOS 测试机无 GMS 依赖可跑；
 * 相机权限由调用方（accompanist）申请后本组件才进入组合。
 */
@Composable
fun QrCodeScanner(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val decoded = remember { AtomicBoolean(false) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    try {
                        if (!decoded.get()) {
                            decodeQr(imageProxy)?.let { text ->
                                if (decoded.compareAndSet(false, true)) {
                                    Logger.d("QrScanner", "decoded len=${text.length}")
                                    onResult(text)
                                }
                            }
                        }
                    } finally {
                        imageProxy.close()
                    }
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (e: Exception) {
                    Logger.e("QrScanner", "bind failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = modifier,
    )
}

/** Y 平面灰度 → zxing 解码；rowStride padding 逐行拷贝归一。 */
private fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height
    val data = ByteArray(width * height)
    val row = ByteArray(rowStride)
    var offset = 0
    for (y in 0 until height) {
        buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
        System.arraycopy(row, 0, data, offset, width)
        offset += width
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    return runCatching {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }.decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
