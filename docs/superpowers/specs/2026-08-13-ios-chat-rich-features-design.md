# iOS Chat 富交互对齐 · 设计 spec（批次①）

> **状态**：待实施（交 **GLM** 执行——今天；kimi-code 明天恢复额度后可接替；Claude Code 审查）
> **日期**：2026-08-13 · **基线**：Android main v1.0.35 · **iOS 分支**：`feat/ios-chat-align`（当前含未提交的键盘避让调试改动，本 spec 第 §7 处置）
> **范围**：iOS Chat 从 ~45% 对齐到 Android 全功能面（5 子功能），消息模型 + 流式节奏器 + Markdown 分段器**下沉 `shared/commonMain` 双端共用**
> **关联**：[`IOS_TASK_STATUS.md`](../../01-PRODUCT/IOS_TASK_STATUS.md) §6.2 / [`2026-08-10-ios-android-consistency-gap.md`](../../reviews/2026-08-10-ios-android-consistency-gap.md) §5

---

## §1 目标与非目标

### 目标
1. **流式节奏器**：复刻 Android 豆包风逐字吐字（50ms/字 + 标点/CJK 分块 + 光标内联右侧、受 pacing 驱动）
2. **富消息 11 type**：`ChatMessageType` 全量建模，渲染按 type 分发
3. **Markdown 富渲染**：分段（MARKDOWN/TABLE/CODE）原生 SwiftUI 渲染（不引第三方库）
4. **媒体反馈**：卡片 👍👎🔄 + 持久化
5. **模型胶囊**：下拉切 official / 自配 Key

### 非目标（本批不做渲染，但模型字段全下沉）
- **模型层全量下沉**：`ChatMessage` 对齐 Android `ChatMessageUi` **全部字段**（含 `claudeAgent`/`claudeDeliver`/`optimizeCandidates`/`performance`/`chartSvg` 等）——这些是**双端通用产品功能、仅 iOS 当前未补齐**，非 Android 专属。字段全就位 = 架构单 SSOT，iOS 后续补功能时模型已备。
- Claude 工程师模式 / 抽卡（`OPTIMIZE_CANDIDATES`）/ `AGENT_EDIT_RESULT`：**字段下沉但本批不实现渲染**（降级纯文本占位），留后续批次补齐 iOS 功能。
- **CHART 端到端本批接通**（用户决策 A）：iOS JS 沙盒接入（系统 **JavaScriptCore** 实现 commonMain `JsEngine`）+ gallery/media native handlers + CHART 生成消息流 + `ChartSvgCard` SVG 渲染。**JS 沙盒接入作为本批首 task（前置）**，详见 §5.6/§5.7。
- 停止生成 UI（Android 亦无，双端持平，非缺口）
- 语音输入（用户明确延后）

---

## §2 现状（实施前基线）

### iOS（`iosApp/PoLang/Features/Chat/`，6 文件 1593 行）
- **`ChatMessage.swift`**（42 行）：单一模型，`role`(user/assistant) + `text` + `mediaIds:[Int64]` + 流式 flags(isStreaming/isThinking/isToolCalling) + `error` + `mediaQuery`/`mediaTotalCount`。**无 type 枚举**。
- **`ChatViewModel.swift`**（352 行）：`send()` 流程完整（user append → thinking 占位 → `bridge.sendMessage(onText/onToolCall/onComplete)`）；`streamingUpdate(id,text:)` **直接写全快照，无节奏**（`messages[idx].text = snapshot`，line 219）；多会话 + 附件 + handleUiAction(media_results/text_reply/error/success) 已 live；持久化 `ChatHistoryStore`（Codable JSON，`chat_history_<sessionId>.json`）。
- **`ChatView.swift`**：`MarkdownText`(line 359) 用 `AttributedString(markdown:)` 仅基础 CommonMark；`BlinkCursor`(line 501) 文本**下方**且恒显；`MediaCardRow`(519)/`MediaThumbnail`(598) 无反馈按钮；无模型胶囊。
- **未提交改动**：`ChatView.swift` 手动键盘避让（`keyboardHeight` padding + DEBUG dump）—— **方向错误**，见 §7。

