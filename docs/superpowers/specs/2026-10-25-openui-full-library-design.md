# OpenUI 官方组件库全量补全设计（21 → 82）

> **日期**：2026-10-25
> **状态**：实施中
> **上位约束**：spec《openui-wire-format-toggle-design》/ ADR-016 / [PRIVACY] / [I18N]
> **动机**：OpenUI Lang 官方库（thesysdev/openui，`openuiLibrary`）共 **82 个组件**，
> 本仓库目前实现 21 个。用户要求全量补全，放大 OpenUI 线的表现力上限（与 A2UI 线对比实验
> 需要两线各自打满）。

## 1. 官方组件清单来源

- 组件集：`packages/react-ui/src/genui-lib/openuiLibrary.tsx`（82 个，含分组与 notes）
- prop schema：`benchmarks/schema.json`（53 个有完整 JSON Schema）+ `genui-lib/<Name>/schema.ts`
  （其余 29 个，Zod 声明，2026-10-25 已提取）

## 2. 总体策略

1. **能力覆盖优先于签名 purity**：LLM 只读我们的工具描述（RenderOpenUiToolService prompt），
   官方签名不必逐字照搬；但组件名一律用官方名（新概念零臆造），prop 语义对齐官方。
2. **存量 21 组件不动签名**（DB 存量卡向后兼容）；官方同名异构的（Callout/Tabs/Accordion/
   Table/Image/Card/Button）按 §3.2 处理。
3. **别名组件**：Stack→Row/Column、Separator→Divider、MarkDownRenderer→Markdown——schema
   独立注册（官方名可过校验），渲染走既有实现。
4. **表单态适配**：官方表单组件是 `name` 键控 form state；本仓适配为「`name` → `$<name>`
   state 变量」：输入组件交互即 `@Set($<name>, 值)` 直写（Slider/CheckBox 既有多例同口径），
   他处经 `$name` 绑定读。Form 只做视觉分组容器，无提交作用域。
5. **[PRIVACY]**：官方 Image(src=url) 形态 src 限 `https://`（A2UI 同口径，validator 正则
   硬校验）；相册照片仍走既有 `photo` id 通道。uri/内容不回灌 LLM。
6. **半信任输入防御**：未知组件/非法 prop 丢组件不炸卡（既有口径）；所有新渲染分支同。
7. **刻意差异（本轮确认保留）**：官方 `rules` 校验对象只收不执行（渲染层不强制校验，
   prompt 注明）；`@Each/@Filter/@Sort/@Count` 函数层不在本轮范围（组件补全 ≠ 函数补全）。

## 3. 组件映射总表（82）

### 3.1 已有 21 组件（覆盖官方 14 个 + 别名 3 个）

| 官方 | 本仓 | 处理 |
|------|------|------|
| Card | Card | ✅ 既有；增可选 `title` prop（有则渲头部）|
| Text | Text | ✅ 既有（官方 Text 是卡块内构件，见 §3.5 注）|
| Button | Button | ✅ 既有；增可选 `variant`(primary/secondary/tertiary→Filled/Tonal/Text)、`size`（收不用）|
| BarChart | BarChart | ✅ 既有 buckets 形态；增官方形态 `labels`+`series`+`variant`（见 §3.4）|
| LineChart | LineChart | ✅ 已是官方 labels+series 形态；增 `variant`（linear/natural/step，渲染 linear/step，natural≈linear）|
| PieChart | PieChart | ✅ 既有；增 `variant`（donut 默认/pie 实心）|
| Table | Table | ✅ 既有行式（官方 Col 列式不收，prompt 只教行式）|
| Image | Image | ✅ 既有 photo 形态；增 `src`（https 限）+`alt`，photo 降为可选（photo/src 二缺一丢组件）|
| Icon | Icon | ✅ 既有（59 枚举口径，不收官方 lucide 自由名）|
| Tabs / TabItem | Tabs | ✅ 既有 {title,children} 形态（能力对偶）|
| Accordion / AccordionItem | Accordion | ✅ 既有 {title,children} 形态（能力对偶）|
| Slider | Slider | ✅ 既有绑定形态（能力对偶）|
| Callout | Callout | ✅ 既有；tone 增 NEUTRAL（对齐官方五档）|
| Col | — | ✅ 不收（行式 Table 已覆盖，防双形态精神分裂）|

别名（schema 注册官方名 → 渲染复用）：
| 官方 | 渲染映射 |
|------|----------|
| Stack(children*, direction?, gap?, wrap?) | direction=row→Row / 缺省 column→Column；gap enum→既有 spacing token；wrap=true→FlowRow/FlowColumn |
| Separator(orientation?) | Divider（horizontal→HORIZONTAL） |
| MarkDownRenderer(textMarkdown*, variant?) | Markdown（variant 收不用） |

### 3.2 新增·内容/布局（M4a，19 个）

