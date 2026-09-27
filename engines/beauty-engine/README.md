# PoLang Beauty Engine

`beauty-engine` 是 PoLang 的实时美颜预览引擎，以独立 Android Library 模块存在，长期演进为可独立发布的视觉能力基础库。

---

## 模块定位

- **能力层**：对外暴露稳定的 `api/` 接口（`BeautyPreviewEngine`、`BeautyParams`、`BeautySettings`、`FilterType`、`StyleFilter`、`Face`、`PhotoProcessor`、`FaceDetector` 等）。
- **渲染实现层**：内部封装 OpenGL ES + EGL 多 Pass 渲染管线（`render/` 包），禁止外部直接引用。
- **人脸检测层**：多引擎 ROI + Landmark 双阶段检测（`internal/facedetect/`），支持 MediaPipe / MNN 双引擎独立配置，含 Image 零拷贝路径。NCNN 路径已于 2026-07 移除。
- **帧同步系统**：独立的时序对齐机制（`internal/framesync/`），解决妆容甩飞问题，预览与录制链路共用同一套同步逻辑。
- **录制系统**：GPU 离屏渲染视频录制（`recorder/`），复用预览渲染管线。
- **性能目标**：零拷贝 GPU 数据流，单帧处理 ≤ 16ms（60fps），参数响应延迟 < 100ms，人脸检测 < 100ms（高端机）；零拷贝路径额外节省 3-5ms（跳过 YUV→ARGB CPU 转换）。
- **拍照 GPU 化**：支持 GPU 离屏渲染拍照，复用预览同一套 Shader 管线，1080p < 300ms, 4K < 800ms。

---

## 架构概览

| 子系统 | 包路径 | 技术栈 | 状态 |
|---|---|---|---|
| 大美丽渲染（BIG_BEAUTY） | `render/` | 自研 OpenGL ES + EGL | ✅ 稳定 |
| 人脸检测（FACE_DETECT） | `internal/facedetect/` | MediaPipe / MNN | ✅ 多引擎 |
| 帧同步（FRAME_SYNC） | `internal/framesync/` | 速度外推 + 时序对齐 | ✅ 稳定 |
| 视频录制（RECORDER） | `recorder/` | GPU 离屏渲染 + MediaCodec | ✅ 稳定 |

> GPUPixel 实验性模块已于 2026-05 完全移除。

---

## 包结构

