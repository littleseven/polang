# Agent 步骤时间线（Android M1）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 主聊天云端 Agent 回合进行中，在流式气泡内展示策划过的步骤时间线（RUNNING/SUCCESS/FAILED），回合结束即消失。

**Architecture:** 纯消费现有 SSE 帧——`CloudAgentTurnRunner` 把现在丢弃的 `action`/`observation` 事件映射为 `StepStarted`/`StepFinished`；`ChatViewModel` 用纯 reducer `AgentStepTimeline` 归约步骤列表并挂到瞬态 `_streamingMessage.agentSteps`；`ChatScreen` 流式分支渲染。不落 Room、不加网络请求、不动服务端。

**Tech Stack:** Kotlin / Jetpack Compose / kotlinx.coroutines；JUnit4 + UnconfinedTestDispatcher（现有测试基建）。

**Spec:** `docs/superpowers/specs/2026-11-01-agent-step-timeline-and-admin-trace-design.md`（§4 现状、§5 M1 设计、§8 验收）

## Global Constraints

- **[I18N]** 新增用户可见文案五语同步（EN/zh-CN/zh-TW/ES/FR），key 用 `cloud_agent_*` 前缀；禁止 `else` 直出工具名。
- **[PARITY]** M1 交付时固化步骤时间线 UI spec（布局/颜色/行为），落 `docs/08-UI-SPECS/`，供 M3 iOS 读取。
- **不新增持久化、不新增网络请求**：步骤是瞬态内存态，回合结束（含取消/异常）即弃。
- **孤儿 Observation 不上屏**：`actionEventId == 0` 或找不到配对步骤的 `StepFinished` 一律忽略（服务端门控拦截会产生此类事件，见 spec §5.1）。
- **工作区隔离**：动手前按 `using-git-worktrees` 在 `.worktrees/` 建隔离 worktree + 专用分支（AGENTS.md §3.4）。
- 文案精确值见 Task 3 表格（五语全文照抄，不得自行改写）。

## Review Focus

1. **孤儿 observation（`actionEventId=0`）**：门控拦截的 render_ui 会产生它——必须不生成幽灵步骤（Task 1/2 测试钉住）。
2. **上一回合的 pending 重放 observation**：回合开始服务端会闭合旧 action，stepId 不在本回合列表——必须静默忽略（Task 2 测试钉住）。
3. **`content` 以 `"error"` 开头的 observation**：步骤必须置 FAILED（Task 1 测试钉住）。
4. **发卡工具（render_ui/render_ui_openui/render_html）**：既出卡事件也出步骤事件，两份表达不冲突（Task 1 断言两事件都在；Task 5 渲染检查）。
5. **会话失效重试路径**（`runCloudTurn` 中 session invalid 重建）：重试前必须清空步骤，否则新旧回合步骤混杂（Task 4 步骤钉住）。

---

### Task 0: GenUI 默认线格式切到 OpenUI（2026-11-01 用户定）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`（`normalizeGenUiFormat` :1412-1414）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunner.kt`（`runTurn` 默认参数 :64）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`（可组合默认参数 :443）
- Test: `androidApp/src/test/java/com/mamba/picme/data/preferences/UserPreferencesRepositoryTest.kt`（无此文件则新建，模式参照同目录既有 preferences 测试）

**Interfaces:**
- Consumes: 无（独立改动）。
- Produces: 默认线格式 `"openui"`（后续 Task 的真机验收在 OpenUI 线下进行）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `unset genui format defaults to openui`()        // 空 DataStore → genUiFormatFlow.first() == "openui"
@Test fun `unknown genui format normalizes to openui`()    // 写入 "weird" → 读出 "openui"
@Test fun `explicit a2ui preference is preserved`()        // 写入 "a2ui" → 读出 "a2ui"（存量显式选择不翻）
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*UserPreferencesRepositoryTest*"`
Expected: 首条 FAIL（当前缺省归一 `"a2ui"`）

- [ ] **Step 3: 实现**

`normalizeGenUiFormat` else 分支 `"a2ui"` → `"openui"`，函数注释同步改「缺省/未知值归一默认 openui（2026-11-01 起）」；`CloudAgentTurnRunner.runTurn` 默认参数 `genUiFormat: String = "openui"`；`SettingsScreen.kt:443` 默认参数同步。