### Android ground truth（`androidApp/.../features/chat/`）
- **`ChatMessageType`**（`ChatScreen.kt:2380`）11 type：
  `USER_TEXT, AGENT_TEXT, USER_IMAGE, USER_IMAGE_TEXT, AGENT_IMAGE, AGENT_EDIT_RESULT, COMMAND, PLAN_PREVIEW, MEDIA_RESULTS, CHART, OPTIMIZE_CANDIDATES`
- **`ChatMessageUi`**（`ChatScreen.kt:2350`）字段：`id, type, content, modelUsed, timestamp, performance, mediaResults, imageUri, chartSvg, imageSaved, isStreaming, showCursor, isThinking, claudeAgent, claudeDeliver, optimizeCandidates, gachaInteractive`
- **`StreamingPacingController.kt`**（`streaming/`，118 行，纯 Kotlin 仅依赖 `kotlinx.coroutines` + `kotlin.math.min`）：常量 `BASE_CHAR_MS=50 / PUNCT_MS=100 / LINE_MS=200 / CHUNK_CJK=2 / TAIL_BLINK_MS=2500 / IDLE_POLL_MS=50`；接口 `start()/onTextSnapshot(fullText)/reset()/finish()` + `onPaced(text, cursorVisible)` 回调；标点集 `，。？！：；,?!:;`。有 `runTest` 单测。
- **`ClaudeMarkdownSegmenter.kt`**（纯 Kotlin + 正则）：`enum AgentSegmentType { MARKDOWN, TABLE, CODE }`；`data class AgentSegment(type, text)`；`TABLE_DELIMITER`/`CODE_FENCE` regex；`segment(text)->List<AgentSegment>`；`parseTable`。流式未闭合 ```` ``` ```` 之后归 CODE。

---

## §3 架构设计（全下沉 commonMain）

```
shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/   ← 新增 SSOT
├── ChatMessageType.kt            (11 type 枚举)
├── ChatMessage.kt                (统一消息模型，精选双端通用字段)
├── streaming/
│   └── StreamingPacingController.kt   (从 androidApp 迁入，逻辑零改动)
└── markdown/
    └── MarkdownSegmenter.kt      (从 ClaudeMarkdownSegmenter 迁入，重命名去 Claude 前缀)

shared/src/commonTest/.../domain/chat/   (节奏器 + 分段器单测迁入，双端共用)
        ▲ consume                              ▲ consume
androidApp/ChatScreen.kt               iosApp/Features/Chat/
(删本地 ChatMessageType/              ChatMessage.swift → 消费 commonMain（KMP 互操作）
 ChatMessageUi 改引用 commonMain；     ChatViewModel.swift → 接 StreamingPacingController
 Android 专属字段在本地扩展保留)       ChatView.swift → 按 type 分发 + segment 渲染 + 反馈 + 胶囊
```

### 边界（重要）
- 下沉的是 **UI 层消息模型 + 纯逻辑**，**不动 Koog 记忆层**（LLM 多轮上下文，iOS 走 NSUserDefaults/bridge memory，两套保持分离，见现 `ChatMessage.swift` 顶部注释）。
- `shared/commonMain` 纯度硬规则（ADR-013）：下沉文件**不得**含 `@Composable` / `android.*` / `java.*` / `androidx.*` import。`StreamingPacingController` 已确认仅依赖 `kotlinx.coroutines`（commonMain 可用）+ `kotlin.math`。

---

## §4 下沉模型定义（commonMain）

### 4.1 ChatMessageType.kt
```kotlin
package com.mamba.picme.domain.chat
enum class ChatMessageType {
    USER_TEXT, AGENT_TEXT, USER_IMAGE, USER_IMAGE_TEXT, AGENT_IMAGE,
    AGENT_EDIT_RESULT, COMMAND, PLAN_PREVIEW, MEDIA_RESULTS, CHART, OPTIMIZE_CANDIDATES
}
```

### 4.2 ChatMessage.kt（全字段，1:1 对齐 Android ChatMessageUi）
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
    val error: String? = null,            // iOS 侧补充（Android 用 content 承载错误）
)
```
**全字段下沉原则**：以上字段**非 Android 专属**——是双端通用产品功能（Claude 工程师模式/抽卡/性能/图表等），仅 iOS 当前未补齐。字段全下沉 = 架构单 SSOT，iOS 后续补功能时模型已就位。

