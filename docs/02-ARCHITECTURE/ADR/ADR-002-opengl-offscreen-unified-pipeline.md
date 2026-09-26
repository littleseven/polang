# ADR-002: OpenGL 离屏渲染统一美颜处理管线

**状态**: 已接受 (Accepted)  
**日期**: 2026-04-17  
**最后同步**: 2026-06-04（与 `PhotoProcessorImpl.kt` / `BeautyRenderer.kt` 实际代码对齐，修正 `BeautyShaderChain` 等已废弃设计）  
**决策**: PM/RD 联合评审  
**依赖**: ADR-001 (分层架构重构)

---

## 1. 背景

### 当前问题 (ADR-001 修复后仍存在的差距)

| 美颜效果 | 预览 (GPU Shader) | 拍照 (CPU Canvas) | 一致性 |
|---------|------------------|------------------|--------|
| 磨皮 | 双边滤波 (边缘保持) | 高斯模糊近似 | ⚠️ 70% |
| 美白 | YUV 亮度 Shader | ColorMatrix | ⚠️ 80% |
| 瘦脸 | FaceWarp Shader | Canvas Mesh | ⚠️ 85% |
| 大眼 | 径向放大 Shader | Canvas Mesh | ⚠️ 85% |
| 唇色 | HSV Shader | 像素级着色 | ⚠️ 75% |
| 腮红 | 椭圆染色 Shader | ColorMatrix | ⚠️ 70% |

**根本原因**: 两套独立实现（OpenGL ES vs Canvas API），算法细节难以完全对齐

---

## 2. 决策: OpenGL 离屏渲染统一管线

### 2.1 目标架构

![GPU 统一管线](assets/diagrams/adr002-gpu-pipeline.png)

### 2.2 核心设计

**统一 Shader 管线路径**:
```
预览路径: Camera → SurfaceTexture → BeautyRenderer 多 Pass → SurfaceView (实时 30fps)
拍照路径: Bitmap → Texture → BeautyRenderer 多 Pass → FBO → glReadPixels → Bitmap (单次)
```

**关键洞察**: 
- 复用同一套 `BeautyRenderer` 多 Pass 渲染管线（CopyPass / BeautyUnitPass / FaceMakeupPass / MainShader）
- 预览和拍照只有输入/输出不同，处理逻辑完全一致
- 拍照路径跳过 CopyPass（`skipCopyPass = true`），因为输入已是 2D 纹理
- 彻底解决预览/拍照一致性
- PBO 异步读取当前未启用（`usePbo = false`），使用同步 `glReadPixels`

---

## 3. 技术实现

### 3.1 核心实现: PhotoProcessorImpl

```kotlin
// engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/PhotoProcessorImpl.kt（实际落地类）

class PhotoProcessorImpl(private val context: Context) : PhotoProcessor {
    private val eglCore = EGLCore()
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var isEglInitialized = false

    // 复用预览的 BeautyRenderer
    private var beautyRenderer: BeautyRenderer? = null

    // FBO 资源（离屏渲染输出）
    private var fboId: Int = 0
    private var fboTextureId: Int = 0
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    // PBO 当前未启用（需要 OpenGL ES 3.0）
    private var usePbo: Boolean = false

    // 输入纹理
    private var inputTextureId: Int = 0

    override fun process(bitmap: Bitmap, params: BeautyParams, faceData: FaceData?): Bitmap {
        // 1. 初始化 EGL 环境（Pbuffer Surface，4096x4096）
        ensureEglInitialized()
        eglCore.makeCurrent(eglSurface, eglContext)

        // 2. 检查纹理尺寸限制
        val maxTextureSize = getMaxTextureSize()
        if (bitmap.width > maxTextureSize || bitmap.height > maxTextureSize) {
            throw PhotoProcessException("Bitmap exceeds max texture size")
        }

        // 3. 创建/复用 FBO
        ensureFbo(bitmap.width, bitmap.height)

        // 4. 上传 Bitmap 到 OpenGL 2D Texture
        uploadBitmapToTexture(bitmap)

        // 5. 初始化 BeautyRenderer（如果未初始化）
        val renderer = ensureBeautyRenderer()

        // 6. 设置美颜参数和人脸数据
        applyBeautyParams(renderer, params, faceData)

        // 7. 执行渲染（多 Pass）
        val outputTexture = renderPhoto(renderer, params, bitmap.width, bitmap.height)

        // 8. 读取 FBO 到 Bitmap（同步 glReadPixels）
        val result = readPixelsToBitmap(bitmap.width, bitmap.height, outputTexture)

        return result
    }
}
```

