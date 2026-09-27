# :shared 模块技术实现规范 (Shared KMP Module)

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:shared` KMP 模块的实现细节（target 结构、source set 分层、依赖方向、平台坑位）。
> - 顶层治理规则（全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`。

**模块定位**：`:shared` 是 PoLang Agent 编排层的 Kotlin Multiplatform 模块（Phase 4 自 `:runtime-core` 整体抽取，后者已于 2026-08-08 删除）。承载 Agent 编排、远程推理（Koog）、JS 引擎无关层、命令/能力模型、隐私守卫等**引擎无关逻辑**与**Android 平台实现**，供 `:androidApp` 消费；iOS target 已实装 Phase 6.2 chat 全链路（组合根/桥/能力/记忆存储，见 §1 iosMain）。

**主要维护者**：项目开发者

**阅读对象**：项目开发者、AI Agent

---

## 1. Target 与 source set 结构

```
shared/src/
├── commonMain/    ← 引擎无关层（104 个 Kotlin 文件，见 §2）
├── androidMain/   ← Android 平台实现（VLM/语音/DataStore/dispatcher actual，14 文件）
├── iosMain/       ← iOS actual + Phase 6.2 chat 全链路（26 文件）：IosAgentComposition 组合根/ChatAgentBridge（Swift↔Kotlin 桥，多会话 setSessionId/clearHistory(sessionId:) 按 koog_memory_<sessionId> 分键隔离）/IosChatGalleryCapability（+IosChatGallerySearch 纯逻辑、IosChatSearchBridge 搜索引擎桥，契约 tmp/ios-follow/gallery-search/contracts.md §9）/IosKoogMessageMemoryStore（NSUserDefaults）/IosMediaRepository(+Bridge)/FlowWatchers/ChatUiActionDto/IosChatPrompt/IosAiOptimizeCapability(+IosAiOptimizeBridge)/IosChartCapability(+IosChartBridge)/IosRunScriptCapability(+IosRunScriptBridge)/IosJsRuntimeSupport/IosDiagnosticLogStore/domain/chat/StreamingPacingControllerFactory/TimeProvider；唯一 stub 为 IosUnavailableImageInferenceEngine（端侧 VLM 未落地，显式空契约）
├── jvmMain/       ← JVM actual（5 文件：Platform/DispatcherProvider/AgentIdGenerator/KoogHttpClientFactoryProvider actual + domain/chat/TimeProvider.kt）
├── commonTest/    ← 多平台测试（kotlin.test，34 文件）
├── iosTest/       ← iOS 测试（6 文件）
└── jvmTest/       ← JVM-only 测试（@Tool 反射清单/prompt golden/守卫扫描，7 文件）
```

Gradle target：`android`（KMP android library 插件）+ `jvm()` + `iosX64()` + `iosArm64()` + `iosSimulatorArm64()`。

**分层规则**：

- **commonMain**：只依赖 Koog `koog-agents`（排除 `serialization-jackson`，jackson-module-kotlin 需 API 26，minSdk 24 下 D8 拒绝 dex）、kotlinx-coroutines/serialization/datetime。🔴 禁止 `import android.*` / `java.*`（iOS 编译会炸；Task 15 复验零泄漏）。
- **androidMain**：VLM 引擎（`inference/local/llm/`：LocalLlmEngine/MnnLlmClient/LlmModelManager）、语音（`platform/voice/`：SherpaOnnxAsrEngine/KeywordSpotterEngine/AudioRecorder）、DataStore 存储（`platform/storage/`：KoogMessageMemoryStore/MemoryManager）、`DispatcherProvider`/`AgentIdGenerator`/`KoogHttpClientFactoryProvider` actual。依赖 `:engines:mnn-core` + `:engines:agent-native`（VLM JNI `.so` 经 AAR 传递至 androidApp）。
- **jvmTest vs commonTest**：涉及 `@Tool` 元数据反射展开（`asToolsByClass`，Koog JVM-only API）、prompt 逐字节 golden、java.io 文件扫描（隐私守卫）的测试放 jvmTest；纯 common 逻辑放 commonTest（经 jvmTest 运行）。
- **iOS 互操作（2026-08-10 起）**：SKIE 0.10.14 插件已接入（`build.gradle.kts` 顶部 `alias(libs.plugins.skie)`）——suspend→`async throws`、sealed→Swift enum（`onEnum`）、Flow→`AsyncSequence` 直出 Swift 形态。**新链路一律用 SKIE 形态，不再新增 FlowWatcher 式手写桥**；存量桥迁移冻结至 iOS 1.0 功能冻结后。铁律详见 `skills/kmp-ios-interop`。

## 2. commonMain 核心组件（`agent/core/`）

