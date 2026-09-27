# ADR-016: Chat 消息内容模型与渲染架构——Vercel parts 协议 + ChatGPT 渲染 + tokens SSOT

**状态**: 已定稿（宪法级：Chat 域消息模型/流式管线/渲染架构的一切新增与改动，均须以本 ADR 及其实施 spec 为上位约束；偏离须先修订本 ADR）
**日期**: 2026-09-25（原 ADR-014 方案 B 选定）→ 2026-09-27（parts 主流对齐定稿；**整合吸收 ADR-014 全部仍有效决策，编号 014 永久留空**）
**决策**: 用户（2026-09-25 方案 B 选定、D1 措辞/D2 呈现/D5 约束框架逐节认可；2026-09-27 三方组合选型逐条确认，JS 桥/消息树明确排除；2026-09-27 指示两篇整合）
**依赖**: ADR-008（隐私红线）、ADR-011（ui-driver 测试体系）、ADR-013（KMP 契约）、[PARITY] 红线
**实施 spec**: `docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md`（parts 模型/流式/状态机/渲染，M1~M5 分期）；HTML 卡实现 SSOT：`JS_ENGINE_TECH_SPEC.md` §7/§7.1；双形态 + 任务卡 HTML 化：`2026-09-26-html-card-two-tier-design.md`

---

## 1. 背景（两轮调研）

### 1.1 渲染形态调研（2026-09-25/26，原 ADR-014）

Chat 是主入口（2026-08 产品重心迁移后），两类渲染诉求：**(a) LLM 富输出表达力**（markdown/表格/代码高亮）；**(b) Agent 生成 UI**（tool_call 产出富卡片，不发版长出新消息形态）。讨论过三方案：A 原生增强 / B 混合富卡片 / C 全量 HTML 会话。市场结论：

- **无头部 App 用 WebView 渲染正文**：ChatGPT 移动端为双端原生（Android Kotlin+Compose / iOS Swift+SwiftUI；2026-09-26 勘误——原「RN 管线」系与 web 端 react-markdown 栈混淆），web 端走 react-markdown 式组件管线（流式不完整 markdown 的增量解析是专门课题）；Android 原生阵营走 Markwon 式管线
- **头部 App 全员收敛到「原生聊天流 + 沙箱 WebView 卡片/独立面板」**：Claude Artifacts、ChatGPT Apps widgets、微信小程序卡片、电商客服 H5 卡
- **C（全量 HTML 会话）无市场先例**

### 1.2 数据/协议调研（2026-09-27）

评估「回复整体框架为 markdown、markdown 内嵌富媒体卡片」提案时，对主流方案做的一轮联网调研（Vercel AI SDK / OpenAI Responses & ChatGPT 内部结构 / Anthropic content blocks / Gemini parts / LangChain / AG-UI / MCP Apps）。要点：

- **数据层殊途同归到「有序 block/part 序列」**：没有一家用「多条独立 assistant 消息交错」表达一次回复，也没有一家用「markdown 文内锚点」定位卡片——**数组顺序即锚点**，关联靠 `tool_use_id`/`toolCallId`/`call_id`
- **Vercel 亲自投过票**：v4.2 从「content string + 旁路 toolInvocations」迁到 parts 数组，官方动机原文即「交错内容保序困难 + 连续多条 assistant 消息」；v5 删除 content string，parts 成唯一载体；UIMessage（渲染层富文档，落库 SSOT）与 ModelMessage（喂 LLM 的精简形态）双层分离
- **渲染层共识：卡片是序列中的独立 block，永不嵌进文本流**；ChatGPT widget 旁挂于工具调用位置（sandboxed iframe），正文 markdown 无组件标记；OpenAI 的 Canvas 侧栏面板方案在 2026-05 回撤为「聊天内联块 + 全屏编辑器」——与双形态 inline/fullpage 殊途同归
- **卡片生命周期 = tool call 生命周期**：Vercel 工具状态机（input-streaming → input-available → output-available / output-error，v6 加审批态）与 CopilotKit render 三态同构；「卡片未完成时渲染什么」是契约的一部分
- **MCP Apps 印证沙箱卡路线**：widget = tool `_meta` 指向的 HTML resource + sandboxed iframe + postMessage 桥

