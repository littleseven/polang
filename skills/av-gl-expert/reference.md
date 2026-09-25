---
title: AV-GL Expert 参考文档
---

# AV-GL Expert 参考文档

本文件包含 av-gl-expert SKILL.md 中拆分的详细代码示例和参考实现。

---

## 1. Shader 调试代码参考

### 1.1 红色测试 Shader

经 `BeautyRenderer.MODE_DEBUG_RED`（`setDebugMode(MODE_DEBUG_RED)`）启用：

```glsl
// debug mode: MODE_DEBUG_RED（纯红输出，验证管线连通）
precision mediump float;
varying vec2 vTextureCoord;

void main() {
    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
}
```

### 1.2 纹理 R 通道灰度显示

```glsl
precision mediump float;
varying vec2 vTextureCoord;
uniform samplerExternalOES uTexture;

void main() {
    vec4 color = texture2D(uTexture, vTextureCoord);
    float gray = color.r;
    gl_FragColor = vec4(gray, gray, gray, 1.0);
}
```

### 1.3 UV 坐标可视化

```glsl
precision mediump float;
varying vec2 vTextureCoord;

void main() {
    gl_FragColor = vec4(vTextureCoord.x, vTextureCoord.y, 0.0, 1.0);
}
```

### 1.4 Uniform 值打印（Kotlin）

```kotlin
fun debugUniforms() {
    val smoothingLoc = shaderProgram.getUniformLocation("uSmoothing")
    val whiteningLoc = shaderProgram.getUniformLocation("uWhitening")
    
    if (smoothingLoc >= 0) {
        val values = FloatArray(1)
        GLES20.glGetUniformfv(shaderProgram.programId, smoothingLoc, values, 0)
        Log.d(TAG, "uSmoothing = ${values[0]}")
    }
    
    if (whiteningLoc >= 0) {
        val values = FloatArray(1)
        GLES20.glGetUniformfv(shaderProgram.programId, whiteningLoc, values, 0)
        Log.d(TAG, "uWhitening = ${values[0]}")
    }
}
```

---

## 2. 性能优化代码参考

### 2.1 FPS 统计

```kotlin
private var frameCount = 0L
private var lastFpsUpdateTime = 0L
private var currentFps = 0

fun updateFpsStats() {
    frameCount++
    val currentTime = System.currentTimeMillis()
    
    if (currentTime - lastFpsUpdateTime >= 1000) {
        currentFps = frameCount
        frameCount = 0
        lastFpsUpdateTime = currentTime
        
        Log.d(TAG, "FPS: $currentFps")
        latestPerfStats = latestPerfStats.copy(fps = currentFps)
    }
}
```

### 2.2 单帧渲染耗时

```kotlin
fun onDrawFrame() {
    val startTime = System.nanoTime()
    // ... 渲染逻辑 ...
    val endTime = System.nanoTime()
    val renderTimeMs = (endTime - startTime) / 1_000_000.0
    
    latestPerfStats = latestPerfStats.copy(
        renderTimeMs = renderTimeMs.toFloat()
    )
    
    if (renderTimeMs > 16.67) {
        Log.w(TAG, "渲染超时: ${renderTimeMs}ms (>16.67ms)")
    }
}
```

### 2.3 FBO 复用

```kotlin
private var fboId: Int = 0
private var fboWidth: Int = 0
private var fboHeight: Int = 0

fun ensureFBO(width: Int, height: Int) {
    if (fboId == 0 || fboWidth != width || fboHeight != height) {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
        }
        
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]
        
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTextureId = textures[0]
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 
                           width, height, 0, GLES20.GL_RGBA, 
                           GLES20.GL_UNSIGNED_BYTE, null)
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, 
                                     GLES20.GL_COLOR_ATTACHMENT0,
                                     GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        
        fboWidth = width
        fboHeight = height
        
        Log.d(TAG, "FBO 创建: ${width}x${height}")
    }
}
```

### 2.4 PBO 异步读取

```kotlin
private fun readPixelsWithPBO(width: Int, height: Int): Bitmap {
    val pixelCount = width * height
    val bufferSize = pixelCount * 4
    
    if (pboIds == null) {
        val pbos = IntArray(PBO_COUNT)
        GLES20.glGenBuffers(PBO_COUNT, pbos, 0)
        pboIds = pbos
        
        for (i in 0 until PBO_COUNT) {
            GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pbos[i])
            GLES20.glBufferData(GLES20.GL_PIXEL_PACK_BUFFER, bufferSize, 
                               null, GLES20.GL_DYNAMIC_READ)
        }
        GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, 0)
    }
    
    val readPboIndex = pboIndex
    val nextPboIndex = (pboIndex + 1) % PBO_COUNT
    
    GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pboIds!![readPboIndex])
    GLES20.glReadPixels(0, 0, width, height, 
                       GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
    
    GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, pboIds!![nextPboIndex])
    val buffer = GLES20.glMapBufferRange(
        GLES20.GL_PIXEL_PACK_BUFFER,
        0, bufferSize,
        GLES20.GL_MAP_READ_BIT
    ) as ByteBuffer
    
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    bitmap.copyPixelsFromBuffer(buffer)
    
    GLES20.glUnmapBuffer(GLES20.GL_PIXEL_PACK_BUFFER)
    GLES20.glBindBuffer(GLES20.GL_PIXEL_PACK_BUFFER, 0)
    
    pboIndex = nextPboIndex
    return bitmap
}
```

