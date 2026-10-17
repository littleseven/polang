# Chat 请求/回复协议技术规格（Chat Protocol Tech Spec）

> **定位**：主聊天流「用户输入 → 远程 LLM → 流式响应 → 渲染/落库 → 历史回灌」全链路协议的**系统描述 SSOT**。ADR-016 管「为什么这么设计」（决策宪法），本文管「现在是怎么跑的」（链路实况）；卡片级协议实例（每张卡的 tool_call 入参 / Room 落库 JSON / parts 形态）见 `CHAT_CARD_CATALOG.md`，本文只到链路级，不重复逐卡登记。
> **日期**：2026-10-11 建立（基于 main 分支实况勘察，Koog 1.3.0，Room v26）
> **增补**：2026-10-17 §2.8 线格式实例（SSE 帧骨架 ↔ 合并响应 ↔ 三轮 ReAct walkthrough）；M3 意图级工具裁剪落地（§1.4）+ server 侧 cached_tokens 可观测（§5 缺口两条核销/降级）。
> **上游**：ADR-016（parts 模型/渲染架构）、ADR-015（意图路由契约）、ADR-008（隐私红线）
> **维护规则**：链路改造（请求组装/流式事件/落库路径/历史管线任一变动）须同一原子提交内同步本文；行号引用会漂移，以文件级锚点为准。

---

## 0. 一张图看全链路

```
用户输入 (ChatViewModel.sendMessage)
  │
  ├─ 0. 落库用户消息（Room，insertMessageWithParts）
  ├─ 1. 意图路由 IntentRouter（ADR-015 前置闸门）
  │      ├─ 直执命中（VIEW_PHOTOS/REFINE）→ 直接 dispatch → 产卡落库，不走 LLM
  │      └─ 其余（含 BROWSE_WEB/OPEN_QA）→ 完整 agent loop
  │
  ▼
Koog Agent Loop（poLangSingleRunStrategy，ReAct 图循环）
  │  每轮：callLLM ──SSE 流式──▶ 文本快照 + tool_calls
  │            │                      │
  │            │                      ▼
  │            │         nodeExecuteTools（ToolRegistry 反射派发
  │            │          → ChatToolService @Tool → CapabilityRegistry.dispatch）
  │            │                      │
  │            │                      ├─ uiActions → UI 产卡落库（emit* 系列）
  │            │                      └─ observation 文本 → nodeSendToolResult 回喂
  │            ▼
  │  纯文本响应 → nodeFinish（终止）
  │
  ▼
流式三件套（shared domain/chat/streaming/）
  ChatStreamEvent（引擎层 3 值闭集）
    → ChatStreamTurnAdapter（快照差分 → 三段式）
    → TurnPartsReducer（占位/原位填充/瞬态错误）
    → _streamingMessage（内存态，不落 Room）
  │
  ▼
渲染（M4 parts 拍平）：displayMessages = Room 消息 + 流式消息
  + TaskCardOverlay / BrowserLiveOverlay（live 态原位覆写）
  → ChatListFlattener → LazyColumn
  │
  ▼
历史主从（分层记忆 spec M4，2026-10-11 起）：
  长期轨（SSOT）= Room chat_messages partsJson 归档
  短期轨（派生缓存）= Koog ChatMemory（DataStore，8000 token 预算 + 10 条上限 + 滚动压缩）
    ├─ 冷启动重建：短期轨空 ← Room 最近 50 条经 toModelInput 投影（sendMessage 前置兜底）
    └─ 删除传播：deleteSession/clearChat → clearChatMemory 同步清短期轨
  工程师隧道 = Room partsJson → toModelInput → toHistoryPair（GET_CHAT_HISTORY）
```

---

## 1. 请求侧协议

### 1.1 入口调用链

| 跳 | 位置 | 语义 |
|---|---|---|
| 1 | `ChatViewModel.sendMessage(text, imageUri)`（androidApp features/chat/ChatViewModel.kt） | DEV 捷径（`/html` `/task`）拦截；用户消息落 Room；构建 `AgentContext`（scene=CHAT、memorySessionId、recentSearchResults、lastUserImageUri、gallerySummary、traceId、persona、replyLanguage） |
| 2 | `RemoteChatEngine.streamChat(input, agentContext, onEvent)`（shared agent/core/inference/remote/RemoteChatEngine.kt） | 先走意图路由 `routeAndMaybeExecute`，命中直执则短路返回 |
| 3 | `streamChatReAct → processChatReAct` | `getChatAgent` 懒建/缓存 → `withTimeout(120s) { agent.runChat(...) }` |
| 4 | `KoogChatAgent.runChat`（shared .../remote/koog/KoogChatAgent.kt） | `SessionCompactor.maybeCompact` → 载会话摘要 → Koog `AIAgent.run(input, sessionId)` |

