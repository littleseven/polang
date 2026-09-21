# PoLang 全量 UI 微信风重设计（WeChat Restyle）设计文档

> 状态：已拍板待实施 ｜ 2026-09-22 ｜ 打法：方案 A（规范 → Token 换值 → 形态逐页重画）

## 1. 背景与决策链

| 日期 | 事件 |
|---|---|
| 2026-09 | 青玉绿×Cloud Dancer 配色定稿（tokens v2.2.2）；Ardot Light/Dark 全域可切重组完成（113 帧/11 页/359 变量） |
| 2026-09-18 | Play 商店素材 v2.2.3 按青玉绿设计稿三语全量上线 |
| 2026-09-22 | 用户决定：App 所有 UI 风格向微信看齐（本文档） |

现状资产：Token SSOT = `shared/src/commonMain/resources/design-tokens.json`（v2.2.3），经 `scripts/gen-design-tokens.py` 生成 Android `DesignTokens.kt` / iOS `DesignTokens.swift`；视觉 SSOT = Ardot 设计稿（Dark 正稿、Light 帧级钉定）；官网同色系（主绿 `#1CBB6E`）。

## 2. 用户拍板决策

1. **看齐深度 = 完整微信设计语言**：品牌绿 `#07C160`、灰白中性底、列表式设置页、微信式导航栏/标签栏/聊天气泡——整套换掉，视觉接近微信系应用。
2. **范围 = 含相机全部页面**：相机页也换皮，但只改主题/配色，不加功能不做 parity 打磨（与 2026-08-16 相机线冻结决策不冲突）。
3. **路径 = 设计稿先行**：先重做 Ardot 帧（微信风）→ 导出验收 → 再同步双端代码，保持设计驱动纪律。
4. **连带资产 = 同批跟发**：App 双端换完验收后，同批重导 Play 素材（v2.3）+ 官网换肤。
5. **打法 = 方案 A**：利用「帧已大量绑定 token」的既有资产——token 变量值换成微信色板后大部分帧自动换色，手工重画的只有「形」（卡片→列表、导航/标签/气泡形态），工作量远低于 113 帧全重画。

## 3. 设计方案

### §1 微信设计语言适配规范（PoLang 版）

参照源：微信 8.0.x **Android 真机**（实现期可随时采样对照）。规范中的值允许实现期 **±2 色阶 / ±1dp** 微调，任何改动必须回写本节。

#### 色板（映射进现有 M3 语义槽位——槽位结构不动，只换值）

核心槽位：

| 槽位 | Light | Dark | 用途 |
|---|---|---|---|
| primary | `#07C160` | `#07C160` | 主操作、选中态、标签栏高亮 |
| onPrimary | `#FFFFFF` | `#FFFFFF` | |
| background / surface | `#EDEDED` / `#FFFFFF` | `#111111` / `#1A1A1A` | 页面底 / cell、卡片、面板 |
| onSurface 主字 | `#181818` | `#D5D5D5` | |
| onSurfaceVariant 次级字 | `#888888` | `#7F7F7F` | |
| outlineVariant 分隔线 | `#E5E5E5` | `#2C2C2C` | 组内 hairline |
| error / badge 红 | `#FA5151` | `#FA5151` | 角标、警示（替换现 `#B3261E`） |
| chatBubble 自方 | `#95EC69` | `#3EB575`（采样校准） | |
| chatBubble 对方 | `#FFFFFF` | `#2C2C2C` | |
| 链接蓝（tertiary 承载） | `#576B95` | `#7D90B0`（采样校准） | 聊天内链接/引用文字 |

派生槽位（推导规则，Token 阶段落值并回写）：

- `surfaceContainer*` 梯度：Light `#FFFFFF→#F7F7F7→#F2F2F2→#EDEDED→#E5E5E5`；Dark `#0C0C0C→#141414→#1A1A1A→#222222→#2C2C2C`
- `secondary` 系 → 中性灰（Light `#888888` / container `#F2F2F2`；Dark `#7F7F7F` / container `#2C2C2C`）
- `tertiary` 系 → 链接蓝（如上表），onTertiary `#FFFFFF`
- errorContainer：Light `#FDEBEB` / Dark `#3A1F1F`

标注「采样校准」的两个初值，实现期以微信真机截图取色后回写本表，不改规范外散落值。

#### 形态清单（真正手工重画的部分）

