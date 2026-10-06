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
├── commonMain/    ← 引擎无关层（135 个 Kotlin 文件，见 §2）
├── androidMain/   ← Android 平台实现（VLM/语音/DataStore/dispatcher actual，14 文件）
├── iosMain/       ← iOS actual + Phase 6.2 chat 全链路（32 文件）：IosAgentComposition 组合根/ChatAgentBridge（Swift↔Kotlin 桥，多会话 setSessionId/clearHistory(sessionId:) 按 koog_memory_<sessionId> 分键隔离）/IosChatGalleryCapability（+IosChatGallerySearch 纯逻辑、IosChatSearchBridge 搜索引擎桥，契约 tmp/ios-follow/gallery-search/contracts.md §9）/IosKoogMessageMemoryStore（NSUserDefaults）/IosMediaRepository(+Bridge)/FlowWatchers/ChatUiActionDto/IosChatPrompt/IosAiOptimizeCapability(+IosAiOptimizeBridge)/IosChartCapability(+IosChartBridge)/IosRunScriptCapability(+IosRunScriptBridge)/IosRenderHtmlCapability(+IosRenderHtmlBridge，render_html 工具→HtmlCardSanitizer 清洗→Swift HtmlCardView，M5)/IosJsRuntimeSupport/IosDiagnosticLogStore/domain/chat/StreamingPacingControllerFactory/TimeProvider/domain/usertask IosUserTaskStore(+IosUserTaskStoreBridge Swift 持久化桥协议+createUserTaskRegistry 工厂，ios-follow/taskcenter)；唯一 stub 为 IosUnavailableImageInferenceEngine（端侧 VLM 未落地，显式空契约）
├── jvmMain/       ← JVM actual（5 文件：Platform/DispatcherProvider/AgentIdGenerator/KoogHttpClientFactoryProvider actual + domain/chat/TimeProvider.kt）
├── commonTest/    ← 多平台测试（kotlin.test，56 文件）
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
| `inference/remote/` | `KoogChatAgent`/`KoogReActAgent`/`KoogReActStrategy`（koog/）、`KoogMessageMemory`（koog/，三不变式 + M1 预算制组装：`estimateTokens`/`ageToolResults`/`trimToTokenBudget`/`assembleForPersistence` + M2 压缩候选选择 `selectCompactionCandidates`，双端 store save 统一入口，spec《chat-agent-layered-memory》）、M2 滚动摘要套件（koog/：`SessionCompaction`/`CompactionSlots` 四槽位 + 增量合并、`CompactionPrompt` 语义保护 prompt 构造、`SessionCompactor` 触发执行器、`SummaryGenerator` 函数接口 + `SummaryGeneratorViaExecutor` 经 Koog executor 单发、`composeChatSystemPrompt` 三段组装注入）、`ChatToolService`/`CameraToolService`/`ToolInventory`/`MemoryContextProvider`（tool/）、`RemotePromptBuilder`（prompt/，L2/L3/L4 遗留模板）、`ChatPromptRules`（prompt/，chat system prompt 行为规则段分节拼装）、`RemoteChatEngine`、`LlmCallRecorder` |
| `intent/` | `IntentGuard`（意图守卫层：LLM 误拒搜索回退判定、chat 页模糊跳转拦截；纯函数确定性规则，自 ChatViewModel 私有 guard 收口，双端可复用）；意图路由体系（2026-09-26，spec《意图路由契约与意图路由器》+ ADR-015）：`ChatIntentContract`（10 意图闭集契约表，allowed∩forbidden=∅ 机器校验）、`IntentRouter`（门控→pattern 捷径→LLM 闭集分类 1.5s 超时降级，路由审计 recorder 口）、`ChatRoutingPolicy`（意图→命令确定性查表，VIEW_PHOTOS/REFINE_RESULTS 直执） |
| `inference/local/` | `ImageInferenceEngine` 接口（端侧 VLM 抽象）、`LocalModelService` |
| `js/` | JS 引擎无关层（JsEngine/JsValue/JsBridge/JsRuntime/NativeHandler/BuiltInHandlers/GallerySummaryJs） |
| `runtime/` | `CapabilityRegistry`/`CommandExecutor`/`CrossPageCommandQueue`（capability/）、`PrivacyGuard`（policy/）、`SceneManager`（state/）、`ExecutionEngine`（execution/） |
| `capability/` | `Capability`/`BaseCapability`/`FaceDetectionProvider`、`BrowserSessionCapability`（2026-10-06 browser-vnc 直播卡 M1：云端浏览器 7 命令 browser_open/navigate/click/type/extract/screenshot/close；帧策略端侧决策——改状态动作 wantFrame、extract/close 不带；一切失败映射结构化降级 TextReply 不穿透 ReAct 链；`BrowserTransport` 传输接口（commonMain 无 HTTP 手段，组合根注入平台实现）+ `BrowserSessionDelegate` UI 事件出口（帧只走 UI 通道不回灌 LLM）） |
| `model/` | `AgentCommands`/`CommandRisk`/`EditParams`（command/）、`AiAgentConfig`/`AiAgentMode`（config/）、`AgentContext`/`GallerySummary`/`SearchIntent`/`MediaAsset`（context/） |
| `platform/` | `DispatcherProvider`/`ChatMemoryStore`/`KoogMessageMemoryCodec`/`Logger`/`AsrEngine` 等接口与 expect |
| `remote/config/` | `RemoteModelFactory`/`RemoteModelConfig`/`KoogHttpClientFactoryProvider`（按 `RemoteProtocol` 分流 OpenAI/Anthropic 客户端；DeepSeek 系 `thinking.type=disabled` 注入点，仅 tokenhub/kimi/deepseek 保留） |
| `tool/` | `CameraToolHelper`、`perception/UiObservationFormatter` |

