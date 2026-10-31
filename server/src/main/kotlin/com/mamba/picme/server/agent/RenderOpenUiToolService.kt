package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

/**
 * render_ui_openui——云端 Agent 的 OpenUI 发卡工具（openui P2，spec《openui-wire-format-toggle》§5）。
 *
 * 与 [RenderUiToolService]（A2UI 线）互斥注册：AgentRoutes 按请求头 X-GenUI-Format
 * 二选一（缺头/未知值默认 A2UI，向后兼容），LLM 一轮只见其中一套工具描述。
 *
 * 工具体是 CLOUD 侧空执行：OpenUI 程序不经 tool-result 回环，而是经
 * AgentRunner.onToolCallStarting 落的 action 事件（tool+argsJson）随 SSE 下行，
 * 端侧 CloudAgentTurnRunner "action" 分支拦截渲染。工具只回确认文本。
 *
 * ⚠️ @LLMDescription 与 shared `OpenUiContract`/`OpenUiLibrary` 的字面量
 * （data_ui_openui/local_handle/组件签名/$selected 约定）是字面量副本关系
 * （server 不依赖 :shared），改动须双向同步核对。
 */
class RenderOpenUiToolService {

    companion object {
        // 字符串模板插值只许引用 const（编译期常量约束），$selected 的 $ 由此而来
        private const val D = "$"

        /**
         * 黄金示例（program 骨架 SSOT）：@LLMDescription 内嵌文本与
         * RenderOpenUiValidatorTest 同源引用（A2UI 线 RENDER_UI_EXAMPLE 先例），
         * 改示例测试必红——防描述与校验器脱节。handleId 用合法形态的样例值
         * （<id> 占位过不了 LOCAL_HANDLE_FORM 形态校验，示例必须自身合法）。
         */
        const val GOLDEN_STATS_PROGRAM =
            D + "selected = \"\"\n" +
                "buckets = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#histogram\" }, [])\n" +
                "root = Card([Text(\"相册按月分布\", \"HEADLINE\"), Text(\"按月统计\", \"CAPTION\"), " +
                "BarChart(\"每月照片数\", buckets, " + D + "selected)])"

        /** 图文混排黄金示例（搜索拿 handle 后，照片与文字同卡混排）。 */
        const val GOLDEN_MIXED_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([Text(\"海边之旅\", \"HEADLINE\"), " +
                "Row([Image(photos[0].id), Image(photos[1].id), Image(photos[2].id)]), " +
                "Text(photos[0].date, \"CAPTION\")])"

        /**
         * 交互黄金示例（M2，spec《openui-component-library-m2-design》§5）：
         * BarChart 点选 + Text($selected) 联动文案 + CheckBox 勾选回写。
         */
        const val GOLDEN_INTERACTIVE_PROGRAM =
            D + "selected = \"\"\n" +
                D + "onlyFavorites = false\n" +
                "buckets = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#histogram\" }, [])\n" +
                "root = Card([Text(\"我的相册\", \"HEADLINE\"), " +
                "BarChart(\"每月照片数\", buckets, " + D + "selected), " +
                "Text(\"当前选中: \" + " + D + "selected, \"CAPTION\"), " +
                "CheckBox(\"只看精选\", " + D + "onlyFavorites)])"

        /**
         * 图文报表黄金示例（M3，spec《openui-component-library-m3-design》§4.2）：
         * Table + PieChart + Callout 组合（纯骨架无 Query 绑定亦可发卡）。
         */
        const val GOLDEN_REPORT_PROGRAM =
            "root = Card([Text(\"相册健康报告\", \"HEADLINE\"), " +
                "Callout(\"本月新增 328 张，存储占用健康\", \"结论\", \"SUCCESS\"), " +
                "PieChart(\"类目占比\", [{label: \"人像\", value: 142}, {label: \"风景\", value: 98}]), " +
                "Table([{label: \"月份\"}, {label: \"数量\"}], " +
                "[[\"2026-01\", 128], [\"2026-02\", 200]])])"

        /**
         * 统计大数字黄金示例（stat tile 构图，设计规范 §4.1）：DISPLAY 数字 + CAPTION 弱色
         * 标签，Row 等宽分栏放 2~4 个。反模式：Text("7894 张照片") 纯文本统计行。
         */
        const val GOLDEN_STAT_TILE_PROGRAM =
            "root = Card([Text(\"相册年度统计\", \"HEADLINE\"), " +
                "Row([Column([Text(\"7,120\", \"DISPLAY\"), Text(\"照片总数\", \"CAPTION\")], align = \"center\"), " +
                "Column([Text(\"771\", \"DISPLAY\"), Text(\"视频总数\", \"CAPTION\")], align = \"center\"), " +
                "Column([Text(\"12\", \"DISPLAY\"), Text(\"覆盖月份\", \"CAPTION\")], align = \"center\")])])"

        /**
         * 图文排版黄金示例（2026-10-30 图文组合固化）：相册照片走 ImageTextLarge/ImageText
         * 组合组件的 photo 键（版式结构性锁死），不再 Row 裸拼 Image+Text。
         */
        const val GOLDEN_PHOTO_TEXT_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([Text(\"本周精选\", \"HEADLINE\"), " +
                "ImageTextLarge(\"海边日落\", photo = photos[0].id, subtitle = \"2026-07-18\"), " +
                "ImageText(\"山顶云海\", photo = photos[1].id, subtitle = \"2026-07-19\"), " +
                "ImageText(\"城市夜景\", photo = photos[2].id, subtitle = \"2026-07-20\")])"

        /**
         * 通用布局黄金示例（2026-10-30 通用布局原语批次；2026-10-31 相册批次修订）：
         * 照片墙走 PhotoGrid 固化件（Grid 装 Image 已被 lint 引导至 PhotoGrid），
         * Grid/Row weights 留给图表与混合内容的比例分栏（1:2 按比例左图右文）。
         */
        const val GOLDEN_LAYOUT_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([Text(\"本周精选\", \"HEADLINE\"), " +
                "PhotoGrid(photos = [photos[0].id, photos[1].id, photos[2].id, photos[3].id], columns = 2), " +
                "Spacer(\"s\"), " +
                "Row([Image(photos[4].id), " +
                "Column([Text(\"海边日落\"), Text(\"2026-07-18 · 三亚\", \"CAPTION\")])], " +
                "weights = [1, 2], align = \"center\")])"

        /**
         * 经典组合版式黄金示例（2026-10-31 经典组合版式批次）：Banner 题图横幅 +
         * StatRow 统计行 + List([ListItem...]) 经典列表——固化构图一行调用，
         * 不再 Row+Column+Text(DISPLAY) 拼装。
         */
        const val GOLDEN_CLASSIC_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([Banner(\"本周精选\", photo = photos[0].id, subtitle = \"2026-07-18 · 三亚\"), " +
                "StatRow([{value: \"7,120\", label: \"照片总数\"}, {value: \"128\", label: \"本周新增\"}, " +
                "{value: \"12\", label: \"覆盖月份\"}]), " +
                "ListItem(\"海边日落\", photo = photos[1].id, trailing = \"07-18\"), " +
                "ListItem(\"山顶云海\", photo = photos[2].id, trailing = \"07-19\")])"

        /**
         * 相册 APP 构图黄金示例（2026-10-31 相册 APP 组合方式批次）：CardHeader 时间分组
         * 标题 + PhotoGrid 方形照片网格 + AvatarRow 人物头像条——相册 APP 签名形态一行调用，
         * 不再 Grid+Image / Row 拼装。actionLabel 必配 action（2026-10-31 真机回归补：
         * 只传 actionLabel 不传 action 曾致「查看全部」点击无效；@ToAssistant 以用户消息
         * 上行回话，端侧有同文案兜底但显式文案语境更准）。
         */
        const val GOLDEN_PHOTO_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([CardHeader(\"2026年7月\", subtitle = \"128 张\", " +
                "actionLabel = \"查看全部\", action = @ToAssistant(\"查看全部 2026年7月的照片\")), " +
                "PhotoGrid(photos = [photos[0].id, photos[1].id, photos[2].id, photos[3].id, " +
                "photos[4].id, photos[5].id], columns = 3), " +
                "AvatarRow([{photo: photos[0].id, label: \"妈妈\"}, {photo: photos[1].id, label: \"宝宝\"}])])"

        /**
         * Icon 预定义图标名清单（59 枚，描述内嵌用）——字面量副本与
         * [RenderOpenUiValidator.ICON_NAMES] 同源，测试钉住（改动须四处核对，
         * 见 RenderOpenUiValidator.ICON_NAMES KDoc）。
         */
        const val ICON_NAMES_TEXT =
            "accountCircle, add, arrowBack, arrowForward, attachFile, calendarToday, call, " +
                "camera, check, close, delete, download, edit, error, event, fastForward, " +
                "favorite, favoriteOff, folder, help, home, info, locationOn, lock, lockOpen, " +
                "mail, menu, moreHoriz, moreVert, notifications, notificationsOff, pause, " +
                "payment, person, phone, photo, play, print, refresh, rewind, search, send, " +
                "settings, share, shoppingCart, skipNext, skipPrevious, star, starHalf, " +
                "starOff, stop, upload, visibility, visibilityOff, volumeDown, volumeMute, " +
                "volumeOff, volumeUp, warning"
    }

    @Tool(customName = "render_ui_openui")
    @LLMDescription(
        """向用户下发一张 OpenUI 原生渲染卡（图表/统计可视化首选，优于 render_html）。
约束（必须全部满足，否则端上拒绝渲染或静默丢弃组件）：
1. program 是 OpenUI Lang 1.0 程序：每行一条语句，形如 `名 = 表达式`；组件调用
   大写开头、只用位置参数；字符串一律双引号；
2. 必须有 `root = ...` 语句（root 是整个组件树的入口）；
3. 可用组件签名（逐字遵守，禁发明属性；* 为必填）：
   - Text(text*, role?, colorRole?)：text 可传 ${'$'}绑定变量（字符串/数字/布尔自动转文本，
     如 Text(${'$'}selected) 显示柱图点选月份），随 state 回写联动刷新（见规则 6）；
     role∈HEADLINE|SUBHEAD|BODY|CAPTION|DISPLAY（默认 BODY；DISPLAY 是统计大数字 32sp
     加粗等宽数字，stat tile 专用，形如 Text("7,120", "DISPLAY")，一屏 ≤4 个），
     colorRole∈PRIMARY|SURFACE|ERROR|ON_SURFACE（默认 ON_SURFACE）；
     ⚠️ SURFACE 是卡面背景色，文字上使用会隐形——文字颜色只用 PRIMARY/ON_SURFACE/ERROR；
   - Row(children*, spacing?, align?, weights?) / Column(children*, spacing?, align?) /
     Card(children*, spacing?, title?, align?)：
     children 是组件数组（[组件1, 组件2]），spacing∈COMPACT|STANDARD|COMFORTABLE（默认 STANDARD）；
     align 是交叉轴对齐∈start|center|end|stretch|baseline（官方 align-items 语义，缺省
     stretch = 子组件铺满交叉轴：Row 子栏撑满行高、Column/Card 内容撑满整宽；start/center/end
     = 顶/中/底（Row）或左/中/右（Column/Card）对齐，baseline = 子组件首行基线对齐）——
     stat tile 数字栏写 align = "center"，高矮不一的栏顶线对齐写 align = "start"；
     正文/表单缺省 stretch 即为官方形态无需显式传；
     Row 的子组件默认等宽分栏；weights 是数字数组、与子组件一一对应按比例分栏（CSS
     flex-grow 语义）——按比例左图右文写法 Row([Image(photos[0].id), Column([...])],
     weights = [1, 2], align = "center")（Divider/Spacer 分隔件不占 weights 槽位）；
     Card 有 title 时渲染头部标题区；
   - Grid(children*, columns?, spacing?, align?)：通用 N 列网格（写法
     Grid([a, b, c, d], columns = 2)），columns∈1..4（默认 2），children 按序填满各行、
     每格等宽；spacing/align 同 Row 词汇；装图表/混合内容用它，装相册照片请用
     PhotoGrid 固化件（Grid 内放 ≥2 个 Image 会被 lint 引导）；
   - Box(children*, align?)：通用叠放容器——children 按数组序从底到顶叠放，align 是
     九值方位∈topStart|topCenter|topEnd|centerStart|center|centerEnd|bottomStart|
     bottomCenter|bottomEnd（默认 topStart）——图上叠文字/角标写法
     Box([Image(photos[0].id), Tag("精选")], align = "bottomStart")；
   - Spacer(size?)：通用留白件，size∈xs|s|m|l|xl（默认 m）——Row 内占宽、Column/Card
     内占高，段落间节奏控制写法 Spacer("s")；
   - Button(label*, action?, variant?, size?)：action 是裸动作表达式、最外层不要加引号，
     形如 Button("查看", @OpenUrl("https://..."))（"@OpenUrl(...)" 包成字符串会被拒）；
     variant∈primary|secondary|tertiary（默认 primary 实心主按钮、占满整行宽度——主 CTA
     独占一行；secondary 淡色 / tertiary 文字按钮为内容宽）；size 收而不用；
     一卡 Button ≤3 个（行为色面积收敛，次要动作并入主 Button 或改 Markdown 文本）；
   - BarChart(title?, buckets?, selected?, labels?, series?, variant?)：buckets 是 [{label,count}]
     数据引用；或官方形态 labels=["x1",...] + series=[{name, values:[数值,...]}]（两种数据面二选一）；
     variant∈grouped|stacked（多系列并排/堆叠）；buckets >12 个时先聚合（按年/按季）再发卡，
     避免超密横滑柱图；
   - Image(photo?, aspect?, src?, alt?)：photo 与 src 二缺一必给——photo 只许填 photos 数组元素的
     id（如 photos[0].id），禁止写字面量数字 id（防臆造）；src 是 https:// 公网图片地址
     （禁本地路径/uri）；aspect 是宽高比（宽/高）——省略时端侧按照片真实比例渲染
     （竖屏图不再被横框拦腰，推荐省略），只有要强制造型（统一方块拼图/横幅封面）才显式传
     （横图 1.5~1.78，竖图 0.75）；Row([Image(...), Image(...)]) 可多张并排等宽；
     单卡图片≤12 张（内存上限）；
   - Icon(name*, colorRole?)：name 只用预定义图标名（59 枚清单见规则 6 末），禁臆造；
     装饰性图标省略 colorRole（自动弱色），PRIMARY 只留强调性图标；
   - Divider(axis?=HORIZONTAL|VERTICAL) / Separator(orientation?=horizontal|vertical)：分隔线（同义别名）；
   - List(children*, direction?, spacing?)：direction∈VERTICAL|HORIZONTAL（默认 VERTICAL
     限高纵向滚动，HORIZONTAL 横滑行）；
   - Tabs(tabs*)：tabs 是 [{title: "页签标题", children: [组件...]}]，至少一个页签；
   - CheckBox(label*, checked*) / Switch(label*, checked*)：布尔项与开关（语义对偶），
     checked 传 ${'$'}布尔绑定（见规则 6），点按回写；裸 true/false 字面量只读不回写；
   - ChoicePicker(label?, options*, selected*)：options 是 [{label: "显示文案", value: "值"}]，
     selected 传 ${'$'}字符串绑定，点选回写 value；裸字面量只读；
   - Slider(label?, value*, min?, max?)：value 传 ${'$'}数字绑定，min/max 默认 0/100，
     拖动结束回写数值；裸数字字面量只读；label 可省略，省略时 value 须写命名实参
     （形如 Slider(value=${'$'}level)——位置参数首槽是 label）；
   - Table(columns*, rows*)：行式表格（唯一表格形态，无列式）；columns 是 [{label: "列名"}]，
     rows 是行式嵌套数组 [[cell, ...], ...]（每行一条内层数组，cell 只许字符串/数字/布尔，
     单元格数与 columns 对齐）；
   - PieChart(title?, slices*, variant?)：占比分布；slices 是 [{label: "分类名", value: 数值}]；
     variant∈donut|pie（默认 donut 环形）；
   - LineChart(title?, labels*, series*, variant?)：趋势折线；labels 是横轴标签数组
     ["x1", ...]，series 是 [{name: "系列名", values: [数值, ...]}]
     （values 长度与 labels 对齐）；variant∈linear|natural|step（折线插值形态）；
   - Callout(text*, title?, tone?)：结论/告警高亮块，tone∈INFO|SUCCESS|WARNING|ERROR|NEUTRAL
     （默认 INFO）；
   - Markdown(text*) / MarkDownRenderer(textMarkdown*, variant?)：富文本正文（同义别名，
     标题/加粗/列表混排），text 可传 ${'$'}绑定（同 Text 口径）；
   - Accordion(items*)：可折叠分区；items 是 [{title: "分区标题", children: [组件...]}]；
   ── 官方组件库扩充（同套语法，数据/绑定/动作写法与上面完全一致）──
   - 布局容器：Stack(children*, direction?=row|column, gap?=none|xs|s|m|l|xl|2xl, align?, justify?, wrap?)
     （direction=row 同 Row、缺省 column 同 Column，wrap=true 自动换行；align 同上方官方
     align-items 语义五值、缺省 stretch，justify 是主轴分布∈start|center|end|between|around|evenly）/
     Carousel(children*, variant?=card|sunk)（横滑卡片行）/
     Buttons(buttons*=[Button...], direction?=row|column) /
     Modal(title*, open*, children*)（open 传 ${'$'}布尔绑定，真值时弹窗，关闭即回写 false）；
   - 标题与文案：CardHeader(title*, subtitle?) / InlineHeader(title*, description?) /
     TextContent(text*, size?=small|default|large|small-heavy|large-heavy) /
     Label(text*)（弱色小标签）/ BoldText(value*, subtext?, size?=xs|sm|md|lg)（value 可传 ${'$'}绑定）；
   - 高亮与标签：TextCallout(title*, description?, variant?=neutral|info|success|warning|danger)
     （走 Callout 渲染）/ Tag(text*, icon?, size?=sm|md|lg, variant?=neutral|info|success|warning|danger) /
     TagBlock(tags*=["标签", ...]) / CodeBlock(codeString*, language?)（等宽代码块）；
   - 图文项：IconText(title*, icon?, subtitle?, bold?, layout?=horizontal|vertical) /
     ImageText(title*, photo?, src?, subtitle?, imageSize?) / ImageTextLarge(title*, photo?, src?, subtitle?) /
     ImageBlock(src*, alt?) / ImageGallery(images*=[{src*, alt?, details?}])
     （ImageText/ImageTextLarge 的 photo 与 src 二选一——photo 写 photos[0].id 相册投影
     （与 Image(photo) 同通道，禁字面量数字），src 是 https:// 公网图；其余 src/bgImageSrc/image
     图片地址一律 https:// 公网图、禁本地路径/uri）；
   - 列表：EntityList(rows*=[{left*, right*}], headerLeft?, headerRight?, footerLeft?, footerRight?)（双列键值行）/
     ListBlock(items*=[{title*, subtitle?, image?, actionLabel?, action?}], variant?=number|image) /
     Steps(items*=[{title*, details?}])（纵向步骤条）/
     ListItem(title*, subtitle?, photo?, image?, icon?, trailing?, actionLabel?, action?)（经典列表行：
     前件三选一——photo 相册投影 48dp 图（写 photos[0].id，禁字面量数字）> image https 图 > icon 徽章
     （59 枚枚举名）；trailing 是尾部弱色小字（如日期/计数）；List([ListItem(...)]) 即经典列表，独立用亦可）；
   - 经典组合版式（固化构图，优先于原语拼装）：StatRow(items*=[{value*, label?}])（经典统计行：
     2~4 项等分，每项 DISPLAY 大数字 + CAPTION 弱色标签居中——统计数字首选，替代
     Row+Column+Text("DISPLAY") 拼装）/ Banner(title*, subtitle?, photo?, src?, aspect?)（通栏题图横幅：
     图 + 底部渐变蒙层 + 左下白字标题；photo 写 photos[0].id 相册投影、src 是 https:// 公网图，
     二选一，皆无纯色底；aspect 缺省 16:9）；
   - 相册组合件（相册 APP 签名形态固化，照片网格/人物条首选）：PhotoGrid(photos*=[相册投影 id
     数组], columns?=2|3|4 缺省 3)（方形 1:1 + 人脸聚焦 + 密缝照片网格，单件最多 12 张——
     替代 Grid+Image 拼装，元素写 photos[0].id 投影、禁字面量数字）/ AvatarRow(items*=[{photo*,
     label?}])（人物圆形头像横滑条：圆形人脸聚焦 + 名字居下，photo 写 photos[0].id 投影）；
     CardHeader 增可选 actionLabel/action（分组标题尾部「查看全部」动作位——
     CardHeader("2026年7月", subtitle = "128 张", actionLabel = "查看全部",
     action = @ToAssistant("查看全部 2026年7月的照片"))，有 actionLabel 必配 action）；
   - 表单（name 键控直写 state：用户交互即 @Set(${'$'}<name>, 值)——如 Input(name="city")
     键入后 Text("城市: " + ${'$'}city) 经 ${'$'}name 绑定联动读；Form 仅视觉分组，无提交作用域；
     ${'$'}变量同样先独占一行声明默认值）：
     Form(name*, children*) / FormControl(label*, input*=[输入组件], hint?) /
     Input(name*, placeholder?, type?=text|email|password|number|url) / TextArea(name*, placeholder?, rows?) /
     Select(name*, items*=[{value*, label*}], placeholder?) / DatePicker(name*, mode*=single|range) /
     CheckBoxGroup(name*, items*=[{label*, description?, name*, defaultChecked?}])（每项写 ${'$'}<item.name>）/
     RadioGroup(name*, items*=[{label*, description?, value*}], defaultValue?) /
     SwitchGroup(items*=[{label*, description?, name*, defaultChecked?}], variant?=clear|card|sunk)（每项写 ${'$'}<item.name>）/
     Chips(name*, items*=[{value*, label*, icon?}], type?=single|multiple) /
     OptionCards(name*, items*=[{value*, title*, subtitle?}], type?=single|multiple)
     （type=single 写 ${'$'}name=值；multiple 写 ${'$'}name=逗号拼接串——选项 value 内禁止逗号）/
     EditableTable(name*, columns*=[{type*=text|number|date-single|select|url, key*, header*}],
     data*=[{id*, values*}])（单元格编辑即写 @Set(${'$'}name, JSON 行数组)）；
   - 图表（labels/series 形态同 LineChart）：AreaChart(title?, labels*, series*, variant?) /
     HorizontalBarChart(title?, labels*, series*, variant?=grouped|stacked) /
     RadarChart(title?, labels*, series*) /
     RadialChart(title?, slices*=[{category*, value*}]) /
     SingleStackedBarChart(title?, slices*=[{category*, value*}]) /
     ScatterChart(title?, datasets*=[{name*, points*=[[x,y],...]}], xLabel?, yLabel?)；
   - 卡块（共性：items* + layout?=grid|carousel 双列网格/横滑 + action? 点击卡动作）：
     MetricIndicatorInline(value*, subtext?, trendDirection?=up|down, trendValue?) /
     MetricIndicatorWithStrikethrough(value*, subtext?, previousValue?, trendDirection?, trendValue?) /
     SnippetCardBlock(items*=[{lhs*=[组件], rhs?=[组件]}]) /
     OverviewCardBlock(items*=[{top*=[组件], bottom?=[组件]}]) /
     ContextCardBlock(items*=[{title*, body?}]) /
     CompositeCardBlock(items*=[{header?=[组件], body*=[组件], footer?=[组件]}]) /
     VisualCardBlock(items*=[{body*, bgPhoto?, bgImageSrc?, tag?}])（题图卡：bgPhoto 相册投影 id
     （photos[0].id，禁字面量数字）与 bgImageSrc https 公网图二选一、bgPhoto 优先）；
   - 元素形状（仅作父组件数组元素的对象形状，不独立使用——优先用上面对象数组写法，
     组件形仅保底；ListItem 已升格为经典列表行见上方列表段）：TabItem / AccordionItem /
     StepsItem / SelectItem /
     CheckBoxItem / RadioItem / SwitchItem / ChipItem / OptionCard / Series / Slice /
     ScatterSeries / Point / SnippetCardItem / OverviewCardItem / ContextCardItem /
     CompositeCardItem / VisualCardItem / StatItem；
4. 数据禁令：相册数据一律经查询绑定，写法逐字为
   `buckets = Query("local_handle", { handle: "local://scan/<id>#histogram" }, [])`
   （第三个参数 [] 是数据缺失时的降级默认值），其中 handleId 只许来自
   search_media/refine_media_search 结果尾部的「handle: scan/<id>」；
   照片清单取数写法逐字为
   `photos = Query("local_handle", { handle: "local://scan/<id>#photos" }, [])`，
   得到 [{id, date}]（按搜索相关度排序，最多 60 张，date 是 "yyyy-MM-dd"
   拍摄日期可直接给 Text 用）；
   禁止臆造 handleId，禁止把路径/uri/媒体清单写进 program 任何位置；
   handle id 只在 program 里引用，回复正文禁止向用户展示 handle id；
5. Query 推荐独占整条语句右侧（先 `buckets = Query("local_handle", …, [])` 单独成行，
   组件参数里引用 buckets——见规则 7 黄金示例；端侧引擎已支持内联提升，独占写法最稳）；
6. state 与交互约定（交互组件 = BarChart/CheckBox/ChoicePicker/Slider/Switch/Modal +
   表单组件（name 键控 @Set 直写，见规则 3 表单段），可绑定文案 = Text/Markdown/BoldText）：
   每个用到的 ${'$'}变量先独占一行声明默认值（如 `${'$'}selected = ""`、`${'$'}level = 50`、
   `${'$'}flag = false`），再传给组件的可绑定参数（BarChart selected / CheckBox checked /
   Switch checked / ChoicePicker selected / Slider value / Text text）；端侧点选/勾选/拖值 → 回写该变量并
   整卡重渲染（如 Text(${'$'}selected) 随点选刷新显示当前选中项）；传裸字面量则只读展示
   不回写（BarChart 裸字符串点选高亮静默失效）；交互 state 不持久化，`${'$'}selected`
   之外变量重开卡回默认值；
   Icon 预定义图标名清单（59 枚，逐字使用，禁臆造）：
""" + ICON_NAMES_TEXT + """
7. fallbackSummary 写投影级摘要（如「327 张，数据在原设备」），端上数据缺失时降级展示；
8. 完整黄金示例（统计卡标准骨架——照此结构写：root = 语句必须有、独占一行、
   组件引用前面语句的变量名；program 不要包 markdown 代码围栏）：
""" + GOLDEN_STATS_PROGRAM + """
   图文混排黄金示例（搜索拿 handle 后，照片与文字同卡混排）：
""" + GOLDEN_MIXED_PROGRAM + """
   交互相册卡黄金示例（BarChart 点选 + Text 联动文案 + CheckBox 勾选回写）：
""" + GOLDEN_INTERACTIVE_PROGRAM + """
   图文报表黄金示例（M3：Table + PieChart + Callout 组合，纯骨架无 Query 绑定亦可发卡）：
""" + GOLDEN_REPORT_PROGRAM + """
   统计大数字黄金示例（stat tile 构图，设计规范 §4.1：DISPLAY 数字 + CAPTION 弱色标签，
   三个指标并排——有 2~4 个关键统计数字时优先用此构图，别只写一段 BODY 文案）：
""" + GOLDEN_STAT_TILE_PROGRAM + """
   通用布局食谱（经典版式照方抓药，通用原语组合优先于业务组合组件）：
   - 照片网格（四宫格/三宫格/双列照片墙）→ PhotoGrid(photos = [...], columns = 2|3)
     （方形人脸聚焦裁切已固化；Grid 只用于图表/混合内容网格）；
   - 按比例左图右文（上下对齐）→ Row([Image(photos[i].id), Column([Text(标题), Text(副题, "CAPTION")])],
     weights = [1, 2], align = "center")（图窄文宽改 [1, 3]，顶线对齐改 align = "start"）；
   - 图上叠文字/角标/浮层 → Box([Image(...), Tag(...) 或 Text(...)], align = "bottomStart")；
   - 段落间节奏留白 → Spacer("s") / Spacer("m")；
   - 纯多图并排不带说明 → Row([Image(photos[0].id), Image(photos[1].id)])（≤4 张）；
   - 一行写法便捷糖（不想组合时）：ImageTextLarge(title, photo=photos[i].id, subtitle=...)
     单图主打、多个 ImageText(title, photo=photos[i].id) 叠 Column 小图列表；
   ⚠️ 不支持文字环绕图片（float wrap 无此形态）；Row 裸拼 Image+Text 必须传 weights
   按比例分栏，否则等宽分栏会把缩略图拉成半宽栏（lint 会提示）；
   经典组合版式（2026-10-31，固化构图优先于原语拼装）：
   - 统计数字行（2~4 个关键指标）→ StatRow([{value: "7,120", label: "照片"}, ...])
     （等分居中构图已固化，别再 Row+Column+Text("DISPLAY") 拼装）；
   - 通栏题图横幅（单图主打 + 标题叠字）→ Banner("标题", photo = photos[i].id, subtitle = ...)；
   - 经典列表行（图/图标 + 标题副题 + 尾部小字）→ List([ListItem("标题", photo = photos[i].id,
     trailing = "07-18"), ...]) 或多行 ListItem 直接排进 Card；
   - 双列题图卡（相册照片）→ VisualCardBlock([{body: "精选", bgPhoto: photos[i].id}])；
   - 页内分区标题 → CardHeader("分区标题", "可选副题")；
   通用布局黄金示例（PhotoGrid 照片墙 + 1:2 左图右文）：
""" + GOLDEN_LAYOUT_PROGRAM + """
   图文排版黄金示例（ImageTextLarge/ImageText 便捷糖形态）：
""" + GOLDEN_PHOTO_TEXT_PROGRAM + """
   经典组合版式黄金示例（Banner 题图横幅 + StatRow 统计行 + ListItem 列表行——
   相册卡首选骨架）：
""" + GOLDEN_CLASSIC_PROGRAM + """
   相册 APP 构图规则（2026-10-31，对标 Google Photos/Apple 照片/小米相册签名形态——
   相册类卡片按此构图，别再自由拼装）：
   - 时间分组网格（相册主视图签名形态）→ CardHeader("2026年7月", subtitle = "128 张",
     actionLabel = "查看全部", action = @ToAssistant("查看全部 2026年7月的照片")) +
     PhotoGrid(photos = [...], columns = 3)；
   - 人物区（面孔签名形态）→ AvatarRow([{photo: photos[i].id, label: "妈妈"}, ...])；
   - 回顾卡（年度/月度杂志感）→ Banner 首屏 + StatRow 关键数字 + PhotoGrid 摘选；
   - 相册列表（封面 + 名称 + 计数）→ List([ListItem(title, photo = photos[i].id,
     subtitle = 日期, trailing = "128 张"), ...])；
   - 回忆条（大卡横滑）→ VisualCardBlock([...], layout = "carousel")（既有件）；
   ⚠️ 照片网格 ≥2 张一律 PhotoGrid，禁用 Grid+Image 拼装（帧随各图真实比例会犬牙交错，
   lint 会提示）；CardHeader 有 actionLabel 必须配 action（@ToAssistant("查看全部 xx")
   以用户消息上行回话——缺省端侧虽兜底发同文案，显式写语境更准，lint 会提示）；
   相册 APP 构图黄金示例（时间分组网格 + 人物头像条——相册卡签名骨架）：
""" + GOLDEN_PHOTO_PROGRAM
    )
    suspend fun renderUiOpenUi(
        @LLMDescription("OpenUI Lang 1.0 程序原文（骨架 + Query 绑定，无数据）")
        program: String,
        @LLMDescription("投影级降级摘要（数据缺失时端上展示的占位文案）")
        fallbackSummary: String,
    ): String {
        // 与发射点门控同源校验（review 🟡4 对偶）：非法回 error 文本作 Observation
        // 回灌，LLM 同轮修正重试（同 RenderUiToolService 闭环口径）
        RenderOpenUiValidator.validateToError(program)?.let { error -> return error }
        // 构图软警告（设计规范第一刀配套）：卡照发，建议附在回执里供下次发卡改进
        val warnings = RenderOpenUiValidator.lintComposition(program)
        return buildString {
            append("UI 已下发端侧渲染（OpenUI）")
            if (warnings.isNotEmpty()) {
                append("。构图建议（本卡已下发、无需重发，下次发卡请改进）：")
                warnings.forEach { warning -> append("\n- ").append(warning) }
            }
        }
    }
}
