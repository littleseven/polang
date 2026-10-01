# iOS Chat 富交互 Implementation Plan（批次①）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐 task 实现。步骤用 `- [ ]` 跟踪。
> **执行方**：GLM（今天）/ kimi-code（明天恢复额度后可接替）。Claude Code 审查。
> **Spec**：[`../specs/2026-08-13-ios-chat-rich-features-design.md`](../specs/2026-08-13-ios-chat-rich-features-design.md)

**Goal:** iOS Chat 从 ~45% 对齐到 Android 全功能面，消息模型 + 节奏器 + Markdown 分段器下沉 commonMain 双端共用，CHART 端到端接通（iOS JS 沙盒用系统 JavaScriptCore）。

**Architecture:** commonMain 成为消息模型/节奏器/分段器/JS 引擎抽象的 SSOT；Android 删本地重复定义改引用 commonMain；iOS 用 JavaScriptCore 实现 commonMain `JsEngine` 接口，KMP 互操作消费 commonMain 模型。纯逻辑走 TDD（commonTest），UI 走编译+真机+XCUITest。

**Tech Stack:** KMP（shared commonMain/commonTest/iosMain）· Kotlin coroutines · JavaScriptCore（iOS 系统框架）· SwiftUI · XCUITest · ktlint/detekt · xcodegen

---

## 执行进展（2026-08-14，分支 `feat/ios-chat-rich-features`）

> 2026-09-27 复盘勾选：批次①已交付（见下方 ✅ 记录与 commit），全部 step 勾选闭合。

**已完成（7 commit，全程编译绿）**：
- Task 1a commonMain 下沉（ChatMessageType + StreamingPacingController + MarkdownSegmenter + commonTest 19 用例 + 纯度门）
- Task 2a Android 改引用（删本地重复 + import/`AgentSegmentType`→`SegmentType` 同步 + chat 单测绿）
- shared-fix：`@Volatile` 移除（commonMain 跨平台）+ iosMain `createStreamingPacingController` 工厂
- Task 5 iOS 流式节奏器接入（逐字吐 50ms/字 + 光标内联右侧 + showCursor 驱动）
- Task 6 iOS Markdown 富渲染（AgentTextView 分段：表格网格 + 代码块折叠/复制）
- Task 8 媒体反馈 👍👎🔄（MediaThumbnail 本地态）+ 模型胶囊（inputBar Menu 读 ModelConfigStore）
- Task 9 键盘避让（已 done `d56fb3f3`）
- **Task 7 渲染层**：`ChartJsEngine`（JavaScriptCore + `chart_bootstrap.js`，纯 JS 拼 SVG，不需 gallery handler）+ `ChartSvgCard`（WKWebView）+ `ChatMessage.chartSvg` + `MessageBubble` 分发 + `/chart` demo。真机 XCUITest `testChartRenderDemo` 通过（15.2s，`chat_chart_card` 出现 = 渲染链路端到端通）

**进行中（Task 7 触发链：LLM draw_chart → 端侧渲染）**：
- 设计：Android 经 `ChatRunScriptCapability.Delegate.onDrawChart` 直进 app 层 ChatViewModel；iOS 能力在组合根构造（早于 ChatViewModel），故走 **Swift 桥**——SVG 不跨 K/N 边界，仅 summary 回传 LLM。**零 commonMain / androidMain 改动**（图表渲染纯 iOS 关注点）。
- 实现（4 组件）：
  - iosMain `data/IosChartBridge.kt`：Swift→Kotlin 渲染桥协议（`renderChart(type,title,labels,values,unit,onResult)`）
  - iosMain `capability/IosChartCapability.kt`：`draw_chart` 执行端（`supportedCommands=["draw_chart"]`，suspendCancellableCoroutine 包 onResult → `AgentAction.TextReply(summary)`）
  - `IosAgentComposition.initialize` 加 `chartBridge` 参数 + 注册 `IosChartCapability`
  - Swift `Platform/ChartRendererBridge.swift`（conforms `IosChartBridge`，调 ChartJsEngine，SVG 经 `onChart` 静态闭包交 ChatViewModel）+ `AppContainer` 接线 + `ChatViewModel.appendChartMessage`
- 路由验证：`CapabilityRegistry.findCapabilityForCommand` 按 `supportedCommands()` 匹配，`draw_chart` 独占无冲突
- **✅ 真机验证通过**：确定性 `/charttool` 测试（`testChartTriggerChain`，经 `ChatAgentBridge.dispatchDrawChart` 派发，绕过 LLM）通过 14.4s，图卡渲染。**注意**：访客模型经实测未稳定发起 `draw_chart` tool_call（回文字/表格）——属模型/提示词行为，非接线缺陷（接线已由 `/charttool` 确定性证明）。更强模型（DeepSeek 等用户自配）或加强 iOS system prompt 工具引导可改善。
- **K/N 互操作坑**：`List<Double>` 经 K/N 导出为 `[KotlinDouble]`（NSNumber 子类，apinotes 映射 `SharedKitDouble`→`KotlinDouble`，`.doubleValue` 取值）；Swift→Kotlin 方向传数值列表用 CSV 字符串规避 boxing（`dispatchDrawChart` 用 `valuesCsv`）。SharedKit 改 Kotlin 后增量构建 clang 模块缓存陈旧（Swift 找不到新符号）——**清 derivedData 全量重建**可解（`touch .shared-kit-hash` 跳过伎俩勿用于改了 Kotlin 的场景）。

