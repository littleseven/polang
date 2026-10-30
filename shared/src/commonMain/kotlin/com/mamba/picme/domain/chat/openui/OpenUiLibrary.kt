package com.mamba.picme.domain.chat.openui

/** polang OpenUI 组件库 prop 类型（验证与渲染共用同一份 schema，Agent First §2.3 自描述）。 */
enum class OpenUiPropType {
    STRING,
    NUMBER,
    BOOLEAN,
    ENUM,

    /** 组件数组（children 槽；只收组件，数据剔除，null 字面量保留渲染为空）。 */
    COMPONENT_LIST,

    /** 对象数组（BarChart.buckets：{label: string, count: number}）。 */
    OBJECT_ARRAY,

    /** 动作表达式（ActionVal 或 ActionVal 数组，§6.3 裸动作/Action([...]) 皆可）。 */
    ACTION,

    /** 字符串或 $binding（$var 原文收为 Binding，§5.2）。 */
    BINDABLE_STRING,

    /** 布尔字面量或 $binding（语义同 BINDABLE_STRING；CheckBox.checked）。 */
    BINDABLE_BOOLEAN,

    /** 数字字面量或 $binding（语义同 BINDABLE_STRING；Slider.value）。 */
    BINDABLE_NUMBER,

    /**
     * 原始值数组（M3；Table.rows / LineChart.labels/series[].values）：元素限 Str/Num/Bool
     * 或**一层嵌套 Arr**（表格行 `[[cell,...],...]`），两层嵌套拒。不收 Binding
     * （数组整体绑定无交互语义，M3 不需要）。
     */
    PRIMITIVE_ARRAY,
}

/**
 * @param required 必填（缺省/显式 null 无默认值时丢组件）
 * @param default schema 默认值（null = 无默认）
 * @param enumValues ENUM 域
 * @param objectFields OBJECT_ARRAY 元素字段约束
 */
data class OpenUiProp(
    val name: String,
    val type: OpenUiPropType,
    val required: Boolean = false,
    val default: OpenUiValue? = null,
    val enumValues: List<String> = emptyList(),
    val objectFields: List<OpenUiProp> = emptyList(),
)

/** 组件 schema：位置参数按声明序映射为命名属性。 */
data class OpenUiComponent(val name: String, val props: List<OpenUiProp>)

/**
 * polang OpenUI 组件库（八十九项注册 = 官方 81/82 + 本仓别名与既有 8 项）。
 * M2（2026-10-24，spec《openui-component-library-m2-design》）七扩十四；M3（同日，
 * spec《openui-component-library-m3-design》）十四扩二十一；2026-10-25 全量补全
 * （spec《openui-full-library-design》）：对齐官方 openuiLibrary（thesysdev/openui）
 * 82 组件——补 81（Col 列式 Table 刻意不收，行式为唯一形态），官方同名异构组件
 * （Callout/Tabs/Accordion/Table/Image/Card/Button）保本仓签名向后兼容，别名
 * Stack/Separator/MarkDownRenderer 渲染复用既有实现，表单 name 键控 → @Set($name) 直写。
 * 数据声明式：组件名 → 位置参数列表；验证（[OpenUiRuntime]）与渲染（androidApp
 * OpenUiNodeRenderer）共用同一份。
 */
object OpenUiLibrary {

    /** LibrarySpec root 组件（§2.2 fallback entry 的判定组件）。 */
    const val ROOT_COMPONENT = "Card"

    private val textRoles = listOf("HEADLINE", "SUBHEAD", "BODY", "CAPTION", "DISPLAY")
    private val colorRoles = listOf("PRIMARY", "SURFACE", "ERROR", "ON_SURFACE")
    private val spacings = listOf("COMPACT", "STANDARD", "COMFORTABLE")
    private val aligns = listOf("start", "center", "end", "stretch", "baseline")

    /** Box 叠放容器的 contentAlignment 九值（Compose 官方词汇，缺省 topStart = Box 官方缺省）。 */
    private val boxAligns = listOf(
        "topStart", "topCenter", "topEnd",
        "centerStart", "center", "centerEnd",
        "bottomStart", "bottomCenter", "bottomEnd",
    )

    /** Spacer 留白档位（官方 Stack gap 词汇子集）→ Spacing.xs/sm/md/lg/xl。 */
    private val spacerSizes = listOf("xs", "s", "m", "l", "xl")

