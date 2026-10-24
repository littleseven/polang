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
 * polang OpenUI 组件库（七组件；与 A2UI catalog 逐项对偶，保证两线可比性；spec §4.2
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
                OpenUiProp("text", OpenUiPropType.STRING, required = true),
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
    ).associateBy { component -> component.name }
}
