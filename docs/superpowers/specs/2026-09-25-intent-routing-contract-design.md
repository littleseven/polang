# 意图路由契约与意图路由器——chat 路由体系重构（Spec）

> **日期**: 2026-09-25
> **来源**: 「儿子的照片」无横滑卡片事故复盘（当日真机日志 + chat/llm_log 双库交叉定位，根因见 §1.1）。用户定方向：①「意图理解和路由存在问题，请系统性提出解决方案，不要单点处理」；②「专门搞一个意图理解和路由的 LLM 接口/模块」。
> **上游**: `AGENT_ARCHITECTURE.md` §2.4.4（意图理解）、`CAPABILITY_REGISTRY.md`（命令路由 SSOT）、ADR-008（隐私红线）、ADR-014（RENDER_RICH_HTML 渲染基线，已合 main）、`shared/.../intent/IntentGuard.kt`（既有确定性守卫层先例）
> **状态**: 方向已认可，评审意见已回写，待实施
> **评审**: 2026-09-25 GLM 交叉审查（对照代码实证），决议已回写：串行路由加本地信号门控 + 1.5s 硬超时 + 失败分类降级（§3.2）；M1 护栏触发条件与 ids→asset 水合写明（§3.5）；search_media 诊断修正为「@Tool 未透传引擎既有 person 能力」（§1.2/§3.4）；幻觉拦截改结构性信号、不做文本匹配（§3.5-b）；M2 验收去循环论证（§6）；行号/工具数勘误（§1.2）

---

## 1. 问题陈述

### 1.1 事故（2026-09-25，app v1.0.39，Koog 1.3.0 构建）

用户问「看下我儿子的照片」，得到纯文本回复「已经把前 100 张展示在卡片里了 👆」，但横滑卡片（`MediaResultsCarousel`）从未出现。证据链（`polang_llm_log.db` + `picme_database` 交叉核验）：

```
17:17:44  query_person_relation    → 「大宝 = 儿子」✓（意图理解正确）
17:17:44  run_gallery_script       → gallery.query({person:'大宝'}) → 263 个 id（无 UI）
17:17:47  回复「已展示在卡片里」     ← 幻觉（该回复前无任何 media_results 消息）
17:18:04  refine_media_search 3ms   ← 级联失败：脚本路径未更新搜索基数，refine 空转
19:40:20  view_media ×5 全部被拒    ← scene mismatch (CHAT≠GALLERY)，id 为编造负数
19:40:22  回复「已为您打开照片列表」  ← 二次幻觉
```

历史对照：08-27 / 08-29 / 09-03 / 09-08 同类查询全部正常出卡片（`search_media` 路径）；当日 20:33 复现亦正常。**间歇性、非确定性**。

排除项（均有证据）：Koog 1.3.0 升级（同构建 20:33 复现成功）、IntentGuard 重构（golden 逐字节校验）、模型更换（deepseek-v4-flash 自 08-19 未变）、横滑卡片去重修复 c4cea4995。

### 1.2 缺陷分类（从个例泛化）

| # | 系统性缺陷 | 实证 |
|---|---|---|
| D1 | 路由规则无单一事实源，手写规则互斥且无机器校验 | `ChatPromptRules.kt` L41「人物∩时间必须 gallery.query」vs L62「女儿的照片用 search_media」vs L133 再强化前者 |
| D2 | 意图→UI 契约缺失：模型不知道哪些工具产 UI，无 artifact 反馈回路 | 脚本拿 263 ids 后谎称「已展示」；view_media 全败后仍称「已打开」 |
| D3 | 工具面与真实能力不对齐 | view_media 在 chat 面暴露但 `GalleryCapability` 只挂 GALLERY 场景（chat 内必败）；`search_media` 的 @Tool 只透传 query——引擎层 `SearchIntent.personName` / `MediaSearchEngine.collectPersonMediaIds` 早已支持人物维度，缺口仅在工具面透传，被迫绕无卡片的 gallery.query |
| D4 | 会话状态隐式耦合 | refine 基数 `lastResultAssets[sessionId]`（更新点 `ChatViewModel.kt:1792/:1834`，声明 :632）只被 search/refine 更新，脚本路径不更新 → 级联空转 |
| D5 | 全量规则常驻 + flash 档指令遵循不稳 | 34k token prompt、43 个 @Tool 全量注入；「最多 2 次工具调用」被无视（3 分钟 7+ 次）、编造 id、失败后谎报成功 |

