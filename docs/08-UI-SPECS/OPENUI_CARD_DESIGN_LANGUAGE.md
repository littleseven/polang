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
StatRow([{value: "7,120", label: "照片总数"},
         {value: "771",   label: "视频总数"},
         {value: "12",    label: "覆盖月份"}])
```

- **2026-10-31 起首选 StatRow 组件**（经典统计行固化：等分 + DISPLAY 数字 + CAPTION 弱色标签居中，一行调用）；
  手写拼装形态 `Row([Column([Text("7,120","DISPLAY"), Text("照片总数","CAPTION")], align="center"), ...])` 仍合法但降级为备用；
- 一行 2~4 个 tile 最佳；**反模式**：`Text("7894 张照片")` 纯文本统计行（数字不突出、无可比性）；
- 装饰 Icon 不进 stat tile；需要图标引导时单列 Icon 组件且**省略 colorRole**（自动弱色）。

### 4.2 Hero 封面

```
Banner("我的相册年度杂志", photo = photos[0].id, subtitle = "2026 · 12 个月")
```

- **2026-10-31 起首选 Banner 组件**（通栏题图横幅固化：图 + 底部渐变蒙层 + 左下白字标题/副题）；
  多图封面（2~3 张并排）仍用 `Row([Image, Image, Image])`；导语用 Markdown（关键数字加粗）而非纯 Text；
- Banner 图双源：photo 相册投影优先 / src https；**2026-10-31 比例治理（v7）起帧随图片真实比例自适应**（相册元数据 / 网络图 intrinsic，加载后采集单帧过渡），显式 aspect 仅用于强制造型（钳 [0.5, 2.5]），竖图不再需要手动传 0.75。

### 4.2.1 经典列表行（ListTile）

```
List([ListItem("海边日落", photo = photos[0].id, subtitle = "2026-07-18 · 三亚", trailing = "128 张"), ...])
```

- **2026-10-31 起 ListItem 实渲染化**（原纯元素形状升格）：前件三选一（photo 48dp 图 > image https 图 > icon 徽章）+ 标题/副题 + trailing 弱色小字；`List([ListItem...])` 纵向限高滚动即经典列表，独立用亦可；
- 前件缺省时是纯文字行（标题+副题+尾部），也是合法形态。

### 4.3 交互区（底部控制台）

- 交互组件（ChoicePicker/Slider/Switch/Button）成组收在卡尾，前置 SUBHEAD 分区标题；
- 需要更强分区感时用嵌套 Card 包裹交互区（L1 surface）；
- 联动文案（如「当前选中： X」）紧跟其交互源组件。

### 4.4 数据量构图引导

- 柱图 buckets > 12 时 LLM 应先聚合（按年/按季）或在回复正文说明，不原样塞 160 桶横滑；
- 单卡 Image ≤ 12 张（协议护栏），照片页横滑 List 每屏 2~3 列构图最佳。

### 4.5 图片帧比例决策链（2026-10-31 v7 起，全组件统一）

```
帧宽高比 = 显式 aspect prop（半信任，钳 [0.5, 2.5] = 强制造型）
        > 图片真实比例（端侧可信：相册 MediaStore 元数据 / 网络图 coil intrinsic 加载后采集，
          宽 sanity 界 [0.3, 3.0] 只防脏数据）
        > 组件缺省框（Image/ImageBlock 4:3、Banner/ImageTextLarge 16:9）