并发约束：`KoogChatAgent.isRunning()` 互斥（同一时刻一个回合）；整体超时 120s。

**媒体红线落地**：带图消息只把 URI **文本**拼进 input（`"[用户选择了图片：content://…] 文本"`），图片字节永不上传（ADR-008）。

### 1.2 意图路由前置（ADR-015）

拦截点：`RemoteChatEngine.streamChat` 开头。`IntentRouter.route` 判定顺序（shared agent/core/intent/IntentRouter.kt）：

1. **浏览器捷径**：URL 正则或「浏览器/网页/打开/访问」句式 → `BROWSE_WEB`（confidence=1.0），优先于门控；
2. **本地信号门控**：不含相册域信号（寒暄/开放问答）→ `OPEN_QA` 直通（GATED_PASSTHROUGH），零延迟；
3. **pattern 捷径**：「看/找/搜+…+照片」最热句式（≤40 字）→ `VIEW_PHOTOS` 短路；
4. **LLM 闭集分类**：11 意图闭集，temperature=0、maxTokens=256、**1.5s 硬超时** + schema 失败重试 1 次；超时/低置信（<0.6）一律降级 `OPEN_QA`。路由器调用是**非流式单发**。

策略层 `ChatRoutingPolicy.decide`：`VIEW_PHOTOS` → DirectSearch（有基数时 DirectRefine）；`REFINE_RESULTS` → DirectRefine；**`BROWSE_WEB` → FullAgentLoop**（走完整 agent loop 执行 browser_* 工具链，契约限定 allowedTools=browser_* 七件套，forbiddenTools 排除相册工具防误路由）；其余一律 FullAgentLoop。

直执语义（`executeDirect`）：复用 `ChatToolService.dispatchCommandDetailed`（与 LLM tool_calls **同源 dispatch**）；失败回落完整 agent loop；成功不产 LLM 总结，由平台层本地化渲染回执气泡；并**补写 Koog 会话记忆**（user 原文 + observation），防多轮指代断裂。每次判定落 `routing_audit_log` 审计。

### 1.3 system prompt 三段式组装

最终 system prompt = **base +【关于用户】+【会话摘要】**，组装于 `KoogChatAgent.composeChatSystemPrompt`：

**base**（`RemoteChatEngine.buildChatSystemPrompt + buildPromptSuffix`，getChatAgent 时烘焙进 config）：

| 段 | 来源 | 内容 |
|---|---|---|
| 角色行 | buildChatSystemPrompt | 「你是 PoLang 相册 AI 助手…」 |
| 工具清单 | `ToolInventory.build(descriptors)` | 按名字字典序、取描述首句（与 ToolRegistry 同源零漂移） |
| 行为规则 | `ChatPromptRules.render()` | 10+ 有序节：chart_rules / gallery_script_overview / memory_tools / capability_dispatch / **html_card_rules** / script_vs_tool / browser_rules / image_edit / refinement_rules / convergence_rules（含「每次请求最多 2 次工具调用」收敛规则） |
| 动态尾段 | buildPromptSuffix | 当前日期 + RenderEnvironment 段 + 性格段（DEFAULT 不注入）+ 回复语言规则（恒注入） |

**RenderEnvironment**（shared agent/core/model/context/RenderEnvironment.kt）：deviceType / screenWidthDp / screenHeightDp / screenWidthPx / screenHeightPx / cardContentWidthCssPx / cardSuggestedHeightCssPx / previewCardHeightCssPx / fullpageThresholdCssPx——LLM 排版 HTML 卡的确定基准（双形态 spec §8）。组装点 = Android 组合根 `PoLangApplication.buildRenderEnvironment()`（DisplayMetrics 换算），经 provider 注入，**环境变化不触发 agent 重建**。

**【关于用户】**：`MemoryContextProviderImpl.snapshot()`（Room facts + 人物关系，@Volatile 缓存），字符预算 1500，截断后附 recall_memory 兜底提示。
**【会话摘要】**：每轮 `memoryStore.loadSummary(sessionId)`，滚动压缩产物（见 §3.2）。

**params 烘焙**：`polangSystemPrompt(...)`（koog/KoogPromptFactory.kt）——🔴 必须走 Koog prompt DSL 传 params，否则 temperature/maxTokens/thinking 丢失。AIAgent 按「记忆快照内容 + 摘要 version」新鲜度懒重建。

### 1.4 工具集（ChatToolService，43 个 @Tool）

全部 `suspend`、参数无 Kotlin 默认值（Koog 反射/R8 稳健性）；每个都是 `dispatchCommand(AgentCommand, scene=CHAT)` 薄封装，超时 5s（browser_* 族 25s）。

