# OpenUI 通用布局原语批次设计（Grid / Box / Spacer / Row.weights）

> 日期：2026-10-30
> 状态：在途
> 上游：用户遛狗反馈——「希望有一批通用布局组件而不是业务组件」「经典样式布局：四宫格、按比例左图右文上下对齐等」

## 1. 背景与定位

align 治理与图文组合固化（ImageText/ImageTextLarge 接 photo）之后，用户明确方向：
**通用布局原语优先，业务组合组件降级为便捷糖**。根因链——LLM 裸拼 Row/Column 丑在
「Row 恒等宽分栏 + 无叠放/网格/留白表达」，而非缺业务组件。补齐经典版式所需的四个
通用表达缺口后，LLM 可用原语组合出任意经典布局，组合组件不再是必选项。

官方核查（thesysdev/openui `packages/react-ui/src/genui-lib/Stack/schema.ts`）：官方通用
布局仅 Stack 一件（direction/gap/align/justify/wrap，无 per-child weight、无 Grid/Box/
Spacer）。本批次属本仓刻意扩展（Col 列式不收、Image 双源等先例同口径），词汇与缺省
严格对齐官方/CSS/Compose 语义。

## 2. 组件设计

### D1 Grid(children*, columns?, spacing?, align?)——通用 N 列网格

- `columns` NUMBER，缺省 2，渲染期 clamp [1, 4]（卡内宽度约束，>4 列无可读性）；
  四宫格 = `Grid([a, b, c, d], columns = 2)`。
- `spacing` 沿用容器词汇 COMPACT|STANDARD|COMFORTABLE（缺省 STANDARD），行距列距同值。
- `align` 交叉轴五值 start|center|end|stretch|baseline，缺省 stretch（官方 align-items
  缺省），语义同 Row（每个网格行内 cell 的纵向对齐；stretch = cell 撑满行高）。
- 渲染：children 按 columns 分块成多行 Row，cell `weight(1f)` 等宽；末行缺 cell 补空
  Box(weight) 保持列对齐。

### D2 Box(children*, align?)——通用叠放容器

- children 按数组序从底到顶叠放（后声明者在上层）。
- `align` 九值 contentAlignment 语义：topStart|topCenter|topEnd|centerStart|center|
  centerEnd|bottomStart|bottomCenter|bottomEnd，缺省 **topStart**（Compose Box 官方缺省）。
- 渲染：androidx Box(contentAlignment = 映射值)，子组件各自 wrap/fill 自定尺寸，
  Box 尺寸 = 最大子件（图上叠文字/角标 = Image + Text/Tag 叠放）。

### D3 Spacer(size?)——通用留白件

- `size` ENUM 沿用官方 gap 词汇子集 xs|s|m|l|xl（缺省 m）→ Spacing.xs/sm/md/lg/xl
  （4/8/12/16/24dp，token 直引禁魔法数）。
- 渲染：`Spacer(Modifier.size(dp))` 双维占位——Row 内自然占宽、Column/Card 内占高；
  Row 分栏权重排除 Spacer（与 Divider 同口径，分隔件不参与 weight 分栏）。

### D4 Row 增 weights prop——按比例分栏

- `weights` PRIMITIVE_ARRAY（数字），与 children 一一对应；缺省/个数不符 → 回退全 1
  （现行等宽，渲染期容错不炸卡）。
- 左图右文 = `Row([Image(photos[0].id), Column([Text(...)])], weights = [1, 2])`，
  上下对齐配既有 `align`（start 顶对齐 / center 居中 / stretch 撑满）。
- Divider/Spacer 不占 weights 槽位（它们本就不参与分栏）；weights 计数只对组件子项。

### D5 lint 与食谱修订（通用优先）

- lint 第四条修订：Row 实参区间内 Image+Text 裸拼**且未传 weights** 才软警告
  （weights 比例分栏是认可的经典版式写法，不再警告）。
- 工具描述新增「通用布局食谱」段，经典版式 → 原语映射：
  - 四宫格/三宫格/双列网格 → `Grid(children, columns = 2|3)`；
  - 按比例左图右文（上下对齐）→ `Row([图, 文栏], weights = [1, 2], align = "center")`；
  - 图上叠文字/角标/浮层按钮 → `Box([底图, 上层件], align = "bottomStart")`；
  - 段落间节奏留白 → `Spacer("s"|"m")`；
  - ImageText/ImageTextLarge 保留为「一行写法便捷糖」，泛化组合优先用上面原语。
- 新增黄金示例 `GOLDEN_LAYOUT_PROGRAM`（#photos 投影：四宫格 Grid + 1:2 左图右文 Row）。

## 3. 影响面

| 层 | 改动 |
|---|---|
| shared OpenUiLibrary | +Grid/Box/Spacer 三组件、Row +weights prop；组件总数 89→92 |
| androidApp 渲染 | Row weights 分栏（Spacer 排除）+ Grid/Box/Spacer 三渲染分支 |
| server | 白名单 89→92（描述/校验器/测试三处字面量同步）+ 食谱段 + 黄金示例 + lint 修订 |
| 测试 | shared 注册/求值用例；server 白名单 92 钉住 + golden 过校验 + lint 正反 |
| 文档 | OPENUI_CARD_DESIGN_LANGUAGE v5 + §5 速查表 / CHAT_CARD_CATALOG / shared·server AGENTS.md |

## 4. 不做清单

- 不做 Column.weights（卡内纵向比例分栏罕见，后续有真需求再加）。
- 不下线 ImageText/ImageTextLarge/IconText（存量卡兼容 + 一行写法糖；描述降级为糖）。
- 不做 Grid 不等宽列模板（columns 模板数组属 CSS grid-template 复杂度，LLM 用 Row weights 可等价表达）。
- 不做 Box 子件独立定位（per-child align 属下一层复杂度，先容器级九值）。