**根因总结**：「意图理解与路由」目前只是 system prompt 里的一段话，由 flash 模型在 34k token 上下文里一次性完成理解+策略+执行。模型的**语义理解没有错**（儿子→大宝→按人物检索链路正确），错的是**策略层**（在互斥规则里选了不产卡片的工具）。方案核心：把理解和策略拆开——**LLM 管语义理解（输出意图），代码管路由策略（查表执行）**。

## 2. 方案概述

```
用户输入
   │
   ├─① pattern 捷径（零延迟，仅最热句式：「(看|找|看看|给我看)…照片」）
   │
   ▼ ② 未命中
┌──────────────────────────────────┐
│ 意图路由器 IntentRouter（专用 LLM 调用） │  prompt ~1k token（非 34k）
│ 输入：query + 紧凑对话状态 + 日期        │  闭集分类（契约表枚举）+ 槽位抽取
│ 输出：{intent, confidence, slots}       │  temperature=0，JSON schema 强校验
└──────────────┬───────────────────┘
               ▼ ③ 确定性代码（不是 LLM）
┌──────────────────────────────────┐
│ 路由策略（契约表查表）                  │  intent → 命令/工具子集/规则包/UI 契约
└──────────────┬───────────────────┘
   ├─ VIEW_PHOTOS / REFINE → SearchMedia(person=…) / RefineMediaSearch → 必出卡片
   ├─ ANALYZE_STATS       → 脚本路径（工具面仅盘点类）
   ├─ DRAW_CHART          → 取数 + draw_chart
   ├─ EDIT_IMAGE          → adjust_image / edit_image
   ├─ MEMORY / NAVIGATE / SETTINGS → 对应 capability
   └─ OPEN_QA             → 现有完整 agent loop（兜底，自由路由）
               ▼ ④ 执行后
┌──────────────────────────────────┐
│ 回合终态护栏（post-conditions）         │  契约不满足 → 端侧补偿/更正
└──────────────────────────────────┘
```

四层各自消除的缺陷：路由器消 D5（小 prompt 闭集任务是 flash 强项）、契约表消 D1（互斥在设计上不可能）、策略层+护栏消 D2/D3/D4。

## 3. 设计

### 3.1 意图契约表 `ChatIntentContract`（P0，路由 SSOT）

`shared/commonMain/.../intent/ChatIntentContract.kt`，声明式纯数据：

```kotlin
data class IntentDef(
    val id: IntentId,                    // 枚举：VIEW_PHOTOS / REFINE_RESULTS / ANALYZE_STATS /
                                         // DRAW_CHART / EDIT_IMAGE / RENDER_RICH_HTML / MEMORY /
                                         // NAVIGATE / SETTINGS / OPEN_QA
    val uiContract: UiArtifact,          // MEDIA_RESULTS_CARD / CHART / EDITED_IMAGE / RICH_HTML_CARD /
                                         // NAV_EFFECT / TEXT_ONLY
    val compositional: Boolean = false,  // 组合型意图（RENDER_RICH_HTML）：执行器职责即组装多原料，
                                         // allowedTools 相应更宽（script 取数/照片资产引用/draw_chart/render_html）
    val allowedTools: List<String>,      // 该意图分支的工具白名单
    val forbiddenTools: List<String>,    // 显式黑名单（如 VIEW 禁 run_gallery_script / view_media）
    val ruleText: String                 // 该意图的 prompt 规则文案（由表生成或校验）
)
```

