# OpenUI 回合原生（turn-native）改造 — 设计 Spec

> **日期**：2026-11-01
> **状态**：已定稿待评审
> **上位约束**：ADR-016（Chat parts 宪法）/ spec《2026-10-21-cloud-local-hybrid-a2ui-genui-design.md》（D1~D3、§3.4 不变式继承）/ spec《2026-10-24-openui-wire-format-toggle-design.md》（OpenUI 线既有实现）/ [PRIVACY] / [I18N] 红线
> **对标**：GPT-6 Intelligent UI（2026-10-07 发布）——原生可流式组件库 + 边生成边渲染编译器 + 训练出的设计判断力；本方案以「编排层 + 校验回路 + 渲染顺序约定」逼近其终端形态
> **对齐记录**：2026-11-01 会话内逐项确认——① 回合层统一（回复 = 一颗 OpenUI 组件树，text part 退役为降级兜底）；② 流式走整树前缀增量渲染（A 路线）；③ 发射通道走甲方案（响应即程序，render 工具退役），乙（工具即出口）作为回退预案记录；④ 过程感三层（过渡语消散 + 工具状态卡 + step timeline）维持，reasoning 追溯条独立分期；⑤ 分期 M1 单链路最小闭环

---

## 1. 问题陈述

OpenUI 线当前是「工具发卡」模式：LLM 先以纯文本流式回复，最后调用 `render_ui_openui` 工具交一颗组件树。这带来三个结构性问题：

1. **两种回复人格**：文本走 markdown 通道、界面走工具通道，LLM 用 markdown 硬撑排版（伪图表禁令即证据），「过渡语 vs 最终卡」的重复/断裂需要专门规则消解（GPT Apps SDK issue #73 同款坑）；
2. **markdown 体系过载**：ADR-016 的 markdown AST 渲染承担了大量本应由结构化组件承担的版式职责；
3. **与行业终态的差距**：GPT-6 Intelligent UI 已验证「回复 = 界面」的终端形态（组件目录 + 边生成边编译渲染 + 文本作为组件树的一种叶子），并推向全量用户。我们的 OpenUI 自研栈（100 组件目录 + 前缀增量解析引擎 + P2 流式预览）与其架构同构，差距集中在发射通道（工具信封 vs 响应本体）与模型引导（训练 vs 编排）。

**目标**：OpenUI 线升级为回合原生——LLM 的回复内容本身即 OpenUI Lang 程序，流式编译上屏；工具发卡模式在 OpenUI 线退役；A2UI 线零改动保留为回退闸。

**非目标**：不训练/微调模型（三板斧补偿，§3.5）；不动 parts 协议与 Room schema（D1 继承）；不动本地链路、A2UI 线、iOS。

## 2. 方案概述

**一句话：OpenUI 线从「工具发卡」改为「响应即程序」——content 即组件树，流式前缀直驱渲染，校验回环保合法，兜底包装保不炸。**

```
设置页 genui_format = openui（既有开关，语义升级）
        ↓
server：ToolRegistry 不再注册 render_ui_openui / render_html（OpenUI 线）
        system prompt 换成 OpenUI 母语契约（DSL 教学自工具描述迁入）
        ↓ Koog loop（cloudAgentStrategy，节点已全流式）
        content delta 帧（新增透传）→ openui_delta SSE 帧（语义升级：响应程序前缀）
        ↓ 终结消息
        ProgramGuard（CardClaimGuard 泛化）：RenderOpenUiValidator 全量校验
            失败 → 带错误详情的 user 纠正消息回环重发（上限防死循环）
            纠正用尽仍失败 → server 兜底 root = Markdown("原文") 包装
        ↓ 合法程序
        落 AgentEventPayload.Message（事件溯源格式零变化）→ SSE event 帧
        ↓
端上：openui_delta → 既有 P2 瞬态预览（OpenUiStreamingCard，覆盖式替换）
      终结 message 事件 → 解析为 OpenUiCard → 既有 flush/落库/渲染管线
```

关键性质：**渲染管线一条**——文本（Markdown 组件）、图表、照片网格、HTML 沙箱（Html 组件）都是同一棵树上的叶子；**降级链双层**——校验回环（同轮修正）+ 兜底包装（永不炸卡）；**回退闸零成本**——开关拨回 a2ui 即回到旧世界。

## 3. 核心机制

### 3.1 发射通道：响应即程序（甲方案）