| 子包 | 内容 |
|------|------|
| `facade/` | `AgentOrchestrator`（initialize(AgentDependencies) + 无参 getInstance）、`AgentConfigurator`、`AgentDependencies`（9 字段注入契约）、`LocalModelService` |
| `inference/remote/` | `KoogChatAgent`/`KoogReActAgent`/`KoogReActStrategy`（koog/）、`ChatToolService`/`CameraToolService`/`ToolInventory`/`MemoryContextProvider`（tool/）、`RemotePromptBuilder`（prompt/，L2/L3/L4 遗留模板）、`ChatPromptRules`（prompt/，chat system prompt 行为规则段分节拼装）、`RemoteChatEngine`、`LlmCallRecorder` |
| `intent/` | `IntentGuard`（意图守卫层：LLM 误拒搜索回退判定、chat 页模糊跳转拦截；纯函数确定性规则，自 ChatViewModel 私有 guard 收口，双端可复用）；意图路由体系（2026-09-26，spec《意图路由契约与意图路由器》+ ADR-015）：`ChatIntentContract`（10 意图闭集契约表，allowed∩forbidden=∅ 机器校验）、`IntentRouter`（门控→pattern 捷径→LLM 闭集分类 1.5s 超时降级，路由审计 recorder 口）、`ChatRoutingPolicy`（意图→命令确定性查表，VIEW_PHOTOS/REFINE_RESULTS 直执） |
| `inference/local/` | `ImageInferenceEngine` 接口（端侧 VLM 抽象）、`LocalModelService` |
| `js/` | JS 引擎无关层（JsEngine/JsValue/JsBridge/JsRuntime/NativeHandler/BuiltInHandlers/GallerySummaryJs） |
| `runtime/` | `CapabilityRegistry`/`CommandExecutor`/`CrossPageCommandQueue`（capability/）、`PrivacyGuard`（policy/）、`SceneManager`（state/）、`ExecutionEngine`（execution/） |
| `model/` | `AgentCommands`/`CommandRisk`/`EditParams`（command/）、`AiAgentConfig`/`AiAgentMode`（config/）、`AgentContext`/`GallerySummary`/`SearchIntent`/`MediaAsset`（context/） |
| `platform/` | `DispatcherProvider`/`ChatMemoryStore`/`KoogMessageMemoryCodec`/`Logger`/`AsrEngine` 等接口与 expect |
| `remote/config/` | `RemoteModelFactory`/`RemoteModelConfig`/`KoogHttpClientFactoryProvider`（按 `RemoteProtocol` 分流 OpenAI/Anthropic 客户端；DeepSeek 系 `thinking.type=disabled` 注入点，仅 tokenhub/kimi/deepseek 保留） |
| `tool/` | `CameraToolHelper`、`perception/UiObservationFormatter` |

**远程协议接入坑位（`RemoteModelFactory`，真机实证，2026-09-27 自根 `AGENTS.md` 移入）**：
- 自定义模型名须在 `LLModel.capabilities` 显式声明 `Completion, Tools, OpenAIEndpoint.Completions`——🔴 不加 Responses/Thinking，否则 Koog 选错端点。
- `RemoteProtocol.CLAUDE` 经 `AnthropicLLMClient`（Messages 协议）：自建 LLModel 须映射进 `AnthropicClientSettings.modelVersionsMap` 为模型 id 字符串（Koog 默认版本表只认其预定义实例）。
- 自定义 `poLangSingleRunStrategy` 修复 Koog 1.1.1 内建策略丢「文本+tool_calls 同帧」工具调用的缺陷。
- 协议分流收口在 `RemoteModelFactory.createKoogExecutor`。

另有 `beauty/api/`（BeautySettings/FilterType/StyleFilter，供 beauty-api 经 `api(project(":shared"))` 透出）、`domain/`（UserPreferences/MediaRepository/StructuredFilter/tag 聚类纯算法；旧 `DuplicateGroup` 已随去重 2.0（androidApp `domain/dedup/`）于 2026-08-26 删除）。

