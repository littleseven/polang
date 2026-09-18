# 端侧 VLM 打标引擎运维手册

> **文档编号**: TECH-SPEC-MNN-VLM-OPS-001  
> **关联模块**: `:shared`（LocalLlmEngine/MnnLlmClient 在 androidMain，AgentOrchestrator/AgentConfigurator 在 commonMain facade）, `:engines:mnn-core` (MnnResourceManager), `:engines:agent-native`（libagent_native.so JNI 桥构建）, `:androidApp` (MediaPager, TagGenerationScheduler)  
> **最后更新**: 2026-08-03  
> **维护者**: 项目开发者  
>
> **变更说明（2026-08）**：端侧**文本** LLM（Qwen3.5-2B 聊天/指令模型）已移除（`AiAgentMode.LOCAL`、`LocalCameraAgent`/`LocalInferencePipeline`/`LocalCommandParser`/`LocalPromptBuilder` 全部删除）。`LocalLlmEngine` 现仅服务 **VLM 打标**（Qwen3-VL-2B `imageInference`）；相机 AI 指令改走远程 tool_calls（`AgentOrchestrator.processCameraInput` + `CameraToolService`）。MNN-LLM 运行时（`libMNN.so` + `libmnn_llm.so` + `MnnLlmClient` + `libagent_native.so` JNI 桥）仍保留，用于 VLM 图像推理。
>
> **历史合并说明**：本文档由以下 5 份文档合并而成：`MNN_LLM_MULTI_INSTANCE_RESEARCH.md`、`MNN_LLM_PERFORMANCE_OPTIMIZATION.md`、`MNN_RESOURCE_MANAGER_DESIGN.md`、`MNN_UNLOAD_TRIGGER_MECHANISM.md`、`MNN_UNLOAD_TEST_CASES.md`。内容已按「架构与单例安全 → 资源管理器 → 加载/卸载触发机制 → 性能优化 → 测试用例」重组，并消除重复内容。原文档面向通用 MNN-LLM 运维；2026-08 更新后聚焦于 VLM 打标引擎运维。

---

## 1. 架构与单例安全

### 1.1 调研背景

相册预览页点击"图像理解"返回空结果，日志显示 `LLM not loaded, cannot do image inference`。修复过程中发现 `MediaPager.kt` 直接调用 `imageInference()` 前未加载模型，引发对全局模型加载模式的深入调研。

> **注意**：`LocalLlmEngine` 名称中保留 "Llm" 是历史遗留——当前该引擎**仅用于 VLM 打标**（`imageInference`），不再承载文本聊天/Agent 指令推理（已改走远程 tool_calls）。

### 1.2 全局模型加载调用点（LocalModelService 收口，2026-09-18 核验）

全库 `engine.loadModel(` 直调**仅剩一处**：`shared/.../inference/local/LocalModelService.kt:127`（`ensureModelLoaded` 内部，含 `[ModelLoadAudit]` 审计日志、模型下载检查、`isModelLoading` 状态流）。其余调用方一律经 `LocalModelService` 收口（`ensureModelLoaded` / `withModelLoaded` / `loadModel`，后者为前者的别名入口）：

| # | 调用位置 | 调用方式 | 场景 | 说明 |
|---|----------|----------|------|------|
| 1 | `LocalModelService.kt:127` | `imageEngine.loadModel(targetModel, targetUseOpencl)` | 唯一直调点 | `ensureModelLoaded` 内部；`loadModel(modelId)` 即其别名（caller="loadModel"） |
| 2 | `TagGenerationScheduler.kt:1116`（私有 `ensureModelLoaded`） | `orchestrator.localModelService.ensureModelLoaded(...)`（:1146 OpenCL / :1169 CPU 回退） | Pass 3 打标 | :316 / :345 / :398 / :1508 / :1580（`prepareTaggerModel`）全走此私有封装 |
| 3 | `OpenClGuardian.kt:211` | `orchestrator.localModelService.ensureModelLoaded(...)` | OpenCL warmup | Guardian 内部检查 |
| 4 | `TagScanOrchestrator.kt:672-673` | 委托 `scheduler.executeImageTagging`（内部 ensureModelLoaded） | 后台标签扫描 Pass 3 | 不直接触模型 |

已失效调用点（历史参考）：~~`ChatViewModel.kt:583`~~ / ~~`AiAgentUseCase.kt:157`~~（文本 LLM 删除，聊天/Agent 改远程 tool_calls）；~~`AgentOrchestrator.loadModel()`~~（facade 已移除，见 §1.3）；~~`ImageTagIndexingWorker.kt:209`~~（文件已不存在，TAG 域现为 `domain/tag/scan/TagScanOrchestrator.kt`）；~~`MediaPager.kt:400`~~（MediaPager 已无任何 loadModel 调用）。

**关键事实**：所有调用最终汇聚到 `AgentConfigurator` 持有的**同一个** `LocalLlmEngine` 实例（接口视图 `ImageInferenceEngine`，经 `LocalModelService.getLlmEngine()` 暴露，`LocalModelService.kt:71`）；androidApp TAG 域经组合根 `AndroidAgentComposition.localLlmEngine` 取同一实例的具体类型。

### 1.3 单例架构链路

```
调用方（TagGenerationScheduler / OpenClGuardian / TagScanOrchestrator）
    │
    ▼
AgentOrchestrator.getInstance()  ←── 进程级单例（组合根 Application.onCreate 经 initialize(deps) 完成初始化，无参 getInstance() 取实例）
    │
    └── localModelService（AgentOrchestrator.kt:134 持有 LocalModelService）
            │
            ├── getLlmEngine()（LocalModelService.kt:71）──→ ImageInferenceEngine（Android actual = LocalLlmEngine，由 AgentConfigurator 持有）
            │                                                       │
            │                                                       ▼
            │                                               MnnLlmClient（成员变量，唯一实例）
            │                                                       │
            │                                                       ▼
            │                                               nativeHandle: Long（指向 C++ Llm 对象）
            │
            └── ensureModelLoaded / withModelLoaded ──→ imageEngine.loadModel(modelId, useOpencl)（LocalModelService.kt:127，唯一直调）
                                                            │
                                                            ▼
                                                    engineMutex.withLock（协程 Mutex + modelDispatcher）
                                                            │
                                                            ▼
                                                    MnnLlmClient.load(modelId)
                                                            │
                                                            ▼
                                                    if (isLoaded) return true（幂等守卫）
                                                            │
                                                            ▼
                                                    MnnGlobalReleaseLock.withOperation {
                                                        nativeHandle = nativeCreate(configPath)
                                                    }
```

### 1.4 MNN 多实例安全性判断：安全（在当前架构下）

#### 安全的核心原因

| 保障层级 | 机制 | 说明 |
|----------|------|------|
| **架构层** | 全局单例 `AgentOrchestrator` | 所有调用方通过 `getInstance()` 获取同一实例，自然汇聚到同一 `LocalLlmEngine` |
| **引擎层** | `LocalLlmEngine` 唯一实例 | 由 `AgentConfigurator` 持有，进程生命周期内唯一 |
| **客户端层** | `MnnLlmClient` 唯一实例 | `LocalLlmEngine` 的成员变量，构造函数内创建 |
| **加载层** | `isLoaded` 幂等守卫 | `MnnLlmClient.load()` 第一行检查 `if (isLoaded) return true`，防止重复 `nativeCreate` |
| **线程层** | `engineMutex` + `modelDispatcher` | `LocalLlmEngine.loadModel()` 使用 `Mutex.withLock` + 专用单线程调度器，串行化所有模型操作 |
| **Native 层** | `MnnGlobalReleaseLock` | 所有 MNN native 操作（create/destroy/reset/generate）通过全局锁串行化，防止并发冲突 |