    /**
     * Icon.name 的 59 枚预定义图标名（A2UI catalog v1 同口径，禁 svgPath 自由形式）。
     * 字面量副本四处同步：server `RenderUiValidator.ICON_NAMES`（副本源）↔
     * server `RenderOpenUiValidator.ICON_NAMES` ↔ 本清单 ↔ `RenderOpenUiToolService`
     * 描述内嵌清单，改动须四处核对（测试钉住同源）。
     * ⚠️ 残余风险（review 🟡5 记录在案）：server 与 :shared 是两个独立 Gradle build，
     * 「本清单 ↔ server 副本」无直接程序性比对——shared 测试持自己的字面量、server 测试
     * 比对 server 内两份，跨模块漂移时两边各自全绿。改动图标清单时人工四处核对。
     */
    val iconNames = listOf(
        "accountCircle", "add", "arrowBack", "arrowForward", "attachFile", "calendarToday",
        "call", "camera", "check", "close", "delete", "download", "edit", "error", "event",
        "fastForward", "favorite", "favoriteOff", "folder", "help", "home", "info",
        "locationOn", "lock", "lockOpen", "mail", "menu", "moreHoriz", "moreVert",
        "notifications", "notificationsOff", "pause", "payment", "person", "phone", "photo",
        "play", "print", "refresh", "rewind", "search", "send", "settings", "share",
        "shoppingCart", "skipNext", "skipPrevious", "star", "starHalf", "starOff", "stop",
        "upload", "visibility", "visibilityOff", "volumeDown", "volumeMute", "volumeOff",
        "volumeUp", "warning",
    )

    private val childrenProp = OpenUiProp("children", OpenUiPropType.COMPONENT_LIST, required = true)
    private val spacingProp = OpenUiProp(
        "spacing",
        OpenUiPropType.ENUM,
        default = OpenUiValue.Str("STANDARD"),
        enumValues = spacings,
    )