- **规则由表生成/校验**：`ChatPromptRules` 的路由相关节从本表派生；CI 一致性测试保证「同一意图的 ruleText 与 allowed/forbidden 不互斥」——L41 vs L62 类矛盾在设计上不可能再发生。
- 与 `CAPABILITY_REGISTRY.md`（命令路由 SSOT）同构，形成代码+文档双层 SSOT。
- 意图初版 10 个（见上枚举）；`slots` 统一定义：`person? / fromMs? / toMs? / label? / constraint? / mediaId?`。
- **`RENDER_RICH_HTML` 基线**：render_html 通路已合 main（2026-09-25，89270e0a4：离线沙箱 WebView + 卡片内直接交互 + ResizeObserver 高度跟随 + `<a>` 外链全屏落地页，详见 ADR-014 修订注记与 `JS_ENGINE_TECH_SPEC.md` §7.1）；契约表登记以 main 为实现基线。隐私语义（ADR-008 × ADR-014）：模型只产出 HTML 结构与本地资产引用，图片/渲染全部端侧解析，媒体文件永不出端。

**混合意图原则——按「最终产出物」分类，不按「动作清单」分类**。路由器判定的是 deliverable（用户最终要什么形态的东西），动作是原料：

| 用户说 | deliverable | include（原料槽位） |
|---|---|---|
| 「生成 HTML 报告，展示我儿子的照片和月度趋势」 | RICH_HTML_CARD | `[{photos, person=儿子}, {chart, metric=月度}]` |
| 「找出去年夏天的照片，画个分布图」 | MEDIA_RESULTS_CARD + secondary CHART | — |
| 「生成健康报告」 | TEXT_ONLY（ANALYZE_STATS） | `[{stats}]` |

- 绝大多数混合请求归约为「1 个产出物 + N 个原料需求」，由组合型分支执行器组装；
- 真·双产出物由策略层顺序执行、共享上下文（先 search 出卡片 → 再取数 + draw_chart）；
- 产出物 >2 或置信度不足 → OPEN_QA 走完整 agent——路由器永不拦截能力，混合长尾天然落回兜底。

### 3.2 意图路由器 `IntentRouter`（专用 LLM 闭集分类）

- **位置**：`AgentOrchestrator` chat 入口，`PrivacyGuard` 之后、agent loop 之前。仅处理文本（query + 紧凑状态），符合 ADR-008（文本可走远程）。
- **输入**：用户 query + 紧凑对话状态（上一轮意图、上一轮 artifact 类型、搜索基数是否存在）+ 当前日期（供时间槽位换算）。
- **输出**（JSON schema 强校验，Koog 结构化输出）：

```json
{ "deliverable": "RICH_HTML_CARD", "confidence": 0.9,
  "isRefinement": false,
  "secondary": null,
  "include": [
    { "kind": "photos", "person": "儿子", "limit": 20 },
    { "kind": "chart", "metric": "monthly_count", "type": "bar" }
  ] }
```

（单意图请求退化为 `deliverable=VIEW_PHOTOS, include=[{photos, person=…}]`；`secondary` 至多 1 个，承载「找照片 + 画图」类双产出物请求。`isRefinement` 由路由器结合紧凑对话状态判定——refine 语义的产生者在此，策略层（§3.3）只消费不再猜。）

- **可靠性工程**：temperature=0；schema 校验失败重试 1 次；**1.5s 硬超时**；失败分类同路降级——网络错 / schema 重试仍败 / 超时 / `confidence < 阈值` 均 → OPEN_QA（现有完整 agent loop，优雅退化）。person 槽位称谓→人物名的解析在**端侧**做（关系词表 + `query_person_relation`），路由器不负责实体消歧。
- **本地信号门控**（评审新增）：路由器不对全量流量无差别启用——先用零成本本地信号（相册域关键词/实体词典命中、pattern 未命中但含媒体语素）判定「本轮是否可能为结构化意图」，寒暄与明显开放问答直通 OPEN_QA、零额外延迟；门控只放行不拦截（误判代价 = 多走一次 OPEN_QA，与现状同，无回退风险）。
- **路由期间 UX**：路由器等待期间即开始流式占位（与现链路 streaming pacing 一致），命中专用分支后无缝切换，不做「转圈静默」。
- **模型**：复用 deepseek-v4-flash（闭集分类 + 槽位抽取是 flash 档最可靠的任务形态）；经 `RemoteModelFactory` 可独立换强模型，与生成模型解耦。注意：Koog 结构化输出经 OpenAI 兼容网关时 `response_format: json_schema` 支持因供应商而异（Koog 有文本解析兜底），M2 首日需真机验证 deepseek 通路。
- **延迟预算**：< 1s（~1k token prompt、几十 token 输出；仅门控放行的流量产生此开销）。
- **Koog 落点**：M2 先作为普通 LLM 调用插入编排层；M3 升级为 graph strategy 条件边（分类节点 → 分支节点）。