**子类型一并下沉**：`LlmPerformance` / `MediaResultsUi` / `ClaudeAgentState` / `ClaudeDeliverUi` / `OptimizeCandidateGroup` 下沉到 commonMain 同包（Android 侧已是 data class，纯数据结构）。**实施前 grep 确认无 `android.*`/`androidx.*`/compose 依赖**；若有，剥离平台类型（换成 Kotlin 标准类型）后再下沉。

**Android 侧**：`ChatMessageUi` 直接替换为 commonMain `ChatMessage`（或 typealias），删本地重复定义；`ChatScreen` 渲染逻辑不变（字段名一致）。

### 4.3 StreamingPacingController.kt
整体迁入 `shared/commonMain/.../domain/chat/streaming/`，**逻辑零改动**（已确认纯 Kotlin）。包名改 `com.mamba.picme.domain.chat.streaming`。单测 `StreamingPacingControllerTest`（`runTest`）迁入 `commonTest`。

### 4.4 MarkdownSegmenter.kt
从 `ClaudeMarkdownSegmenter.kt` 迁入 `shared/commonMain/.../domain/chat/markdown/`，重命名 `MarkdownSegmenter`（去 Claude 前缀，双端通用）。`enum SegmentType { MARKDOWN, TABLE, CODE }` + `data class Segment(type, text)` + `fun segment(text): List<Segment>` + `fun parseTable(segment): MarkdownTable`。单测迁入 commonTest。

---

## §5 子功能详细设计

### 5.1 流式节奏器
**iOS 改动**（`ChatViewModel.swift`）：
- 持有 `private var pacing: StreamingPacingController?`（commonMain 类型）
- `send()` 创建占位后：`pacing = StreamingPacingController(scope:, onPaced: { text, cursor in /* 更新 messages[idx].content=text, showCursor=cursor */ }); pacing?.start()`
- `onText` 回调：`pacing?.onTextSnapshot(snapshot)`（**不再直接写 messages[idx].text**）
- `onComplete`：`pacing?.finish()` → 收尾
- `streamingUpdate(id,text:)` 方法替换为 `onPaced` 回调内更新 `content` + `showCursor`

**iOS 改动**（`ChatView.swift`）：
- 光标位置：从文本**下方**移到**内联右侧**（`HStack { SegmentedAgentText; if showCursor { BlinkCursor() } }`，底对齐）
- `BlinkCursor` 可见性由 `message.showCursor` 驱动（非恒显）

**验收**：中文长回复逐字吐（肉眼 ~50ms/字），标点后微停，换行后停顿；光标吐字中可见、完成后消失。commonTest 节奏器单测全绿。

### 5.2 富消息 11 type 渲染分发
**iOS 改动**（`ChatView.swift` `messageList`）：按 `message.type` 分发到对应 View：
- `AGENT_TEXT` → `AgentTextView`（含 segment 富渲染，§5.3）
- `MEDIA_RESULTS` → 现有 `MediaCardRow`（+ §5.4 反馈）
- `USER_TEXT` / `USER_IMAGE_TEXT` → 用户气泡（后者带 `imageUri` 缩略图）
- `CHART` / `OPTIMIZE_CANDIDATES` / `AGENT_EDIT_RESULT` → 本批降级为 `AgentTextView` 纯文本（占位注释 TODO，留后续批次）
- `COMMAND` / `PLAN_PREVIEW` / `AGENT_IMAGE` → 本批隐藏不渲染（仅建模，留后续批次）