> **注意**：早期设计中的 `OffscreenRenderer` / `BeautyShaderChain` 类未实际落地。实际实现直接复用 `BeautyRenderer` 的多 Pass 渲染管线（`renderBeautyMultiPass()` / `renderMainShaderFromFbo2D()`），通过 `skipCopyPass = true` 跳过预览路径的 OES→2D 转换。

### 3.2 渲染管线复用（实际代码路径）

```kotlin
// 预览时：CameraPreviewRenderer 驱动 BeautyRenderer
class CameraPreviewRenderer {
    private lateinit var beautyRenderer: BeautyRenderer
    
    fun onDrawFrame() {
        // 设置外部 OES 纹理和变换矩阵
        beautyRenderer.setExternalTextureId(textureId)
        beautyRenderer.setTextureTransform(transformMatrixBuffer)
        beautyRenderer.setIsFrontCamera(isFrontCamera)
        
        // 执行完整多 Pass 渲染
        beautyRenderer.onRender()
    }
}

// 拍照时：PhotoProcessorImpl 复用同一 BeautyRenderer
class PhotoProcessorImpl {
    private var beautyRenderer: BeautyRenderer? = null
    
    fun process(bitmap: Bitmap, params: BeautyParams, faceData: FaceData?): Bitmap {
        val renderer = ensureBeautyRenderer()
        
        // 设置 2D 纹理输入
        renderer.setExternalTextureId(inputTextureId)
        
        // 应用美颜参数和人脸数据
        applyBeautyParams(renderer, params, faceData)
        
        // 绑定 FBO 并执行渲染
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        
        // 判断是否需要完整多 Pass 管线
        val needMultiPass = params.smoothing > 0.001f ||
            params.whitening > 0.001f ||
            params.bigEyes > 0.001f ||
            kotlin.math.abs(params.slimFace) > 0.001f ||
            params.lipColor > 0.001f ||
            params.blush > 0.001f ||
            params.styleEffect != StyleEffect.NONE

        if (needMultiPass) {
            // 使用与预览完全一致的完整多 Pass 管线
            renderer.renderBeautyMultiPass(
                width = width,
                height = height,
                outputFramebufferId = fboId,
                skipCopyPass = true  // 拍照路径跳过 CopyPass
            )
        } else {
            // 无需多 Pass：直接主 Shader
            renderer.renderMainShaderFromFbo2D(inputTextureId, width, height)
        }
        
        // 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        
        // glReadPixels 读取结果
        return readPixelsDirect(width, height)
    }
}
```

> **关键差异**：早期设计中的 `BeautyShaderChain` 类（含 `SmoothingShader`/`WhiteningShader` 等独立 Shader 类）未实际落地。实际实现中，GLSL 源码已迁移至 `assets/shaders/` 并通过 `ShaderModuleLoader` 按需加载；`BeautyRenderer` 内部通过多 Pass 管线（CopyPass → BeautyUnitPass → FaceMakeupPass → MainShader/style effect）和独立的 `FaceMakeupPass` 处理妆容，通过 `uniform` 参数控制各效果开关和强度。注意：早期常量名 `FRAGMENT_SHADER_BEAUTY` 已随 GLSL 迁移而移除。

### 3.3 预览与拍照复用

```kotlin
// 预览时：由 CameraPreviewRenderer 驱动
class CameraPreviewRenderer {
    private lateinit var beautyRenderer: BeautyRenderer
    
    fun onDrawFrame() {
        // 实时渲染到 SurfaceView
        beautyRenderer.onRender()
    }
}

// 拍照时：由 PhotoProcessorImpl 驱动
class PhotoProcessorImpl {
    private var beautyRenderer: BeautyRenderer? = null
    
    fun process(bitmap: Bitmap, params: BeautyParams, faceData: FaceData?): Bitmap {
        // 离屏渲染，复用同一 BeautyRenderer 实例
        val inputTexture = bitmapToTexture(bitmap)
        
        // 设置输入并渲染到 FBO
        beautyRenderer?.setExternalTextureId(inputTexture)
        renderPhoto(beautyRenderer!!, params, bitmap.width, bitmap.height)
        
        return fboToBitmap()
    }
}
```

---

## 4. 迁移计划

### Phase 1: 基础设施 (2-3 周)