| 组件 | 签名（本仓适配） | 渲染 |
|------|------|------|
| CardHeader | CardHeader(title*, subtitle?) | SUBHEAD 标题 + CAPTION 副题 |
| InlineHeader | InlineHeader(title*, description?) | 同 CardHeader 渲染（官方语义即轻量标题对） |
| TextContent | TextContent(text*, size?=small/default/large/small-heavy/large-heavy) | size→role 映射：small→CAPTION、default→BODY、large→SUBHEAD、small-heavy→SUBHEAD、large-heavy→HEADLINE |
| TextCallout | TextCallout(title*, description?, variant?=neutral/info/success/warning/danger) | 走 Callout 渲染，variant 小写映射 tone |
| CodeBlock | CodeBlock(codeString*, language?) | 等宽字体 surfaceVariant 块 + 横滑 |
| ImageBlock | ImageBlock(src*, alt?) | 走 Image src 渲染（https 限）|
| ImageGallery | ImageGallery(images*=[{src*, alt?, details?}]) | 横滑 List 定宽图 + 点击 OpenPhoto 同款宿主动作（https src 直开 UriHandler？否——https 图不做大图页，仅展示） |
| Tag | Tag(text*, icon?, size?=sm/md/lg, variant?=neutral/info/success/warning/danger) | AssistChip 风小胶囊，variant→tone 色 |
| TagBlock | TagBlock(tags*=[string]) | FlowRow 平铺 Tag |
| Label | Label(text*) | Text CAPTION |
| BoldText | BoldText(value*, subtext?, size?) | Text BODY + Bold（官方卡块构件，本仓允许任意位置） |
| IconText | IconText(title*, icon?, subtitle?, bold?, layout?) | Row(Icon 徽章 + 标题/副题列)；layout=vertical 换 Column |
| ImageText | ImageText(title*, src?, subtitle?, imageSize?) | Row(小方图 + 标题/副题列)；src https 限 |
| ImageTextLarge | ImageTextLarge(title*, src?, subtitle?) | Column(通栏图 + 粗标题 + 副题) |
| EntityList | EntityList(rows*=[{left*, right*}], header?, footer?) | 双列 key/value 行列表 |
| ListBlock | ListBlock(items*=[{title*, subtitle?, image?, actionLabel?, action?}], variant?=number/image) | 纵向列表（序号/缩略图 + 标题副题），action 经宿主动作通道 |
| ListItem | —（OBJECT_ARRAY 元素形状，不独立注册） | — |
| Steps | Steps(items*=[{title*, details?}]) | 纵向步骤条（序号圆点 + 连接线） |
| StepsItem | —（元素形状） | — |
| Carousel | Carousel(children*=[组件], variant?) | 横滑定宽条目（List HORIZONTAL 同径） |
| Buttons | Buttons(buttons*=[Button...], direction?=row/column) | Row/Column 平铺 Button |
| IconButton | IconButton(name*, icon*, action?) | M3 IconButton（icon 走 59 枚举；name 作 contentDescription） |
| Modal | Modal(title*, open*=BINDABLE_BOOLEAN, children*) | state 真值时弹 Dialog（标题 + children + 关闭）；X/遮罩关闭 → @Set($open, false) |

### 3.3 新增·表单（M4b，14 个 + 既有 CheckBox/Switch/ChoicePicker/Slider 对偶）

| 组件 | 签名 | 渲染/写入 |
|------|------|-----------|
| Form | Form(name*, children*) | Column 视觉分组（无语义作用域） |
| FormControl | FormControl(label*, input*=组件, hint?) | label + 单个输入组件 + hint caption |
| Input | Input(name*, placeholder?, type?=text/email/password/number/url) | OutlinedTextField，键入即 @Set($name, 文本)；type→KeyboardType |
| TextArea | TextArea(name*, placeholder?, rows?) | 多行 OutlinedTextField，同写入 |
| Select | Select(name*, items*=[{value*,label*}], placeholder?) | OutlinedButton + DropdownMenu，选 → @Set($name, value) |
| SelectItem | —（元素形状） | — |
| DatePicker | DatePicker(name*, mode*=single/range) | 按钮 + M3 DatePickerDialog/DateRangePickerDialog → @Set($name, ISO 串/区间串) |
| CheckBoxGroup | CheckBoxGroup(name*, items*=[CheckBoxItem...]) | 纵向 CheckBox 列，每项写 `$<item.name>` |
| CheckBoxItem | 元素形状 {label*, description?, name*, defaultChecked?} | — |
| RadioGroup | RadioGroup(name*, items*=[RadioItem...]) | 纵向单选列，选 → @Set($name, value) |
| RadioItem | 元素形状 {label*, description?, value*} | — |
| SwitchGroup | SwitchGroup(items*=[SwitchItem...]) | 纵向 Switch 列，每项写 `$<item.name>` |
| SwitchItem | 元素形状 {label*, description?, name*, defaultChecked?} | — |
| Chips | Chips(name*, items*=[ChipItem...], type?=single/multiple) | FlowRow FilterChip；single 写 $name=值，multiple 写 $name=逗号串 |
| ChipItem | 元素形状 {value*, label*, icon?} | — |
| OptionCards | OptionCards(name*, items*=[OptionCard...], type?=single/multiple) | FlowRow 可选卡（顶部 Icon/Image + 标题副题），写入同 Chips |
| OptionCard | 元素形状 {value*, title*, subtitle?} | — |
| EditableTable | EditableTable(name*, columns*=[{type*,key*,header*,options?}], data*=[{id*,values*}]) | 表格单元格用 BasicTextField 内联编辑，失焦/换行即 @Set($name, JSON 行数组)（官方 Save 提交制简化为即写，prompt 注明） |