```

- 帧 = 真实比例时 `ContentScale.Crop` 退化为无损铺满——**竖图不再被缺省横框拦腰裁**，这是「布局时图片内容考虑图片比例及缩放」的落地口径；
- 覆盖组件：Image / ImageBlock / ImageTextLarge / Banner（通栏图全量）；ImageGallery 改**定高变宽**经典画廊形态（各条目帧随自身真实比例，等高不齐宽）；
- 定尺寸缩略图（ImageText 48dp / ListItem 48dp / ListBlock 48dp）与 VisualCard 背景图保持 Crop 裁切——缩略图/背景填充语义上裁切即正确；
- LLM 侧零负担：无需为竖图手传 aspect，显式 aspect 仅用于强制造型（如横幅必须 16:9）；
- 实现：`frameAspectOf(prop, realRatio, fallback)` 纯函数（OpenUiFrameAspectTest 钉住）+ `intrinsicAspectOf`（coil intrinsic 采集，NaN/零维防御）；加载中按缺省框占位，加载成功单帧过渡到真实比例；
- **刻意例外（2026-10-31 v8）**：PhotoGrid cell 恒 1:1、AvatarRow 恒圆形——网格/头像的均匀裁切本身是设计语义，不走真实比例链（人脸聚焦兜底构图）。

### 4.6 相册 APP 构图规则（2026-10-31 v8，对标 Google Photos / Apple 照片 / 小米相册）

相册类卡片按主流相册 APP 的签名组合形态构图，LLM 一行调用固化件，不再自由拼装：

| 签名形态 | 固化构图 | 出处 |
|----------|----------|------|
| 时间分组网格（相册主视图） | `CardHeader("2026年7月", subtitle = "128 张", actionLabel = "查看全部") + PhotoGrid(photos = [...], columns = 3)` | 三家主视图 = 日期/月份分组头 + 方形等宽网格 |
| 人物区（面孔） | `AvatarRow([{photo: photos[i].id, label: "妈妈"}, ...])` | Google Photos/Apple 人物页 = 圆形头像 + 名字 |
| 回顾卡（年度/月度杂志感） | Banner 首屏 + StatRow 关键数字 + PhotoGrid 摘选 | Apple 回忆 / Google Photos 年度回顾 |
| 相册列表（封面+名称+计数） | `List([ListItem(title, photo = …, subtitle = 日期, trailing = "128 张"), ...])` | 三家相册 Tab 列表行 |
| 回忆条（大卡横滑） | `VisualCardBlock([...], layout = "carousel")`（既有件） | Google Photos Memories 条 |

- **PhotoGrid 是照片网格唯一形态**：cell 恒 1:1 Crop + 人脸聚焦 + 2dp 密缝 + 整网格 12dp 圆角（本机相册页 MediaGrid 同形态），columns 2|3|4 缺省 3，单件 ≤12 张（单卡图片上限同口径）；⚠️ 禁用 Grid+Image 拼装照片网格（v7 起 Image 帧随各图真实比例，网格犬牙交错，server lint 会提示）；
- **AvatarRow**：56dp 圆形 Crop + 人脸聚焦 + CAPTION 名字居下，横滑；photo 写相册投影 id；
- **CardHeader 尾部动作位**：actionLabel/action 可选——「分组标题 + 查看全部 >」是相册 APP 日期分组头签名形态，actionLabel 缺省不渲染尾部；
- 网格/头像是刻意裁切件（§4.5 例外），人像构图由人脸聚焦保障。

## 5. 组件皮肤速查（渲染器现状对齐表）

| 组件 | 皮肤要点 |
|------|----------|
| Card | AppShapes.card + Spacing.lg 内边距；嵌套 = L1 surface |
| Row/Column/Card align | 交叉轴五值 start\|center\|end\|stretch\|baseline（官方 align-items 语义，缺省 stretch = 子项铺满交叉轴：Row 子栏撑满行高、Column/Card 内容撑满整宽；baseline = 首行基线对齐，列向兜底 start）；stat tile 数字栏写 `align="center"`、高矮栏顶线对齐写 `align="start"` |
| Row weights | 按比例分栏（2026-10-30，CSS flex-grow 语义）：数字数组与组件子项一一对应（Divider/Spacer 不占槽位），缺省/个数不符回退全 1 等宽；左图右文 = `weights=[1,2]` 配 `align="center"` |
| Grid | 通用 N 列网格（2026-10-30）：columns 1..4 缺省 2，children 按序填满各行、cell 等宽 `weight(1f)`，末行缺格补空位保持列对齐；四宫格 = `columns=2`；spacing/align 同 Row 词汇 |
| Box | 通用叠放容器（2026-10-30）：children 按数组序从底到顶叠放；align 九值 contentAlignment（缺省 topStart = Compose 官方缺省）；图上叠文字/角标 = `Box([Image, Tag], align="bottomStart")` |
| Spacer | 通用留白件（2026-10-30）：size∈xs\|s\|m\|l\|xl → Spacing.xs/sm/md/lg/xl（缺省 m）；Row 内占宽、Column/Card 内占高；不参与 Row 分栏（与 Divider 同口径） |
| Text | §2 字阶；DISPLAY 32B tnum；CAPTION 默认弱色 |
| Button | M3 Button（primary 行为色，primary 占满整行宽度；stretch 容器内各 variant 均整宽 = 官方主流形态） |
| BarChart | §3.1 |
| PieChart | §3.2 |
| LineChart | §3.3 |
| Image | 12dp 圆角；占位同形 surfaceVariant；横滑 List 内固定条目宽 200dp；aspect 缺省按图片真实比例（§4.5：相册元数据 / 网络图 intrinsic 同径，宽 sanity 界 [0.3, 3.0]，界外极长图轻微裁切），显式传 = 强制造型（clamp [0.5, 2.5]）；点击看大图（宿主动作 OpenPhoto → chat 内预览浮层单页，2026-10-30 由 PhotoEditor 编辑页改为预览页） |
| Icon | 无 colorRole → onSurfaceVariant 弱色；PRIMARY 仅强调性图标 |
| Divider | 0.5dp，onSurface × 0.08 |
| Tabs | 胶囊分段控件（surfaceVariant 容器 + primary 选中胶囊），>4 横滑 |
| CheckBox/ChoicePicker/Slider/Switch | M3 控件 + token 色；整行 toggleable 大热区 |
| Table | 等宽列，超宽横滑；表头 Bold |
| Callout | StatusColor 色块 + tone 图标 |
| Markdown | AgentMarkdown（mikepenz 唯一入口） |
| Accordion | 标题行 + 箭头，展开态渲染期本地态 |
| **M5 新增（2026-10-25 全量补全，89 项注册）** | |
| Stack / Separator / MarkDownRenderer | 官方别名：direction=row→Row/缺省→Column（gap 七档→Spacing、wrap→Flow*；align 五值官方语义缺省 stretch、justify 六值主轴分布，wrap=true 时 stretch/baseline 退化容器基线）；Separator→Divider 同皮肤；MarkDownRenderer→Markdown 同渲染 |
| CardHeader / InlineHeader | SUBHEAD 标题 + CAPTION 弱色副题（prop 名 subtitle/description 各一） |
| TextContent / Label / BoldText | size→字阶映射（small→CAPTION … large-heavy→HEADLINE）；Label=CAPTION 弱色；BoldText=Bold + subtext 弱 |
| TextCallout | variant 小写→tone 色（danger→error、neutral→弱色），Callout 同款色块 |
| CodeBlock | 等宽字体 + surfaceVariant 圆角块 + 横滑，language 弱色标注 |
| ImageBlock / ImageGallery | https src（[PRIVACY] 正则硬校验）；Gallery 横滑定宽 200dp，仅展示无大图页 |
| Tag / TagBlock | 胶囊（tonal 0.12 + variant→tone 色），TagBlock FlowRow 平铺 |
| IconText / ImageText / ImageTextLarge | 徽章/缩略图 + 标题副题列；Large=通栏 16:9 图 + 粗标题。ImageText/ImageTextLarge 图双源（2026-10-30）：`photo` 相册投影 id（photos[i].id，人脸聚焦 + 点击 OpenPhoto 大图，Image 同径）优先，`src` https 公网图兜底；未解析/非法 → 同形占位 |
| EntityList / ListBlock / Steps | 双列 key/value 行；序号圆点/缩略图列表（action 走宿主动作）；纵向步骤条（序号圆点 + 连接线） |
| Carousel / Buttons / IconButton | 横滑定宽条目；按钮组 Row/Column 平铺；M3 IconButton + surfaceVariant 圆底 |
| Modal | open BINDABLE_BOOLEAN 真值弹 Dialog；关闭 → @Set($open, false) |
| Form / FormControl / Input / TextArea / Select / DatePicker / CheckBoxGroup / RadioGroup / SwitchGroup / Chips / OptionCards / EditableTable | 表单族：name 键控 → 交互即 @Set($name, 值) 直写 state（$ 前缀），他处经 $name 绑定读；Form 仅视觉分组；OutlinedTextField/FilterChip/OutlinedCard 等 M3 件 + token 色 |
| AreaChart / HorizontalBarChart / RadarChart / RadialChart / SingleStackedBarChart / ScatterChart | §3 图表皮肤同族：chartPalette 离散色 + CAPTION 弱标签 + 图例；Area=线下填充 0.2、Radar=蛛网多边形、Radial=同心环弧、Scatter=散点 z→半径 |
| SnippetCardBlock / OverviewCardBlock / ContextCardBlock / CompositeCardBlock / VisualCardBlock | Block 壳：items≥1，layout grid=FlowRow 双列 / carousel=横滑 200dp；Visual=大图底 + 渐变蒙层白字，题图双源（2026-10-31）：bgPhoto 相册投影 id 优先 / bgImageSrc https |
| MetricIndicatorInline / MetricIndicatorWithStrikethrough | DISPLAY 大数字 + trend ↑success/↓error；删除线旧值 |
| **经典组合版式批次（2026-10-31，95 项注册）** | |
| StatRow / StatItem | 经典统计行（§4.1 固化件）：items [{value*, label?}] 等分 weight，DISPLAY 数字 + CAPTION 弱色标签居中；StatItem 独立 = 单项统计格 |
| Banner | 通栏题图横幅（§4.2 固化件）：photo 相册投影优先 / src https，底部渐变蒙层 + 左下白字标题副题；帧 = 显式 aspect > 图片真实比例 > 16:9 缺省（§4.5）；无图 = surfaceVariant 纯色底 + 主题色文字；photo 图点击 OpenPhoto 大图 |
| ListItem | 经典列表行（§4.2.1，实渲染化）：前件 photo 48dp 图 > image https 图 > icon 36dp 圆形徽章三选一 + 标题 BODY（≤2 行）/副题 CAPTION 弱 + trailing 弱色小字 / actionLabel 胶囊；action 整行可点 |
| 元素形状 18 件（*Item/Series/Slice/Point/ScatterSeries） | 仅作父组件数组元素的对象形状，独立出现渲染空占位（ListItem 2026-10-31 起升格出组） |
| **相册 APP 组合方式批次（2026-10-31，97 项注册）** | |
| PhotoGrid | 方形照片网格（§4.6 固化件）：photos* 相册投影 id 数组 + columns 2\|3\|4 缺省 3；cell 恒 1:1 Crop + 人脸聚焦 + 2dp 密缝，整网格 12dp 圆角；≤12 张；点击 OpenPhoto |
| AvatarRow | 人物圆形头像横滑条（§4.6 固化件）：items [{photo*, label?}]；56dp 圆形 Crop + 人脸聚焦 + CAPTION 名字居下；点击 OpenPhoto |
| CardHeader+actionLabel/action | 分组标题尾部动作位（§4.6）：「标题/副题 + 查看全部 >」相册 APP 日期分组头签名形态；actionLabel 缺省不渲染尾部 |

## 6. 变更记录

- v1（2026-10-25）：初版。落地件 = M4（字阶/间距/色板/Tabs 胶囊/空绑定兜底）+ M4.2（Image 链路三修）+ 本轮（DISPLAY 字阶、BarChart 柱道与圆角柱端、PieChart 环形化、Icon 默认弱色、stat tile 构图食谱入 server prompt）。
- v1.1（2026-10-25）：Image 体验二修——aspect 缺省改为按照片真实比例渲染（MediaStore 元数据；API<29 无 WIDTH/HEIGHT 列走 BitmapFactory 边界解码兜底；宽 sanity 界 [0.3, 3.0]，界外极长全景图轻微裁切；显式传 aspect 仍生效 = 强制造型，clamp [0.5, 2.5]）；Image 可点击看大图（初版经宿主动作 OpenPhoto 打开 PhotoEditor 大图页，2026-10-30 改为 chat 内预览浮层——点图语义是「看」不是「编辑」）。
- v2（2026-10-25）：OpenUI 官方组件库全量补全（21→官方 82 适配 81，注册 89 项，Col 列式 Table 刻意不收）——渲染层四域分文件（OpenUiContentRenderers 24 / OpenUiFormRenderers 12 / OpenUiChartRenderers 6 + 既有三图官方 prop 扩展 / OpenUiCardBlockRenderers 7），dispatch 按集合分派；BarChart 增 labels+series 官方数据面（grouped/stacked）、PieChart 增 pie 实心、LineChart 增 step 阶梯；表单族确立 name→$name 直写约定；§5 速查表同步。
- v3（2026-10-30，spec 已随交付清理，git 历史可查）：align 结构治理——Row/Column/Card 补 `align` prop，词汇/语义/缺省严格对齐官方 thesysdev/openui Stack（CSS align-items：五值 start\|center\|end\|stretch\|baseline，缺省 stretch = 子项铺满交叉轴；baseline 首行基线对齐，列向兜底 start 文档化偏差）；Stack 别名 align 缺省同步修正 start→stretch；主按钮（variant=primary）整行宽；server 工具描述官方语义行文 + stat tile 黄金示例三栏 `align="center"` + lint 软警告（DISPLAY+Row 未居中提示）。
- v4（2026-10-30，spec 已随交付清理，git 历史可查）：图文组合固化——ImageText/ImageTextLarge 补 `photo` prop（相册投影 id，与 src 二选一、photo 优先），图文版式被组件结构性锁死（小图左+标题副题列 / 通栏 16:9 图+粗标题），相册图文不再 Row 裸拼 Image+Text（等宽分栏反模式）；server 工具描述增图文排版食谱段 + 黄金示例 + lint 软警告（Row 内 Image+Text 裸拼提示）；单卡图片上限口径补 photo 键计数。文字环绕图片（float wrap）不支持，食谱声明替代形态。
- v5（2026-10-30，spec 已随交付清理，git 历史可查）：通用布局原语批次——设计方向从业务组合组件转向通用布局原语（用户定调「通用布局组件而不是业务组件」）。新增 Grid（通用 N 列网格，columns 1..4 缺省 2，四宫格 = columns 2）/ Box（通用叠放容器，align 九值 contentAlignment 缺省 topStart = Compose 官方缺省）/ Spacer（通用留白件，size∈xs\|s\|m\|l\|xl → 间距 token，缺省 m）三组件 + Row 增 `weights` prop（CSS flex-grow 语义按比例分栏，左图右文 = `weights=[1,2]` 配 align 上下对齐）；组件总数 89→92。lint 修订：Row 裸拼 Image+Text 仅无 weights 时警告（weights 比例分栏是认可的经典版式写法）；工具描述「通用布局食谱」段 + 黄金示例（四宫格照片墙 + 1:2 左图右文）；ImageText/ImageTextLarge 保留为便捷糖，泛化组合优先通用原语。官方核查：thesysdev/openui 通用布局仅 Stack 一件（无 per-child weight/Grid/Box/Spacer），本批次属本仓刻意扩展，词汇/缺省严格对齐官方/CSS/Compose。
- v6（2026-10-31，spec 已随交付清理，git 历史可查）：经典组合版式批次——原语之上固化经典构图（用户定调「布局丑问题依旧没有解决，应该做一些经典的布局组件」），LLM 一行调用即得正确版式，不再依赖原语拼装纪律。新增 StatRow（经典统计行 = §4.1 stat tile 组件化，items [{value*, label?}] 等分居中，lint 第三条「stat tile 未居中」的根治——警告文案同步引导换 StatRow）/ StatItem（元素形状保底）/ Banner（通栏题图横幅 = §4.2 Hero 组件化，photo 相册投影 + 渐变蒙层白字，aspect 缺省 16:9）；ListItem 实渲染化（经典 ListTile：前件 photo/image/icon 三选一 + 标题副题 + trailing，`List([ListItem...])` 经典列表成形，新增 photo/icon/trailing props 向后兼容）；VisualCardBlock/VisualCardItem +bgPhoto（题图卡相册投影，与 bgImageSrc 二选一、bgPhoto 优先）。组件总数 92→95；server 图片计数口径扩 `bgPhoto` 键 + 对象字段冒号形态（`{bgPhoto: …}` 同 `photo =` 计位图）；工具描述增经典组合版式食谱五则 + 黄金示例 GOLDEN_CLASSIC_PROGRAM。刻意不做 SectionHeader（CardHeader/InlineHeader 已是同义件）。
- v7（2026-10-31，纯渲染层批次，schema/server 零改动）：图片帧比例治理——§4.5 决策链全组件统一（显式 aspect > 图片真实比例 > 组件缺省框），真实比例双源 = 相册元数据（既有）+ 网络图 coil intrinsic（新增，加载后采集、单帧过渡）；Image 网络 src 支路告别恒 4:3、ImageTextLarge/Banner 告别写死 16:9（竖构图题图不再拦腰裁）、ImageBlock 帧随 intrinsic、ImageGallery 改定高变宽经典画廊形态；`frameAspectOf` 兜底比例参数化（组件缺省框随调用方），新增 `intrinsicAspectOf`（NaN/零维防御）；定尺寸缩略图与 VisualCard 背景保持 Crop 不变；OpenUiFrameAspectTest +4 用例钉住 fallback 参数化与 intrinsic 同径语义。
- v8（2026-10-31，无 spec，结论直落本文件 §4.6）：相册 APP 组合方式批次——学习 Google Photos / Apple 照片 / 小米相册签名组合形态固化为组件与构图规则（用户定调「学习主要相册 APP 的 UI 组合方式，实现为布局组件和规则」）。新增 PhotoGrid（相册主视图签名形态：方形 1:1 cell + 人脸聚焦裁切 + 2dp 密缝 + 整网格 12dp 圆角，photos* 数组 ≤12 张，columns 缺省 3）+ AvatarRow（人物区签名形态：56dp 圆形头像横滑条，items [{photo*, label?}]）；CardHeader 扩 `actionLabel`/`action`（「查看全部 >」分组头签名形态）。组件总数 95→97。两组件刻意绕开 §4.5 真实比例链——均匀方形/圆形裁切即设计语义（网格节奏感优先于单图比例保真）。server 图片计数口径扩 photos 数组括号配对扫描（防 `photos[0].id` 嵌套括号截断）；lint 第五条：Grid 内 Image≥2 引导换 PhotoGrid；工具描述 GOLDEN_PHOTO_PROGRAM 黄金示例 + 「相册 APP 构图规则」食谱段；GOLDEN_LAYOUT_PROGRAM 四宫格照片墙同步改 PhotoGrid（Grid 只留图表/混合内容）。

