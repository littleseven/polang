---
name: egl-state-machine
description: |
  EGL 上下文状态机管理专家。预防 AI 在操作 EGL 上下文、离屏渲染、拍照 GPU 化时陷入线程与状态泥潭。
version: 1.2.0
created: 2026-05-03
updated: 2026-08-03
maintainer: "[RD] 全栈工程师"
tags:
  - opengl
  - egl
  - gpu
  - rendering
  - threading
---


# EGL 状态机专家 (EGL State Machine Expert)

> **定位**：预防 AI 在操作 EGL 上下文、离屏渲染、拍照 GPU 化时陷入"线程与状态"泥潭。
> **来源**：项目 EGL 离屏渲染实践经验
> **触发时机**：修改 `PhotoProcessorImpl`、`EGLCore`、`CameraPreviewRenderer`、`BeautyRenderer` 时（均在 `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/`）

## 核心原则：三元绑定关系

```
EGLDisplay  --(连接)-->  物理显示/GPU
    |
    +-- EGLConfig  --(配置选择)-->  颜色深度/缓冲区
    |       |
    |       +-- EGLContext  --(状态容器)-->  Shader/Texture/FBO
    |               |
    |               +-- EGLSurface  --(绘制目标)-->  Window/Pbuffer
    |
    +-- 另一 EGLContext (共享上下文)
```

**红线**：任何 EGL 操作必须明确知道当前线程绑定的 `(Display, Context, Surface)` 三元组。

## 四大铁律

### 铁律 1：单线程创建与销毁
```kotlin
// ❌ 错误：在 A 线程创建，在 B 线程释放
// ✅ 正确：创建、makeCurrent、swapBuffers、释放 必须在同一线程
class PhotoProcessorImpl {
    private val processorThread = HandlerThread("PhotoProcessor").apply { start() }
    private val processorHandler = Handler(processorThread.looper)
    
    fun process(bitmap: Bitmap): Bitmap {
        val result = CountDownLatch(1)
        processorHandler.post {
            // EGL 操作全部在此线程
            eglCore.makeCurrent()
            // ... 渲染 ...
            eglCore.swapBuffers()
            result.countDown()
        }
        result.await()
    }
    
    fun release() {
        processorHandler.post {
            // 同一线程释放
            eglCore.release()
        }
        processorThread.quitSafely()
    }
}
```

### 铁律 2：makeCurrent / release 成对出现
```kotlin
// ❌ 错误：只 makeCurrent 不 release
try {
    eglCore.makeCurrent()
    render()
} catch (e: Exception) {
    Log.e(TAG, "Render failed")
}
// 未 release！后续线程可能拿到脏状态

// ✅ 正确：必须成对，即使在异常路径
val previousContext = EGL14.eglGetCurrentContext()
try {
    eglCore.makeCurrent()
    render()
} finally {
    eglCore.releaseCurrent()
    // 恢复之前线程的上下文（如预览上下文）
    if (previousContext != EGL14.EGL_NO_CONTEXT) {
        EGL14.eglMakeCurrent(display, drawSurface, readSurface, previousContext)
    }
}
```

### 铁律 3：共享上下文显式传递纹理 ID
```kotlin
// ❌ 错误：拍照上下文重新渲染预览内容
val bitmap = BitmapFactory.decodeFile(path)
val texture = uploadToTexture(bitmap)
renderToFbo(texture) // 重新执行全部 Shader

// ✅ 正确：预览与拍照共享上下文，直接传递已渲染的纹理
// 预览上下文创建时设置 shareContext
val previewContext = eglCore.createContext(EGL14.EGL_NO_CONTEXT)
val photoContext = eglCore.createContext(previewContext) // 共享！

// 拍照时直接使用预览管线的输出纹理
val previewOutputTexture = previewRenderer.getOutputTexture()
photoRenderer.render(previewOutputTexture) // 零拷贝
```

### 铁律 4：finally 块确保 FBO/Surface 解绑
```kotlin
// ✅ 正确：任何涉及 FBO 的操作必须包裹在 try-finally 中
try {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
    GLES20.glViewport(0, 0, width, height)
    shader.use()
    drawQuad()
} finally {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    GLES20.glUseProgram(0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
}
```

## 状态机日志标签

EGL 相关类日志 TAG 用类名（如 `EGLCore`），统一经 `core/common/Logger` 输出（自动加 `PoLang:` 前缀），格式：
```kotlin
Logger.d(TAG, "[makeCurrent] thread=${Thread.currentThread().name}, context=$context, surface=$surface")
Logger.d(TAG, "[release] thread=${Thread.currentThread().name}, context=$context")
Logger.d(TAG, "[createContext] shareContext=$shareContext, newContext=$newContext")
```

## 拍照 GPU 化检查清单

修改 `PhotoProcessorImpl` 时必须检查：
- [ ] 是否创建了独立 HandlerThread？（禁止在主线程或线程池中执行）
- [ ] EGL 上下文是否与预览上下文共享？
- [ ] `makeCurrent` 是否有对应的 `releaseCurrent`？
- [ ] FBO 操作是否在 `try-finally` 中？
- [ ] `glReadPixels` / PBO 读取后是否解绑了 FBO？
- [ ] 拍照完成后是否恢复了预览线程的 EGL 上下文？
- [ ] 失败时是否有降级到 CPU 路径的异常处理？

## 常见症状与根因对照

| 症状 | 根因 | 修复 |
|------|------|------|
| GPU 拍照黑屏 | `BeautyRenderer` 未在新上下文中完成 `onInit` | 确保 `onInit` 在 `PhotoProcessorImpl` 线程内调用 |
| EGL 绑定错误 | 在线程池中复用上下文 | 使用专用 HandlerThread |
| 预览/拍照效果不一致 | 两套独立 Shader 逻辑 | 复用同一 `BeautyRenderer`，仅切换输入纹理类型 |
| PBO 读取图像翻转 | `glReadPixels` 的坐标系与 Bitmap 不一致 | 使用 `Matrix` 翻转 Y 轴或调整 FBO attachment |
| 连续拍照内存泄漏 | FBO/Texture/PBO 未释放 | `finally` 块中确保全部释放 |

## 决策树：遇到 EGL 问题时

```
问题：渲染失败/黑屏/崩溃
    |
    +-- 检查日志中是否有 "EGLCore ... makeCurrent failed"?
    |       +-- YES -> 上下文已被其他线程绑定，检查线程隔离
    |       +-- NO  -> 继续
    |
    +-- 检查 Shader 编译日志 `glGetShaderInfoLog`
    |       +-- 有错误 -> 修复 Shader
    |       +-- 无错误 -> 继续
    |
    +-- 检查 `glCheckFramebufferStatus`
    |       +-- 非 COMPLETE -> FBO 配置错误
    |       +-- COMPLETE   -> 继续
    |
    +-- 检查 Uniform 是否正确传递
    |       +-- 使用 `BeautyRenderer.MODE_DEBUG_RED`（`setDebugMode`）验证管线
    |
    +-- 仍无法解决 -> 上报并请求人工介入
```

## 相关文件

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
| 1.2.0 | 2026-09-25 | 日志规范对齐现状（TAG=类名经 Logger，删虚构 `PoLang:EGL`）；`FRAGMENT_SHADER_DEBUG_RED` → `BeautyRenderer.MODE_DEBUG_RED`；触发类补模块路径 |
