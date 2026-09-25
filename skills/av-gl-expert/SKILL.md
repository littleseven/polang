---
name: av-gl-expert
description: |
  PoLang OpenGL/CameraX 专家。诊断黑屏、Shader 错误、EGL 上下文及性能瓶颈。
version: 1.2.0
created: 2026-05-03
updated: 2026-08-03
maintainer: "[RD] 全栈工程师 + [CR] 规范守护者"
tags:
  - opengl
  - camerax
  - egl
  - shader
  - gpu
  - rendering
---


# AV-GL Expert (PoLang)

> **定位**：PoLang OpenGL/CameraX 专家，诊断黑屏、Shader 错误、EGL 上下文及性能瓶颈。
> **触发时机**：用户报告黑屏、渲染异常、Shader 编译失败或 CameraX 相关问题时自动启用。


## 🚀 PoLang CLI 快速执行指南

### 核心指令集
| 触发词 | 动作 |
|--------|------|
| `diagnose-black-screen` | 检查 EGL 上下文、Shader 编译、FBO 状态、Viewport |
| `debug-shader` | 启用红色测试 Shader、UV 可视化、Uniform 打印 |
| `profile-performance` | 统计 FPS、渲染耗时、空帧率、PBO 异步读取验证 |
| `debug-camerax` | 检查 Surface 绑定、YUV 流、前后置切换逻辑 |
| `debug-landmarks` | 验证 468→106 映射、旋转校正、镜像翻转、Viewport 映射 |

### 📉 Token 优化原则
- **记忆优先**：处理坐标问题时，先检索 `expert_experience` 中的坐标系规范。
- **引用替代**：严禁粘贴超过 50 行的 Shader 或 Kotlin 代码，使用 `[file](file:///path)` 引用。
- **文档导向**：复杂架构（如多 Pass 渲染）请直接查阅 `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`。

---

## 🛠️ 核心诊断流程 (Linear Execution)

### 1. 黑屏排查 (Black Screen)
1. **EGL 检查**：确认 `eglContext`、`surfaceTexture`、`textureId` 非空且有效。
2. **Shader 检查**：调用 `glGetShaderInfoLog` 确认编译成功；检查 `samplerExternalOES` 类型匹配。
3. **FBO 检查**：调用 `glCheckFramebufferStatus` 确保 `GL_FRAMEBUFFER_COMPLETE`。
4. **Viewport 检查**：确认 `glViewport` 尺寸与屏幕一致，非 `[0,0,0,0]`。

### 2. 性能优化 (Performance)
- **FPS 目标**：≥ 55fps。若低于此值，检查 FBO 复用情况及 PBO 异步读取。
- **耗时分解**：单帧渲染应 < 16.67ms。重点关注 Shader 复杂度与纹理上传。
- **资源管理**：确保纹理/FBO 在 `onDestroy` 时通过 `glDeleteTextures` 释放。

### 3. 坐标映射 (Coordinate Mapping)
- **分层标准**：UI 层可用 [人脸坐标系]，渲染层**必须**使用 [图像坐标系]。
- **转换链路**：MediaPipe 468 → 106（FaceLandmarkAdapter）→ 旋转校正 → 镜像翻转 → Viewport 映射。
- **常见陷阱**：前置摄像头下，图像左侧对应被拍摄者右脸（镜像效应）。

---

## 📚 专项技术文档索引
- **渲染管线**：`docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`
- **相机集成**：`docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`（相机预览内容已并入，§9）
- **离屏拍照**：`docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md`
- **坐标系规范**：`docs/07-STANDARDS/COORDINATE_SYSTEM.md`

---

## 🔧 核心功能

### 1. OpenGL 黑屏诊断 (Black Screen Diagnosis)

**触发命令**：`diagnose-black-screen` 或 `排查黑屏`

**常见原因及排查步骤**：

#### Step 1: 检查 EGL 上下文状态

```kotlin
// 在 CameraPreviewRenderer.kt 中添加诊断日志
Log.d(TAG, "EGL Context Status:")
Log.d(TAG, "  - eglContext: ${eglContext != null}")
Log.d(TAG, "  - surfaceTexture: ${surfaceTexture != null}")
Log.d(TAG, "  - textureId: $textureId")
Log.d(TAG, "  - isRendering: $isRendering")

val currentContext = EGL14.eglGetCurrentContext()
Log.d(TAG, "  - Current EGL Context: ${currentContext != EGL14.EGL_NO_CONTEXT}")
```

