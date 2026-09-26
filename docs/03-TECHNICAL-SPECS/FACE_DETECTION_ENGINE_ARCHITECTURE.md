# 人脸检测引擎技术架构（2026-07）

> **重要变更（2026-07-05）**：NCNN 路径已按激进策略完全移除。当前仅保留 `MEDIAPIPE` 与 `MNN` 双引擎，代码、模型配置、设置选项、so 库及 runtime 依赖中均不再包含 NCNN。

## 1. 概述

PoLang 当前采用双引擎人脸检测架构：`MEDIAPIPE`、`MNN`。

**零拷贝优化**（2026-06 新增，2026-07 调整为 MNN 独占）：
- MediaPipe Image 路径：CameraX `ImageProxy` → `MPImage`，跳过 YUV→ARGB 转换，节省约 5ms
- MNN NV21 路径：`DirectByteBuffer` → C++ 层一体式预处理+推理，跳过 Bitmap→RGB 转换（约 3-5ms）

**并发优化**（2026-06）：MNN ROI/Landmark 检测器初始化从阻塞 `synchronized` 切换为非阻塞 `AtomicBoolean` CAS 模式，重型模型加载从 ResourceManager 回调中延迟到下一次 detect 调用，避免阻塞渲染线程。

架构目标：

- 统一输出 106 点（归一化坐标），供大美丽渲染链路直接消费
- 通过可配置流水线组合 ROI 与 Landmark 检测器，便于调优与回归
- 将检测引擎实现下沉到 `beauty-engine`，App 层只依赖 `api/facedetect` 契约
- 最小化 CPU-GPU 数据拷贝，优先使用零拷贝路径（NV21 DirectByteBuffer / CameraX ImageProxy）

## 2. 代码落点（当前实现）

### 2.1 对外契约层

`engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/facedetect/`

- `FaceDetector.kt`：统一检测接口（含 `detect(bitmap)` 和 `detectFromImage(image)` 零拷贝重载）
- `FaceDetectorFactory.kt`：创建 `FaceDetectorManager`
- `FaceDetectionResult.kt`：返回 `landmarks106 + detectionSource + roiRect`
- `EngineType.kt`：`MEDIAPIPE` / `MNN`（已移除 `NCNN` 与旧 `INSIGHTFACE`）
- `DetectionPipelineConfig.kt`：ROI 与 Landmark 组合配置；同文件内定义 `InferenceBackendType`（ONNX / MNN / TFLite）、`DevicePreference`、`RoiDetectorType`、`LandmarkDetectorType` 枚举

### 2.2 内部实现层

`engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/`

- `FaceDetectorManager.kt`：双引擎调度核心，含 `detectFromImage()` 零拷贝入口和 `detectRoiFromNv21()` 路由（MNN 后端）
- `DetectionPipelineFactory.kt`：创建 ROI/Landmark 检测器，DET10G/2D106 仅支持 MNN
- `MediaPipeFaceDetector.kt`：MediaPipe 检测入口（预览 VIDEO + 静态图 IMAGE；含 `detect(mediaImage: Image)` 零拷贝重载）
- `MediaPipeLandmarkDetector.kt`：MediaPipe Landmark 检测（含 `detectLandmarks(mediaImage: Image)` 零拷贝重载）
- `MediaPipeRoiDetector.kt`：MediaPipe ROI 检测
- `MnnRoiDetector.kt`：MNN ROI 检测（RetinaFace det_500m_mnn），`AtomicBoolean` CAS 非阻塞初始化
- `MnnLandmarkDetector.kt`：MNN Landmark 检测（2D106），`AtomicBoolean` CAS 非阻塞初始化
- `mnn/MnnFaceDetector.kt`：MNN JNI 桥接
- `adapter/FaceLandmarkAdapter.kt`：统一适配器接口
- `adapter/MediaPipe468Adapter.kt`：468 -> 106 映射
- `adapter/MnnLandmarkAdapter.kt`：MNN 原生 106 -> 统一 106 重排
- `Face106ToWarpParams.kt`：106 -> `FaceWarpParams`