### 3.3 确定性策略层（查表 + refine 判定 + pattern 捷径）

- **查表执行**：`intent → AgentCommand / 工具子集`，纯函数，commonMain，可单测。
- **多产出物顺序执行**：`secondary` 非空时按序分发两个意图（共享回合上下文），产物依次落卡片/图表消息。
- **refine 判定移入代码**：`intent=VIEW_PHOTOS && router.isRefinement && lastResultAssets 非空` → `RefineMediaSearch`；基数缺失 → 先 `SearchMedia`。`isRefinement` 取自路由器输出（§3.2 schema），策略层只消费。不再依赖模型记住上一轮。
- **pattern 捷径**：仅「看/找/看看/给我看 …照片」最热句式直接短路（跳过路由器调用，零延迟零成本），**不建大 pattern 库**——开放语义（如「想看看我家崽最近长什么样」）归路由器。pattern 集必须配负面样例测试（如「看看照片里有没有糊的」是 ANALYZE 非 VIEW），防捷径劫持语义。
- **IntentGuard 红线修订**：由「只做保守的误伤修正」改为「高频意图确定性直通 + 误伤修正」；现有两函数保留并入新 `IntentRouter` 命名空间，作用域明确：`isRefusedSearchRequest`（拒答回退本地直搜）在 VIEW_PHOTOS / REFINE / OPEN_QA 三分支的回合收尾统一生效；`sanitizeNavigationCommands` 仅 OPEN_QA 分支生效（专用分支不产 NAVIGATE 命令）。

### 3.4 工具面契约对齐（模型看得见的 = 真的能做的）

1. **`search_media` 透传既有结构化参数** `person / fromMs / toMs`：引擎层早已就绪——`SearchIntent.personName`（`shared/.../model/context/SearchIntent.kt:26`）、`StructuredFilter.personName`、`MediaSearchEngine.collectPersonMediaIds`（含 raw query 人物解析）、`QueryGalleryMediaUseCase.filter.person`（人脸归属 AND 交集，`QueryGalleryMediaUseCase.kt:28`）；缺口仅 `ChatToolService` 的 @Tool 只传 query。M1 只需填充既有 `SearchIntent` 字段并透传到 capability，**不新建解析链路**。「精确人物查询必须绕 gallery.query」的存在基础消失，L41 实为落后于引擎演进的 stale 规则，随透传落地删除。
2. **`view_media` 移出 chat 工具面**：chat 内看图 = 点卡片；消除「chat 必败工具」陷阱及其诱发的幻觉链。
3. **脚本出卡**：`run_gallery_script` 返回含 `mediaIds` → 端侧自动渲染横滑卡片（不新增 `show_media_results` 工具，减少模型决策点；触发条件随阶段演进，详见 §3.5-a：M1 无条件补卡、M2 起叠加意图抑制）。
4. **`@LLMDescription` 标注 UI 效果**：每个工具声明「结果以横滑卡片展示」/「仅返回文本统计」——模型叙述有据，幻觉失去土壤。
5. **CI 三方一致性测试**：chat 工具面 × capability `activeScenes()` × 契约表 allowed/forbidden 交叉校验——「chat 面暴露但场景必拒」类缺陷被测试拦住。

### 3.5 回合终态护栏（post-conditions，`ChatViewModel` 回合收尾）

与 IntentGuard 同层（commonMain 纯函数 + 平台注入 i18n 文案）：

