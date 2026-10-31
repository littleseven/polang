# Agent 步骤时间线（用户可见）+ 管理后台推理追溯 设计

> **日期**：2026-11-01
> **状态**：待评审
> **范围**：`server/`（管理后台）、`androidApp/`（主聊天 + 云端 Agent 调试页）、`shared/`（如需）、`iosApp/`（跟随，条件触发）
> **关联**：ADR-016（Chat parts 渲染宪法）、ADR-018（云端 Agent 会话域）、`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`

---

## 1. 意图与为什么

同一个诉求的两个视图：

1. **用户视图（②，本批主目标）**：云端 Agent 跑一轮时，用户在聊天里看到一条**策划过的**步骤时间线——「正在搜索相册… → 找到 23 张照片 → 正在生成卡片…」，感知 AI 在干活，而不是盯着三个点转几十秒。
2. **运营视图（A）**：管理后台能逐笔、逐步还原任何一次后端推理——全局调用流水（谁、何时、什么模型、哪个渠道、成败、延迟、token、成本）+ 云端 Agent 会话的完整事件流回放（LLM 输出 → 工具调用 → 工具结果 → 发卡 Action）。

两个视图消费**同一份数据**（`agent_event` 事件流 + `llm_call_log`），一处埋点两处消费。

**为什么现在做**：云端 Agent 链路（ADR-018）已承载主聊天流量，但过程对用户完全黑盒（只有 thinking 三点和单行「Calling tool…」文字切换），对运营也只有聚合统计没有逐笔下钻。侦察确认：**步骤数据已经全部到达端侧，只是被丢弃了**——`CloudAgentTurnRunner.handleEvent` 显式忽略 `observation`/`finish` 和除 `render_ui*` 外的 `action`（`androidApp/.../features/cloudagent/CloudAgentTurnRunner.kt:131-133`），iOS 调试页同样丢弃（`iosApp/.../CloudAgentChatStore.swift:204-211`）。这是一个「接水」而非「打井」的窗口期。

## 2. 目标 / 非目标 / 反目标

### 目标

- **G1**：Android 主聊天云端回合进行中，流式气泡内展示步骤时间线：每步 = 图标 + 用户可读短语 + 状态（运行中 / 成功 / 失败）；回合结束即消失（瞬态）。
- **G2**：步骤短语是**策划过的**产品语言（i18n 五语），不是工具名直出；内部/噪声工具不出现在时间线。
- **G3**：管理后台新增「调用流水」页：`llm_call_log` 全局分页列表，可按状态/模型/渠道筛选，从用户详情交叉跳转。
- **G4**：管理后台新增「Agent 会话」页：会话列表 + 详情页按事件序只读回放整轮过程（action↔observation 配对呈现）。
- **G5**：Android 定稿后固化 UI spec（tokens/布局/行为），满足 [PARITY] 红线的「Android 定稿 → 固化 spec → iOS 读 spec 跟随」流程。

### 非目标（本批不做）

- **LLM 文本流式下行**：服务端 LLM 输出仍是终态单条 `message` 事件（不逐 delta 下发/落库）。步骤时间线不依赖它。
- **步骤时间线落 Room 持久化**：瞬态展示，回合结束/进程死亡即弃（对应 chat-parts spec M5 口径，M5 本身不在本批）。
- **Agent 回合的 `llm_call_log` 计费记录**：现状 `/agent/**` 回合不写 `llm_call_log`（成本观测缺口），本批不补，见 §7 已知缺口。
- **原始思维链（reasoning_content）展示**：产品形态定为「策划步骤」而非裸 CoT（已决策，见 §3）。
- **iOS 主聊天云端 Agent 接入**：iOS 主聊天当前走 KMP 本地回合（`ChatAgentBridge`），云端 Agent SSE 仅 DEBUG 页消费。时间线 iOS 跟随以「云端回合进入 iOS 主聊天」为前提（见 §8 M3）。
- **管理后台实时推送**：SSR 页 + 手动刷新即可，不上 WebSocket。

### 反目标（做了即失败）

- 把工具名/argsJson 原样糊给用户（技术细节泄漏，违背「策划过的语言」）。
- 端侧为步骤时间线新增任何持久化存储或新的网络请求（必须纯消费现有 SSE 帧）。
- 管理后台回放页提供任何写操作（只读镜像）。
- 服务端为追溯开始留存 `/v1/chat/completions` 的 prompt/response 原文（违背最小留存定位；Agent 域内容已在 `agent_event`，无新增留存）。

## 3. 已决策项（来自需求讨论）

