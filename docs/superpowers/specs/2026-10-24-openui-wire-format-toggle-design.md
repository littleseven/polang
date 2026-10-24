# OpenUI 线格式并行接入 + GenUI 协议开关 — 设计 Spec

> **日期**：2026-10-24
> **状态**：已定稿，实施中（worktree `.worktrees/openui-genui`，分支 `feat/openui-genui`）
> **上位约束**：ADR-016（parts 宪法）/ spec `2026-10-21-cloud-local-hybrid-a2ui-genui-design.md`（混合架构，D1/D2/D3/§3.4 不变式全部继承）/ [PRIVACY] / [I18N] 红线
> **动机**：A2UI 已全量合 main（M2 + 语义样式令牌）。用户要求**并行支持 OpenUI Lang（Thesys，`thesysdev/openui`，spec 1.0，2026-10-12 发布）**，经开关在两种 GenUI 线格式间切换做实证比较（token 效率 / LLM 书写成功率 / 渲染保真）。

---

## 1. 选型再确认（对 hybrid spec D4 的修订）

hybrid spec D4 以「OpenUI 是开发期原型工具（wandb），thesysdev 同名项目偏 Web 生态无原生端故事」为由弃选。2026-10 情况已变：Thesys OpenUI Lang 发布 1.0 正式规范（行导向 DSL、流式解析、组件库 schema 驱动、错误恢复语义完备，附 conformance fixtures），是**真正的运行期 GenUI 协议**，与 A2UI 构成同层竞品。其「无原生端故事」缺口由本仓库**自研 Kotlin 客户端**（commonMain 解析器 + Compose 渲染器）填补——这本身就是比较实验的一部分（A2UI 有官方 androidx 渲染器 vs OpenUI 全自研）。

**结论（防翻案）**：A2UI 与 OpenUI 作为两条并行线格式共存，共享同一套隐私闸 / handle 体系 / 卡片落库骨架；OpenUI 线默认关（开关默认 A2UI）。

## 2. 方案概述

**一句话：同一 render_ui 语义、两条线格式；开关决定云端 LLM 拿到哪份工具描述、产出哪种文本；端上按 part 子类型分派到各自渲染器。**

```
设置页开关 genui_format = a2ui | openui（默认 a2ui）
        ↓ 每轮请求头
X-GenUI-Format: openui ──→ server AgentRoutes 按头二选一注册工具：
                            render_ui（A2UI 信封 JSON） | render_ui_openui（OpenUI Lang 程序）
        ↓ action 帧下行（复用 AgentRunner 既有通道，零新增）
端上 TurnRunner 按工具名分流 → MessagePart.A2uiCard | MessagePart.OpenUiCard
        ↓
A2uiCardHost（androidx.a2ui 官方渲染器） | OpenUiCardHost（自研 Compose 渲染器）
```

不变式继承：**GenUI 只生成骨架不生成数据**——OpenUI 侧数据同样只经 `local://` handle 端上补全（§4.3）；§3.4 外向通道不变式天然满足（OpenUI 卡无 outbound，交互动作本地消费）。

## 3. 开关（Toggle）

| 层 | 机制 |
|---|---|
| 存储 | DataStore string pref `genui_format`，值 `"a2ui"` / `"openui"`，默认 `"a2ui"`（`UserPreferencesRepository`，照 `cloud_agent_enabled` 三件套模式） |
| UI | 设置页云 agent 区（`SettingsScreen.kt` :558-573 附近）新增「GenUI 协议」行：点击弹单选对话框（A2UI / OpenUI），五语文案 |
| 注入 | `CloudAgentClient.postMessage` 加 `genUiFormat` 参数 → 条件头 `X-GenUI-Format`（照 `X-User-Api-Key` 先例 :166-171）；穿透链：ChatViewModel.runCloudTurnOnce / CloudAgentChatViewModel.send → CloudAgentTurnRunner.runTurn → CloudAgentApi.postMessage |
| server | `AgentRoutes.kt` tools 注册点（:281-287，已验证 per-request）读 `call.request.headers["X-GenUI-Format"]`：`= "openui"` 注册 `RenderOpenUiToolService`，否则 `RenderUiToolService`（默认/缺头/未知值一律 A2UI，向后兼容） |

比较方法说明（文档级，不写代码）：同一句 prompt 分别在两开关位跑，对比 messagesJson/program 字符数（token 效率）、端上解析成功率、渲染保真截图。

## 4. OpenUI 线设计

### 4.1 part 子类型（五个分派点，照 A2UI M2 T1 先例）