**预期输出**：全部 true。任一 false 说明对应组件初始化失败。

#### Step 2: 检查 Shader 编译状态

使用 `glGetShaderiv(GL_COMPILE_STATUS)` 和 `glGetShaderInfoLog()` 检查编译结果。

**常见 Shader 编译错误**：
- Uniform 声明但未使用 → `glGetUniformLocation` 返回 -1
- 精度限定符缺失（ES 2.0）→ 应使用 `mediump float`
- 纹理采样器类型不匹配 → 外部纹理应使用 `samplerExternalOES`

#### Step 3: 检查纹理绑定

验证 `glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)` 是否成功，检查 `glGetError()` 返回值。

#### Step 4: 检查 FBO 状态

```kotlin
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) { /* 处理错误 */ }
```

#### Step 5: 检查 Viewport 设置

```kotlin
GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
Log.d(TAG, "Viewport: [${viewportArray[0]}, ${viewportArray[1]}, ${viewportArray[2]}x${viewportArray[3]}]")
```

完整代码示例见 [reference.md](reference.md) §1-§2。

---

### 2. Shader 调试工具集 (Shader Debug Toolkit)

**触发命令**：`debug-shader` 或 `调试 Shader`

#### 工具 1: 红色测试 Shader

纯红色输出，验证渲染链路是否通畅。

#### 工具 2: 纹理 R 通道灰度显示

仅显示 R 通道，验证纹理是否正确加载。

#### 工具 3: UV 坐标可视化

R=U, G=V，验证纹理坐标映射。预期：左上黑(0,0)，右下黄(1,1)。

#### 工具 4: Uniform 值打印

通过 `glGetUniformfv` 读取 Uniform 实际值，验证参数传递。

完整 Shader 代码和 Kotlin 实现见 [reference.md](reference.md) §1。

---

### 3. 性能分析与优化 (Performance Profiling)

**触发命令**：`profile-performance` 或 `性能分析`

#### 指标 1: 渲染 FPS 监控

每秒统计帧数，目标 ≥ 55fps。

**性能标准**：
- ✅ **优秀**: ≥ 55 fps
- ⚠️ **合格**: 45-54 fps
- ❌ **不合格**: < 45 fps

#### 指标 2: 单帧渲染耗时

使用 `System.nanoTime()` 测量 `onDrawFrame()` 耗时，目标 < 16.67ms。

#### 指标 3: 空帧计数

统计 SurfaceTexture 无新帧的情况，空帧率应 < 5%。

#### 优化技巧 1: FBO 复用

禁止每帧创建/销毁 FBO。初始化时创建，尺寸变化时重建。

#### 优化技巧 2: PBO 异步读取

使用双缓冲 PBO 实现异步像素读取，将 `glReadPixels` 从 ~50ms 降至 ~15ms。

完整实现代码见 [reference.md](reference.md) §2。

---

### 4. CameraX 集成调试 (CameraX Integration Debug)

**触发命令**：`debug-camerax` 或 `调试 CameraX`

#### 问题 1: Preview Surface 绑定失败

**症状**：`Surface not ready after 120 attempts`

**根因**：Renderer 未在正确线程初始化，或 Surface 尚未就绪就尝试绑定。

**修复**：确保 `CameraPreviewRenderer` 在渲染线程初始化（`BeautyPreviewView.ensureRendererInitialized()` / `bindDisplaySurface()`），等待 Surface valid 后再绑定。

#### 问题 2: ImageAnalysis YUV 数据流中断

**症状**：`ImageProxy closed before processing`

**修复**：立即提取 `mediaImage`，在 `finally` 中确保 `imageProxy.close()`。

#### 问题 3: 前后置摄像头切换卡顿

**优化**：预初始化双摄像头 Surface，切换时直接复用。

完整代码示例见 [reference.md](reference.md) §5。

---

### 5. 人脸关键点坐标映射 (Landmark Coordinate Mapping)

**触发命令**：`debug-landmarks` 或 `调试人脸关键点`

#### 坐标系转换流程

**重要说明**：所有"左/右"描述均基于**图像坐标系**（观察者视角），详见 [COORDINATE_SYSTEM_STANDARD.md](docs/07-STANDARDS/COORDINATE_SYSTEM.md)。

