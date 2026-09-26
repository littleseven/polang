# ADR-001: 大美丽单引擎分层架构

**状态**: 已接受 (Accepted)  
**日期**: 2026-04-17
**最后同步**: 2026-08-23（修正 §4.2/§4.3：`render/` 等为单模块内包目录而非 Gradle 子模块；ArchUnit 检查从未落地）  
**决策**: RD  
**PM Review**: 已完成

---

## 1. 背景与问题陈述

### 重构前架构
```
App Layer → 大美丽模块 (混合业务逻辑+GPU实现)
```

**核心问题**:
1. **层次越界**: 大美丽直接包含 GPU 渲染代码，违反 Clean Architecture
2. **强耦合**: App 层直接依赖引擎内部类，无法 Mock 测试
3. **复用阻塞**: 渲染逻辑被业务逻辑污染，无法独立作为视觉能力库输出
4. **性能风险**: EGL Context 管理分散，线程模型混乱

> **演进说明**: 架构经过多次迭代，最终沉淀为单引擎方案，当前仅保留自研大美丽（BIG_BEAUTY）引擎。

---

## 2. 决策目标架构

![美颜引擎四层架构](assets/diagrams/adr001-beauty-layering.png)

---

## 3. 关键决策

| 决策项 | 方案 |
|--------|------|
| **分层策略** | Domain(api) / Data(render) / External 三层分离 |
| **依赖方向** | App → api → render → 底层 GPU 驱动 (单向) |
| **接口定义** | `BeautyPreviewProvider` / `BeautyPreviewEngine` / `PhotoProcessor` 接口类，对外唯一出口 |
| **引擎封装** | 自研引擎统一走 `render/` 包，OpenGL ES 调用集中在 `CameraPreviewRenderer` / `BeautyRenderer` |
| **线程模型** | 独立渲染线程（`CameraPreviewRenderer`），`EGLCore` 管理 EGLContext；`PhotoProcessorImpl` 独立 EGL 上下文 |

---

## 4. 技术实现

### 4.1 模块结构
```
engines/beauty-engine/src/main/java/com/mamba/picme/beauty/
├── api/                    # Domain Layer - 实现层 API（依赖 :engines:beauty-api 共享类型）
│   ├── BeautyPreviewProvider.kt   # 预览 Provider 接口
│   ├── BeautyPreviewProviderFactory.kt # Provider 工厂
│   ├── BeautyPreviewEngine.kt     # 组合接口（Provider + Capability）
│   ├── BeautyPreviewCapability.kt # GL 能力扩展（FaceWarp/LipMask）
│   ├── PhotoProcessor.kt          # 拍照后处理接口
│   ├── BeautyParams.kt            # Shader 参数（来自 :engines:beauty-api）
│   ├── BeautyPerfStats.kt         # 性能统计（来自 :engines:beauty-api）
│   ├── FilterTypeExt.kt           # FilterType 扩展（来自 :engines:beauty-api）
│   ├── StyleFilterExt.kt          # StyleFilter 扩展（来自 :engines:beauty-api）
│   ├── BeautyParamsConverter.kt   # 参数转换
│   ├── Logger.kt                  # 日志接口
│   └── facedetect/
│       └── FaceDetectorFactory.kt # 人脸检测器工厂
│   > **Note**: `BeautyParams`, `FilterType`, `StyleFilter`, `FaceData`, `FrameId`, `FrameSyncConfig`, `FrameSyncResult`, `BeautyPerfStats` 等类型定义在 `:engines:beauty-api` 模块，`:engines:beauty-engine:api` 仅保留实现相关接口。
├── internal/               # 内部实现（帧同步 / 人脸检测 / 模型管理）
│   ├── framesync/          # 帧同步（FrameSyncManager/FrameSyncBridge/MotionTracker）
│   ├── facedetect/         # 人脸检测适配（FaceDetectorManager、MediaPipe*/Mnn* 检测器、mnn/）
│   └── model/              # 模型管理（ModelManager）
├── recorder/               # 视频录制（BeautyVideoRecorder）
├── log/                    # 日志实现（BeautyLog/BeautyLogProxy）
└── render/                 # Data Layer - 自研引擎 GL 渲染实现
    ├── GlBeautyPreviewProvider.kt   # Provider 接口实现
    ├── CameraPreviewRenderer.kt     # 渲染管线核心
    ├── BeautyRenderer.kt            # 美颜 Shader 渲染器
    ├── BeautyPass.kt                # 通用渲染 Pass 基类
    ├── FaceMakeupPass.kt            # 唇色/腮红三角网格 Pass
    ├── StyleEffectShader.kt         # 风格特效 Shader
    ├── PhotoProcessorImpl.kt        # 拍照 GPU 离屏渲染实现
    ├── EGLCore.kt                   # EGL 上下文管理
    ├── WindowSurface.kt             # EGL Window Surface 封装
    ├── Framebuffer.kt               # FBO 封装
    ├── FramebufferPool.kt           # FBO 对象池
    └── ShaderProgram.kt             # Shader 编译与链接
```

### 4.2 依赖规则 (Gradle)

`:engines:beauty-engine` 为**单 Gradle 模块**——`api/`、`internal/`、`render/`、`recorder/`、`log/` 是模块内**包目录**，不是子模块（`settings.gradle.kts` 仅声明 `:engines:beauty-api` 与 `:engines:beauty-engine` 两个引擎模块）：

```groovy
// App 层依赖（androidApp/build.gradle.kts 实况）
dependencies {
    implementation project(':engines:beauty-api')      // 共享类型契约（BeautySettings, FilterType, StyleFilter, Face 等）
    implementation project(':engines:beauty-engine')   // 实现层（含 api/internal/render 包）
}
```

**包边界（约定，非构建期强制）**：App 源码只允许 import `com.mamba.picme.beauty.api.*` 与 `:engines:beauty-api` 类型；直接引用 `com.mamba.picme.beauty.render.*` / `...beauty.internal.*` 视为违规。

### 4.3 依赖边界检查

> **现状（2026-08-23 核实）**：早期设计中提出的 ArchUnit 依赖检查**未落地**，仓库中不存在 ArchUnit 依赖；App → `render/`/`internal/` 的包边界目前依赖 code review 与 CLAUDE.md 依赖规则约束，无自动化守卫。若后续需要机器判定，可引入 ArchUnit 或自定义 lint 规则。

---

## 5. 后果分析

### 正面影响
- ✅ 接口契约稳定，支持版本化管理
- ✅ 单元测试可 Mock BeautyEngine 接口
- ✅ 引擎实现完全隔离，可独立演进
- ✅ GL 线程独立，不阻塞主线程
- ✅ 4K 大图分块处理，无 OOM 风险

### 负面影响
- ⚠️ EGL Context 初始化约 50-100ms
- ⚠️ Shader 编译首次加载约 100-200ms

---

## 6. 状态

| 阶段 | 状态 | 日期 |
|------|------|------|
| 接口提取 (Phase 1) | ✅ 完成 | 2026-04-17 |
| 实现迁移 (Phase 2) | ✅ 完成 | 2026-04-17 |
| App层适配 (Phase 3) | ✅ 完成 | 2026-04-17 |
| 引擎归一 (单引擎化) | ✅ 完成 | 2026-04-17 |
| 编译验证 | ✅ 通过 | 2026-04-17 |
| 安装运行 | ✅ 成功 | 2026-04-17 |

---

## 7. 相关文档

- `README.md` - 项目总览与架构图
- `engines/beauty-engine/AGENTS.md` - 模块详细规范
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` - 大美丽引擎技术规范