**留下一单元（高复杂度 / 需决策）**：
- ~~**Task 1b ChatMessage 全字段下沉**~~ **✅ 完成（commit 64d3b70a）**：全字段 + 子类型下沉 commonMain `domain.chat`；org.json 序列化剥离到 androidApp 扩展（ChatModelCommonMainShim）；timestamp 用 expect/actual `nowEpochMillis()`（androidMain/iosMain）；androidApp typealias ChatMessageUi + 直接 import 子类型零语义改动。Android 编译+chat 单测+ktlint+我的文件 detekt 零违规 + commonMain 纯度门 + iosArm64 编译全过。iOS 暂未消费（Swift 原生 ChatMessage）。
- **Task 4 run_gallery_script（JS 沙盒）—— Tier 1+2+3 ✅ 实现（12/12 只读 handler 齐备，编译绿 + 真机单测 9/9 通过）**：CHART 渲染已端侧化，故 Task 4 聚焦 `run_gallery_script`。
  - **Tier 1（沙盒基建 + 3 盘点 handler）✅**（commit 27c3d58a）：
  - **Swift 基建**：`Platform/Js/JsValueConverter.swift`（JSValue↔commonMain `JsValue`；CFBoolean 识别 bool）；`JsCoreEngine.swift`（JavaScriptCore 实现 `JsEngine`+`JsClosable`）。
  - **JSCore async 解法（核心）**：JSCore `evaluateScript` 不能像 QuickJS pump pending job / 中断 C 死循环。解法：`__bridgeCallAsync` block 内 `DispatchSemaphore` **同步等** handler 完成，返回**已 settle 的 Promise**——JS 侧 `await` 立即 resolve，整条 async IIFE 在一次 `evaluateScript` 内同步跑完，QuickJS 的 `ASYNC_WRAPPER`/`READ_ASYNC_RESULT` 两段式 JS **原样复用**。死锁前提：JsRuntime scope 用 `Dispatchers.Default`（≠ evalAsync 线程）。evalAsync 把脚本异常（含 rejection 转 throw）转可读串返回（与 Android「抛出→capability 捕获→回传 LLM 文案」终态等价）。
  - **iosMain**：`IosRunScriptBridge`（Swift→Kotlin 桥协议）+ `IosRunScriptCapability`（`run_gallery_script` 执行端，镜像 IosChartCapability）+ `IosJsRuntimeSupport.createIosJsRuntime`（后台 scope 工厂）。`ChatAgentBridge.dispatchRunScript`（确定性全链路测试）。
  - **gallery 只读 handler（3/12）**：`gallery.summary`（ScanDbStats+mediaTypeCounts）、`gallery.tags`（tags 表+cross_ref 聚合，新 `TagDatabase+GalleryJs`）、`tag.scan_status`（TagScanOrchestrator.currentProgress）。Swift `NativeHandlerAsync`（SKIE 重写为 `__invoke(args:completionHandler:)`）。
  - **接线**：`RunScriptBridge`（懒建 JsRuntime+注册 handler）→ `AppContainer` → `IosAgentComposition.initialize(runScriptBridge:)` 注册 `IosRunScriptCapability`。
  - **K/N 互操作坑（记录）**：① SKIE 把 Kotlin `suspend invoke` 重写为 Swift 协议方法 **`__invoke`**（双下划线），非 `invoke`；② `JsValue` 子类 Swift 名是**嵌套点号** `JsValue.Num/Str/Bool/Obj/Arr/Null`（非 `JsValueNum`）——header `swift_name("JsValue.Num")`；③ 大字典字面量致 Swift 类型检查器超时→级联 "cannot find" 假错误，须逐项赋值拆开。
  - **Tier 2（query/meta/batch_meta/stats_by_tag 只读 handler）✅**（commit 61bb6678，hash 见 git log）：`TagDatabase+GalleryJs` 加 `MediaDbRow`（白名单，无 uri/gps/ocrText/embedding）+ `mediaRow/mediaRows/personMediaIds/queryMediaIds(参数化动态 SQL，filter 值来自 LLM JS 防注入)/tagsByFilter`；Swift `parseQueryFilter`（移植）+ 4 handler。gallery.query 用单条动态 WHERE（多维 AND，对齐 Android applyFilter 语义），person 先独立 queue.sync 解析（避免串行队列重入死锁）。`gallery.tags`/`stats_by_tag` 形状对齐 Android 扁平 `{tag:count}`。纯 Swift（无 Kotlin 改动→免重编框架）。
  - **Tier 3 只读（timeline/intersect/stats_by_city/face.cluster/tag.audit 5 handler）✅**：TagDatabase 加 timelineCounts（时间分桶）/cityCounts（GROUP BY city）/embeddingCounts（face_embeddings total+unassigned）/tagAuditCounts（COUNT 聚合）；intersect 纯端侧集合运算（保序去重 LinkedOrderSet）；outOfVocabTags 从 controlled_vocab.json 加载词表全集（懒缓存）过滤。**12/12 只读 handler 齐备**。纯 Swift，JsCoreEngineTest 9/9 通过。
  - **修复（2026-08-14，用户报「相册健康度报告」报错）**：两处缺口——① chat 沙盒未注入 `chart_bootstrap.js`（`Chart` 全局缺失，LLM 按工具描述 `return Chart.timeline(...)` → ReferenceError）；② `JsCoreEngine` 装了 exceptionHandler 后 JSCore **不再写 `context.exception`**（异常被吞成 `null` 回传，evalAsync 错误检测失效）。修复：`ensureRuntime` eval chart_bootstrap（对齐 Android getOrCreateJsRuntime）+ runScript 图表拦截（`{chart,summary}` → SVG 经 `ChartRendererBridge.onChart` 渲图卡、summary 回传 LLM，对齐 onRunScript）+ 异常自存 `lastException` 上送 `[脚本错误] …`。`testRunScriptChartReturn`（健康度报告形态脚本）锁定，JsCoreEngineTest 10/10。
  - **剩余（写操作，子系统级）**：capability.dispatch 写操作（delete_media/favorite_media/select_media/remember_fact/forget_fact/recall_memory/remember_person_relation/forget_person_relation/query_person_relation）需用户确认弹窗 UI。CHART 已不需 JS 沙盒。