**验收**：媒体结果仍作为独立消息项（现状保留）；新增 type 不崩溃，降级项有明确占位。

### 5.3 Markdown 富渲染（原生分段）
**iOS 新增**（`ChatView.swift` 或新文件 `AgentTextView.swift`）：
- `AgentTextView`：调 `MarkdownSegmenter.segment(text)` → 逐段渲染：
  - `MARKDOWN` → 现有 `MarkdownText`（`AttributedString(markdown:)`）
  - `TABLE` → 自建 `TableView`：`parseTable` → `Grid` 网格（表头加粗 + 数据行）
  - `CODE` → 自建 `CodeBlockView`：等宽字体 + 折叠/展开 + 复制按钮（`UIPasteboard`）
- 流式期间末段可能未闭合：`MarkdownSegmenter` 已处理（未闭合 ```` ``` ```` 归 CODE）

**验收**：Agent 回复含表格 → 渲染网格；含代码块 → 折叠+可复制；普通 Markdown 不退化。

### 5.4 媒体反馈 👍👎🔄
**iOS 改动**（`ChatView.swift` `MediaThumbnail`）：
- 卡片右上角加 3 个 `FeedbackIconButton`（ThumbUp/ThumbDown/Refresh），选中态高亮
- `ChatViewModel` 加 `@Published var feedbackState: [MessageID: FeedbackType]`，持久化到 `ChatHistoryStore`（扩展 Codable）
- `onMediaFeedback(messageId:, type:)` 更新状态 + 调 bridge 上报（若 Android 有上报通道则对齐）

**验收**：点 👍 高亮且持久化（冷启仍选中）；🔄 触发重新生成（若有 bridge 接口，否则 toast 占位）。

### 5.5 模型胶囊
**iOS 改动**（`ChatView.swift` 输入栏第二行）：
- 加 `ModelCapsule`：`hasUserKey` 时显示，下拉切 official / 自配 Key（读 `ModelConfigStore` 已有配置）
- 对齐 Android `ModelSelector.kt` 语义

**验收**：胶囊显示当前模型，下拉可切换，切换后生效。

### 5.6 CHART 端到端（生成 + 渲染，本批）
**链路**（对齐 Android `ChatRunScriptCapability` + `ChatViewModel.emitChartMessage`）：远程 LLM 发 `draw_chart`/`run_gallery_script` → ChatRunScriptCapability → `JsRuntime.evalAsync(script)` → 返回 `{chart: <SVG>, summary: <text>}`（JsValue.Obj）→ 取 `chart` 字段落 `ChatMessage(type=CHART, chartSvg=svg)` → `ChartSvgCard` 渲染。

**iOS 新增**：
- `ChartSvgCard(svg:)`（`Features/Chat/ChartSvgCard.swift`）：解析 SVG width/height（对齐 `ChatScreen.kt:2534`），**WKWebView** 加载 SVG 字符串渲染（无新依赖）+ 点击全屏预览。`type==CHART && chartSvg != null` → `ChartSvgCard`。
- `ChatViewModel.emitChartMessage(svg:)`：追加 CHART 消息（对齐 Android `emitChartMessage`）。
- ChatRunScriptCapability iOS 接入：注册 `draw_chart`/`run_gallery_script` 命令 → 调 `JsRuntime`（engine=`JsCoreEngine`）。capability 逻辑若 commonMain 可共享则下沉，否则 iOS app 层等价实现。
- **依赖 §5.7 JS 沙盒先就位**。

### 5.7 iOS JS 沙盒接入（JavaScriptCore，本批首 task 前置）
commonMain 已有引擎无关层（`shared/.../agent/core/js/`：`JsEngine` 接口 + `JsRuntime` + `JsBridge` + `JsValue` + `NativeHandler`）。Android 提供 `QuickJsEngine`（dokar3/quickjs-kt，`androidApp/.../chat/js/QuickJsEngine.kt`）。iOS 用**系统 JavaScriptCore** 实现等价（`import JavaScriptCore`，无 pod、合规无忧）。

**`JsEngine` 接口 5 方法 → JSCore 映射**（对照 `QuickJsEngine.kt`）：
- `eval(script)` / `eval(script, timeoutMs)`：`JSContext.evaluateScript(script)` → `JsValueConverter` 转 commonMain `JsValue`
- `callFunction(name, args)`：`context.objectForKeyedSubscript(name)?.call(withArguments: argsJs)`
- `installBridge(bridge)`：`context.setObject(block, forKeyedSubscript: "__bridgeCall"/"__bridgeCallAsync"/"__bridgeList"/"__consoleLog")`（`@convention(block)`），再 `evaluateScript(BOOTSTRAP_JS)`——**与 Android 同一 bootstrap**（`globalThis.bridge={call,callAsync,list}` + console），保证双端 JS API 一致
- `evalAsync(code, timeoutMs)`：JSCore `evaluateScript` 不解包 Promise → 用 Android **同款两段式 async wrapper**（写 `globalThis.__asyncResult`/`__asyncError` 再读回，见 `QuickJsEngine` ASYNC_WRAPPER_HEAD/TAIL/READ_ASYNC_RESULT_JS）
- `close()`：释放 `JSContext`/`JSVirtualMachine`

**iOS 新增文件**（`iosApp/PoLang/Platform/Js/`）：
- `JsCoreEngine.swift`：实现 commonMain `JsEngine`（Kotlin interface → Swift），持 `JSVirtualMachine` + `JSContext`
- `JsValueConverter.swift`：`JSValue` ↔ commonMain `JsValue`（Str/Num/Obj/Arr/Bool/Null）双向（对照 Android `QuickJsConverter`）
- gallery/media **native handlers**（注册进 commonMain `JsBridge`）：`gallery.summary`/`gallery.query`/`gallery.tags`/`gallery.timeline`/`media.meta` 等——读 PHAsset/TagDatabase（对照 Android handler 实现）。**只读 handler 优先**（chart 只需读）；写 handler（delete/favorite）本批占位

**验收（JS 沙盒）**：`evaluateScript("1+2")`→3；`bridge.callAsync('gallery.summary',{})` 返回相册摘要；CHART 脚本返回 `{chart:<svg>, summary:...}`。

---

## §6 关键技术决策（已与用户确认）

1. **Markdown 富渲染 = 纯原生分段，不引第三方库**。表格/代码块自建 SwiftUI View。
2. **`StreamingPacingController` + `MarkdownSegmenter` 下沉 commonMain 双端共用**（纯逻辑，已确认可迁移）。
3. **`ChatMessage` 全字段下沉 commonMain**（含 `claudeAgent`/`optimizeCandidates`/`performance` 等双端通用字段 + 子类型）；Android `ChatMessageUi` 替换为 commonMain 模型——iOS 后续补功能时模型已就位。
4. **CHART 端到端本批接通**（用户决策 A）：iOS JS 沙盒接入（系统 **JavaScriptCore**）作为本批首 task，随后 CHART 生成链路 + `ChartSvgCard` 渲染。详见 §5.6/§5.7。
5. **iOS 持久化保留 Codable wrapper**（旧 `chat_history` JSON 向前兼容；commonMain 模型不直接 Codable，规避 KMP 跨平台复杂度）。

## §7 当前未提交改动处置
**回退** `ChatView.swift` 的手动键盘避让（`keyboardHeight` padding + `GeometryReader` + DEBUG dump）+ `DebugBypass.swift` 的 `dumpViewHierarchy`。
**理由**：memory `ios-keyboard-avoidance-system-only` 已验证——iOS 系统在 `TabView(.page)` 内会处理输入栏避让，手动 lift 与系统叠加→过度上抬。十几轮调试全因截图/onReceive 渲染竞态误判。
**改为**：删除手动 padding，依赖系统处理；验证**只用 XCUITest**（`input.frame` vs `keyboard.frame`，不靠截图）。留回归测试 `testKeyboardAvoidance`。

---

## §8 文件清单

### 新增（shared commonMain + commonTest）
- `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessageType.kt`
- `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`
- `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/streaming/StreamingPacingController.kt`
- `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/markdown/MarkdownSegmenter.kt`
- `shared/src/commonTest/.../domain/chat/streaming/StreamingPacingControllerTest.kt`（迁移）
- `shared/src/commonTest/.../domain/chat/markdown/MarkdownSegmenterTest.kt`（迁移）

### 修改（androidApp）
- `ChatScreen.kt`：删 `enum class ChatMessageType`（改 import commonMain）；`ChatMessageUi` 替换为 commonMain `ChatMessage`（全字段已下沉，§4.2）；子类型 `OptimizeCandidateGroup`/`ClaudeAgentState`/`ClaudeDeliverUi`/`LlmPerformance`/`MediaResultsUi` 随之迁 commonMain
- `ChatViewModel.kt`：`streaming/StreamingPacingController` import 改 commonMain 包
- 删除 `androidApp/.../features/chat/streaming/StreamingPacingController.kt`（迁入 commonMain）
- 删除 `androidApp/.../features/chat/ClaudeMarkdownSegmenter.kt`（迁入 commonMain）
- 迁移对应 androidApp test

### 修改（iosApp）
- `Features/Chat/ChatMessage.swift`：改为消费 commonMain `ChatMessage`（KMP 互操作；Codable 兼容见下）
- `Features/Chat/ChatViewModel.swift`：接入 `StreamingPacingController`（§5.1）+ feedbackState（§5.4）
- `Features/Chat/ChatView.swift`：按 type 分发（§5.2）+ `AgentTextView`/`TableView`/`CodeBlockView`（§5.3）+ 反馈按钮（§5.4）+ 模型胶囊（§5.5）+ **回退手动键盘避让（§7）**
- 可能新增 `Features/Chat/AgentTextView.swift`（若 ChatView 过大则拆分）
- **iOS JS 沙盒（§5.7）**：新增 `Platform/Js/JsCoreEngine.swift`（实现 commonMain `JsEngine`，JavaScriptCore）+ `Platform/Js/JsValueConverter.swift`；gallery/media native handlers（读 PHAsset/TagDatabase）注册进 JsBridge
- **CHART（§5.6）**：新增 `Features/Chat/ChartSvgCard.swift`（WKWebView 渲染 SVG）+ `ChatViewModel.emitChartMessage`；ChatRunScriptCapability iOS 接入
- `Features/Chat/ChatHistoryStore.swift`：Codable 兼容（见下）

### Codable 向前兼容（iOS）
旧 `chat_history_<sessionId>.json` 按旧 `ChatMessage` 结构（role+text+...）。commonMain `ChatMessage` 字段扩展后：
- 新增字段全部有默认值（`type` 需从旧 `role`+`text` 推断：role==user → USER_TEXT，role==assistant 且无 mediaIds → AGENT_TEXT，有 mediaIds → MEDIA_RESULTS）
- 反序列化时补默认值，老数据可读
- **建议**：`ChatMessage.swift` 保留为 iOS Codable wrapper（`init(decoder:)` 做旧→新映射），commonMain 模型不直接 Codable（KMP Codable 跨平台复杂）

---

## §9 验收标准

### 功能（逐项对标 Android）
- [x] 流式逐字吐字（50ms/字 + 标点/CJK 块）；光标内联右侧、受 pacing 驱动
- [x] 11 type 建模；至少 AGENT_TEXT/MEDIA_RESULTS/USER_TEXT/USER_IMAGE_TEXT 端到端渲染正确
- [x] Markdown 表格→网格、代码块→折叠+复制
- [x] 媒体反馈 👍👎🔄 持久化
- [x] 模型胶囊切换生效
- [x] **JS 沙盒**：JS `1+2`→3；`bridge.callAsync('gallery.summary',{})` 返回相册摘要
- [x] **CHART 端到端**：`draw_chart` → JS 生成 SVG → `ChartSvgCard` 渲染成图
- [x] 键盘避让：系统处理，XCUITest `testKeyboardAvoidance` 绿

### 工程
- [x] `shared/commonMain` 纯度检查（`checkCommonMainPurity`）绿——新增文件无平台 import
- [x] commonTest（节奏器 + 分段器）全绿
- [x] Android 删本地重复定义后 `./gradlew :androidApp:assembleDebug` 绿 + Chat 回归无回归
- [x] iOS `xcodegen generate` → `xcodebuild` 绿（新增 commonMain KMP 互操作）
- [x] ktlint + detekt 绿

---

## §10 风险与回退

| 风险 | 缓解 |
|---|---|
| commonMain KMP 互操作 iOS 编译问题（Kotlin/Native 冻结/泛型） | 先做 §11 步骤 1-2（下沉 + Android 改引用）编译绿，再动 iOS；iOS 消费用 wrapper 隔离 |
| `OptimizeCandidateGroup`/`ClaudeAgentState` 等子类型含平台依赖 | 下沉前 grep 剥离 `android.*`/compose 依赖换 Kotlin 标准类型；剥离成本高则该字段临时 iOS 默认 null |
| 节奏器在 iOS MainActor 协程表现差异 | `StreamingPacingController` 用 `CoroutineScope`，iOS 传 `MainActor` scope；commonTest 验证逻辑，真机验节奏 |
| 键盘避让回退后真机异常 | XCUITest 回归兜底；memory 已记录系统处理正确 |
| iOS JSCore Promise 不解包（同 dokar3 QuickJS） | 用 Android 同款两段式 async wrapper（写全局变量再读回），bootstrap JS 双端共用 |
| JSCore bridge block 线程模型（同步 dispatch 阻塞） | `__bridgeCall` 同步 / `__bridgeCallAsync` 走 Promise；handler 内异步取数用 callAsync；参考 `QuickJsEngine` 的 dispatchSync/dispatchAsync 分流 |

**回退**：每个子功能独立 commit，任一子功能失败可单独 revert 不影响其他。

---

## §11 实施顺序（建议执行 agent task 拆分；GLM 今天 / kimi-code 明天恢复可接替）

1. **commonMain 下沉**：新建 ChatMessageType/ChatMessage（全字段+子类型）/StreamingPacingController/MarkdownSegmenter 4 文件 + 迁移 2 单测 → commonTest 绿 + `checkCommonMainPurity` 绿
2. **Android 改引用**：删本地 enum/controller/segmenter/重复子类型，改 import commonMain；`ChatMessageUi`→commonMain `ChatMessage` → `assembleDebug` 绿 + Chat 回归
3. **iOS 模型接入**：`ChatMessage.swift` → wrapper 消费 commonMain + Codable 兼容 → iOS 编译绿
4. **iOS JS 沙盒接入**（CHART 前置）：`JsCoreEngine`（JavaScriptCore 实现 commonMain `JsEngine`）+ `JsValueConverter` + gallery/media native handlers → JS `1+2`→3、`gallery.summary` 返回数据
5. **iOS 节奏器**：`ChatViewModel` 接 `StreamingPacingController` + 光标内联右侧 → 真机验吐字
6. **iOS 富渲染**：`AgentTextView` + `TableView` + `CodeBlockView` + 按 type 分发
7. **iOS CHART 端到端**：ChatRunScriptCapability 接入（`draw_chart`/`run_gallery_script`）→ `JsRuntime.evalAsync` → `emitChartMessage(svg)` → `ChartSvgCard`（WKWebView）渲染
8. **iOS 反馈 + 胶囊**：`MediaThumbnail` 反馈按钮 + 输入栏模型胶囊
9. **键盘避让回退**：删手动 lift + XCUITest 回归
10. **全量验收**：§9 清单逐项

> 每步独立 commit，编译绿再进下一步。
