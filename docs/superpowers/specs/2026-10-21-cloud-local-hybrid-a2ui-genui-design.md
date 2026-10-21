# 云端编排 × 端侧执行 × A2UI GenUI 混合架构 — 设计 Spec

> **日期**：2026-10-21
> **状态**：已定稿待评审
> **上位约束**：ADR-016（Chat parts 模型与渲染宪法）/ ADR-017（推理后端域）/ ADR-018（云端 Agent 会话状态：工具桥、事件溯源）；[PRIVACY] / [PARITY] / [I18N] 红线
> **前置交付**：云端 Agent M1~M3（工具桥已上生产，`2026-10-17-cloud-agent-tool-bridge-design.md`）；chat parts M1~M5 已合 main
> **对齐记录**：2026-10-21 会话内逐项确认——① parts 协议零改动，本地性全部压进 tool-result 内容投影；② 出端只过 projectionSchema 白名单投影，全量靠 `local://` handle 端上补全；③ GenUI 选型 A2UI（弃 OpenUI——其为开发期原型工具，非运行期协议）；④ L1 = A2UI 组件卡（首选），L2 = 现有 HTML 沙箱卡（长尾兜底）；⑤ 分期 M1 投影通路 → M2 A2UI 槽位填充 → M3 沙箱卡交互态

---

## 1. 问题陈述

云端 Agent（Koog JVM，已上生产）承担编排；但本地任务（相册扫描、媒体处理等）必须在端上执行，且**执行结果不想完整泄露给云端**（[PRIVACY]：媒体处理 100% 端侧，文本/元数据/摘要可走远程）。两端的结果又要**混合后在端上同一消息流里渲染**。

需要回答三个问题：云端如何在「看不到全量数据」的前提下继续推理？端上如何把本地结果与云端流无缝拼进同一 Turn？GenUI（Agent 生成界面）如何接入而不戳破隐私闸？

## 2. 方案概述

**一句话：协议不动、编排在云、执行在端、出端只过投影、全量靠 handle 本地补全、GenUI 只生成骨架不生成数据。**

Vercel parts 协议（ADR-016）零改动——所有新语义用约定（convention）表达，不新增 part type：

```
┌─ 云端（Koog Agent）─────────────────────────────
│  推理 → text/reasoning parts                    ← 内容
│  意图 → tool-call parts（executor=local 工具）   ← 调度
│  表达 → data-ui parts（A2UI JSON 骨架+绑定槽位） ← GenUI
│  可见的：projection（白名单摘要）+ 模板 +  opaque handle
├─ 端上（工具桥 + HandleResolver + A2UI 渲染器）───
│  执行 local tool → full_result 只落 Room         │
│  裁剪 projection → tool-result 回传云端          │
│  解析模板绑定路径 → 本地全量数据填充渲染           │
│  L1 = A2UI 卡（原生组件）/ L2 = HTML 沙箱卡      │
├─ 协议平面（Vercel parts，不变）──────────────────
│  text / reasoning / tool-call / tool-result /    │
│  data-*（含 data-ui、data-local-ref）            │
└─────────────────────────────────────────────────
```

关键性质：**云端 LLM 对完整结果结构性无知**——它只见到工具的 projectionSchema，想泄露都无从引用；隐私保证是可审计的代码（投影函数），不是 prompt 约定。延续 ADR-015「LLM 管意图、代码管策略」。

## 3. 核心机制

### 3.1 Locality 静态声明

每个工具注册时声明三样（代码自有，非运行时判断）：

- `executor: cloud | local`
- `resultSchema`（完整结果）
- `projectionSchema`（出端白名单：摘要/计数/文本元数据等红线允许项）

云端 ToolRegistry 只暴露工具名 + 参数 schema + projectionSchema。投影由**集中的投影函数**做结构化裁剪（单测断言「输出不含媒体引用」），不散落在各工具里。

### 3.2 `local://` Handle 体系

