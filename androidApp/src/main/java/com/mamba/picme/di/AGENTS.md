# 依赖注入模块技术实现规范 (Dependency Injection)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：确保组件间的依赖关系清晰、可测试且符合解耦原则。

**主要维护者**：项目开发者

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[DECOUPLING] 彻底解耦**：所有 Feature 模块不应直接实例化 Data 层类，必须通过 DI 获取 Domain 层定义的接口实现。
- **[TESTABILITY] 易测性**：依赖注入的设计必须支持在测试时轻松替换为 Mock 实现。
- **[LIFECYCLE] 生命周期对齐**：确保 Singleton、ActivityRetained 和 ViewModelScope 的作用域被正确使用，严禁内存泄漏。

## 2. 技术实现规范 (Technical Implementation)

### 2.1 AppContainer 架构

**技术规范**:
- **接口定义**: `AppContainer` 接口暴露全局依赖；字段清单以 `AppContainer.kt` 为准（已扩展至约百个依赖，本文不再维护快照）
- **实现类**: `AppContainerImpl` 使用 `by lazy` 延迟初始化，确保单例且线程安全
- **ViewModel Factory**: 通过 `MediaViewModelFactory` 注入 ViewModel 依赖，避免直接访问 Container
- **依赖数据结构**: `MediaViewModelDependencies` 封装 ViewModel 所需的全部依赖（字段清单以 `AppContainer.kt` 为准）

### 2.2 美颜引擎动态切换

> 跨模块容灾降级流程的完整说明请参阅 `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`。本节仅保留 DI 层的实现代码与关键约束。

**当前引擎现状（2026-04）**：

| 引擎 | `BeautyStrategy` 枚举值 | 实现类 | 状态 |
|---|---|---|---|
| 大美丽（自研 OpenGL ES） | `BIG_BEAUTY` | `GlBeautyPreviewProvider` | ✅ 默认启用 |
> 注意：当前为单引擎架构，DI 层不硬编引擎类型。

**技术规范**:
- **实时预览引擎切换**：通过 `rememberGlBeautyPreviewProvider(context, beautyStrategy)` Composable 创建/释放，DI 层不参与
- **拍照后处理器**：`PhotoProcessorImpl`（GPU 离屏渲染，复用多 Pass Shader 管线）作为静态 Bitmap 处理器，生产可用；GPU 路径失败时回退到 `GpuBeautyProcessor`（Canvas + ColorMatrix）
- **容灾降级**：大美丽初始化失败时记录异常并降级为无美颜预览（CameraX `PreviewView` 直出）
- **运行时状态**：使用 `BeautyEngineRuntimeState` 单例记录初始化异常原因，支持 UI 层查询并提示用户

**代码示例**:
```kotlin
// DI 层提供拍照后处理器：优先 GPU 路径，失败时回退 CPU 路径
private val photoProcessor: PhotoProcessor by lazy {
    PhotoProcessorImpl(context)  // GPU 离屏渲染，复用预览 Shader 管线
}

// CPU Fallback（GPU 路径失败时降级使用）
private val cpuBeautyProcessor: BeautyProcessor by lazy {
    GpuBeautyProcessor(context)  // Canvas + ColorMatrix，不涉及 GL
}
```

> 实时预览引擎由 Composable `rememberGlBeautyPreviewProvider` 维护（见 `GlBeautyPreviewRuntime.kt`），DI 层不参与；
> `BeautyEngineRuntimeState` 实现见 `di/BeautyEngineRuntimeState.kt`（`markGlEngineFallback` / `consumeGlEngineFallbackReason`）。

### 2.3 UseCase 与 OCR 集成

**技术规范**:
- **UseCase 实例化**: 在 Container 中创建单例 UseCase（`GetGroupedMediaUseCase`）
- **OCR 处理器**: `OcrUseCase` 作为单例注入 ViewModel，生命周期由 ViewModel 管理
- **依赖封装**: 通过 `MediaViewModelDependencies` 数据结构聚合 ViewModel 所需依赖（字段清单以 `AppContainer.kt` 为准）
- **Factory 模式**: `MediaViewModelFactory` 负责创建 ViewModel 并注入依赖；新增依赖时同步更新 Dependencies 数据类与 Factory 构造调用

### 2.4 Hilt 模块定义（预留扩展）

**当前状态**: 项目采用手动 DI（AppContainer），Hilt 为预留扩展方案、长期未启用；若启用，全局单例用 `@Singleton`、ViewModel 依赖用 `@HiltViewModel`/`@ViewModelScoped`，并在 Module 中显式绑定接口与实现。

## 3. Agent 执行规约 (Execution Rules)

- **延迟初始化**: 所有全局依赖必须使用 `by lazy` 确保单例且线程安全
- **故障回退**: 美颜引擎初始化失败时必须自动回退，严禁崩溃
- **状态管理**: 回退原因必须通过 `BeautyEngineRuntimeState` 记录，支持 UI 层查询与消费
- **依赖封装**: ViewModel 依赖必须通过 `MediaViewModelDependencies` 数据结构聚合
- **Factory 模式**: 禁止直接在 Activity/Fragment 中实例化 ViewModel，必须使用 Factory
- **I18N**: 回退提示文案必须提取到 strings.xml，支持多语言
- **日志规范**: 引擎切换、回退事件需记录 `PoLang:DI` 日志
- **生命周期对齐**: OCR Processor 必须在 ViewModel `onCleared()` 时释放资源

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 是否对所有全局依赖使用了 by lazy？(确保单例与线程安全)
- [ ] 美颜引擎初始化失败是否有降级机制？(try-catch + 无美颜 PreviewView 兜底)
- [ ] 回退原因是否正确记录到 BeautyEngineRuntimeState？(支持 UI 查询)
- [ ] ViewModel Factory 是否正确注入了全部依赖？(检查 Dependencies 数据类)
- [ ] 是否在 ViewModel onCleared 中释放了 OCR 资源？(避免内存泄漏)
- [ ] 新增 Repository 后是否更新了 AppContainer？(保持依赖完整性)
- [ ] 是否避免了在 ViewModel 中直接注入 Activity Context？(应使用 ApplicationContext)
- [ ] 单例对象是否真的需要全局唯一？(过度使用会增加状态管理复杂度)
- [ ] 是否正确区分了 Singleton 与 ViewModelScoped？(避免作用域混淆)
- [ ] 是否避免了循环依赖？(A 依赖 B，B 又依赖 A)

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 架构清晰 → 通过 DI 实现彻底的模块解耦
- ✅ 易于测试 → 所有依赖都可轻松替换为 Mock 实现
- ✅ 无内存泄漏 → 生命周期严格对齐，避免不当引用

**技术决策记录**:
- 选择手动 DI 而非 Hilt：项目规模适中，手动 DI 更轻量、易理解；Hilt 作为预留扩展
- 使用 by lazy 延迟初始化：确保单例、线程安全，避免启动时不必要的资源分配
- 单引擎策略（BIG_BEAUTY）：实时预览引擎由 Composable 层维护，DI 层不硬编
- 故障降级机制：大美丽 异常时降级为无美颜预览（PreviewView 直出），保证相机可用
- 依赖数据结构封装：通过 MediaViewModelDependencies 聚合依赖，简化 Factory 构造
- `GlBeautyPreviewProviderFactory`：工厂类仍保留用于创建 `PhotoProcessor`（拍照 GPU 路径），实时预览 Provider 由 `rememberGlBeautyPreviewProvider` Composable 接管（2026-04 更新）
