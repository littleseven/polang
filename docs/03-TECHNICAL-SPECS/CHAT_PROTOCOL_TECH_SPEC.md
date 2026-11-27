# Chat 请求/回复协议技术规格（Chat Protocol Tech Spec）

> **定位**：主聊天流「用户输入 → 远程 LLM → 流式响应 → 渲染/落库 → 历史回灌」全链路协议的**系统描述 SSOT**。ADR-016 管「为什么这么设计」（决策宪法），本文管「现在是怎么跑的」（链路实况）；卡片级协议实例（每张卡的 tool_call 入参 / Room 落库 JSON / parts 形态）见 `CHAT_CARD_CATALOG.md`，本文只到链路级，不重复逐卡登记。
> **日期**：2026-10-11 建立（基于 main 分支实况勘察，Koog 1.3.0，Room v26）
> **增补**：2026-10-17 §2.8 线格式实例（SSE 帧骨架 ↔ 合并响应 ↔ 三轮 ReAct walkthrough）；M3 意图级工具裁剪落地（§1.4）+ server 侧 cached_tokens 可观测（§5 缺口两条核销/降级）；2026-11-08 值域终态批次收编（parts-first 落库 §2.4 / 回灌剥离面 §3.1 / 不变式 5-7 重写，Room v28）；2026-11-12 老三样缺口清偿（§1.4 +recall_chat_history、§3.3 主动召回消费面、§5 requestJson/cached_tokens 端侧可观测落地）。
> **上游**：ADR-016（parts 模型/渲染架构）、ADR-015（意图路由契约）、ADR-008（隐私红线）
> **维护规则**：链路改造（请求组装/流式事件/落库路径/历史管线任一变动）须同一原子提交内同步本文；行号引用会漂移，以文件级锚点为准。

---

## 0. 一张图看全链路

```
用户输入 (ChatViewModel.sendMessage)
  │
  ├─ 0. 落库用户消息（Room，insertPartsMessage）
  ├─ 1. 意图路由 IntentRouter（ADR-015 前置闸门；直执路径已随 2026-11-08 横滑图集卡退役删除）
  │      └─ 一切意图 → 完整 agent loop（VIEW_PHOTOS/REFINE 重指 RICH_HTML_CARD+render_html）
  │
  ▼
Koog Agent Loop（poLangSingleRunStrategy 配置化工厂，ReAct 图循环）
  │  每轮：callLLM ──SSE 流式──▶ 文本快照 + tool_calls
  │            │                      │
  │            │                      ▼
  │            │         nodeExecuteTools（ToolRegistry 反射派发
  │            │          → ChatToolService @Tool → CapabilityRegistry.dispatch）
  │            │                      │
  │            │                      ├─ uiActions → UI 产卡落库（emit* 系列）
  │            │                      └─ observation 文本 → nodeSendToolResult 回喂
  │            ▼
  │  纯文本响应 → nodeFinish（终止）；终结回复 2026-11-24 起 turn-native 化：
  │  程序形态（OpenUI 卡）或回滚闸关时纯文本气泡——见 §2.10
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

云端回合（并行链路，2026-10 起）：CloudAgentClient → picme-server /agent/**（服务端 Koog，ADR-017/018）
  GenUI 卡：2026-11-06 起唯一线 = OpenUI 回合原生（turn-native；A2UI render_ui 线已退役）——
  无发卡工具，终结 Message 事件 content 即 OpenUI 程序 → 端侧嗅探落库 data_genui_program（详见 §2.9；
  v5 备份中的存量 data_ui 历史卡恢复时落 Tombstone 占位，运行时零存量行）
  OpenUI 流式预览经 SSE kind=openui_delta 瞬态帧旁路（不落库）——详见 §2.9
  本地回合 2026-11-24 起 turn-native 同口径（策略切换/流式预览/落库见 §2.10，回滚闸可退回文本气泡）
```

---

## 1. 请求侧协议

### 1.1 入口调用链

| 跳 | 位置 | 语义 |
|---|---|---|
| 1 | `ChatViewModel.sendMessage(text, imageUri)`（androidApp features/chat/ChatViewModel.kt） | DEV 捷径（`/html` `/task`）拦截；用户消息落 Room；构建 `AgentContext`（scene=CHAT、memorySessionId、recentSearchResults、lastUserImageUri、gallerySummary、traceId、persona、replyLanguage） |
| 2 | `RemoteChatEngine.streamChat(input, agentContext, onEvent)`（shared agent/core/inference/remote/RemoteChatEngine.kt） | 先走意图路由判定意图（直执短路已随 2026-11-08 横滑图集卡退役删除，恒进 agent loop；M3 工具子集随路由结果下传） |
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

策略层 `ChatRoutingPolicy.decide`：**一切意图恒返 `FullAgentLoop`**（走完整 agent loop；BROWSE_WEB 契约限定 allowedTools=browser_* 七件套，forbiddenTools 排除相册工具防误路由；VIEW_PHOTOS/REFINE_RESULTS 重指 `RICH_HTML_CARD`+`render_html` 出图集卡）。~~VIEW_PHOTOS/REFINE_RESULTS 直执~~已随 2026-11-08 横滑图集卡退役删除（直执产出锚点 = 原生横滑卡，卡退役后直执失去意义，`executeDirect`/`DirectRouteReply`/RoutingGate 同步物理删除）。每次判定落 `routing_audit_log` 审计。

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

**turn-native 契约段（2026-11-24 起，本地回合 OpenUI 统一）**：`KoogChatAgent.composeSystemPrompt` 末尾（技能清单段之后恒最末）追加 `OpenUiTurnNativeContract.buildSystemPrompt(端侧身份行, toolNote="", toolModeOverride=端侧模式覆盖段)` + `openUiReplyLanguageRuleSegment`（五语规则段）——仅回滚闸开（`RemoteReActAgentConfig.openUiTurnNative`）时追加；契约段文本与技能清单段同槽进 AIAgent 重建键（flag off 时空串入键零扰动）。模式覆盖段（2026-11-26 第三变体槽）：端侧基础规则段（ChatPromptRules 收敛规则/窄化规则）与工具描述里的 render_html 图集卡指引同「响应即程序」冲突（1.0.96 真机实证模型服从收敛规则出 HTML 卡），覆盖段显式作废之——数据工具结果直接进终结程序绑句柄出 PhotoGrid，draw_chart 豁免；server 缺省空槽零扰动。意图子集裁剪路径（toolSetOverride）的子集 prompt 缓存不含契约段——契约段在 KoogChatAgent 层恒追加，子集缓存键不受污染。