| 决策 | 结论 | 理由 |
|------|------|------|
| 追溯粒度 | A：元数据级（llm_call_log）+ Agent 事件流回放；不做 proxy 原文落库 | [PRIVACY] 精神与存储成本；Agent 域内容已落库，零新增留存 |
| 用户可见「思考过程」形态 | 策划步骤时间线（②），非裸思维链（①） | 普通用户可读性、成本（reasoning tokens 计费且拉长首字）、质量可控 |
| 两个视图的关系 | 同一份事件数据，用户看精选步骤，运营看完整黑匣子 | 一处埋点两处消费 |
| 实施顺序 | M1 用户步骤时间线（Android）→ M2 后台追溯 → M3 iOS 跟随（条件触发） | 用户显式定调「实现2」；M1 纯端侧无服务端依赖 |
| GenUI 默认线格式 | 客户端默认 **OpenUI**（2026-11-01 用户定）：`normalizeGenUiFormat` 缺省/未知值归一 `"openui"`；已显式存储 `a2ui` 的用户偏好保留 | OpenUI 组件库（97 组件）已成为主力线，A2UI 退为可切换备选 |

## 4. 现状盘点（侦察结论，实施的事实基础）

### 4.1 服务端事件模型（`server/.../agent/`）

- `agent_event` 表：六型载荷 `message | action | observation | condensation | finish | html_card`；`id` 全局自增即事件序；`created_at` epoch ms；`parent_id` 用于分支锚定。
- **步骤配对键**：`Observation.actionEventId` 指向对应 `Action` 事件 id；Action 无配对 Observation = pending（`AgentEventRepository` 有此不变式查询）。
- `Action` 载荷：`tool` + `argsJson`；`Observation` 载荷：`content`（结果文本，错误为 `"error: …"` 前缀）。
- 工具全集：`server_time`、`render_html`（服务端）；`get_gallery_summary`、`search_media`、`refine_media_search`（端侧桥）；`render_ui` xor `render_ui_openui`（按 `X-GenUI-Format` 互斥注册）。
- SSE 帧五种 `kind`：`event`（落库事件原样下发）、`tool_request`（瞬态，端侧工具桥）、`openui_delta`（瞬态预览）、`done`、`error`。
- 事件回放 API 已存在：`GET /agent/sessions/{id}/events?after=<id>&limit=`（用户本人域）。
- 管理后台现有：kotlinx.html SSR、`AdminQueries`（概览/用户/流量/设备/渠道/诊断），`recentCalls(accountId, limit)` 已有按账号查 `llm_call_log` 的查询先例。

### 4.2 Android 端（`androidApp/`）

- SSE 消费内核 `CloudAgentTurnRunner`（主聊天与调试页共用）：帧 → `CloudTurnEvent`（`AssistantText|ToolCall|HtmlCard|Error|A2uiCard|OpenUiCard|OpenUiCardDelta|Done`）。
- **丢弃点**：`handleEvent` 中 `observation`/`finish`/`condensation` 不上屏，`action` 仅处理 `render_ui`/`render_ui_openui`。
- 主聊天云端回合 UI 现状：占位气泡（`chat_thinking` + `TypingIndicator` 三点）→ 首个 `ToolCall` 把气泡文本换成 `chat_calling_tool` → 文本流到位后覆盖。`runCloudTurn` **绕过 parts reducer**（M4 那套 ToolPart 状态机只服务本地 Koog 回合）。
- **现成视觉先例**：工程师模式 `ClaudeAgentSteps`（`ChatScreen.kt:1630` 附近，`ClaudeStepUi(tool, status, detail)` + `ClaudeStepStatus{RUNNING,SUCCESS,FAILED}`，渲染于 `AgentMessageExtras`）；BrowserLive 卡内 ≤3 条实时动作日志。
- i18n 先例：`cloud_agent_tool_call` = "Calling %1$s…"（五语已同步）；ViewModel 经 `stringContext()` 取串，保持 Context-free。

### 4.3 iOS 端（`iosApp/`）

- 主聊天走 KMP `ChatAgentBridge` 本地回合，共享 `ChatListFlattener`（与 Android 同源）；`pendingToolName` → `TYPE_TOOL_STATUS` 单行工具状态（单步退化形态）。
- 云端 Agent SSE 仅 DEBUG 页消费（该页 PARITY 豁免）；`handleEvent` 只上屏 assistant message，其余丢弃。
- i18n：`Localizable.xcstrings` 五语；运行时切换走 `L(_:)`（注意 `ChatView.swift` 有 `String(localized:)` 混用，新增串统一用 `L()`）。

## 5. 设计：M1 用户步骤时间线（Android）

### 5.1 事件映射（`CloudAgentTurnRunner` 扩展）

新增 `CloudTurnEvent` 子型（命名以实现时代码风格为准）：

```
StepStarted(stepId: Long, tool: String)          // 来自 action 事件（含 render_ui*/render_html，见下）
StepFinished(stepId: Long, ok: Boolean)           // 来自 observation 事件，stepId = actionEventId
RoundFinished(reason: String)                     // 来自 finish 事件（可选，用于清理兜底）
```