**意图级工具子集（M3 分支化落地，2026-10-17，成本治理）**：契约表 `ChatIntentContract.allowedTools` 从声明转为执行——路由判定为单一意图且路径可靠（LLM_ROUTER / PATTERN_SHORTCUT / BROWSER_SHORTCUT）时，`ChatRoutingPolicy.toolSubsetFor` 产出「契约白名单 + 通用工具（finish/delay）」子集，经 `RouteDecision.FullAgentLoop.toolNames` 传到引擎层；`RemoteChatEngine.resolveToolSetOverride` 自同一份 `chatTools`（组合根新增注入，与 registry/descriptors 同源）过滤派生子集 registry + 子集 prompt 清单段（不变式 8 不破坏），以 `KoogChatAgent.ChatToolSetOverride` 按轮覆写——AIAgent 重建键加 toolSetKey（与记忆快照/摘要版本同级），**KoogChatAgent 单实例单 executor 不为子集多建**。**保守全量（null）场景**：OPEN_QA / 一切降级路径 / secondary 双产出物 / 直执失败回退 / 平台未注入 chatTools / 子集过滤为空——路由器永不拦截能力的语义不变。子集 base prompt 按 key 缓存逐字节复用（保前缀稳定）。**权衡**：不同意图的相邻回合 system prompt/tools 前缀不同，会碎片化上游前缀缓存——用缓存碎片换 ~40 条 schema（约 2-4k token/轮 × ReAct 轮数）的确定削减。iOS 经 `ChatToolManifest.tools` 同路径注入（8 工具手工清单）。

| 分组 | 工具 |
|---|---|
| 相册 | get_gallery_summary / search_media / refine_media_search / delete_media / share_media / select_media / favorite_media / switch_view_mode |
| 反馈 | record_feedback / more_like_this / exclude_constraint |
| 打标修图 | start_tag_scan / ai_optimize / adjust_image / edit_image |
| 脚本/卡片 | run_gallery_script / draw_chart / render_html |
| 云端浏览器 | browser_open / browser_navigate / browser_click / browser_type / browser_extract / browser_screenshot / browser_close |
| 记忆 | remember_person_relation / forget_person_relation / list_person_relations / remember_fact / forget_fact / recall_memory |
| 设置 | change_theme / change_language / toggle_setting / download_model / switch_face_engine |
| 导航/系统 | navigate_to / go_back / launch_app / open_system_settings |
| 通用 | delay / finish |

**tool schema → OpenAI `tools[]` 转换由 Koog 框架内部完成**（`AbstractOpenAILLMClient`，外部依赖，本仓库无手写转换层）。本地只做：组合根 `AndroidAgentComposition.initialize` 用 Koog JVM-only 反射 `ChatToolService.getInstance().asToolsByClass()` 展开注入 `ToolRegistry`；同名 descriptor 清单喂 system prompt「可用工具」段，**两者同源**。`@Tool(customName=…)` 保 LLM-facing 名字确定性，`@LLMDescription` 供描述。

### 1.5 模型与传输

- **默认走 server/ 网关**：`RemoteModelConfig.PICME_SERVER_DEFAULT`（modelId=`deepseek-v4-flash`，protocol=OPENAI，baseUrl=`https://api.polang.net/`）；用户可自配直连（tokenhub/kimi/deepseek/openai/anthropic）。
- **网关路径**：Koog `OpenAILLMClient` → `{baseUrl}chat/completions`；网关 `LlmRoute` 同时接 `/v1/chat/completions` 与 `/chat/completions`：限流 → 鉴权（tokenHash 或 deviceId）→ 配额 → 转发上游（Cloudflare AI Gateway / TokenHub）。
- **鉴权头**：`buildGatewayHeaders()` 注入 `X-App-Token` / `X-Device-Id` / `X-Platform`；apiKey 走 Koog 标准 Authorization。
- **协议分流**（RemoteModelFactory）：OPENAI → `OpenAILLMClient`（capabilities = Completion + Tools + Completions，🔴 不加 Responses/Thinking）；CLAUDE → `AnthropicLLMClient` + `modelVersionsMap` 映射。
- **params**：temperature 钳制（kimi-k2.6→1.0，其余 0.7）；maxTokens=**4096**；DeepSeek 系注入 `thinking.type=disabled`。
- **流式 = SSE**：Koog `nodeLLMRequest` 流式执行；网关 `ProxyResult.Streaming` 逐 chunk 透传上游 SSE 并 tee 解析 usage。

### 1.6 多轮工具循环与终止