**远程协议接入坑位（`RemoteModelFactory`，真机实证，2026-09-27 自根 `AGENTS.md` 移入）**：
- 自定义模型名须在 `LLModel.capabilities` 显式声明 `Completion, Tools, OpenAIEndpoint.Completions`——🔴 不加 Responses/Thinking，否则 Koog 选错端点。
- `RemoteProtocol.CLAUDE` 经 `AnthropicLLMClient`（Messages 协议）：自建 LLModel 须映射进 `AnthropicClientSettings.modelVersionsMap` 为模型 id 字符串（Koog 默认版本表只认其预定义实例）。
- 自定义 `poLangSingleRunStrategy` 修复 Koog 1.1.1 内建策略丢「文本+tool_calls 同帧」工具调用的缺陷。
- 协议分流收口在 `RemoteModelFactory.createKoogExecutor`。
- **Koog 实际版本 = 1.3.0**（`gradle/libs.versions.toml`；本文其余 1.1.1 表述为历史实证记录）。1.3.0 起消息模型变化：`MessagePart.Tool.Result` 内容为 `parts: List<ContentPart>`（读全文用 `output`，改写用 `copy(parts = …)`，String content 仅是便捷构造）；`MessagePart.Tool.Call` 参数字段为 `args`（非 arguments）。

另有 `beauty/api/`（BeautySettings/FilterType/StyleFilter，供 beauty-api 经 `api(project(":shared"))` 透出）、`domain/`（UserPreferences/MediaRepository/StructuredFilter/tag 聚类纯算法；旧 `DuplicateGroup` 已随去重 2.0（androidApp `domain/dedup/`）于 2026-08-26 删除）。2026-10-05 新增 `domain/tagscan/`（ScanProgressCalculator：TAG 扫描总进度双端 SSOT 纯函数——任务域基线加权进度 + 库域 photo-only 两阶段加权完成度，ETA 与进度同源；2026-10-05 口径立法，androidApp `domain/tag/scan/LibraryCompletion` 与 iOS（M2 待接）共用）。2026-10-03 任务中心上提（ios-follow/taskcenter，iOS 经 SKIE 消费）：`domain/usertask/`（UserTask 协议模型+5 枚举/UserTaskMapping 纯推导/UserTaskAdapter/UserTaskStore+UserTaskRow 存储抽象/UserTaskRegistry 混合注册表——写节流 WrittenRow（Mutex 保护）/HISTORY_KEEP=50/异常降级不穿透/trimHistory/进度内存快照，包名与 androidApp 原址相同经 api 透出，平台体系投影扩展留 androidApp 同包，Android 存储实现 = androidApp `data/local/RoomUserTaskStore`）；`domain/chat/taskcenter/`（TaskCenterPartition 分区纯逻辑 + EngineerTaskHtml 任务卡 L1 模板组装器/EngineerTaskPalette/EngineerTaskTexts/EngineerTaskThrottle，自 androidApp `features/chat/engineer/` 上提，包名改为 domain.chat.taskcenter）。2026-10-06 新增 `domain/browser/`（BrowserProtocol：云端浏览器端云同源 DTO SSOT——BrowserStatus 六状态闭集/BrowserAction 动作枚举/BrowserActionRequest（`targetIndex` wire 名 `index`，三模式定位 index>targetText>selector）/BrowserActionResult/BrowserFrameResult/BrowserElement，`App ↔ picme-server ↔ xuxing bridge` 三段共用同一 JSON 形态）与 `domain/chat/BrowserLiveOverlay.kt`（直播卡 live 态同 sessionId 原位覆写纯函数，见 §2 注记 browser 工具线）。

