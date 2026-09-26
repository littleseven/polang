# 大美丽：实时美颜完整指南

> **版本**: 7.0  
> **状态**: 生效中（大美丽 BIG_BEAUTY 单引擎）  
> **最后更新**: 2026-08-03  
> **维护者**: RD Agent  
> **技术路线**: 自研 GPU 加速管线 + EGL 共享上下文 + SurfaceTexture 直通 + GPU 离屏渲染拍照（CPU Fallback 降级）

---

## 文档边界与导航

- 本文档聚焦 大美丽 主引擎：渲染链路、容灾回退、冷却恢复与观测指标，同时涵盖相机预览比例策略、帧同步美妆系统与容灾降级恢复机制。
- 预览比例与坐标转换细节：见「[9. 相机预览与比例策略](#9-相机预览与比例策略)」。
- 帧同步美妆系统：见「[10. 帧同步美妆系统](#10-帧同步美妆系统)」。
- 容灾降级与恢复：见「[11. 容灾降级与恢复](#11-容灾降级与恢复)」。
- 产品交互与验收口径：见 `FEATURES.md`。
- beauty-engine 模块实现规范：见 `engines/beauty-engine/AGENTS.md`。

---

## 0. 背景与目标

### 0.0 引擎现状（2026-04-11）

| 引擎 | 状态 | 说明 |
|------|------|------|
| **大美丽（BIG_BEAUTY）** | ✅ 唯一引擎 | 自研 OpenGL ES + EGL；基础美颜走主 Shader，磨皮/美白/几何美型/妆容按需切换多 Pass GPU 管线 |

**配置方式**：通过 `BeautyStrategy`（`domain.model.UserPreferences`）配置，当前仅支持 `BeautyStrategy.BIG_BEAUTY`。

**容灾策略**：
- 大美丽初始化失败或运行异常时，由 `onGlWarmUpFallback` 收敛，回退并持久化状态。
- **当前无自动恢复热切换**：需重启预览绑定流程。

**长期定位**：
- 大美丽 从 App 内部能力逐步演进为独立视觉能力基础库（`beauty-engine` 模块库化）。
- App 侧通过稳定 API 接入，不直接依赖底层 OpenGL/CameraX 实现。

### 0.1 现状（2026-05）

- **引擎链路**：大美丽（BIG_BEAUTY）单引擎，旧兜底引擎与实验性模块已清理。
- **多 Pass 链路**：磨皮/美白/大眼/瘦脸/唇色/腮红按需走多 Pass GPU 管线。
- **可观测性**：渲染线程每秒聚合 `PerfStats`，调试浮层实时展示。

### 0.2 目标（第一性原理）

从"用户体验"倒推技术要求：

1. **极致流畅**：预览帧率 ≥ 30fps，理想 60fps；单帧处理 ≤ 16ms
2. **零感延迟**：参数调节到画面变化的延迟 < 100ms（用户阈值）
3. **技术可控**：自研管线，快速迭代；零授权成本
4. **容错可用**：大美丽预览链路失败时触发 fallback，并支持冷却结束后重试

### 0.3 技术本质

实时美颜预览的本质是 **GPU 加速的图像流处理管道**：

```
相机传感器 → YUV 数据 → GPU 纹理 → Shader 处理 → RGB 显示
           (CameraX)   (OpenGL)  (GLSL)    (Surface)
```

关键约束：

- 数据流必须零拷贝（直接纹理传递）
- 处理流必须在 GPU（避免 CPU 瓶颈）
- 显示流必须直通（避免额外 Surface 切换）

---

## 1. 第一性原理拆解

### 1.1 为什么选择自研（大美丽）而非第三方 SDK

| 维度 | 大美丽（自研） |
|------|----------------|
| 算法可控性 | ✅ 完全自主 |
| 性能调优 | ✅ 可精准控制每一步 |
| 内存占用 | 目标 < 30MB |
| 延迟控制 | ✅ 可精准控制线程优先级 |
| 故障排查 | ✅ 全链路日志可观测 |
| 授权成本 | ✅ 零成本 |
| Compose 兼容性 | ✅ SurfaceView/FrameLayout |
| 人脸检测 | MediaPipe / MNN 双引擎外部提供（ML Kit 人脸检测已移除） |

### 1.2 为什么选择 OpenGL ES 而非 Vulkan

- **CameraX 兼容性**：CameraX Preview 默认输出 SurfaceTexture，天然对接 OpenGL ES
- **设备覆盖**：OpenGL ES 2.0 覆盖 99%+ Android 设备
- **开发周期**：Vulkan 学习曲线陡峭，2-3 周难以完成
- **后续优化**：可在 大美丽稳定后逐步评估迁移到 Vulkan

### 1.3 为什么必须 EGL 上下文共享

- **离屏初始化**：Shader 编译、资源加载需要 EGL 上下文
- **多线程渲染**：渲染线程需要独立的上下文，但共享纹理资源
- **CameraX 约束**：CameraX 的 SurfaceProvider 在主线程，渲染必须在独立线程

**正确做法**：

```
主线程：EGL 初始化 + SurfaceTexture 创建
   ↓
渲染线程：独立上下文（共享纹理） + 美颜渲染
```

### 1.4 磨皮算法演进路线（2026-04）

**当前实现**：双边滤波快速近似（5×5 采样核 + 值域高斯权重），已落地在 `engines/beauty-engine/src/main/assets/shaders/pass_smoothing.glsl`。

**算法演进路线**：

| 阶段 | 方案 | 复杂度 | 边缘保持 | 状态 |
|------|------|------|------|------|
| 已放弃 | 盒式模糊（Box Blur） | O(1) | 无，失真明显 | ❌ 放弃 |
| **当前** | 双边滤波快速近似（5×5 采样核 + 锐化后处理） | O(N·r²) 近似 | 良好，保留皮肤轮廓 | ✅ 已落地 |
| Phase 2 | **引导滤波（Guided Filter）** | **O(N)，与半径无关** | 更优，结构转移特性，无梯度反转光晕 | ⏳ 规划中 |
| Phase 3 | 多尺度细节分层（Multi-scale） | O(N·K)，K层 | 工业级，分频层独立处理后融合 | 🔭 长期目标 |

### 1.5 滤镜技术演进路线（2026-04）

**当前实现**：自定义 GLSL Shader 硬编码颜色变换（LEICA_CLASSIC / FILM_GOLD / COOL / WARM）。

**演进规划**：

| 阶段 | 方案 | 优势 | 状态 |
|------|------|------|------|
| **当前** | 自定义 GLSL Shader | 完全可控，无额外依赖 | ✅ 已落地 |
| Phase 2 | **3D LUT（颜色查找表）** | 预计算 64×64×64 网格，运行时三线性插值，接近零计算开销，支持专业调色风格动态扩展 | ⏳ 规划中 |
| Phase 3 | 滤镜管线模块化重构 | 滤镜模块化可插拔，支持多滤镜链组合与动态加载 | 🔭 长期目标 |

---

## 2. 架构设计

### 2.1 整体架构

![大美丽引擎预览栈](assets/diagrams/beauty-preview-stack.png)

### 2.2 数据流（零拷贝）

![预览帧 GPU 管线](assets/diagrams/beauty-frame-pipeline.png)

**关键优化点**：

- ❌ 避免从 GPU 读回 CPU（耗时 ~50ms）
- ❌ 避免多次纹理上传（内存带宽瓶颈）
- ✅ 全流程在 GPU 完成，零拷贝

### 2.3 引擎策略路由（2026-04 当前实现）

![美颜策略装配链](assets/diagrams/beauty-strategy-chain.png)

### 2.4 拍照处理架构（2026-04 新增）

#### 拍照处理架构（2026-04 当前实现）

**标准路径（GPU 离屏渲染，✅ 已落地）**：
- 预览：CameraX → SurfaceTexture → OpenGL ES Shader → SurfaceView
- 拍照：CameraX → ImageCapture → Bitmap → `PhotoProcessorImpl` GPU 离屏渲染 → 保存
- 预览与拍照复用同一套 Shader 管线，效果一致性 ≥ 99%。详见 `ADR-002-opengl-offscreen-unified-pipeline.md`。

**降级路径（CPU Canvas）**：GPU 路径失败时回退到 `GpuBeautyProcessor`（`core/image/`），确保拍照不失败。

**关键优化点**：
1. 人脸检测复用：预览阶段检测结果缓存，拍照时直接使用 `FaceWarpParams`
2. 参数统一转换：预览和拍照共用 `BeautySettings` → `BeautyParams` 转换逻辑
3. 算法核心抽象：磨皮/美白/瘦脸等核心数值计算抽象为纯函数，GPU/CPU 共用

---

### 2.5 核心组件职责

#### BeautyPreviewView

**职责**：封装 SurfaceView 与渲染管线，提供简洁 API

**关键约束**：

- **禁止**在构造函数中启动渲染线程。
- 必须先等待 `SurfaceView` 的 `surfaceCreated/surfaceChanged` 可用后再绑定显示 Surface。
- CameraX 输入 Surface 通过 `getSurfaceForCamera()` 延迟创建并缓存，避免重复 new Surface。

#### CameraPreviewRenderer

**职责**：管理完整的渲染管线

**关键约束**：

- `init()` 不启动渲染线程
- `setRenderSurface()` 才启动渲染
- 渲染线程使用独立的共享上下文
- 渲染线程优先级设为 `Thread.MAX_PRIORITY`

#### BeautyRenderer

**职责**：执行美颜渲染，管理所有 uniform 参数

**当前支持的美颜参数（基础主 Shader + 妆容多 Pass）**：

| 参数 | uniform | 类型 | 范围 |
|------|---------|------|------|
| 磨皮 | `uSmoothing` | Float | 0.0~1.0 |
| 美白 | `uWhitening` | Float | 0.0~1.0 |
| 大眼 | `uBigEyes` | Float | 0.0~1.0 |
| 瘦脸 | `uSlimFace` | Float | -1.0~1.0 |
| 唇色强度 | `uLipColor` | Float | 0.0~1.0 |
| 唇色色号 | `uLipColorIndex` | Int | 0~11 |
| 腮红强度 | `uBlush` | Float | 0.0~1.0 |
| 腮红色系 | `uBlushColorFamily` | Int | 0=粉/1=橙/2=梅 |
| 人脸中心 | `uFaceCenter` | Vec2 | 纹理 UV |
| 双眼位置 | `uLeftEye/uRightEye` | Vec2 | 纹理 UV |
| 嘴部关键点 | `uMouthLeft/Right/Center` | Vec2 | 纹理 UV |
| 唇部中心 | `uUpperLipCenter/uLowerLipCenter` | Vec2 | 纹理 UV |
| 唇部外轮廓 | `uLipOuterContourPoints[20]` | Vec2[] | 纹理 UV |
| 唇部内轮廓 | `uLipInnerContourPoints[20]` | Vec2[] | 纹理 UV |
| 人脸半径 | `uFaceRadius` | Float | 0.08~0.45 |
| 是否有脸 | `uHasFace` | Float | 0.0 / 1.0 |
| 纹素大小 | `uTexelSize` | Vec2 | 1/width, 1/height |

---

## 3. 核心技术难点与解决方案

### 3.1 难点 1：EGL 上下文管理

**解决方案**：EGL 上下文共享

```kotlin
// 1. 主线程：创建共享上下文
val shareContext = eglCore.createContext()

// 2. 离屏初始化（Pbuffer Surface）
val pbufferSurface = eglCore.createSurface(null, 1, 1)
eglCore.makeCurrent(pbufferSurface, shareContext)
beautyRenderer.onInit()  // 编译 Shader

// 3. 渲染线程：创建共享上下文
val renderContext = eglCore.createContext(shareContext)
eglCore.makeCurrent(windowSurface.getEglSurface(), renderContext)
```

**关键约束**：

- 所有上下文必须通过 `eglCore.createContext()` 创建（自动共享）
- **禁止**在多个线程同时调用 `eglMakeCurrent`
- 渲染线程必须有自己的上下文

### 3.2 难点 2：输入 Surface 与显示 Surface 解耦

**解决方案**：输入 Surface 与显示 Surface 分离

**关键约束**：

- 输入 Surface（给 CameraX）与显示 Surface（SurfaceView）必须解耦。
- `setDefaultBufferSize()` 由输入分辨率动态设置（当前默认 1280x720）。
- 显示 Surface 可重建，输入 Surface 尽量复用。

### 3.3 难点 3：渲染线程同步与性能统计

**解决方案**：帧可用标记 + 轻量 sleep + 每秒聚合统计

```kotlin
@Volatile private var frameAvailable: Boolean = false

surfaceTexture?.setOnFrameAvailableListener {
    frameAvailable = true
}

while (isRendering && !Thread.interrupted()) {
    if (!frameAvailable) {
        Thread.sleep(1)
        continue
    }

    surfaceTexture?.updateTexImage()
    frameAvailable = false

    beautyRenderer.onRender()
    windowSurface?.swapBuffers()

    // 每秒聚合一次 FPS / processing / delay / cpu / nullFrames
    latestPerfStats = calculateStats()
}
```

**关键约束**：

- 无帧时走 `sleep(1)`，避免忙等。
- 纹理矩阵与人脸点位映射必须使用同一份 `uTextureTransform`（通过 `mapViewNormalizedToUv` 转换）。
- 调试指标统一由 `BeautyPerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames, errorCategory, errorReason)` 提供。

### 3.4 难点 4：View 归一化坐标 → 纹理 UV 坐标映射

**背景**：`FaceWarpParams` 输出的人脸坐标是屏幕归一化坐标（View 空间），而 Shader 中人脸变形参数需要在纹理 UV 空间中使用。

**解决方案**：`CameraPreviewRenderer.mapViewNormalizedToUv()` 四步映射

![人脸坐标 → Shader UV 变换](assets/diagrams/beauty-uv-transform.png)

**约束**：
- `transformFaceCoordinateSimple()`（ML Kit → 屏幕像素）与 `mapViewNormalizedToUv()`（屏幕 → UV）是串联关系，不能跳过任何一步。
- 前置摄像头镜像已在 `transformFaceCoordinateSimple` 中处理，`mapViewNormalizedToUv` 中不重复处理。

### 3.5 难点 5：MediaPipe 468 点 → 106 点映射

**背景**：人脸检测多引擎统一输出 106 点标准格式。MediaPipe Face Landmarker 输出 468 点，需映射为 106 点；MNN 2D106 检测器输出 106 点，通过 Adapter 重排。多种来源供大美丽链路复用。旧 InsightFace ONNX 路径与 NCNN 路径均已移除。

**关键说明**：具体映射关系以代码实现为准，本文档仅记录核心原则。

#### 3.5.1 106 点拓扑定义

| 索引范围 | 点数 | 区域 | 说明 |
|---------|------|------|------|
| 0-32 | 33 | 脸部轮廓 | 开放曲线：右脸颊 → 下巴 → 左脸颊，无额头点 |
| 33-37 | 5 | 右眉上部 | 从眉头到眉尾（画面左侧=实际右脸） |
| 38-42 | 5 | 左眉上部 | 从眉头到眉尾（画面右侧=实际左脸） |
| 43 | 1 | 眉心 | 两眉之间中点 |
| 44-46 | 3 | 鼻梁 | 从上到下 |
| 47-51 | 5 | 鼻尖 | 从左到右，严格对称 |
| 52-57 | 6 | 右眼外轮廓 | 画面左侧=实际右脸 |
| 58-63 | 6 | 左眼外轮廓 | 画面右侧=实际左脸 |
| 64-67 | 4 | 右眉下部 | 从眉头到眉尾 |
| 68-71 | 4 | 左眉下部 | 从眉尾到眉头 |
| 72-74 | 3 | 右眼内/下 | 74=右瞳孔 |
| 75-77 | 3 | 左眼内/下 | 77=左瞳孔 |
| 78-79 | 2 | 山根 | 眉心两侧 |
| 80-83 | 4 | 鼻孔 | 左右鼻孔 |
| 84-95 | 12 | 嘴巴外轮廓 | 顺时针闭合曲线 |
| 96-103 | 8 | 嘴巴内轮廓 | 顺时针闭合曲线 |
| 104-105 | 2 | 瞳孔 | 104=右瞳孔, 105=左瞳孔 |

#### 3.5.2 映射实现参考

**非轮廓 73 点（33-105）**的具体映射关系请参考：
- [MediaPipe468Adapter.kt](../../engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/adapter/MediaPipe468Adapter.kt) - 生产环境映射
- [FaceLandmarkOverlay.kt](../../androidApp/src/main/java/com/mamba/picme/features/gallery/components/FaceLandmarkOverlay.kt) - 静态图调试映射与可视化

**映射原则**：
1. **语义优先**：每个 106 点找到 MediaPipe 中语义对应的固定点
2. **插值过渡**：在固定点之间使用 `midPoint` 插值
3. **对称性验证**：确保关键点左右对称

#### 3.5.3 轮廓 33 点生成算法

**核心约束**：
- 轮廓为**开放曲线**，从右脸颊开始，经下巴，到左脸颊结束
- **无额头点**，M0 和 M32 与上眼皮位置齐平
- **严格左右对称**，中心点 M16 强制 x=0.5
- 中间点均匀分布

**算法步骤**：

1. **选取 MediaPipe 左半边基础点**（12 个）：
   ```
   234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152, 377
   ```

2. **插值生成 17 个左半边点**（含中心点）：
   - 使用基于线段长度的自适应插值
   - 较长线段分配更多插值点
   - 生成点 0-16（16 为下巴中心）

3. **强制对称生成右半边**（16 个点）：
   - 右半边点 17-32 是左半边点 15-0 的水平镜像
   - `x_right = 1.0 - x_left`
   - `y_right = y_left`

4. **调整 M0 和 M32 到上眼皮位置**：
   - 强制 `y = 0.38`（上眼皮齐平）
   - 均匀分布中间点：`y_step = (chinY - 0.38) / 16`

5. **强制中心点对齐**：
   - `M16.x = 0.5`（严格居中）

**坐标验证示例**（非前置摄像头）：
```
M0=(0.119,0.380)  M1=(0.125,0.391)  ...  M16=(0.500,0.552)  ...  M31=(0.875,0.391)  M32=(0.881,0.380)
```

**对称性验证**：
- M0.y = M32.y = 0.380（上眼皮齐平）✓
- M1.y = M31.y = 0.391（对称）✓
- M16.x = 0.500（中心）✓
- 所有对称点 y 值相等 ✓

---

## 4. 当前实现快照（2026-05）

### 4.1 已落地能力

- [x] `BeautyPreviewView` 采用 `SurfaceView` 显示，显示 Surface 与 CameraX 输入 Surface 解耦。
- [x] `CameraPreviewRenderer` 在主线程完成 EGL + 外部纹理 + SurfaceTexture 初始化。
- [x] 渲染线程使用 `OnFrameAvailableListener + sleep(1)` 驱动，避免忙等。
- [x] 基础主 Shader + 多 Pass 管线覆盖磨皮（双边滤波近似）/美白/大眼/瘦脸/唇色（纹理妆容）/腮红，支持实时 uniform 更新。
- [x] 已实现人脸点位到 UV 坐标映射（`mapViewNormalizedToUv`），保证形变参数与纹理变换一致。
- [x] 已输出 `BeautyPerfStats(fps, processingMs, delayMs, cpuUsage, nullFrames, errorCategory, errorReason)` 供调试浮层展示。
- [x] `BeautyPreviewEngine` 组合接口统一 `BeautyPreviewProvider` + `BeautyPreviewCapability`，App 层通过接口访问。
- [x] `BeautyStrategy`、`FaceDetectIntervalProfile` 等枚举迁移至 `domain.model.UserPreferences`，实现分层解耦。
- [x] **人脸检测缓存**：`FaceDetectionCache` 实现预览/拍照人脸检测复用，减少效果差异。
- [x] **滤镜一致性修复**：`FilterType` 新增 `toAndroidColorMatrix()` 方法，修复 Compose ColorMatrix 与 Android ColorMatrix 类型不兼容导致的滤镜失效问题。
- [x] **GPU 离屏拍照**：`PhotoProcessorImpl` 实现 GPU 离屏渲染拍照路径，预览与拍照复用同一套 Shader 管线，效果一致性 ≥ 99%。
- [x] **风格特效**：`BeautyShaders` 新增 `LEICA_CLASSIC`、`FILM_GOLD`、`COOL`、`WARM` 等风格滤镜 Shader。
- [x] **帧同步美妆（🔄 部分实现）**：`FrameSyncManager` + `MotionTracker` + `FrameSyncBridge` 框架已落地，解决检测帧率（~10fps）与渲染帧率（30~60fps）不同步导致的妆容飞脱问题。`DetectionQueue`/`FaceDetectionWorker` 为设计期概念，当前使用同步检测路径。

### 4.2 引擎现状

#### 大美丽（BIG_BEAUTY）— 唯一引擎
- **触发条件**：`BeautyStrategy.BIG_BEAUTY`（默认值，唯一值）
- **路由类**：`GlBeautyPreviewStrategy`
- **Provider**：`GlBeautyPreviewProvider` → `BeautyPreviewView` → `CameraPreviewRenderer`
- **人脸检测**：默认使用 MediaPipe Face Mesh 468→106 输出构建 `FaceWarpParams`，MNN 2D106 作为备选（通过 DetectionPipelineFactory 配置），再由 `CameraPreviewRenderer.mapViewNormalizedToUv()` 映射到纹理 UV。旧 InsightFace ONNX 路径与 NCNN 路径均已完全移除。
- **容灾**：warm-up 失败调用 `onGlWarmUpFallback(reason)` 上报，由 `CameraRuntimeState` 持久化

### 4.3 下一步技术项（RD，优先级排序）

#### 🔴 P0 — 稳定性与文档一致性
- [ ] 将自动回退结果提示与 I18N 文案在 UI 层彻底打通（当前以日志和调试态为主）。
- [x] `engines/beauty-engine/AGENTS.md` 中磨皮算法描述已与实际代码对齐（"双边滤波快速近似"）。✅ 已完成

#### 🟡 P1 — 性能与可观测性
- [ ] 补充低端机专项压测基线（720p/1080p，前后置，连续 5 分钟）。
- [ ] 针对 `nullFrames` 异常波动增加告警阈值与自动抓日志能力。

#### 🟢 P2 — 大美丽库化落地（8~16 周）
- [ ] `beauty-core`：沉淀策略模型、参数协议、状态机与能力契约。
- [ ] 定义库级稳定 API（含语义版本），确保 App 仅依赖能力接口。

### 4.4 三大目标驱动的重构路线（技术视角）

#### Phase 1：质量门禁与可观测性收敛（2~4 周）✅ 已完成
- 固化 CR 检查项：分层越界、I18N 漏同步、回退链路失败一票否决。
- 统一调试指标采集与日志字段，确保回归可追踪。

#### Phase 2：架构边界收敛（4~8 周）✅ 已完成
- 已落地（2026-04~05）：
  - [x] `BeautyStrategy`、`ThemeMode`、`AppLanguage`、`FaceDetectIntervalProfile` 迁移至 `domain.model.UserPreferences`。
  - [x] `LensFacing` 去除 CameraX 直接依赖，改为纯 Kotlin 枚举。
  - [x] `OcrUseCase` 实现下沉至 `data/local/MlKitOcrProcessor`，domain 层仅保留 `OcrProcessor` 接口。
  - [x] 新增 `domain/repository/UserSettingsRepository` 接口，ViewModel 依赖接口而非实现类。
  - [x] `PhotoProcessorImpl` GPU 离屏渲染拍照路径落地，预览/拍照 Shader 管线统一。
  - [x] 帧同步美妆框架（`FrameSyncManager`、`MotionTracker`、`FrameSyncBridge`）落地。`DetectionQueue`/`FaceDetectionWorker` 为设计期概念未实现，当前使用同步检测路径。
- 剩余事项（⏳ 待排期）：
  - [ ] gallery 层 ViewModel 依赖审计与收敛。
  - [ ] camera 层其余直接调用 data 层的用例审计。

#### Phase 3：大美丽 库化落地（8~16 周）⏳ 待启动
- `beauty-core`：沉淀策略模型、参数协议、状态机与能力契约。
- `:engines:beauty-engine`：实现 大美丽 引擎适配并对接能力契约。
- App 侧改为消费者模式：仅通过稳定 API 接入，避免直接依赖引擎实现。

#### 跨阶段验收标准
- M1：P0 自动化真实断言通过率 100%，关键链路可无人值守回归。✅
- M2：核心模块完成边界收敛，domain 层无跨层污染。✅
- M3：大美丽 完成接口化接入，具备独立版本演进能力。⏳

---

## 5. 性能指标与监控

### 5.1 核心指标

| 指标 | 目标值 | 测量方法 |
|------|--------|----------|
| 预览帧率 | ≥ 30fps，理想 60fps | 每秒帧计数 |
| 处理延迟 | ≤ 16ms | 单帧处理耗时 |
| 参数响应延迟 | < 100ms | 参数变更到画面变化 |
| 内存占用 | < 30MB | Android Profiler |
| 启动时间 | < 500ms | 相机开启到预览显示 |

### 5.2 性能告警

```kotlin
// 当 FPS < 25 或处理耗时 > 20ms 时发出告警
if (fps < 25 || processingMs > 20) {
    Log.w("PoLang:BeautyEngine", "Performance warning: fps=$fps, processing=${processingMs}ms")
}
```

### 5.3 性能分级目标（设备分级）

| 设备等级 | 代表机型 | 目标帧率 | 分辨率策略 |
|----------|----------|----------|------------|
| 低端机 | 骁龙 660 | ≥ 30fps | 720p 输入，可降至 480p |
| 中端机 | 骁龙 778G | ≥ 50fps | 1080p 输入 |
| 高端机 | 骁龙 8 Gen2 | ≥ 55fps | 1080p/2K 输入 |

---

## 6. 降级与恢复策略（当前实现）

### 6.1 自动回退触发点

- `GlBeautyPreviewProvider.initialize()` 抛出异常。
- `createPreviewSurface()` 在重试窗口内仍未拿到可用 Surface（120 次 × 30ms = 3.6s）。
- 预览 warm-up 期间 Provider 绑定失败，或超过 `PROVIDER_VIEW_BIND_TIMEOUT_MS`。

### 6.2 回退执行链路

```kotlin
// CameraRuntimeState.rememberGlRecoveryState
val onGlWarmUpFallback: (String) -> Unit = { reason ->
    BeautyEngineRuntimeState.markGlEngineFallback(reason)
    if (!persistedFallback) {
        persistedFallback = true
        persistedFallbackReason = reason
        coroutineScope.launch {
            userPreferencesRepository.persistGlEngineFallback(BIG_BEAUTY_RECOVERY_COOLDOWN_MS)
        }
    }
}
```

- 回退状态写入 DataStore，包含恢复时间戳 `gl_engine_recovery_available_at_ms`。
- 冷却窗口结束后自动触发 `triggerManualGlEngineRecovery()` 重试主引擎。
- 当前冷却窗口：`BIG_BEAUTY_RECOVERY_COOLDOWN_MS = 3 * 60 * 1000L`（3 分钟）。

### 6.3 持久化冷却与自动恢复

```kotlin
// UserPreferencesRepository
suspend fun persistGlEngineFallback(cooldownMs: Long) {
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = now + cooldownMs
}

suspend fun triggerManualGlEngineRecovery() {
    preferences[GL_ENGINE_RECOVERY_AVAILABLE_AT_MS] = 0L
}
```

> 当前实现仅持久化冷却时间，不再写入任何已删除的旧兜底引擎状态。

### 6.4 相机绑定自愈（2026-08-20 新增）

针对「翻转后预览冻结 + 拍照静默失败」问题（根因：rebind 完成后 `isActivePage` 异常翻 false 触发 `unbindAll`，Compose 侧残留的 `ImageCapture` 指向已解绑实例，拍照抛 `Not bound to a valid Camera` 被吞），相机绑定链路增加三层自愈：

- **stale 引用清零**：解绑（非活跃页 `unbindAll`）或绑定失败时立即将 `imageCapture`/`cameraControl` 置空，拍照走 fail-fast 分支而非带陈旧实例执行
- **自愈看门狗**：页面活跃且状态机处于 `Previewing` 但相机未绑定时，1.5s 间隔触发重绑，上限 4 次（`Logger.e` 常驻可见，不受日志模块开关影响）
- **交互覆盖**：用户点击快门/翻转且相机未绑定时置 `interactionRebindOverride`，强制走绑定分支——覆盖 `isActivePage` 卡 false 的极端场景；override 仅在该标记恢复 true 时复位，避免「绑定→复位→解绑」死循环

实现细节与约束见 `androidApp/src/main/java/com/mamba/picme/features/camera/AGENTS.md` §2.1「绑定生命周期与自愈」。

---

## 7. 测试与验收

### 7.1 功能测试

| 测试项 | 预期结果 |
|--------|----------|
| 打开相机 | 预览画面正常显示，无黑屏 |
| 调节磨皮 | 画面实时变化，延迟 < 100ms |
| 调节美白 | 画面实时变化，延迟 < 100ms |
| 调节瘦脸 | 画面实时变化，延迟 < 100ms |
| 调节大眼 | 画面实时变化，延迟 < 100ms |
| 调节唇色 | 唇部精准染色，轮廓自然 |
| 调节腮红 | 双颊自然着色，无侵入眼周/鼻梁 |
| 拍照保存 | 照片包含美颜效果 |

### 7.2 性能测试（大美丽）

| 测试项 | 目标值 | 测试机型 |
|--------|--------|----------|
| 预览帧率 | ≥ 30fps | 低端（骁龙 660） |
| 预览帧率 | ≥ 50fps | 中端（骁龙 778G） |
| 预览帧率 | ≥ 55fps | 高端（骁龙 8 Gen2） |
| 内存占用 | < 30MB | 所有机型 |
| 启动时间 | < 500ms | 所有机型 |

### 7.3 兼容性测试

- Android 8.0+（API 26+）
- 主流品牌：小米、华为、OPPO、vivo、三星
- 不同分辨率：720p、1080p、2K
- 不同摄像头：前置、后置、广角

### 7.4 QA 与回归检查

性能基线与历史 trace 见 `docs/06-QA/PERFORMANCE_BASELINE_REPORT.md`（独立的 QA 验收清单已于 ADR-011 退役）。

---

## 8. 风险与应对

### 风险 1：设备兼容性

**风险**：部分设备 OpenGL ES 实现有 Bug

**应对**：
- 建立"兼容性问题库"，记录已知问题
- 针对特定设备禁用高级特性
- 大美丽 warm-up 失败通过 `onGlWarmUpFallback` 上报，触发冷却重试链路

### 风险 2：性能不达标

**风险**：低端设备无法达到 30fps

**应对**：
- 分辨率自适应（720p → 480p）
- 美颜强度自适应（降低磨皮强度）
- 提供开关让用户选择

### 风险 3：内存溢出

**风险**：纹理资源未及时释放

**应对**：
- 严格的生命周期管理
- 使用 `WeakReference` 避免内存泄漏
- 定期内存分析

---

## 12. 相关文档与实现入口

- `PRODUCT.md` — 产品需求规格说明书（大美丽 产品策略）
- `FEATURES.md` — 功能交互规范（重点：`1.3.5` 大美丽 性能与验收）
- `AGENTS.md` — AI Agent 操作规范
- 「[9. 相机预览与比例策略](#9-相机预览与比例策略)」 — 相机预览与坐标系统规范
- `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/api/` — 对外稳定 API（`BeautyParams`、`BeautyPreviewProvider`、`BeautyPreviewCapability`、`BeautyPreviewEngine`）
- `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/` — GL 渲染管线核心实现
- `androidApp/src/main/java/com/mamba/picme/features/camera/CameraScreen.kt` — 预览绑定、容灾回退与调试浮层
- `androidApp/src/main/java/com/mamba/picme/features/camera/CameraPreviewStrategies.kt` — 引擎策略路由

---



---

## 9. 相机预览与比例策略

**最后更新**：2026-04（按预览策略重构对齐）
**状态**：生产稳定版（策略化预览链路：大美丽 Provider + PreviewView 兜底）

---

### 1. 核心解决方案

#### 1.1 核心原则
**采用策略化预览绑定（大美丽 Provider + PreviewView 兜底）**，并在 `PreviewView` 路径下使用 `ScaleType` 处理比例。

> 说明：本指南聚焦预览层的比例与坐标问题；当前实现仅保留 `GlBeautyPreviewStrategy`（BIG_BEAUTY）单策略。GL 引擎的 `SurfaceView + Provider` 初始化、容灾回退与恢复链路详见本文档正文。

#### 1.2 PreviewView 路径技术方案（兜底与通用预览）

##### PreviewView 配置
```kotlin
val previewView = remember {
    PreviewView(context).apply {
        // [关键配置] 根据比例模式设置 ScaleType
        scaleType = if (aspectRatio == AspectRatio.RATIO_FULL) {
            PreviewView.ScaleType.FILL_CENTER  // FULL 模式：裁剪填充，铺满屏幕
        } else {
            PreviewView.ScaleType.FIT_CENTER   // 其他模式：保持比例，可能有黑边
        }
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
}
```

##### 动态调整 ScaleType
```kotlin
LaunchedEffect(aspectRatio) {
    previewView.scaleType = if (aspectRatio == AspectRatio.RATIO_FULL) {
        PreviewView.ScaleType.FILL_CENTER
    } else {
        PreviewView.ScaleType.FIT_CENTER
    }
}
```

##### 拍照与预览比例同步（ViewPort + UseCaseGroup）
```kotlin
val screenWidth = context.resources.displayMetrics.widthPixels
val screenHeight = context.resources.displayMetrics.heightPixels

// 为 FULL 模式配置 ViewPort
val viewPort = ViewPort.Builder(
    Rational(screenWidth, screenHeight),
    preview.targetRotation
).build()

val useCaseGroup = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(imageCapture)
    .setViewPort(viewPort)
    .build()

cameraProvider.bindToLifecycle(
    lifecycleOwner,
    cameraSelector,
    useCaseGroup
)
```

##### 手动裁剪 Bitmap（必须）
```kotlin
// 在 ImageProcessor.onCaptureSuccess 中
val cropRect = image.cropRect
val originalBitmap = image.toBitmap()
val croppedBitmap = if (cropRect.width() != originalBitmap.width ||
                         cropRect.height() != originalBitmap.height) {
    Bitmap.createBitmap(
        originalBitmap,
        cropRect.left,
        cropRect.top,
        cropRect.width(),
        cropRect.height()
    )
} else {
    originalBitmap
}
```

---

### 2. 技术原理

#### 2.1 相机传感器物理特性

##### 传感器方向
手机竖屏握持时，传感器为**横向放置**（864×480 原始输出）。

**关键事实**：
- 相机传感器**永远横向放置**（width > height）
- 输出的原始帧永远是**横向分辨率**
- FULL 模式典型输出：**864 x 480**（宽高比 1.8:1）

##### 不同模式的传感器输出

| 模式 | 传感器输出 | 宽高比 | 说明 |
|------|-----------|--------|------|
| **4:3** | 640 x 480 | 1.33 (4:3) | 标准照片模式 |
| **16:9** | 864 x 480 | 1.8 (≈16:9) | 宽屏模式 |
| **FULL** | 864 x 480 | 1.8 | 传感器最大输出，需裁剪到屏幕比例 |

#### 2.2 CameraX 的旋转机制

##### 自动旋转流程

![传感器方向旋转链](assets/diagrams/beauty-sensor-rotation.png)

**旋转规则**：
- 后置摄像头：顺时针旋转 **90°**
- 前置摄像头：顺时针旋转 **270°**
- 竖屏显示时：宽高交换（480 x 864）

#### 2.3 PreviewView ScaleType 工作原理

##### FIT_CENTER（保持比例）
**contain 模式**：预览画面按 480×864 完整显示，上下留黑边。

**适用场景**：4:3、16:9 模式
**特点**：
- 预览画面完整显示
- 可能有黑边（letterbox）
- 所见即所得

##### FILL_CENTER（裁剪填充）
**crop 模式**：裁剪掉顶部与底部，预览画面铺满全屏。

**适用场景**：FULL 模式
**特点**：
- 预览画面铺满全屏
- 边缘会被裁剪
- 需配合 ViewPort 确保拍照与预览一致

---

### 3. 坐标系统与人脸跟踪（重构对齐）

#### 3.1 当前实现的转换模型

当前实现不再依赖 `PreviewView.getImageTransform()`，而是使用统一函数：

- `transformFaceCoordinateSimple(...)`（分析链路）
- `transformFaceCoordinate(...)`（屏幕绘制链路）

两者都遵循同一套四步法：

1. **归一化**：按旋转后的宽高将人脸点位映射到 `0~1`
2. **镜像补偿**：前置摄像头执行 `x = 1 - x`
3. **旋转补偿**：根据 `rotationDegrees` 做方向修正
4. **像素映射**：乘以 `previewWidth/previewHeight` 得到屏幕坐标

#### 3.2 当前代码实现（简化版）

```kotlin
internal fun transformFaceCoordinateSimple(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewWidth: Float,
    previewHeight: Float,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1f - normX else normX

    val (adjustedX, adjustedY) = when (rotationDegrees) {
        180 -> Pair(1f - mirroredX, 1f - normY)
        else -> Pair(mirroredX, normY)
    }

    return Offset(adjustedX * previewWidth, adjustedY * previewHeight)
}
```

#### 3.3 重构后注意事项

- `rotationDegrees=90/270` 时先交换 `imageProxy` 的宽高再归一化。
- 前置镜像与旋转补偿顺序不可颠倒。
- 该链路服务于十字星绘制与 `FaceWarpParams`，两者必须共用同一转换逻辑。
- 调试日志固定输出 `Step1~Step4`，用于回归比对坐标偏移。

---

### 4. 最佳实践

#### 4.1 比例选择器实现
```kotlin
@Composable
fun RatioSelector(
    currentRatio: AspectRatio,
    onRatioChange: (AspectRatio) -> Unit
) {
    val ratios = listOf(
        AspectRatio.RATIO_4_3 to "4:3",
        AspectRatio.RATIO_16_9 to "16:9",
        AspectRatio.RATIO_FULL to "FULL"
    )

    Row {
        ratios.forEach { (ratio, label) ->
            Button(onClick = { onRatioChange(ratio) }) {
                Text(label)
            }
        }
    }
}
```

#### 4.2 PreviewView 生命周期管理
```kotlin
DisposableEffect(previewView) {
    onDispose {
        previewView.releasePointerCapture()
    }
}
```

#### 4.3 性能优化
- 使用 `ImplementationMode.COMPATIBLE` 确保兼容性
- 避免频繁切换 `ScaleType`
- 坐标转换缓存 `Matrix` 对象
- 大美丽模式下使用 `Preview` UseCase，零拷贝直连 OES 纹理

---

### 5. 常见问题

#### 问题 1：预览画面拉伸变形
**原因**：使用了错误的 ScaleType
**解决**：FULL 模式使用 `FILL_CENTER`，其他模式使用 `FIT_CENTER`

#### 问题 2：拍照比例与预览不一致
**原因**：未配置 ViewPort 或未手动裁剪 Bitmap
**解决**：使用 `UseCaseGroup` + `ViewPort`，并在 `ImageProcessor` 中手动裁剪

#### 问题 3：人脸跟踪十字星位置偏移
**原因**：归一化宽高、前置镜像或旋转补偿顺序不一致
**解决**：统一走 `transformFaceCoordinateSimple()` / `transformFaceCoordinate()` 四步法，并核对 `Step1~Step4` 日志

#### 问题 4：FULL 模式黑边
**原因**：传感器比例与屏幕比例不匹配
**解决**：使用 `FILL_CENTER` + ViewPort 裁剪

---

---

## 10. 帧同步美妆系统

> **版本**: 1.0  
> **状态**: 生效中  
> **最后更新**: 2026-07-06  
> **维护者**: RD Agent  


**版本**：1.1
**状态**：🔄 部分实现（核心组件 FrameSyncManager / MotionTracker / FrameSyncBridge 已落地；预测补偿算法与 hide 降级策略待收尾）
> ⚠️ **审计备注（2026-06）**：`DetectionQueue` 和 `FaceDetectionWorker` 为**设计期概念**，从未实际落地（对应 .kt 文件不存在）。当前使用同步检测路径（CameraFrameAnalyzer 直接调用 faceDetector.detect()）。本文档 Section 3.2 及 10(代码变更清单) 中关于 DetectionQueue 的内容均为设计方案，非已落地代码。
**依赖**：`BIG_BEAUTY_TECH_SPEC.md`（已落地）
**最后更新**：2026-05-24

---

### 1. 架构目标

将当前"异步松散耦合"的人脸检测-渲染链路，升级为"准同步帧匹配"架构：

- **精确对齐**：渲染帧使用对应相机帧的人脸检测结果
- **可预测**：检测缺失时，基于运动轨迹预测补偿
- **可降级**：预测不可信时，隐藏妆容而非错误渲染
- **零侵入**：`FaceMakeupPass` 等下游组件只消费同步后的数据，不感知同步逻辑

---

### 2. 系统架构

#### 2.1 整体数据流

![帧同步美妆体系](assets/diagrams/beauty-framesync.png)

#### 2.2 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| FrameId 生成位置 | `SurfaceTexture.updateTexImage()` 时 | 与相机帧严格绑定，避免多线程竞争 |
| 队列存储位置 | CPU 侧（非 GL 线程） | 避免阻塞渲染，检测线程直接写 |
| 预测计算位置 | CPU 侧，`FrameSyncManager` | GPU 只做渲染，CPU 做轻量预测 |
| 同步结果传递 | 拷贝到 `FaceMakeupPass` writeBuffer | 保持现有双缓冲机制，替换数据来源 |
| 严格缺失阈值 | 3 帧（默认，可配置） | 60fps 下约 50ms，用户无感知 |

---

### 3. 核心组件设计

#### 3.1 FrameId 体系

```kotlin
/**
 * 全局帧标识符
 * - 单调递增，从 1 开始
 * - 与相机帧生命周期绑定
 */
@JvmInline
value class FrameId(val value: Long) : Comparable<FrameId> {
    companion object {
        val INVALID = FrameId(0L)
        private val counter = AtomicLong(0L)
        fun next(): FrameId = FrameId(counter.incrementAndGet())
    }
    override fun compareTo(other: FrameId): Int = value.compareTo(other.value)
}
```

**绑定点**：

```kotlin
// CameraPreviewRenderer 渲染循环
surfaceTexture?.updateTexImage()
val currentFrameId = FrameId.next()  // ← 帧ID在此生成

// 1. 将帧ID与SurfaceTexture当前帧绑定
frameSyncManager.bindFrameId(currentFrameId, surfaceTextureTimestamp)

// 2. 查询该帧对应的人脸同步结果
val syncResult = frameSyncManager.query(currentFrameId)

// 3. 将同步结果传递给 BeautyRenderer
beautyRenderer.applyFrameSyncResult(syncResult)
```

#### 3.2 DetectionQueue（检测输入队列）

> **⚠️ 设计概念（未落地）**：以下 DetectionQueue 为设计方案，当前代码库中 `DetectionQueue.kt` 不存在。同步检测路径仍在 CameraFrameAnalyzer 中直接调用 `faceDetector.detect()`。保留本节供未来异步检测改造参考。

```kotlin
/**
 * 带帧ID的检测任务队列
 * - 深度限制：2（防止检测线程积压）
 * - 超时策略：任务入队后 > 200ms 未消费则丢弃
 */
class DetectionQueue(
    private val maxDepth: Int = 2,
    private val timeoutMs: Long = 200L
) {
    data class Task(
        val frameId: FrameId,
        val bitmap: Bitmap,
        val rotationDegrees: Int,
        val lensFacing: Int,
        val enqueueTimeMs: Long
    )

    private val queue = ArrayBlockingQueue<Task>(maxDepth)

    fun offer(task: Task): Boolean {
        // 队列满时丢弃最旧的任务
        if (queue.remainingCapacity() == 0) {
            queue.poll()
        }
        return queue.offer(task)
    }

    fun poll(): Task? {
        val task = queue.poll() ?: return null
        // 超时丢弃
        return if (SystemClock.elapsedRealtime() - task.enqueueTimeMs > timeoutMs) {
            task.bitmap.recycle()
            null
        } else {
            task
        }
    }
}
```

**检测线程改造**：

> **⚠️ 设计概念（未落地）**：以下检测线程改造为设计方案，当前仍使用同步检测路径。

```kotlin
// FaceDetectorManager.detect() 改为消费队列
detectionThread = Thread {
    while (isRunning) {
        val task = detectionQueue.poll() ?: continue
        
        // 执行检测，结果携带 FrameId
        val result = detectInternal(task.bitmap, task.rotationDegrees, task.lensFacing)
        
        result?.let {
            frameSyncManager.storeResult(
                FrameSyncResult(
                    frameId = task.frameId,
                    landmarks106 = it.landmarks,
                    detectionSource = it.source,
                    detectionLatencyMs = SystemClock.elapsedRealtime() - task.enqueueTimeMs
                )
            )
        }
        
        task.bitmap.recycle()
    }
}.apply { name = "FaceDetectionWorker" }
```

#### 3.3 FrameSyncManager（时序对齐核心）

```kotlin
/**
 * 帧同步管理器
 * - 线程安全：ResultStore 使用 ConcurrentHashMap + 环形缓冲区
 * - 轻量级：查询操作 O(1)，无锁读（使用 volatile + copy-on-write）
 */
class FrameSyncManager(
    private val config: FrameSyncConfig = FrameSyncConfig.DEFAULT
) {
    data class FrameSyncConfig(
        val maxStoredResults: Int = 10,        // 保留最近 10 个检测结果
        val missingThresholdFrames: Int = 3,    // 缺失 3 帧后隐藏妆容
        val predictionMaxRatio: Float = 1.5f,   // 预测位移不超过上一帧 150%
        val syncMode: SyncMode = SyncMode.STRICT
    ) {
        companion object {
            val DEFAULT = FrameSyncConfig()
        }
    }

    enum class SyncMode {
        STRICT,      // 精确匹配 + 缺失隐藏
        SMOOTH,      // 历史回退 + 预测补偿
        OFF          // 关闭帧同步（保持当前行为）
    }

    data class FrameSyncResult(
        val frameId: FrameId = FrameId.INVALID,
        val landmarks106: FloatArray? = null,
        val detectionSource: FaceDetectionSource = FaceDetectionSource.NONE,
        val syncStatus: SyncStatus = SyncStatus.MISSING,
        val detectionLatencyMs: Long = 0L,
        val predictedOffsetPx: Float = 0f
    )

    enum class SyncStatus {
        EXACT_MATCH,      // 精确匹配
        HISTORICAL_FALLBACK, // 历史回退（使用最近旧结果）
        PREDICTED,        // 预测补偿
        MISSING           // 缺失（无可用结果）
    }

    // ─── 内部存储 ───
    private val resultStore = ConcurrentHashMap<FrameId, DetectionResult>()
    private val frameHistory = ConcurrentLinkedQueue<FrameId>()
    private val motionTracker = MotionTracker()

    // ─── 公共 API ───

    /**
     * 绑定当前渲染帧的 FrameId（由渲染线程调用）
     */
    fun bindFrameId(frameId: FrameId, timestampNs: Long) {
        // 记录帧时间戳，用于计算检测延迟
    }

    /**
     * 存储检测结果（由检测线程调用）
     */
    fun storeResult(result: DetectionResult) {
        resultStore[result.frameId] = result
        frameHistory.offer(result.frameId)
        motionTracker.update(result.frameId, result.landmarks106)
        trimOldResults()
    }

    /**
     * 查询帧同步结果（由渲染线程调用，每帧一次）
     */
    fun query(currentFrameId: FrameId): FrameSyncResult {
        if (config.syncMode == SyncMode.OFF) {
            return FrameSyncResult(syncStatus = SyncStatus.MISSING)
        }

        // 1. 精确匹配
        resultStore[currentFrameId]?.let {
            return FrameSyncResult(
                frameId = currentFrameId,
                landmarks106 = it.landmarks106,
                detectionSource = it.detectionSource,
                syncStatus = SyncStatus.EXACT_MATCH,
                detectionLatencyMs = it.detectionLatencyMs
            )
        }

        // 2. 查找最近历史结果
        val historicalResult = findNearestHistoricalResult(currentFrameId)
            ?: return FrameSyncResult(syncStatus = SyncStatus.MISSING)

        val frameDiff = currentFrameId.value - historicalResult.frameId.value

        // 3. 严格模式：超过阈值直接隐藏
        if (config.syncMode == SyncMode.STRICT && frameDiff > config.missingThresholdFrames) {
            return FrameSyncResult(syncStatus = SyncStatus.MISSING)
        }

        // 4. 平滑模式：预测补偿
        if (config.syncMode == SyncMode.SMOOTH) {
            val predicted = motionTracker.predict(
                fromFrameId = historicalResult.frameId,
                toFrameId = currentFrameId,
                maxRatio = config.predictionMaxRatio
            )
            return FrameSyncResult(
                frameId = historicalResult.frameId,
                landmarks106 = predicted,
                detectionSource = historicalResult.detectionSource,
                syncStatus = SyncStatus.PREDICTED,
                detectionLatencyMs = historicalResult.detectionLatencyMs,
                predictedOffsetPx = calculateOffset(predicted, historicalResult.landmarks106)
            )
        }

        // 5. 严格模式且未超阈值：使用历史结果（无预测）
        return FrameSyncResult(
            frameId = historicalResult.frameId,
            landmarks106 = historicalResult.landmarks106,
            detectionSource = historicalResult.detectionSource,
            syncStatus = SyncStatus.HISTORICAL_FALLBACK,
            detectionLatencyMs = historicalResult.detectionLatencyMs
        )
    }

    private fun findNearestHistoricalResult(currentFrameId: FrameId): DetectionResult? {
        // 从 currentFrameId 向前查找最近的有结果的帧
        return frameHistory.asReversed()
            .firstOrNull { it <= currentFrameId && resultStore.containsKey(it) }
            ?.let { resultStore[it] }
    }

    private fun trimOldResults() {
        while (frameHistory.size > config.maxStoredResults) {
            val oldId = frameHistory.poll() ?: break
            resultStore.remove(oldId)
        }
    }
}
```

#### 3.4 MotionTracker（运动预测）

```kotlin
/**
 * 轻量级运动跟踪器
 * 基于速度外推的预测算法（Phase 1），后续可替换为 Kalman Filter
 */
class MotionTracker {
    data class FrameState(
        val frameId: FrameId,
        val landmarks106: FloatArray,
        val timestampMs: Long
    )

    private val history = ArrayDeque<FrameState>(3)  // 保留最近 3 帧

    fun update(frameId: FrameId, landmarks106: FloatArray) {
        history.addLast(FrameState(frameId, landmarks106.clone(), SystemClock.elapsedRealtime()))
        if (history.size > 3) history.removeFirst()
    }

    /**
     * 预测目标帧的人脸关键点位置
     * @return 预测后的 FloatArray(212)，如果无法预测则返回历史结果
     */
    fun predict(fromFrameId: FrameId, toFrameId: FrameId, maxRatio: Float): FloatArray {
        if (history.size < 2) {
            return history.lastOrNull()?.landmarks106 ?: FloatArray(212)
        }

        val latest = history.last()
        val previous = history[history.size - 2]

        // 计算帧间速度：velocity = (latest - previous) / (latestFrameId - previousFrameId)
        val frameDiff = (latest.frameId.value - previous.frameId.value).coerceAtLeast(1L)
        val targetDiff = (toFrameId.value - fromFrameId.value).coerceAtLeast(0L)

        val predicted = FloatArray(latest.landmarks106.size)
        for (i in latest.landmarks106.indices) {
            val velocity = (latest.landmarks106[i] - previous.landmarks106[i]) / frameDiff
            val rawPredicted = latest.landmarks106[i] + velocity * targetDiff

            // 约束：预测位移不超过上一帧位移的 maxRatio 倍
            val actualDiff = rawPredicted - latest.landmarks106[i]
            val maxDiff = kotlin.math.abs(velocity * frameDiff * maxRatio)
            val clampedDiff = actualDiff.coerceIn(-maxDiff, maxDiff)

            predicted[i] = latest.landmarks106[i] + clampedDiff
        }

        return predicted
    }
}
```

---

### 4. 渲染管线改造

#### 4.1 CameraPreviewRenderer 改造点

```kotlin
// 新增成员
private val frameSyncManager = FrameSyncManager.getInstance()
private var currentFrameId: FrameId = FrameId.INVALID

// 渲染循环改造
while (isRendering && !Thread.interrupted()) {
    if (!frameAvailable) { /* ... */ }

    surfaceTexture?.updateTexImage()
    frameAvailable = false

    // ─── 帧同步核心 ───
    currentFrameId = FrameId.next()
    val syncResult = frameSyncManager.query(currentFrameId)
    applySyncResultToRenderer(syncResult)
    // ────────────────

    beautyRenderer.onRender()
    // ...
}

private fun applySyncResultToRenderer(result: FrameSyncManager.FrameSyncResult) {
    when (result.syncStatus) {
        FrameSyncManager.SyncStatus.EXACT_MATCH,
        FrameSyncManager.SyncStatus.HISTORICAL_FALLBACK,
        FrameSyncManager.SyncStatus.PREDICTED -> {
            result.landmarks106?.let {
                // 直接更新 FaceMakeupPass 的 writeBuffer
                // 替换原有的 updateFacePoints106 路径
                beautyRenderer.updateSyncedFacePoints106(it)
            }
            beautyRenderer.setHasFace(true)
        }
        FrameSyncManager.SyncStatus.MISSING -> {
            beautyRenderer.setHasFace(false)
        }
    }

    // 调试指标透传
    latestPerfStats = latestPerfStats.copy(
        detectionLatencyMs = result.detectionLatencyMs,
        syncStatus = result.syncStatus.name,
        predictedOffsetPx = result.predictedOffsetPx
    )
}
```

#### 4.2 BeautyRenderer 新增接口

```kotlin
class BeautyRenderer(private val context: Context) : GLRenderer() {
    // 新增：接收帧同步后的 106 点
    fun updateSyncedFacePoints106(landmarks106: FloatArray) {
        // 直接透传给 FaceMakeupPass，跳过旧的插值路径
        faceMakeupPass.updateFaceLandmarksSynced(landmarks106)
    }

    fun setHasFace(hasFace: Boolean) {
        this.hasFace = if (hasFace) 1f else 0f
    }
}
```

#### 4.3 FaceMakeupPass 改造

```kotlin
class FaceMakeupPass(private val context: Context) {
    // 新增：帧同步入口（替换旧的双缓冲插值路径）
    fun updateFaceLandmarksSynced(landmarks106: FloatArray) {
        synchronized(bufferLock) {
            // 直接写入 writeBuffer，不再做时间插值
            // 帧同步已由 FrameSyncManager 完成
            writeBuffer.clear()
            writeBuffer.put(landmarks106)
            writeBuffer.flip()
            hasNewLandmarks = true
        }
    }

    // 保留旧接口用于降级模式
    fun updateFaceLandmarks(landmarks106: FloatArray) { /* ... */ }
}
```

---

### 5. 拍照与录制链路

#### 5.1 拍照后处理（PhotoProcessorImpl）

拍照为单帧场景，帧同步退化为"有无人脸判断"：

```kotlin
fun processPhoto(imageProxy: ImageProxy): Bitmap {
    val bitmap = imageProxy.toBitmap()
    val frameId = FrameId.next()

    // 同步检测（拍照场景允许阻塞）
    val detectionResult = faceDetector.detectPhoto(bitmap, lensFacing)

    return if (detectionResult != null) {
        // 有人脸：正常渲染妆容
        frameSyncManager.storeResult(
            DetectionResult(frameId, detectionResult.landmarks, detectionResult.source)
        )
        gpuRenderer.renderWithSync(frameId)
    } else {
        // 无人脸：跳过妆容 Pass
        gpuRenderer.renderWithoutMakeup(bitmap)
    }
}
```

#### 5.2 视频录制

视频录制复用预览同一套渲染管线，`CameraPreviewRenderer` 的帧同步逻辑自动覆盖录制输出。

**关键约束**：录制帧率固定 30fps，渲染到 `recordingWindowSurface` 时必须与预览帧使用相同的 `FrameSyncResult`。

---

### 6. 性能指标与监控

#### 6.1 新增性能指标

```kotlin
data class BeautyPerfStats(
    val fps: Float = 0f,
    val processingMs: Int = 0,
    val delayMs: Int = 0,
    val cpuUsage: Float = 0f,
    val nullFrames: Int = 0,
    val errorCategory: String = "",
    val errorReason: String = "",
    // ─── 帧同步新增 ───
    val detectionLatencyMs: Long = 0L,      // 检测滞后时间
    val syncStatus: String = "",            // 同步状态
    val predictedOffsetPx: Float = 0f       // 预测补偿像素量
)
```

#### 6.2 调试浮层展示

调试 HUD 实际显示：`FPS: 58.3 | GPU: 4.2ms | Latency: 45ms | Sync: PRED`（新增）`| Offset: 3.2px | Face: ✓`（新增）

#### 6.3 日志字段

```
[FrameSync] frameId=1024, status=EXACT_MATCH, latency=32ms, source=MEDIAPIPE
[FrameSync] frameId=1025, status=PREDICTED, latency=48ms, offset=5.1px, framesSinceDetection=2
[FrameSync] frameId=1026, status=MISSING, hidden=true, framesSinceDetection=4
```

---

### 7. 线程安全模型

| 组件 | 所属线程 | 线程安全策略 |
|------|----------|-------------|
| `FrameId.next()` | 渲染线程 | AtomicLong，无锁 |
| `FrameSyncManager.bindFrameId()` | 渲染线程 | 无需同步 |
| `FrameSyncManager.storeResult()` | 检测线程 | ConcurrentHashMap.put |
| `FrameSyncManager.query()` | 渲染线程 | volatile + copy-on-write |
| `MotionTracker.update()` | 检测线程 | synchronized (history) |
| `MotionTracker.predict()` | 渲染线程 | synchronized (history) |

**关键保证**：
- `query()` 与 `storeResult()` 可并发执行，无需互斥
- `MotionTracker` 的 `update` 与 `predict` 需互斥（synchronized）
- 渲染线程每帧只读，检测线程只写，无死锁风险

---

### 8. 风险与降级策略

#### 8.1 风险评估

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 检测队列积压 | 中 | 检测延迟增加 | 队列深度限制 + 超时丢弃 |
| 预测算法不稳定 | 低 | 妆容抖动 | 位移约束 + 可关闭预测 |
| 内存泄漏（Bitmap） | 低 | OOM | DetectionQueue 超时自动 recycle |
| 低端机性能 | 中 | 帧率下降 | 支持关闭帧同步（SyncMode.OFF） |

#### 8.2 降级路径

![帧同步降级路径](assets/diagrams/beauty-degradation-paths.png)

---

### 9. 实现顺序（建议）

#### Step 1：FrameId 体系（1 天）
- 定义 `FrameId` value class
- 在 `CameraPreviewRenderer` 中生成并传递

#### Step 2：FrameSyncManager 骨架（2 天）
- 实现 `ResultStore` + `MatchEngine`
- 实现 `query()` 的精确匹配 + 历史回退 + 缺失隐藏
- 单元测试覆盖

#### Step 3：检测线程改造（2 天）
- `DetectionQueue` 实现
- `FaceDetectorManager` 改为消费队列
- 检测结果携带 FrameId

#### Step 4：渲染管线对接（1 天）
- `CameraPreviewRenderer` 调用 `query()`
- `BeautyRenderer` 新增 `updateSyncedFacePoints106()`
- `FaceMakeupPass` 新增 `updateFaceLandmarksSynced()`

#### Step 5：预测补偿（2 天）
- `MotionTracker` 实现速度外推
- 位移约束 + 参数调优

#### Step 6：调试与验收（2 天）
- 调试浮层指标接入
- 多机型真机测试
- A/B 对比（开启/关闭帧同步）

---

### 10. 代码变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `engines/beauty-engine/.../FrameId.kt` | 新增 | 全局帧 ID |
| `engines/beauty-engine/.../FrameSyncManager.kt` | 新增 | 时序对齐核心 |
| `engines/beauty-engine/.../MotionTracker.kt` | 新增 | 运动预测 |
| `engines/beauty-engine/.../DetectionQueue.kt` | 新增（⏳ 设计中，未落地） | 检测任务队列 |
| `CameraPreviewRenderer.kt` | 修改 | 集成 FrameSyncManager |
| `BeautyRenderer.kt` | 修改 | 新增同步接口 |
| `FaceMakeupPass.kt` | 修改 | 新增同步入口 |
| `FaceDetectorManager.kt` | 修改（⏳ 设计中，未落地） | 改为消费队列 |
| `BeautyPerfStats.kt` | 修改 | 新增帧同步指标 |
| `GlBeautyPreviewProvider.kt` | 修改 | 透传帧同步配置 |

---

---

## 11. 容灾降级与恢复

> **定位**：跨模块容灾兜底的单一事实来源（SSOT）。
> 
> 本节统一说明 `beauty-engine`（大美丽）初始化失败或运行异常时的回退策略、状态记录与恢复机制。

**最后更新**：2026-05-01（同步多 Pass 渲染现状、PreviewView 容灾路径与可观测性说明）

---

### 1. 引擎策略概览

PoLang 当前引擎策略如下：

| 引擎 | 状态 | 职责 | 实现类 | 所在模块 |
|------|------|------|--------|----------|
| **大美丽 (`BIG_BEAUTY`)** | ✅ 唯一引擎 | 自研 OpenGL ES + EGL 管线；当前基础美颜走主 Shader，磨皮/美白/几何美型/妆容按需走多 Pass GPU 链路 | `GlBeautyPreviewProvider` | `:engines:beauty-engine` |

> **重要说明**：当前项目为单引擎架构。大美丽初始化失败后，系统将使用 `PreviewView` 进行无美颜预览，并通过冷却窗口机制在下次启动时自动重试。

---

### 2. 故障回退流程

#### 2.1 初始化阶段回退（大美丽 warm-up 失败）

在 `:androidApp` 模块的相机预览链路（`CameraPreviewStrategies.kt`）中，按以下流程处理初始化失败：

1. 相机绑定时触发大美丽 warm-up（`GlBeautyPreviewProvider.initialize()`）。
2. 若 `initialize()` 抛出异常（如 GLES 不支持、Shader 编译失败、EGL 上下文创建失败）：
   - 调用 `onGlWarmUpFallback(reason)` 收敛回退逻辑；
   - 调用 `BeautyEngineRuntimeState.markGlEngineFallback(reason)` 记录回退原因与冷却时间；
   - 切换至 `useProviderRenderView = false`，使用 CameraX 原生 `PreviewView` 继续预览；
   - 仅持久化 `gl_engine_recovery_available_at_ms` 冷却窗口，不再写入任何已删除的旧兜底引擎状态；
   - 输出 `PoLang:Camera` 级别日志，确保问题可追踪。
3. 若超过 `PROVIDER_VIEW_BIND_TIMEOUT_MS` 超时仍未绑定成功，同样触发上述回退流程。

```kotlin
// CameraPreviewStrategies.kt（示意，非完整代码）
private fun onGlWarmUpFallback(reason: String) {
    BeautyEngineRuntimeState.markGlEngineFallback(reason)
    // 切换到 PreviewView
    _uiState.update { state -> state.copy(useProviderRenderView = false) }
    Logger.w("PoLang:Camera", "大美丽 warm-up failed: $reason, fallback to PreviewView")
}
```

#### 2.2 运行时异常回退

- `beauty-engine` 内部运行异常（如渲染线程崩溃、FBO 失效、妆容 Pass 渲染失败）会直接抛出。
- `BeautyRenderer` 会同步输出 `PoLang:BeautyRenderer` 分类日志，例如 `shader_compile`、`fbo_pipeline`、`texture_input`、`face_makeup`、`style_effect`。
- `CameraPreviewRenderer` 会把最近一次分类与原因聚合进 `BeautyPerfStats.errorCategory/errorReason`，供调试浮层直接展示。
- `:androidApp` 层在接收到异常后，通过 `BeautyEngineRuntimeState` 标记状态，并在下一次页面重建时回落至 `PreviewView`。
- 详细的运行时冷却与重试机制，请参阅 `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`。

---

### 3. 冷却恢复机制

`BeautyEngineRuntimeState` 是 `:androidApp` 模块中的单例对象，负责记录并消费回退原因：

- **`markGlEngineFallback(reason: String)`**：记录回退原因，并写入冷却时间戳（`gl_engine_recovery_available_at_ms`）。
- **`consumeGlEngineFallbackReason(): String?`**：消费并清空回退原因，供 UI 层展示一次性提示（如 Toast / Snackbar）。
- **冷却到期后**：自动触发 `triggerManualGlEngineRecovery()`，下次相机启动时重新尝试大美丽初始化。

**设计意图**：
- 回退原因只会被消费一次，避免重复弹窗。
- UI 层在适当时机（如相机页面 `onResume`）查询并展示降级提示文案，文案必须提取到 `strings.xml` 以支持 I18N。

---

### 4. 依赖方向约束

- `:engines:beauty-engine` 模块**不依赖** `:androidApp` 模块，也不感知外部策略的存在。
- `:engines:beauty-engine` 仅在初始化失败时抛出异常；兜底决策完全由 `:androidApp` 的相机预览策略层负责。
- 禁止 `:engines:beauty-engine` 的 `render/` 内部实现类被 `:androidApp` 直接引用；`:androidApp` 只能通过 `api/BeautyPreviewProvider` 访问能力。

---

---

## 附录 A：正向映射与反向映射（图像变形核心概念）

### A.1 概述

在图像变形（如瘦脸、大眼）实现中，**映射方向**是决定效果正确性的核心概念。

> **2026-05 更新**：GPU 离屏渲染拍照已落地，拍照路径复用同一套 Shader 管线，统一采用**反向映射（Backward Mapping）**。原 CPU Canvas 正向映射路径已废弃，仅保留作为历史参考。

大美丽引擎当前映射方式：

- **预览（Shader）**：反向映射（Backward Mapping）
- **拍照（GPU）**：反向映射（Backward Mapping）✅ 当前标准路径
- **拍照（CPU）**：正向映射（Forward Mapping）⚠️ 已废弃，仅作历史参考

### A.2 正向映射（Forward Mapping）

**定义**：从源图像的像素/顶点出发，计算它在目标图像中的新位置。

![关键点映射](assets/diagrams/beauty-landmark-mapping.png)

**特点**：
- 直接移动源像素/顶点到新位置
- 可能出现"空洞"（某些目标位置没有源像素映射过来）
- CPU `drawBitmapMesh` 使用此方式

**代码示例**（`GpuBeautyProcessor.kt` 瘦脸）：
```kotlin
// 遍历源图像的每个顶点
for (i in 0 until count) {
    val vx = orig[i * 2 + 0]  // 源顶点 X（像素坐标）
    val vy = orig[i * 2 + 1]  // 源顶点 Y（像素坐标）
    
    // 计算变形后的新位置
    val newX = vx + offsetX   // 正向：源位置 + 偏移 = 新位置
    val newY = vy + offsetY
    
    verts[i * 2 + 0] = newX
    verts[i * 2 + 1] = newY
}
```

### A.3 反向映射（Backward Mapping）

**定义**：从目标图像的像素出发，反向查找它在源图像中的对应位置。

反向查找：由**目标图像**的关键点 A′/B′ 反查**源图像**的 A/B（用于 warp 逆映射）。

**特点**：
- 遍历目标图像的每个像素
- 不会出现空洞（每个目标像素都有来源）
- GPU Shader 使用此方式（更适合并行）

**代码示例**（`warp.glsl` 瘦脸）：
```glsl
// 遍历目标图像的每个像素（通过纹理坐标 uv）
vec2 applySlimFace(vec2 uv, vec2 center, float radius, float intensity) {
    vec2 dir = uv - center;           // 从中心指向当前像素（UV单位）
    float dist = length(dir);
    if (dist >= radius) return uv;
    
    vec2 eyeAxis = normalize(uRightEye - uLeftEye);
    float percent = 1.0 - dist / radius;
    float strength = intensity * percent * percent * 0.45;
    float axisOffset = dot(dir, eyeAxis) / max(radius, 0.0001);
    vec2 offset = eyeAxis * axisOffset * strength * radius;
    
    return uv - offset;  // 反向：目标位置 - 偏移 = 源位置
}
```

### A.4 关键差异：坐标系

| 特性 | Shader UV | CPU 像素 |
|------|-----------|----------|
| **范围** | 0.0 ~ 1.0 | 0 ~ width/height |
| **单位** | 比例（归一化） | 像素 |
| **原点** | 左上角 | 左上角 |
| **Y轴方向** | 向下递增 | 向下递增 |

### A.5 瘦脸效果中的符号差异

**核心问题**：相同的数学公式，因映射方向不同，需要相反的符号。

#### 瘦脸的数学本质
瘦脸 = 将脸部像素向眼轴方向（水平）收缩

#### 正向映射（CPU）
```kotlin
// 源顶点向眼轴方向移动（脸部变窄）
verts[i] += eyeAxis * offset  // 正确：瘦脸
verts[i] -= eyeAxis * offset  // 错误：丰脸
```

#### 反向映射（Shader）
```glsl
// 目标像素从内侧采样（脸部变窄）
return uv - offset;  // 正确：瘦脸
return uv + offset;  // 错误：丰脸
```

### A.6 符号对照表

| 效果 | 正向映射（CPU） | 反向映射（Shader） |
|------|----------------|-------------------|
| **瘦脸**（向中心收缩） | `+` | `-` |
| **丰脸**（向外扩展） | `-` | `+` |

**记忆口诀**：**正向加，反向减；方向相反效果同。**

### A.7 实际修复案例

**问题**：大美丽瘦脸预览与拍照效果相反
- 预览（Shader）：正强度 → 变胖
- 拍照（CPU）：正强度 → 变瘦

**修复**（`GpuBeautyProcessor.kt`）：
```kotlin
// 修复前（与 Shader 一致，但效果相反）
verts[i * 2 + 0] -= eyeAxisX * axisOffset * str * slimRadius
verts[i * 2 + 1] -= eyeAxisY * axisOffset * str * slimRadius

// 修复后（符号反转，效果一致）
verts[i * 2 + 0] += eyeAxisX * axisOffset * str * slimRadius
verts[i * 2 + 1] += eyeAxisY * axisOffset * str * slimRadius
```

### A.8 调试技巧

1. **小幅度测试**：先用 0.1 的小强度测试方向
2. **可视化偏移**：用颜色表示偏移方向（红色=正，蓝色=负）
3. **坐标打印**：在关键位置打印坐标，对比 Shader 和 CPU 结果

### A.9 相关文件

- `androidApp/src/main/java/com/mamba/picme/core/image/GpuBeautyProcessor.kt` - 正向映射实现
- `engines/beauty-engine/src/main/assets/shaders/warp.glsl` - 反向映射实现

---

## 13. 总结

大美丽的核心是**构建一个高性能、可观测、可降级的 GPU 加速图像流处理管道**：

1. **零拷贝数据流**：CameraX → SurfaceTexture → OpenGL → SurfaceView
2. **共享上下文**：主线程初始化，渲染线程独立处理
3. **线程同步**：帧可用监听 + `sleep(1)` 轻量轮询
4. **性能监控**：通过 `BeautyPerfStats` 实时暴露 FPS、处理耗时、延迟、CPU、空帧
5. **容灾机制**：大美丽 warm-up 失败时触发 `onGlWarmUpFallback`，进入冷却并在超时后自动重试

**当前技术路线关键决策**：

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 引擎 | 自研 大美丽（OpenGL ES） | 可控性最高、零授权、全链路可观测 |
| 磨皮算法 | 双边滤波快速近似（5×5 采样核） | 保边效果好，移动端性能可接受 |
| 显示层 | SurfaceView | 直接硬件合成，延迟更低，功耗更小 |
| 人脸检测 | MediaPipe 468→106（主链路） | 与当前预览分析流一致，直接服务 `FaceWarpParams` |
| 渲染参数 | 全 uniform 实时更新 | 无需重新编译 Shader，延迟 < 100ms |

**预期结果**：

- 成功：实现 30-60fps 实时美颜预览，零授权成本，用户可无感知享受效果
- 降级：大美丽 warm-up 失败进入冷却，3 分钟后自动重试