---

## 3. 坐标映射代码参考

### 3.1 归一化坐标

```kotlin
fun normalizeLandmark(
    x: Float, y: Float,
    imageWidth: Int, imageHeight: Int
): Pair<Float, Float> {
    val normX = x / imageWidth
    val normY = y / imageHeight
    return Pair(normX, normY)
}
```

### 3.2 旋转校正

```kotlin
fun rotateLandmark(
    normX: Float, normY: Float,
    rotationDegrees: Int,
    imageWidth: Int, imageHeight: Int
): Pair<Float, Float> {
    val rotatedWidth = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
    val rotatedHeight = if (rotationDegrees % 180 == 0) imageHeight else imageWidth
    
    return when (rotationDegrees) {
        90 -> Pair(normY, 1f - normX)
        180 -> Pair(1f - normX, 1f - normY)
        270 -> Pair(1f - normY, normX)
        else -> Pair(normX, normY)
    }
}
```

### 3.3 镜像翻转

```kotlin
fun mirrorLandmark(
    normX: Float, normY: Float,
    lensFacing: Int
): Pair<Float, Float> {
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }
    return Pair(mirroredX, normY)
}
```

### 3.4 屏幕坐标映射

```kotlin
fun toScreenCoordinate(
    normX: Float, normY: Float,
    previewWidth: Int, previewHeight: Int
): Offset {
    val screenX = normX * previewWidth
    val screenY = normY * previewHeight
    return Offset(screenX, screenY)
}
```

### 3.5 UV 坐标映射

```kotlin
fun toUVCoordinate(
    normX: Float, normY: Float,
    outputWidth: Int, outputHeight: Int,
    cameraInputWidth: Int, cameraInputHeight: Int,
    isFillCenter: Boolean
): Pair<Float, Float> {
    val rawSourceAspect = cameraInputWidth.toFloat() / cameraInputHeight
    val rotatedSourceAspect = if (isFillCenter) rawSourceAspect else 1f / rawSourceAspect
    val outputAspect = outputWidth.toFloat() / outputHeight
    
    val uvX: Float
    val uvY: Float
    
    if (isFillCenter) {
        if (rotatedSourceAspect > outputAspect) {
            val scale = outputAspect / rotatedSourceAspect
            uvX = normX
            uvY = (normY - 0.5f) / scale + 0.5f
        } else {
            val scale = rotatedSourceAspect / outputAspect
            uvX = (normX - 0.5f) / scale + 0.5f
            uvY = normY
        }
    } else {
        uvX = normX
        uvY = normY
    }
    
    return Pair(uvX.coerceIn(0f, 1f), uvY.coerceIn(0f, 1f))
}
```

---

## 4. 关键点调试可视化

```kotlin
@Composable
fun LandmarkDebugOverlay(
    landmarks: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        landmarks.forEachIndexed { index, (x, y) ->
            val color = when {
                index < 33 -> Color.Red
                index < 43 -> Color.Green
                index < 53 -> Color.Blue
                index < 61 -> Color.Yellow
                index < 69 -> Color.Cyan
                index < 77 -> Color.Magenta
                else -> Color.White
            }
            
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = Offset(x, y)
            )
            
            if (index % 10 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "$index", x + 5, y - 5, Paint().apply {
                        textSize = 20f
                        color = android.graphics.Color.WHITE
                    }
                )
            }
        }
    }
}
```

---

## 5. CameraX 集成代码参考

### 5.1 Preview Surface 绑定

```kotlin
fun initializeCamera() {
    beautyPreviewView.initialize()
    
    repeat(120) { attempt ->
        val surface = beautyPreviewView.getSurfaceForCamera()
        if (surface != null && surface.isValid) {
            Log.i(TAG, "Surface ready on attempt ${attempt + 1}")
            bindCamera(surface)
            return
        }
        Thread.sleep(30)
    }
    
    throw IllegalStateException("Surface not ready")
}
```

### 5.2 ImageAnalysis YUV 处理

```kotlin
imageAnalysis.setAnalyzer(Dispatchers.IO.asExecutor()) { imageProxy ->
    try {
        val mediaImage = imageProxy.image ?: return@setAnalyzer
        val landmarks = faceDetector.detect(mediaImage, rotationDegrees)
        if (landmarks != null) {
            beautyRenderer.updateFaceLandmarks(landmarks)
        }
        imageProxy.close()
    } catch (e: Exception) {
        Log.e(TAG, "YUV conversion error", e)
    } finally {
        imageProxy.close()
    }
}
```

---

## 6. 最佳实践代码参考

### 6.1 渲染线程管理

```kotlin
renderThread = Thread {
    while (isRendering) {
        try {
            renderFrame()
        } catch (e: Exception) {
            Log.e(TAG, "Render error", e)
        }
    }
}.apply {
    name = "CameraPreviewRender"
    priority = Thread.MAX_PRIORITY
    start()
}
```

### 6.2 GL 错误检查

```kotlin
fun checkGLError(operation: String) {
    var error: Int
    while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
        Log.e(TAG, "GL Error after $operation: 0x${error.toString(16)}")
    }
}
```

### 6.3 资源清理

```kotlin
fun release() {
    isRendering = false
    renderThread?.join(300)
    
    if (fboId != 0) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        fboId = 0
    }
    
    if (textureId != 0) {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
    }
    
    surfaceTexture?.release()
    surfaceTexture = null
    
    eglCore.release()
}
```