> 🔴 **`domain/chat/` 消息模型的上位约束（2026-09-27 起，宪法级）**：ChatMessage/MessagePart/流式 chunk/工具状态机的一切演进以 ADR-016 + spec `docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md` 为准（Vercel parts 模型：有序 parts 数组、块级 id、UIMessage/ModelMessage 双层分离、data part 默认不回灌 LLM）；type 值域与 role 另受分类法专项 spec `docs/superpowers/specs/2026-09-28-chat-type-taxonomy-design.md` 约束（3 分类 9 值——2026-10-06 扩 `tool_browser`——+ role 升格消息级独立列）。
>
> **M1 落地状态（2026-09-27；2026-09-28 分类法重构更新；2026-10-06 browser 扩值）**：`MessagePart` sealed（9 子类型：Text/Chart/HtmlCard/TaskCard/MediaResults/Image/EditResult/OptimizeCandidates/BrowserLive，partId 块级 id + `PartState`/`ToolPartState` 枚举 + `PartCategory` 分类载体 getter）、`MessagePartsCodec`（kotlinx JSON 线格式，`type` 鉴别字段 = 新 3 分类 9 值：text/image + tool_chart/tool_html/tool_task/tool_image_edit/tool_browser + data_media_results/data_optimize_candidates）、`MessagePartsConverter`（原 `LegacyMessagePartsConverter` 已改名——仅认新 8 值 + `role` 入参，行级 Text 兜底；另认 `tool_browser`：content 列存 BrowserLive 整颗 JSON 直通解码，无 legacy 源；legacy 13→8 映射收编 `LegacyChatTypeMigration`，仅供 Room MIGRATION_25_26 与备份恢复）、`toModelInput`/`ModelInputItem`（UIMessage→ModelMessage 显式转换）均已落地本包；`ChatMessage` 带消息级 `role` 字段（"user"/"agent"，取代 user_/agent_ type 前缀），`ChatMessage.parts` 双写共存（渲染源 M4 已切 parts）。`@Immutable` 不进 commonMain（纯度守卫禁 androidx.compose），Compose 稳定性注解属 M4 androidApp 侧收口。
>
> **browser 工具线（2026-10-06，browser-vnc 直播卡 M1）**：`MessagePart.BrowserLive`（`tool_browser`）为云端浏览器直播卡 part——瞬态轨 `browser_open` 类型化占位（`TurnPartsReducer`）→ 会话期间经 `BrowserLiveOverlay.overlayLiveBrowserState` 同 sessionId 原位覆写（帧/动作流水，形态与 `overlayLiveTaskState` 同构）→ 会话结束落定格卡（`OUTPUT_AVAILABLE`/`OUTPUT_ERROR`）；帧永不回灌 LLM（回灌投影 `browser_session` tool-call/tool-result 文本摘要对，见 `ChatModelInput`）。能力面见 §2 `capability/` 行与「另有」段 `domain/browser/`。
>
> **M2 落地状态（2026-09-27）**：流式管线三件套落地 `domain/chat/streaming/`——`TurnStreamEvent`（spec §4 块级三段式：Text 三事件 + 工具五事件）、`ChatStreamTurnAdapter`（Koog 累计快照语义 → 块级事件：差分 delta / 轮边界闭合 / `txt-N`·`call-N` 合成 id；「非扩展快照=轮边界」的单调追加前提与失效表现见其类注释）、`TurnPartsReducer`（占位契约：draw_chart/render_html 类型化占位 → 产物原位填充 / 无占位 append / OUTPUT_ERROR 进文档，DONE 不可变；单线程契约=调用方漏斗 Main.immediate）；`ChatStreamEvent.ToolCallStarted` 带 toolName/args（RemoteChatEngine 两发射点透传）；Chart/HtmlCard part 加 `state: ToolPartState`（持久化卡恒 OUTPUT_AVAILABLE 默认值，M1 存量行兼容）；Chart/HtmlCard 回灌 toolCallId 换 `"$messageId:$partId"` 命名空间锚；任务卡 live 态挂载收口 `TaskCardOverlay.overlayLiveTaskState`（part 同 id 原位覆写 + legacy 字段自 part 投影）。M2 期占位 parts 瞬态不落 Room、UI 渲染仍读 legacy 列（渲染源切换属 M4）；OUTPUT_ERROR 双轨口径见 spec §5.3（持久化轨=TaskCard，Chart/HtmlCard/脚本错误瞬态不落库）。
>
> **M5 落地状态（2026-09-28，iOS 跟随，chat.yaml §3.2 ios_todo 五项清零）**：commonMain 新增 `HtmlCardSanitizer`（render_html 卡片 HTML 清洗 SSOT，chat.yaml §13 契约：128KB 上限/剔远程 script/高危嵌入/meta refresh；androidApp `features.chat.HtmlCardSanitizer` 副本并存，收敛债）；iosMain 新增 `IosRenderHtmlCapability`(+`IosRenderHtmlBridge`) 挂 IosAgentComposition，及 `history/ChatHistoryStoreCodec`（iOS 会话 parts 落库 JSON 编解码，iosTest 覆盖 round-trip）；Swift 侧（ChatMessage/ChatView/ChatViewModel/HtmlCardView/AgentTextView 等）按拍平渲染矩阵对齐：parts 消费 + 拍平列表 item 化、turn 聚合间距阶梯（段落 4/卡片 8/回合 16 token 同源）、AST 正文渲染（GFM 分段+排版阶梯+parse gating）、HTML 卡双形态（INLINE 动态测高/FULLPAGE 预览+查看器+链接落地页）、type 分类法 3 分类 8 值 + role 独立列（iOS 零 legacy 包袱）。SKIE 边界实证：Kotlin `Int?` 在 Swift 构造器与属性均落 `KotlinInt?`（构造 `KotlinInt(int:)`，读取 `.int32Value`）。

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
> **最后更新**：2026-10-06
> **状态**：生效中