### 2.3 C++ 原生层

`engines/beauty-engine/src/main/cpp/`

- `mnn_face_detector.cpp/h`：MNN RetinaFace / 2D106 检测器
- `mnn_jni_bridge.cpp`：MNN JNI 桥接
- `CMakeLists.txt`：仅链接 MNN 与 MediaPipe 相关库，NCNN 头文件/库/宏已移除

### 2.4 App 集成层

- `androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt`
  - 启动时执行 `FaceLandmarkAdapterRegistry.initDefaults()`
- `androidApp/src/main/java/com/mamba/picme/features/camera/CameraRuntimeState.kt`
  - 监听用户设置，将 ROI/Landmark 检测器类型转为 `DetectionPipelineConfig`
- `androidApp/src/main/java/com/mamba/picme/features/camera/CameraFrameAnalyzer.kt`
  - 检测入口：优先走 `detectFromImage()` MediaPipe 零拷贝路径（若配置 MediaPipe 统一管线）
  - 非 MediaPipe 路径走 `detectRoiFromNv21()` 零拷贝 ROI → Landmark 检测
  - 智能帧跳过优化（每 N 帧检测 + 运动触发重检）

## 3. 运行流程

### 3.1 预览实时检测（双路径）

#### 路径 A：MediaPipe Image 零拷贝（首选，~5ms 节省）

![MediaPipe 零拷贝路径](assets/diagrams/face-medipipe-path.png)

#### 路径 B：MNN NV21 零拷贝（ROI + Landmark 双阶段）

![MNN NV21 路径](assets/diagrams/face-mnn-path.png)

#### 路径 C：Bitmap 降级路径（Legacy）

![Bitmap 兼容路径](assets/diagrams/face-bitmap-legacy.png)

### 3.2 静态图检测（拍照后）

`FaceDetector.detectPhoto(bitmap, lensFacing)` 当前实现按配置走 `MediaPipeLandmarkDetector` 或 MNN 完整检测管线。

说明：静态图链路不会走 `FaceDetectorManager` 的 `EngineType` 分支。`detectFromImage()` 和 `detectRoiFromNv21()` 零拷贝路径目前仅用于预览实时检测。

## 4. 引擎与流水线配置

### 4.1 引擎选择（EngineType）

- `MEDIAPIPE`：统一管线，468 点 → 106 点映射（优先使用 `detectFromImage()` 零拷贝路径）
- `MNN`：可配置流水线（ROI RetinaFace + Landmark 2D106），支持 NV21 零拷贝 ROI 检测，`AtomicBoolean` CAS 非阻塞懒加载

> `NCNN` 枚举值与相关实现已于 2026-07-05 完全移除。

### 4.2 MNN 流水线组合（DetectionPipelineConfig）

> **⚠️ 审计备注（2026-07）**：原 InsightFace ONNX 路径与 NCNN 路径均已移除。当前仅 MNN 备选引擎使用独立 ROI+Landmark 配置。

ROI 检测器：

- `RoiDetectorType.MEDIAPIPE`
- `RoiDetectorType.DET10G`（RetinaFace，MNN 后端）

Landmark 检测器：

- `LandmarkDetectorType.MEDIAPIPE`
- `LandmarkDetectorType.INSIGHTFACE_2D106`（MNN 后端）

## 5. 关键映射与坐标约束

### 5.1 MediaPipe 468 -> 统一 106

由 `MediaPipe468Adapter` 实现：

- 轮廓 33 点：沿 `FACE_OVAL` 两段路径插值生成
- 非轮廓 73 点：`NON_CONTOUR_MAPPING` 固定表映射
- 前置镜头：`x = 1 - x`

参考：`docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md`

### 5.2 MNN 原生 106 -> 统一 106