**params 烘焙**：`polangSystemPrompt(...)`（koog/KoogPromptFactory.kt）——🔴 必须走 Koog prompt DSL 传 params，否则 temperature/maxTokens/thinking 丢失。AIAgent 按「记忆快照内容 + 摘要文本」新鲜度懒重建（2026-11-27 起比摘要文本本体而非 version，防跨会话同版本号串摘要）。

### 1.4 工具集（ChatToolService，41 个 @Tool；2026-11-12 起 40→41：+`recall_chat_history`（Room 长期轨主动召回）；2026-11-08 曾 44→40：`switch_view_mode` 移出 chat 工具面 + 反馈三件套（record_feedback/more_like_this/exclude_constraint）随横滑图集卡退役删除）

全部 `suspend`、参数无 Kotlin 默认值（Koog 反射/R8 稳健性）；每个都是 `dispatchCommand(AgentCommand, scene=CHAT)` 薄封装，超时 15s（browser_* 族 25s、web_search 15s；2026-11-08 由 5s 上调——直执链路搜图 5~12s 常态超 5s 误回落 agent loop 引发空结果打转）。

**意图级工具子集（M3 分支化落地，2026-10-17，成本治理）**：契约表 `ChatIntentContract.allowedTools` 从声明转为执行——路由判定为单一意图且路径可靠（LLM_ROUTER / PATTERN_SHORTCUT / BROWSER_SHORTCUT）时，`ChatRoutingPolicy.toolSubsetFor` 产出「契约白名单 + 通用工具（finish/delay）」子集，经 `RouteDecision.FullAgentLoop.toolNames` 传到引擎层；`RemoteChatEngine.resolveToolSetOverride` 自同一份 `chatTools`（组合根新增注入，与 registry/descriptors 同源）过滤派生子集 registry + 子集 prompt 清单段（不变式 8 不破坏），以 `KoogChatAgent.ChatToolSetOverride` 按轮覆写——AIAgent 重建键加 toolSetKey（与记忆快照/摘要文本同级），**KoogChatAgent 单实例单 executor 不为子集多建**。**保守全量（null）场景**：OPEN_QA / 一切降级路径 / secondary 双产出物 / 平台未注入 chatTools / 子集过滤为空——路由器永不拦截能力的语义不变。子集 base prompt 按 key 缓存逐字节复用（保前缀稳定）。**权衡**：不同意图的相邻回合 system prompt/tools 前缀不同，会碎片化上游前缀缓存——用缓存碎片换 ~40 条 schema（约 2-4k token/轮 × ReAct 轮数）的确定削减。iOS 经 `ChatToolManifest.tools` 同路径注入（9 工具手工清单：8 相册/优化 + render_html，2026-11-08 横滑卡退役追齐）。

| 分组 | 工具 |
|---|---|
| 相册 | get_gallery_summary / search_media / refine_media_search / delete_media / share_media / select_media / favorite_media（2026-11-08：`switch_view_mode` 移出 chat 工具面——CHAT 场景无执行体，dispatch 必落跨页队列回「正在切换页面」但页面不跳转；`share_media` 反向补全 Android 执行体入 `ChatMediaWriteCapability`，对齐 iOS 既有通路；2026-11-24 起 search/refine/get_gallery_summary 增 `mediaType` 维度 + 回执附 `handle: scan/<id>` 句柄注册 LocalHandleStore，见 §2.10） |
| 打标修图 | query_tag_scan_status / ai_optimize / adjust_image / edit_image（2026-11-08：`start_tag_scan` 更名 `query_tag_scan_status`——旧名说谎：实现硬编码 action="query" 永不启动扫描；命令层 `AgentCommand.StartTagScan`/`ChatStartTagScanCapability` 不动） |
| 脚本/卡片 | run_gallery_script / draw_chart / render_html（2026-11-08：run_gallery_script 描述瘦身——handler 目录/写操作清单/示例抽到 `gallery-script` skill 经 load_skill 按需加载，描述只留硬约束与指针，常驻 prompt 每轮省约 1.5k 字符） |
| 技能 | load_skill（2026-11-08：Skill 中心端云同构，见 `AgentSkillContract`） |
| 云端浏览器 | browser_open / browser_navigate / browser_click / browser_type / browser_extract / browser_screenshot / browser_close |
| 网页搜索 | web_search（2026-11-02：DeepSeek Search 经 server `/v1/web-search` 代理——服务端持 key 调 `/v1/tools/search` 并做配图 URL 提取/瘦身（≤5 结果、≤3 图/结果、400 字符摘录），端侧 `WebSearchClient`（WebSearchTransport）转发；结果 images 字段供 LLM 以 `![描述](https://图片URL)` 嵌回正文，AgentMarkdown 经 `NetworkImageTransformer`（Coil，仅 https、限高 320dp）渲染——「图文并茂」闭环；失败回 `[WEB_SEARCH_UNAVAILABLE]`/`[WEB_SEARCH_FAILED]` 结构化降级文本） |
| 记忆 | remember_person_relation / forget_person_relation / list_person_relations / remember_fact / forget_fact / recall_memory / recall_chat_history（2026-11-12：Room 长期轨主动召回——检索**当前会话** `type='text'` 投影行（query LIKE / 空=最近 N 条，limit 钳 1..50），返回硬编码中文 observation（只进模型不进气泡，无 UI 卡）；会话范围经 `ChatToolService.chatSessionId`（引擎每轮与 `setSessionId` 同步）→ `AgentContext.memorySessionId` 下传；MEMORY 意图白名单已收编；iOS `ChatToolManifest` 手工清单不接入，平台差异登记） |
| 设置 | change_theme / change_language / toggle_setting / download_model / switch_face_engine |
| 导航/系统 | navigate_to / go_back / launch_app / open_system_settings |
| 通用 | delay / finish |
| 技能 | load_skill（端侧 Skill 中心：经 server `/v1/skills` 同步本地缓存后按需加载技能包，扩展 Agent 专长；2026-11 端侧执行器落地） |

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