`poLangSingleRunStrategy`（koog/KoogReActStrategy.kt）：start → callLLM → executeTool → sendToolResult 循环图，**与 Koog 内建 singleRunStrategy 唯一差异** = nodeSendToolResult 出边把 `onToolCalls` 声明在 `onTextMessage` 之前——修复 Koog（≤1.3.0）对「叙述文本+tool_calls 同帧」响应静默丢弃工具调用的缺陷。

tool 结果作为 `Message.User` 内嵌 `MessagePart.Tool.Result` 追加进 prompt 进入下一轮。**终止条件**：① LLM 返回纯文本（onTextMessage → nodeFinish，文本作回合 summary）；② `maxIterations`=10（×3 换算 Koog 节点步数 30）；③ 外层 120s 超时。

---

## 2. 回复侧协议

### 2.1 流式事件链（三级翻译）

```
Koog EventHandler（KoogChatAgent.buildAgent 内）
  onLLMStreamingStarting  → 快照清零 + RoundStarted
  onLLMStreamingFrameReceived → StreamFrame.TextDelta 累计进 snapshotBuffer
  onToolCallStarting      → (toolName, args 全量 JSON)（Koog 调用开始即给全量 args）
        │
        ▼ 引擎层闭集（shared .../remote/ChatStreamEvent.kt）
ChatStreamEvent = TextSnapshot(本轮累计全文快照，非 delta)
                | ToolCallStarted(toolName, args)
                | RoundStarted          ← M4 显式轮边界
        │
        ▼ 适配层（domain/chat/streaming/ChatStreamTurnAdapter.kt）
TurnStreamEvent 三段式（块级 id：txt-N / call-N）
  · 快照差分出 TextDelta（快照必须是前值前缀扩展；非扩展 = 兜底轮边界，闭合旧块开新块）
  · ToolCallStarted → 闭合当前文本块 + 连发 ToolInputStart + ToolInputAvailable
        │
        ▼ 归约层（TurnPartsReducer.kt，单线程契约，调用方漏斗保证）
流式消息 parts（内存态，不落 Room）
```

UI 侧每个事件分两路（ChatViewModel）：**legacy 文案路**（pacingController 驱动气泡打字机）+ **parts 路**（feedTurn → adapter → reducer → `_streamingMessage`）。

**打字机节奏**（StreamingPacingController）：50ms/字基准、中文 2 字一跳、标点 +100ms、换行 +200ms；`onTextSnapshot` 只更缓冲，paceLoop 定速播放；流式结束 `finish()` 追平 + 光标余闪 2.5s。

### 2.2 占位、原位填充与错误双轨

TurnPartsReducer 规则：

- **类型化占位**：仅 `draw_chart`→Chart、`render_html`→HtmlCard、`browser_open`→BrowserLive 有 `INPUT_STREAMING` 占位 part；其余工具只登记不产 part（渲染为 ToolStatusChip 状态 item）。
- **原位填充**：`toolOutput` 有位置锚 `replaceAt`（partId 以产物 toolCallId 为准）；无锚 append 尾部（脚本直出场景）。回填关联 `feedToolOutput/feedToolError`：同名最旧在途优先 → 任意在途 → 无在途跳过。
- **OUTPUT_ERROR 瞬态**：占位标错 + errorText 入内存 map——**不落库**，流式消息清除即消失（拍平层渲染为 tool_error item）。
- **错误双轨分流**：持久化轨 = 任务卡（SSE Error → FAILED 落 Room）；瞬态轨 = Chart/HtmlCard/脚本错误仅内存。例外：**browser 失败定格卡落库**（OUTPUT_ERROR 持久化）。
- **DONE 不可变**：Text DONE 后忽略 Delta；TextStart 幂等。

### 2.3 工具执行与 observation 双通道

`dispatchCommandDetailed`（ChatToolService.kt）：`withTimeout` 内 `CapabilityRegistry.dispatch(command, AgentContext(scene=CHAT))`。能力注册点在 Android 组合根（ChatSearchCapability / ChatRunScriptCapability / BrowserSessionCapability 等），多为 WeakReference Delegate 桥到 ChatViewModel。

成功后**双通道**：

1. `uiActions.tryEmit(action)` → ChatViewModel 产卡落库（§2.4）；
2. 按 action 类型生成**硬编码中文模板 observation**（"找到 N 张…"）作 @Tool 返回值 → Koog 自动包成 tool result 回喂下一轮 ReAct。⚠️ observation 只进模型/记忆，**绝不直达用户气泡**（I18N）；失败/超时统一 `"Error: ..."` 文本回传。

### 2.4 产物落库路径（emit* 全清单）

统一入口 `chatMessageDao.insertMessageWithParts`（REPLACE upsert）。**`withPartsJson` 每次从标量列（type/content/metadata/role）经 `MessagePartsConverter.toParts` 现算重填 partsJson**——M1 期 parts 是标量列的纯函数，故 metadata 回写型 update 也走 insertMessageWithParts 防漂移。读侧 `decodePartsOrLegacy`：优先 partsJson 解码，缺失/损坏回标量列现算。