- [ ] **Step 4: 跑测试 + 编译确认**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*UserPreferencesRepositoryTest*" --tests "*CloudAgentTurnRunnerTest*" && ./gradlew :androidApp:compileDebugKotlin`
Expected: PASS + 编译过（`CloudAgentTurnRunnerTest` 既有用例显式传值，不受影响）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunner.kt androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt androidApp/src/test/java/com/mamba/picme/data/preferences/
git commit -m "feat(settings): default GenUI wire format to OpenUI"
```

---

### Task 1: CloudTurnEvent 步骤事件 + Runner 发射

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunner.kt`（`CloudTurnEvent` sealed interface :13-43；`handleEvent` :94-135）
- Test: `androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunnerTest.kt`

**Interfaces:**
- Consumes: 现有 `CloudFrame.Event`（`JSONObject`，含 `id`/`type`/`payload` 字段）。
- Produces（后续 Task 依赖）:
  ```kotlin
  /** 一步开始（action 事件；stepId = 服务端事件 id）。 */
  data class StepStarted(val stepId: Long, val tool: String) : CloudTurnEvent
  /** 一步结束（observation 事件；stepId = payload.actionEventId；ok = content 非 "error" 前缀）。 */
  data class StepFinished(val stepId: Long, val ok: Boolean) : CloudTurnEvent
  ```

- [ ] **Step 1: 写失败测试**

在 `CloudAgentTurnRunnerTest` 新增（FakeApi/framesScript 模式照抄现有测试）：

```kotlin
@Test
fun `action event emits StepStarted and observation emits StepFinished`() // frames:
//   action   {"id":11,"type":"action","role":"assistant","payload":{"type":"action","tool":"search_media","argsJson":"{}"}}
//   observation {"id":12,"type":"observation","role":"tool","payload":{"type":"observation","actionEventId":11,"content":"找到 3 张"}}
// assert events == [StepStarted(11, "search_media"), StepFinished(11, true)]

@Test
fun `observation with error content emits StepFinished ok=false`() // content "error: timeout" → StepFinished(id, false)

@Test
fun `orphan observation with actionEventId 0 is skipped`() // 不产生任何事件

@Test
fun `render_ui action emits both StepStarted and A2uiCard`() // 现有 A2uiCard 行为不回归 + StepStarted(id, "render_ui") 在卡事件之前
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*CloudAgentTurnRunnerTest*"`
Expected: FAIL（`StepStarted` 未定义，编译错误）

- [ ] **Step 3: 实现**

`CloudTurnEvent` 增上述两个子型（放在 `ToolCall` 之后，注释风格对齐现有）；`handleEvent` 改动：