- ~~**Task 1b ChatMessage 全字段下沉**~~ **✅ 完成（commit 64d3b70a）**：全字段 + 子类型下沉 commonMain `domain.chat`；org.json 序列化剥离到 androidApp 扩展（ChatModelCommonMainShim）；timestamp 用 expect/actual `nowEpochMillis()`（androidMain/iosMain）；androidApp typealias ChatMessageUi + 直接 import 子类型零语义改动。Android 编译+chat 单测+ktlint+我的文件 detekt 零违规 + commonMain 纯度门 + iosArm64 编译全过。iOS 暂未消费（Swift 原生 ChatMessage）。
- ~~**Task 3** iOS 模型接入 commonMain~~ **✅ 以字段对齐方式完成（批次② `2026-08-14-ios-chat-image-messages.md` Task 1）**：iOS `ChatMessage` 保持 Swift struct（KMP Codable 踩坑，Task 3 既定方案），加 `MessageType`（对齐 commonMain ChatMessageType 子集）+ `imageUri` + Codable 老数据推断（ChatMessageTypeTests 5 用例锁定）。type 字段成为图片消息渲染地基（上图下文/编辑结果/全屏预览，批次②全交付）。

---

## File Structure（文件职责映射）

### commonMain 新增（`shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/`）
| 文件 | 职责 |
|---|---|
| `ChatMessageType.kt` | 11 type 枚举（SSOT） |
| `ChatMessage.kt` | 全字段消息模型（1:1 对齐 Android ChatMessageUi）+ 子类型（`LlmPerformance`/`MediaResultsUi`/`ClaudeAgentState`/`ClaudeDeliverUi`/`OptimizeCandidateGroup` 同包） |
| `streaming/StreamingPacingController.kt` | 流式吐字节奏器（从 androidApp 迁入，逻辑零改动，包名改） |
| `markdown/MarkdownSegmenter.kt` | MARKDOWN/TABLE/CODE 三分段（从 `ClaudeMarkdownSegmenter` 迁入，去 Claude 前缀） |

### commonTest 新增（`shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/`）
- `streaming/StreamingPacingControllerTest.kt`（从 androidApp test 迁入）
- `markdown/MarkdownSegmenterTest.kt`（从 androidApp test 迁入）

### androidApp 修改
- `ChatScreen.kt`：删 `enum class ChatMessageType`（line 2380）+ `data class ChatMessageUi`（line 2350）改 typealias commonMain；子类型删本地定义
- `ChatViewModel.kt`：StreamingPacingController / 消息类型 import 改 commonMain 包
- 删 `features/chat/streaming/StreamingPacingController.kt`（迁 commonMain）
- 删 `features/chat/ClaudeMarkdownSegmenter.kt`（迁 commonMain）
- 删对应 test（迁 commonTest）

### iosApp 新增/修改
| 文件 | 职责 |
|---|---|
| `Features/Chat/ChatMessage.swift`（改） | Codable wrapper 消费 commonMain `ChatMessage`，旧→新映射 |
| `Features/Chat/ChatViewModel.swift`（改） | 接 StreamingPacingController + emitChartMessage + feedbackState |
| `Features/Chat/ChatView.swift`（改） | type 分发 + 反馈按钮 + 模型胶囊 + 回退键盘避让 |
| `Features/Chat/AgentTextView.swift`（新） | segment 分段渲染（MARKDOWN/TABLE/CODE） |
| `Features/Chat/ChartSvgCard.swift`（新） | WKWebView 渲染 SVG |
| `Platform/Js/JsCoreEngine.swift`（新） | JavaScriptCore 实现 commonMain `JsEngine` |
| `Platform/Js/JsValueConverter.swift`（新） | JSValue ↔ commonMain JsValue |
| `Platform/Js/GalleryHandlers.swift`（新） | gallery/media native handler（读 PHAsset/TagDatabase） |

---

## Task 1: commonMain 下沉（模型 + 节奏器 + 分段器）

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessageType.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/streaming/StreamingPacingController.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/markdown/MarkdownSegmenter.kt`
- Create: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/streaming/StreamingPacingControllerTest.kt`
- Create: `shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/markdown/MarkdownSegmenterTest.kt`

- [x] **Step 1: ChatMessageType.kt**

```kotlin
package com.mamba.picme.domain.chat
enum class ChatMessageType {
    USER_TEXT, AGENT_TEXT, USER_IMAGE, USER_IMAGE_TEXT, AGENT_IMAGE,
    AGENT_EDIT_RESULT, COMMAND, PLAN_PREVIEW, MEDIA_RESULTS, CHART, OPTIMIZE_CANDIDATES
}
```