- **导航栏**：与页面底同色（Light `#EDEDED` / Dark `#111111`）、居中 17sp semibold 标题、左返回箭头、右功能入口；无大标题、无投影，底一条 hairline。
- **标签栏**：surface 底、图标+10sp 文字、选中 `#07C160` / 未选中 `#888`、无指示器胶囊。
- **列表 cell**（设置页大改）：surface 底组内行高 ~54dp、水平 padding 16dp、左 icon+标题、右值+chevron；组圆角 8dp、组间距 8dp 悬于灰底、组内行间 hairline（末行无线）——**替换现有卡片式**。
- **聊天气泡**：圆角 12dp、靠头像侧尾角 4dp；头像圆 40dp；自方绿/对方白。
- **主按钮**：`#07C160` 胶囊白字；次按钮：白底胶囊 + 绿字 + 细绿描边。
- **搜索框**：`#F7F7F7` 圆角 8dp 灰字（Dark `#2C2C2C`）。
- **底部弹层**：顶圆角 12dp、surface 底、列表式内容。
- **字阶**：结构保留、值向微信尺度对齐——导航/标题 17sp、正文 16-17sp、tab 10sp、辅助 13-14sp（整体比现值略大）。
- **圆角**：列表组 8dp、按钮胶囊、气泡 12+4dp、搜索框 8dp、弹层 12dp。

**品牌资产不动**：App 图标、闪屏品牌元素保持 PoLang 自有（只随主题换底色）；**图标全部自绘或用现有 Material 体系，绝不用微信任何图形资产**（商标红线，见 §7）。

### §2 Token v3.0 变更

- `design-tokens.json` → `_version: 3.0.0`：`colorScheme` light/dark 按 §1 全量换值；在用固定色跟换（`chatBubble`、badge、providerTag 等绿色系字面量统一改到新绿）。
- `gen-design-tokens.py` 重生成 `DesignTokens.kt` / `DesignTokens.swift`，token 门禁（三向 `--check`）必须绿。
- Ardot 主题变量（359 个中的主题集）同步改值——已绑 token 的帧自动换色；改值前先跑全稿 audit 清 literal 残留。

### §3 Ardot 帧改造（形，按用户旅程分批）

| 批次 | 页 | 帧数 | 重点 |
|---|---|---|---|
| B1 | Camera | 7 | 只换色（取景全屏近黑），面板底色跟随 token |
| B2 | Gallery + Chat | 17 | 主入口：气泡、搜索框、标签栏、导航栏微信化 |
| B3 | Settings | 11 | 卡片→列表组，工作量最大 |
| B4 | People + Organize + Memories + Editor + Components | 30 | Components 页组件（floating_tab 等）先改，下游实例自动跟 |
| B5 | Store | 47 | 按新规范重画 → 导 Play v2.3 |

每批 Light/Dark 双模导出验收；**全程单会话串行操作 Ardot 文件**（并发会话曾互相覆盖）。

### §4 双端代码落地

- **Android**：token 重生成后 `colorScheme` 直喂 M3，大部分页面自动换色；手工改形态组件——设置页列表化（新建 WeChat 风列表 cell 组件）、导航栏/标签栏/气泡/搜索框改造；相机页只换主题资源。
- **iOS**：走 `/ios-follow` 管线对等跟随。
- **验收**：`ui_driver` 截图 + `/ui-parity-guard` 双端一致性；**screenshot-diff 基线全红是预期**，重建基线。
- i18n 不动文案；列表化后布局挤压需五语（en/zh-rCN/zh-rTW/es/fr）回读。

### §5 Play v2.3 + 官网

- Store 47 帧重画后走既有三语导出管线（zh 两行回读 18/18 检查）+ SOCKS 桥发布。
- 官网：主绿 `#1CBB6E` → `#07C160`，深绿/薄荷按既有推导关系跟随，**只换色不重构布局**。

## 4. 阶段拆分（供实施计划）

```
①规范+Token（§1 定稿落 JSON + Ardot 变量改值 + 门禁绿）
②Ardot 帧改造 B1→B4（形，逐批双模验收）
③Android 落地（形态组件 + 截图验收 + 基线重建）
④iOS 跟随（/ios-follow）
⑤Play v2.3 + 官网收口
```

每阶段独立可验收、独立出实施计划；token 门禁绿才许进下一阶段。

## 5. 风险与红线

- **[商标]** 借鉴设计语言 ≠ 克隆资产：所有图形资产自绘，绝不用微信 logo/图标/贴图；App 名、图标、品牌识别保持 PoLang 自有。
- **[相机冻结]** 仅改主题资源，不加功能、不做 parity 打磨。
- **[回滚]** token v2.2.3 打 tag 留回滚点；Ardot 快照体系本身是设计稿回滚点。
- **[并发]** Ardot 文件全程单会话串行操作。
- **[规范漂移]** 采样校准值必须回写 §1，禁止散落实现里。

## 6. 验收标准

1. token 门禁（三向 `--check`）绿；ktlint / detekt / 单测绿。
2. 双端逐页截图 vs Ardot 设计稿 SSOT 闭环（Light/Dark 双模）。
3. `/ui-parity-guard` 双端一致性通过，截图基线按新规范重建。
4. Play v2.3 三语全量替换成功（zh 两行回读 18/18）。
5. 官网换色上线（`#07C160` 主绿系）。