- `stepId` = 服务端 `Action` 事件 id；`Observation.actionEventId` 为配对键。
- **发卡类工具（`render_ui`/`render_ui_openui`/`render_html`）也进时间线**：用户感知的是「还在干活」，卡到位步骤自然完结；卡的呈现仍走现有卡通道，步骤只是过程信号，两份表达不冲突。
- **孤儿 Observation 处理（已核实的协议洞）**：门控拦截（`actionGate` 拒绝 render_ui 负载，属 LLM 自我修正回路的常规路径）或 toolCallId 未命中时，服务端会落 `actionEventId = 0L` 的孤儿 Observation（`AgentRunner.kt:147,163`），回合开始 pending 重放闭合也会产生。端侧规则：**收到未知/0 stepId 的 StepFinished 一律忽略**（内部自我修正噪声，不上屏，符合「策划过的语言」）。
- 失败步骤**不展示服务端原始错误文本**（通常英文技术文本，违反 [I18N] 与「不泄漏技术细节」反目标）：FAILED 步骤 = 步骤标签 + 一个通用五语失败提示（如 "didn't work, retrying…"），`AgentStepUi` 无 detail 字段。

### 5.2 状态载体（`ChatViewModel`）

- 新增瞬态 `StateFlow<List<AgentStepUi>>`，生命周期完全镜像 `_pendingNonCardTool`：回合开始清空、回合 `finally` 清空；不触 Room、不触 parts reducer。
- `AgentStepUi(stepId, labelRes, status)`，status 枚举 `RUNNING | SUCCESS | FAILED`（对齐 `ClaudeStepStatus` 语义）。
- 同一时刻通常只有一步 RUNNING；异常时（上一 Observation 丢失）新 StepStarted 把滞留 RUNNING 兜底置 FAILED 与否——**留给实现者**，建议保守置 SUCCESS 之外不动，仅新步追加。

### 5.3 步骤标签映射（产品语言，五语）

ViewModel 侧工具名 → string resource 映射表（穷举 sealed/when，禁止 else 直出工具名）：

| tool | 步骤短语（en 示意） | 备注 |
|------|------------------|------|
| `get_gallery_summary` | "Looking through your gallery…" | |
| `search_media` | "Searching your photos…" | |
| `refine_media_search` | "Refining the search…" | |
| `render_ui` / `render_ui_openui` | "Composing a card…" | |
| `render_html` | "Building a page…" | |
| `server_time` | **不展示** | 内部工具，噪声 |
| 未知工具 | **不展示**（记日志） | 防工具名泄漏，新增工具需显式登记映射 |

五语同步（EN/zh-CN/zh-TW/ES/FR），Android `strings.xml` 五目录 + iOS `xcstrings` 在 M3 同步。key 命名遵循现有 `cloud_agent_*` 前缀。

### 5.4 渲染（主聊天）

- **位置**：流式气泡内、正文之上——替换现有「`chat_calling_tool` 单行换字」行为（不再 swap 气泡文本）。
- **形态**：纵向步骤列表，视觉参考 `ClaudeAgentSteps` 先例（该先例实际为文本字形 ⏳/✓/✗ + 13sp monospace `onSurface`；本时间线建议升级为 spinner/✓/⚠ 图标 + 13sp secondary，具体样式以固化 UI spec 为准）；FAILED 步骤下方一行通用失败提示（五语）。
- **数量**：全量展示（一轮 ≤20 迭代上限，实际 2~5 步）；不做折叠（初版从简，实测过长再加）。
- **时序**：`ToolCall` 事件（tool_request 帧到达）与 StepStarted（action 事件到达）几乎同帧——**以 action 事件为准**，`tool_request` 帧维持现有端侧执行职责，不再驱动 UI 文本 swap。
- thinking 三点保留在「首个事件到达前」阶段不变。
- 调试页（`CloudAgentChatScreen`）：同一批事件顺手接入其 `inFlight` 区域（低成本同构， PARITY 豁免页）。

### 5.5 埋点/日志

结构化日志沿用 `PoLang:` 前缀现状，记录 step 开始/结束（tool、耗时、ok）到 logcat；不落新表。

## 6. 设计：M2 管理后台推理追溯（server）

### 6.1 调用流水页 `GET /admin/llm/calls`

- 数据源 `llm_call_log`；按 `id` 倒序游标分页（`?before=<id>&limit=50`），筛选：`status`（ok/upstream_error/blocked_quota/blocked_rate）、`provider`、`model`（文本精确）、`account_id`（从用户详情带入）。
- 列：时间、用户（maskEmail 同款脱敏 + 链到用户详情）、模型、渠道、prompt/completion/cached tokens、成本、延迟、状态（色块）、platform。
- `AdminQueries` 新增 `llmCalls(before, limit, filters): List<CallRow>`——复用现有 `CallRow` 模型扩展 cachedTokens（已有）；查询走索引 `(created_at)` 前缀可接受，量大再优（2G 盒子，不做全表 count）。