| 工具/来源 | 链路 | 落库行 |
|---|---|---|
| draw_chart | onDrawChart → QuickJS `Chart.bar/line/pie` 生成 SVG → `emitChartMessage` + feedToolOutput | `tool_chart`，content=SVG |
| render_html | onRenderHtml → `HtmlCardSanitizer.sanitize` → `emitHtmlCardMessage` + feedToolOutput | `tool_html`，content=清洗后 HTML，metadata.html_card={display,summary} |
| run_gallery_script 直出 | onRunScript 拦截 return：`{chart}`→emitChartMessage；`{html}`→sanitize→emitHtmlCardMessage；含 `ids`→登记，回合末 `settleScriptMediaIds` 补横滑卡 | 同上 + `data_media_results` |
| search_media / refine | uiActions(MediaResults) → `insertMediaResultsMessage`；一回合至多一卡（REPLACE 复用上一张卡 id） | `data_media_results`，content=assets 数组，metadata=query/totalCount/isRefinement |
| edit_image | ImageEditCapability → uiActions → `insertEditResultMessage` | `tool_image_edit`，content=explanation，metadata={imageUri,saved,suggestions} |
| ai_optimize | handleAiOptimize → `insertOptimizeCandidatesMessage`；换一组/落选同 id REPLACE | `data_optimize_candidates`，metadata=group JSON |
| browser_* | 会话定格 `emitBrowserCardMessage`（turn 结束 `settleBrowserSessionsAtTurnEnd` 兜底强制 close+定格） | `tool_browser`，content=BrowserLive 整颗 JSON（partId 归一 "p0"） |
| 纯文本/错误 | `insertAgentMessage`（性能 metadata 直挂顶层） | `text`（role=agent） |
| 工程师气泡 | `persistClaudeBubble` | `text` + metadata.claude_agent_state |

### 2.5 live 态覆写与落库后回写

- **覆写挂载点**：`displayMessages` = `_messages + _streamingMessage` → `.overlayLiveTaskState(liveTasks).overlayLiveBrowserState(liveBrowser)`；两条 live 流各经 `sample(500ms)` + 首值直通节流。
- **TaskCardOverlay**：消息内首个 TaskCard part 按 taskId 同 id 原位覆写（legacy engineerTask 字段自 part 投影，同源防漂移）；live 源 = SSE 驱动的内存 map，本身不落库。
- **BrowserLiveOverlay**：同构，按 sessionId 覆写；帧/动作流水只走 WS/HTTP UI 通道不落 Room，会话结束才落定格卡。
- **测高回写**：`persistHtmlCardDisplayMode`——合并 metadata.html_card.displayMode/measuredHeightPx，**幂等**（同值跳过写库）。
- **任务卡 SSE upsert**：ClaudeChatClient SSE 解析 → EngineerTaskReducer 五态迁移 → **仅结构性事件落库**（`isStructural = event !is AssistantText`，文本 delta 不触发 Room churn）→ `persistEngineerTask`：id=taskId REPLACE、timestamp 恒为 startedAtMs 锚定提交位置。重启恢复：Room 态与内存 live 态按 updatedAtMs 较大者合并。

### 2.6 渲染源（M4 拍平，要点）

- `toUiModel` 双读：`decodePartsOrLegacy()`（快路径 partsJson 解码，回退标量列现算）；全列表重 decode 挪出主线程（messageDecodeDispatcher）。
- ChatListFlattener：key=`"messageId:partId"`；USER 整颗单 item；claude 气泡/AGENT_IMAGE/AGENT_EDIT_RESULT 整消息 legacy 直通；流式三分流（已填充跳过防双显 / 未完成占位 / 失败瞬态）；Turn 聚合纯视觉层。详见 `CHAT_CARD_CATALOG.md` §1.3。

### 2.7 异常与降级矩阵

| 场景 | UI | 落库 | LLM 侧 |
|---|---|---|---|
| HTML 清洗 Rejected（空/超 128KB/违禁标签） | feedToolError 瞬态标错 | **不落库** | reason 作 tool result 回传，引导重生成 |
| 工具执行异常/超时 | 同上 | 不落库 | `"Error: ..."` observation 回传 |
| 网络失败/120s 超时 | 清流式占位 + 错误气泡 | 落 `text` 错误消息（modelUsed="error"；guest 403 特判弹注册引导） | — |
| 用户停止（stopGeneration） | pacing 追平 | **有真实内容则半截落库**，不产错误气泡 | 取消级联断流 |
| SSE 断连（工程师模式） | 任务卡 FAILED | RUNNING 中卡裁决 FAILED 落库；其余态不二次裁决 | — |
| browser WS 断开 | 保留最后一帧 | 仅记日志，不标失败 | — |
| 意图路由超时/低置信 | 无感知 | — | 降级 OPEN_QA 走完整 loop |