```
MediaPipe 468 点 → 468→106 语义映射 → 旋转校正 → 归一化 → 镜像翻转 → Viewport 映射 → UV 映射
```

**关键理解**：
- **图像左侧** = 观察者看到的左边 = x 坐标较小的一侧
- 前置摄像头镜像后：图像左侧对应被拍摄者的右脸

#### 关键函数解析

| Stage | 函数 | 说明 |
|-------|------|------|
| 1 | `normalizeLandmark()` | 图像坐标 → [0,1] 归一化 |
| 2 | `rotateLandmark()` | 根据 rotationDegrees 旋转校正 |
| 3 | `mirrorLandmark()` | 前置摄像头 x 轴翻转 |
| 4 | `toScreenCoordinate()` | 归一化 → 屏幕像素坐标 |
| 5 | `toUVCoordinate()` | 考虑 Viewport 和画幅比例的 UV 映射 |

#### 调试可视化

使用 Compose Canvas 绘制 106 个关键点，按区域着色（轮廓红、眉毛绿/蓝、眼睛黄/青、鼻子紫、嘴巴白）。

完整代码实现见 [reference.md](reference.md) §3-§4。

---

## 📋 执行检查清单

### OpenGL 渲染问题排查清单

- [ ] EGL 上下文是否成功创建？
- [ ] SurfaceTexture 是否有效？
- [ ] 纹理 ID 是否非零？
- [ ] Shader 编译是否成功？（检查 `glGetShaderInfoLog`）
- [ ] Shader 链接是否成功？（检查 `glGetProgramInfoLog`）
- [ ] Uniform 位置是否有效？（`>= 0`）
- [ ] 纹理是否正确绑定？（`GL_TEXTURE_EXTERNAL_OES`）
- [ ] FBO 是否完整？（`glCheckFramebufferStatus`）
- [ ] Viewport 是否正确设置？
- [ ] 是否有 `glGetError()` 报错？

### 性能优化检查清单

- [ ] FBO 是否复用（避免每帧创建/销毁）？
- [ ] 是否使用 PBO 异步读取（拍照路径）？
- [ ] 纹理是否及时释放（`glDeleteTextures`）？
- [ ] Shader 是否预编译（避免运行时编译）？
- [ ] Uniform 更新是否批量进行？
- [ ] 渲染线程优先级是否设置为 `MAX_PRIORITY`？
- [ ] 是否避免在渲染线程执行耗时操作（文件 IO、网络请求）？
- [ ] FPS 是否稳定在 55+？
- [ ] 单帧渲染耗时是否 < 16.67ms？
- [ ] 空帧率是否 < 5%？

### CameraX 集成检查清单

- [ ] Preview Surface 是否在渲染线程初始化？
- [ ] ImageAnalysis 是否在后台线程执行？
- [ ] YUV→RGBA 转换是否高效（Native 实现）？
- [ ] ImageProxy 是否及时关闭？
- [ ] 前后置摄像头切换是否流畅（< 200ms）？
- [ ] 画幅切换（4:3 ↔ 16:9）是否无闪烁？
- [ ] 旋转角度是否正确处理（0°/90°/180°/270°）？

### 人脸关键点映射检查清单

- [ ] 468→106 映射表是否正确？
- [ ] 旋转校正是否应用？
- [ ] 前置摄像头是否镜像翻转？
- [ ] 归一化坐标范围是否为 [0.0, 1.0]？
- [ ] Viewport 计算是否考虑画幅比例？
- [ ] UV 映射是否与 Shader 期望一致？
- [ ] 调试浮层是否显示检测来源（MediaPipe/MNN）？

---

## 🚨 常见问题与解决方案

### Q1: Shader 编译成功但画面全黑

**A**: 
1. 检查纹理是否正确绑定：
   ```kotlin
   GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
   GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
   ```
2. 检查 Uniform 是否传递：
   ```kotlin
   val loc = shaderProgram.getUniformLocation("uTexture")
   GLES20.glUniform1i(loc, 0)  // 对应 TEXTURE0
   ```
3. 使用红色测试 Shader 验证渲染链路

### Q2: 画面倒置或左右翻转

**A**:
1. 检查纹理坐标是否需要翻转：
   ```glsl
   // Y 轴翻转
   vec2 flippedUV = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
   ```
