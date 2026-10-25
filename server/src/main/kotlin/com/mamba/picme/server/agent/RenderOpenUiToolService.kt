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
                "Row([Column([Text(\"7,120\", \"DISPLAY\"), Text(\"照片总数\", \"CAPTION\")]), " +
                "Column([Text(\"771\", \"DISPLAY\"), Text(\"视频总数\", \"CAPTION\")]), " +
                "Column([Text(\"12\", \"DISPLAY\"), Text(\"覆盖月份\", \"CAPTION\")])])])"

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
3. 可用组件签名（逐字遵守，禁发明属性）：
   - Text(text*, role?, colorRole?)：text 可传 ${'$'}绑定变量（字符串/数字/布尔自动转文本，
     如 Text(${'$'}selected) 显示柱图点选月份）；role∈HEADLINE|SUBHEAD|BODY|CAPTION|DISPLAY
     （默认 BODY；DISPLAY 是统计大数字 32sp 加粗等宽数字，stat tile 专用，形如
     Text("7,120", "DISPLAY")，一屏 ≤4 个），
     colorRole∈PRIMARY|SURFACE|ERROR|ON_SURFACE（默认 ON_SURFACE）；
     ⚠️ SURFACE 是卡面背景色，文字上使用会隐形——文字颜色只用 PRIMARY/ON_SURFACE/ERROR；
     text 可传 ${'$'}绑定（如 Text(${'$'}selected)），随 state 回写联动刷新（见规则 6）；
   - Row(children*, spacing?) / Column(children*, spacing?) / Card(children*, spacing?)：
     children 是组件数组（[组件1, 组件2]），spacing∈COMPACT|STANDARD|COMFORTABLE（默认 STANDARD）；
     Row 的子组件等宽分栏（左右两栏排版写法 Row([左栏组件, 右栏组件])）；
   - Button(label*, action?)：action 是裸动作表达式、最外层不要加引号，
     形如 Button("查看", @OpenUrl("https://..."))（"@OpenUrl(...)" 包成字符串会被拒）；
   - BarChart(title?, buckets*, selected?)：buckets 是 [{label,count}] 数据引用；
     buckets >12 个时先聚合（按年/按季）再发卡，避免超密横滑柱图；
   - Image(photo*, aspect?)：相册照片（图文混排用）；photo 只许填 photos 数组元素的
     id（如 photos[0].id），禁止写字面量数字 id（防臆造）；aspect 是宽高比
     （宽/高，默认 1.33，横图可传 1.5~1.78，竖图 0.75）；
     Row([Image(...), Image(...)]) 可多张并排等宽；单卡图片≤12 张（内存上限）；
   - Icon(name*, colorRole?)：name 只用预定义图标名（59 枚清单见规则 6 末），禁臆造；
     装饰性图标省略 colorRole（自动弱色），PRIMARY 只留强调性图标；
   - Divider(axis?)：分隔线，axis∈HORIZONTAL|VERTICAL（默认 HORIZONTAL）；
   - List(children*, direction?, spacing?)：direction∈VERTICAL|HORIZONTAL（默认 VERTICAL
     限高纵向滚动，HORIZONTAL 横滑行）；
   - Tabs(tabs*)：tabs 是 [{title: "页签标题", children: [组件...]}]，至少一个页签；
   - CheckBox(label*, checked*)：checked 传 ${'$'}布尔绑定（见规则 6），点按回写；
     裸 true/false 字面量只读不回写；
   - ChoicePicker(label?, options*, selected*)：options 是 [{label: "显示文案", value: "值"}]，
     selected 传 ${'$'}字符串绑定，点选回写 value；裸字面量只读；
   - Slider(label?, value*, min?, max?)：value 传 ${'$'}数字绑定，min/max 默认 0/100，
     拖动结束回写数值；裸数字字面量只读；label 可省略，省略时 value 须写命名实参
     （形如 Slider(value=${'$'}level)——位置参数首槽是 label）；
   - Table(columns*, rows*)：行式表格；columns 是 [{label: "列名"}]，rows 是行式
     嵌套数组 [[cell, ...], ...]（每行一条内层数组，cell 只许字符串/数字/布尔，
     单元格数与 columns 对齐）；
   - PieChart(title?, slices*)：占比分布；slices 是 [{label: "分类名", value: 数值}]；
   - LineChart(title?, labels*, series*)：趋势折线；labels 是横轴标签数组
     ["x1", ...]，series 是 [{name: "系列名", values: [数值, ...]}]
     （values 长度与 labels 对齐）；
   - Callout(text*, title?, tone?)：结论/告警高亮块，tone∈INFO|SUCCESS|WARNING|ERROR
     （默认 INFO）；
   - Markdown(text*)：富文本正文（标题/加粗/列表混排），text 可传 ${'$'}绑定
     （同 Text 口径）；
   - Accordion(items*)：可折叠分区；items 是 [{title: "分区标题", children: [组件...]}]；
   - Switch(label*, checked*)：开关式布尔（与 CheckBox 语义对偶），checked 传
     ${'$'}布尔绑定，点按回写；裸 true/false 字面量只读不回写；
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
6. state 与交互约定（交互组件 = BarChart/CheckBox/ChoicePicker/Slider/Switch，可绑定文案 = Text）：
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
""" + GOLDEN_STAT_TILE_PROGRAM
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
        return "UI 已下发端侧渲染（OpenUI）"
    }
}