> 🔴 **`domain/chat/` 消息模型的上位约束（2026-09-27 起，宪法级）**：ChatMessage/MessagePart/流式 chunk/工具状态机的一切演进以 ADR-016 + spec `docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md` 为准（Vercel parts 模型：有序 parts 数组、块级 id、UIMessage/ModelMessage 双层分离、data part 默认不回灌 LLM）。
>
> **M1 落地状态（2026-09-27）**：`MessagePart` sealed（8 子类型：Text/Chart/HtmlCard/TaskCard/MediaResults/Image/EditResult/OptimizeCandidates，partId 块级 id + `PartState`/`ToolPartState` 枚举）、`MessagePartsCodec`（kotlinx JSON 线格式，`type` 鉴别字段对齐 legacy 列值）、`LegacyMessagePartsConverter`（13 种 Room type 全枚举 → parts，行级 Text 兜底）、`toModelInput`/`ModelInputItem`（UIMessage→ModelMessage 显式转换）均已落地本包；`ChatMessage.parts` 双写共存（UI 仍读 legacy 字段，M4 切换渲染源）。`@Immutable` 不进 commonMain（纯度守卫禁 androidx.compose），Compose 稳定性注解属 M4 androidApp 侧收口。
>
> **M2 落地状态（2026-09-27）**：流式管线三件套落地 `domain/chat/streaming/`——`TurnStreamEvent`（spec §4 块级三段式：Text 三事件 + 工具五事件）、`ChatStreamTurnAdapter`（Koog 累计快照语义 → 块级事件：差分 delta / 轮边界闭合 / `txt-N`·`call-N` 合成 id）、`TurnPartsReducer`（占位契约：draw_chart/render_html 类型化占位 → 产物原位填充 / OUTPUT_ERROR 进文档，DONE 不可变）；`ChatStreamEvent.ToolCallStarted` 带 toolName/args（RemoteChatEngine 两发射点透传）；Chart/HtmlCard part 加 `state: ToolPartState`（持久化卡恒 OUTPUT_AVAILABLE 默认值，M1 存量行兼容）；Chart/HtmlCard 回灌 toolCallId 换 `"$messageId:$partId"` 命名空间锚；任务卡 live 态挂载收口 `TaskCardOverlay.overlayLiveTaskState`（part 同 id 原位覆写 + legacy 字段自 part 投影）。M2 期占位 parts 瞬态不落 Room、UI 渲染仍读 legacy 列（渲染源切换属 M4）。

## 3. 依赖方向

```
:androidApp ──→ :shared ──→ Koog（外部）/ kotlinx-*
                  └── androidMain ──→ :engines:mnn-core、:engines:agent-native（implementation）
:engines:beauty-api ──→ :shared（api，BeautySettings 等公开 API 面需要）
```

Android 组合根：`androidApp/src/main/java/com/mamba/picme/agent/AndroidAgentComposition.kt`（平台实现唯一直构点，`AgentOrchestrator.initialize(AgentDependencies)` 注入；commonMain 无 `getInstance(context)` 旧签名）。

## 4. 编译与测试验证

```bash
# JVM 单测（commonTest + jvmTest 一起跑，107 用例 @2026-08-08）
JITPACK=true ./gradlew :shared:jvmTest

# 整体编译门槛（含 android AAR + iOS 三 target metadata，坑位④类问题只有这里能暴露）
JITPACK=true ./gradlew :shared:assemble

# iOS 单测（Intel 主机注意：iosSimulatorArm64Test 被 KGP 按 host arch 禁用，用 iosX64Test）
JITPACK=true ./gradlew :shared:iosX64Test

# iOS framework 产物（Phase 5 Task 1；Kotlin 2.2+ DSL 类名 XCFrameworkConfig）
JITPACK=true ./gradlew :shared:assembleSharedKitDebugXCFramework

# Android 侧编译
./gradlew :shared:compileAndroidMain
```

## 5. 平台坑位（Phase 4 实证，后续改动必读）

1. **无 androidUnitTest source set**：KMP android library 插件（`com.android.kotlin.multiplatform.library`）不产生该 source set；Android 侧单测一律留 `:androidApp`（经 `:shared` 依赖解析符号），勿迁 shared。
2. **Android 编译任务名是 `:shared:compileAndroidMain`**（非传统 AGP 的 `compileDebugKotlinAndroid`）。
3. **无 `assembleDebug`**（KMP 单 variant）：整体验证用 `:shared:assemble`。
4. **commonMain 禁用裸 `@Volatile`**（kotlin.jvm 包不自动导入）：用 `@kotlin.concurrent.Volatile`；只有 `:shared:assemble`/metadata 编译能暴露此类问题，jvmTest/compileAndroidMain 发现不了——验证门槛必须含 `:shared:assemble`。
5. **构建一律加 `JITPACK=true`**：阿里云镜像对 Koog iOS metadata jar 间歇 404 且不穿透到 mavenCentral；settings.gradle.kts 内置开关走 google/mavenCentral/jitpack。

## 6. 编码约定

- `System.currentTimeMillis()` → `kotlin.time.Clock`（纯 stdlib，未用 kotlinx-datetime 做时间戳）。
- 单例 `synchronized` → `lazy(SYNCHRONIZED)`；共享可变状态 → 协程 `Mutex`（访问点 suspend 化）或 StateFlow。
- `t.javaClass.simpleName` → `t::class.simpleName ?: "unknown"`。
- 反射 `@Tool` 展开只能在 Android/JVM 侧（组合根 `asToolsByClass()`），commonMain 注入 `ToolRegistry`/描述清单。

---

> **维护者**：项目开发者
> **最后更新**：2026-09-18
> **状态**：生效中