```
beauty-engine/src/main/java/com/mamba/picme/beauty/
├── api/                               # 对外稳定 API（能力契约层）
│   ├── BeautySettings.kt              # 美颜设置领域模型（UI 原始值）
│   ├── BeautyParams.kt                # 美颜参数数据类（Shader 归一化值）
│   ├── BeautyParamsConverter.kt       # BeautySettings → BeautyParams 转换
│   ├── FilterType.kt                  # 色调滤镜枚举（含 ColorMatrix）
│   ├── StyleFilter.kt                 # 风格特效枚举
│   ├── Face.kt                        # 人脸数据结构（Face/FaceContour/FaceLandmark）
│   ├── BeautyProcessor.kt             # CPU 后处理接口契约
│   ├── BeautyPerfStats.kt             # 性能统计模型
│   ├── BeautyPreviewCapability.kt     # GL 能力扩展接口（FaceWarp/LipMask 等）
│   ├── BeautyPreviewProvider.kt       # 预览 Provider 基础接口
│   ├── BeautyPreviewEngine.kt         # 组合接口（Provider + Capability + getView）
│   ├── BeautyPreviewProviderFactory.kt # Factory（未来 DI 扩展点）
│   ├── FrameId.kt                     # 帧同步全局帧标识符
│   ├── FrameSyncConfig.kt             # 帧同步配置
│   ├── FrameSyncResult.kt             # 帧同步结果
│   ├── PhotoProcessor.kt              # 拍照后处理接口（GPU 离屏渲染）
│   └── facedetect/                    # 人脸检测对外 API
│       ├── FaceDetector.kt            # 人脸检测器接口
│       ├── FaceDetectorFactory.kt     # 检测器工厂
│       ├── FaceDetectionResult.kt     # 检测结果数据类
│       ├── FaceContourData.kt         # 人脸轮廓数据
│       ├── FaceWarpParams.kt          # 变形参数
│       ├── DetectionPipelineConfig.kt # 检测流水线配置（ROI/Landmark 引擎独立）
│       ├── EngineType.kt              # 引擎类型枚举
│       └── FaceDetectionSource.kt     # 检测来源枚举
├── render/                            # 大美丽渲染实现（GL 渲染管线层）
│   ├── GlBeautyPreviewProvider.kt     # Provider 接口实现
│   ├── GlBeautyPreviewProviderFactory.kt # Provider 工厂
│   ├── BeautyPreviewView.kt           # 自定义 View（SurfaceView 封装）
│   ├── CameraPreviewRenderer.kt       # 渲染管线核心（含帧同步逻辑）
│   ├── BeautyRenderer.kt              # 美颜 Shader 渲染器
│   ├── BeautyPass.kt                  # 美颜渲染 Pass
│   ├── FaceMakeupPass.kt              # 妆容渲染 Pass
│   ├── StyleEffectShader.kt           # 风格特效 Shader 管理
│   ├── BeautyShaders.kt               # GLSL Shader 源码
│   ├── ShaderProgram.kt               # Shader 编译与链接
│   ├── ShaderModuleLoader.kt          # Shader 模块加载器
│   ├── EGLCore.kt                     # EGL 上下文与 Surface 管理
│   ├── WindowSurface.kt               # EGL Window Surface 封装
│   ├── Framebuffer.kt                 # FBO 封装
│   ├── FramebufferPool.kt             # FBO 对象池
│   ├── GLRenderer.kt                  # GL 渲染器基类
│   ├── LutTextureLoader.kt            # LUT 纹理加载器
│   ├── PhotoProcessorImpl.kt          # 拍照 GPU 化处理实现
│   └── StyleEffect.kt                 # 风格特效数据类
├── internal/                          # 内部工具、帧同步与人脸检测
│   ├── BeautyShaderChain.kt           # Shader 链路辅助（已废弃，实际由 BeautyRenderer 统一管线替代）
│   ├── facedetect/                    # 人脸检测实现（多引擎）
│   │   ├── FaceDetectorManager.kt     # 检测管理器（双阶段流水线）
│   │   ├── DetectionPipelineFactory.kt # 流水线工厂
│   │   ├── Face106ToWarpParams.kt     # 106点→变形参数转换
│   │   ├── MediaPipeFaceDetector.kt   # MediaPipe 检测器
│   │   ├── MediaPipeRoiDetector.kt    # MediaPipe ROI 检测器
│   │   ├── MediaPipeLandmarkDetector.kt # MediaPipe Landmark 检测器
│   │   ├── MnnRoiDetector.kt          # MNN ROI 检测器（RetinaFace Det10G/Det500M）
│   │   ├── MnnLandmarkDetector.kt     # MNN Landmark 检测器（2D106）
│   │   ├── RoiDetector.kt             # ROI 检测器接口
│   │   ├── LandmarkDetector.kt        # Landmark 检测器接口
│   │   ├── adapter/                   # 检测适配器
│   │   └── mnn/                       # MNN JNI 桥接
│   ├── framesync/                     # 帧同步系统核心
│   │   ├── FrameSyncBridge.kt         # 线程安全 FrameId 共享
│   │   ├── FrameSyncManager.kt        # 时序对齐核心
│   │   └── MotionTracker.kt           # 速度外推预测算法
│   └── model/                         # 模型管理
│       └── ModelManager.kt            # 模型下载与缓存管理
├── recorder/                          # 视频录制
│   └── BeautyVideoRecorder.kt         # GPU 美颜视频录制器
└── engine/                            # （预留扩展）
```

**依赖方向红线**：
- `api/` 包：**禁止**依赖 `render/`、`internal/`、`androidx.camera.*`、`features.*`、`data.*` 等任何实现细节
- App 层：只允许依赖 `api/` 接口，**禁止**直接实例化 `render/` 或 `internal/` 内部类

---

## Gradle 依赖

```
dependencies {
    implementation(project(":engines:beauty-engine"))
}
```

---

## 最小初始化

### 预览美颜

```
// 1. 通过 GlBeautyPreviewProvider 获取实例（app 层通过 rememberGlBeautyPreviewProvider 管理）
val provider: BeautyPreviewEngine = GlBeautyPreviewProvider(context)

// 2. 初始化引擎（离屏 EGL + Shader 编译）
provider.initialize()

// 3. 设置输入分辨率并获取给 CameraX 的 Surface
provider.setCameraInputBufferSize(width = 1280, height = 720)
val previewSurface = provider.createPreviewSurface()
previewUseCase.setSurfaceProvider { request ->
    request.provideSurface(previewSurface, executor) { }
}

// 4. 实时更新美颜参数
provider.updateFilters(
    BeautyParams(
        enabled = true,
        smoothing = 0.3f,
        whitening = 0.2f,
        bigEyes = 0.1f,
        slimFace = 0.15f,
        lipColor = 0.4f,
        blush = 0.2f,
        filterType = FilterType.LEICA_CLASSIC,
        styleEffect = StyleFilter.NONE
    )
)
```

