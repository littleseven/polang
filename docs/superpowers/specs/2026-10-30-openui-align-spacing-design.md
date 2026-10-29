# OpenUI 卡对齐与间距结构治理（align/justify 全链路）

> **日期**：2026-10-30
> **状态**：已定稿，当日实施
> **范围**：shared（schema）+ androidApp（渲染）+ server（校验器/工具描述/lint）
> **背景文档**：`docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md`

## 1. 问题（用户真机反馈）

OpenUI 卡「毫无规则的堆放原始组件，UI 的对齐和结构很差」；各种居中、间距控制失效。

## 2. 结构性诊断（根因，已代码核实）

| # | 根因 | 位置 |
|---|------|------|
| R1 | `Column` 无 horizontalAlignment（恒 Start）——stat tile `Row([Column(DISPLAY+CAPTION),…])` 每栏 weight 等宽但栏内数字贴左错落 | `OpenUiNodeRenderer.kt` Column 分支 |
| R2 | `Row` verticalAlignment 硬编码 CenterVertically——高矮不一栏顶线不齐 | `OpenUiNodeRenderer.kt` Row 分支 |
| R3 | align/justify 只在冷门组件 `Stack` 上实现；黄金示例与 LLM 肌肉记忆全用 Row/Column，**想居中无处表达** | `OpenUiContentRenderers.kt` OpenUiContentStack |
| R4 | Button 恒 wrap-content 窄按钮贴左，卡底视觉重心飘 | `OpenUiNodeRenderer.kt` Button 分支 |
| R5 | 间距双轨：Row/Column `spacing` 三档 vs Stack `gap` 七档并存（本期只记录，不治理） | — |

核心结论：**LLM 没有表达对齐的词汇**——不是提示词没写好，是语言本身缺 prop。

## 3. 设计决策

### D1 align prop（三容器 + Stack 别名，官方语义严格对齐，v2 修订）

> **v2 修订（2026-10-30 当日，用户指令「严格遵循 OpenUI 标准组件每个参数的语义可选项」）**：
> 初版取「枚举 start|center|end、缺省=治理前现状零回归」，经核实官方
> `thesysdev/openui` Stack schema（`packages/react-ui/src/genui-lib/Stack/schema.ts` +
> `index.tsx`）后收紧为官方口径——词汇五值、语义 = CSS `align-items`、缺省 = `stretch`。

| 组件 | prop | 官方语义映射（Compose） | 缺省 |
|------|------|------------------------|------|
| Row | `align?`（交叉轴） | start→Top / center→CenterVertically / end→Bottom / stretch→子栏 `fillMaxHeight()` / baseline→子栏 `alignByBaseline()` | **stretch**（官方 CSS 缺省） |
| Column | `align?`（交叉轴） | start→Start / center→CenterHorizontally / end→End / stretch→子项 `fillMaxWidth()` / baseline→兜底 start（列向无对应概念，文档化偏差） | **stretch** |
| Card | `align?`（同 Column，作用于卡内内容列） | 同上 | **stretch** |
| Stack（官方别名） | `align?` | 同 Row/Column（direction 分流；wrap=true 的 Flow* 不支持交叉轴修饰符，stretch/baseline 退化容器基线）；缺省同步由 start 修正为 stretch | **stretch** |

- 枚举词汇/语义/缺省严格对齐官方：`start|center|end|stretch|baseline`，缺省 `stretch` = 官方 CSS `align-items` 缺省（官方 schema `z.optional()` 无显式缺省，`alignMap[undefined]` → CSS 默认 stretch）。
- 官方 Stack 其余参数核对：direction 缺省 column ✓、gap 缺省 m ✓、justify 六值（start|center|end|between|around|evenly）缺省 start ✓、wrap 缺省 false ✓——本仓 schema 本就一致，仅 align 缺省偏差本次修正。
- `justify` 不引入 Row/Column/Card：Row 子组件恒 weight 等宽分栏、Column 高度恒 wrap 内容，主轴 justify 无意义场景，YAGNI（Stack 别名有 justify，保持）。
- 非法值：端侧 shared schema 拦（有 default → 回退默认值 + error 记录，不丢组件）；server 校验器半信任不做 per-prop 校验（既有口径）。
- 行为变化说明（放弃零回归假设）：Column/Card 内容缺省撑满整宽（Text 无视觉差；secondary/tertiary 按钮随之整宽 = 官方主流 GenUI 形态，与 D2 primary 整宽一致）；Row 子栏缺省由居中改 stretch（无界高约束下 `fillMaxHeight` 幂等退化，高矮栏顶线对齐 = 官方形态）。

### D2 主按钮 full-width

`variant=primary`（含缺省）的 Button 渲染 `fillMaxWidth`；secondary/tertiary 保持内容宽。Row 分栏内 primary 填满所在栏，语义自然。

### D3 工具描述同步

- Row/Column/Card 签名补 `align?` + 官方语义一句话（五值、缺省 stretch、stat tile 数字栏写 `align="center"`、高矮栏顶线对齐写 `align="start"`）；
- Stack 签名行补 align 同官方五值缺省 stretch + justify 六值说明；
- stat tile 黄金示例三个栏 Column 改 `align="center"`（示例即范式）。

### D4 lint +1 条（软警告）

program 含 DISPLAY 且含 `Row(` 但全文无 `align="center"` → 警告「stat tile 数字栏建议 Column(align="center") 居中」。正则级、只建议不拒卡，同 lintComposition 既有口径。

### D5 四处同源同步点

shared `OpenUiLibrary`（schema）↔ server `RenderOpenUiValidator`（白名单）↔ 工具描述签名 ↔ androidApp 渲染端——字面量副本按既有惯例同步，测试钉住。

## 4. 不做（YAGNI）

- justify 主轴分布（weight 分栏下无意义场景）；
- 间距双轨并轨（R5，记录待评估）；
- A2UI 对照组任何改动（样式冻结）；
- iOS 渲染端（OpenUI 线 Android-only 实验，iOS 跟随走 ios-follow）。

## 5. 验收

- [x] server `RenderOpenUiValidatorTest`：stat tile lint 正反用例（align 合法/非法由端侧 schema 拦，server 半信任不做 per-prop 校验）
- [x] shared `OpenUiLibraryM2Test`：align schema 钉住（官方五值 + 缺省 stretch，Row/Column/Card/Stack 四处）、居中 stat tile 求值、非法值回退默认 + error 记录
- [x] 黄金示例钉住测试全绿（示例自身过校验）
- [x] `:shared:jvmTest` openui 套件 + server RenderOpenUi 套件全绿
- [ ] androidApp 编译过
- [ ] 真机遛狗：stat tile 卡三栏数字居中（显式 `align="center"`）、主按钮 full-width、缺省 stretch 形态（内容撑满整宽）符合官方观感