- **a 补卡**：脚本路径返回 `mediaIds` + 本轮无 media_results 消息 → 端侧用 ids 直接补渲染卡片。触发条件 M1 不依赖意图判定（M1 阶段尚无路由器）：凡产出 mediaIds 的脚本回合即适用；M2 起叠加意图条件（ANALYZE_STATS 等盘点意图可抑制补卡）。ids→MediaAsset 水合走既有按 id 批量查询通道（实现时定位 MediaRepository/MediaSearchEngine 的 by-ids 入口，与搜索路径同一资产源）。
- **b 幻觉拦截（结构性信号，不做文本匹配）**：以 **tool_call 序列 + artifact presence** 判定——分支执行器先落 artifact 再生成总结（顺序保证叙述有据）；回合收尾校验「本轮应产 artifact（查契约表）而未产」→ 补 artifact 或追加更正文案。不对模型回复做关键词匹配（措辞无限、语言随用户走，i18n 管不住生成文本）。
- **c refine 基数收口**：`lastResultAssets` 更新点扩展到所有产出媒体 id 集合的路径（search / refine / **script**）——级联断裂消除；无基数时 refine 返回明确错误而非 3ms 空成功。

### 3.6 分支化 mini-agent（M3，Koog graph）

每个意图分支 = 独立 mini-agent：各自 `ToolRegistry` 子集 + 对应规则包（scenario 约束）。prompt 总量从「34k 全量常驻」变为「路由器 ~1k + 当前分支 3–5k」，flash 档注意力稀释问题釜底抽薪。OPEN_QA 分支保留接近现状的完整工具面。此即 Koog skill 讨论的落地形态：**分支 = 事实上的 skill**（指令注入 + 工具子集），不需要 Koog 上游新概念。

**组合型分支**（`compositional=true`，如 RENDER_RICH_HTML）：执行器为多步组合 agent，工具面按「原料工具集」配置（run_gallery_script 取数 / 照片资产引用 / draw_chart / render_html），include 槽位即其任务清单；UI 契约绑定产出物（html_card 消息存在），回合护栏照常可校验。组合规则（「HTML 里怎么嵌图/嵌图表」）只存在于该分支规则包，不污染其他分支——这正是分支化优于单一大 agent 的地方。

### 3.7 可观测性与行为回归 eval

- `polang_llm_log.db` 已有 `llm_call_log` / `tool_call_log` 地基，新增 turn 级路由审计：`{意图判定, 路径(pattern/router/open), 工具序列, artifact 产出, 契约满足与否}`。
- **eval harness**：固定查询集（「儿子的照片」×变体、统计类、画图类、记忆类…）重放 N 次，断言 artifact 契约满足率。路由稳定性从「体感」变成数字——当日 Koog 1.3.0 上线无行为回归兜底，就是这个缺口。

## 4. 已定决策

- **D1 系统性重构而非单点修补**（用户 2026-09-25）。
- **D2 专用 LLM 意图路由器为主力，pattern 仅作零延迟捷径**（用户 2026-09-25：「大模型更能理解意图，强于写死的规则」——采纳并限定：LLM 输出**意图**而非工具，路由策略归代码）。
- **D3 脚本出卡用端侧自动渲染**，不新增 show_media_results 工具。
- **D4 view_media 从 chat 工具面移除**（chat 内看图 = 点卡片）。
- **D5 M1 止血项先行**，与目标架构不冲突、不会白做。

## 5. 测试决策

- **契约一致性**（新增 CI）：契约表 ruleText × allowed/forbidden 无互斥；三方一致性（§3.4-5）。
- **IntentRouter 纯函数单测**：pattern 捷径（含负面样例）、本地信号门控、refine 判定、查表分发、降级路径（超时 / schema 失败 / 低置信）。
- **路由器 LLM 离线 golden eval**：固定查询集 × N 次跑意图一致率与槽位准确率，记录基线（不设硬门禁，先观测）。查询集含三类对抗样例：pattern 负面样例（「看看照片里有没有糊的」）、注入样例（用户原文试图改写路由规则）、混合意图（双产出物 / 原料组合）。
- **prompt golden**：`ChatPromptRules` 变更走既有 `ChatSystemPromptGoldenTest` 重生成流程（`POLANG_WRITE_GOLDEN=1`）+ 人工 diff review。
- **回合护栏单测**：脚本 ids 无卡 → 补卡；幻觉断言无 artifact → 更正；refine 无基数 → 明确错误。
- **i18n**：护栏/更正/降级文案五语同步（EN/zh-CN/zh-TW/es/fr）。