```kotlin
@Serializable
@SerialName("data_ui_openui")
data class OpenUiCard(
    override val partId: String,
    val program: String,          // OpenUI Lang 1.0 程序原文（骨架 + Query 绑定，无数据）
    val fallbackSummary: String,  // 投影级降级摘要
    val viewState: String? = null, // 交互态（"selected=<label>"），同 part id 覆写
) : MessagePart { override val category get() = PartCategory.DATA }
```

- `ChatMessageType.OPENUI_CARD`；`ChatListItem.TYPE_OPENUI_CARD = "openui_card"`；converter `"data_ui_openui"` 分支（content 列存整颗 part JSON，tool_browser 先例）；ChatModelInput 不回灌（emptyList）；ChatViewModel.toUiModel 加分支。
- 契约 SSOT：shared `domain/chat/openui/OpenUiContract.kt`——`PART_TYPE = "data_ui_openui"`、`TOOL_LOCAL_HANDLE = "local_handle"`、`EVENT_TO_ASSISTANT` 等字面量；server `RenderOpenUiToolService` 持字面量副本（双向同步注释）。

### 4.2 polang OpenUI 组件库（与 A2UI catalog 逐项对偶，保证可比性）

| 组件 | 位置参数（* = 必填） | 对偶 A2UI |
|---|---|---|
| `Text` | `text*`, `role?`, `colorRole?` | Text + 语义样式令牌（role=HEADLINE/SUBHEAD/BODY/CAPTION，colorRole=PRIMARY/SURFACE/ERROR/ON_SURFACE，端侧经 A2uiStyleMapper 同款映射走 design token） |
| `Row` | `children*`, `spacing?` | Row（spacing=COMPACT/STANDARD/COMFORTABLE） |
| `Column` | `children*`, `spacing?` | Column |
| `Card` | `children*`, `spacing?` | Card（library root 组件） |
| `Button` | `label*`, `action?` | Button |
| `BarChart` | `title?`, `buckets*`, `selected?` | BarChart（buckets=[{label,count}]；selected 传 `$selected` 绑定） |
| `Image` | `photo*`, `aspect?` | 相册照片（图文混排，2026-10-24 新增第七组件，详见 spec《openui-image-component-design》）：photo = `local://scan/<id>#photos` 投影元素的 id（渲染期端侧 id→uri，uri 不进 program/payload，[PRIVACY]）；aspect 宽高比（渲染期缺省 4:3，clamp [0.5, 2.5]） |

- scan 句柄 fragment 分流（2026-10-24，spec《openui-image-component-design》D2）：`#histogram`（或省略）= 月桶 `[{label,count}]`（BarChart 统计卡）；`#photos` = 照片清单 `[{id, date}]`（保持搜索相关度原序，cap 60，date="yyyy-MM-dd"，Image/Text 图文混排卡）；未知 fragment → 整卡 Degraded。
- 库 schema 在 shared `OpenUiLibrary.kt` 以数据声明（组件名 → 参数列表：名/类型/必填/默认值/enum 域），验证与渲染共用同一份（自描述，Agent First §2.3）。
- entry 规则：必须 `root = ...` 语句；fallback = 第一条值为 `Card(...)` 调用的 value 语句（OpenUI spec §2.2，library root = Card）。无 entry → `no-root` 致命 → 降级卡。
- [I18N]：静态 label 由 LLM 产出属动态内容豁免；端上自建文案（降级卡）复用 `chat_a2ui_card_unavailable` / `chat_a2ui_card_data_elsewhere` 两条既有五语资源。

### 4.3 数据绑定：Query("local_handle", ...)（隐私闸对偶）

A2UI 线用 updateDataModel 字符串值写 `local://` 绑定；OpenUI 线用规范自带的 Query 语句：

```openui
$selected = ""
root = Card([title, chart])
title = Text("月度照片分布", "SUBHEAD")
chart = BarChart("按月份", buckets, $selected)
buckets = Query("local_handle", { handle: "local://scan/7f3a#histogram" }, [])
```

- `local_handle` 是**端上宿主工具**（spec §6.4 host tool resolution）：androidApp 侧实现 = `HandleLookup.payloadOf(domain, id)` → domain `scan` 经 shared `GalleryHistogramBuilder.buildMonthly` 出 `[{label,count}]`。
- 任一 handle 缺失/不可解析/负载畸形 → **整卡 Degraded**（宁缺毋滥，与 HandleResolver 同口径）→ 渲染降级占位卡（fallbackSummary）。
- handle 字面量只出现在下行 program（不经投影闸）；上行投影仍是 opaque token（`handle: scan/<id>`，A2UI 线 M2 T3 已落，零改动复用）。

### 4.4 交互与 viewState（US-7 对偶）

