# 本地回合 OpenUI 统一（U1 shared 收口 + U2 Android 主线）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 本地（端侧 Koog）chat 回合的终结回复从纯文本气泡统��为云端同款 turn-native OpenUI 程序（流式预览 + 校验回环 + 兜底 + `data_genui_program` 落库），云端校验/契约资产收口 shared 同一声明源。

**Architecture:** 三阶段——U1 把 server `agent/` 包六件（校验器/布局门禁/契约 prompt/ProgramGuard）原样上提 shared `domain/chat/openui/`，server 改 typealias 真引用（行为零变化）；U2 端侧策略切 `streaming + finalOutputCorrector`、prompt 尾部恒追加契约段、engine 层 ensureValidProgram 兜底、ChatViewModel 统一嗅探落卡 + 流式预览接线 + 端侧工具回执附 local_handle；回滚闸 DataStore 开关可整体退回。U3（iOS 镜像）为后续独立计划，不在本文。

**Tech Stack:** Kotlin Multiplatform（shared commonMain）、Koog 1.3.0、Ktor server（composite build 消费 :shared jvm）、Android Compose、Room。

**Spec:** `docs/superpowers/specs/2026-11-24-local-turn-openui-unification-design.md`

## Global Constraints

- 工作区隔离：动工前在 `.worktrees/` 建隔离 worktree + 专用分支（AGENTS.md §3.4），分支建议 `feat/local-turn-openui`。
- server 行为零变化：U1 完成后 `./gradlew -p server build` 全绿，server prompt/校验输出逐字节不变（既有 server 测试钉住，禁改断言）。
- DSL_RULES 等 prompt 资产逐字节搬运（含「服务端校验」等云端措辞，端侧不改写——字节钉住优先于措辞洁癖）；raw string 顶格禁 re-indent。
- Koog 版本两端锁死 1.3.0；@Tool 参数禁 Kotlin 默认值（可选语义用空串）。
- [I18N]：新增用户可见文案（回滚闸设置项）五语同步（EN/zh-CN/zh-TW/ES/FR）。
- 测试命令约定：shared = `JITPACK=true ./gradlew :shared:jvmTest`；server = `./gradlew -p server build`；Android 编译 = `./gradlew :androidApp:compileDebugKotlinAndroid`，单测 = `./gradlew :androidApp:testDebugUnitTest`。
- commonMain 禁用 JVM API（`java.util.concurrent.atomic.*` 用 `kotlin.concurrent.atomics`，slf4j 用 shared `Logger`）。

## Review Focus

1. **模型输出非程序且纠正用尽** → engine 层 `ensureValidProgram` 必须兜底包装，程序源码永不上屏（Task 6 测试钉住）。
2. **streaming 切换后 token 指标漏计/双计**：Koog 1.3.0 流式不触发 `onLLMCallCompleted`——`StreamFrame.End` 分支与非流式回退路径互斥累加（Task 4 测试钉住）。
3. **回滚闸切换后旧 agent 继续服务**：flag 必须进 `getChatAgent` 缓存键比对，切换后下一条消息生效（Task 6 测试钉住）。
4. **用户停止时半截程序落文本气泡**：预览非空时半截丢弃不落库（Task 9 测试钉住）。
5. **handle 注册失败（Room 异常）时搜索回执必须静默降级为纯前缀**，不抛错、不走 `error:` 协议（Task 10 测试钉住，CloudAgentGalleryTools 先例）。

---