- 形式：`local://<domain>/<id>#<view-state>`。域 + id 定位本地数据；fragment 编码交互态（页码/筛选/勾选），使交互后可确定性重建
- handle 对云 opaque：云可存储、传递（保上下文连续性），但无法解析——只有端上 `HandleResolver` 认识它
- 双端不变式：**同一 part id 在两轨（本地 Room / 云端事件流）中指向同一逻辑内容**，只是详略不同

### 3.3 GenUI = A2UI（已定选型）

A2UI（Google，v0.9）：Agent 发送声明式 JSON 组件蓝图，客户端用原生组件渲染，**无任意代码执行**。与本设计逐项命中：

| 本设计 | A2UI |
|--------|------|
| L1 组件卡：目录选卡 + 填 props | catalog 模型：Agent 只能用注册组件，属性过严格 schema 校验 |
| CHAT_CARD_CATALOG 作 SSOT | 映射为 A2UI catalog 定义；A2UI 只是**线格式**，目录抽象仍是自己的（防 v0.9→1.0 演进冲击，换序列化器即可） |
| 模板 + 槽位 + `local://` 填充 | UI 树与数据模型原生分离，属性经绑定路径引用数据模型——「数据留端、路径上云」的协议化版本 |
| 沙箱安全红线 | 纯声明式无代码执行，生成 UI 不能成为外泄通道 |
| [PARITY] 双端一致 | 同一 JSON 蓝图双端各自原生渲染 |
| 无障碍零容差 | v0.9 预留 Accessibility Tree + Agent-readable 元数据层 |

**渲染器**：Android 用官方 `androidx.a2ui` Compose 渲染器（已支持 v0.9.1）；**iOS 无官方 SwiftUI 渲染器，按 v0.9 规范自研**（首期只实现目录内组件，工作量随 catalog 大小可控）——这是本方案最大的 [PARITY] 账。自研的好处：渲染语义全自控，接 design token SSOT 反而干净。

**两级卡分工**：

- **L1 = A2UI 卡（首选）**：云端 Koog 产出 A2UI JSON 作为 data part payload → 端上原生渲染；数据字段用绑定路径，HandleResolver 解析到本地全量
- **L2 = 现有 HTML 沙箱卡（长尾兜底）**：A2UI 目录表达不了的自由可视化才走，QuickJS/WebView 沙箱 + Inline/Fullpage 双形态不变
- 原则：**能用 L1 不用 L2**；每张 L2 卡过专项安全审查

### 3.4 强制不变式

> **GenUI 沙箱/卡片的任何外向通道（capability.dispatch、日志、错误上报）只许携带投影级数据。**

L1 A2UI 卡因无代码执行天然满足；L2 沙箱卡网络默认关死、取数只走白名单 dispatch。本地渲染填充可以是全量（渲染不出端），外向通道必须过投影闸。违反时的表现是「功能正常」，最难发现——故写成不变式 + 审查清单，不靠自觉。

### 3.5 双轨落库与回放

- 本地 Room：完整 parts（含 data-local-ref 全量引用）
- 云端事件流（ADR-018 事件溯源）：投影后 parts + **模板 + opaque handle 可上云**（均不含数据）
- 回放：云端恢复骨架 + 模板，端上 HandleResolver 重解析填数据
- **新增降级态**：跨端恢复时目标端无该本地数据（照片在旧手机）→ 渲染降级为投影级占位卡（"327 张，数据在原设备"），需设计稿覆盖
- 交互态：写 handle fragment 落本地 data part；云端只收 fact 事件（"用户交互过卡 X"），不收数据

## 4. 端到端时序（以「统计上周照片」为例）