## 2. 决策

### D1 数据/协议层：对齐 Vercel AI SDK parts 模型

- 一条回复 = 一条消息 = **有序 parts 数组**（`MessagePart` sealed：`Text`/`Chart`/`HtmlCard`/`TaskCard`/`MediaResults`/`Image` 等），取代 13 值 `ChatMessageType` 平铺单类型 payload
- 流式对齐 **chunk 三段式语义**（start/delta/end，块级 id）——对齐的是事件语义，不是 SSE 线协议（传输仍走 Koog 内部循环）
- 工具调用状态机对齐 Vercel 四态 + 审批态；工程师任务卡五态（RUNNING/AWAITING_CONTINUE/AWAITING_DELIVER/COMPLETED/FAILED）映射为该状态机的渲染投影
- **双层分离**：渲染/持久层保存富 parts 文档（防信息丢失）；回灌 LLM 时显式转换（工具结果拆为 tool 角色消息、UI 专有 part 不进上下文）——对应 Vercel UIMessage↔ModelMessage

### D2 渲染层：对齐 ChatGPT——markdown AST → 原生受控组件 + 卡片独立 block

- 正文渲染从「regex 分段 + compose-markdown 0.5.4」升格为 **markdown AST → 原生受控组件**管线（ChatGPT web 端 react-markdown 组件管线的原生对等物）；替换的是渲染库不是 markdown 格式——LLM 输出仍以 markdown 为准。选型硬指标：不完整 markdown 增量解析 / 表格 / 代码高亮 / 白名单内联 HTML（下划线/高亮/上标级，GitHub README 模式）
- **WebView 永不渲染聊天正文**（流式性能/文本选择/a11y/PERF 红线均不支持）
- 卡片 = **parts 文档内独立 block**（独立渲染单元、不拆多 WebView）——「一回复一 parts 文档」取代原「一消息一卡、多元素靠消息序列」；渲染隔离的立法本意不变
- **HTML 卡呈现形态（已落地，2026-09-25~26 决策）**：**双形态**——Inline（卡片内直接交互 + 动态测高 + 完全撑开，短卡默认）/ Fullpage（长卡固定高预览 + 底部渐隐，点击进全屏查看器）；分流终判随消息 metadata 持久化，形态不跳变；`<a>` 外链一律全屏落地页（`HtmlLinkPreviewOverlay`）
- **Turn 聚合渲染：做**。一个回合（user 消息到下一 user 消息之间）的 parts 视觉聚合为连续文档流，去掉逐消息气泡边界——对齐 ChatGPT「数据层多 item、呈现层单 turn 文档」的聚合视图
- 图表维持现状（QuickJS 端侧 SVG → androidsvg 栅格化静态位图）；Chart.js 交互化为可选后续（迁入 artifact 容器），现有 SVG 卡保留为降级路径

### D3 沙箱安全基线（ADR-008 红线落地；原 ADR-014 D3，编号不变）

- **媒体不可经 WebView 外泄**：远程 script/iframe/object/embed/form/meta refresh 一律剔除；远程 img/CSS/`<a>` href 放行（2026-09-25 表现力优先决策；收紧点在 `HtmlCardSanitizer` 与 `shouldInterceptRequest`，可随时回退严格模式）
- 出站测高 JS 允许（ResizeObserver），**入站指令桥为零**（零 JS 桥红线，承双形态 spec §15）
- WebView 禁通用 file 访问/DOM Storage；`content://` 媒体经 WebViewAssetLoader 白名单注入
- 端侧 HTML 清洗与风格白名单是**同一条管线**（见 D5 防线 2）：安全与风格一鱼两吃

