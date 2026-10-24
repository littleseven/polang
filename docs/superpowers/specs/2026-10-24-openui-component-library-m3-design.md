# OpenUI 组件库 M3：图表/表格/内容扩充（14 → 21 组件）设计

> **日期**：2026-10-24
> **状态**：在途
> **上位**：spec《openui-genui-parallel-design》（P1 引擎/P2 服务端闭环）、spec《openui-component-library-m2-design》（M2 14 组件）、ADR-016
> **动机**：官方 openuiLibrary 注册 76 组件（[thesysdev/openui openuiLibrary.tsx](https://github.com/thesysdev/openui/blob/main/packages/react-ui/src/genui-lib/openuiLibrary.tsx)），polang M2 仅 14。M3 按相册场景价值选取 7 个扩充，同时引入 PRIMITIVE_ARRAY prop 类型打开图表/表格数据面。

## 1. 范围

新增 7 组件（14 → 21）：

| 组件 | 分组 | 价值场景 |
|------|------|----------|
| Table | 表格 | 相册统计明细（按月/按类目行式数据） |
| PieChart | 图表 | 占比分布（类目占比、人脸/无人脸占比） |
| LineChart | 图表 | 趋势（拍摄量随时间曲线） |
| Callout | 内容 | 结论/告警高亮块（健康报告结论） |
| Markdown | 内容 | 富文本正文（加粗/列表/标题混排） |
| Accordion | 布局 | 可折叠分区（明细收起展开） |
| Switch | 表单 | 开关式布尔（与 CheckBox 语义对偶） |

**明确不做**（官方有但本批不取，记帐防重复评估）：EditableTable、Form/Input/TextArea/Select/DatePicker/RadioGroup/Chips/OptionCards（表单提交体系，相册卡无回传语义）、Modal/Carousel/Steps、CodeBlock、ImageGallery（Row([Image...]) 已覆盖）、17 个 Cards 构建块（CompositeCard 等，与 Card 职责重叠）、RadarChart/RadialChart/ScatterChart/HorizontalBarChart/SingleStackedBarChart（边际价值低）。

## 2. 新 prop 类型：PRIMITIVE_ARRAY

`OpenUiPropType.PRIMITIVE_ARRAY`：收 `OpenUiValue.Arr`，元素限 Str/Num/Bool 或**一层嵌套 Arr**（表格行 `[[cell,...],...]`）。Binding 不收（数组整体绑定无交互语义，M3 不需要）。

- shared `OpenUiLibrary` 声明 + `OpenUiEval`/schema 校验（元素类型不符丢该组件不炸卡，同既有口径）
- 支持嵌套进 OBJECT_ARRAY objectFields（LineChart.series[].values），与 Tabs 的 COMPONENT_LIST 递归同机制

## 3. 组件签名（位置参数序 = 声明序，逐字为契）

```
Table(columns*, rows*)                       columns = [{label: string}]（OBJECT_ARRAY）
                                             rows = [[cell, ...], ...]（PRIMITIVE_ARRAY，cell=string/number/bool；
                                             每行一条内层数组，单元格数与 columns 对齐，缺 cell 渲染空串）
PieChart(title?, slices*)                    slices = [{label: string, value: number}]（OBJECT_ARRAY）
LineChart(title?, labels*, series*)          labels = ["x1", ...]（PRIMITIVE_ARRAY）
                                             series = [{name: string, values: [number, ...]}]（OBJECT_ARRAY
                                             嵌套 PRIMITIVE_ARRAY；values 长度与 labels 对齐，缺补 null 断点）
Callout(text*, title?, tone?)                tone ∈ INFO|SUCCESS|WARNING|ERROR（默认 INFO）
Markdown(text*)                              text BINDABLE_STRING（同 M2 Text 口径，$绑定联动）
Accordion(items*)                            items = [{title: string, children: [组件...]}]（OBJECT_ARRAY
                                             嵌套 COMPONENT_LIST，Tabs 同构）；展开态是渲染期本地态不进 OpenUI state
Switch(label*, checked*)                     checked BINDABLE_BOOLEAN（CheckBox 同构：点按 → @Set 回写，
                                             裸字面量只读）
```

设计取舍：
- **Table 行式**（非官方列式 Col 组件）：LLM 写生成功实测行式嵌套数组最稳；官方列式依赖 `@Each`/pluck 等我们引擎没有的函数面。
- **LineChart 双数组**（labels + series[].values）而非 buckets 对象数组：趋势图 x 轴共享，避免每 series 重复 label 串烧 token。
- **PieChart slices 与 BarChart buckets 字段名不同**（value vs count）：保持各自语义自描述，schema 层各自独立无共享。

## 4. 分层改动

### 4.1 shared（`domain/chat/openui/`）

- `OpenUiLibrary.kt`：+7 组件声明 + `OpenUiPropType.PRIMITIVE_ARRAY`；组件总数注释 14→21
- `OpenUiEval.kt`（schema 校验处）：PRIMITIVE_ARRAY 元素校验（Str/Num/Bool/一层 Arr 嵌套，不符丢组件）；OBJECT_ARRAY objectFields 支持 PRIMITIVE_ARRAY 递归
- 测试：commonTest 既有 OpenUi 测试文件追加——7 组件签名解析/校验正反用例、PRIMITIVE_ARRAY 边界（嵌套两层拒、元素类型混杂按既有口径处理）

### 4.2 server（`agent/`）

- `RenderOpenUiValidator.kt`：ALLOWED_COMPONENTS +7（注释「十四」→「二十一」）；无新正则（本批无 Icon 名字面量类新攻击面）
- `RenderOpenUiToolService.kt`：@LLMDescription 组件签名段 +7 行 + 新增图文报表黄金示例 `GOLDEN_REPORT_PROGRAM`（Table + PieChart + Callout 组合，companion const 与测试同源钉住，GOLDEN_* 先例）；规则 6 交互组件清单句补 Switch
- `RenderOpenUiValidatorTest.kt`：白名单正反用例 +7、黄金示例过校验钉住

### 4.3 androidApp（`features/chat/openui/OpenUiNodeRenderer.kt`）

- Table：表头行 + 数据行（行数 `take(MAX_TABLE_ROWS=50)` 护栏），等宽列，超宽横滑
- PieChart：Canvas 扇形 + 图例列（色板复用 BarChart 同族 token）；slices `take(12)` 护栏
- LineChart：Canvas 折线 + 点 + 横轴 label 抽稀（BarChart 标签抽稀先例）；series `take(4)` 护栏
- Callout：色块容器 + tone→Icon 映射（INFO=info/SUCCESS=check/WARNING=warning/ERROR=error）+ 可选 title 加粗行
- Markdown：复用 `features/common/chat/AgentMarkdown`（mikepenz，全 app 唯一 Agent 正文渲染入口）；$绑定解析失败丢组件同 Text 口径
- Accordion：页签外第二例渲染期本地态（remember 展开集，键 = items 标题列表，Tabs M2 review 修复同口径）
- Switch：M3 Switch 配 token 色（AppSlider 红线只管滑杆，Switch 无既有封装，用 M3 + DesignTokens）

### 4.4 文档

- 本 spec；`CHAT_CARD_CATALOG.md` OpenUI 卡条目组件清单 14→21
- `shared/AGENTS.md` §2 openui 段、「`server/AGENTS.md`」agent 域行同步

## 5. 验证

1. `JITPACK=true ./gradlew :shared:jvmTest` 绿（新用例含）
2. `./gradlew -p server build` 绿（validator 测试含）
3. `JITPACK=true ./gradlew :shared:assemble` 绿（iOS metadata 坑位④口径）
4. `./gradlew :androidApp:assembleDebug` 绿
5. 真机（92a0cfb8，GenUI=openui）：指令触发 Table/PieChart/LineChart/Callout/Accordion/Switch 同卡，验证渲染 + Switch 回写 + Accordion 展开收起 + 角标在