- [x] **Step 2: ChatMessage.kt（全字段 + 子类型）**

先 grep 确认 Android 子类型纯度：
```bash
grep -rn "data class OptimizeCandidateGroup\|data class ClaudeAgentState\|data class ClaudeDeliverUi\|data class LlmPerformance\|data class MediaResultsUi" androidApp/src/main/java --include=*.kt
```
对每个子类型 `cat <file>` 确认无 `android.*`/`androidx.*`/compose import。若有，剥离（换 Kotlin 标准类型）后下沉。全部放 `domain/chat/` 同包（或子包），`ChatMessage` 全字段引用：

```kotlin
package com.mamba.picme.domain.chat
data class ChatMessage(
    val id: String,
    val type: ChatMessageType,
    val content: String,
    val modelUsed: String? = null,
    val timestamp: Long,
    val performance: LlmPerformance? = null,
    val mediaResults: MediaResultsUi? = null,
    val imageUri: String? = null,
    val chartSvg: String? = null,
    val imageSaved: Boolean = false,
    val isStreaming: Boolean = false,
    val showCursor: Boolean = false,
    val isThinking: Boolean = false,
    val claudeAgent: ClaudeAgentState? = null,
    val claudeDeliver: ClaudeDeliverUi? = null,
    val optimizeCandidates: OptimizeCandidateGroup? = null,
    val gachaInteractive: Boolean = false,
    val error: String? = null,
)
```

- [x] **Step 3: StreamingPacingController.kt（迁移）**

整文件复制 `androidApp/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt` → 新路径。仅改：`package com.mamba.picme.domain.chat.streaming`。逻辑/常量/import 零改动（已确认仅依赖 `kotlinx.coroutines` + `kotlin.math`，commonMain 可用）。

- [x] **Step 4: MarkdownSegmenter.kt（迁移）**

复制 `androidApp/.../chat/ClaudeMarkdownSegmenter.kt` → 新路径，重命名 `MarkdownSegmenter`（类名 + 文件名去 Claude 前缀）。改 `package com.mamba.picme.domain.chat.markdown`。`enum AgentSegmentType`→`SegmentType`，`AgentSegment`→`Segment`。逻辑零改动（纯 Kotlin + 正则）。

- [x] **Step 5: 迁移 commonTest**

复制 `androidApp/.../chat/streaming/StreamingPacingControllerTest.kt` 和 `ClaudeMarkdownSegmenterTest.kt` → commonTest 对应路径。改 package + import 指向 commonMain。`runTest` 在 commonTest 可用。

- [x] **Step 6: 验证（TDD pass + 纯度）**

```bash
./gradlew :shared:checkCommonMainPurity         # 纯度门（ADR-013）
./gradlew :shared:jvmTest --tests "*StreamingPacingController*" --tests "*MarkdownSegmenter*"
```
Expected: 纯度检查绿；commonTest 全绿（节奏器 8 用例 + 分段器用例）。

- [x] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ shared/src/commonTest/kotlin/com/mamba/picme/domain/chat/
git commit -m "feat(shared): Chat 消息模型/节奏器/分段器下沉 commonMain"
```

---

## Task 2: Android 改引用 commonMain

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（line 2350 `ChatMessageUi`、line 2380 `ChatMessageType`）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（StreamingPacingController import）
- Delete: `androidApp/.../features/chat/streaming/StreamingPacingController.kt`
- Delete: `androidApp/.../features/chat/ClaudeMarkdownSegmenter.kt`
- Delete: 对应 androidApp test

- [x] **Step 1: ChatScreen.kt 改 typealias**

`ChatScreen.kt:2350` `data class ChatMessageUi(...)` → 替换为：
```kotlin
import com.mamba.picme.domain.chat.ChatMessage
typealias ChatMessageUi = ChatMessage
```
删 `enum class ChatMessageType`（line 2380，已下沉）。删本地子类型定义（OptimizeCandidateGroup 等，若在 ChatScreen.kt 或邻文件——grep 定位，已迁 commonMain 则删本地）。
ChatScreen 全文 `ChatMessageUi` / `ChatMessageType` 引用不变（typealias 透明）。若字段名差异（如 Android `content` vs commonMain `content`）已对齐，无需改。

- [x] **Step 2: ChatViewModel.kt import 改包**

`import com.mamba.picme.features.chat.streaming.StreamingPacingController` → `import com.mamba.picme.domain.chat.streaming.StreamingPacingController`。同理 `ClaudeMarkdownSegmenter`→`MarkdownSegmenter`（domain.chat.markdown）。

- [x] **Step 3: 删 Android 本地文件**

```bash
git rm androidApp/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt
git rm androidApp/src/main/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenter.kt
git rm androidApp/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt
git rm androidApp/src/test/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenterTest.kt
```

- [x] **Step 4: 验证编译 + Chat 回归**

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:testDebugUnitTest --tests "*chat*"
```
Expected: assembleDebug 绿；chat 相关单测绿（无回归）。若编译报 `ChatMessageUi` 字段缺失，对照 commonMain `ChatMessage` 补 typealias 或调整引用。

- [x] **Step 5: Commit**

```bash
git add -A androidApp/
git commit -m "refactor(android): Chat 模型/节奏器/分段器改引用 commonMain，删本地重复"
```

---