#### MNN 全局共享状态问题

> `libMNN.so` 的 `EagerBufferAllocator` 和 `Express::Executor` 是全局共享状态，非线程安全。所有涉及 MNN native 资源释放的操作必须通过此锁串行化，防止并发释放导致的 use-after-free / double-free 崩溃。

**当前架构如何规避**：

| 风险场景 | 是否可能发生 | 原因 |
|----------|-------------|------|
| 两个 `MnnLlmClient` 实例同时加载同一模型 | **不可能** | `LocalLlmEngine` 是单例，其内部 `MnnLlmClient` 也是单例 |
| 同一 `MnnLlmClient` 重复调用 `load()` | **安全** | `isLoaded` 守卫 + `engineMutex` 串行化 |
| 加载与卸载并发执行 | **安全** | `engineMutex` 确保互斥；`MnnGlobalReleaseLock` 确保 native 层串行 |
| 多个调用方同时请求加载 | **安全** | `modelDispatcher` 单线程 + `Mutex` 排队 |
| 一处加载后另一处调用推理 | **安全** | 所有操作在同一 `nativeHandle` 上执行，状态一致 |

#### 假设多实例架构的风险（理论分析）

如果**未来**出现多个 `LocalLlmEngine` / `MnnLlmClient` 实例同时加载同一模型：

| 风险 | 严重程度 | 说明 |
|------|----------|------|
| Native 内存重复分配 | 高 | 每个 `nativeCreate` 独立分配 ~4GB Native Heap，极易 OOM |
| MNN 全局状态冲突 | 高 | `EagerBufferAllocator` 非线程安全，并发 create/destroy 可能崩溃 |
| 模型状态不一致 | 中 | 各实例独立 KV Cache，多轮对话历史不共享 |
| 引用计数混乱 | 中 | `MnnResourceManager` 的引用计数与实例生命周期难以对齐 |

**结论**：即使 MNN 底层支持多实例，应用层也应保持**单例模式**，通过 `MnnResourceManager` 的引用计数协调生命周期。

### 1.5 图像理解空结果根因与修复

**根因**：`MediaPager.kt` 的 `onStartVision` 回调直接调用 `engine.imageInference()`，未先加载模型。

**修复**：在调用 `imageInference()` 前添加 `ensureModelLoaded` 模式：

```kotlin
val orchestrator = AgentOrchestrator.getInstance(context)
val engine = orchestrator.getLlmEngine()

val modelKey = "qwen3_vl_2b"
if (!engine.isLoaded) {
    val loadResult = engine.loadModel(modelKey, useOpencl = false)
    if (loadResult.isFailure) {
        // 处理加载失败
        return@launch
    }
}

val result = engine.imageInference(bitmap, systemPrompt, userPrompt)
```

### 1.6 模型加载最佳实践模式

```kotlin
/**
 * 标准模型加载模式（适用于所有调用方）
 */
suspend fun safeModelInference(
    orchestrator: AgentOrchestrator,
    modelKey: String = "qwen3_vl_2b",
    inferenceBlock: suspend (LocalLlmEngine) -> String
): String {
    val engine = orchestrator.getLlmEngine()
    
    // Step 1: 确保模型已加载（幂等操作）
    if (!engine.isLoaded) {
        val loadResult = engine.loadModel(modelKey, useOpencl = false)
        if (loadResult.isFailure) {
            return "模型加载失败: ${loadResult.exceptionOrNull()?.message}"
        }
    }
    
    // Step 2: 执行推理
    return inferenceBlock(engine)
}
```

### 1.7 架构设计建议

| 改进项 | 优先级 | 说明 |
|--------|--------|------|
| 封装 `ensureModelLoaded()` 到 `AgentOrchestrator` | P1 | 避免各调用方重复实现加载检查逻辑 |
| 添加 `loadModel()` 调用点日志审计 | P2 | 便于追踪谁在何时触发了模型加载 |
| 统一 `useOpencl` 参数决策 | P2 | 当前各调用方自行决定，应由 `OpenClGuardian` 统一管理 |
| 文档化加载契约 | P2 | 明确各调用方的加载责任（调用前是否必须加载） |

---

## 2. 资源管理器

### 2.1 背景与问题

PoLang 使用 MNN 3.5.0 统一构建的 `libMNN.so`，当前承载两个独立子系统：

| 子系统 | MNN API | 内存占用 | 生命周期 |
|--------|---------|----------|----------|
| **VLM 打标** | `MNN::Transformer::Llm` | Qwen3-VL-2B（INT4）约 1.5-2.5GB | 加载后常驻，仅响应内存压力卸载 |
| **MNN 人脸检测** | `MNN Interpreter`（beauty-engine `MnnRoiDetector` / `MnnLandmarkDetector`） | 约 50-70MB | 相机页场景绑定，离开相机页延迟卸载 |

> **注意**：语音栈已迁移至 Sherpa-ONNX（见 [VOICE_STACK.md](VOICE_STACK.md)），ASR 不再依赖 `libMNN.so`，`SherpaOnnxAsrEngine` 也明确**不再接入 `MnnResourceManager` / `MnnGlobalReleaseLock`**（引用计数协调机制中已无 ASR 一方）。`libMNN.so` 的共享方为 VLM 打标与 MNN 人脸检测。历史上的「VLM + ASR 共享协调」设计见下文标注的历史小节，保留用于理解 `MnnResourceManager` 的演进。

### 2.2 核心冲突（历史）

`MNN::Transformer::Llm::destroy()` 在释放 LLM 独占内存时，会触及 MNN 全局内存分配器状态，导致**同一进程内仍在运行的 Sherpa-MNN ASR 崩溃**（历史问题，ASR 已迁移至 Sherpa-ONNX，不再依赖 MNN）。

> **当前对应关系**：同类冲突如今存在于 **VLM 打标 ↔ MNN 人脸检测** 之间——两者共享 `libMNN.so` 全局分配器，一方释放时若另一方仍在推理，同样可能触发 use-after-free。`MnnGlobalReleaseLock` 与引用计数协调即为解决此问题。

现有规避方案（`AgentOrchestrator.applySceneDrivenModelPolicy`）：

```kotlin
// 相机场景：不卸载模型，仅 trimMemory 清理 KV Cache
localLlmEngine.trimMemory()
```

这导致 VLM 模型在相机场景下**无法真正释放**，后台内存占用较高。

### 2.3 设计目标

| 目标 | 度量 |
|------|------|
| **安全共享** | VLM 与人脸检测可共存，释放时互不破坏 |
| **自动卸载** | App 后台 60s 后完全释放，无需人工干预 |
| **内存压力响应** | `onTrimMemory(CRITICAL)` 时紧急释放 |
| **零泄漏** | 离开相机页时人脸检测实例 100% 释放 |
| **低延迟恢复** | 前台恢复时模型重新加载 < 3s |

### 2.4 引用计数协调

引入 `MnnResourceManager` 作为**唯一协调者**，VLM 打标和 MNN 人脸检测分别持有独立引用计数：

