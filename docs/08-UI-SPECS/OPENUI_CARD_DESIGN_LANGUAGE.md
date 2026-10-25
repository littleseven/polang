# OpenUI 卡内设计语言（OpenUI Card Design Language）

> **状态**：生效中（v1，2026-10-25）
> **适用**：OpenUI 协议渲染的全部聊天卡片（`data_ui_openui`），Android 先行，iOS 跟随
> **上位约束**：根 `AGENTS.md`（[PARITY]/[I18N] 红线）、`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`
> **实现锚点**：`androidApp/.../openui/OpenUiCardTokens.kt`（token SSOT）、`OpenUiNodeRenderer.kt`（组件皮肤）、server `RenderOpenUiToolService`（LLM 构图引导）

## 0. 定位与三条架构原则

1. **样式收敛在渲染器**：LLM 只选择组件与数据，不控制任何样式数值。一切颜色/字号/间距/圆角由 token 与组件皮肤定死——这是 OpenUI 与 render_html（H5 线）的本质差异，也是风格一致性的保证。
2. **A2UI 是对照组，样式冻结**：OpenUI 的一切视觉演进不进 A2UI 线（`A2uiCardTokens`/`A2uiStyleMapper` 只读复用几何尺寸与枚举解析）。没有规范约束的渲染结果就是对照组本身。
3. **规范即 PARITY 契约**：本文档是 OpenUI 卡片的视觉 SSOT。iOS 跟随实现以本文档 + token 为准，不读 Android 源码；双端截图比对以本文档的组件皮肤小节为验收清单。

## 1. 色彩系统

### 1.1 职责分离：行为色 vs 数据色（第一原则）

| 类别 | 用途 | 取值 | 规则 |
|------|------|------|------|
| **行为色** | 可操作控件与其选中态：Button、Tabs 选中胶囊、Slider、Switch、图表选中高亮 | `colorScheme.primary`（app 主色，不另设） | 一卡内 primary 大色块 ≤ 3 处；装饰元素禁用 |
| **数据色** | 图表数据编码（柱/扇形/折线） | `OpenUiCardTokens.chartPalette` 6 色离散板 | 只出现在图表内；不承担交互语义（选中高亮仍用 primary） |
| **语义色** | Callout tone（INFO/SUCCESS/WARNING/ERROR） | `StatusColor` 四色 | 只用 StatusColor 体系，不新造 |
| **弱色** | 辅助文本、装饰 Icon、图表柱道 | `onSurfaceVariant` / `onSurface` × alpha | 装饰性元素默认弱色 |

> 背景（2026-10-25 真机）：M3 主色绿与 M4 图表蓝橙板同卡打架。裁决：行为色跟随 app primary 不动（跨卡一致性），数据色驻留图表边界内；冲突的真正解法是**收敛行为色面积**——装饰 Icon 一律弱色，primary 只给真正的行为。

### 1.2 Surface 层级

- L0 卡底：`Card`（AppShapes.card + surface 底色），唯一的最外层容器；
- L1 区块容器：嵌套 `Card` 或 `Callout` 形成第二层（交互区/结论区的分区手法，见 §4 构图模式）；
- L2 控件底：Tabs 胶囊容器（surfaceVariant）、Switch/Slider 轨道等组件内建。

渲染器不自动包区块容器——分区是构图决策（LLM 用嵌套 Card/Callout 表达），皮肤是渲染器定的。

## 2. 字阶（OpenUiCardTokens）

| role | 字号/字重 | 用途 |
|------|-----------|------|
| **DISPLAY** | 32sp Bold，tnum 等宽数字，行高 38sp | 统计大数字（stat tile 专用），一屏 ≤ 4 个 |
| HEADLINE | 20sp Bold，行高 26sp | 卡标题，一卡 1 个 |
| SUBHEAD | 15sp SemiBold，行高 21sp | 区块标题（Section 起头，前距升 lg） |
| BODY | 14sp，行高 20sp | 正文 |
| CAPTION | 12sp，行高 16sp，默认弱色 | 辅助说明/脚注/统计标签 |

DISPLAY 与其他 role 的差异不仅是字号：tnum 让多栏 stat tile 的数字纵向对齐。

## 3. 图表皮肤规范

### 3.1 BarChart

- 单系列柱色 = 数据板首色（蓝 `#64B5F6`）；选中柱 = primary；
- 柱端圆角：topStart/topEnd 4dp（`barCornerRadius`）；
- 柱道（track）：每柱背后满高圆角柱道，`onSurface` × 0.06（`barTrackAlpha`）——给稀疏数据以结构感；
- 标签抽稀沿用 `barChartLabelInterval`（A2UI 只读复用）。

### 3.2 PieChart