- `genui_format=openui` 时（`AgentRoutes.kt:296-307` 的注册点）：`ToolRegistry` = ServerToolService（去掉 render_html）+ DeviceToolService + **无 GenUI 工具**。`render_ui_openui`、`render_html` 在 OpenUI 线退役；`render_ui`（A2UI 线）不受影响。
- LLM 的 assistant content = OpenUI Lang 1.0 程序全文。终结消息经 `AgentEventPayload.Message` 落流（事件溯源格式零变化，`AgentRunner.kt:186-193` 原路径）。
- 系统 prompt（`DEFAULT_SYSTEM_PROMPT`，`AgentRoutes.kt:60`）替换为 OpenUI 母语契约：DSL 一句话语法 + 目录组件签名（自 `RenderOpenUiToolService` 工具描述迁移，token 平移不新增）+ §3.4 渲染顺序约定 + 数据禁令（`Query("local_handle", …)`，handleId 只许来自 search_media/refine_media_search 结果，禁路径/uri——[PRIVACY] 口径不变）。

### 3.2 流式：content delta 直驱整树预览

- 现状：`cloudAgentStrategy` 全部 LLM 节点已走 `requestLLMStreamingOrOnce()`（`CloudAgentStrategy.kt:27`），EventHandler `onLLMStreamingFrameReceived` 已接收流式帧，但只透传 `StreamFrame.ToolCallDelta`（`AgentRunner.kt:110-118`）。
- 改造：同钩子增加 **content 增量帧透传**——已到达的响应文本前缀（节流口径同 `OpenUiToolCallStreamTracker`）经既有 `onOpenUiProgramDelta` 回调 → `{"kind":"openui_delta","program":"<前缀>"}` SSE 帧下行。帧格式不变，语义从「工具参数前缀」升级为「响应程序前缀」；不落 agent_event（瞬态传输信号，同 P2 既有口径）。
- 端上零新建：`openui_delta` → `_openUiStreamProgram` → `OPENUI_STREAM_PREVIEW_ID` 瞬态消息 → `OpenUiStreamingCard` 前缀增量渲染，**覆盖式替换**（回合内每颗新程序整体覆写预览，§3.3）。
- 实施首日验证项：Koog 1.3.0 `StreamFrame` 的 content 增量帧类型（Append/ContentDelta）确实经 EventHandler 透传。**退路**：不产帧则终结 message 事件仍保证正确性——流式预览退化为无预览，功能不缺失，后续再补。

### 3.3 多程序关系：覆盖式

- 一个回合内 LLM 可能产出多颗程序：中间迷你程序（"我来扫一下" → `root = Markdown("我来扫一下")`）+ 终结整树。**每颗新程序整体替换前一颗渲染树**（GPT-6 cohesive final answer 同款语义）。
- 中间迷你程序是瞬态轨（不落 Room、不进事件流本体——其 content 不落 Message 事件，仅 delta 帧驱动预览）；LLM 想把过渡语保留进最终界面，须自行写进终结树。
- DeepSeek「叙述文本 + tool_calls 同帧」惯性与策略层既有修正（先匹配 tool_calls，`CloudAgentStrategy.kt:44`）天然自洽：同帧叙述文本即迷你程序。
- `Mutation`/`@Set` 增量更新**不引入**（OpenUI spec §6.2 维持 M1 未实施口径）。

### 3.4 渲染顺序约定（写进 system prompt）

为让「边生成边渲染」的前缀始终是「骨架先立、数据后填」（GPT-6 靠训练获得的输出顺序，我们用规约逼近）：

1. 首行 `// summary: <投影级一句话摘要>`（替代原工具参数 fallbackSummary；端上降级卡与历史折叠的数据源）；
2. `root` 声明紧随其后；
3. 布局骨架组件（Column/Row/Card/Grid）先于数据组件；
4. `Query` 语句殿后。

`OpenUiLayoutNormalizer`（既有，原 argsTransformer 链路）迁移至终结消息落流前执行——门禁见到的程序 = 矫正后的程序。

### 3.5 校验回路：ProgramGuard（CardClaimGuard 泛化）