- `"action"` 分支：在进入 render_ui/render_ui_openui 卡解析**之前**，先 `onEvent(CloudTurnEvent.StepStarted(event.optLong("id"), payload.optString("tool")))`（所有 action 都发步骤，含发卡工具；卡解析逻辑原样保留）。
- `"observation"` 分支（新增，替换 else 中的忽略）：读 `payload.optLong("actionEventId")`，`== 0L` 直接 return；否则 `onEvent(CloudTurnEvent.StepFinished(actionEventId, !payload.optString("content").startsWith("error")))`。
- else 分支保留忽略 `finish`/`condensation`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*CloudAgentTurnRunnerTest*"`
Expected: PASS（含既有用例全绿）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunner.kt androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentTurnRunnerTest.kt
git commit -m "feat(cloudagent): emit StepStarted/StepFinished from action/observation events"
```

---

### Task 2: AgentStepTimeline 纯 reducer + 步骤 UI 模型

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/AgentStepTimeline.kt`
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`（`ClaudeStepUi` :130 附近）
- Test: `androidApp/src/test/java/com/mamba/picme/features/cloudagent/AgentStepTimelineTest.kt`

**Interfaces:**
- Consumes: Task 1 的 stepId/tool 概念。
- Produces（Task 3/4/5 依赖）:
  ```kotlin
  // shared/domain/chat/ChatMessage.kt（ClaudeStepUi 旁；放 shared 供 ChatMessageUi 引用与 iOS M3 KMP 同源）
  data class AgentStepUi(val stepId: Long, val label: String, val status: AgentStepStatus)
  enum class AgentStepStatus { RUNNING, SUCCESS, FAILED }

  // androidApp features/cloudagent/AgentStepTimeline.kt
  class AgentStepTimeline {
      val steps: List<AgentStepUi>                       // 只读快照（内部 MutableList）
      fun start(stepId: Long, label: String)             // 同 id 重复 start 幂等忽略；内部记 startedAtMs
      fun finish(stepId: Long, ok: Boolean): AgentStepUi? // 未知 id → null（静默忽略）；命中 → RUNNING→SUCCESS/FAILED 并返回更新后步骤（供日志取耗时）；终态步骤再 finish → null
      fun clear()
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `start appends running step and finish marks success`()
@Test fun `finish with ok=false marks FAILED`()
@Test fun `finish unknown stepId returns null and is ignored`()  // 上一回合 pending 重放场景
@Test fun `duplicate start with same id is idempotent`()
@Test fun `finish already finished step returns null`()
@Test fun `finish returns updated step with duration recorded`() // spec §5.5 结构化日志的耗时来源
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*AgentStepTimelineTest*"`
Expected: FAIL（类未定义，编译错误）

- [ ] **Step 3: 实现**

`ChatMessage.kt` 增 `AgentStepUi`/`AgentStepStatus`（`ClaudeStepUi` 旁，注释「云端 Agent 回合的一步（瞬态，不落 Room）」）；`AgentStepTimeline.kt` 实现 reducer（内部 `MutableList<AgentStepUi>`，`steps` 暴露 `toList()` 快照或直接只读视图）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*AgentStepTimelineTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt androidApp/src/main/java/com/mamba/picme/features/cloudagent/AgentStepTimeline.kt androidApp/src/test/java/com/mamba/picme/features/cloudagent/AgentStepTimelineTest.kt
git commit -m "feat(cloudagent): add AgentStepTimeline reducer and step UI model"
```

---

### Task 3: 五语文案 + 标签映射函数

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`、`values-es/strings.xml`、`values-fr/strings.xml`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/AgentStepTimeline.kt`（补 `agentStepLabelRes`）
- Test: `androidApp/src/test/java/com/mamba/picme/features/cloudagent/AgentStepTimelineTest.kt`（补 label 用例）

**Interfaces:**
- Produces: `agentStepLabelRes(tool: String): Int?`（Task 4 消费）；六个 `R.string.cloud_agent_step_*` key。

- [ ] **Step 1: 五个 strings.xml 各加 6 条（key 与译文照抄）**

| key | EN (values) | zh-CN | zh-TW | ES | FR |
|-----|-----|-------|-------|----|----|
| `cloud_agent_step_gallery_summary` | Looking through your gallery… | 正在浏览相册概况… | 正在瀏覽相簿概況… | Revisando tu galería… | Parcours de votre galerie… |
| `cloud_agent_step_search_media` | Searching your photos… | 正在搜索照片… | 正在搜尋照片… | Buscando tus fotos… | Recherche dans vos photos… |
| `cloud_agent_step_refine_search` | Refining the search… | 正在细化搜索… | 正在細化搜尋… | Afinando la búsqueda… | Affinage de la recherche… |
| `cloud_agent_step_compose_card` | Composing a card… | 正在生成卡片… | 正在產生卡片… | Creando una tarjeta… | Création d'une carte… |
| `cloud_agent_step_build_page` | Building a page… | 正在生成页面… | 正在產生頁面… | Generando una página… | Génération d'une page… |
| `cloud_agent_step_failed_hint` | didn't work, trying another way… | 未成功，换个方式试试… | 未成功，換個方式試試… | no funcionó, probando otra forma… | échec, essai d'une autre approche… |

- [ ] **Step 2: 实现 `agentStepLabelRes` 并补测试**

穷尽 when（常量来自 `CloudAgentToolContract`；`TOOL_RENDER_HTML` 同文件已有）：
`get_gallery_summary→gallery_summary`、`search_media→search_media`、`refine_media_search→refine_search`、`render_ui`/`render_ui_openui→compose_card`、`render_html→build_page`、`"server_time"`（字面量，contract 无常量）→ null、`else -> null`。
把 Task 2 挪来的 `label mapping covers…` 测试补上。

- [ ] **Step 3: 编译 + 测试**

Run: `./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:testDebugUnitTest --tests "*AgentStepTimelineTest*"`
Expected: 编译过、PASS

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/res/values*/strings.xml androidApp/src/main/java/com/mamba/picme/features/cloudagent/AgentStepTimeline.kt androidApp/src/test/java/com/mamba/picme/features/cloudagent/AgentStepTimelineTest.kt
git commit -m "feat(cloudagent): i18n labels for agent step timeline (5 languages)"
```

---

### Task 4: ChatMessageUi.agentSteps + ChatViewModel 接线

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt`（`ChatMessageUi` 字段区 :45-51）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（`runCloudTurn` :2192-2253、`runCloudTurnOnce` :2406-2471）
- Test: 无新增单测（胶水层；reducer 行为已由 Task 2 钉住，端到端由 Task 6 真机验证）

**Interfaces:**
- Consumes: `AgentStepTimeline`/`AgentStepUi`/`agentStepLabelRes`（Task 2/3）；Task 1 的 `StepStarted`/`StepFinished`。
- Produces: `ChatMessageUi.agentSteps: List<AgentStepUi>`（Task 5 渲染消费）；`ChatViewModel` 私有成员 `cloudStepTimeline`。

- [ ] **Step 1: ChatMessageUi 加瞬态字段**

`ChatMessage.kt`：`ChatMessageUi` 增加 `val agentSteps: List<AgentStepUi> = emptyList()`（放 `isThinking` 附近，注释「云端回合步骤时间线（瞬态，仅流式占位消息持有，不落 Room）」）。

- [ ] **Step 2: ChatViewModel 持有并生命周期管理**

- 私有成员 `private val cloudStepTimeline = AgentStepTimeline()`（跟随 VM 生命周期，每回合开头 clear）。
- `runCloudTurn`：`_openUiStreamProgram.value = null`（:2215）旁加 `cloudStepTimeline.clear()`；会话失效重试重置占位气泡（:2226-2230）处再次 `cloudStepTimeline.clear()` 并把新占位气泡 `agentSteps` 置空（copy 默认即空，显式调 clear 即可）。

- [ ] **Step 3: runCloudTurnOnce 事件接线**

- `is CloudTurnEvent.StepStarted ->`：`agentStepLabelRes(event.tool)?.let { res -> cloudStepTimeline.start(event.stepId, stringContext().getString(res)); _streamingMessage.update { m -> m?.copy(agentSteps = cloudStepTimeline.steps, isThinking = false) } }`（label null = server_time/未知工具：跳过；未知工具加 `Logger.w` 一记）。
- `is CloudTurnEvent.StepFinished ->`：`cloudStepTimeline.finish(event.stepId, event.ok)?.let { step -> Logger.d(TAG, "cloud step: ${step.label} -> ${step.status}") }`，随后 `_streamingMessage.update { m -> m?.copy(agentSteps = cloudStepTimeline.steps) }`（未知 id 被 reducer 忽略返回 null，update 无害）。
- `is CloudTurnEvent.ToolCall ->`：**删除气泡文本 swap**（:2425-2431 替换为空分支 + 注释「步骤时间线接管进度表达（M1），保留事件供调试页」）。

- [ ] **Step 4: 编译 + 回归测试**

Run: `./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:testDebugUnitTest`
Expected: 编译过、全量单测绿

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/chat/ChatMessage.kt androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt androidApp/src/main/java/com/mamba/picme/features/cloudagent/
git commit -m "feat(chat): wire cloud round step events into streaming bubble state"
```

---

### Task 5: ChatScreen 渲染 AgentStepsTimeline

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（新增 composable 放 `ClaudeAgentSteps` :1630 附近；渲染点 `ChatMessageItem` 流式分支 :1709-1721）

**Interfaces:**
- Consumes: `ChatMessageUi.agentSteps`（Task 4）；`R.string.cloud_agent_step_failed_hint`（Task 3）。
- Produces: `@Composable private fun AgentStepsTimeline(steps: List<AgentStepUi>)`。

- [ ] **Step 1: 实现 AgentStepsTimeline composable**

`Column(verticalArrangement = Arrangement.spacedBy(4.dp))`，每步一个 `Row(spacedBy(6.dp), vertically center)`：
- RUNNING：`CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tertiary)`
- SUCCESS：`Text("✓", color = primary, fontSize = 13.sp)`
- FAILED：`Text("⚠", color = error, fontSize = 13.sp)`
- 标签 `Text(label, fontSize = 13.sp, color = onSurfaceVariant, maxLines = 1, ellipsis)`；FAILED 追加 `" — " + stringResource(R.string.cloud_agent_step_failed_hint)`（同色）。

- [ ] **Step 2: 接入流式分支**

`ChatMessageItem` 的 `if (message.isStreaming)` 块（:1709）内、`if (message.isThinking) TypingIndicator() else …` **之前**插入：
`if (message.agentSteps.isNotEmpty()) { AgentStepsTimeline(message.agentSteps); Spacer(Modifier.height(6.dp)) }`。
（isThinking 三点保留在首个 StepStarted 前；StepStarted 到达后 VM 已置 isThinking=false，走文本分支，空文本 Row 无害。）

- [ ] **Step 3: 编译**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: 编译过

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): render cloud agent steps timeline in streaming bubble"
```

---

### Task 6: 调试页同构接入 + 真机验收 + 文档固化

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/cloudagent/CloudAgentChatViewModel.kt`、`CloudAgentChatScreen.kt`（inFlight 区域 :193-201）
- Test: `androidApp/src/test/java/com/mamba/picme/features/cloudagent/CloudAgentChatViewModelTest.kt`
- Create: `docs/08-UI-SPECS/CLOUD_AGENT_STEPS_TIMELINE.md`（PARITY 固化 spec）