### 拍照 GPU 化

```
// 使用 PhotoProcessor 进行拍照后处理（GPU 离屏渲染）
val photoProcessor = (provider as GlBeautyPreviewProvider).createPhotoProcessor()

val originalBitmap = loadOriginalImage() // 从 ImageCapture 获取的原始 Bitmap
val faceData = getLatestFaceData()      // 从预览缓存获取的人脸数据

val processedBitmap = photoProcessor.process(
    bitmap = originalBitmap,
    params = BeautyParams(...),          // 与预览相同的美颜参数
    faceData = faceData
)

// 处理后的 Bitmap 保存到 MediaStore
saveToMediaStore(processedBitmap)
```

**性能指标**：
- 1080p 照片：< 300ms
- 4K 照片：< 800ms

**降级策略**：GPU 路径失败时自动回退 CPU 路径，拍照不失败。

### 帧同步系统

帧同步系统在 `CameraPreviewRenderer` 内部自动启用，无需手动配置。录制场景自动复用预览的帧同步逻辑。

**开发者选项**（调试用）：
```
// 在 CameraScreen 中配置帧同步模式
val frameSyncConfig = FrameSyncConfig(
    mode = FrameSyncMode.STRICT,           // STRICT / SMOOTH / OFF
    predictionAlgorithm = PredictionAlgorithm.VELLOCITY_EXTRAPOLATION,
    missingThreshold = 3                   // 1~10 帧
)
frameSyncManager.updateConfig(frameSyncConfig)
```

---

## 生命周期与释放

`BeautyPreviewEngine` 持有 EGL 上下文、渲染线程和 GPU 资源，**必须**在合适的生命周期节点释放：

```
// Composable 场景由 DisposableEffect 自动管理
DisposableEffect(provider) {
    onDispose { provider.release() }
}

// ViewModel 场景
override fun onCleared() {
    provider.release()
}
```

释放顺序由内部保证：`WindowSurface` → `EGL Context` → `SurfaceTexture` → `渲染线程`。

---

## 容灾降级

如果 `initialize()` 抛出异常（如设备不支持所需 GLES 版本、Shader 编译失败）：

1. `BeautyPreviewEngine` 内部不会自动回退，异常会向上抛出。
2. **调用方**（`GlBeautyPreviewStrategy`）捕获异常，降级为 CameraX `PreviewView` 直出，并通过 `onWarmUpFallback` 通知 UI 层。
3. `BeautyEngineRuntimeState` 记录回退原因，供 UI 层消费展示提示。
4. `BeautyPerfStats` 会额外暴露 `errorCategory` / `errorReason`，用于调试浮层展示最近一次 `PoLang:BeautyRenderer` 错误分类。
5. 详细的兜底策略与冷却恢复机制请参阅 `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` 容灾降级章节（原 BEAUTY_ENGINE_FALLBACK.md 已并入）。

---

## 接口稳定性

| API | 状态 | 说明 |
|-----|------|------|
| `BeautyPreviewEngine` | ✅ 稳定 | 库化核心契约（Provider + Capability + getView） |
| `BeautySettings` | ✅ 稳定 | 美颜设置领域模型，已从 app 层下沉至 api/ |
| `FilterType` / `StyleFilter` | ✅ 稳定 | 滤镜/风格枚举，已下沉至 api/ |
| `BeautyParams` | ✅ 稳定 | 新增参数默认 `0.0f`，向后兼容 |
| `PhotoProcessor` | ✅ 稳定 | 拍照 GPU 化接口（2026-05 新增） |
| `FaceDetector` / `FaceDetectionResult` | ✅ 稳定 | 人脸检测接口（2026-05 新增多引擎支持） |
| `DetectionPipelineConfig` | ✅ 稳定 | ROI/Landmark 引擎独立配置 |
| `FrameId` / `FrameSyncConfig` / `FrameSyncResult` | ⚠️ 实验性 | 帧同步系统 API，可能随优化调整 |
| `BeautyPerfStats` | ⚠️ 实验性 | 字段可能随观测需求微调 |
| 独立发布 AAR | ⏳ 未开始 | 预计 M3 完成后进入 Maven 发布流程 |