```
VLM 请求加载      →  acquireLlm()            → llmRefCount++
人脸检测请求加载   →  acquireFaceDetection()  → faceDetectionRefCount++

VLM 请求释放  →  releaseLlm()
    ├─ faceDetectionRefCount == 0 → onSafeUnload()  → 真正 unload()（MnnGlobalReleaseLock 串行化）
    └─ faceDetectionRefCount  > 0 → onSoftRelease() → trimMemory()

人脸检测请求释放  →  releaseFaceDetection()
    ├─ llmRefCount == 0 → onSafeUnload()  → 真正 release()
    └─ llmRefCount  > 0 → onSoftRelease()
```

> **注意**：引用计数 API 名仍为 `acquireLlm`/`releaseLlm`，但当前 `llmRefCount` 实际管理的是 **VLM 打标引擎**的生命周期（文本 LLM 已移除）。历史上的 `acquireAsr`/`releaseAsr` 已随 ASR 迁移 Sherpa-ONNX 而移除——ASR 不再共享 `libMNN.so`，也不接入 `MnnResourceManager`。

### 2.5 联合状态机

```
                    ┌─────────────────────────────────────┐
                    │         MNN_RESOURCE_STATE          │
                    └─────────────────────────────────────┘

    ┌──────────┐    load()    ┌──────────┐   both agree   ┌──────────┐
    │  IDLE    │ ───────────→ │  SHARED  │ ─────────────→ │ UNLOADED │
    │(无模型)   │              │(VLM+Face)│   unload()     │(已释放)   │
    └──────────┘              └────┬─────┘                └──────────┘
         ↑                         │
         │              ┌──────────┴──────────┐
         │              │                     │
         │         trim()               face_unload()
         │              ↓                     ↓
         │       ┌──────────┐          ┌──────────┐
         └───────│ VLM_ONLY │          │ FACE_ONLY │
                 │(仅VLM常驻)│          │(仅Face常驻)│
                 └──────────┘          └──────────┘
```

> 每模型另有独立细粒度状态机（`ModelState`：`UNLOADED → MODEL_LOADED → SESSION_READY → ACTIVE`），见 `MnnResourceManager`。

### 2.6 生命周期触发矩阵（当前实现）

场景驱动逻辑见 `MnnResourceManager.setScene()`：**VLM 永不响应场景切换**（跨页面保活，仅响应内存压力）；人脸检测绑定相机场景。

| 当前状态 | 触发事件 | VLM 动作 | 人脸检测动作 | 结果状态 |
|----------|----------|----------|--------------|----------|
| IDLE | TAG 打标触发 | `loadModel()` | 无 | VLM_ONLY |
| IDLE | 进入相机页（Scene.CAMERA） | 无 | 取消卸载调度，通知加载 | FACE_ONLY |
| VLM_ONLY | 进入相机页 | 保持 | 加载 | SHARED |
| FACE_ONLY | TAG 打标触发 | `loadModel()` | 保持 | SHARED |
| SHARED | 离开相机页（CHAT/SETTINGS/OTHER） | 保持（不受场景切换影响） | 强制卸载（忽略引用计数） | VLM_ONLY |
| SHARED | TAG 后台结束 | `releaseLlm()` → soft/safeRelease | 保持 | FACE_ONLY |
| * | 后台 30s（无引用） | `trimMemory()`（softTrim） | softTrim | * (soft) |
| * | 后台 60s / 内存压力 | `unload()`（safeUnload） | `release()`（safeUnload） | IDLE |
| * | `onTrimMemory(CRITICAL/COMPLETE)` | `unload()` | `release()` | IDLE |

> 历史矩阵（VLM + ASR 共享时期）已不再适用：ASR 生命周期现由 `VoiceCommandCoordinator` / `SherpaOnnxAsrEngine` 自行管理，不经 `MnnResourceManager`。

### 2.7 组件职责

| 组件 | 位置 | 职责 |
|------|------|------|
| `MnnResourceManager` | `engines/mnn-core/src/main/java/com/mamba/picme/mnn/MnnResourceManager.kt` | 引用计数管理（VLM + 人脸检测）、生命周期监听、内存压力响应、事件分发 |
| `LocalLlmEngine` | `shared/src/androidMain/.../inference/local/llm/LocalLlmEngine.kt` | `load()` 成功后调用 `acquireLlm()`；`unload()` 改为调用 `releaseLlm()`（仅服务 VLM 打标） |
| `MnnRoiDetector` / `MnnLandmarkDetector` | `engines/beauty-engine/.../facedetect/MnnRoiDetector.kt` 等 | 初始化时调用 `acquireFaceDetection()`；释放时调用 `releaseFaceDetection()` |
| `SherpaOnnxAsrEngine` | `shared/src/androidMain/.../platform/voice/SherpaOnnxAsrEngine.kt` | ASR 引擎（Sherpa-ONNX）。**不参与 MNN 引用计数**——不再依赖 `libMNN.so` / `MnnResourceManager` / `MnnGlobalReleaseLock` |
| `VoiceCommandCoordinator` | `app/.../camera/voice/VoiceCommandCoordinator.kt` | 语音生命周期管理（直接释放 ASR 引擎，不经 `MnnResourceManager`） |
| `CameraScreen` | `app/.../camera/CameraScreen.kt` | 监听 ON_RESUME / ON_PAUSE，联动 `MnnResourceManager`（场景切换） |
| `PoLangApplication` | `app/.../PoLangApplication.kt` | `ActivityTracker` 计数，前后台切换通知 `MnnResourceManager` |

### 2.8 线程安全

| 组件 | 关键操作 | 同步机制 |
|------|----------|----------|
| `MnnResourceManager` | 引用计数 | `AtomicInteger` |
| `MnnResourceManager` | 监听器列表 | `CopyOnWriteArrayList` |
| `MnnResourceManager` | 后台调度 | `AtomicBoolean` 防重复 |
| `LocalLlmEngine` | load/unload/generate | `Mutex` |
| MNN 人脸检测（ROI/Landmark） | native create/release | `MnnGlobalReleaseLock` 串行化 |
| `SherpaOnnxAsrEngine` | recognizer 创建/释放 | 引擎内部自行管理（不经 MNN 锁） |

### 2.9 调试与观测

#### 日志标签

| 标签 | 来源 | 关键日志 |
|------|------|----------|
| `MnnResourceManager` | `MnnResourceManager` | refCount 变化、前后台切换、内存压力级别 |
| `LocalLlmEngine` | `LocalLlmEngine` | load/unload/trimMemory 状态转换 |
| `SherpaOnnxAsr` | `SherpaOnnxAsrEngine` | ASR init/release 状态（独立于 MNN 资源管理） |

#### 内存统计 API

```kotlin
val stats = MnnResourceManager.getInstance(context).getMemoryStats()
// stats.llmRefCount
// stats.faceDetectionRefCount
// stats.currentScene
// stats.appInForeground
// stats.javaHeapUsedMB
// stats.nativeHeapMB
// stats.availableMemoryMB
// stats.lowMemory
```

---

## 3. 加载/卸载触发机制

### 3.1 触发源总览