    val components: Map<String, OpenUiComponent> = listOf(
        OpenUiComponent(
            "Text",
            listOf(
                // M2：STRING → BINDABLE_STRING（字面量照收向后兼容；$binding 原样存 Binding，
                // 渲染期从 state 快照读，解析不出丢该 Text 组件不炸卡）
                OpenUiProp("text", OpenUiPropType.BINDABLE_STRING, required = true),
                OpenUiProp("role", OpenUiPropType.ENUM, default = OpenUiValue.Str("BODY"), enumValues = textRoles),
                OpenUiProp(
                    "colorRole",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("ON_SURFACE"),
                    enumValues = colorRoles,
                ),
            ),
        ),
        // 2026-10-30 align 结构治理：三容器补 align——词汇/语义/缺省严格对齐官方
        // thesysdev/openui Stack（CSS align-items 语义）：start|center|end|stretch|baseline
        // 五值，缺省 stretch（官方缺省）= 子项铺满交叉轴（Row 子栏撑满行高、Column/Card
        // 内容撑满整宽），baseline = 子项首行基线对齐；stat tile 数字栏居中写
        // align="center"。justify 不引入（Row 恒 weight 分栏、Column 恒 wrap 高，
        // 主轴分布无意义场景）
        OpenUiComponent(
            "Row",
            listOf(
                childrenProp,
                spacingProp,
                OpenUiProp("align", OpenUiPropType.ENUM, default = OpenUiValue.Str("stretch"), enumValues = aligns),
                // 2026-10-30 通用布局原语批次：按比例分栏（CSS flex-grow 语义）——数字数组
                // 与组件子项一一对应，缺省/个数不符渲染期回退全 1（现行等宽）；Divider/Spacer
                // 分隔件不占槽位。左图右文 = Row([图, 文栏], weights = [1, 2])
                OpenUiProp("weights", OpenUiPropType.PRIMITIVE_ARRAY),
            ),
        ),
        OpenUiComponent(
            "Column",
            listOf(
                childrenProp,
                spacingProp,
                OpenUiProp("align", OpenUiPropType.ENUM, default = OpenUiValue.Str("stretch"), enumValues = aligns),
            ),
        ),
        // Card：2026-10-25 全量补全增可选 title（官方 fixtures 形态；有则渲染头部标题区）
        OpenUiComponent(
            "Card",
            listOf(
                childrenProp,
                spacingProp,
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp("align", OpenUiPropType.ENUM, default = OpenUiValue.Str("stretch"), enumValues = aligns),
            ),
        ),
        // ── 通用布局原语批次（2026-10-30，spec《2026-10-30-openui-layout-primitives-design》）：
        // 官方 Stack 之外的三个经典版式原语（本仓刻意扩展，词汇/缺省严格对齐官方/CSS/Compose）
        // Grid：通用 N 列网格（四宫格 = columns 2）；columns 渲染期 clamp [1,4]
        OpenUiComponent(
            "Grid",
            listOf(
                childrenProp,
                OpenUiProp("columns", OpenUiPropType.NUMBER, default = OpenUiValue.Num(2.0)),
                spacingProp,
                OpenUiProp("align", OpenUiPropType.ENUM, default = OpenUiValue.Str("stretch"), enumValues = aligns),
            ),
        ),
        // Box：通用叠放容器——children 按数组序从底到顶叠放，align 九值 contentAlignment
        OpenUiComponent(
            "Box",
            listOf(
                childrenProp,
                OpenUiProp(
                    "align",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("topStart"),
                    enumValues = boxAligns,
                ),
            ),
        ),
        // Spacer：通用留白件（Row 内占宽、Column/Card 内占高，不参与 Row weight 分栏）
        OpenUiComponent(
            "Spacer",
            listOf(
                OpenUiProp("size", OpenUiPropType.ENUM, default = OpenUiValue.Str("m"), enumValues = spacerSizes),
            ),
        ),
        OpenUiComponent(
            "Button",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("action", OpenUiPropType.ACTION),
                // 2026-10-25 官方对齐：variant→Filled/Tonal/Text 映射；size 收而不用（卡内统一触控尺寸）
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("primary"),
                    enumValues = listOf("primary", "secondary", "tertiary"),
                ),
                OpenUiProp(
                    "size",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("medium"),
                    enumValues = listOf("extra-small", "small", "medium", "large"),
                ),
            ),
        ),
        OpenUiComponent(
            "BarChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                // 2026-10-25 全量补全：buckets 降可选——官方 labels+series 形态为另一合法数据面
                // （buckets / labels+series 二缺一，渲染层防御：皆无 → 占位不炸卡）
                OpenUiProp(
                    "buckets",
                    OpenUiPropType.OBJECT_ARRAY,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("count", OpenUiPropType.NUMBER, required = true),
                    ),
                ),
                OpenUiProp("selected", OpenUiPropType.BINDABLE_STRING),
                OpenUiProp("labels", OpenUiPropType.PRIMITIVE_ARRAY),
                OpenUiProp(
                    "series",
                    OpenUiPropType.OBJECT_ARRAY,
                    objectFields = listOf(
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("values", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                    ),
                ),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("grouped"),
                    enumValues = listOf("grouped", "stacked"),
                ),
            ),
        ),
        // 图文混排（spec《openui-image-component-design》D3）：photo = #photos 投影元素的 id
        // （求值为 Num，渲染期端侧 id→uri，uri 不进 program/payload）；aspect 宽高比
        // 无 schema 默认字面量，渲染期缺省按真实比例。
        // 2026-10-25 官方对齐：photo 降可选，增 src（https 公网图，[PRIVACY] validator 正则
        // 硬校验，A2UI Image.url 同口径）+ alt；photo/src 二缺一，皆无渲染层丢占位
        OpenUiComponent(
            "Image",
            listOf(
                OpenUiProp("photo", OpenUiPropType.NUMBER),
                OpenUiProp("aspect", OpenUiPropType.NUMBER),
                OpenUiProp("src", OpenUiPropType.STRING),
                OpenUiProp("alt", OpenUiPropType.STRING),
            ),
        ),
        // ── M2 六组件（spec《openui-component-library-m2-design》§2） ──────────
        // Icon.name 59 枚举（iconNames 字面量副本，三方同步见 iconNames KDoc）；
        // colorRole 复用色板（SURFACE 在文字语义上是弱化色，图标同理可用）
        OpenUiComponent(
            "Icon",
            listOf(
                OpenUiProp("name", OpenUiPropType.ENUM, required = true, enumValues = iconNames),
                OpenUiProp(
                    "colorRole",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("ON_SURFACE"),
                    enumValues = colorRoles,
                ),
            ),
        ),
        OpenUiComponent(
            "Divider",
            listOf(
                OpenUiProp(
                    "axis",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("HORIZONTAL"),
                    enumValues = listOf("HORIZONTAL", "VERTICAL"),
                ),
            ),
        ),
        // List：VERTICAL = 限高纵向滚动列（渲染期 maxHeight 约 320dp，规避聊天 LazyColumn
        // 嵌套滚动事故）；HORIZONTAL = 横滑行（MediaResultsCarousel 先例）
        OpenUiComponent(
            "List",
            listOf(
                childrenProp,
                OpenUiProp(
                    "direction",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("VERTICAL"),
                    enumValues = listOf("VERTICAL", "HORIZONTAL"),
                ),
                spacingProp,
            ),
        ),
        // Tabs：tabs = [{title, children: [组件...]}]；选中页签是渲染期本地态（remember），
        // 不进 OpenUI state（页签切换是纯 UI 导航，无 LLM 语义）
        OpenUiComponent(
            "Tabs",
            listOf(
                OpenUiProp(
                    "tabs",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("title", OpenUiPropType.STRING, required = true),
                        OpenUiProp("children", OpenUiPropType.COMPONENT_LIST, required = true),
                    ),
                ),
            ),
        ),
        // CheckBox：checked BINDABLE_BOOLEAN——点按 → @Set(<绑定变量>, !当前值)；
        // 裸 Bool 字面量只读展示不写入（BarChart 裸字面量口径一致）
        OpenUiComponent(
            "CheckBox",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("checked", OpenUiPropType.BINDABLE_BOOLEAN, required = true),
            ),
        ),
        // ChoicePicker：单选 chips 行，点选 → @Set(<绑定变量>, value)
        OpenUiComponent(
            "ChoicePicker",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING),
                OpenUiProp(
                    "options",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("value", OpenUiPropType.STRING, required = true),
                    ),
                ),
                OpenUiProp("selected", OpenUiPropType.BINDABLE_STRING, required = true),
            ),
        ),
        // Slider：value BINDABLE_NUMBER，拖动结束 → @Set(<绑定变量>, 数值)；
        // 渲染必须用 androidApp AppSlider（全 app 滑杆红线，禁裸 M3 Slider）
        OpenUiComponent(
            "Slider",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING),
                OpenUiProp("value", OpenUiPropType.BINDABLE_NUMBER, required = true),
                OpenUiProp("min", OpenUiPropType.NUMBER, default = OpenUiValue.Num(0.0)),
                OpenUiProp("max", OpenUiPropType.NUMBER, default = OpenUiValue.Num(100.0)),
            ),
        ),
        // ── M3 七组件（spec《openui-component-library-m3-design》§3） ──────────
        // Table：行式表格。columns = [{label}]；rows = [[cell,...],...]（一层嵌套
        // PRIMITIVE_ARRAY，cell=string/number/bool；缺 cell 渲染期补空串，行式嵌套
        // 数组是 LLM 书写实测最稳形态，spec §3 取舍）
        OpenUiComponent(
            "Table",
            listOf(
                OpenUiProp(
                    "columns",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                    ),
                ),
                OpenUiProp("rows", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
            ),
        ),
        // PieChart：slices = [{label, value}]（字段名 value 与 BarChart buckets 的 count
        // 刻意不同——各自语义自描述，schema 层各自独立无共享，spec §3 取舍）
        // 2026-10-25 官方对齐：variant donut（默认，M4 环形）/ pie（实心扇形）
        OpenUiComponent(
            "PieChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp(
                    "slices",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("value", OpenUiPropType.NUMBER, required = true),
                    ),
                ),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("donut"),
                    enumValues = listOf("donut", "pie"),
                ),
            ),
        ),
        // LineChart：双数组（labels + series[].values，趋势图 x 轴共享，避免每 series
        // 重复 label 串烧 token）；series[].values = OBJECT_ARRAY 嵌套 PRIMITIVE_ARRAY
        // （Tabs 的 COMPONENT_LIST 递归同机制）；values 长度与 labels 对齐，缺补 null 断点
        OpenUiComponent(
            "LineChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp("labels", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                OpenUiProp(
                    "series",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("values", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                    ),
                ),
                // 2026-10-25 官方对齐：折线插值形态（渲染 linear/step；natural≈linear 简化）
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("linear"),
                    enumValues = listOf("linear", "natural", "step"),
                ),
            ),
        ),
        // Callout：结论/告警高亮块；tone 默认 INFO（2026-10-25 增 NEUTRAL 对齐官方五档）
        OpenUiComponent(
            "Callout",
            listOf(
                OpenUiProp("text", OpenUiPropType.STRING, required = true),
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp(
                    "tone",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("INFO"),
                    enumValues = listOf("INFO", "SUCCESS", "WARNING", "ERROR", "NEUTRAL"),
                ),
            ),
        ),
        // Markdown：富文本正文；text BINDABLE_STRING（同 M2 Text 口径，$绑定联动）
        OpenUiComponent(
            "Markdown",
            listOf(OpenUiProp("text", OpenUiPropType.BINDABLE_STRING, required = true)),
        ),
        // Accordion：items = [{title, children: [组件...]}]（OBJECT_ARRAY 嵌套
        // COMPONENT_LIST，Tabs 同构）；展开态是渲染期本地态不进 OpenUI state
        OpenUiComponent(
            "Accordion",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("title", OpenUiPropType.STRING, required = true),
                        OpenUiProp("children", OpenUiPropType.COMPONENT_LIST, required = true),
                    ),
                ),
            ),
        ),
        // Switch：checked BINDABLE_BOOLEAN（CheckBox 同构：点按 → @Set 回写，
        // 裸字面量只读展示不写入）
        OpenUiComponent(
            "Switch",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("checked", OpenUiPropType.BINDABLE_BOOLEAN, required = true),
            ),
        ),
        // ── 官方全量补全（2026-10-25，spec《openui-full-library-design》）────────────────
        // 官方 openuiLibrary（thesysdev/openui）82 组件补 81（Col 列式 Table 刻意不收——
        // 行式 Table 为唯一形态，防双形态精神分裂）。表单组件 name 键控 → 渲染期交互即
        // @Set($<name>, 值) 直写 state（Slider/CheckBox 既有口径），他处经 $name 绑定读。
        // 数据形状组件（Series/Slice/Point/ScatterSeries/SelectItem 等 Item 形）仅在父槽
        // 内有语义，独立出现渲染为空占位（半信任输入防御既有口径）。

        // ── 别名（官方名注册，渲染复用既有实现）──
        // Stack(direction=row→Row / 缺省 column→Column；wrap=true→FlowRow/FlowColumn；
        // gap 六档映射既有 spacing token；align/justify 映射主轴/交叉轴排布)
        OpenUiComponent(
            "Stack",
            listOf(
                childrenProp,
                OpenUiProp(
                    "direction",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("column"),
                    enumValues = listOf("row", "column"),
                ),
                OpenUiProp(
                    "gap",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("m"),
                    enumValues = listOf("none", "xs", "s", "m", "l", "xl", "2xl"),
                ),
                OpenUiProp(
                    "align",
                    OpenUiPropType.ENUM,
                    // 官方缺省 = stretch（官方 schema optional 无显式缺省，CSS align-items
                    // 默认 stretch；2026-10-30 由 start 修正对齐官方语义）
                    default = OpenUiValue.Str("stretch"),
                    enumValues = aligns,
                ),
                OpenUiProp(
                    "justify",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("start"),
                    enumValues = listOf("start", "center", "end", "between", "around", "evenly"),
                ),
                OpenUiProp("wrap", OpenUiPropType.BOOLEAN, default = OpenUiValue.Bool(false)),
            ),
        ),
        OpenUiComponent(
            "Separator",
            listOf(
                OpenUiProp(
                    "orientation",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("horizontal"),
                    enumValues = listOf("horizontal", "vertical"),
                ),
            ),
        ),
        OpenUiComponent(
            "MarkDownRenderer",
            listOf(
                OpenUiProp("textMarkdown", OpenUiPropType.BINDABLE_STRING, required = true),
                OpenUiProp("variant", OpenUiPropType.STRING),
            ),
        ),
        // TabItem/AccordionItem：官方页签/折叠项组件形（本仓 Tabs/Accordion 收 {title,children}
        // 对象形为 prompt 主形态；组件形注册保底，独立出现渲染 content 直排）
        OpenUiComponent(
            "TabItem",
            listOf(
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
                OpenUiProp("trigger", OpenUiPropType.STRING, required = true),
                OpenUiProp("content", OpenUiPropType.COMPONENT_LIST, required = true),
            ),
        ),
        OpenUiComponent(
            "AccordionItem",
            listOf(
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
                OpenUiProp("trigger", OpenUiPropType.STRING, required = true),
                OpenUiProp("content", OpenUiPropType.COMPONENT_LIST, required = true),
            ),
        ),

        // ── 内容/布局 ──
        OpenUiComponent(
            "CardHeader",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("subtitle", OpenUiPropType.STRING),
            ),
        ),
        // InlineHeader：官方「轻量标题+描述对」，与 CardHeader 同渲染
        OpenUiComponent(
            "InlineHeader",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("description", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "TextContent",
            listOf(
                OpenUiProp("text", OpenUiPropType.BINDABLE_STRING, required = true),
                // size→字阶映射：small→CAPTION、default→BODY、large→SUBHEAD、
                // small-heavy→SUBHEAD、large-heavy→HEADLINE（渲染层映射表）
                OpenUiProp(
                    "size",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("default"),
                    enumValues = listOf("small", "default", "large", "small-heavy", "large-heavy"),
                ),
            ),
        ),
        OpenUiComponent(
            "TextCallout",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("description", OpenUiPropType.STRING),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("neutral"),
                    enumValues = listOf("neutral", "info", "success", "warning", "danger"),
                ),
            ),
        ),
        OpenUiComponent(
            "CodeBlock",
            listOf(
                OpenUiProp("codeString", OpenUiPropType.STRING, required = true),
                OpenUiProp("language", OpenUiPropType.STRING),
            ),
        ),
        // ImageBlock：官方纯 src 形态（https 限，[PRIVACY] validator 正则硬校验）
        OpenUiComponent(
            "ImageBlock",
            listOf(
                OpenUiProp("src", OpenUiPropType.STRING, required = true),
                OpenUiProp("alt", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "ImageGallery",
            listOf(
                OpenUiProp(
                    "images",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("src", OpenUiPropType.STRING, required = true),
                        OpenUiProp("alt", OpenUiPropType.STRING),
                        OpenUiProp("details", OpenUiPropType.STRING),
                    ),
                ),
            ),
        ),
        OpenUiComponent(
            "Tag",
            listOf(
                OpenUiProp("text", OpenUiPropType.STRING, required = true),
                OpenUiProp("icon", OpenUiPropType.ENUM, enumValues = iconNames),
                OpenUiProp(
                    "size",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("md"),
                    enumValues = listOf("sm", "md", "lg"),
                ),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("neutral"),
                    enumValues = listOf("neutral", "info", "success", "warning", "danger"),
                ),
            ),
        ),
        OpenUiComponent(
            "TagBlock",
            listOf(OpenUiProp("tags", OpenUiPropType.PRIMITIVE_ARRAY, required = true)),
        ),
        OpenUiComponent(
            "Label",
            listOf(OpenUiProp("text", OpenUiPropType.STRING, required = true)),
        ),
        // 卡块内构件（官方限定卡块内使用，本仓不强制位置——渲染递归位置无关）
        OpenUiComponent(
            "BoldText",
            listOf(
                OpenUiProp("value", OpenUiPropType.BINDABLE_STRING, required = true),
                OpenUiProp("subtext", OpenUiPropType.STRING),
                OpenUiProp(
                    "size",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("sm"),
                    enumValues = listOf("xs", "sm", "md", "lg"),
                ),
            ),
        ),
        OpenUiComponent(
            "IconText",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("icon", OpenUiPropType.ENUM, enumValues = iconNames),
                OpenUiProp("subtitle", OpenUiPropType.STRING),
                OpenUiProp("bold", OpenUiPropType.BOOLEAN, default = OpenUiValue.Bool(false)),
                OpenUiProp(
                    "layout",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("horizontal"),
                    enumValues = listOf("horizontal", "vertical"),
                ),
            ),
        ),
        // 2026-10-30 图文组合固化：ImageText/ImageTextLarge 增 photo（#photos 投影元素 id，
        // 与 Image.photo 完全同口径，求值为 Num、渲染期端侧 id→uri）——相册场景不再被迫
        // Row/Column 裸拼撞等宽分栏；photo/src 二选一，皆无同形占位
        OpenUiComponent(
            "ImageText",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("photo", OpenUiPropType.NUMBER),
                OpenUiProp("src", OpenUiPropType.STRING),
                OpenUiProp("subtitle", OpenUiPropType.STRING),
                OpenUiProp("imageSize", OpenUiPropType.NUMBER),
            ),
        ),
        OpenUiComponent(
            "ImageTextLarge",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("photo", OpenUiPropType.NUMBER),
                OpenUiProp("src", OpenUiPropType.STRING),
                OpenUiProp("subtitle", OpenUiPropType.STRING),
            ),
        ),
        // EntityList：官方 header/footer 是 {left,right} 对象——schema 无单对象 prop 型，
        // 拍平为 headerLeft/headerRight/footerLeft/footerRight 四个可选串
        OpenUiComponent(
            "EntityList",
            listOf(
                OpenUiProp(
                    "rows",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("left", OpenUiPropType.STRING, required = true),
                        OpenUiProp("right", OpenUiPropType.STRING, required = true),
                    ),
                ),
                OpenUiProp("headerLeft", OpenUiPropType.STRING),
                OpenUiProp("headerRight", OpenUiPropType.STRING),
                OpenUiProp("footerLeft", OpenUiPropType.STRING),
                OpenUiProp("footerRight", OpenUiPropType.STRING),
            ),
        ),
        // ListBlock：带序号/缩略图列表；item.action 经宿主动作通道（OpenUiEval
        // objectFieldOk ACTION 分支，2026-10-25 增）
        OpenUiComponent(
            "ListBlock",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("title", OpenUiPropType.STRING, required = true),
                        OpenUiProp("subtitle", OpenUiPropType.STRING),
                        OpenUiProp("image", OpenUiPropType.STRING),
                        OpenUiProp("actionLabel", OpenUiPropType.STRING),
                        OpenUiProp("action", OpenUiPropType.ACTION),
                    ),
                ),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("number"),
                    enumValues = listOf("number", "image"),
                ),
            ),
        ),
        OpenUiComponent(
            "ListItem",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("subtitle", OpenUiPropType.STRING),
                OpenUiProp("image", OpenUiPropType.STRING),
                OpenUiProp("actionLabel", OpenUiPropType.STRING),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        OpenUiComponent(
            "Steps",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("title", OpenUiPropType.STRING, required = true),
                        OpenUiProp("details", OpenUiPropType.STRING),
                    ),
                ),
            ),
        ),
        OpenUiComponent(
            "StepsItem",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("details", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "Carousel",
            listOf(
                childrenProp,
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("card"),
                    enumValues = listOf("card", "sunk"),
                ),
            ),
        ),
        OpenUiComponent(
            "Buttons",
            listOf(
                OpenUiProp("buttons", OpenUiPropType.COMPONENT_LIST, required = true),
                OpenUiProp(
                    "direction",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("row"),
                    enumValues = listOf("row", "column"),
                ),
            ),
        ),
        OpenUiComponent(
            "IconButton",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("icon", OpenUiPropType.ENUM, required = true, enumValues = iconNames),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        // Modal：open BINDABLE_BOOLEAN——state 真值时渲染 Dialog；X/遮罩关闭 →
        // @Set($open, false) 回写（官方 reactive 语义的本仓适配）
        OpenUiComponent(
            "Modal",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("open", OpenUiPropType.BINDABLE_BOOLEAN, required = true),
                childrenProp,
            ),
        ),

        // ── 表单（name 键控 → @Set($name, ...) 直写 state）──
        OpenUiComponent(
            "Form",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                childrenProp,
            ),
        ),
        // FormControl.input 单组件槽：COMPONENT_LIST 收（通常一项，渲染直排）
        OpenUiComponent(
            "FormControl",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("input", OpenUiPropType.COMPONENT_LIST, required = true),
                OpenUiProp("hint", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "Input",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("placeholder", OpenUiPropType.STRING),
                OpenUiProp(
                    "type",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("text"),
                    enumValues = listOf("text", "email", "password", "number", "url"),
                ),
                OpenUiProp("rules", OpenUiPropType.PRIMITIVE_ARRAY),
            ),
        ),
        OpenUiComponent(
            "TextArea",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("placeholder", OpenUiPropType.STRING),
                OpenUiProp("rows", OpenUiPropType.NUMBER),
                OpenUiProp("rules", OpenUiPropType.PRIMITIVE_ARRAY),
            ),
        ),
        OpenUiComponent(
            "Select",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("value", OpenUiPropType.STRING, required = true),
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                    ),
                ),
                OpenUiProp("placeholder", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "SelectItem",
            listOf(
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
            ),
        ),
        OpenUiComponent(
            "DatePicker",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("mode", OpenUiPropType.ENUM, required = true, enumValues = listOf("single", "range")),
            ),
        ),
        OpenUiComponent(
            "CheckBoxGroup",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("description", OpenUiPropType.STRING),
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("defaultChecked", OpenUiPropType.BOOLEAN),
                    ),
                ),
            ),
        ),
        OpenUiComponent(
            "CheckBoxItem",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("description", OpenUiPropType.STRING),
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("defaultChecked", OpenUiPropType.BOOLEAN),
            ),
        ),
        OpenUiComponent(
            "RadioGroup",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("description", OpenUiPropType.STRING),
                        OpenUiProp("value", OpenUiPropType.STRING, required = true),
                    ),
                ),
                OpenUiProp("defaultValue", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "RadioItem",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("description", OpenUiPropType.STRING),
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
            ),
        ),
        OpenUiComponent(
            "SwitchGroup",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("description", OpenUiPropType.STRING),
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("defaultChecked", OpenUiPropType.BOOLEAN),
                    ),
                ),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("clear"),
                    enumValues = listOf("clear", "card", "sunk"),
                ),
            ),
        ),
        OpenUiComponent(
            "SwitchItem",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("description", OpenUiPropType.STRING),
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("defaultChecked", OpenUiPropType.BOOLEAN),
            ),
        ),
        // Chips/OptionCards：type=single 写 $name=值；multiple 写 $name=逗号串
        // （官方默认值刻意相反：Chips=multiple、OptionCards=single，保持一致）
        OpenUiComponent(
            "Chips",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("value", OpenUiPropType.STRING, required = true),
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("icon", OpenUiPropType.ENUM, enumValues = iconNames),
                    ),
                ),
                OpenUiProp(
                    "type",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("multiple"),
                    enumValues = listOf("single", "multiple"),
                ),
            ),
        ),
        OpenUiComponent(
            "ChipItem",
            listOf(
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("icon", OpenUiPropType.ENUM, enumValues = iconNames),
            ),
        ),
        OpenUiComponent(
            "OptionCards",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("value", OpenUiPropType.STRING, required = true),
                        OpenUiProp("title", OpenUiPropType.STRING, required = true),
                        OpenUiProp("subtitle", OpenUiPropType.STRING),
                    ),
                ),
                OpenUiProp(
                    "type",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("single"),
                    enumValues = listOf("single", "multiple"),
                ),
            ),
        ),
        OpenUiComponent(
            "OptionCard",
            listOf(
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("subtitle", OpenUiPropType.STRING),
            ),
        ),
        // EditableTable：官方 Save 提交制简化为单元格即写（@Set($name, JSON 行数组)，
        // prompt 注明）；columns[].options（select 列选项）不收——schema 不支持
        // OBJECT_ARRAY 嵌套 OBJECT_ARRAY，select 列按 text 渲染
        OpenUiComponent(
            "EditableTable",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp(
                    "columns",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp(
                            "type",
                            OpenUiPropType.ENUM,
                            required = true,
                            enumValues = listOf("text", "number", "date-single", "select", "url"),
                        ),
                        OpenUiProp("key", OpenUiPropType.STRING, required = true),
                        OpenUiProp("header", OpenUiPropType.STRING, required = true),
                    ),
                ),
                OpenUiProp(
                    "data",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("id", OpenUiPropType.STRING, required = true),
                        OpenUiProp("values", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                    ),
                ),
            ),
        ),

        // ── 图表（Series/Slice/Point/ScatterSeries 为数据形状，独立出现渲染空占位）──
        OpenUiComponent(
            "AreaChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp("labels", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                OpenUiProp(
                    "series",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("values", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                    ),
                ),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("linear"),
                    enumValues = listOf("linear", "natural", "step"),
                ),
            ),
        ),
        OpenUiComponent(
            "HorizontalBarChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp("labels", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                OpenUiProp(
                    "series",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("values", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                    ),
                ),
                OpenUiProp(
                    "variant",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("grouped"),
                    enumValues = listOf("grouped", "stacked"),
                ),
            ),
        ),
        OpenUiComponent(
            "RadarChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp("labels", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                OpenUiProp(
                    "series",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("values", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                    ),
                ),
            ),
        ),
        OpenUiComponent(
            "RadialChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp(
                    "slices",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("category", OpenUiPropType.STRING, required = true),
                        OpenUiProp("value", OpenUiPropType.NUMBER, required = true),
                    ),
                ),
            ),
        ),
        OpenUiComponent(
            "SingleStackedBarChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp(
                    "slices",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("category", OpenUiPropType.STRING, required = true),
                        OpenUiProp("value", OpenUiPropType.NUMBER, required = true),
                    ),
                ),
            ),
        ),
        // ScatterChart.points 用 PRIMITIVE_ARRAY 嵌套点（[[x,y],...]）——schema 不支持
        // OBJECT_ARRAY 嵌套 OBJECT_ARRAY，点坐标数组形对 LLM 书写也更省 token
        OpenUiComponent(
            "ScatterChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp(
                    "datasets",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("name", OpenUiPropType.STRING, required = true),
                        OpenUiProp("points", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
                    ),
                ),
                OpenUiProp("xLabel", OpenUiPropType.STRING),
                OpenUiProp("yLabel", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "Series",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("values", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
            ),
        ),
        OpenUiComponent(
            "Slice",
            listOf(
                OpenUiProp("category", OpenUiPropType.STRING, required = true),
                OpenUiProp("value", OpenUiPropType.NUMBER, required = true),
            ),
        ),
        OpenUiComponent(
            "ScatterSeries",
            listOf(
                OpenUiProp("name", OpenUiPropType.STRING, required = true),
                OpenUiProp("points", OpenUiPropType.PRIMITIVE_ARRAY, required = true),
            ),
        ),
        OpenUiComponent(
            "Point",
            listOf(
                OpenUiProp("x", OpenUiPropType.NUMBER, required = true),
                OpenUiProp("y", OpenUiPropType.NUMBER, required = true),
                OpenUiProp("z", OpenUiPropType.NUMBER),
            ),
        ),

        // ── 卡块（Block 壳：items* + layout?=grid/carousel + action?；grid→双列 FlowRow、
        // carousel→横滑定宽条目；action 点击卡经宿主动作通道）──
        OpenUiComponent(
            "MetricIndicatorInline",
            listOf(
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
                OpenUiProp("subtext", OpenUiPropType.STRING),
                OpenUiProp("trendDirection", OpenUiPropType.ENUM, enumValues = listOf("up", "down")),
                OpenUiProp("trendValue", OpenUiPropType.NUMBER),
            ),
        ),
        OpenUiComponent(
            "MetricIndicatorWithStrikethrough",
            listOf(
                OpenUiProp("value", OpenUiPropType.STRING, required = true),
                OpenUiProp("subtext", OpenUiPropType.STRING),
                OpenUiProp("previousValue", OpenUiPropType.STRING),
                OpenUiProp("trendDirection", OpenUiPropType.ENUM, enumValues = listOf("up", "down")),
                OpenUiProp("trendValue", OpenUiPropType.NUMBER),
            ),
        ),
        OpenUiComponent(
            "SnippetCardBlock",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("lhs", OpenUiPropType.COMPONENT_LIST, required = true),
                        OpenUiProp("rhs", OpenUiPropType.COMPONENT_LIST),
                    ),
                ),
                OpenUiProp(
                    "layout",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("grid"),
                    enumValues = listOf("grid", "carousel"),
                ),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        OpenUiComponent(
            "SnippetCardItem",
            listOf(
                OpenUiProp("lhs", OpenUiPropType.COMPONENT_LIST, required = true),
                OpenUiProp("rhs", OpenUiPropType.COMPONENT_LIST),
            ),
        ),
        OpenUiComponent(
            "OverviewCardBlock",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("top", OpenUiPropType.COMPONENT_LIST, required = true),
                        OpenUiProp("bottom", OpenUiPropType.COMPONENT_LIST),
                    ),
                ),
                OpenUiProp(
                    "layout",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("grid"),
                    enumValues = listOf("grid", "carousel"),
                ),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        OpenUiComponent(
            "OverviewCardItem",
            listOf(
                OpenUiProp("top", OpenUiPropType.COMPONENT_LIST, required = true),
                OpenUiProp("bottom", OpenUiPropType.COMPONENT_LIST),
            ),
        ),
        OpenUiComponent(
            "ContextCardBlock",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("title", OpenUiPropType.STRING, required = true),
                        OpenUiProp("body", OpenUiPropType.STRING),
                    ),
                ),
                OpenUiProp(
                    "layout",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("grid"),
                    enumValues = listOf("grid", "carousel"),
                ),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        OpenUiComponent(
            "ContextCardItem",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING, required = true),
                OpenUiProp("body", OpenUiPropType.STRING),
            ),
        ),
        OpenUiComponent(
            "CompositeCardBlock",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("header", OpenUiPropType.COMPONENT_LIST),
                        OpenUiProp("body", OpenUiPropType.COMPONENT_LIST, required = true),
                        OpenUiProp("footer", OpenUiPropType.COMPONENT_LIST),
                    ),
                ),
                OpenUiProp(
                    "layout",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("grid"),
                    enumValues = listOf("grid", "carousel"),
                ),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        OpenUiComponent(
            "CompositeCardItem",
            listOf(
                OpenUiProp("header", OpenUiPropType.COMPONENT_LIST),
                OpenUiProp("body", OpenUiPropType.COMPONENT_LIST, required = true),
                OpenUiProp("footer", OpenUiPropType.COMPONENT_LIST),
            ),
        ),
        OpenUiComponent(
            "VisualCardBlock",
            listOf(
                OpenUiProp(
                    "items",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("body", OpenUiPropType.STRING, required = true),
                        OpenUiProp("bgImageSrc", OpenUiPropType.STRING),
                        OpenUiProp("tag", OpenUiPropType.STRING),
                    ),
                ),
                OpenUiProp(
                    "layout",
                    OpenUiPropType.ENUM,
                    default = OpenUiValue.Str("grid"),
                    enumValues = listOf("grid", "carousel"),
                ),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        OpenUiComponent(
            "VisualCardItem",
            listOf(
                OpenUiProp("body", OpenUiPropType.STRING, required = true),
                OpenUiProp("bgImageSrc", OpenUiPropType.STRING),
                OpenUiProp("tag", OpenUiPropType.STRING),
            ),
        ),
    ).associateBy { component -> component.name }
}
