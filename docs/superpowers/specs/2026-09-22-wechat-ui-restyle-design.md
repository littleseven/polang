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
| chatBubble 自方 | `#95EC69` | `#3EB575`（**待用户微信真机 Dark 截图采样定值**，当前单值双模） | |
| chatBubble 对方 | `#FFFFFF` | `#2C2C2C` | |
| 链接蓝（tertiary 承载） | `#576B95` | `#7D90B0`（采样校准） | 聊天内链接/引用文字 |

派生槽位（推导规则，Token 阶段落值并回写）：

- `surfaceContainer*` 梯度：Light `#FFFFFF→#F7F7F7→#F7F7F7→#EDEDED→#E5E5E5`（2026-09-22 校准：surfaceContainer 由 F2 调至 F7=微信标签栏/次级面标准值，与 Low 同值）；Dark `#0C0C0C→#141414→#1A1A1A→#222222→#2C2C2C`
  - **canvas 侧注记**：新增 `cell` 槽位(light 白/dark #1A1A1A)供 App 消费；画布列表组绑定 surfaceContainer（帧钉 Light 预览对 in-flow 组填充存在引擎 quirk，与变量新旧无关，Dark 正稿不受影响）
- `secondary` 系 → 中性灰（Light `#888888` / container `#F2F2F2`；Dark `#7F7F7F` / container `#2C2C2C`）
- `tertiary` 系 → 链接蓝（如上表），onTertiary `#FFFFFF`
- errorContainer：Light `#FDEBEB` / Dark `#3A1F1F`

标注「采样校准」的两个初值，实现期以微信真机截图取色后回写本表，不改规范外散落值。

#### 形态清单（真正手工重画的部分）

- **导航栏**：与页面底同色（Light `#EDEDED` / Dark `#111111`）、居中 17sp semibold 标题、左返回箭头、右功能入口；无大标题、无投影，底一条 hairline。
- **标签栏**：surface 底、图标+10sp 文字、选中 `#07C160` / 未选中 `#888`、无指示器胶囊。
- **列表 cell**（设置页大改）：surface 底组内行高 ~54dp、水平 padding 16dp、左 icon+标题、右值+chevron；组圆角 8dp、组间距 8dp 悬于灰底、组内行间 hairline（末行无线）——**替换现有卡片式**。
- **聊天气泡**：圆角 12dp、尾角 4dp；自方绿 `#95EC69`/对方白。~~头像圆 40dp~~ **N/A（执行期决策 2026-09-22）**：PoLang 聊天为助手对话范式（2026-08-18 豆包范式去头像：AI 消息通栏、用户气泡无头像），微信化不覆盖此交互结构。
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

- **Android**（2026-09-24 Phase 3 完成，分支 feat/wechat-restyle-phase3）：平底标签栏（FloatingBottomTab 重造+4 调用点）、设置列表组（SettingsListSection→cell+8dp+组距8）、顶栏（AppTopBar 默认居中+SemiBold+hairline+常量收敛 TopBarTokens）、Theme 全槽位映射修正（存量缺口：container/variant/outline 系此前为 M3 默认紫基线）+ `ColorScheme.cell` 扩展。设备 Light 实测：标题 x=600 精确居中/白 cell 组/平底条四要素全中。相机页仅主题资源（已随 token 生效）。
- **iOS**：走 `/ios-follow` 管线对等跟随。
- **验收**：`ui_driver` 截图 + `/ui-parity-guard` 双端一致性；**screenshot-diff 基线全红是预期**，重建基线。
- i18n 不动文案；列表化后布局挤压需五语（en/zh-rCN/zh-rTW/es/fr）回读。

### §5 Play v2.3 + 官网（2026-09-25 完成，主线）

- **已上线**：海报铬件微信化（135 字面色回绑 + 33 渐变拍平）→ 27 处 Screen 重烘三语（源帧钉语言导 2x 灌图）→ Play 三语 8 截图+FG 全量替换（**27 删 27 传，sha 回读 28/28 零漂移**，通道=HK ssh -D 桥 + PySocks 包装 urllib，403>8 张按配方 prune）。商店文案零变更（纯图像 v2.3）。
- **官网**：主绿 `#1CBB6E→#07C160`、深绿 `→#06AD56`（site.css + 三语页）、首页 shots ×24 重出（q85 直出）、`?v=20260925-1`；HK polang.net + 国内镜像 8100 双站部署实测新值。
- **P5 遗留（台账）**：getting-started 的 setup-*.jpg 指南图未重烘（内嵌截图仍青玉）——需逐题映射（entry=设置总览/1=模型中心/2=TAG扫描/3=三阶段/4=账号/preview=聊天）+ 3x 导出 + tag_control 超长页顶裁 2556 配方，参照 2026-09-21 c493bce6a 流程；zh-TW 02-07 海报 09-18 手工繁体覆写已被 mode 导出替换（已知代价）。

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

## 6.5 执行增补二（2026-09-26 · 底 bar 形态回退 + Logo 组件化）

用户反馈追加：
1. **底 bar 形态回退**：平底条现代感不足，**悬浮胶囊恢复**（token v3.0.7 cornerRadius 28 回位、画布组件+14 实例回悬浮位+圆角 28、Android/iOS 代码回退、调用点恢复 16dp 悬浮边距）；**配色保留微信系**（选中 primary/未选中 onSurfaceVariant 不变）。~~§1 标签栏平底条~~ 形态条款作废，配色条款有效。
2. **人物页三 action 光学对齐**：FilterList 三线字形密度重心比圆形字形高 ~1.8dp（设备 3x 实测），App 侧 +2dp 光学校正。
3. **三帧状态栏漂移修复**：dedup/scanning、people/detail、gallery/grid-store01-zhTW 亮状态栏残留 → 回 Dark（烘焙周期覆写丢失病第三批）。
4. **Logo 组件化**：component/logo（primary 绿盒+白 WaveMark；旧青玉渐变+蓝紫波形退役）+ component/brand_row（Brand Dot+PoLang Gallery 锁组）落 Components 页；26 处换装（24 海报 Brand Row + Chat/Screens LogoBox 实例化）。渐变+变量 stop 绑定引擎不渲染（SendButton 同款雷），Logo 用 scheme/primary 纯色。
5. Play v2.3.1 三语图像再上线（胶囊圆角+Logo 修正后 27 图重传，回读 28/28）+ 官网 shots ?v=20260926-1 双站部署。

## 7. 执行增补（2026-09-22 · Organize 页专项指令）

用户追加指令：Organize 页 UI 重新设计——**简化 + 配色尽量一致**（当日自动执行完毕）。

1. **结构简化**：hub 页 6 张散置 category_card → 单个微信列表组（surfaceContainer 底、组圆角 8、5 条 hairline 等距分隔），与设置页列表语言一致。
2. **配色统一（token v3.0.5 值级）**：`statusColor.success` `#4CAF50→#07C160`（全 App 双绿合一为品牌绿）；`statusColor.info` 与 `modelCenter.tagColorChat` `#2196F3→#576B95`（Material 蓝收敛为微信链接蓝）。warning/error 保持产品语义色不变。
3. **连带修复**：`category_card` 组件 Play 烘焙残留 Light 钉 → 显式回 Dark（与铬件漂移同病族：组件级钉定驱动全部实例）。