| 任务 | 负责人 | 输出 |
|------|--------|------|
| PhotoProcessorImpl 框架 | RD | 类实现 + 单元测试 |
| FBO/Texture 管理封装 | RD | 资源管理器 |
| glReadPixels 优化 | RD | PBO 异步读取（⏳ 待优化） |
| Shader 加载器重构 | RD | 支持 .glsl 文件（⏳ 待优化） |

**验收标准**:
- [x] PhotoProcessorImpl 可独立单元测试
- [x] 1080p 图片处理 < 500ms（设备实测 200ms 以内，见 §9 落地记录）
- [x] 内存无泄漏（连续 100 张压力测试通过，见 §9 落地记录）

### Phase 2: Shader 迁移 (3-4 周)

| Shader | 当前状态 | 迁移方式 |
|--------|---------|---------|
| 双边滤波（磨皮） | BeautyRenderer 主 Shader 内联 | 已通过 uniform 控制 |
| YUV 美白 | BeautyRenderer 主 Shader 内联 | 已通过 uniform 控制 |
| FaceWarp（瘦脸） | BeautyRenderer 主 Shader 内联 | 已通过 uniform 控制 |
| 径向放大（大眼） | BeautyRenderer 主 Shader 内联 | 已通过 uniform 控制 |
| HSV 唇色 | FaceMakeupPass 独立 Pass | 独立 Pass 处理 |
| 椭圆腮红 | FaceMakeupPass 独立 Pass | 独立 Pass 处理 |
| ColorMatrix 滤镜 | BeautyRenderer 主 Shader 内联 | 已通过 uniform 控制 |
| 风格特效 | StyleEffectShader 独立 Pass | 独立 Pass 处理 |

**验收标准**:
- [x] 所有效果通过 BeautyRenderer 统一管线渲染
- [x] 预览效果与之前完全一致
- [ ] 拍照效果与预览 100% 一致（像素级对比）— 持续优化中

### Phase 3: 拍照路径切换 (2 周)

| 步骤 | 操作 |
|------|------|
| 1 | PhotoProcessorImpl 复用 BeautyRenderer 多 Pass 管线 |
| 2 | A/B 测试：对比 CPU vs GPU 路径效果 |
| 3 | 灰度发布：50% 用户使用 GPU 路径 |
| 4 | 全量切换：100% GPU 路径 |
| 5 | 废弃 CPU 路径代码 |

**验收标准**:
- [x] 拍照效果与预览高度一致（主观对比通过）
- [x] 性能指标：1080p < 300ms（实测约 200ms）
- [ ] 崩溃率 < 0.1% — 持续监控中

### Phase 4: 库化准备 (4-6 周)

> ❌ **不再计划（2026-08-23 标注）**：项目定位为技术研究、不追求商业化（见 CLAUDE.md / PRODUCT.md），`beauty-core` 拆分与 SDK 发布取消；引擎能力以 `:engines:beauty-api` + `:engines:beauty-engine` 模块源码形态沉淀，分层边界已由 ADR-001 落实。

- ~~抽离 `beauty-core` 模块（纯 Kotlin 接口）~~
- ~~发布内部 SDK v0.1.0~~

---

## 5. 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 内存压力（大图片） | 中 | 高 | PBO 异步读取，分块处理 4K |
| 兼容性（低端设备） | 中 | 中 | 保留 CPU 路径作为 Fallback |
| 开发周期延长 | 低 | 中 | 分阶段交付，每个 Phase 可独立发布 |
| Shader 精度差异 | 低 | 高 | 浮点精度统一，端到端测试 |

---

## 6. 性能预期

| 指标 | 当前 (CPU) | 目标 (GPU) | 提升 |
|------|-----------|-----------|------|
| 1080p 处理时间 | 800-1200ms | 200-300ms | **4-6x** |
| 4K 处理时间 | 3-5s | 600-1000ms | **3-5x** |
| 内存峰值 | 150MB | 80MB | **47%↓** |
| 预览/拍照一致性 | 70% | 99%+ | **提升 29%** |

---

## 7. 依赖与前置条件

- [x] ADR-001 分层架构完成
- [x] Phase 1 基础设施评审通过
- [x] QA 自动化测试框架就绪
- [x] 性能基准测试环境搭建

---

## 8. 决策记录

| 日期 | 决策 | 决策人 | 状态 |
|------|------|--------|------|
| 2026-04-17 | 采用方案 A (OpenGL 离屏渲染) | PM/RD | 已接受 |
| 2026-04-17 | 4 阶段实施计划 | RD | 已接受 |
| 2026-04-17 | **Phase 1 启动** | PM | ✅ **已完成** |

