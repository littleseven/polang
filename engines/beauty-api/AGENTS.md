# Beauty API 模块契约规范 (Beauty API Contracts)

> **边界声明（Boundary Statement）**
> - 本文档定义 `:engines:beauty-api` 模块的 API 契约稳定性承诺、类型清单和演变规则。
> - `:engines:beauty-api` 是**纯 Kotlin 库模块**（零 Android/OpenGL 依赖），仅持有接口、数据类和枚举。
> - 实现由 `:engines:beauty-engine` 提供；消费方为 `:androidApp`、`:shared` 与 `:engines:beauty-engine`。
> - 美颜引擎实现细节见 `engines/beauty-engine/AGENTS.md`；架构决策背景见 `docs/02-ARCHITECTURE/ADR/`。

**模块定位**：`:engines:beauty-api` 是 PoLang 美颜系统的**接口契约层**，为 `:androidApp`、`:shared`、`:engines:beauty-engine` 三个模块提供稳定的共享类型定义。该模块不包含任何实现代码，仅定义跨模块通信的"语言"。

**主要维护者**：项目开发者

**阅读对象**：RD、CR、AI Agent

---

## 1. 设计原则 (Design Principles)

### 1.1 零依赖
`:engines:beauty-api` 不直接引入第三方库，仅依赖 Kotlin stdlib 和 Android `graphics` 基础类型（`Bitmap`、`PointF`、`Rect`）；模块级依赖 `:shared`（经 `api(project(":shared"))` 透出 `BeautySettings` 等已迁 commonMain 的公开 API）。这保证了所有消费者（包括纯 JVM 测试）均可零成本引用。

### 1.2 纯契约
模块内任何文件不得包含算法实现、IO 操作、网络请求或 Android Context 引用。所有类必须是 `interface`、`data class`、`enum class`、`object`（常量）或 `value class`。

### 1.3 向后兼容
API 变更必须保证源级兼容（source-compatible）。破坏性变更需走 ADR 流程，并在变更前通知所有消费方模块。

---

## 2. 包结构与类型清单 (Package Structure & Type Index)

共有 **15 个 Kotlin 文件**，分为两个包：

### 2.1 根包：`com.mamba.picme.beauty.api`（5 文件）

| 类型 | 文件 | 说明 |
|------|------|------|
| `BeautyProcessor` | `BeautyProcessor.kt` | **CPU 后处理接口** — 10 个 `suspend` 方法的 post-processing 契约（拍照后处理用）；默认实现 `applyAllEffects()` 按序链式调用 |
| `Face` | `Face.kt` | **人脸数据模型** — 替代 ML Kit 的轻量 `data class`（boundingBox + landmarks + contours）；伴生对象定义 `FaceLandmark` 和 `FaceContour` 常量 |
| `FrameId` | `FrameId.kt` | **帧标识** — `inline value class` 包装 `Long`，线程安全递增（`AtomicLong`），`INVALID = 0L` |
| `FrameSyncConfig` | `FrameSyncConfig.kt` | **帧同步配置** — 检测到渲染的同步参数（maxStoredResults、missingThresholdFrames、predictionMaxRatio、syncMode） |
| `FrameSyncResult` | `FrameSyncResult.kt` | **帧同步结果** — 含 `syncStatus` 枚举（EXACT_MATCH / HISTORICAL_FALLBACK / PREDICTED / MISSING） |

> `BeautySettings.kt` / `FilterType.kt` / `StyleFilter.kt` 已迁 `shared/src/commonMain/kotlin/com/mamba/picme/beauty/api/`（FQN 不变，经 `api(project(":shared"))` 透出），不在本模块根包内。

### 2.2 子包：`com.mamba.picme.beauty.api.facedetect`（10 文件）