### D4 iOS 同构（不推翻 ADR-013；原 ADR-014 D4，编号不变）

- HTML 模板与 JSON→HTML 组装器（纯 Kotlin、JVM 可测）可入 `commonMain`；WebView/WKWebView 容器是平台实现，各端自理
- parts 模型经 shared commonMain 直接可得；markdown AST 解析层可入 commonMain，SwiftUI 渲染各端自绘
- iOS 批次①② SwiftUI 原生成果不动；HTML 卡双形态 iOS 现为零实现（gap 最大），走 /ios-follow 管线一次补齐

### D5 样式层：样式权威收归 app——tokens SSOT + L1/L2/L3 分级 + 四道防线（原 ADR-014 D5，编号不变）

原则：**样式权威收归 app，LLM 只交结构/数据**（参照 email 客户端/GitHub README 的「作者交结构、阅读器出样式」模式；Claude Artifacts 为反例——故意放权换创造性，与本 App 目标相反）。`design-tokens.json` → codegen 双端镜像的现有体系即样式权威（与 ChatGPT 的 `@openai/apps-sdk-ui` 组件样式对齐容器策略同构但自有）。

**铁律：LLM 永不产代码只产数据**；交互能力 = app 预注入的受控组件库，L2 HTML 只能声明式引用。

**自由度分级**：

| 级别 | LLM 交什么 | 可控度 | 定位 |
|---|---|---|---|
| **L1 模板+JSON** | 模板 ID + 数据 | 100%（模板 CSS app 管控） | 默认路径，覆盖常见形态（报告/统计卡/时间线/九宫格） |
| **L2 受控 HTML** | 语义 HTML 片段（无样式） | 结构自由、样式受注入约束 | 表达力档，「不发版上新形态」的兑现层 |
| **L3 自由 HTML** | 任意 | 无 | 仅开发者调试，永不对 LLM 开放 |

**四道防线**（针对 L2，纵深防御）：

1. **注入式 CSS**（最强）：容器渲染时注入 reset + 组件类词表 + tokens 导出的 CSS variables；禁 inline style / `<style>` 块；字体走本地 font-family 栈；Light/Dark = 变量组切换
2. **白名单清洗**（执法，不信 prompt）：标签→属性→CSS 属性三级白名单，剥 script/外链/`position:fixed`；纯 JVM 可单测；与 D3 安全是同一条管线
3. **Prompt 契约**（引导）：tool description 写明样式契约与 class 词表——只引导不执法，必须叠在清洗之上
4. **回归防线**：模板画廊页（全模板 × 样例数据 × 双主题）跑 `screenshot-diff.py` 基线对比

**token→CSS 变量源**：Ardot `export_variables` 已支持 css 格式（`--name: value` + `[data-theme]` 块），纳入 token sync 流程即为 L1/L2 的变量源，无需新建管线。

### D6 明确不做（原 ADR-014 D6 + 原 016 D4 合并）

- **全量 HTML 会话**：无先例，滚动/输入/键盘/a11y 全量过桥的手感税不可控
- **自研 DSL→native 卡片引擎**（蚂蚁动态卡片/Telegram Instant View 路线）：体量不匹配
- **文内锚点**（`{{card:id}}` 占位符）：主流无一采用，数组顺序即锚点；失败模式（忘写/写错/流式截断）整体避免
- **JS 桥**（window.openai 式卡内指令通道）：polang 卡片是第一方 LLM 产物，零 JS 桥红线不动
- **消息树**（编辑/重生成/分支）：当前无产品需求；消息模型预留 `parentId` 扩展点即可，不实施
- **正文嵌渲染级 HTML**：正文流检测到块级 HTML → 剥离或降级代码块，不允许半渲染
- **任务中心列表 HTML 化**（双形态 spec §15 已否）

## 3. 对 ADR-014 的整合记录（2026-09-27）

本 ADR 由原 ADR-014（2026-09-25，Chat 富内容渲染·方案 B）与原 ADR-016（2026-09-27，parts 主流对齐）合并而来。合并映射：