1. 云端：text part（"我来扫一下"）+ tool-call part（`local_scan_photos`，partId=tc_1，状态 pending）
2. 端上：工具桥执行，进度事件刷 tc_1 状态保活流；完成后 full_result 落 Room，projection（count/时间分布/重复组数）作 tool-result 回传
3. 云端续推：text part（"上周 327 张，重复 12 组"）+ data-ui part（A2UI 图表卡，series 绑定 `local://scan/7f3a#histogram`）
4. 端上渲染：同一 Turn 聚合 text + tool-result + 图表卡；HandleResolver 取全量填 series
5. 用户翻页：交互态写 `local://scan/7f3a#view=page2` 落本地；云端收 fact 事件

时序保障：长任务靠 ADR-016 工具状态机 progress 保活；partId 幂等防重传；断线重放从本地 Room 补齐。

## 5. 用户故事

- **US-1（投影出端）**：用户让云端 Agent 扫描相册，云端只收到计数与摘要，抓包验证请求体无任何媒体文件与全量路径清单
- **US-2（混合渲染）**：同一条助手消息内，云端文本、工具结果卡、A2UI 图表卡按序渲染，用户无感来源差异
- **US-3（云端续推）**：云端拿到 projection 后能继续推理（"要不要帮你清理重复 12 组"），不需要全量数据
- **US-4（A2UI 原生卡）**：云端生成图表卡，Android Compose 原生渲染，样式走 design token，非 WebView
- **US-5（沙箱防外泄）**：L2 HTML 卡内代码尝试网络外发被沙箱拦截；dispatch 外向负载只含投影字段
- **US-6（跨端降级）**：换新手机登录，旧设备产生的数据卡渲染为投影级占位卡而非崩溃/空白
- **US-7（交互确定性）**：图表卡翻页后杀进程重进，卡恢复到 page2

## 6. 已定决策（防翻案）

| # | 决策 | 依据 |
|---|------|------|
| D1 | parts 协议零改动，新语义全走约定（data-* / partId / 状态机） | ADR-016 宪法地位；协议面稳定 > 表达便利 |
| D2 | 完整结果引用式（handle），不内联 | 消息历史同步上云时泄露面只有摘要 |
| D3 | 投影边界 = 工具注册的 projectionSchema，代码裁剪非 LLM 自觉 | 可审计；ADR-015 原则 |
| D4 | GenUI 选型 **A2UI**，OpenUI 出局 | OpenUI（wandb）是开发期原型工具（开源 v0 类），产出原始 HTML，非运行期协议；thesysdev 同名项目偏 Web 生态无原生端故事。A2UI 有官方 Compose 渲染器、catalog 安全模型、UI/数据分离原生支持 |
| D5 | L1 首选 A2UI 卡，L2 保留 HTML 沙箱卡兜底 | 表达力长尾 vs 安全面最小化的分工 |
| D6 | A2UI 只作线格式，包在自有 catalog 抽象后 | v0.9 演进期协议的隔离层；CHAT_CARD_CATALOG 仍是 SSOT |
| D7 | iOS 自研 SwiftUI A2UI 渲染器，首期只覆盖目录组件 | 官方无 SwiftUI 渲染器；[PARITY] 账显性化 |
| D8 | 编排在云，不搞全本地编排 | 与 ADR-017/018 已投产方向一致 |

## 7. 测试决策（接缝与完成标准）

接缝优先复用，不新建：

1. **投影函数**（纯函数接缝）：单测断言每个 local 工具的 projection 输出 schema 合规 + 不含媒体引用/全量路径（[PRIVACY] 门禁）
2. **桥协议**（M2 已有 SSE + tool-results 双通道）：投影回传、超时/断线/409 复用现有错误矩阵测试
3. **HandleResolver**（端上纯组件）：handle 解析、fragment 交互态重建（US-7）、缺失数据降级（US-6）单测
4. **A2UI 渲染器**：catalog 组件级截图测试（Android 官方渲染器冒烟 + iOS 自研渲染器逐组件对齐），校验非法组件/越界 schema 被拒
5. **沙箱外向通道**：L2 卡网络拦截 + dispatch 负载白名单断言（US-5）
6. **端到端**：§4 时序真机遛狗（云端编排 → 端执行 → 投影回传 → A2UI 卡渲染）