- 现状接缝：`correctFakeCardClaim` 节点（`CloudAgentStrategy.kt:80`）——终结消息命中发卡声称 + 当轮无卡 Action → 追加 user 纠正消息就地重发（`MAX_CORRECTIONS=1`）。
- 泛化为 **ProgramGuard**：OpenUI 线下，终结消息（onTextMessage 边）一律过 `RenderOpenUiValidator` 校验（结构级全量：root 入口/组件白名单/Query 形态/媒体路径禁令/长度上限——规则同源复用，校验器本就不依赖 Koog 上下文，不跑完整 parser 的轻量口径不变）：
  - **失败** → 纠正消息携带**具体错误清单**（比 CardClaimGuard 的笼统提示更强——LLM 同轮修正成功率已被 10-24 门控验证）→ 回环重发；上限沿用 `MAX_CORRECTIONS=1`（防死循环）。
  - **纠正用尽仍失败** → 兜底：`root = Markdown(<原文 JSON 转义>)` 包装后落流。**端上永远收到合法树**。
  - A2UI 线行为不变（CardClaimGuard 原逻辑保留）。
- 假发卡守卫语义消亡：OpenUI 线没有发卡工具，「声称发卡」概念不复存在——守卫职责被「必须是合法程序」完全覆盖。

### 3.6 Html 组件：L2 沙箱入树

- `OpenUiLibrary` 注册 `Html(html*, display?)` 组件：html = 完整自包含 HTML 字符串；display = INLINE（缺省，限高约 320dp）/ FULLPAGE 占位。
- 端上渲染：`OpenUiNodeRenderer` 加 WebView 沙箱叶子——复用现有 HTML 卡沙箱实现（网络默认关死、dispatch 白名单、QuickJS/JSBridge 取数），Inline 形态内嵌；Fullpage 点击弹既有全屏查看器。
- §3.4 外向通道不变式原样继承（hybrid spec）：Html 组件内任何外向通道只许携带投影级数据。
- render_html 工具的「summary-only 回执」语义消亡：HTML 本体作为程序一部分，LLM 上下文可见（与甲方案「程序即回复」自洽）；历史膨胀由 §4 风险表的投影折叠规则消化（M2）。

### 3.7 端上解析与落库

- `CloudAgentTurnRunner`（`CloudAgentTurnRunner.kt:104-106`）：`message` 分支在 OpenUI 线下把 assistant content 按程序解析 → `CloudTurnEvent.OpenUiCard(program, fallbackSummary)`——fallbackSummary 提取规则：首行 `// summary:` 注释文本；缺失时回退程序内第一条 Markdown/Text 字符串字面量（截 80 字），仍无则置空（端上降级卡走既有 `chat_a2ui_card_*` 通用文案）；解析失败（理论上不可能，兜底包装保证）→ 退化为 `AssistantText` 按文本渲染。
- action 分支的 `TOOL_RENDER_UI_OPENUI` 解析**保留**（A2UI 线照旧；OpenUI 线不再产生该 action 帧，保留无害向后兼容）。
- `ChatViewModel`：终结程序走既有 `openUiFlushes` → `insertOpenUiCardMessage`（`data_ui_openui` part、Room schema 零变化）；`persistOpenUiViewState` 交互态持久化不变。
- 历史消息零迁移：旧 `data_ui_openui` 行照常解码渲染。

### 3.8 GenUI 开关语义升级与回退闸

- `genui_format` 开关不变（`a2ui`/`openui`），`openui` 语义升级为回合原生。**A2UI 线 = 天然回退闸**：线上事故时端侧拨开关即回旧世界（按请求头发送，server 无状态），不需要发版回滚。
- A2UI 线的处置（删除倒计时）不在本 spec，随 M1 稳定运行后的选型 ADR 定论。

## 4. 端到端时序（「看看我上周拍了多少照片」）

1. 端上：`POST /agent/sessions/{id}/messages`（`X-GenUI-Format: openui`）。
2. server Koog 轮次 1：LLM content 流式产出迷你程序 `// summary: 开始扫描\nroot = Markdown("我来扫一下…")` → content delta 帧 → `openui_delta` 下行 → 端上预览上屏。
3. 同轮或次轮：LLM 发起 `search_media(timeRange="上周")`（function calling 照旧）→ tool_request 帧 → 端执行 → 投影（327 张 + `handle: scan/7f3a`）回传；工具状态卡照常转圈收尾。
4. server Koog 轮次 2：LLM 流式产出终结整树（`// summary: 上周共 327 张照片` + `root = Column([...])` + `PhotoGrid(photos, 3)` + `Query("local_handle", {handle:"local://scan/7f3a#photos"}, [])` 殿后）→ delta 帧逐段驱动预览「长成」。
5. 终结：ProgramGuard 校验通过（或纠正/兜底后）→ Message 事件落流下行 → 端上解析为 `OpenUiCard` → 落 Room（`data_ui_openui`）→ `OpenUiCardHost` 正式渲染，`HandleLookup` 填全量照片。
6. 用户点选/翻页：`@Set` 写 state → `persistOpenUiViewState` 同 part id 覆写（不变）。