| 原 ADR-014 | 去向 |
|---|---|
| D1 正文原生富渲染 | → 本 D2（选型随实施 spec §7.1 spike，硬指标不变） |
| D2 `RICH_HTML` 消息类型 + 一消息一卡 | 数据模型被 parts 取代（本 D1/D2）；沙箱卡、一卡一份完整 HTML 文档、不拆多 WebView 的本意保留 |
| D3 沙箱安全基线 | → 本 D3（编号不变；按落地态更新外链政策表述） |
| D4 iOS 同构 | → 本 D4（编号不变） |
| D5 L1/L2 分级 + 四道防线 | → 本 D5（编号不变；并入 tokens SSOT 与「LLM 永不产代码」铁律） |
| D6 明确不做 | → 本 D6 合并清单 |
| 顶部三段修订注记（落地形态差异/外链放开/双形态 H1） | 决策级结论收编进本 D2/D3；实现细节归双形态 spec + `JS_ENGINE_TECH_SPEC.md` §7.1 |
| §4 开放问题 18 条 | 大多随 H1（双形态）/H2（任务卡 HTML 化）落地裁决；余量由 `JS_ENGINE_TECH_SPEC.md` §7 与双形态 spec 持续收敛，ADR 不再维护清单 |

ADR-014 文件已删除，编号永久留空不复用（循 004/006/009/010 先例），历史靠 git 追溯。现存文档中「ADR-014 D3/D4/D5」引用语义等价迁移为「ADR-016 D3/D4/D5」。

## 4. 性能约束（决策级，spec 必须遵守）

- **渲染时必须把 parts 拍平为 LazyColumn 独立 item**（段级 key + contentType），禁止「一条回复一个巨型 item」——否则回收粒度、重组隔离、测高防抖全面劣化（2026-09-27 性能梳理结论）
- HTML 卡既有防抖三件套（测高 LruCache / 滑动冻结 / 抖动阈值）、双形态分流、零桥沙箱**原样保留**
- 顺车修复现状热点：消息模型 `@Immutable`、LazyColumn `contentType`、流式重组放大
- 预热/常驻 WebView 的内存预算须中端机实测定；JS 动画 onPause 冻结

## 5. 后果

- ✅ 消息模型扩展性（新内容形态 = 新 part 类型，不动 schema）；与 Vercel/Anthropic 生态概念互通（文档/示例可直接引用）；任务卡状态机获得主流契约背书；正文渲染管线换代；(a)(b) 两类诉求均按市场验证形态落地
- ✅ 沙箱面收敛在卡片/查看器内，正文全原生（流式/文本选择/a11y 零妥协）
- ⚠️ 代价：Room schema 迁移（迁移失败降级 Text 兜底，不允许丢消息）、流式管线改造、markdown 渲染器选型 spike、双端 parity 重做一轮（iOS 走 ios-follow）
- ⚠️ 持续维护面：清洗器白名单纪律（绕过样例单测覆盖）、模板库与 class 词表随形态演进（画廊回归基线防漂移）、ui-driver 对 web 内容的 a11y 适配（ADR-011 体系内扩展）
- ⚠️ 远程 LLM 产出 HTML = 新攻击面：清洗 + 禁 script + 模板白名单三道闸，实现 spec 必须含安全测试
- 回退：各里程碑独立可回退（见 spec §10 分期与 §13 回退）

## 6. 相关

- 依赖：ADR-008（隐私红线）、ADR-011（ui-driver）、ADR-013（KMP 契约）
- 调研存档：`docs/reviews/2026-09-26-chat-rendering-framework-research.md`（四家系统调研 + ChatGPT「RN」勘误）、`docs/reviews/2026-09-25-meta-muse-feature-research.md`（任务卡范式来源）
- 任务卡产品线：`2026-09-25-engineer-task-card-design.md`（五态状态机）· UserTask 协议 2026-09-26