---

### 9. Phase 1 进展记录

### 2026-04-17 基础设施框架搭建

| 任务 | 状态 | 产出文件 |
|------|------|---------|
| PhotoProcessorImpl 框架 | ✅ 完成 | `PhotoProcessorImpl.kt` |
| BeautyRenderer 复用 | ✅ 完成 | `BeautyRenderer.kt` |
| 多 Pass 管线统一 | ✅ 完成 | `BeautyPass.kt`, `FaceMakeupPass.kt` |
| 单元测试骨架 | ✅ 完成 | `PhotoProcessorImplTest.kt` |

### 已完成代码文件

**PhotoProcessorImpl.kt** - 核心离屏渲染器（实际落地类，替代早期 `OffscreenRenderer` 设计）
- Bitmap → Texture → FBO → Bitmap 完整流程
- 独立 EGL 上下文（Pbuffer Surface 4096x4096）
- FBO 复用与资源自动管理
- 同步 `glReadPixels` 读取（PBO 待后续升级）
- 错误处理和边界检查

**BeautyRenderer.kt** - 统一渲染器（预览和拍照共用）
- 主 Shader（`FRAGMENT_SHADER_BEAUTY`）处理磨皮/美白/美型/调色
- `FaceMakeupPass` 独立 Pass 处理唇色/腮红
- `StyleEffectShader` 独立 Pass 处理风格特效
- `renderBeautyMultiPass()` 完整多 Pass 管线
- `renderMainShaderFromFbo2D()` 拍照路径快捷入口

**BeautyPass.kt / FaceMakeupPass.kt** - Pass 基类与妆容 Pass
- `BeautyPass`: 通用渲染 Pass 封装
- `FaceMakeupPass`: 三角网格 + 纹理贴图的妆容渲染

### 验收进度

Phase 1 验收标准：
- [x] PhotoProcessorImpl 框架实现（支持 Bitmap 输入输出）
- [x] 单元测试通过（真实 EGL 环境验证）
- [x] 1080p 图片处理 < 500ms（设备实测 200ms 以内）
- [x] 内存无泄漏测试（连续 100 张压力测试通过）

### 落地实现记录（2026-05）

GPU 离屏渲染拍照已由 `PhotoProcessorImpl` 在 `engines/beauty-engine/render` 中完整落地，关键实现要点：

1. **独立 EGL 上下文与 Pbuffer Surface**：在 `PhotoProcessorImpl` 中创建独立 EGL 上下文，避免与预览线程竞争，实现后台线程异步处理。
2. **渲染管线复用**：直接调用 `BeautyRenderer.renderBeautyMultiPass()` / `renderMainShaderFromFbo2D()`，复用预览同一套渲染逻辑。拍照路径设置 `skipCopyPass = true` 跳过 OES→2D 转换。
3. **坐标系统一**：拍照路径直接使用原始人脸关键点坐标，跳过预览路径的 `inverseTransform`，Bitmap 上传后的 UV 空间与 Shader 期望的标准 UV 空间天然对齐。
4. **FBO 复用与 glReadPixels**：实现 `ensureFbo(width, height)` 按需重建；采用同步 `glReadPixels` 读取，主流设备 1080P 处理耗时控制在 200ms 以内。
5. **黑屏修复**：确保 `BeautyRenderer` 在 `PhotoProcessorImpl` 线程内完成 `onInit`，显式设置 `glViewport`，解决跨上下文 Uniform 缓存失效问题。

> 注：
> - 原 `GPU_PHOTO_MAJOR_CHANGES.md` 已归档合并至本文档。
> - 早期设计中的 `BeautyShaderChain` / `OffscreenRenderer` / 独立 Shader 类（`SmoothingShader` 等）未实际落地，实际实现直接复用 `BeautyRenderer` 统一管线。
> - PBO 异步读取当前未启用（`usePbo = false`），后续作为优化项评估。

---

**当前状态**: Phase 1 已落地，`PhotoProcessorImpl` 生产可用。后续优化（PBO 异步读取、多分辨率压测）归入 P1 迭代。

> **设计演变说明**：本文档中的 `BeautyShaderChain`、`OffscreenRenderer` 以及独立的 `SmoothingShader`/`WhiteningShader` 等类属于早期设计草案，未实际落地。实际实现直接复用 `BeautyRenderer` 统一多 Pass 管线，通过 `uniform` 参数控制各效果开关。此简化降低了架构复杂度，同时保证了预览/拍照效果一致性。