## 5. 用户故事

- **US-1（响应即界面）**：用户提问，回复以组件树形态边生成边上屏，无「先文字后卡片」两段感。
- **US-2（纯文本形态保留）**：简单闲聊（"你好"）渲染为只含 Markdown 组件的树——用户看到的是纯文本，管线只有一条。
- **US-3（合法保证）**：LLM 写出非法程序时用户无感——同轮修正或兜底包装后正常渲染，不出现降级卡（降级卡仅保留给端上解析灾难路径）。
- **US-4（过程感不倒退）**：工具调用期间工具状态卡、step timeline 照常；过渡语以迷你程序流式上屏、被终结树覆盖消散。
- **US-5（HTML 入树）**：LLM 产出含 Html 组件的树，端上沙箱渲染（网络关死、dispatch 白名单），Inline/Fullpage 行为同现有 HTML 卡。
- **US-6（回退闸）**：开关拨回 a2ui，一切行为回到 M1 之前（A2UI 线零改动验证）。
- **US-7（隐私闸不松）**：抓包验证——程序中无媒体路径/uri；handleId 仅来自搜索工具结果；Html 组件外向负载只含投影字段。

## 6. 已定决策（防翻案）

| # | 决策 | 依据 |
|---|------|------|
| D1 | 回合层统一：OpenUI 线回复 = 一颗组件树，text part 退役为降级兜底 | 用户定调「全部纳入 openui 体系」；GPT-6 同构验证 |
| D2 | 流式走整树前缀增量渲染（A 路线），排除分块多树与快慢混合 | P2 基建复用最大；viewState/落库语义最干净 |
| D3 | 发射通道走甲（响应即程序），乙（工具即出口）为回退预案 | 用户定调「跟随 GPT-6 走最强方案」；content delta 免 JSON 信封税；乙的工程资产（RenderOpenUiValidator 规则、normalizer、P2 通道）全部平移复用 |
| D4 | 校验回路 = ProgramGuard（CardClaimGuard 泛化）+ 兜底 Markdown 包装 | 纠正回环接缝已存在且经生产验证；端上永远收到合法树 |
| D5 | 覆盖式多程序关系，不引入 Mutation/追加 | 一句话规则零复杂度；GPT-6 cohesive answer 同款语义 |
| D6 | Html 作为组件入树（L1/L2 统一为同一棵树的两种叶子） | 消解双线分裂；沙箱实现原样复用 |
| D7 | A2UI 线零改动，作为回退闸保留；删除与否随选型 ADR 另行定论 | 线上事故零发版回退；并行实验资产不提前销毁 |
| D8 | 不训练/微调模型；三板斧（校验回环 + 渲染顺序约定 + golden 示例库）补偿 | 第三方 API（DeepSeek/Kimi）不可微调；LoRA 记远期项 |
| D9 | 过程感三层维持（过渡语消散 + 工具状态卡 + step timeline）；reasoning 追溯条独立分期 | thinking=disabled 的格式稳定口径在程序契约下更重要；reasoning 与 content 通道正交 |
| D10 | 中间迷你程序瞬态不落库；想保留的话语由 LLM 写进终结树 | 事件溯源只收终态；避免树间关系复杂度 |

## 7. 测试决策（接缝与完成标准）

