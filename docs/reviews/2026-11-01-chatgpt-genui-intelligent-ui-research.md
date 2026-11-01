# ChatGPT 5→6→GenUI 与 Intelligent UI 技术方案调研（快照）

> **类型**：结论性调研快照（docs/reviews，登记准入，不随代码维护）
> **日期**：2026-11-01
> **动机**：为 Chat 域渲染架构（ADR-016 parts 模型、HTML 卡双形态、任务卡交互回联）提供行业对标
> **关键来源**：
> - [OpenUI: How ChatGPT's Intelligent UI works（2026-10 逆向工程，决定性证据）](https://www.openui.com/blog/how-chatgpt-intelligent-ui-works)
> - [Google Research: Generative UI（2025-11-18，Gemini 3 官宣）](https://research.google/blog/generative-ui-a-rich-custom-visual-interactive-user-experience-for-any-prompt/)
> - [MCP Blog: MCP Apps 官方扩展（2026-01-26）](https://blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps/)
> - [azukiazusa: A2UI 协议解析（2026-04）](https://azukiazusa.dev/en/blog/a2ui-protocol)
> - [RohitAI: GPT-6 Intelligent UI rollout](https://rohitai.com/blog/openai-gpt-6-chatgpt-intelligent-ui-rollout)、[TechInKey 解读](https://techinkey.com/gpt-6-intelligent-ui-explained/)
> - [mer.vin: Generative UI 2026 模式综述](https://mer.vin/2026/06/generative-ui-in-2026-controlled-a2ui-declarative-and-open-ended-patterns-on-ag-ui/)

---

## 1. 演进时间线

| 时间 | 里程碑 | UI 形态 |
|------|--------|---------|
| 2025-08 | GPT-5 发布（统一模型 + router） | 文本/Markdown 为主，Canvas、Agent Mode 并行 |
| 2025-10 | OpenAI DevDay：Apps SDK | 第三方 App 以 React widget（sandboxed iframe）嵌入对话，基于 MCP |
| 2025-11-18 | **Google Gemini 3 + Generative UI 官宣** | Dynamic View / Visual Layout 实验 + Search AI Mode 生成式布局 |
| 2026-01-26 | **MCP Apps 成为 MCP 首个官方扩展**（合并 Apps SDK 与 MCP-UI） | `ui://` 资源 + iframe + postMessage JSON-RPC |
| 2026-02~03 | GPT-5/5.1 从 ChatGPT 退休（5.3~5.5 过渡） | — |
| 2026-04 | **Google A2UI v0.9 公开** | JSON 组件声明 → 客户端原生渲染 |
| 2026-10-07 | **GPT-6 + Intelligent UI 上线**（付费 Sol / 免费 Luna；Pro 推理档 Astra 不支持） | 模型直接产出可交互界面 |

术语分野：**GenUI（Generative UI）是 Google 提出的范式概念；Intelligent UI 是 OpenAI 在 GPT-6 上对同一范式的产品化回答**。两者技术路线截然不同。

## 2. GPT-6 Intelligent UI 真实架构（逆向工程结论）

> ⚠️ 修正记录：早期二手报道（RohitAI/TechInKey）将其描述为「预置原生组件库 + 实时编译」，易误读为「模型从目录挑预制件」。OpenUI 逆向（会话导出 + SSE 抓包 + runner/worker 源码 + 逐字节重实现服务端编译器）证明：**模型是在写一门带逻辑的 DSL 程序**，原生组件只是渲染层底座。

五层管线：

1. **推理格式 DIL**：Markdown（散文）+ JSX 风格标签（组件）+ JavaScript（状态与逻辑）混合。含 `DIL.useState`、派生计算、`{#if}/{#each}`、`{expression}` 插值、事件绑定、`GenUI` 动作。专为流式生成设计：语句独立成行、未闭合标签可自动补全，服务端可在「最后完整构造」处截断并编译。
2. **服务端编译**：DIL → JS 程序 + JSON 数据文档（`model_dil_v2`）。要点：标记转 `__dil.jsx()` 调用；表达式包 `__dilSafe`（单点报错不炸整页）；**静态文本抽 constants 表**（流式增长只改数据不改程序）；state 加稳定 key（跨重编译保活）；**对照 catalog 校验属性**（非法属性剔除 + 记诊断，实测抓到 `unknown_prop`/`invalid_literal`）。
3. **客户端沙箱运行时**：模型代码不进页面——隐藏 iframe（`allow-scripts` + `default-src 'none'`）内 Web Worker，lockdown（删网络/定时器/动态 eval、冻结全局）后 `new Function` 求值，看门狗超时隔离。React 风格 reconciler，diff 后输出**二进制 UI 操作流**（CREATE/SET/PLACE；函数仅传 `fn#N` 标识符）。沙箱兼容 Hermes（React Native JS 引擎）全局对象 → 移动端大概率同一运行时 + 各自原生渲染器。
4. **原生渲染**：页面只接受已知组件类型的操作，应用到自己设计系统的组件树；模型无法注入任意标记/样式。**交互零模型调用**：用户操作 → handler id 回 worker → 本地重渲染 → 增量操作回页面。
5. **Catalog**：约 70 个注册组件（实测出现 39 个）+ design tokens + OpenAI 用 DIL 预写的组合组件（模型只会用、不会自定义）。

流式机制：SSE + JSON-Patch 风格增量，同时 patch 原始 DIL 与编译产物。服务端**不做增量编译**——每 ~300ms 全量重编译重发（实测一个 6.3KB 响应的 275KB patch 中 **83% 是重发程序**）。

数据不经模型转抄：工具结果由服务端填充，模型只写引用（`{turn448791product0.displayed_price}`）；`<AsyncImage query="..."/>` 标签闭合后服务端跑图搜再 patch URL。屏幕上的价格来自搜索结果本身——防抄错/防幻觉。

**AppBlock 逃生舱**：catalog 画不了的（如 Web Audio 鼓机），模型直接写自包含 HTML/CSS/JS，编译器不翻译，客户端抠出后在独立域名双重 iframe（复用 Apps SDK 的 Skybridge host）渲染；host 注入 Tailwind base + `--viz-*` 主题变量维持观感统一。

**定位修正**：Intelligent UI = **声明式为主 + 开放式逃生舱**的混合架构。主路表达力强于 A2UI（有真实 JS 逻辑/状态），可控性强于 Gemini 自由 HTML（catalog 白名单 + 属性校验 + 原生渲染）。

逆向暴露的粗糙边缘（对自研有直接参考价值）：

- 流式带宽浪费（83% 重发代码）；交互操作流冗余（一次点击 158 个操作仅 4 个有效）
- reload 丢 UI 状态；**模型看不到 UI 状态**（唯一回联通道是 `issueNewTurn` 发新用户消息）
- 编译诊断疑似不回灌模型（同一错误反复犯）
- 布局固定列数/像素宽，未用断点 hooks；AppBlock 完全隔离、无回联

## 3. Google GenUI 技术方案（对立路线）

Gemini 3 Pro + 三个附加件：**Tool access**（图生/搜索，结果可直达浏览器）、**精心设计的系统指令**（目标/规划/示例/格式规范/避错）、**Post-processing**。输出物是**完整 HTML/CSS/JS** 直达浏览器。评测（PAGEN 数据集）：人类偏好仅次于专家设计，显著优于 Markdown/纯文本。承认的缺点：**生成可超一分钟、偶发错误**——自由度换延迟与可靠性。

## 4. 另外两条路线

**MCP Apps（嵌入式 Widget 标准）**：工具声明 `_meta.ui.resourceUri` → `ui://` 打包 HTML/JS → sandboxed iframe + postMessage JSON-RPC；`App` API 支持 `ontoolresult` / `callServerTool` / **`updateModelContext`（UI 操作回写模型上下文）**。安全四层：iframe 沙箱、模板预审、消息可审计、UI 发起的工具调用需用户同意。客户端：Claude/ChatGPT/Goose/VS Code。

**A2UI（声明式 JSON）**：Agent 输出纯 JSON 组件声明（无可执行代码），从预审批 **Catalog**（JSON Schema + 给 LLM 看的使用规范）选组件；消息 `createSurface/updateDataModel/updateComponents/deleteSurface`，**JSONL 流式**；组件**扁平 id 引用**（LLM 难流式生成深层嵌套）；数据绑定用 JSON Pointer；客户端映射原生 widget。工程要点：LLM 输出必须经「生成 → schema 校验 → 失败回灌重生成」闭环。

## 5. 四路线对比

| 路线 | 代表 | 模型输出物 | 渲染 | 表达力 | 可控性 | 延迟 |
|------|------|-----------|------|--------|--------|------|
| DSL 程序 + 原生渲染 | **GPT-6 Intelligent UI** | DIL（MD+JSX+JS） | 沙箱 worker → 原生组件 | 高（带逻辑） | 高（catalog 校验） | 快（流式 patch） |
| 声明式 JSON | A2UI / CopilotKit | JSONL 组件树 | 客户端原生渲染器 | 中 | 高（白名单） | 快 |
| 自由代码生成 | Gemini Dynamic View / **AppBlock** | HTML/CSS/JS | iframe | 最高 | 需后处理兜底 | 慢（分钟级） |
| 嵌入式 Widget | MCP Apps / Apps SDK | 预置 HTML bundle（人工开发） | sandboxed iframe | 高 | 高（沙箱+审计） | 即时 |

协议栈分工：**MCP 连工具、A2A 连 agent、AG-UI 连前端事件流、A2UI 描述界面、MCP Apps 嵌入第三方应用**。

## 6. 对 polang 的可吸收设计

1. **静态文本抽 constants 表**：流式时文本增长只 patch 数据不动程序结构 → ADR-016 parts/chunk 流式可借鉴，降低 patch 体积。
2. **数据引用不经模型转抄**：模型写引用、服务端填值 → 图表卡/任务卡的数据回灌防幻觉范式。
3. **交互零模型调用的本地状态机**：slider/form 在沙箱内闭环 → 任务卡 radio/approval 交互的范本；回联模型只走一条显式通道（OpenAI 用 `issueNewTurn`，MCP Apps 用 `updateModelContext`）——对应任务卡 US-4~6 回联设计。
4. **受控主路 + 自由逃生舱的混合分流**被 OpenAI/Google 双侧验证 → 支持 HTML 卡双形态（Inline 受控 + Fullpage/AppBlock 式自由 HTML）的路线判断。
5. **反面教材**：全量重编译重发（83% 带宽浪费）、诊断不回灌模型、UI 状态不进上下文——自研时应避免。
6. **A2UI 扁平 id 引用**：若未来做原生 Compose 声明式卡，「LLM 难流式生成嵌套树 → 扁平化 + id 引用」是成熟答案。