## Task 3: iOS 消息模型接入 + Codable 兼容

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatMessage.swift`
- Modify: `iosApp/PoLang/Features/Chat/ChatHistoryStore.swift`（如有解码逻辑）

- [x] **Step 1: ChatMessage.swift 改 wrapper 消费 commonMain**

iOS 不直接用 commonMain `ChatMessage` 做 Codable（KMP Codable 跨平台复杂）。保留 `ChatMessage` 为 Swift struct，字段对齐 commonMain，`Codable` 向前兼容老数据（旧字段 role+text+mediaIds）：

```swift
struct ChatMessage: Identifiable, Codable {
    let id: UUID
    var type: MessageType      // 新：对齐 commonMain ChatMessageType
    var content: String
    let timestamp: Date
    var isStreaming: Bool
    var showCursor: Bool       // 新：节奏器驱动
    var isThinking: Bool
    var isToolCalling: Bool
    var mediaIds: [Int64]
    var mediaQuery: String?
    var mediaTotalCount: Int?
    var imageUri: String?
    var chartSvg: String?      // 新：CHART
    var error: String?
    // commonMain 的 claudeAgent/optimizeCandidates/performance 等：本批 iOS 不渲染，
    // Codable 用 optional + 忽略未知键（Codable 默认忽略解码端多余键需自定义）

    enum MessageType: String, Codable {
        case userText, agentText, userImage, userImageText, agentImage,
             agentEditResult, command, planPreview, mediaResults, chart, optimizeCandidates
    }
    // ... init 保持
}
```
旧→新映射（`init(from decoder:)`）：若解码到老字段 `role`(user/assistant) + 无 `type`，推断：role==user→.userText；assistant 无 mediaIds→.agentText；有 mediaIds→.mediaResults。`showCursor`/`chartSvg` 等新字段缺省补 false/nil。`content` 兼容老 `text` 字段名（CodingKeys 映射 text↔content）。

> 注：iOS `ChatMessage` 是 commonMain 模型的 Swift 投影（非 KMP 直接互操作），通过 ViewModel 在边界转换。本批保持 Swift-owned（避免 KMP Codable 踩坑），后续 KMP 整理阶段再评估直接消费 commonMain。

- [x] **Step 2: ChatViewModel/ChatView 字段引用更新**

`messages[idx].text` → `messages[idx].content`（全局替换，因字段改名 text→content 对齐 commonMain）。`streamingUpdate` 等更新 content + showCursor。

- [x] **Step 3: 验证 iOS 编译**

```bash
cd iosApp && xcodegen generate && pod install && xcodebuild -workspace polang.xcworkspace -scheme polang -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build 2>&1 | tail -5
```
（或 device 构建：`-sdk iphoneos -destination 'generic/platform=iOS'`，按 Intel 机用 device，见 memory `ios-intel-dev-device-only-build`）
Expected: BUILD SUCCEEDED。旧 chat_history JSON 能解码（写个临时单测或真机验证老会话可见）。

- [x] **Step 4: Commit**

```bash
git add iosApp/PoLang/Features/Chat/
git commit -m "feat(ios): Chat 消息模型扩字段对齐 commonMain + Codable 向前兼容"
```

---

## Task 4: iOS JS 沙盒接入（JavaScriptCore）— CHART 前置

**Files:**
- Create: `iosApp/PoLang/Platform/Js/JsCoreEngine.swift`
- Create: `iosApp/PoLang/Platform/Js/JsValueConverter.swift`
- Create: `iosApp/PoLang/Platform/Js/GalleryHandlers.swift`
- Test: `iosApp/PoLangTests/JsCoreEngineTest.swift`

> 对照 Android `androidApp/.../chat/js/QuickJsEngine.kt`（实现同一 `JsEngine` 接口）+ `shared/.../agent/core/js/JsEngine.kt`（接口定义 5 方法）。

- [x] **Step 1: 写失败测试（JS 引擎基础）**

```swift
// JsCoreEngineTest.swift
import XCTest
@testable import PoLang