| 触发源 | 触发条件 | 动作 | 延迟 | 优先级 |
|--------|----------|------|------|--------|
| **App 后台** | 最后一个 Activity `onStopped()` | `onAppBackground()` | 30s softTrim → 60s safeUnload | 中 |
| **CameraScreen 生命周期** | `ON_PAUSE` | `onAppBackground()` | 同上 | 中 |
| **内存压力** | `onTrimMemory(RUNNING_MODERATE)` | `notifySoftTrim()` | 立即 | 低 |
| **内存压力** | `onTrimMemory(RUNNING_LOW)` | `notifySafeUnload()` | 立即 | 高 |
| **内存压力** | `onTrimMemory(RUNNING_CRITICAL)` | `notifySafeUnload()` | 立即 | 紧急 |
| **内存压力** | `onTrimMemory(UI_HIDDEN)` | `onAppBackground()` | 30s → 60s | 中 |
| **内存压力** | `onTrimMemory(COMPLETE)` | `notifySafeUnload()` | 立即 | 紧急 |
| **页面退出（场景切换）** | `setScene(CHAT/SETTINGS/OTHER)` | `forceFaceDetectionUnload()` | 立即 | 中 |
| **离开相机页** | `setScene(CAMERA → 其他)` | 人脸检测延迟卸载（`FACE_DETECTION_UNLOAD_DELAY_MS = 5000L`） | 5s | 中 |
| **TAG 后台结束** | `AgentOrchestrator.unloadModel()` | `releaseLlm()` → softRelease | 立即 | 低 |
| **模型切换** | `loadModel()` 加载新模型 | `unload()` 旧模型 | 立即 | 中 |

> 历史触发源「`VoiceCommandCoordinator.release()` → `releaseAsr()`」已失效：ASR 迁移 Sherpa-ONNX 后不经 `MnnResourceManager`，由 `SherpaOnnxAsrEngine.release()` 自行释放。

### 3.2 引用计数协调机制（核心规则）

```
acquireLlm()           → llmRefCount++
acquireFaceDetection() → faceDetectionRefCount++

releaseLlm()
    ├─ faceDetectionRefCount == 0 → onSafeUnload()  → 真正 nativeDestroy()（MnnGlobalReleaseLock 串行化）
    └─ faceDetectionRefCount  > 0 → onSoftRelease() → trimMemory()（保留模型）

releaseFaceDetection()
    ├─ llmRefCount == 0 → onSafeUnload()  → 真正 detector release()
    └─ llmRefCount  > 0 → onSoftRelease() → 保留模型
```

### 3.3 App 前后台切换

**触发位置**: `PoLangApplication.ActivityTracker`

```kotlin
override fun onActivityStarted(activity: Activity) {
    if (activityCount == 0) {
        MnnResourceManager.getInstance(this@PoLangApplication).onAppForeground()
    }
    activityCount++
}

override fun onActivityStopped(activity: Activity) {
    activityCount--
    if (activityCount == 0) {
        MnnResourceManager.getInstance(this@PoLangApplication).onAppBackground()
    }
}
```

**时序**：

```
用户按 Home 键
    │
    ▼
最后一个 Activity onStopped()
    │
    ▼
onAppBackground()
    │
    ▼
调度协程：delay 30s
    │
    ▼
30s 后检查：仍后台 + 无引用？
    │
    ├── 是 → notifySoftTrim()
    │           ├── LocalLlmEngine.onSoftTrim() → trimMemory()
    │           └── 人脸检测.onSoftTrim() → softRelease（保留模型）
    │
    ▼
delay 再 30s（累计 60s）
    │
    ▼
60s 后检查：仍后台 + 无引用？
    │
    ├── 是 → notifySafeUnload()
    │           ├── LocalLlmEngine.onSafeUnload() → performUnload()
    │           └── 人脸检测.onSafeUnload() → performUnload()
    │
    ▼
┌─────────────────────────────────────┐
│            IDLE 状态                │
│            VLM + 人脸检测完全释放          │
└─────────────────────────────────────┘
```

**关键参数**：
- `BACKGROUND_UNLOAD_DELAY_MS = 30000L`
- `BACKGROUND_FORCE_UNLOAD_DELAY_MS = 60000L`

### 3.4 系统内存压力

**触发位置**: `MnnResourceManager.init()` 注册的 `ComponentCallbacks2`

```kotlin
private fun handleMemoryPressure(level: Int) {
    when (level) {
        TRIM_MEMORY_RUNNING_MODERATE -> notifySoftTrim()
        TRIM_MEMORY_RUNNING_LOW,
        TRIM_MEMORY_RUNNING_CRITICAL -> notifySafeUnload()
        TRIM_MEMORY_UI_HIDDEN -> {
            if (!isAnyRequested) onAppBackground()
        }
        TRIM_MEMORY_COMPLETE -> notifySafeUnload()
    }
}
```

**Android 内存压力级别说明**：

| 级别 | 含义 | 本系统响应 |
|------|------|-----------|
| `RUNNING_MODERATE` | 系统内存略有压力 | softTrim（清理 KV Cache） |
| `RUNNING_LOW` | 系统内存紧张 | safeUnload（完全释放） |
| `RUNNING_CRITICAL` | 系统内存极度紧张 | safeUnload（完全释放） |
| `UI_HIDDEN` | 应用 UI 不可见 | 如果无引用，调度后台卸载 |
| `COMPLETE` | 系统内存耗尽 | safeUnload（完全释放） |

### 3.5 模型切换

**触发位置**: `LocalLlmEngine.loadModel()`

```kotlin
suspend fun loadModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
    engineMutex.withLock {
        if (client.isLoaded && currentModelId == modelId) {
            return@withLock Result.success(Unit)
        }
        if (client.isLoaded) {
            client.unload()  // ← 直接卸载旧模型
            currentModelId = null
        }
        // ... 加载新模型
    }
}
```

**注意**: 模型切换时的 `unload()` 不走 `MnnResourceManager`，因为这是**同类型资源替换**，不是**引用释放**。

### 3.6 防重复机制

#### 后台卸载防重调度

```kotlin
private val backgroundUnloadScheduled = AtomicBoolean(false)

fun onAppBackground() {
    if (backgroundUnloadScheduled.compareAndSet(false, true)) {
        scope.launch {
            delay(BACKGROUND_UNLOAD_DELAY_MS)
            // ... softTrim
            delay(BACKGROUND_FORCE_UNLOAD_DELAY_MS - BACKGROUND_UNLOAD_DELAY_MS)
            // ... safeUnload
            backgroundUnloadScheduled.set(false)
        }
    }
}

fun onAppForeground() {
    cancelBackgroundUnload()  // AtomicBoolean.set(false)
}
```

#### 引用计数防负值

```kotlin
fun releaseLlm(owner: String, onSafeUnload: () -> Unit, onSoftRelease: () -> Unit) {
    val count = llmRefCount.decrementAndGet()
    if (count <= 0) {
        llmRefCount.set(0)  // 防负值
        // ...
    }
}
```

### 3.7 日志追踪

#### 关键日志标签

| 标签 | 来源 | 关键日志模式 |
|------|------|-------------|
| `MnnResourceManager` | `MnnResourceManager` | `LLM/FaceDetection acquired/released by X, refCount=N` |
| `MnnResourceManager` | `MnnResourceManager` | `App entered foreground/background` |
| `MnnResourceManager` | `MnnResourceManager` | `Memory pressure: LEVEL, action` |
| `LocalLlmEngine` | `LocalLlmEngine` | `VLM fully unloaded` / `VLM memory trimmed` |
| `SherpaOnnxAsr` | `SherpaOnnxAsrEngine` | ASR init/release（独立于 MNN 资源管理） |
| `VoiceCommand` | `VoiceCommandCoordinator` | `VoiceCommandCoordinator released` |