- BarChart 点桶 = 组件内建行为：写 state store `$selected = label`（等价 `@Set`）。
- Host 观察 state store 的 `$selected` 变化 → `onViewStateChange("selected=<label>")` → ChatViewModel 持久化（同 part id 覆写，照 `persistA2uiViewState` :3659-3670 先例加 `persistOpenUiViewState`）。
- 重建时 viewState 注入为初始 state（`$selected` 初值），确定性恢复。
- 其他动作：`@Set`/`@Reset` 写 store 重渲染；`@ToAssistant` M1 记日志不路由（对比实验不需要）；`@OpenUrl` 走 `LocalUriHandler`。

### 4.5 自研 Kotlin 客户端范围（M1 实现面 / 明确不做）

shared commonMain `domain/chat/openui/`（🔴 纯度：只许 `kotlin.*`/`kotlinx.*` import）：

| 文件 | 内容 |
|---|---|
| `OpenUiLexer.kt` | §1.3 词法：标识符/$state/@func、字符串（双引 JSON 转义 + 单引 4 转义）、数字（含尾随 `e`/`.` 规则）、注释（`//` `#`）、操作符 |
| `OpenUiParser.kt` | §1.2 预处理（fence 抽取 + 注释剥离 + trim）+ §1.6 完整文法递归下降（语句/表达式/三元/优先级/成员访问/索引/调用/数组/对象）+ §1.4 语句边界（括号深度/未闭合字符串/跨行三元） |
| `OpenUiValue.kt` | 值模型 sealed：`Null/Str/Num(Double)/Bool/Arr/Obj/ComponentVal(type, props, statementId)/ActionVal/Binding($name)` |
| `OpenUiEval.kt` | §3 求值：toNumber/真值/宽松相等（ECMA 语义按 spec 复现）、运算符、pluck/`.length`/索引、内置函数 @Count/@First/@Last/@Sum/@Avg/@Min/@Max/@Filter/@Sort/@Round/@Abs/@Floor/@Ceil、引用提升/环检测/未解析=null、孤儿收集 |
| `OpenUiEngine.kt` | `OpenUiSession`：parse → 语句分类（§2.1）→ entry 解析（§2.2）→ 组件参数按库 schema 位置映射 + §8.2 校验恢复 → Query 经 `OpenUiToolResolver` 宿主工具解析 → 产出 `OpenUiResult.Rendered(root, state, errors)` / `Failed(errors)`；@Set 后全量重求值（程序微小，不建增量依赖图），Query 结果缓存不因重求值重跑 |
| `OpenUiLibrary.kt` | §4.2 组件库 schema 数据声明 + 位置参数映射/校验函数 |

**M1 明确不做**（spec 条款对照，全部有条款号可查）：流式增量解析（§4——本仓库传输是整帧交付，批量解析即可；§4 「streaming equals parsing」保证批量解析结果合法）、`@Each`（§3.5）、Mutation/@Run（§6.2）、编辑合并（§7）、表单/双向绑定输入组件（§5.2-5.4——库内无输入组件）、自定义 function/action 注册（§3.6/§6.3 的 library functions/actions）。

## 5. server 侧

`RenderOpenUiToolService.kt`（照 `RenderUiToolService` 注解模式）：

- `@Tool(customName = "render_ui_openui")`，参数 `(program: String, fallbackSummary: String)`，返回确认文本（A2UI 版同型，action 帧通道复用 `AgentRunner.onToolCallStarting` 零改动）。
- `@LLMDescription` 要点：OpenUI Lang 一句话语法（`名 = 表达式`、组件调用、位置参数、字符串双引号）；§4.2 七组件签名逐字（2026-10-24 +Image，spec《openui-image-component-design》）；数据禁令（相册数据一律 `Query("local_handle", {handle:"local://scan/<id>#histogram"}, [])`，handleId 只许来自 search_media/refine_media_search 结果尾部，禁臆造禁路径/uri）；`$selected` 绑定约定；fallbackSummary 写投影级摘要。
- `CloudAgentToolContract.TOOL_RENDER_UI_OPENUI = "render_ui_openui"` + `CloudAgentToolCatalog` 加 CLOUD 条目。
- **校验闭环对等（2026-10-24 review 补）**：main 线 `1caa81fea` 给 render_ui 落了发射点门控（`RenderUiValidator.gate` + 错误回灌）；OpenUI 侧对等实现 `RenderOpenUiValidator`——结构级轻量校验（不跑完整 parser，server 不依赖 :shared）：root 入口存在性（含 Card 兜底 entry）/ 组件名白名单（七组件 + Query，剥离字符串与注释后正则扫大写调用）/ Query local_handle 形态 / program ≤128_000（对齐 render_html 先例）+ 媒体路径禁令；违规与 render_ui 同口径错误回灌（LLM 同轮修正）。**对比实验口径对齐：两条 GenUI 线的 LLM 书写成功率都有同轮修正机会。**