### 6.2 Agent 会话页 `GET /admin/agent/sessions` + `GET /admin/agent/sessions/{id}`

- 列表：`agent_session` 按更新时间倒序分页；列：session id（短码）、用户（tokenHash 前 8 位 + 可关联 account 则脱敏邮箱）、状态、创建/更新时间、事件数、入口链接。
- 详情：`agent_event` 按 id 序全量（分页 `?after=` 同回放 API 口径，单页 200）；渲染规则：
  - `message`（user/assistant 分色块）、`action`（工具名 + argsJson 折叠 `<details>`）、`observation`（与 action 配对缩进，error 红色；**孤儿 observation（`actionEventId=0` 或无配对）不隐藏**——门控拦截正是运营最想看的失败痕迹，单独成条并加「门控拦截/未配对」徽标）、`html_card`（只展示 summary + 字符数，**不渲染 HTML 本体**防 XSS）、`finish`（终态横幅）、`condensation`（摘要文本）。
  - argsJson/content 一律 HTML escape（kotlinx.html 默认转义，禁用 raw）。
- 双向跳转：用户详情页「最近调用」区 → 流水页（带 account_id 筛选）；用户详情 → 该用户 Agent 会话列表；会话详情每条事件显示相对回合开始的 +ms（由 created_at 差值算）。
- 数据口径说明：① 访客（未注册）`/v1/chat/completions` 调用不落 `llm_call_log`（`LlmRoute.kt` 全部 `accountId?.let` 守卫），流水页天然无访客行；② 重新注册会轮换 `token_hash`，旧 `agent_session` 与 account 脱钩，「用户 → 会话」关联为尽力而为，不承诺完整。
- 鉴权：沿用 `AdminAuth`（cookie / X-Admin-Token），只读，无任何 POST。

### 6.3 服务端测试

沿用现有 `AdminRoutesTest`/`AdminQueriesTest` 模式：新路由的鉴权、分页游标、筛选、HTML 转义（注入 `<script>` 进 argsJson 断言转义）。

## 7. 已知缺口（记录在案，非本批）

- `/agent/**` 回合不写 `llm_call_log`：云端回合的 token/成本无观测（M2 流水页看不到云端回合）。后续可给 `agent_session` 加累计 usage 列或在回合 Finish 时补记。
- LLM 文本流式不持久化、不逐 delta 下发：未来若要「打字机」效果需动 `AgentRunner` + 协议，独立评估。
- 会话状态机 `running` 在超时/断连后不回收：后台列表会出现「假 running」，展示时按「最后事件时间 >30min 视为 stalled」标注即可（视图层处理，不改状态机）。

## 8. 分期与验收

| 期 | 内容 | 验收 |
|----|------|------|
| **M1** | Android 主聊天步骤时间线（§5）+ 调试页同构 | 真机云端回合：搜索类提问可见 ≥2 步（RUNNING→SUCCESS）；失败注入（断网工具超时）见 FAILED+detail；回合结束时间线消失；五语截图检查；不新增持久化/请求 |
| **M2** | 管理后台流水页 + Agent 会话回放（§6） | `./gradlew -p server build` 绿；本地起服造数据：分页/筛选/转义/交叉跳转人工过一遍；`deploy.sh` 部署（遵循发卡双交付铁律，bump version） |
| **M3** | iOS 跟随（条件触发） | 前提：云端 Agent 回合进入 iOS 主聊天；按 §5.4 固化的 UI spec 走 /ios-follow 管线；此前 iOS 不受影响（云端页 PARITY 豁免） |

**PARITY 合规说明**：M1 落在 Android 主聊天（云端回合路径）；iOS 主聊天当前无云端回合路径，属功能缺位而非 UI 分叉，不触发零容差。M1 交付时必须固化步骤时间线 UI spec（布局/tokens/行为，落 `docs/08-UI-SPECS/` 或本 spec §5.4 细化后登记），供 M3 读取。

## 9. 留给实现者的判断

- 事件子型命名与 `CloudTurnEvent` 的具体扩展形态（sealed 子类 vs 字段）。
- 滞留 RUNNING 步骤的兜底策略（§5.2）。
- 步骤列表的最大展示条数与折叠策略（初版全量，实测再调）。
- 管理后台列表的具体列宽/色块样式（沿用现有 SSR 页面视觉语言）。
- `llmCalls` 查询在无索引筛选列（model/provider）上的实现：先内存过滤（读全量窗口）还是补索引，以 2G 盒子实测为准。