#### ADB 日志过滤命令

```bash
# 查看所有 MNN 资源管理日志
adb logcat -s "MnnResourceManager:*" "LocalLlmEngine:*" "SherpaOnnxAsr:*" "VoiceCommand:*" -v time

# 只看引用计数变化
adb logcat -s "MnnResourceManager:*" -v time | grep -E "acquired|released|refCount"

# 只看卸载事件
adb logcat -s "MnnResourceManager:*" "LocalLlmEngine:*" "SherpaOnnxAsr:*" -v time | grep -E "unloaded|trimmed|soft released"
```

---

## 4. 性能优化

### 4.1 当前配置分析

#### 已部署模型

| 模型 | 文件大小 | 运行时内存 | 配置路径 |
|------|---------|-----------|---------|
| **Qwen3-VL-2B**（INT4） | weight 1.4GB | ~1.5-2.5GB | `files/llm_models/qwen3_vl_2b/config.json` |

> **历史参考**：端侧文本 LLM（Qwen3.5-2B，weight 1.8GB，~4.2GB 运行时内存）已于 2026-08 移除。以下 config 示例和优化分析保留作历史参考。

#### 历史 config.json（Qwen3.5-2B 文本 LLM，已移除）

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "cpu",
    "thread_num": 4,
    "precision": "low",
    "memory": "low",
    "sampler_type": "mixed",
    "mixed_samplers": ["penalty", "topK", "topP", "min_p", "temperature"],
    "penalty": 1.1,
    "temperature": 0.6,
    "topP": 0.95,
    "topK": 20,
    "min_p": 0
}
```

#### 当前问题

| 问题 | 现象 | 根因 |
|------|------|------|
| ~~内存占用过高~~ | ~~Native Heap ~4.2GB~~ | ~~文本 LLM weight 1.8GB + KV Cache + 激活值~~（文本 LLM 已移除） |
| ~~应用被 OOM Kill~~ | ~~相机预览 + LLM 同时运行时可能被杀~~ | ~~总 PSS 可能超过 LMK 阈值~~（文本 LLM 已移除） |
| ~~渲染卡顿~~ | ~~Janky frames 增加~~ | ~~内存压力导致 Swap 换页，GPU 竞争~~（文本 LLM 已移除） |
| ~~高温~~ | ~~CPU/GPU 可能发热~~ | ~~CPU 后端推理，未使用 GPU/NPU~~（文本 LLM 已移除） |

> 以上问题均针对历史文本 LLM（Qwen3.5-2B）。VLM 打标（Qwen3-VL-2B INT4）内存占用约 1.5-2.5GB，远低于文本 LLM。

### 4.2 MNN-LLM 配置参数详解

#### 内存相关参数

| 参数 | 类型 | 当前值 | 可选值 | 说明 |
|------|------|--------|--------|------|
| `memory` | string | `low` | `low` / `normal` / `high` | 内存使用模式。`low` 减少中间缓存，`high` 加速但占内存 |
| `precision` | string | `low` | `low` / `normal` / `high` / `low_bf16` | 精度模式。`low` 使用 FP16/INT8，`high` 使用 FP32 |
| `backend_type` | string | `cpu` | `cpu` / `gpu` / `npu` | 推理后端。GPU 可减少 CPU 占用但增加 GPU 内存 |
| `thread_num` | int | 4 | 1-N | CPU 线程数。过多线程增加内存和调度开销 |

#### MNN 高级 Hint（C++ API）

```cpp
// KV Cache 大小限制（单层）
// 超过限制会换出到磁盘
interpreter->setHint(MNN::Interpreter::KVCACHE_SIZE_LIMIT, 1024); // KB

// Attention 量化选项
// 0: 不量化, 1: Q/K Int8 V Float, 2: Q/K/V Int8
interpreter->setHint(MNN::Interpreter::ATTENTION_OPTION, 2);

// Flash Attention
// 0: 不使用, 1: 使用（减少内存占用）
interpreter->setHint(MNN::Interpreter::ATTENTION_OPTION, 2 + 8); // Int8 + FlashAttn

// 使用 mmap 加载模型（减少内存占用）
interpreter->setHint(MNN::Interpreter::USE_CACHED_MMAP, 1);
```

#### 关键发现

> **Memory 经验**：MNN LLM 推理时出现"Memory not Enough"错误，可能是配置中使用了 `use_mmap: true` 导致内存占用过高。建议参考历史版本配置：使用 `thread_num: 4`（而非2），不启用 `use_mmap`，保持 `memory: low` 和 `precision: low`。

### 4.3 优化策略（按优先级排序）

#### ~~P0：模型量化（效果最显著）~~ — 已移除

> 端侧文本 LLM（Qwen3.5-2B）已移除。VLM 打标使用 Qwen3-VL-2B（已是 INT4 量化）。以下为历史分析。

**历史模型**：Qwen3.5-2B FP16/FP32（weight 1.8GB）  
**历史目标**：INT4 量化（weight ~600MB，减少 65%）

| 量化级别 | 模型大小 | 运行时内存 | 质量损失 | 适用场景 |
|---------|---------|-----------|---------|---------|
| FP16（当前） | 1.8GB | ~4.2GB | 无 | 高端设备 |
| INT8 | ~900MB | ~2.5GB | 轻微 | 中端设备 |
| **INT4** | **~600MB** | **~1.5GB** | 可接受 | **推荐** |

**操作步骤**：
1. 从 ModelScope 下载 Qwen3.5-2B-INT4-MNN 或自行转换
2. 替换 `llm.mnn` + `llm.mnn.weight`
3. 更新 `config.json` 中的 `precision: "low"`

**MNN 转换命令参考**：
```bash
# INT4 量化转换（需 MNN 工具链）
mnnconvert -f ONNX --modelFile qwen3.5-2b.onnx \
  --MNNModel qwen3.5-2b-int4.mnn \
  --weightQuantBits 4 \
  --weightQuantAsymmetric
```

#### ~~P0：切换到更小的模型~~ — 已移除

> 端侧文本 LLM 已移除，文本聊天/Agent 不再使用本地模型。以下为历史分析。

**历史备选模型**：Qwen3.5-0.8B（weight 470MB）

| 对比 | Qwen3.5-2B | Qwen3.5-0.8B |
|------|-----------|-------------|
| Weight | 1.8GB | 470MB |
| 预估运行时 | ~4.2GB | ~1.5GB |
| 推理质量 | 较高 | 中等 |
| 适用场景 | 纯聊天页 | 相机预览共存 |

**历史建议**：
- ~~相机预览场景自动切换到 0.8B 模型~~（文本 LLM 已移除）
- ~~聊天页可使用 2B 模型提供更强推理能力~~（聊天改走远程推理）

#### P1：GPU 后端切换

**当前**：`backend_type: "opencl"`（`MnnLlmClient` 默认硬编码；MNN GPU 后端为 OpenCL，非 Vulkan，见 `MnnLlmClient.kt`）  
**可选**：不支持 OpenCL 的低端设备回退 `cpu`（`OpenClGuardian` 负责超时降级）

| 后端 | CPU 占用 | GPU 内存 | 温度 | 适用场景 |
|------|---------|---------|------|---------|
| CPU | 高 | 低 | 高（CPU发热） | 低端设备 |
| GPU | 低 | 高 | 中（GPU发热） | 有 GPU 的设备 |
| NPU | 极低 | 中 | 低 | 支持 NPU 的设备 |

**注意事项**：
- GPU 后端会增加 GPU 内存占用（~500MB-1GB）
- 需要设备支持 OpenCL（项目统一 OpenCL 后端，未使用 Vulkan）
- 首次加载可能有编译延迟

#### ~~P1：动态加载/卸载~~ — 已部分解决

> 文本 LLM 已移除，相机页不再有本地文本 LLM。VLM 打标模型的动态加载/卸载仍可优化。以下为历史分析。

**历史问题**：LLM 模型常驻内存，即使不在聊天页

**优化方案**：

```kotlin
// 相机预览页：不加载 LLM
class CameraViewModel {
    init {
        // 进入相机页时卸载 LLM
        agentOrchestrator.unloadModel()
    }
}