## 6. 端侧分派与落库

- `CloudAgentTurnRunner` action 分支（:96-110）：`tool == TOOL_RENDER_UI_OPENUI` → `CloudTurnEvent.OpenUiCard(program, fallbackSummary)`（解析容错照 render_ui 的 runCatching 先例）。
- `ChatViewModel`：`a2uiFlushes` 同款快照队列（泛化或并列 `openUiFlushes`），SSE 收尾 `insertOpenUiCardMessage`（type=PART_TYPE，content=part JSON，modelUsed="cloud_agent"）。
- `CloudAgentChatViewModel` 调试页：最小文本行 `render_ui_openui(len=…)`。
- `ChatScreen` `when (item.contentType)` 加 `TYPE_OPENUI_CARD` → `OpenUiCardHost`。

## 7. 渲染器（androidApp `features/chat/openui/`）

- `OpenUiCardHost.kt`：`produceState(part.partId)` 里 `OpenUiSession` 准备（注入 `local_handle` 工具实现 + viewState 初始 state）→ `OpenUiResult` 三态 UI：null=Loading / Failed=降级卡 / Rendered=组件树渲染。
- `OpenUiNodeRenderer.kt`：`ComponentVal` → Compose：Card/Row/Column/Text/Button/BarChart。样式映射**复用 `A2uiStyleMapper`**（androidApp `features/chat/a2ui/A2uiStyleMapper.kt`，role/colorRole/spacing → design token 的既有实现）保证两线视觉同源可比。BarChart 视觉参照 `PolangBarChartComponent`（主色/45% 透明度选中态），为避并行 A2UI 任务冲突**不改动 a2ui 包任何文件**，渲染器在 openui 包内自写（样式经同一 StyleMapper，观感对齐由 StyleMapper 保证）。
- state store 用 Compose `SnapshotStateMap` 承载，`@Set` 写即触发重组；`$selected` 变化回调 onViewStateChange。
- 降级卡复用 `A2uiCardDegraded`（若可见性不允许则 openui 包内同款，`chat_a2ui_card_*` 字符串复用）。

## 8. 测试决策

1. **词法/文法**（commonTest `OpenUiParserTest`）：语句边界（括号续行/字符串换行/跨行三元）、fence 抽取、注释、数字边界（`1.`/`1e`）、对象/数组/可选逗号。
2. **求值**（`OpenUiEvalTest`）：toNumber  coercion、宽松相等（`5 == "5"`）、pluck/`.length`、内置函数全项、引用提升/环/未解析、孤儿。
3. **引擎**（`OpenUiEngineTest`）：entry 两规则、组件校验恢复（missing-required/type-mismatch/excess-args/unknown-component 各一）、Query 宿主工具注入（fake resolver）、no-root 致命、@Set 重求值。
4. **分派**（照 A2UI T1）：converter roundtrip + 坏内容 Text 兜底、flattener 分派、ModelInput 不回灌。
5. **端侧**（androidApp test）：TurnRunner render_ui_openui 帧 → OpenUiCard 事件 + 其他工具忽略；catalog 条目断言。
6. 验证门槛：`JITPACK=true ./gradlew :shared:jvmTest` + `:shared:assemble`（commonMain 纯度）+ `:androidApp:compileDebugKotlin` + `:androidApp:testDebugUnitTest` + `./gradlew -p server build` + `./scripts/ai-gate.sh`。

## 9. 明确不做（边界）

- OpenUI 不进 iOS（M1 Android-only，与 A2UI M2 同口径；iOS 跟随属后续 /ios-follow 议题）。
- 不做流式增量渲染 / @Each / Mutation / 编辑合并 / 输入组件（§4.5 清单）。
- 不改 A2UI 线任何文件（并行任务施工中）；`render_ui` 工具与 data_ui 卡行为零变化。
- 不动 parts 协议（D1 继承）；不新增 Room 表/迁移（content 列存 part JSON）。
- 比较报告属实验产物，不入活文档；结论若选型落定走 ADR。

## 10. 交付沉淀（DoD）

- 代码合 main 前：`CHAT_CARD_CATALOG.md` 登记 data_ui_openui 卡（三要素 + 端到端例子）；`shared/AGENTS.md` §2 补 openui 包一句；根 `AGENTS.md` §7 索引不动（本 spec 交付后按沉淀管线删除或收编）。
- 门禁：ai-gate 全绿（含 check_doc_sync）。
