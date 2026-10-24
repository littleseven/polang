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
 * polang OpenUI 组件库（十四组件，M2（2026-10-24，spec《openui-component-library-m2-design》）
 * 七组件扩至十四：+Icon/Divider/List/Tabs/CheckBox/ChoicePicker/Slider（§2 枚举 7 个新组件；
 * spec 标题「十三」与 §5「+6」系计数笔误，以 §2 枚举为准），Text.text 升
 * BINDABLE_STRING；与 A2UI catalog 逐项对偶，保证两线可比性；spec §4.2
 * + spec《openui-image-component-design》D3）。
 * 数据声明式：组件名 → 位置参数列表；验证（[OpenUiRuntime]）与渲染（androidApp
 * OpenUiNodeRenderer）共用同一份。
 */
object OpenUiLibrary {

    /** LibrarySpec root 组件（§2.2 fallback entry 的判定组件）。 */
    const val ROOT_COMPONENT = "Card"

    private val textRoles = listOf("HEADLINE", "SUBHEAD", "BODY", "CAPTION")
    private val colorRoles = listOf("PRIMARY", "SURFACE", "ERROR", "ON_SURFACE")
    private val spacings = listOf("COMPACT", "STANDARD", "COMFORTABLE")

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
        OpenUiComponent("Row", listOf(childrenProp, spacingProp)),
        OpenUiComponent("Column", listOf(childrenProp, spacingProp)),
        OpenUiComponent("Card", listOf(childrenProp, spacingProp)),
        OpenUiComponent(
            "Button",
            listOf(
                OpenUiProp("label", OpenUiPropType.STRING, required = true),
                OpenUiProp("action", OpenUiPropType.ACTION),
            ),
        ),
        OpenUiComponent(
            "BarChart",
            listOf(
                OpenUiProp("title", OpenUiPropType.STRING),
                OpenUiProp(
                    "buckets",
                    OpenUiPropType.OBJECT_ARRAY,
                    required = true,
                    objectFields = listOf(
                        OpenUiProp("label", OpenUiPropType.STRING, required = true),
                        OpenUiProp("count", OpenUiPropType.NUMBER, required = true),
                    ),
                ),
                OpenUiProp("selected", OpenUiPropType.BINDABLE_STRING),
            ),
        ),
        // 图文混排（spec《openui-image-component-design》D3）：photo = #photos 投影元素的 id
        // （求值为 Num，渲染期端侧 id→uri，uri 不进 program/payload）；aspect 宽高比
        // 无 schema 默认字面量，渲染期缺省 4:3 并 clamp [0.5, 2.5]
        OpenUiComponent(
            "Image",
            listOf(
                OpenUiProp("photo", OpenUiPropType.NUMBER, required = true),
                OpenUiProp("aspect", OpenUiPropType.NUMBER),
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
    ).associateBy { component -> component.name }
}