// 聊天页：按需加载
class ChatViewModel {
    fun onEnter() {
        viewModelScope.launch {
            agentOrchestrator.loadModel("qwen3_5_2b")
        }
    }
    
    fun onExit() {
        agentOrchestrator.unloadModel()
    }
}
```

**收益**：
- 相机预览场景释放 ~3.5GB 内存
- 避免 OOM Kill
- 聊天页首次进入有加载延迟（~2-3秒）

#### P1：KV Cache 限制

**当前问题**：VLM 打标 KV Cache 随推理长度增长

**优化方案**：

```json
{
    "max_new_tokens": 512,
    "max_context_length": 2048
}
```

或在 C++ 层设置：
```cpp
// 限制 KV Cache 大小（单层 1024KB = 1MB）
llm->set_config("{\"kv_cache_limit\": 1024}");

// 或限制最大历史长度
llm->set_config("{\"max_history\": 10}");
```

**收益**：
- 长推理场景内存不再无限增长
- 限制后最大额外内存 ~500MB

#### P2：推理参数优化

| 参数 | 当前值 | 建议值 | 说明 |
|------|--------|--------|------|
| `thread_num` | 4 | 2 | 减少线程数和内存开销 |
| `temperature` | 0.6 | 0.3 | 降低随机性，减少采样计算 |
| `topK` | 20 | 10 | 减少候选 token 数 |
| `max_new_tokens` | 8192 | 128 | 限制生成长度（VLM 打标场景 128 足够） |

#### P2：内存监控与自动降级

```kotlin
class MemoryMonitor {
    fun checkMemory(): LlmMode {
        val nativeHeap = getNativeHeapSize()
        return when {
            nativeHeap > 3_000_000_000 -> LlmMode.REMOTE_ONLY // >3GB 用远程
            nativeHeap > 2_000_000_000 -> LlmMode.SMALL_MODEL  // >2GB 用小模型
            else -> LlmMode.LOCAL_FULL
        }
    }
}
```

### 4.4 推荐配置组合

#### 方案 A：相机预览场景（推荐）

> 文本 LLM 已移除，相机 AI 指令走远程 tool_calls。以下为历史 VLM 打标优化参考。

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "cpu",
    "thread_num": 2,
    "precision": "low",
    "memory": "low",
    "max_new_tokens": 128,
    "sampler_type": "mixed",
    "temperature": 0.3,
    "topK": 10,
    "topP": 0.9
}
```

**配合**：TAG 打标使用 Qwen3-VL-2B INT4 模型（已是量化模型）

#### ~~方案 B：聊天页（高质量）~~ — 已移除

> 文本聊天/Agent 已改走远程推理，不再使用本地 LLM。以下为历史配置参考。

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "gpu",
    "thread_num": 4,
    "precision": "low",
    "memory": "normal",
    "max_new_tokens": 512,
    "sampler_type": "mixed",
    "temperature": 0.6,
    "topK": 20,
    "topP": 0.95
}
```

**历史配合**：INT4 量化模型，限制 KV Cache（已不适用）

#### ~~方案 C：低端设备（极致省内存）~~ — 已移除

> 文本 LLM 已移除，低端设备文本推理走远程。VLM 打标低端设备可使用 Florence-2 替代 Qwen3-VL。

```json
{
    "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight",
    "backend_type": "cpu",
    "thread_num": 1,
    "precision": "low",
    "memory": "low",
    "max_new_tokens": 64,
    "sampler_type": "greedy"
}
```

**历史配合**：0.8B 模型，动态加载（已不适用）

### 4.5 预期收益（基于历史 Qwen3.5-2B 文本 LLM，已移除）

> 文本 LLM 已移除，以下为历史收益参考。

| 优化项 | 历史 | 目标 | 收益 |
|--------|------|------|------|
| ~~模型量化（INT4）~~ | ~4.2GB | ~1.5GB | 内存减少 64%（VLM 打标已是 INT4） |
| ~~切换 0.8B 模型~~ | ~4.2GB | ~1.5GB | 内存减少 64%（文本 LLM 已移除） |
| ~~动态加载~~ | 常驻 | 按需 | 相机场景释放 ~4.2GB（文本 LLM 已移除） |
| GPU 后端 | CPU 高负载 | GPU 承担 | CPU 占用降低 |
| 综合优化 | 可能 LMK 被杀 | 稳定运行 | 可用性提升 |

---

## 5. 测试用例

### 5.1 测试环境准备

#### 硬件要求

- Android 设备（API 24+）
- 已安装 PoLang 调试版 APK
- VLM 打标模型（qwen3_vl_2b）已下载
- ASR 模型（sherpa-onnx-zipformer-zh-en）已下载

#### ADB 日志准备

```bash
# 清除旧日志
adb logcat -c

# 开启过滤日志（保持终端运行）
adb logcat -s "MnnResourceManager:*" "LocalLlmEngine:*" "SherpaOnnxAsr:*" "VoiceCommand:*" "AgentOrchestrator:*" -v time
```

#### 内存监控（可选）

```bash
# 监控进程内存
adb shell dumpsys meminfo com.mamba.picme | grep -E "TOTAL|Java Heap|Native Heap"

# 或持续监控
while true; do
    adb shell dumpsys meminfo com.mamba.picme | grep "TOTAL PSS"
    sleep 5