final class JsCoreEngineTest: XCTestCase {
    func testEvalArithmetic() throws {
        let engine = JsCoreEngine()
        let result = try engine.eval(script: "1 + 2")
        XCTAssertEqual(result, .num(3.0))   // commonMain JsValue.num
    }
    func testBridgeCallGallerySummary() throws {
        // 注册 gallery.summary handler 返回固定串，JS 调 bridge.callAsync 验证
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
xcodebuild test -scheme polang -destination 'platform=iOS,name=<device>' -only-testing:PoLangTests/JsCoreEngineTest
```
Expected: FAIL（JsCoreEngine 未定义）。

- [x] **Step 3: JsValueConverter.swift**

`JSValue` ↔ commonMain `JsValue`（SharedKit 暴露的 Kotlin protocol）。对照 Android `QuickJsConverter`。核心：JSValue.number/.string/.bool/.array/.dictionary → JsValue.num/str/bool/arr/obj；null/undefined→JsValue.null。

- [x] **Step 4: JsCoreEngine.swift（实现 JsEngine 5 方法）**

```swift
import JavaScriptCore
import SharedKit   // commonMain JsEngine/JsBridge/JsValue

final class JsCoreEngine: JsEngine, JsClosable {
    private let vm = JSVirtualMachine()
    private let context: JSContext
    init() { context = JSContext(virtualMachine: vm) }

    func eval(script: String) -> JsValue { JsValueConverter.toJsValue(context.evaluateScript(script)) }
    func eval(script: String, timeoutMs: Int64) -> JsValue { eval(script: script) } // JSCore 无原生超时，靠脚本协作或 dispatch 计时
    func callFunction(name: String, args: [JsValue]) -> JsValue {
        let fn = context.objectForKeyedSubscript(name)
        return JsValueConverter.toJsValue(fn?.call(withArguments: args.map(JsValueConverter.toJSValue)))
    }
    func evalAsync(code: String, timeoutMs: Int64) -> JsValue {
        // 两段式（同 QuickJsEngine ASYNC_WRAPPER）：包 async IIFE，.then 写 globalThis.__asyncResult/__asyncError，再读回
        context.evaluateScript(Self.ASYNC_WRAPPER_HEAD + code + Self.ASYNC_WRAPPER_TAIL)
        return JsValueConverter.toJsValue(context.evaluateScript(Self.READ_ASYNC_RESULT_JS))
    }
    func installBridge(bridge: JsBridge) {
        // __bridgeCall / __bridgeCallAsync / __bridgeList / __consoleLog 注入为 @convention(block)
        let bridgeCall: @convention(block) (String, JsValue) -> JsValue = { name, arg in
            JsValueConverter.toJsValue(bridge.dispatchSync(name: name, arg: arg))   // 方法名按 SharedKit 暴露
        }
        let bridgeCallAsync: @convention(block) (String, JsValue, @escaping (JsValue?, Error?) -> Void) -> Void = { name, arg, cb in
            bridge.dispatchAsync(name: name, arg: arg) { err, res in cb(res, err) }
        }
        context.setObject(bridgeCall, forKeyedSubscript: "__bridgeCall" as NSCopying & NSObjectProtocol)
        context.setObject(bridgeCallAsync, forKeyedSubscript: "__bridgeCallAsync" as NSCopying & NSObjectProtocol)
        // __bridgeList / __consoleLog 同理
        context.evaluateScript(Self.BOOTSTRAP_JS)   // 与 Android 同一 bootstrap
    }
    func close() { /* JSContext/JSVirtualMachine 由 ARC 释放 */ }

    // ASYNC_WRAPPER_HEAD/TAIL/READ_ASYNC_RESULT_JS/BOOTSTRAP_JS 常量
    // = 从 QuickJsEngine.kt companion 原样翻译（JS 字符串相同，双端一致）
}
```
> ⚠️ SharedKit 暴露的 Kotlin interface 方法签名（`dispatchSync`/`dispatchAsync` 参数标签）以 KMP 生成的 Swift 头为准——`xcodegen` 后查 `SharedKit.xcframework` 头文件确认实际签名，按它调。

- [x] **Step 5: GalleryHandlers.swift（native handlers）**

注册进 commonMain `JsBridge`（参考 Android ChatRunScriptCapability 注册 gallery.summary/query/tags/timeline + media.meta）。只读 handler 本批实现（读 PHAsset / TagDatabase）；写 handler（delete_media/favorite）占位抛"not implemented"。
对照 Android handler 实现（grep `gallery.summary\|registerHandler` in androidApp capability）逐个移植数据读取逻辑（Android 用 MediaStore，iOS 用 PHAsset/TagDatabase）。

- [x] **Step 6: 跑测试确认通过**

```bash
xcodebuild test -scheme polang -destination 'platform=iOS,name=<device>' -only-testing:PoLangTests/JsCoreEngineTest
```
Expected: PASS（1+2=3；gallery.summary 返回数据）。

- [x] **Step 7: Commit**

```bash
git add iosApp/PoLang/Platform/Js/ iosApp/PoLangTests/JsCoreEngineTest.swift iosApp/project.yml  # 新源文件须 xcodegen 入库
git commit -m "feat(ios): JS 沙盒接入 JavaScriptCore 实现 commonMain JsEngine"
```

---

## Task 5: iOS 流式节奏器接入

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift`
- Modify: `iosApp/PoLang/Features/Chat/ChatView.swift`（光标位置）

- [x] **Step 1: ChatViewModel 接 StreamingPacingController**

commonMain `StreamingPacingController`（domain.chat.streaming）经 SharedKit 暴露给 Swift。ViewModel 持有：
```swift
private var pacing: StreamingPacingController?
// send() 创建占位后：
let onPaced: (String, Bool) -> Void = { [weak self] text, cursor in
    self?.streamingUpdate(id: placeholderId, content: text, showCursor: cursor)
}
pacing = StreamingPacingController(scope: <MainActor KotlinCoroutineScope>, onPaced_: onPaced)  // 构造签名以 SharedKit 头为准
pacing?.start()
// onText 回调：pacing?.onTextSnapshot(fullText: snapshot)（不再直接写 content）
// onComplete：pacing?.finish()
```
替换现有 `streamingUpdate(id:text:)` 为 `streamingUpdate(id:content:showCursor:)`。

> ⚠️ commonMain `StreamingPacingController` 构造需 `CoroutineScope`。iOS 侧用 Kotlin `MainScope()` 或经 SharedKit 提供的 main scope（查 SharedKit 是否暴露 helper；无则在 iosMain 加一个 `actual fun mainScope()`）。onPaced 回调签名以 KMP 头为准。

- [x] **Step 2: 光标内联右侧**

`ChatView.swift` agent 气泡：`HStack(alignment: .bottom) { AgentTextView(...); if msg.showCursor { BlinkCursor() } }`（从文本下方独立 HStack 移到内联右侧底对齐）。

- [x] **Step 3: 验证（真机验吐字 + 编译）**

```bash
xcodebuild ... build   # 编译绿
# 真机：发一条长回复，观察逐字吐（~50ms/字）+ 标点停顿 + 光标吐字中可见完成消失
```

- [x] **Step 4: Commit**

```bash
git add iosApp/PoLang/Features/Chat/
git commit -m "feat(ios): Chat 流式节奏器接入（commonMain StreamingPacingController）+ 光标内联"
```

---

## Task 6: iOS Markdown 富渲染（分段）

**Files:**
- Create: `iosApp/PoLang/Features/Chat/AgentTextView.swift`
- Modify: `iosApp/PoLang/Features/Chat/ChatView.swift`（messageList 按 type 分发）

- [x] **Step 1: AgentTextView.swift（分段渲染）**

```swift
import SwiftUI
struct AgentTextView: View {
    let content: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(MarkdownSegmenter.shared.segment(text: content), id: \.self) { seg in
                switch seg.type {
                case .markdown: MarkdownText(text: seg.text)           // 现有 AttributedString
                case .table:   TableView(svgOrRaw: seg.text)           // 新：parseTable→Grid
                case .code:     CodeBlockView(code: seg.text)          // 新：折叠+复制
                }
            }
        }
    }
}
```
`MarkdownSegmenter` 是 commonMain（domain.chat.markdown），经 SharedKit 暴露（`segment(text:)` 返回 `[Segment]`，Segment 有 `type`/`text`）。

- [x] **Step 2: TableView（自建网格）+ CodeBlockView（折叠+复制）**

`TableView`：调 `MarkdownSegmenter.parseTable(seg)` → 表头+数据行 → SwiftUI `Grid`（表头加粗）。`CodeBlockView`：等宽字体 + `@State expanded` + 复制按钮（`UIPasteboard.general.string = code`）。

- [x] **Step 3: ChatView messageList 按 type 分发**

```swift
switch msg.type {
case .agentText: AgentTextView(content: msg.content)
case .mediaResults: MediaCardRow(...)
case .chart: if let svg = msg.chartSvg { ChartSvgCard(svg: svg) }     // Task 7
case .userText, .userImageText: UserBubble(...)                        // userImageText 带 imageUri 缩略图
case .agentEditResult, .optimizeCandidates, .command, .planPreview, .agentImage:
    AgentTextView(content: msg.content)   // 降级纯文本（字段已建模，渲染留后续）
}
```

- [x] **Step 4: 验证（编译 + 真机看表格/代码块）**

```bash
xcodebuild ... build
# 真机：让 agent 回复含 markdown 表格 + ```代码块```，确认网格渲染 + 代码折叠复制
```

- [x] **Step 5: Commit**

```bash
git add iosApp/PoLang/Features/Chat/AgentTextView.swift iosApp/PoLang/Features/Chat/ChatView.swift
git commit -m "feat(ios): Chat Markdown 分段富渲染（表格网格 + 代码块折叠）"
```

---

## Task 7: iOS CHART 端到端

**Files:**
- Create: `iosApp/PoLang/Features/Chat/ChartSvgCard.swift`
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift`（emitChartMessage）
- Create/Modify: ChatRunScriptCapability iOS 接入（`iosApp/PoLang/Platform/Js/` 或 capability 目录）

> 依赖 Task 4（JsCoreEngine）+ Task 1（chartSvg 字段）。对照 Android `ChatRunScriptCapability.kt` + `ChatViewModel.emitChartMessage`。

- [x] **Step 1: ChartSvgCard.swift（WKWebView 渲染 SVG）**

```swift
import SwiftUI import WebKit
struct ChartSvgCard: View {
    let svg: String
    @State private var showPreview = false
    var body: some View {
        // 解析 width/height（对齐 ChatScreen.kt:2534 SVG 尺寸解析）
        WebView(svg: svg).frame(height: estimatedHeight).onTapGesture { showPreview = true }
        .sheet(isPresented: $showPreview) { WebView(svg: svg) }
    }
}
struct WebView: UIViewRepresentable {
    let svg: String
    func makeUIView(_: Context) -> WKWebView {
        let wv = WKWebView(); wv.loadHTMLString("<html><body>\(svg)</body></html>", baseURL: nil); wv.scrollView.isEnabled = false; return wv
    }
    func updateUIView(_: WKWebView, context _: Context) {}
}
```

- [x] **Step 2: ChatRunScriptCapability iOS 接入**

注册 `draw_chart`/`run_gallery_script` 命令 → 构造 `JsRuntime(engine: JsCoreEngine(), scope:, source: "chat")` → `runtime.evalAsync(code:, timeoutMs:)` → 解析返回 JsValue.Obj 取 `chart`/`summary`（对照 Android `obj.entries["chart"]`）。若 ChatRunScriptCapability 逻辑可下沉 commonMain 则下沉（优先），否则 iOS app 层等价实现并注册进 AgentOrchestrator/CapabilityRegistry。

- [x] **Step 3: ChatViewModel.emitChartMessage**

```swift
func emitChartMessage(svg: String, summary: String) {
    messages.append(ChatMessage(type: .chart, content: summary, chartSvg: svg))
    persist()
}
```
接入 capability 回调（draw_chart 结果 → emitChartMessage）。

- [x] **Step 4: 验证（CHART 端到端真机）**

```bash
xcodebuild ... build
# 真机：chat 输入"画一个柱状图展示..." → agent 调 draw_chart → JS 生成 SVG → ChartSvgCard 渲染成图
```

- [x] **Step 5: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): CHART 端到端（draw_chart→JS生成SVG→ChartSvgCard 渲染）"
```

---

## Task 8: iOS 媒体反馈 + 模型胶囊

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatView.swift`（MediaThumbnail 反馈 + 输入栏胶囊）
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift`（feedbackState）
- Modify: `iosApp/PoLang/Features/Chat/ChatHistoryStore.swift`（feedback 持久化）

- [x] **Step 1: feedbackState + 持久化**

```swift
// ChatViewModel
@Published var feedbackState: [UUID: FeedbackType] = [:]   // thumbUp/thumbDown/refresh
func onMediaFeedback(messageId: UUID, type: FeedbackType) {
    feedbackState[messageId] = type
    ChatHistoryStore.shared.saveFeedback(sessionId:currentSessionId, messageId:messageId, type:type)
    // bridge 上报（若有接口，对照 Android onMediaFeedback）
}
```
ChatHistoryStore 加 feedback 持久化（扩展 Codable 或单独 plist）。

- [x] **Step 2: MediaThumbnail 反馈按钮**

卡片右上角 3 个 `FeedbackIconButton`（thumbUp/thumbDown/refresh），选中态高亮（accentColor），读 feedbackState。对照 Android `MediaResultsCarousel.kt:147-171`。

- [x] **Step 3: 模型胶囊**

输入栏第二行加 `ModelCapsule`：`ModelConfigStore` 的 `hasUserKey` 为真时显示，下拉切 official/自配 Key（对齐 Android `ModelSelector.kt`）。读现有 `ModelConfigStore`（已存在）。

- [x] **Step 4: 验证**

```bash
xcodebuild ... build
# 真机：媒体卡片点👍高亮+冷启仍选中；模型胶囊切换生效
```

- [x] **Step 5: Commit**

```bash
git add iosApp/PoLang/Features/Chat/
git commit -m "feat(ios): Chat 媒体反馈 👍👎🔄 + 模型胶囊"
```

---

## Task 9: 键盘避让回退 + XCUITest 回归

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatView.swift`（删手动 lift）
- Modify: `iosApp/PoLang/Platform/DebugBypass.swift`（删 dumpViewHierarchy，若无其他引用）
- Create: `iosApp/PoLangTests/ChatKeyboardAvoidanceTest.swift`

> memory `ios-keyboard-avoidance-system-only`：系统在 TabView(.page) 内会处理避让，手动 lift 与系统叠加→过度上抬。

- [x] **Step 1: 删手动键盘避让**

`ChatView.swift` 删：`@State keyboardHeight`、`keyboardWillChangeFrameNotification` onReceive、`GeometryReader` DEBUG dump、`.padding(.bottom, max(0, keyboardHeight - bottomSafeInset))`、`bottomSafeInset`。改回依赖系统 safeArea 避让（确认 body 不阻止系统 inset）。`DebugBypass.dumpViewHierarchy` 若仅此处引用则删。

- [x] **Step 2: 写 XCUITest 回归**

```swift
// ChatKeyboardAvoidanceTest.swift
func testKeyboardAvoidance() {
    app.launch()
    // 进入 chat，点输入框唤起键盘
    let input = app.textViews["chat_input"]
    input.tap()
    // 断言输入框底部在键盘上方（不被遮挡）
    let kb = app.keyboards.firstMatch
    XCTAssertTrue(input.frame.maxY <= kb.frame.minY + 5)
}
```

- [x] **Step 3: 验证**

```bash
xcodebuild test ... -only-testing:PoLangTests/ChatKeyboardAvoidanceTest
# 真机：点输入框，输入栏不被键盘遮挡（系统处理）
```

- [x] **Step 4: Commit**

```bash
git add iosApp/
git commit -m "fix(ios): Chat 键盘避让回退系统处理 + XCUITest 回归"
```

---

## Task 10: 全量验收 + 文档同步

- [x] **Step 1: spec §9 验收清单逐项**

逐条核对：节奏器吐字/11type 渲染/Markdown 表格代码块/JS 沙盒(1+2, gallery.summary)/CHART 端到端/媒体反馈持久化/模型胶囊/键盘避让 XCUITest。

- [x] **Step 2: 工程 gate**

```bash
./gradlew :shared:checkCommonMainPurity
./gradlew :shared:jvmTest
./gradlew :androidApp:assembleDebug
./gradlew ktlintCheck detekt
cd iosApp && xcodegen generate && xcodebuild ... build
```
全绿。

- [x] **Step 3: 文档同步**

更新 `docs/01-PRODUCT/IOS_TASK_STATUS.md` §6.2（Chat ~45%→对齐）+ §6 漂移记录加 08-13 Chat 批次行。

- [x] **Step 4: 最终 commit**

```bash
git add docs/
git commit -m "docs(ios): Chat 富交互批次对齐看板更新"
```

---

## 风险提示（执行方注意）
- **SharedKit Swift 头签名**：commonMain Kotlin interface 经 KMP 暴露给 Swift 的方法签名/标签以 `xcodegen` 后的 `SharedKit.xcframework` 头为准——遇到 `dispatchSync`/`onPaced`/`segment` 等签名不符，以实际头修正，勿硬套本 plan 的伪码。
- **JSCore block 线程**：`__bridgeCall` 同步阻塞；handler 内若需异步取数（PHAsset 异步）必须用 `__bridgeCallAsync`（Promise），参考 Android `QuickJsEngine` dispatchSync/dispatchAsync 分流。
- **每 task 独立 commit + 编译绿再进下一步**；任一 task 失败可单独 revert。
