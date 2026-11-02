# 工程师任务卡 OpenUI 化设计（H3）

日期：2026-11-02 · 状态：已定稿（用户四问拍板 + 自主执行授权） · 前置：ADR-016（parts 模型）、H2 HTML 化（2026-09-27）、OpenUI turn-native T1（2026-11-01）

## 1. 背景与目标

工程师任务卡（chat 内 `tool_task` part）目前信息区走 `EngineerTaskHtml`（shared L1 HTML 模板）→ WebView（`HtmlCard`）渲染。OpenUI 线已成为 GenUI 默认线（2026-11-01 回合原生），拥有原生 Compose 渲染器与 100+ 组件词汇。本设计把任务卡信息区升级为 **OpenUI 程序描述 + 原生渲染**，干掉这块 WebView：

- 渲染性能与一致性（WebView 实例成本、测高/防抖机制全免——原生布局同步完成）
- 任务卡成为 OpenUI 运行时的又一个消费者，词汇覆盖缺口反哺 LLM 线
- 为 iOS eventual 统一到 OpenUI 渲染线铺路（本轮不动 iOS）

## 2. 决策记录（用户 2026-11-01 拍板）

| # | 决策 | 选择 | 理由 |
|---|------|------|------|
| D1 | program 合成来源 | **端侧确定性合成** | 任务卡是状态机驱动（五态+裁决、SSE overlay、500ms 合帧、原生动作回调），合成必须确定性；协议（`tool_task` part）不动 |
| D2 | 合成产物形态 | **程序文本全管线** | 产出 OpenUI 程序文本走完整 `OpenUiSession(parse→eval→render)`；debug 程序查看器、golden 单测、词汇覆盖检查全部惠及；几 KB 程序 500ms 合帧封顶，解析成本可忽略 |
| D3 | 卡内动作区 | **保持原生** | 动作是协议/VM 驱动回调（零桥接红线），OpenUI 动作词表（Set/Reset/Run/OpenUrl/ToAssistant）无任务动作，M1 不扩 |
| D4 | iOS 路线 | **Android 先行双轨** | `EngineerTaskHtml` 保留给 iOS WKWebView；后续 /ios-follow 建 SwiftUI OpenUI 渲染器子集后再退役 HTML 模板 |
| D5 | 双形态机制 | **复刻双形态+全屏** | 展开/折叠照旧（合成器带 expanded 重合成）；展开超一屏自动升格预览态 + 全屏 Compose 宿主查看器；原生 `onSizeChanged` 量高，WebView 测高缓存/防抖不再需要 |

## 3. 架构

```
EngineerTaskState (tool_task part, SSE overlay, 500ms 合帧 —— 全不动)
    ↓ EngineerTaskProgram.assemble(state, expanded, texts)     [shared 新增，纯 Kotlin]
OpenUI 程序文本（DSML，Card 根）
    ↓ OpenUiSession(program).prepare()                          [无 local_handle、无 viewState]
root 节点树 → OpenUiNodeRenderer（原生 Compose）                 [androidApp]
    ↳ 失败 → EngineerTaskFallbackCard（既有原生兜底，动作区照常）

外层容器不变：Surface(r16, cell 底) { Column { OpenUI 信息区 + EngineerTaskActionZone(原生) } }
```

### 3.1 合成器 `EngineerTaskProgram`（shared commonMain `domain/chat/taskcenter/`）

- 与 `EngineerTaskHtml` 对偶的纯函数组装器：同输入同输出（不含时间戳），golden 单测钉住。
- 输入 `EngineerTaskState` + `EngineerTaskTexts`（复用既有本地化文案载体；`EngineerTaskPalette` 不再需要——原生渲染直接吃 MaterialTheme）。
- 段落逻辑（header/meta/时间线/事件/diff 行/错误块/进度启发式/文案回退）**逐条平移**自 `EngineerTaskHtml`，含全部已知偏差注释（进度封顶 80%、无 ○ 待办行等）。
- **唯一注入面** = `escapeProgramString`（对偶 escapeHtml）：所有状态文本入 program 前按 lexer 字符串字面量规则全转义（`\` `"` 换行/控制字符/Unicode），敌对输入 golden 用例覆盖。

### 3.2 宿主（androidApp `features/chat/`）

- `EngineerTaskCard` 信息区换渲染路径：`produceState(programText) { OpenUiSession(program).prepare() }` → `Rendered.rootOrNull()` → `OpenUiNodeRenderer`；`Failed`/组装异常 → 既有 `EngineerTaskFallbackCard`。
- `rememberThrottledTask`（500ms 合帧 + 跃迁即时）**原样保留**；program 以节流后 state + expanded 为键重合成。
- **双形态**：`onSizeChanged` 原生量高 → 展开态超 `viewportHeightDp` 升格 FULLPAGE 预览（折叠形态 + 点击开全屏），FULLPAGE 方向粘滞仅内存（spec §10 口径不变）；全屏查看器 = 新 `OpenUiFullpageDialog`（标题 + 同一程序可滚动原生渲染，对齐 HtmlFullpageContent 交互）。测高 LruCache 的 `taskcard:*` 形态域 key 仅 legacy 路径继续用。
- **回滚闸**：开发者选项新增「任务卡渲染引擎」开关（默认 OpenUI；关 = 旧 HtmlCard 路径）。两条路径并存成本≈0（HTML 模板与 HtmlCard 因 iOS 双轨本就要保留）。iOS 退役 HTML 模板时一并清理开关。