1. **ProgramGuard**（server 单测）：合法程序直接 Finish；非法程序触发纠正回环（断言纠正消息含具体错误清单）；纠正用尽 → 兜底包装落流（断言 `root = Markdown(` 前缀）；A2UI 线 CardClaimGuard 行为不变。
2. **content 帧透传**（server 单测）：fake LLM 流式 content → `onOpenUiProgramDelta` 收到递增前缀；无 content 帧（一次性请求回退路径）→ 无 delta、终结 Message 仍正确。
3. **TurnRunner 解析**（androidApp 单测）：message 事件（程序）→ OpenUiCard 事件（含 `// summary` 提取）；message 事件（非程序兜底文本）→ AssistantText 降级；action 帧旧路径兼容。
4. **覆盖式预览**（androidApp 单测）：顺次 delta（迷你程序 → 终结树）→ StateFlow 覆写序列正确；正式卡落库后预览清空。
5. **Html 组件**（shared 单测：目录校验含 Html prop schema；androidApp：渲染宿主挂 WebView 沙箱、dispatch 白名单断言）。
6. **隐私闸**（单测 + 抓包）：程序媒体路径禁令校验用例（RenderOpenUiValidator 同源）；真机抓包验证无媒体出端。
7. **验证门槛**：`JITPACK=true ./gradlew :shared:jvmTest` + `:androidApp:testDebugUnitTest` + `./gradlew -p server build` + `./scripts/ai-gate.sh` + 真机四场景遛狗（统计卡 / 照片网格 / 图文混排 / 纯文本闲聊）。

「完成」= US-1~7 全过 + 门槛全绿 + check_doc_sync 门禁绿。

## 8. 明确不做（本期边界）

- 不训练/微调模型（D8；LoRA 远期项，失败/修正对数据集自本方案校验回路开始积累）
- 不动 A2UI 线任何文件（D7）；不做 A2UI 线删除
- 不动本地链路（draw_chart/render_html 现状维持）；不动 iOS（/ios-follow 管线另行消化）
- 不做 reasoning 追溯条（M3 独立 spec）；不开启 thinking
- 不做历史投影的程序折叠规则（M2；M1 先记录 token 观测数据）
- 不做 Mutation/@Set 增量更新、@Each、流式增量解析（OpenUI spec 既有不做口径继承）
- 任务卡（TaskCard）、浏览器直播卡（BrowserLive）组件化不在本期（宿主组件注册机制留作后续）
- 不改 parts 协议、不新增 Room 表/迁移（D1 继承）
- Markdown 渲染器（ADR-016 ChatGPT AST 渲染）不删除——降级兜底与历史 text 消息仍需

## 9. 分期与任务标记

单期 M1 可独立合 main；M2/M3 后续独立 spec。

### M1 任务 [agent-task:openui-turn-native-m1]

- **Scope**：`server/agent/`（prompt 契约 / ProgramGuard / content 帧透传 / 兜底包装 / normalizer 迁移）+ `shared/domain/chat/openui/`（Html 组件目录注册）+ `androidApp`（TurnRunner message 解析 / 覆盖式预览 / Html 沙箱叶子渲染 / ChatViewModel 接线）+ 设置页文案（开关语义说明五语）
- **Expected Change**：① `genui_format=openui` 时 ToolRegistry 无 GenUI 工具；② system prompt = OpenUI 母语契约（含 §3.4 顺序约定与数据禁令）；③ content delta 帧 → openui_delta 透传；④ ProgramGuard 校验回环 + 兜底包装；⑤ 端上 message→OpenUiCard 解析 + 覆盖式预览 + Html 组件渲染
- **Priority**：P0
- **Acceptance**：US-1~7

## 10. 交付沉淀（DoD）

- 代码合 main 前：`CHAT_CARD_CATALOG.md` 登记 Html 组件与「回合原生」协议变更（三要素 + 端到端例子）；`CHAT_PROTOCOL_TECH_SPEC.md` 更新请求/回复协议实况；`server/AGENTS.md` 更新工具清单与策略层说明；`shared/AGENTS.md` §2 补 Html 组件一句。
- 本 spec 交付后按沉淀管线删除；选型类结论（甲 vs 乙、A2UI 去留触发条件）收编 ADR。
- 门禁：ai-gate 全绿（含 check_doc_sync）。

## 11. 补充说明

- **乙方案回退预案**：若 M1 实施中 content 帧通道被 Koog 证死（首日验证项失败且无绕行），回退到「工具即出口」（保留 render_ui_openui 作为唯一必须最终动作）——工具描述、校验器、normalizer、P2 通道全部资产平移，仅 §3.1/§3.2 回滚，其余决策不变。
- **GPT-6 差距台账**（观测用，不行动）：设计判断力（训练 vs 规约）、边思考边回答（模型能力 vs 编排近似）、API 可用性（OpenAI 未开放 vs 我们全链路自有）。LoRA 微调是追平第一差距的候选路径。
- **token 经济学**：DSL 教学从工具描述迁入 system prompt 为平移；程序本体取代「文本 + 工具调用 JSON」为净省（免 JSON 转义层）；历史投影膨胀是 M2 观测项。