tool 结果作为 `Message.User` 内嵌 `MessagePart.Tool.Result` 追加进 prompt 进入下一轮。**终止条件**：① LLM 返回纯文本（onTextMessage → nodeFinish，文本作回合 summary）；② `maxIterations`=10（×3 换算 Koog 节点步数 30）；③ 外层 120s 超时；④ **ToolLoopGuard 循环熔断**（koog/ToolLoopGuard.kt，2026-11-08）——尾部窗口同一签名（工具名+输出采样）连续重复 3 次，或连续 6 轮全错，携哨兵直达 nodeFinish，双 agent 统一映射 `AgentLoopStuckException` → 五语友好文案 `chat_agent_loop_stuck`，杜绝「空/错工具结果打转撞 30 步上限、Koog 英文原文上屏」事故。

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
2. 按 action 类型生成**硬编码中文模板 observation**（"找到 N 张…，媒体 id：…"——search/refine 2026-11-14 起附前 24 个媒体 id，供写工具与 render_html 非常规展示引用；2026-11-24 起三工具回执尾部另附 `（数据句柄 handle: scan/<12hex>）` 并注册 `LocalHandleStore`，供 OpenUI 程序绑定照片/视频投影，见 §2.10；2026-11-26 起 turn-native 模式看照片的展示通路收敛为句柄 + PhotoGrid 程序卡，render_html 图集卡指引作废）作 @Tool 返回值 → Koog 自动包成 tool result 回喂下一轮 ReAct。⚠️ observation 只进模型/记忆，**绝不直达用户气泡**（I18N）；失败/超时统一 `"Error: ..."` 文本回传。

### 2.4 产物落库路径（emit* 全清单）

统一入口 `chatMessageDao` 的 parts-first 三件套（2026-11-08 值域终态反转）：`insertPartsMessage`（直建 parts，返回 messageId）/ `insertPartsMessages`（备份批量）/ `replacePartsMessage`（覆写型 upsert——保 id/sessionId/role/modelUsed/timestamp，metadata = 新投影 ∪ 原行非投影残留键）。**partsJson 为权威列**（Room v28 非空），`type`/`content`/`metadata` 标量列由 `projectChatRow` 派生投影（type = `primaryPartOf` 判型锚，reasoning/source/tombstone 永不作投影源）——运行时禁直写投影列，metadata 回写型 update 走 `replacePartsMessage` 原位改 part。读侧 `decodeParts`：partsJson 直读，损坏行降级原文 Text part。

| 工具/来源 | 链路 | 落库行 |
|---|---|---|
| draw_chart | onDrawChart → QuickJS `Chart.bar/line/pie` 生成 SVG → `emitChartMessage` + feedToolOutput | `tool_chart`，content=SVG |
| render_html | onRenderHtml → `HtmlCardSanitizer.sanitize` → `emitHtmlCardMessage` + feedToolOutput | `tool_html`，content=清洗后 HTML，metadata.html_card={display,summary} |
| run_gallery_script 直出 | onRunScript 拦截 return：`{chart}`→emitChartMessage；`{html}`→sanitize→emitHtmlCardMessage | 同上 |
| search_media / refine | uiActions(TextReply) → 纯文本回执落库（2026-11-08 横滑图集卡退役：不再产 `data_media_results` 行） | `text`（role=agent），content=计数 + 前 24 个媒体 id 文案（如「找到 N 张「q」的照片，媒体 id：…」，2026-11-14 id 下发） |
| edit_image | ImageEditCapability → uiActions → `insertEditResultMessage` | `tool_image_edit`，content=explanation，metadata={imageUri,saved,suggestions} |
| ai_optimize | handleAiOptimize → `insertOptimizeCandidatesMessage`；换一组/落选同 id REPLACE | `data_optimize_candidates`，metadata=group JSON |
| browser_* | 会话定格 `emitBrowserCardMessage`（turn 结束 `settleBrowserSessionsAtTurnEnd` 兜底强制 close+定格） | `tool_browser`，content 投影 = BrowserLive 整颗 part JSON（partId 归一 "p0"） |
| 纯文本/错误（2026-11-24 起 turn-native：开关 on 时终结程序形态） | `insertAssistantFinalMessage`（程序 → OpenUiCard part；非程序/开关 off 走 `insertAgentMessage`，见 §2.10） | `data_genui_program`（OpenUiCard，reasoning+source 前置）或 `text`（role=agent） |
| 工程师气泡 | `persistClaudeBubble` | `text` + metadata.claude_agent_state |

### 2.5 live 态覆写与落库后回写

- **覆写挂载点**：`displayMessages` = `_messages + _streamingMessage` → `.overlayLiveTaskState(liveTasks).overlayLiveBrowserState(liveBrowser)`；两条 live 流各经 `sample(500ms)` + 首值直通节流。
- **TaskCardOverlay**：消息内首个 TaskCard part 按 taskId 同 id 原位覆写（legacy engineerTask 字段自 part 投影，同源防漂移）；live 源 = SSE 驱动的内存 map，本身不落库。
- **BrowserLiveOverlay**：同构，按 sessionId 覆写；帧/动作流水只走 WS/HTTP UI 通道不落 Room，会话结束才落定格卡。
- **测高回写**：`persistHtmlCardDisplayMode`——合并 metadata.html_card.displayMode/measuredHeightPx，**幂等**（同值跳过写库）。
- **任务卡 SSE upsert**：ClaudeChatClient SSE 解析 → EngineerTaskReducer 五态迁移 → **仅结构性事件落库**（`isStructural = event !is AssistantText`，文本 delta 不触发 Room churn）→ `persistEngineerTask`：id=taskId REPLACE、timestamp 恒为 startedAtMs 锚定提交位置。重启恢复：Room 态与内存 live 态按 updatedAtMs 较大者合并。