done
```

### 5.2 测试用例

> **重要更新（2026-08）**：以下用例已按当前实现改写——`MnnResourceManager` 引用计数协调的双方为 **VLM 打标（`acquireLlm`/`releaseLlm`）与 MNN 人脸检测（`acquireFaceDetection`/`releaseFaceDetection`）**。历史版本中 ASR（`SherpaMnnAsrEngine`）一方的验证步骤已失效：ASR 迁移 Sherpa-ONNX 后不再接入 `MnnResourceManager`，其释放由 `SherpaOnnxAsrEngine.release()` 自行管理（日志 tag `SherpaOnnxAsr`）。

#### TC-001: 后台自动卸载（核心用例）

**目的**: 验证 App 进入后台后，VLM 和人脸检测按预期时间线卸载

**前置条件**:
- App 已启动，进入相机页
- 人脸检测初始化成功（`acquireFaceDetection()`）
- VLM 已加载（`acquireLlm()`）

**操作步骤**:

| 步骤 | 操作 | 期望日志 |
|------|------|----------|
| 1 | 打开 PoLang，进入相机页 | `FaceDetection acquired by MnnRoiDetector, refCount=1` |
| 2 | 触发 TAG 打标 | `LLM acquired by LocalLlmEngine, refCount=1` |
| 3 | 按 Home 键回到桌面 | `App entered background, scheduling unload` |
| 4 | 等待 30 秒 | `Background timeout, triggering soft trim for all` |
| 5 | 继续等待 30 秒（累计 60s） | `Background force unload timeout, triggering safe unload` |
| 6 | 观察最终状态 | VLM 与人脸检测均完全释放 |

**验证标准**:
- [ ] 30s 时触发 softTrim（VLM trimMemory + 人脸检测 softRelease）
- [ ] 60s 时触发 safeUnload（VLM performUnload + 人脸检测 performUnload）
- [ ] 内存下降（Native Heap 减少 1-2GB）

**通过标准**: 所有期望日志按顺序出现，无崩溃

---

#### TC-002: 页面退出释放人脸检测

**目的**: 验证离开相机页时人脸检测正确释放，VLM 不受场景切换影响

**前置条件**:
- App 在相机页，人脸检测已初始化

**操作步骤**:

| 步骤 | 操作 | 期望日志（VLM 未加载） | 期望日志（VLM 已加载） |
|------|------|------------------------|------------------------|
| 1 | 打开相机页 | `FaceDetection acquired, refCount=1` | `FaceDetection acquired, refCount=1` |
| 2 | 触发 TAG 打标（可选） | — | `LLM acquired, refCount=1` |
| 3 | 点击底部导航切换到相册页 | 场景切换触发人脸检测卸载 | 场景切换触发人脸检测卸载 |
| 4 | 观察释放行为 | `FaceDetection safe to unload` → 完全释放 | VLM 保持加载（跨页面保活，不响应场景切换） |

**验证标准**:
- [ ] 离开相机页后人脸检测 refCount 递减到 0 并释放
- [ ] VLM 引用不受场景切换影响（仅响应内存压力）
- [ ] ASR 由 `VoiceCommandCoordinator` 自行释放（日志 tag `SherpaOnnxAsr`，不经 `MnnResourceManager`）

**通过标准**: 人脸检测不再占用内存，无资源泄漏

---

#### TC-003: 内存压力紧急释放

**目的**: 验证系统内存紧张时立即释放模型

**前置条件**:
- App 在相机页，VLM + 人脸检测均已加载

**操作步骤**:

| 步骤 | 操作 | 期望日志 |
|------|------|----------|
| 1 | 进入相机页，触发 TAG 打标 | `LLM acquired` + `FaceDetection acquired` |
| 2 | 执行 ADB 命令模拟内存压力 | — |
| 3 | 观察日志 | `Memory pressure: LOW/CRITICAL, force unload` |
| 4 | 验证释放 | VLM 与人脸检测均完全释放 |

**ADB 命令**:
```bash
# 模拟 RUNNING_CRITICAL
adb shell am send-trim-memory com.mamba.picme RUNNING_CRITICAL

# 或模拟 COMPLETE
adb shell am send-trim-memory com.mamba.picme COMPLETE
```

**验证标准**:
- [ ] 命令执行后立即触发 safeUnload
- [ ] 无 30s/60s 延迟
- [ ] 模型完全释放

**通过标准**: 紧急释放成功，App 不崩溃

---

#### TC-004: 引用计数正确性（边界测试）

**目的**: 验证复杂场景下引用计数不泄漏、不负值

**前置条件**:
- App 已启动

**操作步骤**:

| 步骤 | 操作 | 期望 refCount | 期望状态 |
|------|------|---------------|----------|
| 1 | 打开相机页 | llm=0, face=1 | FACE_ONLY |
| 2 | 触发 TAG 打标 | llm=1, face=1 | SHARED |
| 3 | 切换到相册页（人脸检测卸载） | llm=1, face=0 | VLM_ONLY |
| 4 | TAG 打标继续 | llm=1, face=0 | VLM_ONLY |
| 5 | TAG 打标结束（VLM release） | llm=0, face=0 | IDLE |
| 6 | 再次进入相机页 | llm=0, face=1 | FACE_ONLY |
| 7 | 再次触发 TAG 打标 | llm=1, face=1 | SHARED |
| 8 | 按 Home 键 | — | 调度卸载 |
| 9 | 等待 60s | llm=0, face=0 | IDLE |

**验证标准**:
- [ ] 每个步骤 refCount 符合预期
- [ ] 无负值出现
- [ ] 重复进入/退出不累积泄漏

**通过标准**: 引用计数始终非负，最终可回到 IDLE

---

#### TC-005: 前台恢复可重新加载

**目的**: 验证卸载后重新进入前台，模型可正常恢复

**前置条件**:
- 已完成 TC-001，模型已卸载

**操作步骤**:

| 步骤 | 操作 | 期望日志 |
|------|------|----------|
| 1 | 后台 60s，确认模型已卸载 | VLM 与人脸检测均完全释放 |
| 2 | 重新打开 PoLang | `App entered foreground` |
| 3 | 进入相机页 | `FaceDetection acquired, refCount=1` |
| 4 | 触发 TAG 打标 | `LLM acquired, refCount=1` |
| 5 | 验证功能正常 | 语音识别成功，VLM 打标成功 |

**验证标准**:
- [ ] 前台恢复后模型可重新加载
- [ ] 语音识别功能正常
- [ ] VLM 打标功能正常

**通过标准**: 功能完全恢复，无异常

---

#### TC-006: 单例安全与重复加载

**目的**: 验证多处调用加载同一模型不会导致重复分配

**前置条件**:
- App 已启动

**操作步骤**:

| 步骤 | 操作 | 期望日志 |
|------|------|----------|
| 1 | 触发 TAG 打标 | `LLM acquired by LocalLlmEngine, refCount=1` |
| 2 | 进入相册页触发图像理解 | 复用同一 `LocalLlmEngine`，无新的 `nativeCreate` |
| 3 | 观察内存 | Native Heap 不重复增长 |

**验证标准**:
- [ ] `isLoaded` 幂等守卫生效
- [ ] 同一模型只 `nativeCreate` 一次
- [ ] 多次调用 `loadModel()` 安全

**通过标准**: 无重复加载，无 OOM

---

### 5.3 自动化测试脚本

```bash
#!/bin/bash
# scripts/test-mnn-unload.sh
# MNN 模型卸载自动化测试脚本

set -e

PACKAGE="com.mamba.picme"
LOG_FILE="/tmp/mnn_unload_test_$(date +%Y%m%d_%H%M%S).log"
TAGS="MnnResourceManager:LocalLlmEngine:SherpaOnnxAsr:VoiceCommand"

echo "=== MNN Unload Test ==="
echo "Log file: $LOG_FILE"

# 清理日志
adb logcat -c

# 启动日志收集
adb logcat -s "$TAGS" -v time > "$LOG_FILE" &
LOG_PID=$!

# 测试函数
run_test() {
    local test_name=$1
    local steps=$2
    echo ""
    echo "=== $test_name ==="
    eval "$steps"
}