## 6. 分阶段落地

### M1 止血（1–2 天）——消今天事故的根与全部放大器

| 任务 | 消除 |
|---|---|
| `search_media` 加 person/fromMs/toMs 透传 | D3-② |
| `view_media` 移出 chat 工具面 | D3-① |
| 回合护栏 a/c（补卡 + refine 基数收口） | D2/D4 |
| 三条矛盾规则（L41/L62/L133）按意图二分重写，golden 重生成 | D1（人工版） |
| `@LLMDescription` UI 效果标注（首批：search/script/view 相关） | D2 |

**验收**：真机重放「看下我儿子的照片」「给我看去年夏天的」（含会话上下文预热：先跑一轮模糊搜索 0 命中）各 ×5，全部出卡片；refine 在脚本路径之后不再 3ms 空转。

### M2 意图路由器（3–5 天）

契约表落地（§3.1）→ IntentRouter LLM 调用 + schema 校验 + 降级（§3.2）→ 确定性策略层 + pattern 捷径（§3.3）→ 路由审计落库（§3.7 前半）。
**验收**：路由器离线 eval 基线报告产出；线上一周路由审计同时统计**护栏前路由正确率**（目标 ≥ 95%——只统计护栏兜底后满足率是循环论证，路由器本身的质量必须可观测）与 VIEW_PHOTOS 契约满足率 100%（护栏兜底后）；OPEN_QA 降级率与门控放行率可观测。

### M3 分支化 + eval（演进）

Koog graph 条件边分支（§3.6）+ 规则按分支注入（prompt 瘦身）+ eval harness 门禁化（§3.7 后半）。
**验收**：单回合 prompt token 峰值显著下降（目标 < 1/3 现值）；固定查询集契约满足率 ≥ 99%。

每阶段：双端同步（`IosChatPrompt.kt` 镜像规则；iOS 复用 commonMain 路由器）+ 文档原子更新。

## 7. 明确不做（YAGNI）

- 不建大 pattern 库（开放语义归路由器）。
- 不做多模型路由投票/级联路由器。
- 不引入 Koog 内置 RAG 做规则检索（规则量未到阈值；留待 prompt 超限再评估）。
- 不改 Koog 上游、不自建 agent harness（对齐既定立场）。
- 不做 iOS 独立路由实现（commonMain 共享，iOS 只做镜像 prompt 与组装）。
- 相机链路不动（2026-08-16 冻结决策）。

## 8. 文档同步清单（实施时原子提交）

- `AGENT_ARCHITECTURE.md`：§2.4.4 意图理解改写为路由器架构；新增路由体系小节。
- `CAPABILITY_REGISTRY.md`：工具面变更（view_media 移除、search_media 参数）。
- 新 **ADR-015 意图路由契约与路由器**（决策记录：理解归 LLM、策略归代码）。
- `shared/AGENTS.md` §2 组件表：intent/ 扩展（ChatIntentContract、IntentRouter）。
- `GALLERY_SEARCH.md`：search_media 结构化参数说明。
- 双端：`IosChatPrompt.kt` 同步规则变更。

## 9. 开放问题

- 混合意图的路由器输出形态：`feat/chat-html-card` 已合 main（2026-09-25，89270e0a4），include 槽位 schema（photos/chart/stats）以 main 的实际取数 handler 为准标定；组合分支工具面宽度（给多宽会重新引入注意力稀释）用 M2 eval 观测。
- 路由器携带的对话状态窗口：初值 2 轮紧凑态（上轮意图 + artifact 类型 + 搜索基数存在性；`isRefinement` 的多轮指代判定依赖它），M2 用 eval 数据标定是否可收敛到 1 轮。
- 置信度降级阈值：初值 0.6，M2 期间按误路由率调。
- OPEN_QA 分支保留多大工具面：M3 分支化时按使用率裁剪。
- iOS 落地节奏：Android M2 验收后走 `/ios-follow`。