- 环形（donut）：`Stroke` 宽度 26dp（`pieRingWidth`），不用实心饼——环形中心承载总计数值；
- 中心显示总值（数据文本，无文案——[I18N]：渲染器不写死任何文字）；
- 色板全板轮转；图例 = 色点 + label + 数值。

### 3.3 LineChart

- 色板轮转 + 数据点；null 断点断开折线。（面积渐变填充等增强留待后续版本。）

## 4. 构图模式（Composition Patterns）

模式 = 渲染器皮肤 + LLM 构图引导（server prompt 黄金示例）的合谋。LLM 照食谱组装，渲染器保证成品形态。

### 4.1 Stat Tile（统计大数字）

```
Row([Column([Text("7,120","DISPLAY"), Text("照片总数","CAPTION")]),
     Column([Text("771","DISPLAY"),   Text("视频总数","CAPTION")]),
     Column([Text("12","DISPLAY"),    Text("覆盖月份","CAPTION")])])
```

- Row 等宽分栏正好承载 2~4 个 tile；每 tile = DISPLAY 数字 + CAPTION 弱色标签；
- **反模式**：`Text("7894 张照片")` 纯文本统计行（数字不突出、无可比性）；
- 装饰 Icon 不进 stat tile；需要图标引导时单列 Icon 组件且**省略 colorRole**（自动弱色）。

### 4.2 Hero 封面

```
Text("我的相册年度杂志","HEADLINE") + Markdown("…**7,891**…") + Row([Image, Image, Image])
```

- 导语用 Markdown（关键数字加粗）而非纯 Text；
- 封面图 Row 等宽 2~3 张，竖图 aspect 0.75、横图 1.5~1.78。

### 4.3 交互区（底部控制台）

- 交互组件（ChoicePicker/Slider/Switch/Button）成组收在卡尾，前置 SUBHEAD 分区标题；
- 需要更强分区感时用嵌套 Card 包裹交互区（L1 surface）；
- 联动文案（如「当前选中： X」）紧跟其交互源组件。

### 4.4 数据量构图引导

- 柱图 buckets > 12 时 LLM 应先聚合（按年/按季）或在回复正文说明，不原样塞 160 桶横滑；
- 单卡 Image ≤ 12 张（协议护栏），照片页横滑 List 每屏 2~3 列构图最佳。

## 5. 组件皮肤速查（渲染器现状对齐表）

| 组件 | 皮肤要点 |
|------|----------|
| Card | AppShapes.card + Spacing.lg 内边距；嵌套 = L1 surface |
| Text | §2 字阶；DISPLAY 32B tnum；CAPTION 默认弱色 |
| Button | M3 Button（primary 行为色） |
| BarChart | §3.1 |
| PieChart | §3.2 |
| LineChart | §3.3 |
| Image | 12dp 圆角；占位同形 surfaceVariant；横滑 List 内固定条目宽 200dp；aspect 缺省按照片真实比例（元数据宽 sanity 界 [0.3, 3.0]，界外极长图轻微裁切），显式传 = 强制造型（clamp [0.5, 2.5]）；点击看大图（宿主动作 OpenPhoto → PhotoEditor 页） |
| Icon | 无 colorRole → onSurfaceVariant 弱色；PRIMARY 仅强调性图标 |
| Divider | 0.5dp，onSurface × 0.08 |
| Tabs | 胶囊分段控件（surfaceVariant 容器 + primary 选中胶囊），>4 横滑 |
| CheckBox/ChoicePicker/Slider/Switch | M3 控件 + token 色；整行 toggleable 大热区 |
| Table | 等宽列，超宽横滑；表头 Bold |
| Callout | StatusColor 色块 + tone 图标 |
| Markdown | AgentMarkdown（mikepenz 唯一入口） |
| Accordion | 标题行 + 箭头，展开态渲染期本地态 |

## 6. 变更记录

- v1（2026-10-25）：初版。落地件 = M4（字阶/间距/色板/Tabs 胶囊/空绑定兜底）+ M4.2（Image 链路三修）+ 本轮（DISPLAY 字阶、BarChart 柱道与圆角柱端、PieChart 环形化、Icon 默认弱色、stat tile 构图食谱入 server prompt）。
- v1.1（2026-10-25）：Image 体验二修——aspect 缺省改为按照片真实比例渲染（MediaStore 元数据；API<29 无 WIDTH/HEIGHT 列走 BitmapFactory 边界解码兜底；宽 sanity 界 [0.3, 3.0]，界外极长全景图轻微裁切；显式传 aspect 仍生效 = 强制造型，clamp [0.5, 2.5]）；Image 可点击，经宿主动作 OpenPhoto 打开 PhotoEditor 大图页（Gallery 同款入口）。