# TC-001: 后台自动卸载
tc_001() {
    echo "Step 1: Launch app and enter camera"
    adb shell am start -n ${PACKAGE}/.MainActivity
    sleep 3

    echo "Step 2: Trigger voice command (simulate tap on voice button)"
    adb shell input tap 540 1800
    sleep 2

    echo "Step 3: Press Home"
    adb shell input keyevent KEYCODE_HOME
    sleep 2

    echo "Step 4: Wait 35s for softTrim"
    sleep 35

    echo "Step 5: Wait 30s more for safeUnload (total 65s)"
    sleep 30

    echo "Step 6: Check logs"
    if grep -q "VLM fully unloaded" "$LOG_FILE" && grep -q "FaceDetection safe to unload" "$LOG_FILE"; then
        echo "✓ TC-001 PASSED"
    else
        echo "✗ TC-001 FAILED"
        echo "Expected: VLM fully unloaded + FaceDetection safe to unload"
        grep -E "unloaded|trimmed|soft released" "$LOG_FILE" | tail -10
    fi
}

# TC-003: 内存压力紧急释放
tc_003() {
    echo "Step 1: Launch app and enter camera"
    adb shell am start -n ${PACKAGE}/.MainActivity
    sleep 3

    echo "Step 2: Trigger voice command"
    adb shell input tap 540 1800
    sleep 2

    echo "Step 3: Send trim memory command"
    adb shell am send-trim-memory ${PACKAGE} RUNNING_CRITICAL
    sleep 2

    echo "Step 4: Check logs"
    if grep -q "Memory pressure:.*force unload" "$LOG_FILE" && \
       grep -q "VLM fully unloaded" "$LOG_FILE"; then
        echo "✓ TC-003 PASSED"
    else
        echo "✗ TC-003 FAILED"
        grep -E "Memory pressure|force unload|fully unloaded" "$LOG_FILE" | tail -10
    fi
}

# 执行测试
run_test "TC-001: Background Auto Unload" tc_001

# 清理并重新收集日志
kill $LOG_PID 2>/dev/null
adb logcat -c
adb logcat -s "$TAGS" -v time > "$LOG_FILE" &
LOG_PID=$!

run_test "TC-003: Memory Pressure Emergency Unload" tc_003

# 清理
kill $LOG_PID 2>/dev/null

echo ""
echo "=== Test Complete ==="
echo "Full log: $LOG_FILE"
```

### 5.4 手动测试检查清单

#### 测试前检查

- [ ] 设备已连接，`adb devices` 显示设备
- [ ] PoLang 调试版已安装
- [ ] VLM 打标模型已下载（设置 → AI 模型管理）
- [ ] ASR 模型已下载
- [ ] 日志过滤命令已运行

#### 测试中记录

| 测试项 | 开始时间 | 结束时间 | 结果 | 备注 |
|--------|----------|----------|------|------|
| TC-001 | | | | |
| TC-002 | | | | |
| TC-003 | | | | |
| TC-004 | | | | |
| TC-005 | | | | |
| TC-006 | | | | |

#### 测试后检查

- [ ] 无崩溃、无 ANR
- [ ] 内存最终回到 IDLE 基线
- [ ] 功能可恢复

### 5.5 常见问题排查

#### Q1: 后台 60s 后模型未卸载

**排查步骤**:
1. 检查日志是否有 `App entered background`
2. 检查 `activityCount` 是否正确（是否有后台 Service 保持 Activity）
3. 检查 `backgroundUnloadScheduled` 是否为 true（可能被前台事件取消）

#### Q2: ASR 释放后 recognizer 仍存在

> ASR 已迁移至 Sherpa-ONNX（`SherpaOnnxAsrEngine`），不经 `MnnResourceManager` 引用计数。

**排查步骤**:
1. 检查日志是否有 `VoiceCommandCoordinator released`
2. 检查 `SherpaOnnxAsrEngine.release()` 是否被调用（日志 tag `SherpaOnnxAsr`，期望 `ASR fully released`）
3. 检查 recognizer 是否重复初始化

#### Q3: VLM unload 导致人脸检测崩溃

> 历史版本此问题为「VLM unload 导致 ASR 崩溃」；ASR 迁出后，同类风险存在于 VLM ↔ MNN 人脸检测之间。

**排查步骤**:
1. 检查 refCount 是否正确（不应在人脸检测引用存在时 safeUnload VLM）
2. 检查 `MnnResourceManager` 的协调逻辑
3. 查看崩溃堆栈是否涉及 MNN 全局状态

#### Q4: 多处加载导致 OOM

**排查步骤**:
1. 检查是否所有调用方都通过 `AgentOrchestrator.getInstance()`
2. 检查 `isLoaded` 幂等守卫是否生效
3. 检查 `MnnGlobalReleaseLock` 是否正常工作
4. 查看 Native Heap 是否有异常重复增长

---

## 6. 验收标准

- [ ] `MnnResourceManager` 单例可正常获取，引用计数正确增减
- [ ] VLM 加载后 `llmRefCount == 1`，卸载后 `llmRefCount == 0`
- [ ] 人脸检测初始化后 `faceDetectionRefCount == 1`，释放后 `faceDetectionRefCount == 0`
- [ ] 双方同时存在时，单方 release 只触发 softRelease
- [ ] 双方均 release 后触发真正的 native unload
- [ ] App 后台 30s 触发 softTrim，60s 触发 safeUnload
- [ ] `onTrimMemory(CRITICAL)` 立即触发 safeUnload
- [ ] 离开相机页后人脸检测不再泄漏（ASR 由 `SherpaOnnxAsrEngine` 自行管理，不在此列）
- [ ] ~~量化模型转换并测试推理质量~~（VLM 已是 INT4）
- [ ] GPU 后端兼容性测试（OpenCL 支持检测）
- [ ] VLM 打标动态加载/卸载功能验证
- [ ] 内存监控 + 自动降级逻辑
- [ ] ~~相机预览场景不加载 LLM~~（文本 LLM 已移除）
- [ ] 长推理 KV Cache 增长测试
- [ ] 编译通过，无 lint 错误

---

## 7. 相关文件

| 文件 | 说明 |
|------|------|
| `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt` | Agent 编排器 |
| `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/facade/AgentConfigurator.kt` | 配置器；`LocalLlmEngine` 由 Android 组合根（`androidApp/src/main/java/com/mamba/picme/agent/AndroidAgentComposition.kt`）直构注入 |
| `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/inference/local/llm/LocalLlmEngine.kt` | VLM 打标引擎（仅 `imageInference`） |
| `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt` | MNN LLM 客户端（VLM 打标 JNI 桥，`libagent_native.so` 由 `:engines:agent-native` 构建） |
| `engines/mnn-core/src/main/java/com/mamba/picme/mnn/MnnResourceManager.kt` | 资源协调管理器（含 `MnnGlobalReleaseLock`） |
| `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/platform/voice/SherpaOnnxAsrEngine.kt` | ASR 引擎（Sherpa-ONNX，不经 MNN 资源管理） |
| `androidApp/src/main/java/com/mamba/picme/features/camera/voice/VoiceCommandCoordinator.kt` | 语音协调器 |
| `androidApp/src/main/java/com/mamba/picme/features/camera/CameraScreen.kt` | 相机页面 |
| `androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt` | 应用入口 |
| `scripts/test-mnn-unload.sh` | 自动化测试脚本 |
| `docs/03-TECHNICAL-SPECS/VOICE_STACK.md` | 语音栈迁移后架构 |