| 类型 | 文件 | 说明 |
|------|------|------|
| `FaceDetector` | `FaceDetector.kt` | **人脸检测公共接口** — `detect()`（预览）、`detectPhoto()`（拍照）、`setEngineMode()`、`updatePipelineConfig()`、`release()` |
| `FaceDetection` | `FaceDetection.kt` | **单人脸检测结果** — ROI 矩形 + RetinaFace/ArcFace 标准 5 点（`landmarks5` FloatArray，长度 10，未对齐可空），供人脸识别/聚类对齐 |
| `FaceDetectionResult` | `FaceDetectionResult.kt` | **检测输出** — `landmarks106`（FloatArray）、`detectionSource`、roi 矩形、检测器名称和 GPU 标志 |
| `FaceDetectionSource` | `FaceDetectionSource.kt` | **检测来源枚举** — NONE、MEDIAPIPE、MNN |
| `EngineType` | `EngineType.kt` | **引擎模式枚举** — MEDIAPIPE、MNN（供外部设置） |
| `DetectionPipelineConfig` | `DetectionPipelineConfig.kt` | **检测管线配置** — 组合 ROI 检测器类型、Landmark 检测器类型、推理后端（ONNX/MNN/TFLITE）、设备偏好（AUTO/CPU/GPU） |
| `FaceWarpParams` | `FaceWarpParams.kt` | **人脸变形参数** — GPU Shader 归一化坐标（0.0-1.0），供美颜渲染使用 |
| `GpuPixelLandmarks` | `GpuPixelLandmarks.kt` | **GPU 关键点数据** — 106 点 FloatArray 原始访问（热路径优化），附带 PointF 列表用于调试覆盖层 |
| `FaceContourData` | `FaceContourData.kt` | **人脸轮廓数据** — 15 个轮廓区域（脸型、眉毛、眼睛、嘴唇、鼻子、脸颊） |
| `FaceDetectionConstants` | `FaceDetectionConstants.kt` | **常量定义** — POINT_COUNT = 106、CONTOUR_POINT_COUNT = 33、NON_CONTOUR_POINT_COUNT = 73 |

---

## 3. 消费者与依赖方向 (Consumers)

### 3.1 模块依赖图

```
:androidApp  ──────────────→ :engines:beauty-api ←────────────── :shared
（:engines:beauty-api 经 `api(project(":shared"))` 透出 BeautySettings 等已迁 commonMain 的契约类型）
  │                       ↑                            │
  └──→ :engines:beauty-engine ────┘                            │
           (实现 beauty-api 接口)                       │
           (消费 beauty-api 类型)                       │
```

### 3.2 各模块使用范围

| 消费者 | 使用的类型 | 用途 |
|--------|-----------|------|
| `:androidApp` | `BeautySettings`、`FilterType`、`StyleFilter`、`FaceDetector`、`Face`、`EngineType` | 用户交互读取/设置美颜参数、选择滤镜、切换检测引擎、渲染人脸覆盖层 |
| `:engines:beauty-engine` | 全部类型 | 实现 `FaceDetector` 和 `BeautyProcessor` 接口、消费所有数据类 |

---

## 4. API 变更规则 (API Change Rules)

### 4.1 允许的变更（无需 ADR）
- 为 `data class` 新增带默认值的字段
- 为 `enum class` 新增枚举值
- 为 `interface` 新增带默认实现的方法
- 新增独立的常量或辅助类型
- 完善 KDoc 注释

### 4.2 需 ADR 的变更
- 删除或重命名任何公开类型/方法
- 修改 `interface` 方法签名（增加或删除参数、改变返回类型）
- 改变 `enum` 值的语义含义
- 修改核心常量值（如 POINT_COUNT）
- 在数据类中新增必填字段

### 4.3 变更流程
1. RD 在 PR 中描述 API 变更及影响范围
2. CR 验证向后兼容性
3. 如为破坏性变更：需先创建 ADR，在 `:engines:beauty-engine` 中实现适配，再通知消费者迁移

---

## 5. 与 beauty-engine 的关系

```
engines/beauty-api/                         ← 契约定义（本文档）
    ↑ 依赖
engines/beauty-engine/
    ├── api/                        ← 实现层 API（依赖 beauty-api 共享类型）
    │   ├── BeautyParams.kt         ← Shader 归一化值
    │   ├── BeautyPreviewProvider   ← 预览引擎接口
    │   └── PhotoProcessor          ← 拍照处理接口
    ├── render/                     ← OpenGL ES + EGL 渲染实现
    └── internal/facedetect/        ← 人脸检测适配器（MediaPipe/MNN → 统一 106 点）
```

> **关键规则**：App 代码**只能**依赖 `engines/beauty-api/` 和 `engines/beauty-engine:api/`。禁止直接引用 `engines/beauty-engine:render/` 或 `engines/beauty-engine:internal/`。

---

## 6. 常见变更检查清单

- [ ] 新增类型不含 Android Context 引用
- [ ] 新增类型不含第三方库依赖（仅 Kotlin stdlib + Android graphics 基础类型）
- [ ] `data class` 新字段有合理默认值
- [ ] 枚举值变更已通知所有消费者
- [ ] 接口方法变更已检查向后兼容性
- [ ] KDoc 已更新（中英双语）
- [ ] 破坏性变更已创建 ADR

---

> **维护者**：项目开发者
> **最后更新**：2026-07-15
> **状态**：生效中