### Task 1: U1 — 校验四件 + 布局门禁上提 shared

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/` 下 `RenderOpenUiValidator.kt`、`OpenUiStringScan.kt`、`OpenUiCallTree.kt`、`OpenUiLayoutValidator.kt`、`OpenUiLayoutNormalizer.kt`（自 server 同名文件搬运，package 改 `com.mamba.picme.domain.chat.openui`，internal 改 public 按消费面需要）
- Create: `server/src/main/kotlin/com/mamba/picme/server/agent/OpenUiSharedAliases.kt`（`typealias` 五件指向 shared 新位置，server 消费方零改动）
- Delete: server 五件本体
- Test: server 侧 `RenderOpenUiValidatorTest`/`OpenUiLayoutValidatorTest`/`OpenUiStringScanTest`/`OpenUiCallTreeTest`/`OpenUiLayoutNormalizerTest` 原位保留（经 typealias 钉住行为）

**Interfaces:**
- Consumes: shared `OpenUiContract`/`OpenUiLibrary`（既有）。
- Produces: `com.mamba.picme.domain.chat.openui.RenderOpenUiValidator`（`validate(text): List<String>`、`validateProgramToError(text): String?`、`ALLOWED_COMPONENTS`、`ICON_NAMES`——签名以 server 现文件为准原样保留）、`OpenUiLayoutValidator.validate`、`OpenUiLayoutNormalizer.normalize`。

- [ ] **Step 1:** 搬运五件到 shared，package 声明改 `com.mamba.picme.domain.chat.openui`；文件内相互引用同包免 import；`OpenUiContract`/`OpenUiLibrary` 同包免改。
- [ ] **Step 2:** server 建 `OpenUiSharedAliases.kt`（五件 typealias），删五件本体；server 消费方（OpenUiTurnNative/ProgramGuard/AgentRunner/AgentRoutes/AdminViews/AdminRoutes）零改动。
- [ ] **Step 3:** 跑 `./gradlew -p server build`——全绿（校验器四测试件 + AgentRunnerTest/AgentRoutesTest 全量）。
- [ ] **Step 4:** 跑 `JITPACK=true ./gradlew :shared:jvmTest` 与 `./gradlew :androidApp:compileDebugKotlinAndroid`——绿（shared 纯增量）。
- [ ] **Step 5:** Commit：`refactor(shared): hoist OpenUI validators to shared SSOT (U1-1)`

### Task 2: U1 — prompt 资产 + 回合契约上提（参数化两变体槽）

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiPromptAssets.kt`（自 server 搬运，逐字节；`ICON_NAMES_TEXT`/`DSL_RULES` 等签名不变）
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiTurnNativeContract.kt`（自 server `OpenUiTurnNative` 搬运 + 参数化，见下）
- Modify: server `OpenUiTurnNative.kt` → 薄壳委托（`SYSTEM_PROMPT = OpenUiTurnNativeContract.buildSystemPrompt()`；`ensureValidProgram`/`escapeOpenUiString` 委托）；`OpenUiPromptAssets.kt` → typealias 或薄壳（server 内其他消费面如 `OpenUiPromptAssets.DSL_RULES`/`GOLDEN_*` 引用不改）
- Test: server `OpenUiPromptAssetsTest`/`OpenUiTurnNativeTest` 原位保留钉字节

**Interfaces:**
- Produces:
  - `OpenUiTurnNativeContract.buildSystemPrompt(identityLine: String = DEFAULT_IDENTITY_LINE, toolNote: String = DEFAULT_TOOL_NOTE): String`——默认值与现网 `SYSTEM_PROMPT` 逐字节一致（身份行「你是 PoLang 云端 Agent。…」、toolNote = 「需要服务器时间时调用 server_time 工具；」句）；端侧传端侧变体。
  - `OpenUiTurnNativeContract.ensureValidProgram(text: String, language: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE): String`
  - `OpenUiTurnNativeContract.GOLDEN_CHAT_PROGRAM: String`
  - `OpenUiTurnNativeContract.escapeOpenUiString(raw: String): String`
- 现 `SYSTEM_PROMPT` 的 KDoc 头（`val SYSTEM_PROMPT: String = """你是 PoLang 云端 Agent。…`）拆为：身份行常量、`回复形态先定位` 主体常量、结构约定段（含 toolNote 槽）、DSL_RULES 拼接、组件清单拼接。组装顺序逐字节钉住。

- [ ] **Step 1:** 搬运 `OpenUiPromptAssets` 到 shared（`import com.mamba.picme.domain.chat.openui.OpenUiLibrary` 同包免改）；server 原文件改 typealias/薄壳。
- [ ] **Step 2:** 写 failing test（shared commonTest 新件 `OpenUiTurnNativeContractTest`）：`buildSystemPrompt()` 默认输出 == 现网 SYSTEM_PROMPT 逐字节（测试内嵌从 server 测试拷来的特征断言：含 GOLDEN_CHAT_PROGRAM、含「server_time」、含组件计数行）；`buildSystemPrompt(identityLine = "你是 PoLang 相册 AI 助手。", toolNote = "")` 不含「server_time」且身份行替换。
- [ ] **Step 3:** 实现 `OpenUiTurnNativeContract`（搬运 + 两槽参数化；slf4j 日志改 shared `Logger`，`turnNativeLogger` → `Logger.w/d(tag, …)` 语义保留）；server `OpenUiTurnNative` 改薄壳委托。
- [ ] **Step 4:** 跑 Step 2 测试 + server `OpenUiTurnNativeTest`/`OpenUiPromptAssetsTest` + `./gradlew -p server build`——全绿。
- [ ] **Step 5:** Commit：`refactor(shared): hoist OpenUI turn-native contract with variant slots (U1-2)`

### Task 3: U1 — ProgramGuard 上提为 shared OpenUiProgramGuard

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiProgramGuard.kt`
- Modify: server `ProgramGuard.kt` → typealias（`internal typealias ProgramGuard = OpenUiProgramGuard`）或薄壳
- Test: server `ProgramGuardTest` 原位保留；shared commonTest `OpenUiProgramGuardTest`

**Interfaces:**
- Produces:
  - `class OpenUiProgramGuard`（包 `domain.chat.openui`）：`invalidProgramError(assistantText: String): String?`、`markCorrected()`、`reset()`（新增，端侧跨回合复用场景）、`asCorrector(): FinalOutputCorrector`、`companion MAX_CORRECTIONS = 1`、`correctionPrompt(error: String): String`（文案逐字节搬运）。
  - `FinalOutputCorrector`（既有，`agent.core.inference.remote.koog`）。

- [ ] **Step 1:** 写 failing test `OpenUiProgramGuardTest`：非法程序首轮返回错误、`markCorrected` 后达上限返回 null、`reset()` 后恢复门控、`correctionPrompt` 含「逐条修正后重新输出完整程序」与错误正文。
- [ ] **Step 2:** 实现（自 server ProgramGuard 搬运；`java.util.concurrent.atomic.AtomicInteger` → `kotlin.concurrent.atomics.AtomicInt` + `@OptIn(ExperimentalAtomicApi::class)`，KoogChatAgent 同款先例；校验改调同包 `RenderOpenUiValidator.validateProgramToError`）。
- [ ] **Step 3:** server `ProgramGuard.kt` 改 typealias；跑 `ProgramGuardTest` + `./gradlew -p server build`——绿。
- [ ] **Step 4:** 跑 `JITPACK=true ./gradlew :shared:jvmTest`（新增 guard 测试 + 裸 @Volatile 类坑此任务暴露 `:shared:assemble`——跑一次）——绿。
- [ ] **Step 5:** Commit：`refactor(shared): hoist ProgramGuard as OpenUiProgramGuard (U1-3)`

### Task 4: U2 — 策略层缺口三修（loopGuard 边 / End 帧 metrics / 前缀嗅探）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogReActStrategy.kt`（guardedStrategy/plainConfigurableStrategy 补 loopGuard 熔断边——与 builtinOneShotStrategy 同构：nodeExecuteTool 出边声明在回落边之前，命中携 `ToolLoopGuard.TRIPPED_SENTINEL` 直达 nodeFinish）
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/TurnUsageAccumulator.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/openui/OpenUiProgramSummary.kt`（新增前缀嗅探）
- Test: `shared/src/commonTest/kotlin/.../koog/KoogReActStrategyTest.kt`（既有则扩）、`TurnUsageAccumulatorTest`、`OpenUiProgramSummaryTest`（扩）

**Interfaces:**
- Produces:
  - `class TurnUsageAccumulator`：`onStreamingEnd(inputTokens: Int?, outputTokens: Int?)`、`onCallCompleted(inputTokens: Int?, outputTokens: Int?)`、`promptTokens: Int`、`completionTokens: Int`。语义：两路分别累加（互斥由 Koog 事件语义保证——流式轮只出 End、非流式轮只出 onLLMCallCompleted；server 0.9.17 已实证）。
  - `OpenUiProgramSummary.looksLikeProgramPrefix(text: String): Boolean`——`looksLikeProgram` 的前缀版：首行 `// summary:` 开头 ‖ 任一非空行以 `root =`/`root=` 起首 ‖ `looksLikeProgram(text)` 为 true。前 2 字符噪声（空白/BOM）容忍，空串 false。
- Consumes: `ToolLoopGuard`（既有同包）。

- [ ] **Step 1:** 写 failing tests：①策略配置 `streaming=true + corrector + loopGuard` 时熔断边存在（沿用既有策略测试的图结构断言风格——若既有测试只测行为则写行为级：guard 命中后结果 == TRIPPED_SENTINEL）；②`TurnUsageAccumulator` 两路累加互不干扰；③`looksLikeProgramPrefix`：「// summary: x」true、「root = Markdown(」起首 true、普通中文文本 false、空串 false。
- [ ] **Step 2:** 跑确认三处 FAIL（边缺失/类不存在/函数不存在）。
- [ ] **Step 3:** 实现三件。guardedStrategy 补边时注意：loopGuard 为 null 时不挂（config 闭集不变）。
- [ ] **Step 4:** 跑 `JITPACK=true ./gradlew :shared:jvmTest`——绿。
- [ ] **Step 5:** Commit：`feat(shared): strategy loop-guard edge, stream metrics accumulator, program-prefix sniff (U2-1)`

### Task 5: U2 — RemoteReActAgentConfig 开关 + KoogChatAgent 接线

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt`（Builder 增两字段）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogChatAgent.kt`
- Test: shared commonTest `KoogChatAgentTest`（既有则扩；策略配置选择逻辑若难直测，抽出纯函数判定）

**Interfaces:**
- Produces:
  - `RemoteReActAgentConfig.Builder.openUiTurnNative(enabled: Boolean)`（默认 false）、`.replyLanguage(lang: ReplyLanguage)`（默认 SIMPLIFIED_CHINESE）；config 同名只读属性。
  - KoogChatAgent 行为契约（测试钉住）：`openUiTurnNative=true` 时 buildAgent 用 `PoLangReActStrategyConfig(streaming = true, finalOutputCorrector = guard.asCorrector(), onStreamingFallback = { Logger.w(...) }, toolLoopGuard = toolLoopGuard)`；false 时逐字节保留现行 config（只传 toolLoopGuard）。
  - prompt 组装契约：`composeSystemPrompt` 末尾（技能清单段之后）追加 `OpenUiTurnNativeContract.buildSystemPrompt(identityLine = 端侧身份行, toolNote = "")` + `openUiReplyLanguageRuleSegment(config.replyLanguage)`，仅 flag on。契约段文本进 agent 重建键（与 skillSection 同槽处理：段文本比对）。

- [ ] **Step 1:** 写 failing test：config 默认 `openUiTurnNative=false`；flag on 时 composeSystemPrompt 产物含「OpenUI Lang 1.0 程序」契约特征串与 `openUiReplyLanguageRuleSegment` 特征串、且位于技能清单段之后；flag off 时产物与现行逐字节一致。
- [ ] **Step 2:** 实现 config 两字段 + KoogChatAgent：①guard 实例字段 `private val openUiGuard = OpenUiProgramGuard()`；②`runChat` 开头 `toolLoopGuard.reset()` 同点位加 `openUiGuard.reset()`；③buildAgent 按 flag 选策略 config；④composeSystemPrompt 追加契约段（重建键增契约段文本比对——flag off 时段为空串入键无扰）；⑤EventHandler `onLLMStreamingFrameReceived` 增 `is StreamFrame.End ->` 分支喂 `TurnUsageAccumulator.onStreamingEnd`，`onLLMCallCompleted` 改喂 `onCallCompleted`，最终 metrics 读 accumulator（替代直读 AtomicInt——两 AtomicInt 删除）。
- [ ] **Step 3:** 跑 `JITPACK=true ./gradlew :shared:jvmTest`——绿（含既有 KoogChatAgent/composeChatSystemPrompt 测试不红）。
- [ ] **Step 4:** Commit：`feat(shared): KoogChatAgent OpenUI turn-native mode (U2-2)`

### Task 6: U2 — RemoteChatEngine 开关读取 + 契约段 + engine 层兜底

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/RemoteChatEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/facade/AgentConfigurator.kt`（增开关 provider 注入缝，组合根注入；默认 `{ false }` 保持非 Android 平台零行为变化）
- Test: shared commonTest `RemoteChatEngineTest`（既有则扩）

**Interfaces:**
- Consumes: Task 5 的 config 字段；Task 2 的 `OpenUiTurnNativeContract.ensureValidProgram`。
- Produces:
  - `AgentConfigurator.openUiTurnNativeProvider: (() -> Boolean)?`（组合根注入）。
  - 行为契约：①`getChatAgent` 的 configChanged 比对增开关位（provider 现值 != 缓存位 → 重建）；开关值经 `RemoteReActAgentConfig.Builder.openUiTurnNative` + `.replyLanguage(replyLanguage)` 传入。②`processChatReAct` 成功路径：开关 on 时 summary 经 `ensureValidProgram(summary, replyLanguage)` 兜底后返回（IM/飞书消费方同拿到兜底文本，云端口径）；off 时原样。③子集 prompt 缓存（toolSetPromptCache）不含契约段——契约段在 KoogChatAgent 层追加（Task 5 已定位），本任务不动缓存。

- [ ] **Step 1:** 写 failing test：开关 false→true 切换后 getChatAgent 重建（缓存失效）；processChatReAct 成功且开关 on 时非程序 summary 被包装为 `root = Markdown("…")` 程序（processChatReAct 为 internal 且有 agent 依赖——抽纯函数 `wrapFinalOutput(summary, enabled, language)` 直测）。
- [ ] **Step 2:** 实现（含 `wrapFinalOutput` 纯函数抽取）。
- [ ] **Step 3:** 跑 `JITPACK=true ./gradlew :shared:jvmTest`——绿。
- [ ] **Step 4:** Commit：`feat(shared): engine-level OpenUI toggle and final-output guard (U2-3)`

### Task 7: U2 — ChatViewModel 统一嗅探落卡

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/chat/ChatViewModelTest.kt`（既有则扩）

**Interfaces:**
- Consumes: shared `OpenUiProgramSummary.looksLikeProgram`、`OpenUiContract.LANG_VERSION`；既有 `insertOpenUiCardMessage`（cloud 路径）的 part 构造。
- Produces:
  - `private suspend fun insertAssistantFinalMessage(sessionId: String, text: String, modelUsed: String, performance: LlmPerformance?, reasoningParts: List<MessagePart.Reasoning>, sourceParts: List<MessagePart.SourceUrl>)`：engine 已兜底故 text 恒为程序——`looksLikeProgram` true → `insertPartsMessage(role = ASSISTANT, parts = reasoningParts + sourceParts + OpenUiCard(partId = "p0", program = text, fallbackSummary = OpenUiProgramSummary.extractFallbackSummary(text), langVersion = OpenUiContract.LANG_VERSION), modelUsed = modelUsed, extraMetadataJson = performanceMetadataJson(performance))` + `touchSession`；false（防御不可达）→ 走原 `insertAgentMessage`。
  - 三个调用点改道：`handleAgentAction` 的 TextReply 分支、dispatch 失败 fallback（ChatViewModel.kt:2020）、纯文本分支（:2029）。开关 off 时三处保持原 `insertAgentMessage`（开关读取：ViewModel 注入同一 provider）。

- [ ] **Step 1:** 写 failing test（ViewModel 层既有测试范式）：程序文本落库后消息 parts 含 OpenUiCard 且 reasoning 在前；非程序文本落文本 part；开关 off 时程序文本也落文本 part。
- [ ] **Step 2:** 实现 + 三调用点改道。
- [ ] **Step 3:** 跑 `./gradlew :androidApp:testDebugUnitTest --tests "*ChatViewModel*"`——绿；再跑 `:androidApp:compileDebugKotlinAndroid`。
- [ ] **Step 4:** Commit：`feat(android): unified sniff-and-persist OpenUI card for local turns (U2-4)`

### Task 8: U2 — 流式预览接线（程序前缀 → OpenUiStreamingCard）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（`sendMessage` 的 onEvent 分发，ChatViewModel.kt:1926-1962）
- Test: ChatViewModelTest 扩

**Interfaces:**
- Consumes: Task 4 `looksLikeProgramPrefix`；既有 `_openUiStreamProgram` StateFlow 与 displayMessages 预览合成（ChatViewModel.kt:1247-1266，云端路径已铺好，零渲染层改动）。
- Produces（行为契约）：
  - `TextSnapshot` 分发：开关 on 且 `looksLikeProgramPrefix(event.text)` → `_openUiStreamProgram.value = event.text`，**不喂** pacingController/turnAdapter（本轮无文本气泡）；否则现行路径。轮内一旦命中预览，后续快照同路（轮内 latch，防抖动来回切）。
  - `RoundStarted` → latch 复位 + `_openUiStreamProgram.value = null`（新一轮从空累计，对齐云端 onRoundStart）。
  - `ToolCallStarted` → latch 复位 + 预览清空（工具轮打断程序轮）。
  - 回合收尾三处清空预览：onSuccess（:1982 `_streamingMessage.value = null` 同点位）、onFailure（:2041）、CancellationException 分支（:2090）。

- [ ] **Step 1:** 写 failing test：程序前缀快照 → `_openUiStreamProgram` 置值且 `_streamingMessage` 不更新正文；RoundStarted 清空；开关 off 时快照走原路径。
- [ ] **Step 2:** 实现（latch 字段 `private var openUiPreviewRound = false`）。
- [ ] **Step 3:** 跑 ViewModel 测试 + 编译——绿。
- [ ] **Step 4:** Commit：`feat(android): streaming OpenUI preview for local turns (U2-5)`

### Task 9: U2 — 用户停止半截口径（预览非空丢弃）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（CancellationException 分支 :2086-2095）
- Test: ChatViewModelTest 扩

- [ ] **Step 1:** 写 failing test：停止时 `_openUiStreamProgram` 非空 → 不落库任何消息、预览清空；预览为空且文本半截非空 → 保留现行落库。
- [ ] **Step 2:** 实现：`keepPartial` 判定增 `&& _openUiStreamProgram.value == null`；分支内统一 `_openUiStreamProgram.value = null`。
- [ ] **Step 3:** 跑测试——绿。
- [ ] **Step 4:** Commit：`feat(android): discard partial program on user stop (U2-6)`

### Task 10: U2 — 端侧工具 handle 回执 + mediaType（选项 A）

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/ChatToolService.kt`（search_media 增第 6 参 `mediaType`（空串=photo/video/all）；三工具 @LLMDescription 增句柄双轨措辞：「结果尾部附数据句柄 local://scan/<id>，OpenUI 程序绑定照片/视频展示时使用；media id 保留供 render_html 与写工具」）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/command/AgentCommands.kt`（`SearchMedia` 增 `mediaType: String = "photo"` 字段）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt`（search/refine 回执注册句柄 + 类型过滤）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/capability/` 摘要能力件（get_gallery_summary 注册全库句柄，对齐 `CloudAgentGalleryTools.getGallerySummary`）
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（能力件注入 `LocalHandleStore`，CloudAgentGalleryTools 既有接线先例）
- Test: `ChatSearchCapabilityTest`（既有则扩）+ `ChatIntentContractToolNamesTest` 类契约测试跟进

**Interfaces:**
- Consumes: `LocalHandleStore.put(domain, payloadJson)`（androidApp data.local）、`OpenUiContract.DOMAIN_SCAN`/`formatHandleToken`、`ScanPhotoEntry(id, captureDate, mediaType)`。
- Produces: 回执尾缀格式与云端工具桥逐字一致：`（数据句柄 local://scan/<12hex>）`；注册失败静默降级纯前缀（catch Exception 记日志，不走 error: 协议）。refine 的 mediaType 跟随产生基数的 search（CloudAgentGalleryTools `refineMediaType` 先例）。

- [ ] **Step 1:** 写 failing test：search 命中后回执含 `（数据句柄 local://scan/` 且 store 可按 id 取回 entries（fake store）；mediaType="video" 只注册 VIDEO entries；store 抛异常时回执为纯前缀。
- [ ] **Step 2:** 实现（SearchOutcome 组装处挂句柄尾缀；注册逻辑抽私有函数镜像 `registerScanHandle`/`appendHandleSuffix`）。
- [ ] **Step 3:** 三工具描述更新 + SearchIntent/命令/能力件 mediaType 贯通；iOS `IosCloudAgentToolBridge` 的 search/refine 已注册句柄（2026-11-12 批次），本任务不动 iOS。
- [ ] **Step 4:** 跑 `./gradlew :androidApp:testDebugUnitTest --tests "*ChatSearch*" --tests "*ChatGallerySummary*"` + `:shared:jvmTest` + 编译——绿。
- [ ] **Step 5:** Commit：`feat(chat): local tool receipts carry local_handle for OpenUI binding (U2-7)`

### Task 11: U2 — 回滚闸设置项（DataStore + 调试页 + 五语）

**Files:**
- Modify: `UserSettingsRepository`（androidApp，增 `localTurnOpenUiEnabledFlow: Flow<Boolean>` 默认 true + setter；DataStore key `local_turn_openui_enabled`）
- Modify: 调试页/设置页对应 Screen + ViewModel（开关项）
- Modify: `androidApp/src/main/res/values{,-zh-rCN,-zh-rTW,-es,-fr}/strings.xml`（设置项标题/副标题五语）
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（provider 注入 AgentConfigurator；ChatViewModel 注入同一 flow 供 Task 7/8 开关读取）
- Test: 设置读写单测（既有设置测试范式）

- [ ] **Step 1:** 写 failing test：默认 true；写 false 后 flow 发射 false。
- [ ] **Step 2:** 实现 + 五语文案 + AppContainer 接线。
- [ ] **Step 3:** 跑 `./gradlew :androidApp:testDebugUnitTest` + 编译——绿。
- [ ] **Step 4:** Commit：`feat(settings): local-turn OpenUI rollback toggle (U2-8)`

### Task 12: U2 — 文档同步 + 真机闭环

**Files:**
- Modify: `docs/superpowers/specs/2026-11-07-layered-agent-runtime-design.md`（§3 决策 1 修订：L7 合线为 turn-native 唯一产出协议，指针到本 spec）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_PROTOCOL_TECH_SPEC.md`（本地回合段增 turn-native 口径）
- Modify: `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（data_genui_program 节增本地回合产出来源）
- Modify: `shared/AGENTS.md`（openui 包新件登记）、`androidApp/AGENTS.md`（chat 链路口径）、`server/AGENTS.md`（六件真引用收口）

- [ ] **Step 1:** 文档五处同步（[DOC-SYNC] 红线）。
- [ ] **Step 2:** 全量验证：`JITPACK=true ./gradlew :shared:jvmTest` + `./gradlew -p server build` + `./gradlew :androidApp:testDebugUnitTest`——全绿。
- [ ] **Step 3:** 真机闭环（`./scripts/auto-dev-loop.sh` 或手工）：①纯对话 → Markdown 程序卡（非气泡）；②「看看我今年的照片」→ search_media 拿句柄 → PhotoGrid 卡渐进生长；③回滚闸关 → 文本气泡回归；④开关切回 → 下一条消息即 OpenUI（不重启）；⑤回合中停止 → 半截程序不落库。
- [ ] **Step 4:** Commit：`docs: L7 unification decision + protocol docs (U4)`