### 2.8 线格式实例（SSE 帧 ↔ 合并响应 ↔ 三轮 ReAct walkthrough，2026-10-17 增补）

> **口径**：本节为**协议还原实例**——依据 OpenAI 兼容线格式 + 网关 `LlmProxy` 透传实现（`ProxyResult.Streaming` 逐 chunk 转发）+ `LlmRouteStreamTest` 固化帧形（`data: {...}\n\n` / usage 帧 / `[DONE]`）推断，非逐字节抓包（请求体录制仍是 §5 已知缺口）。文本内容为演示，结构与字段与实况一致。

**SSE 帧骨架**（任何一轮 LLM 流，无论切多少刀，固定只有 4 类帧 + 随机增量帧）：

```
data: {首帧: delta.role="assistant", delta.content=""}   ← 开场占位
      〔delta.content 增量 × N〕    — 切点随机无语义（推理 batch + flush 时机），消费端只做拼接
      〔delta.tool_calls 增量 × M〕 — 骨架帧（id/name + 空 arguments）→ arguments 残片按 index 拼装，拼齐前是非法 JSON
data: {delta:{}, finish_reason="tool_calls"|"stop"}      ← 唯一动作开关
data: {choices: [], usage: {...}}                        ← 记账帧（stream_options.include_usage 恒开）
data: [DONE]                                             ← 字面量哨兵
```

**流式 ≡ 非流式，信息量完全等价**：合并后一轮 = 一条 assistant 消息（content + 可选 tool_calls）+ finish_reason + usage。流式只是把该对象按生成顺序切片、贴逐帧重复信封（`id`/`object`/`created`/`model`/`index` 恒等冗余，文本增量帧有效负载率可低至个位数百分比）。

**三轮 ReAct 完整实例**（用户输入「帮我看看去年夏天海边的照片，顺便画个每月统计图」；每轮请求的 model/stream/temperature=0.7/max_tokens=4096/tools[42] 相同，以下省略，只列 messages 增量）：

```
第 1 轮请求 messages = [
  system(base +【关于用户】+【会话摘要】+ 日期 + RenderEnvironment + 回复语言),
  user("帮我看看去年夏天海边的照片，顺便画个每月统计图")
]
第 1 轮响应（合并后）= {
  choices: [{
    message: {
      role: "assistant",
      content: "找到了！去年夏天海边共 87 张。",
      tool_calls: [{ id: "call_7f2a", type: "function",
        function: { name: "search_media", arguments: "{\"query\":\"去年夏天的海边\"}" } }]
    },
    finish_reason: "tool_calls"
  }],
  usage: { prompt_tokens: 3210, completion_tokens: 64, total_tokens: 3274 }
}
→ App：执行 search_media → data_media_results 卡落库 → observation "找到 87 张去年夏天的海边照片" 回喂

第 2 轮请求 messages = 第 1 轮全量 + [
  assistant(content="找到了！去年夏天海边共 87 张。", tool_calls=[call_7f2a/search_media]),   // 调用声明
  tool(tool_call_id="call_7f2a", content="找到 87 张去年夏天的海边照片")                        // 执行结果，id 严格配对
]
第 2 轮响应（合并后）= {
  choices: [{
    message: { role: "assistant", content: "各月分布如下：",
      tool_calls: [{ id: "call_91d4", type: "function",
        function: { name: "draw_chart",
          arguments: "{\"type\":\"bar\",\"title\":\"每月新增照片\",\"labels\":\"6月,7月,8月\",\"values\":\"128,96,214\",\"unit\":\"张\"}" } }]
    },
    finish_reason: "tool_calls"
  }],
  usage: { prompt_tokens: 3560, completion_tokens: 92, total_tokens: 3652 }
}
→ App：draw_chart → QuickJS Chart.bar 生成 SVG → tool_chart 卡落库 → summary observation 回喂

第 3 轮请求 messages = 第 2 轮全量 + [
  assistant(content="各月分布如下：", tool_calls=[call_91d4/draw_chart]),
  tool(tool_call_id="call_91d4", content="已生成柱状图：每月新增照片（6月:128, 7月:96, 8月:214 张）")
]
第 3 轮响应（合并后）= {
  choices: [{ message: { role: "assistant", content: "点击卡片可横滑浏览全部照片。" },
              finish_reason: "stop" }],                                  // 纯文本 = 终止
  usage: { prompt_tokens: 3720, completion_tokens: 18, total_tokens: 3738 }
}
→ App：onTextMessage → nodeFinish，收尾文本落库，回合结束
```