"完成" = US-1~7 全过 + [PRIVACY] 抓包验证 + check_doc_sync 门禁绿。

## 8. 明确不做（本期边界）

- 不改 Vercel parts 协议本身、不新增 part type（D1）
- 不做全本地编排 / 端侧 Agent 主循环（D8；端侧不自建 harness 立场不变）
- 不做 A2UI catalog 之外的自由组件生成（那是 L2 的职责，不进 L1）
- 不做 A2UI Agent-readable 元数据层的反向利用（Agent 读 UI 操作 UI 的多智能体场景，远期课题）
- OpenUI 不作为运行期方案进入任何环节（D4）
- iOS 完整跟随不在 M1/M2（M1/M2 Android-only；iOS SwiftUI 渲染器随 M3 或 /ios-follow 管线）
- GenUI 卡的设计稿体系（Ardot 帧）不在本 spec，随实施单独出

## 9. 分期与任务标记

- **M1：投影通路**（纯数据层，无 GenUI）——locality 声明 + projectionSchema + 投影函数集中化 + 单测门禁。先把隐私闸立起来，后续一切建立其上
- **M2：A2UI L1 卡槽位填充**——A2UI 线格式接入 data part、Android Compose 渲染器集成、HandleResolver + 绑定路径填充。GenUI 最小可用
- **M3：L2 沙箱卡交互态 + handle fragment + iOS 渲染器**——风险最大放最后，此时闸已立好

每步不破 parts 协议，可独立合 main。

### M1 任务 [agent-task:hybrid-m1-projection]

- **Scope**：`shared/` 工具注册层（locality + 双 schema 声明）+ 端侧投影函数集中点 + `server/agent/` DeviceTool 描述对齐投影 schema
- **Expected Change**：① 工具注册表加 executor/projectionSchema 字段；② 集中投影函数 + 每工具单测；③ 云端 LLM 可见描述只含投影 schema
- **Priority**：P0
- **Acceptance**：US-1、US-3

### M2 任务 [agent-task:hybrid-m2-a2ui]

- **Scope**：`shared/` A2UI 线格式封装（catalog 抽象层）+ `androidApp` `androidx.a2ui` 集成 + HandleResolver
- **Expected Change**：① data-ui part 承载 A2UI JSON；② HandleResolver（含 fragment）；③ Compose 渲染 + token 对接；④ 降级占位卡
- **Priority**：P1
- **Acceptance**：US-2、US-4、US-6、US-7

### M3 任务 [agent-task:hybrid-m3-sandbox-ios]

- **Scope**：L2 沙箱卡审查清单落地 + 交互态持久化 + iOS SwiftUI A2UI 渲染器（目录组件子集）
- **Expected Change**：① 沙箱外向通道断言；② fragment 落 Room；③ iOS 渲染器 + 双端对齐截图
- **Priority**：P2
- **Acceptance**：US-5 + [PARITY] 对齐

## 10. 补充说明

- **[I18N] 新摩擦点**：云端生成的卡内文案本质是「动态硬编码」。约定：L1 卡静态 label 一律传 string key 由端上查表；动态内容（LLM 写的句子）豁免
- **风险排序**：L2 沙箱外泄（安全，最难发现）> projectionSchema 漏字段（单测可防）> A2UI v0.9 演进漂移（D6 隔离层可防）> 跨端降级 UX（需设计稿）
- **生态观察**：A2UI 与 AG-UI 官方表态「不同但配合好」；国内高德+千问基于 A2UI 开源 AGenUI；CopilotKit GenUI 示例支持 A2UI——选型不孤立
- **OpenUI 的正当用途**（仅记录，不行动）：若未来要「设计师用自然语言调卡片样式」的内部工具，wandb/openui 可单独评估，与运行期架构无关