---

## 版本里程碑

| 版本 | 日期 | 关键交付 |
|------|------|----------|
| **M1** | 2026-04 | 大美丽渲染基础：磨皮/美白/瘦脸/大眼/唇色/腮红/滤镜 |
| **M2** | 2026-05 | 人脸检测重构：MediaPipe / MNN 双引擎独立配置；帧同步系统；GPU 拍照；视频录制 |
| **M3** | 2026-06 (计划) | 独立 AAR 发布；性能基线自动化；低端机自动降级策略 |

### M2 已完成项

- ✅ 多引擎 ROI + Landmark 双阶段检测（引擎类型独立配置）
- ✅ MNN OpenCL GPU 推理（RetinaFace + 2D106）+ `AtomicBoolean` CAS 非阻塞懒加载
- ✅ MNN GPU/CPU 推理（2D106 模型）
- ✅ MediaPipe TFLite GPU 推理（FaceLandmarker）+ `detect(mediaImage: Image)` 零拷贝
- ✅ 帧同步系统（FrameSyncManager + MotionTracker）
- ✅ GPU 离屏渲染拍照（PhotoProcessorImpl）
- ✅ 美颜视频录制（BeautyVideoRecorder）
- ✅ 设置页检测引擎切换（DataStore 持久化）
- ✅ C++ 层细分性能计时（preprocess/infer/nms）
- ✅ ANR 修复（异步初始化 + 懒加载）
- ✅ 移除 armeabi-v7a（仅保留 arm64-v8a，节省 54MB）
- ✅ MNN 初始化并发优化（`synchronized` → `AtomicBoolean` CAS）

### M3 待办项

- ⏳ 低端机自动降级（GPU 推理 >200ms 时自动切 MediaPipe）
- ✅ NCNN 路径移除（libncnn.so 及 JNI 桥接已清理）
- ⏳ 独立 AAR Maven 发布
- ⏳ 性能基线自动化测试（CI 集成 perf-baseline.sh）

---

## 已接入能力

| 能力 | 实现方式 | 状态 |
|---|---|---|
| 磨皮 / 美白 | 双边滤波 Shader（9pt 快速近似） | ✅ |
| 瘦脸 / 大眼 | FaceWarp 网格变形 Shader | ✅ |
| 唇色 | HSV 色相调整 + 纹理妆容三角网格 | ✅ |
| 腮红 | 双颊椭圆染色 + 纹理贴图 | ✅ |
| 专业调色 | 曝光/对比度/饱和度/色温/色调/亮度/RGB 通道 Shader | ✅ |
| 卡通 | Toon Shader（Sobel 边缘 + 颜色量化） | ✅ |
| 素描 | Sketch Shader（灰度 + Sobel + 反相） | ✅ |
| 色块化 | Posterize Shader（颜色层级量化） | ✅ |
| 浮雕 | Emboss Shader（3×3 卷积核） | ✅ |
| 交叉线 | Crosshatch Shader（基于亮度绘制交叉线） | ✅ |
| 色调滤镜 | ColorMatrix（OpenGL Shader） | ✅ |
| 人脸关键点 | MediaPipe 468→106 / MNN 2D106 | ✅ |
| **多引擎人脸检测** | MediaPipe / MNN 双引擎独立配置 + Image 零拷贝路径 | ✅ |
| **帧同步系统** | FrameSyncManager + MotionTracker 速度外推 | ✅ |
| **GPU 拍照** | PhotoProcessorImpl（离屏渲染复用预览管线） | ✅ |
| **视频录制美颜** | BeautyVideoRecorder 复用预览渲染管线 | ✅ |

---

## 人脸检测引擎性能基准

> 测试日期：2026-06-08 | 测试模型：RetinaFace det_10g (ROI) + 2D106 (Landmark)
> 零拷贝路径（2026-06 新增）：MediaPipe Image 跳过 YUV→ARGB 转换（约 5ms）

### 测试机型

| 机型 | SoC | GPU | 定位 |
|------|-----|-----|------|
| 机型 A (中端) | - | Adreno 620 | 中端设备 |
| 机型 B (高端) | - | Adreno 740+ | 旗舰设备 |

### 各引擎推理耗时对比

#### 高端机 (Adreno 740+)