既有对偶：CheckBox≈CheckBoxGroup 单项、Switch≈SwitchGroup 单项、ChoicePicker≈Chips(single)、Slider（官方 name 键控不收，绑定形态已覆盖）。

### 3.4 新增·图表（M4c，10 个）

| 组件 | 签名 | 渲染 |
|------|------|------|
| Series | 元素形状 {name*/category*, values*} | — |
| Slice | 元素形状 {category*, value*} | — |
| Point | 元素形状 {x*, y*, z?} | — |
| ScatterSeries | 元素形状 {name*, points*=[Point]} | — |
| AreaChart | AreaChart(labels*, series*=[Series], title?, variant?) | LineChart 渲染 + 线下填充（色板色 × 0.2 alpha） |
| HorizontalBarChart | HorizontalBarChart(labels*, series*=[Series], title?, variant?) | 横向条（左标签列 + 条长 weight） |
| RadarChart | RadarChart(labels*, series*=[Series], title?) | Canvas 蛛网（轴辐 + 多边形填充/描边） |
| RadialChart | RadialChart(slices*=[Slice], title?) | Canvas 同心环弧（值归一） |
| SingleStackedBarChart | SingleStackedBarChart(slices*=[Slice], title?) | 单条水平堆叠条 + 图例 |
| ScatterChart | ScatterChart(datasets*=[ScatterSeries], title?, xLabel?, yLabel?) | Canvas 散点（系列配色 + 轴标签） |

既有扩展：BarChart 增官方形态（labels+series+variant=grouped/stacked，buckets 缺省时启用；
多系列并排/堆叠）；PieChart 增 variant；LineChart 增 variant。

### 3.5 新增·卡块（M4d，12 个）

Block 壳共性：items*=[Item...]（≥1 渲染，官方 min2 降为 1 容错）、layout?=grid/carousel
（grid→FlowRow 双列、carousel→横滑）、action?（点击卡 → 宿主动作）。

| 组件 | Item 结构 | 渲染 |
|------|-----------|------|
| SnippetCardBlock/Item | {lhs*: IconText/ImageText 形, rhs?: Text/BoldText 形} | 迷你卡：左标签 + 右值 |
| OverviewCardBlock/Item | {top*: IconText/ImageText/Text 形, bottom?: Metric 形} | 迷你卡：上标题 + 下指标 |
| ContextCardBlock/Item | {title*: string, body?} | 着色迷你卡（标题 + 正文） |
| CompositeCardBlock/Item | {header?, body*=[...], footer?} | 富卡：header + body 栈 + footer |
| VisualCardBlock/Item | {body*=string, bgImageSrc?, tag?} | 大图底 + 底部粗体文字板 |
| MetricIndicatorInline | MetricIndicatorInline(value*, subtext?, trend?={direction,value}) | 行内指标值 + 趋势箭头 |
| MetricIndicatorWithStrikethrough | (value*, subtext?, previousValue?, trend?) | 旧值删除线 + 新值 |

> 注：官方 Text/BoldText/IconText/ImageText/ImageTextLarge/MetricIndicator* 名义上「仅卡块内」，
> 本仓不强制位置约束（渲染器递归解析，位置无关；prompt 引导放卡块内）。

## 4. 落点清单（每批）

1. shared `OpenUiLibrary.kt`：schema 注册（本批组件）；`OpenUiLibraryM2Test` 计数钉住更新
   （21 → 82，含全量 keys 清单）
2. androidApp `OpenUiNodeRenderer.kt`：渲染分支（复用 tokens/色板/既有渲染器）
3. server `RenderOpenUiValidator.kt`：ALLOWED_COMPONENTS 白名单同步；Image src https 正则
4. server `RenderOpenUiToolService.kt`：工具描述组件签名段扩充（压缩写法，一组一行）+
   表单 name→$name 写入约定段
5. 测试：shared 计数/同源钉住；server 白名单同源钉住；androidApp 渲染冒烟（如既有模式）
6. 文档：`OPENUI_CARD_DESIGN_LANGUAGE.md` §5 速查表补新组件行；server/shared AGENTS 各一句

## 5. 验收

- `./gradlew :shared:allTests`（或对应 JVM test task）绿、`:androidApp:testDebugUnitTest` 绿、
  `./gradlew -p server test` 绿
- 真机：让云端 Agent 出一张「组件博览卡」覆盖新组件代表（Steps/RadarChart/Form/Chips/
  OptionCards/卡块/Modal），目检渲染 + 交互写 state 生效
- server 部署生产