由 `MnnLandmarkAdapter` 实现：使用 FULL_REMAP 对 106 点完整重排。不存在 `index -> same index` 直通点。前置镜头：`x = 1 - x`。

> **⚠️ 已移除（2026-07）**：原 `NcnnLandmarkAdapter.kt`、`NcnnRoiDetector.kt`、`NcnnLandmarkDetector.kt`、`ncnn/NcnnFaceDetector.kt` 及 C++ NCNN 实现均已删除。

## 6. 当前行为边界（避免文档误读）

### 6.1 当前没有检测引擎自动冷却回退状态机

`FaceDetectorManager` 当前行为是：

- 按 `DetectionPipelineConfig` 直接执行对应分支
- 异常时记录日志并返回 `null`
- 不维护“连续失败自动切引擎 + 冷却恢复”状态机

### 6.2 当前有以下降级行为

- MediaPipe 初始化：GPU delegate 失败会 fallback 到 CPU delegate
- MNN（RetinaFace/2D106）：OpenCL GPU 优先，不可用时 fallback CPU
- 若当帧检测失败，调用方使用“无人脸”参数继续渲染，不阻塞主流程

## 7. 性能相关实现点与优化

### 7.1 零拷贝优化（2026-06 新增，2026-07 调整为 MNN 独占）

| 优化项 | 实现方式 | 预期收益 |
|--------|----------|----------|
| MediaPipe Image 路径 | CameraX `ImageProxy` → `MediaImageBuilder` → `MPImage` | 跳过 YUV→ARGB 转换（约 5ms） |
| MNN NV21 路径 | CameraX `DirectByteBuffer` → C++ 预处理+推理 | 跳过 Bitmap→RGB 拷贝（约 3-5ms） |
| C++ 一体式预处理 | BT.601 颜色转换 + 双线性缩放 + letterbox + 归一化 | 减少 CPU 端多次数据搬运 |

### 7.2 MNN 并发初始化优化（2026-06）

| 优化项 | 旧行为 | 新行为 |
|--------|--------|--------|
| 懒加载同步方式 | `synchronized` 阻塞调用线程 | `AtomicBoolean` CAS 非阻塞，初始中进行中跳过本帧 |
| 重型模型加载时机 | 在 `ResourceManager.onLoad()` 回调中同步执行（约 1s+） | 回调中仅重置标记，推迟到下一次 `detect()` 调用 |
| 线程行为 | 初始化进行中时其他线程被阻塞等待 | 其他线程立即返回 false，跳过本帧不阻塞渲染 |

### 7.3 Perf 日志体系

- `FaceDetectorManager` 记录 `[Perf]` 分段耗时（ROI / Landmark / Total），NV21 路径额外输出 `[Perf] NV21 path breakdown`
- `MediaPipeFaceDetector` / `MediaPipeLandmarkDetector` Image 路径记录 `[Perf]` 耗时
- `MnnRoiDetector` / `MnnLandmarkDetector` 初始化跳过帧记录 `[Perf]` 日志
- `CameraFrameAnalyzer` 启用智能帧跳过优化
- 调试浮层可显示"请求引擎 + 实际命中来源 + ROI + 106 点"

## 8. 相关文档

- `docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md`
- `docs/07-STANDARDS/COORDINATE_SYSTEM.md`
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`

## 9. 零拷贝路径选择策略（2026-07）

`FaceDetectorManager` 按以下优先级选择检测路径：

1. **若配置为 MediaPipe 统一管线** → 走 `detectFromImage()`（MediaPipe Image 零拷贝）
2. **若配置 MNN ROI + Landmark 组合** → 走 `detectRoiFromNv21()`（MNN NV21 零拷贝 ROI → NV21 Landmark）
3. **Bitmap 降级**（仅当零拷贝路径不可用时）

---

**文档版本**: v4.0 (2026-07-05)
**维护者**: RD Team  
**说明**: 本文档已按 2026-07 NCNN 移除重构更新。当前架构为 MediaPipe/MNN 双引擎。