| 引擎配置 | ROI | Landmark | 总检测 | 推荐度 |
|----------|-----|----------|--------|--------|
| MediaPipe + MediaPipe | ~25ms | ~5ms | ~30ms | ⭐⭐⭐⭐⭐ |
| MNN + MNN | ~340ms | ~13ms | ~350ms | ⭐⭐ (太慢) |

#### 中端机 (Adreno 620)

| 引擎配置 | ROI | Landmark | 总检测 | 推荐度 |
|----------|-----|----------|--------|--------|
| **MediaPipe + MediaPipe** | **~30ms** | **~5ms** | **~35ms** | ⭐⭐⭐⭐⭐ |
| MNN + MNN | ~550ms | - | ~550ms | ⭐⭐ (ROI 太慢) |
| MNN + MNN | ~500ms+ | - | ~500ms+ | ⭐⭐ (CPU 执行) |

### 关键结论

1. **MediaPipe 是高端机最佳方案**：TFLite GPU delegate 优化好，跨设备性能稳定；Image 零拷贝额外节省约 5ms
2. **MediaPipe 是中端机最佳方案**：TFLite GPU delegate 优化好，跨设备性能稳定；Image 零拷贝额外节省约 5ms
3. **MNN ROI 性能一般**：OpenCL 后端对 640x640 RetinaFace 优化不足，中端机 CPU fallback 耗时较长
4. **GPU 算力是主要瓶颈**：同一模型同一框架，Adreno 740+ 比 Adreno 620 快 10-20 倍
5. **零拷贝收益**：Image 路径消除 YUV→Bitmap→RGB 多重 CPU 拷贝，每帧节省 3-8ms

### 已知问题与修复

| 问题 | 现象 | 根因 | 修复方案 |
|------|------|------|----------|
| ANR (启动卡死) | Input dispatching timed out | 检测器在 `init {}` 中同步初始化 | 改为协程异步初始化 + 懒加载 |
| MNN 初始化阻塞渲染线程 | `synchronized` 导致分析线程等待 1s+ | 重型模型加载同步执行 | 切换为 `AtomicBoolean` CAS 非阻塞模式 |
| MNN 初始化时丢帧 | `onResourceManagerLoad()` 中执行推理初始化 | 回调线程被长时间占用 | 延迟到下一次 `detect()` 调用 |

---

## 常见错误排查

| 现象 | 可能原因 | 排查建议 |
|------|----------|----------|
| `initialize()` 抛出 EGL 相关异常 | 设备 GLES 版本过低或上下文创建失败 | 检查 `EGLCore` 日志，确认 `eglChooseConfig` 成功 |
| 预览黑屏 / 无画面 | `createPreviewSurface()` 未被正确绑定到 CameraX | 确认 `SurfaceRequest.provideSurface()` 已调用 |
| 参数更新不生效 | 在 `initialize()` 之前调用了 `updateFilters()` | 确保在 `isReady() == true` 后再更新参数 |
| 内存泄漏 / ANR | `release()` 未被调用或调用时机过晚 | 在 `ViewModel.onCleared()` 或 `DisposableEffect.onDispose` 中释放 |
| 帧率过低 | Shader 复杂度过高或 FBO 频繁创建 | 检查 `getPerfStats()` 中的 `processingMs` 是否 > 16ms |
| **妆容甩飞 / 滞后** | 帧同步系统未启用或配置错误 | 检查 `FrameSyncManager` 是否复用，确认 `FrameSyncBridge` 正确传递 FrameId |
| **拍照效果与预览不一致** | GPU 拍照路径失败或未使用相同参数 | 检查 `PhotoProcessorImpl` 日志，确认使用了与预览相同的 `BeautyParams` |
| **GPU 拍照耗时过长** | 图片分辨率过高或 PBO 读取阻塞 | 确认图片尺寸在合理范围（<4096），检查是否使用 PBO 双缓冲 |
| **录制视频妆容跳变** | 录制链路未复用预览帧同步逻辑 | 确认录制时使用的是同一 `CameraPreviewRenderer` 实例 |

---

## 相关文档

- `beauty-engine/AGENTS.md` — 内部实现规范与代码约束（详细）
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — 大美丽渲染链路、容灾回退、冷却恢复与观测指标
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — 跨模块容灾降级统一说明（容灾降级章节；原 08-FALLBACK/BEAUTY_ENGINE_FALLBACK.md 已并入）
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — 帧同步美妆系统已并入该文档（原 FRAME_SYNC_TECH_SPEC.md；原 QA_EXECUTION_CHECKLIST 已随 ADR-011 退役）
- `docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md` — GPU 离屏渲染拍照架构决策记录