2. 检查前置摄像头镜像：
   ```kotlin
   val mirroredX = if (lensFacing == FRONT) 1f - normX else normX
   ```
3. 检查旋转角度是否正确应用

### Q3: EGL 上下文丢失导致崩溃

**A**:
1. 检查是否在正确的线程使用上下文：
   ```kotlin
   eglCore.makeCurrent(windowSurface, eglContext!!)
   ```
2. 检查 Surface 是否有效：
   ```kotlin
   if (!surface.isValid) {
       Log.w(TAG, "Surface invalid, skip rendering")
       return
   }
   ```
3. 实现上下文恢复机制：
   ```kotlin
   if (eglContextLost) {
       eglCore.release()
       eglCore.init()
       recreateTextures()
   }
   ```

### Q4: 拍照后处理慢（> 1s）

**A**:
1. 启用 PBO 异步读取
2. 减小处理分辨率（先缩放再处理）
3. 使用 GPU 离屏渲染（而非 CPU Canvas）
4. 复用 FBO 和纹理资源

### Q5: 人脸关键点偏移

**A**: 
1. 检查坐标系是否统一使用**图像坐标系**（详见 [COORDINATE_SYSTEM_STANDARD.md](../../docs/07-STANDARDS/COORDINATE_SYSTEM.md)）
2. 检查 468→106 映射表是否正确（参考 [FACE_LANDMARKS.md](../../docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md)）
3. 检查旋转校正是否应用：
   ```kotlin
   // 根据 rotationDegrees 调整坐标
   val rotatedX = when (rotationDegrees) {
       90 -> normY
       180 -> 1f - normX
       270 -> 1f - normY
       else -> normX
   }
   ```
4. 检查前置摄像头是否镜像翻转：
   ```kotlin
   // 前置摄像头需要 x 轴翻转
   val finalX = if (lensFacing == FRONT) 1f - rotatedX else rotatedX
   ```
5. 检查 Viewport 计算是否考虑画幅比例
6. 启用调试浮层验证关键点位置

---

## 📚 参考文档

### 内部文档
- [BEAUTY_ENGINE_TECH_SPEC.md](docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 相机预览技术规格（原 CAMERA_PREVIEW_TECH_SPEC.md 已并入）
- [BIG_BEAUTY_TECH_SPEC.md](docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 大美丽引擎技术规格
- [ADR-002-opengl-offscreen-unified-pipeline.md](docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md) - 离屏渲染架构决策
- [BEAUTY_ENGINE_TECH_SPEC.md](docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 引擎容灾降级策略（容灾降级章节；原 BEAUTY_ENGINE_FALLBACK.md 已并入）

### 外部资源
- [OpenGL ES 2.0 Reference](https://www.khronos.org/opengles/sdk/docs/man/)
- [Android CameraX Guide](https://developer.android.com/training/camerax)
- [EGL 1.4 Specification](https://www.khronos.org/registry/EGL/specs/eglspec.1.4.pdf)
- [GLSL ES 1.0 Spec](https://www.khronos.org/files/opengles_shading_language.pdf)

---

## 🎓 最佳实践

### 1. 渲染线程管理

专用渲染线程，设置 `Thread.MAX_PRIORITY` 减少调度延迟。

### 2. 错误处理

每步 OpenGL 操作后检查 `glGetError()`，使用结构化日志标签。

### 3. 资源清理

按相反顺序释放：停止线程 → FBO → 纹理 → Surface → EGL。

### 4. 日志规范

统一使用 `PoLang:[ModuleName]` 标签，如 `PoLang:BeautyRenderer`。

完整代码示例见 [reference.md](reference.md) §6。

---

**Skill 版本**: 1.2.0  
**创建日期**: 2026-05-03  
**更新日期**: 2026-05-25  
**维护者**: [RD] 全栈工程师 + [CR] 规范守护者  
**适用范围**: PoLang 项目音视频与 OpenGL 相关开发

## 相关文件

- [metal-render-expert](/metal-render-expert) — iOS Metal 渲染对标（GLSL→MSL 翻译 + CVMetalTextureCache）

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
| 1.2.0 | 2026-09-25 | Preview Surface 绑定修复项对齐现状：`beautyPreviewView.initialize()` → `CameraPreviewRenderer` + `ensureRendererInitialized()`/`bindDisplaySurface()` |