**三条协议规律**：

1. **请求是只增不减的累加器**：第 N 轮请求 = 第 N-1 轮全量 + 上轮 assistant 消息 + tool 结果消息；messages 数组即 ReAct 账本（短期轨 8000 token 预算/老化/摘要负责兜底体积，§3.2）。
2. **Call↔Result 配对铁律**：回灌时 `assistant.tool_calls[i].id` 与下一条 `tool.tool_call_id` 必须严格一致——OpenAI 硬校验，孤儿 tool 消息或缺结果均 400（`toModelInput` 拆对、`sanitizeToolPairing` 双向剔除守此律）。
3. **finish_reason 只有两种走向**：`tool_calls` = 执行工具再来一轮；`stop` = 纯文本终止。每轮请求/响应骨架一模一样，区别全在 messages 的累加上。

---

## 3. 历史与上下文：主从三级（本链路最易误读处）

2026-10-11（分层记忆 spec M4）起，聊天历史从「并列双轨」改为**主从制**：Room = 长期轨 SSOT，Koog ChatMemory = 短期派生缓存，工程师隧道为第三条只读支路。

### 3.1 长期轨（SSOT）= Room chat_messages

Room `chat_messages`（partsJson 富归档）是聊天历史的唯一事实源：全量、不裁剪、含卡片 parts 全字段。屏幕上用户看到的历史就是这一轨。`ChatMessage.toModelInput()`（shared domain/chat/ChatModelInput.kt）是其唯一的回灌显式转换（UIMessage→ModelMessage，ADR-016 D1）：Text→TextMessage；Chart/HtmlCard/TaskCard/BrowserLive→ToolCall+ToolResult 对（toolCallId=`"messageId:partId"` 命名空间锚）；Image→占位 `[user sent an image]`（PRIVACY）；EditResult→只回灌 description；MediaResults/OptimizeCandidates **丢弃**（data part 不进上下文，对齐 Vercel 丢弃规则）。

### 3.2 短期轨（派生缓存）= Koog ChatMemory

主聊天流的 LLM 上下文来自 Koog `ChatMemory.Feature` + `KoogSessionHistoryProvider`（sessionId 即 store key，不设 windowSize 避免拆散 tool 块），Android 侧持久化在 DataStore（`KoogMessageMemoryStore`，key=`koog_memory_<sessionId>`）。**它是可丢弃、可重建的派生缓存**——长期轨在，短期轨随时能重建。

组装管线 `KoogMessageMemory.assembleForPersistence`（四步）：

1. **剔 System**（system prompt 不落盘，每轮现拼）；
2. **老化旧 tool result**：最近 2 个实轮外、>120 字符的 Tool.Result 替换为 `[tool:名 → result omitted: 前80字符]`；
3. **token 预算裁剪**：默认 **8000 tokens**（CJK 1 token/字、其余 4 字符/token 估算）；丢弃优先级 非保护tool块→保护tool块→非保护对话→保护对话；最近 3 实轮保护，最新块永不丢；
4. **条数硬上限**：MAX_MESSAGES=**10 条**，tool_call 块原子裁剪。

三不变式：System 不落盘 / tool_call 块原子 / Call↔Result 双向配对剔除（`sanitizeToolPairing`）。**滚动压缩**：`SessionCompactor.maybeCompact` 超预算触发，摘要叠加式注入 system 段（§1.3）。

**冷启动重建（M4）**：备份恢复/换机/缓存被清后，短期轨空而长期轨仍有历史——`ChatViewModel.sendMessage` 前置调 `AgentOrchestrator.rebuildChatMemoryIfEmpty`，经 shared `KoogSessionMemoryRebuilder` + `KoogMemoryProjection`（`List<ModelInputItem>.toKoogMemoryMessages()`）把 Room 最近 50 条投影进短期轨；短期轨非空幂等跳过，重建失败仅记日志不阻断对话。卡片 part 已是 Call/Result 语义对，投影产物天然满足配对不变式；空 argsSummary 落 `"{}"`，isError 追加 ` (failed)` 文本标记。

**删除传播（M4）**：`deleteSession`/`clearChat` 删除 Room 长期轨后同步 `AgentOrchestrator.clearChatMemory(sessionId)`（历史+摘要全清），杜绝「会话已删、短期轨残留」泄漏。

### 3.3 工程师隧道（只读支路）

同一 `toModelInput` 转换器经 `toHistoryPair()` 进 `GET_CHAT_HISTORY` 载荷（工程师隧道 AppToolExecutor.collectChatHistory）——与主链路重建共用转换器，各走各的组装出口。