**Interfaces:**
- Consumes: 前面全部。调试页 VM 消费同一个 `CloudAgentTurnRunner` 事件流。

- [ ] **Step 1: 调试页接线 + 测试**

`CloudAgentChatViewModel` 持有 `AgentStepTimeline` + `steps: StateFlow<List<AgentStepUi>>`；`StepStarted/StepFinished` 同款接线（label 经 `agentStepLabelRes` + 既有 string-provider 模式）；回合开始/结束 clear。`CloudAgentChatScreen` inFlight 区域渲染简化步骤列表（同 ChatScreen 视觉，可抽公共 composable 到 cloudagent 包复用，或各写一份——实现者择一，不许跨 features 包私用）。`CloudAgentChatViewModelTest` 增一条：action/observation 帧脚本 → steps 状态断言。

- [ ] **Step 2: 测试 + 编译**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*CloudAgent*" && ./gradlew :androidApp:compileDebugKotlin`
Expected: PASS + 编译过

- [ ] **Step 3: 真机验收（按 android-build-debug / adb-bot skill 闭环）**

装 debug 包，主聊天发起云端回合（如「帮我看看相册里最近的照片，做个统计卡片」）：
- 可见 ≥2 步依次出现（RUNNING→SUCCESS），发卡类提问可见「正在生成卡片…」步；
- 断网注入工具超时 → FAILED + 失败提示文案；
- 回合结束（含中途停止）步骤随流式气泡消失；
- 切换 zh-CN/zh-TW/ES/FR 至少抽查 zh-TW 与 ES 各一次步骤文案。

- [ ] **Step 4: 固化 UI spec + 文档同步**

`docs/08-UI-SPECS/CLOUD_AGENT_STEPS_TIMELINE.md`：布局（Row/Column/间距 4dp·6dp·12dp 指示器）、颜色（tertiary/primary/error/onSurfaceVariant）、字号 13sp、状态机三态、FAILED 后缀规则、工具→文案映射表（五语全文）、瞬态不落库声明、空/孤儿事件行为。同步：`docs/superpowers/README.md` §6 状态推进；`androidApp/AGENTS.md` 云端回合段补一句步骤时间线机制。

- [ ] **Step 5: Commit**

```bash
git add androidApp/ docs/08-UI-SPECS/CLOUD_AGENT_STEPS_TIMELINE.md docs/superpowers/README.md
git commit -m "feat(chat): cloud agent steps timeline — debug page + UI spec freeze"
```