### 2.6 渲染源（M4 拍平，要点）

- `toUiModel` 单读：`decodeParts()`（partsJson 直读，损坏行降级原文 Text）；全列表 decode 挪出主线程（messageDecodeDispatcher）。
- ChatListFlattener：key=`"messageId:partId"`；USER 整颗单 item；claude 气泡 / agent 结果图（IMAGE + role 守卫）/ EDIT_RESULT 整消息直通；流式三分流（已填充跳过防双显 / 未完成占位 / 失败瞬态）；Turn 聚合纯视觉层。详见 `CHAT_CARD_CATALOG.md` §1.3。

### 2.7 异常与降级矩阵

| 场景 | UI | 落库 | LLM 侧 |
|---|---|---|---|
| HTML 清洗 Rejected（空/超 128KB/违禁标签） | feedToolError 瞬态标错 | **不落库** | reason 作 tool result 回传，引导重生成 |
| 工具执行异常/超时 | 同上 | 不落库 | `"Error: ..."` observation 回传 |
| 网络失败/120s 超时 | 清流式占位 + 错误气泡 | 落 `text` 错误消息（modelUsed="error"；guest 403 特判弹注册引导） | — |
| 用户停止（stopGeneration） | pacing 追平 | 文本半截**有真实内容则落库**；流式 OpenUI 预览非空时半截程序**丢弃不落库**（瞬态预览从不落库，§2.10） | 取消级联断流 |
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
→ App：执行 search_media → 计数回执 "找到 87 张去年夏天的海边照片" 落库并回喂 observation

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
  choices: [{ message: { role: "assistant", content: "这 87 张已为你找齐，想看分布或导出随时说。" },
              finish_reason: "stop" }],                                  // 纯文本 = 终止
  usage: { prompt_tokens: 3720, completion_tokens: 18, total_tokens: 3738 }
}
→ App：onTextMessage → nodeFinish，收尾文本落库，回合结束
```

**三条协议规律**：

1. **请求是只增不减的累加器**：第 N 轮请求 = 第 N-1 轮全量 + 上轮 assistant 消息 + tool 结果消息；messages 数组即 ReAct 账本（短期轨 8000 token 预算/老化/摘要负责兜底体积，§3.2）。
2. **Call↔Result 配对铁律**：回灌时 `assistant.tool_calls[i].id` 与下一条 `tool.tool_call_id` 必须严格一致——OpenAI 硬校验，孤儿 tool 消息或缺结果均 400（`toModelInput` 拆对、`sanitizeToolPairing` 双向剔除守此律）。
3. **finish_reason 只有两种走向**：`tool_calls` = 执行工具再来一轮；`stop` = 纯文本终止。每轮请求/响应骨架一模一样，区别全在 messages 的累加上。

### 2.9 云端 Agent 回合与 GenUI 卡通道（2026-10-27 增补）

> 前述 §1~§2.8 是**端侧 Koog 回合**（经 `/llm` 网关）。2026-10 起主聊天页存在第二条并行链路——**云端 Agent 回合**（ADR-017/018，服务端 Koog + 事件溯源）。GenUI 卡（OpenUI；A2UI 线 2026-11-06 退役）原只从该链路产出，**2026-11-24 起本地回合也产出（turn-native 统一，见 §2.10）**。本节只登记主聊天流口径；卡片级三要素归 `CHAT_CARD_CATALOG.md`，服务端实现归 `server/AGENTS.md`，端侧渲染宿主归 androidApp chat 模块 AGENTS.md §2.2。

- **链路**：`CloudAgentClient`（androidApp `data/remote/picme/`）→ picme-server `/agent/**` REST + SSE；端侧工具经 SSE `tool_request` 帧下行 + `POST /agent/sessions/{id}/tool-results` 上行回传（工具桥双通道）；端侧结果出端统一过 shared `CloudToolProjectionGate` 投影闸（fail-closed，[PRIVACY]）。
- **AG-UI v2 端点（2026-11-28，M1，spec《2026-11-28-ag-ui-migration-design》）**：`POST /agent/v2/sessions/{id}/messages` 与 v1 同一 runTurn 实现（鉴权/并发闸/配额/目标解析单份），仅出站帧汇不同——`TurnFrameSink` 双实现：v1 `LegacyKindFrameSink`（kind 帧逐字节保留，线上兼容兜底，下线走占比观测三步）/ v2 `AgUiFrameSink`（AG-UI 事件流）。映射要点：RUN_STARTED 回合边界（threadId=sessionId、runId 每回合生成，v1 无对应物）；ACTION→TOOL_CALL_START/ARGS/END（`act-{eventId}` 配对锚，ARGS 单 chunk 全量）、OBSERVATION→TOOL_CALL_RESULT；流式 program 前缀快照**差分**为 TEXT_MESSAGE_* 真 delta（非扩展=轮边界，复刻 `ChatStreamTurnAdapter` 启发式），终结 Message 与流式块去重（累计==content 仅 END，ensureValidProgram 改写则 `evt-{id}` 补完整三段式）；设备工具桥→CUSTOM(device_tool_request)（上行 tool-results 端点不变）；done 帧数据全量进 RUN_FINISHED metadata（elapsed_ms/usage/openui_lang_version）；CONDENSATION/HTML_CARD/FINISH 不下行。解码侧 = shared commonMain `AgUiSseParser` + `AgUiTurnAdapter`（`domain/chat/streaming/`，出口复用既有 TurnStreamEvent，渲染层零改动）。

- **Android 主链路切 v2（2026-11-28，M2）**：`CloudAgentTurnRunner.runTurn` 新增 `agUiEnabled` 参数（DataStore 回滚闸 `cloud_ag_ui_enabled`，默认 true；Chat 主页 `runCloudTurnOnce` 与调试页 `CloudAgentChatViewModel.send` 同一闸现读）。true 且 `CloudAgentApi.agUiEndpointSupported` 时优先 `postMessageV2`（`/agent/v2/...`，shared `AgUiSseParser` 解码）；**v2 路由缺失**（HTTP 404/406 且响应体无服务端 `{"error":...}` 业务体——新服务端 session_not_found 等带 error 体，不误判）自动回落 v1 同会话重发（路由未命中无副作用，重发安全）并置进程内粘性标记免每回合双发探测。v2 帧经回合级 `AgUiCloudTurnTranslator`（CloudAgentTurnRunner.kt）译为既有 `CloudTurnEvent`（ChatViewModel/调试页消费方零改动）：TEXT_MESSAGE_* 聚合整条后同一 `looksLikeProgram` 嗅探（程序→OpenUiCard/否则 AssistantText），多 chunk 流式块经 `looksLikeProgramPrefix` 门判出 OpenUiCardDelta（单 chunk=非流式整条不出预览，对齐 v1 openui_delta 仅程序流语义），TOOL_CALL_START/RESULT → StepStarted/StepFinished（`act-{eventId}` 解析回事件 id，`act-0` 孤儿帧跳过），TOOL_CALL_END 全量 args 解析 render_ui_openui → OpenUiCard，CUSTOM(device_tool_request) → 设备工具桥（上行 tool-results 不变），RUN_FINISHED metadata → Done，RUN_ERROR → Error。

- **iOS 切 v2（2026-11-28，M3）**：无线上存量，直接只实现 v2——`CloudAgentClient.postMessage` 打 `/agent/v2/sessions/{id}/messages`，帧解析 = shared `AgUiSseParser`（SharedKit 导出，单一解码源）；v1 kind 帧模型（`CloudAgentFrame`/`CloudAgentSseParser`）物理删除。翻译层 = `AgUiCloudTurnTranslator`（CloudTurnRunner.swift，Android 同名翻译器的 iOS 子集：无步骤时间线，TOOL_CALL_RESULT 不上屏；CUSTOM value / RUN_FINISHED metadata 经 `JsonObject.toString()` → JSONSerialization 桥接，纯逻辑静态桥接函数可单测）。消费侧：`ChatViewModel.handleCloudFrame` 与调试页 `CloudAgentChatStore`（回合级翻译器实例，send 时重建）同一事件面 `CloudTurnEvent` 零改动。测试 = `CloudTurnRunnerTests` 全管线契约（SSE 文本 → shared parser → 翻译器，14 例对位 Android `CloudAgentTurnRunnerAgUiTest` 子集）。
- **界面语言协商（2026-11-22 OpenUI 多语言批次）**：`POST /agent/sessions/{id}/messages` 增 `X-App-Language` 头（值 = shared `ReplyLanguage.name`，双端发送点：Android `ChatViewModel.runCloudTurn`/调试页 `CloudAgentChatViewModel.send`、iOS `ChatViewModel`/`CloudAgentChatStore`，解析规则与 chat `toReplyLanguage` 同源）；服务端经 shared `replyLanguageFromName` 解析（缺失/非法回落简中，旧端零感知），system prompt 尾部追加 `openUiReplyLanguageRuleSegment`（程序内一切用户可见文案——Markdown 正文/标题/标签/按钮/Callout/summary 行——跟随界面语言；DSL 词汇与 handle id 不翻译），`ensureValidProgram` 终结兜底包装 summary 标签与硬降级错误卡同步五语化（`openUi*Fallback*` 文案 SSOT 在 shared `ReplyLanguage.kt`）。
- **GenUI 唯一化（2026-11-06，turn-native only，server 0.9.20）**：/agent 路由面**不再按 `X-GenUI-Format` 分流**（旧头被忽略，A2UI 回退闸废弃），恒走 **OpenUI 回合原生（turn-native，spec 已随交付清理，git 历史可查）**——不注册任何 GenUI 发卡工具（render_ui/render_ui_openui/render_html 全退役，HTML 以 Html 组件形态入程序树），系统提示词恒为 `OpenUiTurnNative.SYSTEM_PROMPT`（响应即程序契约：整个终结回复必须是一颗 OpenUI Lang 1.0 程序，纯文本也写 `root = Markdown("…")`；契约段后紧跟「回复形态先定位」（2026-11-01 回复活感批次）——对话/问答/闲聊类回复为裸 Markdown 自然正文、禁表单卡包装，数据卡 Card 首子级 Markdown 导语不做冷报表（对话黄金示例 `GOLDEN_CHAT_PROGRAM` 测试同源钉住）；DSL 规则与 `OpenUiPromptAssets.DSL_RULES` 同源复用，尾部内嵌组件全量清单——引用 `RenderOpenUiValidator.ALLOWED_COMPONENTS` 生成而非手抄）。A2UI 时代的 `render_ui` 发射点门控（`RenderUiValidator`，挂 Koog onToolCallStarting）、假发卡守卫与 actionGate/argsTransformer 接线已随发卡工具一并退役，退役类六件（RenderUiToolService/RenderUiValidator/RenderOpenUiToolService/CardClaimGuard/OpenUiToolCallStreamTracker/OpenUiProgramStreamExtractor）2026-11-06 已物理删除。
- **卡下发与落库**：A2UI 通道已退役（2026-11-06 唯一化；运行时零存量 data_ui 行——Room v28 重置，v5 备份恢复落 Tombstone）；**turn-native 经终结 Message 事件下行**——assistant content 即整颗程序，端侧 `CloudAgentTurnRunner` message 分支经 `OpenUiProgramSummary.looksLikeProgram` 嗅探（root 行首/Card 调用形态；**纯内容嗅探**——LLM 可能模仿会话历史里的程序格式把程序当纯文本输出，兜底落卡，2026-11-02 真机事故修复）→ 程序形态落 `data_genui_program`（OpenUiCard part 直建落库，content 投影 = 整颗 part JSON；fallbackSummary 经 `extractFallbackSummary` 提取：`// summary:` 首行注释 > 首个 Markdown/Text 字面量，截 80 字），非程序形态按纯文本 AssistantText 降级；data part 不回灌 LLM。**服务端「端上永远收到合法树」双保险**：① 策略层 `ProgramGuard`（每轮新实例，MAX_CORRECTIONS=1，GenUI 唯一化后无条件启用）——终结文本经 `RenderOpenUiValidator.validateProgramToError`（结构校验 + `OpenUiLayoutValidator` 布局门禁，先 normalize 再 validate；错误文案 turn-native 口径「逐条修正后重新输出完整程序」）判定非法 → `cloudAgentStrategy` `correctInvalidProgram` 节点携带错误清单回环重发（出边顺序 = 程序校验 → finish，假发卡节点已随发卡工具退役摘除）；② 纠正上限用尽放行，由终结落流前内联调用的 `OpenUiTurnNative.ensureValidProgram` 兜底——合法 normalize 后原样放行 / 非法包成 `root = Markdown("…")` 程序（包装自身复验失败再退硬编码错误卡）。
- **OpenUI 流式预览（turn-native 形态，2026-11-01）**：响应即程序后预览跟踪对象从「工具 args JSON 增量」换成「content TextDelta 纯文本累计」——server `AgentRunner.runTurn` content delta 无条件透传（GenUI 唯一化收编，forwardContentDeltas 参数已删），`OpenUiContentStreamTracker` 节流（≥48 字符增量且 ≥80ms，首发免时间窗）后经 SSE `kind=openui_delta` **瞬态帧旁路下行**（不落 agent_event，终结 Message 帧唯一权威；旧端忽略未知 kind 后向兼容）；EventHandler `onLLMStreamingStarting` 调 `onRoundStart` 轮次重置——回合内多轮 LLM 输出（工具调用后的中间轮 → 终结整树）各自从空累计，端上覆盖式替换预览。端侧照旧合成 `OPENUI_STREAM_PREVIEW_ID` 瞬态预览消息挂列表尾，`OpenUiStreamingCard` 每帧前缀新建 session 解析渐进渲染；终结 Message 落库后预览让位。（2026-10-26 P2 的 ToolCallDelta 路径 `OpenUiToolCallStreamTracker` + `OpenUiProgramStreamExtractor` 已随 render_ui_openui 退役物理删除，2026-11-06，server 0.9.20。）
- **图集展示（2026-11-08 横滑卡退役修订）**：原 `CloudAgentGalleryTools.mediaResultsListener` 端侧透出媒体卡 + `display="true"` 意图门控机制已整体退役删除——云端回合 search_media 命中现回执为 TextReply 纯计数文本（媒体不出设备，回传仅文本计数）；图集展示一律由 LLM 走技能出卡（photo-browse 等，`render_html`/OpenUI 程序），不再有原生横滑图集卡通道。
- **done 帧回合性能数据（2026-11-01，server 0.9.15 起）**：done 帧新增 `elapsed_ms` + `usage{prompt_tokens, completion_tokens}`——server `AgentRunner.runTurn` 逐次累计（`TurnUsage` 出参，回合级实例互不串扰），成功收尾回填耗时；端侧 `CloudFrame.Done` 解析 → `ChatViewModel.toLlmPerformanceOrNull` 装配 `LlmPerformance`（映射同 DeepSeek 先例：prefill=0 隐藏、decode=回合端到端耗时）→ 经 metadata 落库（`performanceMetadataJson`，格式与本地路径同源），文本气泡（`ChatAgentTextPart` 既有行）与 OpenUI 卡（TYPE_OPENUI_CARD 分支新增行）下方统一渲染性能指标行。回补背景：性能行原属本地 Koog 路径（`streamResult.metrics`），云端链路（含 turn-native 程序卡）此前无数据源不渲染。旧服务端（无 usage 字段）→ tokens 双 0 → 不装配不渲染（对齐 parsePerformanceMetadata 全 0 护栏）；旧端忽略 done 帧未知字段，双向兼容。**0.9.17 修复（2026-11-03）**：usage 数据源从 `onLLMCallCompleted` 改为流式 End 帧——Koog 1.3.0 流式路径只触发 `onLLMStreaming*` 事件、**不触发** `onLLMCallCompleted`（javap 核实后者唯一调用点是 `ContextualPromptExecutor` 包非流式 `execute`），策略层 LLM 节点全走 `requestLLMStreaming()` 导致 TurnUsage 恒 0、性能行整体缺失；现于 `onLLMStreamingFrameReceived` 的 `StreamFrame.End` 分支累加 `metaInfo`（OpenAI 客户端请求自带 `stream_options.include_usage`、从上游 usage chunk 构建 End metaInfo），非流式回退路径仍由 `onLLMCallCompleted` 覆盖，两路互斥无双计。

### 2.10 本地回合 turn-native（OpenUI 统一，2026-11-24 起）

> spec《2026-11-24-local-turn-openui-unification-design.md》（在册在途 spec）。本地回合终结回复与云端同形态：整颗 OpenUI Lang 1.0 程序（纯对话也写 `root = Markdown("…")`），runtime 落点不变（端侧 Koog + 端工具 + DataStore 记忆），产出协议统一。分层 runtime spec §3 决策 1 同日翻案。灰度期有回滚闸，关闸 = 现行纯文本路径逐字节回归。

- **策略切换（开关 on）**：`KoogChatAgent.chatReActStrategyConfig` 选 `PoLangReActStrategyConfig(streaming=true, finalOutputCorrector=openUiGuard.asCorrector(), onStreamingFallback=shared Logger.w, toolLoopGuard)`；`OpenUiProgramGuard`（自 server 上提 shared）每回合 `runChat` 开头 reset（与 `toolLoopGuard.reset()` 同点位，跨回合不累积纠正计数）；`guardedStrategy` 补齐 loopGuard 熔断边（与 builtinOneShotStrategy 同构，命中携 `TRIPPED_SENTINEL` 直达 nodeFinish）。开关 off 时策略 config 逐字节保留现行（非流式 + 无纠正，只传 toolLoopGuard）。
- **契约段**：见 §1.3——`OpenUiTurnNativeContract`（自 server 上提 shared，SYSTEM_PROMPT 参数化三变体槽：身份行端侧措辞 / toolNote 空串剔除 `server_time` 句 / toolModeOverride 端侧模式覆盖段（2026-11-26，作废 render_html 图集卡指引、豁免 draw_chart；server 缺省空槽零扰动）；契约其余部分与云端逐字相同）+ 五语规则段，技能清单段之后恒最末。工具描述侧配套（2026-11-26）：search_media/refine_media_search/get_gallery_summary/run_gallery_script 四件描述中性化（去 render_html 推送，ChatToolService + ChatToolManifest 双声明源同步）；DSL_RULES 数据禁令段补句柄拼法逐字教学（`scan/<id>` → `local://` 前缀 + `#photos` 后缀，位置参数反例禁令）。已知挂账：run_gallery_script 复合命中集（gallery.intersect 等）不产句柄，turn-native 下精确出图只能改走 search_media 近似集——后续评估脚本结果注册句柄。
- **End 帧 metrics**：`TurnUsageAccumulator` 统一回合用量两路互斥累加（流式轮 `StreamFrame.End` metaInfo ‖ 非流式轮 `onLLMCallCompleted`——同 server 0.9.17 修法，Koog 1.3.0 流式路径不触发后者）；替代原直读 AtomicInt 的 metrics 装配。
- **终结兜底（engine 层）**：`RemoteChatEngine.processChatReAct` 成功路径经 `wrapFinalOutput`（开关 on 时 `OpenUiTurnNativeContract.ensureValidProgram(summary, replyLanguage)`）——对一切消费方（UI/TextReply/IM）fullResponse 恒为合法程序，与云端「server 落流前已兜底」口径一致；开关 off 原样。
- **落库（parts-first）**：`ChatViewModel.insertAssistantFinalMessage` 统一嗅探分流——开关 on 且 `looksLikeProgram(text)`（engine 已兜底，防御不可达）→ `insertPartsMessage`（role=ASSISTANT，parts = reasoning + source + `OpenUiCard(partId="p0", program=text, fallbackSummary, langVersion=OpenUiContract.LANG_VERSION)`，性能 metadata 走既有 `performanceMetadataJson`）+ `touchSession`；否则走原 `insertAgentMessage` 落文本气泡。三个调用点改道：`handleAgentAction` TextReply 分支、dispatch 失败 fallback、纯文本分支。modelUsed 用本地模型标签（非 `cloud_agent`）。IM/无 UI 通道零改动（TextReply 携带兜底后程序文本，与云端 IM 现状同口径）。
- **流式预览**：`sendMessage` 的 `TextSnapshot` 分发——开关 on 且 `looksLikeProgramPrefix(event.text)`（首行 `// summary:` / `root=` 起首）→ 喂 `_openUiStreamProgram`（复用云端 `OpenUiStreamingCard` + forwardRefSkeletons 预览管线，零渲染层改动），**不喂** pacingController/turnAdapter（本轮无文本气泡）；轮内 latch（`openUiPreviewRound`，防抖动来回切）；`RoundStarted`/`ToolCallStarted` 复位 latch 并清预览；回合收尾三处清空（onSuccess/onFailure/CancellationException）。工具轮叙述文本（非程序形态）保持现行文本气泡 + 打字机。
- **回滚闸**：DataStore `local_turn_openui_enabled`（默认开）+ 设置页开关（五语）；`AppContainer` 注入 provider（快照 + Flow 订阅）→ `AgentConfigurator.openUiTurnNativeProvider` → `getChatAgent` 缓存键比对（开关现值 != 缓存位 → 重建 AIAgent，切换后下一条消息生效，无需重启）；ChatViewModel 落库开关同一 `localTurnOpenUiEnabledFlow`。关闸 = 策略回内建节点、prompt 不追加契约段、落库回文本气泡，三处逐字节现行。
- **用户停止半截**：预览非空（已嗅探程序形态）→ 半截丢弃不落库（云端同口径——预览从不落库），预览清空；预览为空且文本半截非空 → 保留现行半截落库（§2.7）。半截程序原文永不可落文本气泡。
- **端侧工具句柄回执**：`search_media`/`refine_media_search`/`get_gallery_summary` 三工具回执尾部附 `（数据句柄 handle: scan/<12hex>）` 并注册 `LocalHandleStore`（`handle: scan/<id>` 供 LLM 绑定 `PhotoGrid/Photo/Video` 等组件 `#photos`/`#videos` 投影；程序内绑定形态 `local://scan/<id>#photos`）——与云端工具桥 `CloudAgentGalleryTools` 逐字一致；注册失败（Room 异常）静默降级为纯前缀回执（不走 error: 协议）。`mediaType` 参数贯通（search/refine/get_gallery_summary 空串=photo）。
- **已知挂账（如实登记，勿写「已支持」）**：①端侧 `mediaType=video` 全链路取数受限——`ChatViewModel` 搜索链路硬编码 PHOTO 过滤（本批次禁改区），Video 组件 `#videos` 投影在本地回合暂不可达，解禁挂后续 Delegate 口径任务；②SearchOutcome 量词「张/照片」未随 mediaType 分型（需补 unitNounOf）；③工具轮占位气泡「正在调用工具」文案在程序生成期悬挂（T9 deferred 观察项，影响面=工具轮→程序主线整个生成阶段，是否立独立任务待真机观感裁定）。

---

## 3. 历史与上下文：主从三级（本链路最易误读处）

2026-10-11（分层记忆 spec M4）起，聊天历史从「并列双轨」改为**主从制**：Room = 长期轨 SSOT，Koog ChatMemory = 短期派生缓存，工程师隧道为第三条只读支路。

### 3.1 长期轨（SSOT）= Room chat_messages

Room `chat_messages`（partsJson 富归档）是聊天历史的唯一事实源：全量、不裁剪、含卡片 parts 全字段。屏幕上用户看到的历史就是这一轨。`ChatMessage.toModelInput()`（shared domain/chat/ChatModelInput.kt）是其唯一的回灌显式转换（UIMessage→ModelMessage，ADR-016 D1）：Text→TextMessage；Chart/HtmlCard/TaskCard/BrowserLive→ToolCall+ToolResult 对（toolCallId=`"messageId:partId"` 命名空间锚）；Image→占位 `[user sent an image]`（PRIVACY）；EditResult→只回灌 description；**剥离面**——OptimizeCandidates/OpenUiCard（data part）、SourceUrl/SourceDocument（source part）、Reasoning（思考链）、Tombstone（占位）一律不进上下文（对齐 Vercel convertToModelMessages 丢弃规则）；回灌只看 parts，Room v28 聊天表重置后运行时零退役值过滤分支（v5 备份恢复的退役卡行落 Tombstone 占位，同被剥离）。

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

> ⚠️ **文档口径更新（2026-11-12，recall_chat_history 落地）**：`CHAT_CARD_CATALOG.md` §0.3 及各卡「回灌」小节描述的 Room→模型投影语义，其消费者现为**三处**：工程师隧道 + 主链路冷启动重建（M4）+ `recall_chat_history` 主动召回工具（§1.4 记忆行——按需拉取当前会话任意段，检索投影 `type='text'` 行而非全量 parts 投影）。稳态运行时主链路上下文仍以 Koog 短期轨为准（预算/老化语义与长期轨全量投影不同）。（原「recall_chat_history 明确未做，留后续」注记就此核销。）

### 3.4 ~~直执回合的记忆补写~~（已退役 2026-11-08）

直执路径已随横滑图集卡退役整体删除，本节机制（直执成功后 user 原文 + observation 补写 Koog 会话记忆）同步下线——现一切意图恒走完整 agent loop，user/assistant/tool 消息由 ReAct 账本自然入记忆，无补写需求。

---

## 4. 设计不变式清单（改动本链路须逐条核对）

1. **媒体红线**：图片字节永不上传，URI 仅以文本形态进 input；user 图回灌为英文中性占位（ADR-008）。
2. **零 JS 桥**：HTML 卡无任何卡内→原生指令通道；清洗器 128KB 上限 + 远程 script/iframe/form 剔除。
3. **observation 与用户文案分离**：模型侧 observation 是硬编码中文，用户可见文案一律走平台层本地化（I18N）。
4. **流式不落 Room**：流式消息纯内存态；落库只发生在产物 emit*、回合结束、用户停止半截落库三个时点。
5. **parts-first 单源**：partsJson 为权威列；type/content/metadata 标量列恒为 `projectChatRow` 派生投影，运行时禁绕过 `insertPartsMessage`/`replacePartsMessage` 直写投影列。
6. **解码不丢消息**：`decodeParts` 损坏行降级原文 Text part；codec 未知 part 鉴别值落 `Tombstone` 占位（前向兼容，`CHAT_CARD_CATALOG.md` §0.4）；Room v28 聊天表重置后运行时零 legacy 迁移知识。
7. **reasoning/source 落库只走 sendMessage 成功路径**：流式收编持久化统一带 `reasoning parts + Text + source parts`（reasoning 落库前置 DONE）；用户取消/错误行不附（error 行语义保持纯净）。
8. **tool schema 单一来源**：ToolRegistry 与 system prompt 工具清单同自 ChatToolService 反射展开，禁手写漂移。

---

## 5. 已知缺口（勘察实录）

| 缺口 | 现状 | 影响 |
|---|---|---|
| ~~请求体逐字 JSON 无录制~~ ✅ 已落地（2026-11-12，DEBUG-only） | `LlmWireTap`（shared remote/config）注入 `RemoteModelFactory.wireTap`，HttpClient 工厂层（`WireTapHttpClientFactory`）装饰 Koog 客户端：`post`/`sse` 出站请求体（Koog 序列化后的 JSON String）旁路捕获 → `LlmCallRecord.requestJson`（16KB 截断，KoogChatAgent/KoogReActAgent/IntentRouter 三组装点同源 `capturedRequestBody`）。**DEBUG（captureContent=true）才录制**；release 不保留请求体（隐私红线，latestRequest 恒 null → 落空串），原行为不变 | 协议实证不再靠推断；release 口径不变 |
| ~~主聊天流 tools schema 无意图级裁剪~~ ✅ 已落地（2026-10-17，M3） | 契约 allowedTools 转执行：路由可靠单意图 → 子集 registry + prompt 同源过滤（§1.4）；OPEN_QA/降级/secondary/子集空交集或缺失过半 保守全量 | — |
| prompt 前缀缓存未显式利用 | ✅ 可观测性已补（2026-10-17）：server 网关解析 `prompt_tokens_details.cached_tokens` 落 `llm_call_log.cached_tokens`（migration 013）+ 管理后台用户详情 Cached 列（依赖 TokenHub/CF 网关原样透传该嵌套对象；DeepSeek 旧版平铺字段 `prompt_cache_hit_tokens` 不采集）；✅ **App 侧已补**（2026-11-12）：`LlmWireTap` 在同一 HttpClient 装饰层对 SSE 逐帧 data 做子串扫描（命中 `"cached_tokens"` 才解析，无正则 O(帧长)，**不包装 Flow、只套 decode lambda 前置同步钩子**，生产开销可忽略故未降级 DEBUG-only），`LlmCallRecord.cachedTokens` → 独立库 `llm_call_log.cachedTokens`（Room v6，migration 5→6），全构建落库（纯指标）；每次 onRequest 清零防跨请求误归因。限制：依赖供应商流式尾帧回传 usage（DeepSeek/Moonshot 恒带；OpenAI 官方需 stream_options，Koog 未传则拿不到）；非流式 post 无 SSE 帧不采集；Anthropic Messages 的 `cache_read_input_tokens` 键名不同不采集。缓存**主动利用**（前缀稳定化之外的命中提升）未做 | 命中率可量化后再评估前缀重排收益；OpenAI 官方直连的命中观测依赖 Koog 上游补 stream_options |
| tool schema 转换在 Koog 内部 | `AbstractOpenAILLMClient` 序列化，仓库外 | schema 问题升级依赖 Koog 版本 |
| ~~Room 回灌在主链路的消费面（主动召回未做）~~ ✅ 已落地（2026-11-12） | toModelInput 已服务工程师隧道 + M4 冷启动重建（§3.2）；`recall_chat_history` 主动召回工具落地（§1.4 记忆行：当前会话 `type='text'` 投影行 LIKE / 最近 N 条，observation 硬编码中文，无 UI 卡）；iOS 未接入（ChatToolManifest 手工清单不动） | 长会话中远期历史可按需召回；跨会话检索仍不做（当前会话 scope） |
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