> ⚠️ **文档口径澄清（2026-10-11 M4 更新）**：`CHAT_CARD_CATALOG.md` §0.3 及各卡「回灌」小节描述的是 Room→模型的投影语义，其消费者现为**两处**：工程师隧道 + 主链路冷启动重建。稳态运行时主链路上下文仍以 Koog 短期轨为准（预算/老化语义与长期轨全量投影不同）。`recall_chat_history` 主动召回工具（按需拉取长期轨任意段）明确未做，留后续。

### 3.4 直执回合的记忆补写

意图路由直执（不走 LLM）成功后，user 原文 + observation **补写 Koog 会话记忆**——否则下一轮「再看第 3 张」式指代会断裂。

---

## 4. 设计不变式清单（改动本链路须逐条核对）

1. **媒体红线**：图片字节永不上传，URI 仅以文本形态进 input；user 图回灌为英文中性占位（ADR-008）。
2. **零 JS 桥**：HTML 卡无任何卡内→原生指令通道；清洗器 128KB 上限 + 远程 script/iframe/form 剔除。
3. **observation 与用户文案分离**：模型侧 observation 是硬编码中文，用户可见文案一律走平台层本地化（I18N）。
4. **流式不落 Room**：流式消息纯内存态；落库只发生在产物 emit*、回合结束、用户停止半截落库三个时点。
5. **parts 双写同源**：partsJson 恒为标量列的现算纯函数（M1 期），metadata 回写必须走 insertMessageWithParts 防漂移。
6. **迁移不丢消息**：Room 迁移行级 runCatching + Text 兜底。
7. **一回合至多一张媒体卡**：search/refine REPLACE 复用本回合上一张卡 id。
8. **tool schema 单一来源**：ToolRegistry 与 system prompt 工具清单同自 ChatToolService 反射展开，禁手写漂移。

---

## 5. 已知缺口（勘察实录）

| 缺口 | 现状 | 影响 |
|---|---|---|
| 请求体逐字 JSON 无录制 | `LlmCallRecord.requestJson=""`（Koog EventHandler 不暴露原始请求；DEBUG 需 Ktor Logging） | 协议实证靠推断（§2.8 为还原实例非抓包）；排查线格式问题需抓包 |
| ~~主聊天流 tools schema 无意图级裁剪~~ ✅ 已落地（2026-10-17，M3） | 契约 allowedTools 转执行：路由可靠单意图 → 子集 registry + prompt 同源过滤（§1.4）；OPEN_QA/降级/secondary/子集空交集或缺失过半 保守全量 | — |
| prompt 前缀缓存未显式利用 | ✅ 可观测性已补（2026-10-17）：server 网关解析 `prompt_tokens_details.cached_tokens` 落 `llm_call_log.cached_tokens`（migration 013）+ 管理后台用户详情 Cached 列（依赖 TokenHub/CF 网关原样透传该嵌套对象；DeepSeek 旧版平铺字段 `prompt_cache_hit_tokens` 不采集）；**App 侧仍拿不到**（Koog 1.3.0 `createMetaInfo` 丢弃 cachedTokens，实证字节码 mask=48）；缓存**主动利用**（前缀稳定化之外的命中提升）未做 | 命中率可量化后再评估前缀重排收益；BYOK 直连不经网关无此观测 |
| tool schema 转换在 Koog 内部 | `AbstractOpenAILLMClient` 序列化，仓库外 | schema 问题升级依赖 Koog 版本 |
| Room 回灌在主链路的消费面 | toModelInput 已服务工程师隧道 + M4 冷启动重建（§3.2）；主动召回工具（recall_chat_history）未做 | 长会话中远期历史仍只能靠短期轨摘要触及，无法按需召回任意段 |
| iOS 链路 | `ChatAgentBridge`（shared iosMain）消费同一 ChatStreamEvent，Swift ChatViewModel 对称实现 | 本文以 Android 主链路为准，iOS 差异走 parity 台账 |

---

## 6. 文档索引

| 主题 | 文档 |
|---|---|
| 消息模型/渲染宪法 | ADR-016（`docs/02-ARCHITECTURE/ADR/`） |
| 意图路由契约 | ADR-015 + spec `2026-09-25-intent-routing-contract-design.md` |
| 逐卡协议实例（入参/落库/parts） | `CHAT_CARD_CATALOG.md` |
| 图表/HTML 卡/脚本实现 | `JS_ENGINE_TECH_SPEC.md` §5/§7/§7.1 |
| 远程接入坑位（Koog） | `shared/AGENTS.md` §2 |
| 服务端网关 | `server/AGENTS.md` + `SERVER_IMPLEMENTATION_PLAN.md` |
| 流式/parts 实施分期 | spec `docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md` |
