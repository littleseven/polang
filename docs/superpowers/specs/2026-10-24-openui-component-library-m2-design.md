# OpenUI 组件库 M2：七组件 → 十三组件 + 可绑定 Text 设计

> **勘误（2026-10-24，in-file）**：标题与 §1/§5/§6/§7 的「十三 / +6」系计数笔误——§2 实际枚举 7 个新组件（Icon/Divider/List/Tabs/CheckBox/ChoicePicker/Slider），7 既有 + 7 新 = **14 组件**。一切以 §2 枚举与代码为准。

> **日期**：2026-10-24
> **状态**：已定稿（Android 先行；iOS 跟随走 ios-follow）
> **上位约束**：`2026-10-24-openui-wire-format-toggle-design.md`（OpenUI 主 spec §4.2 组件库）、`2026-10-24-openui-image-component-design.md`（Image/#photos）、ADR-016

## 1. 背景与目标

OpenUI 线现有七组件（Text/Row/Column/Card/Button/BarChart/Image），A2UI 线 17 组件。对比实验要求两线可比；且真机实测发现可绑定缺口（Text 不收 `$selected`，「显示当前选中月份」类联动文案做不到）。

**目标**：OpenUI 组件库扩至 13 个，交互组件接通 state 写入，Text 支持数据绑定。

**非目标**：`@Each` 列表渲染仍不做；Modal/TextField/DateTimeInput 明确不补（聊天卡内嵌弹层/IME 输入 UX 存疑，记为与 A2UI 线的刻意差异，入对比台账）。

## 2. 新组件签名（OpenUiLibrary，全量）

| 组件 | 签名 | 说明 |
|------|------|------|
| Icon | `Icon(name*, colorRole?)` | name ENUM = A2UI catalog v1 的 59 枚预定义图标名（禁 svgPath 同口径）；colorRole 复用现有色板 |
| Divider | `Divider(axis?)` | axis∈HORIZONTAL\|VERTICAL（默认 HORIZONTAL） |
| List | `List(children*, direction?, spacing?)` | direction∈VERTICAL\|HORIZONTAL（默认 VERTICAL）。VERTICAL = 限高纵向滚动列（maxHeight 约 320dp，聊天卡内嵌 LazyColumn 的嵌套滚动规避）；HORIZONTAL = 横滑行（MediaResultsCarousel 先例） |
| Tabs | `Tabs(tabs*)` | tabs = [{title: STRING, children: COMPONENT_LIST}]；选中页签为**渲染期本地态**（remember），不进 OpenUI state（页签切换是纯 UI 导航，无 LLM 语义） |
| CheckBox | `CheckBox(label*, checked*)` | checked 新 prop 类型 BINDABLE_BOOLEAN（Bool 字面量或 $binding）；点按 → `@Set(<绑定变量>, !当前值)` |
| ChoicePicker | `ChoicePicker(label?, options*, selected*)` | options = [{label, value}]（OBJECT_ARRAY 复用）；selected BINDABLE_STRING；单选 chips 行，点选 → `@Set(<绑定变量>, value)` |
| Slider | `Slider(label?, value*, min?, max?)` | value 新 prop 类型 BINDABLE_NUMBER；min/max NUMBER 默认 0/100；**渲染必须用 `core/designsystem/components/AppSlider.kt`**（全 app 滑杆红线，禁裸 M3 Slider）；拖动结束 → `@Set(<绑定变量>, 数值)` |

**Text 升级**：`text` prop STRING → BINDABLE_STRING（字面量照收向后兼容；Binding 从 state 读，解析不出 → 丢该 Text 组件不炸卡，同现有半信任口径）。

## 3. 引擎改动（shared commonMain）

1. `OpenUiPropType` 新增 `BINDABLE_BOOLEAN`、`BINDABLE_NUMBER`（语义同 BINDABLE_STRING：字面量或 `$binding` 皆收，Binding 原样存 props，渲染期从 state 快照解析——BarChart.selected 既有机制）。
2. `checkPropValue` 加两个分支：BINDABLE_BOOLEAN 收 Bool/Binding；BINDABLE_NUMBER 收 Num/Binding。
3. Tabs.tabs 的 OBJECT_ARRAY objectFields 含 COMPONENT_LIST 字段——验证 `checkPropValue` 递归对组件数组字段的支持，缺则最小补齐。
4. `@Set` 泛化核对：`applyAction` 对任意 target 变量写任意 OpenUiValue（Str/Num/Bool）；若现实现只服务 `$selected` 则泛化。`OpenUiCardHost` 的 dispatch：Set/Reset 后一律刷新 stateSnapshot + root 重求值；`onViewStateChange` 持久化仍只针对 `$selected`（其余 state 不持久化，M2 口径）。
5. OpenUiContract：如需新约定常量在此加（server 字面量副本同步核对）。

## 4. 渲染器（androidApp OpenUiNodeRenderer）

- 六组件新分支 + Text 分支改 BINDABLE 解析（Binding → state[name] as? Str）。
- Icon：name → Material Icons 映射表（与 A2UI 线 Icon 渲染同源/同枚举——找 A2UI 侧既有 Icon 渲染实现复用其映射思路；59 枚枚举校验在 shared schema ENUM 域 + server 白名单双层）。
- 交互组件的 onAction：渲染期构造 `OpenUiAction(name="Set", args=[新值], target=<绑定变量名>)`，与 BarChart 点选同机制；Binding 缺失（LLM 传了字面量）→ 交互只读展示不写入（与 BarChart 裸字面量口径一致）。
- 半信任防御不变：未知组件/类型不符 → 丢组件空占位，不炸整卡。

## 5. 服务端（字面量副本同步）

- `RenderOpenUiValidator.ALLOWED_COMPONENTS` +6 → 13 组件；Icon name 枚举校验（正则级：59 名白名单，与 shared schema 字面量副本）；QUOTED_ACTION/MEDIA_PATH/图数上限等既有规则不变。
- `RenderOpenUiToolService` @LLMDescription：组件清单加 6 行签名 + state 写入约定段（`$` 变量首行声明、交互组件绑定写法、点选/勾选/拖值 → 端侧回写、裸字面量只读）+ 黄金示例升级或新增一例「交互相册卡」（BarChart + Text($selected) + CheckBox）。
- 黄金示例继续走 companion const + 测试同源钉住。

## 6. 测试

- shared commonTest：新 prop 类型校验（字面量/Binding/类型错各一例）、Tabs.tabs 嵌套组件递归校验、@Set 写 Num/Bool state、Text(Binding) 求值。
- server：13 组件放行、Icon 非法名拒绝、第 6 组件描述与校验同源、既有回归全绿。
- 验证命令：`JITPACK=true ./gradlew :shared:jvmTest`、`./gradlew -p server build`、`./gradlew :androidApp:compileDebugKotlin`。

## 7. 文档同步

主 spec §4.2 组件表更新（13 组件 + Text BINDABLE 注记）；CHAT_CARD_CATALOG data_ui_openui 条目；shared/AGENTS.md、server/AGENTS.md 相关行；A2UI↔OpenUI 对比台账记刻意差异（Modal/TextField/DateTimeInput 不补 + OpenUI 独有 Image 相册照片能力）。

## 8. 验收

三条构建命令全绿 → 服务端部署 → 真机新会话发「可交互个人相册卡」复合指令（含 Icon/Divider/CheckBox/Slider 至少其三）→ 截图验证渲染与交互（勾选/拖值/点选 state 回写可见）。