### 3.3 词汇映射与扩展（shared `OpenUiLibrary` + androidApp 渲染器）

| HTML 段 | OpenUI 组件 | 备注 |
|---|---|---|
| 徽章行（TASK·HTML） | `Tag`（右对齐待验证） | 文案改「TASK · OPENUI」（五语） |
| header（⚙ 块+标题+状态 chip） | `Row` + `Icon` + `Text(SUBHEAD)` + `Tag` | ⚙ 块底、实心 chip 三色 → 词汇扩展（下） |
| meta/阶段/摘要行 | `Text(CAPTION, SURFACE)` / `Text(BODY)` | |
| 进度条 | **新 `Progress(value)`** | 4dp 轨道+填充，通用组件 |
| 错误块 | `Callout(tone=ERROR)` | 既有 |
| 时间线 ✓/● | `Steps`/`StepsItem` 或 `Timeline`（实现期定） | 文本时间线无照片 |
| 事件列（mono） | `CodeBlock` 或 `List`+`Text(CAPTION)` | 实现期定皮肤近者 |
| 分隔线/提示行 | `Divider` / `Text(CAPTION, SURFACE)` | |

**通用词汇扩展**（只加 LLM 普适件，不加任务卡专用固化件）：`Progress`；`Tag.tone`（primary/error/neutral 实心）；如实现期确认需要：`Icon` 容器底（tonal block）、Row 右对齐/flex-none 语义。**每项扩展同步 server 侧字面量副本**（`RenderOpenUiValidator`/`RenderOpenUiToolService` 四处核对惯例 + 测试钉住）。Card 根的卡皮与外层两分区容器的嵌套关系在实现期裁定（优先零扩展：Card 即信息区容器；若有双卡皮，宿主层中和或最小 prop 扩展）。

## 4. 数据与协议

**零变更**：`tool_task` part、`EngineerTaskState`、SSE overlay、`ToolPartState` 投影、Room 落库、`EngineerTaskTexts` 结构。OpenUI program 是**渲染期产物不落库**（对齐 H2「displayMode 粘滞不落库」口径），无 viewState、无 local_handle。

## 5. 测试策略

- **golden 单测**（shared commonTest）：`EngineerTaskProgramTest` —— 五态 × 折叠/展开 × 裁决分支 golden 文本；`escapeProgramString` 敌对输入（引号/反斜杠/换行/Unicode/空串）；阶段串解析/文案回退复用 `EngineerTaskHtmlTest` 用例口径；golden 程序全部经 `OpenUiSession.prepare()` 断言 `Rendered` 无错误（词汇覆盖自证）。
- **清单测试**：新增组件进既有「组件注册=渲染器 dispatch」清单测试。
- **server**：字面量副本同步测试跑绿。
- **Android**：`testDebugUnitTest` 全绿 + ktlint + detekt + `assembleDebug`。
- **真机**（醒后验收项）：五态真机截图对比 taskcard 四帧（438:181/208/264、444:31）、展开升格全屏、回滚开关切换、Light/Dark。

## 6. 文档同步（同原子提交）

- `CHAT_CARD_CATALOG.md`：卡目录行（形态列 WebView L1 模板 → OpenUI 原生端侧合成）+ 工程师任务卡节机制段改写 + data_ui_openui 节补一行（任务卡=端侧合成消费者，非 turn-native 产物）。
- `OPENUI_CARD_DESIGN_LANGUAGE.md`：§5 新组件皮肤 + §6 批次编年条目。
- `shared/AGENTS.md` / `androidApp` AGENTS：包清单一行指针（勿堆叙事，2026-11-01 SSOT 分工约定）。
- server 侧字面量副本 + KDoc。

## 7. 非目标

iOS OpenUI 渲染器（后续 /ios-follow 批次）；动作区 OpenUI 化；任务中心列表项（保持原生紧凑形态）；服务端合成；删除 `EngineerTaskHtml`（iOS 双轨仍用）；A2UI 线。

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 词汇覆盖缺口（右对齐/flex-none/实心 chip/进度条/文本时间线） | 实现期逐项核；缺口走通用词汇扩展并同步 server 四处；`prepare()` golden 断言兜底 |
| 像素对齐漂移（对齐 Ardot 四帧是硬要求） | 真机五态截图对比；偏差记录进目录「已知偏差」段（沿 H2 惯例） |
| 每 500ms 重 parse 成本 | 程序几 KB、纯 Kotlin parser、合帧已节流；跃迁即时/微更新合帧与 H2 同口径 |
| program 转义注入面 | `escapeProgramString` 全转义 + 敌对输入 golden 用例（对齐 escapeHtml 红线地位） |
