package com.mamba.picme.domain.chat.openui

/**
 * OpenUI Lang 1.0 值模型（spec §3.1）。
 *
 * 求值器内部另用 Kotlin null 作「dropped」哨兵（数组剔除/组件丢弃/未解析引用），
 * 与 [OpenUiValue.Null]（null 字面量，数组中保留）区分；该哨兵不泄漏到本模型。
 */
sealed interface OpenUiValue {
    data object Null : OpenUiValue
    data class Str(val v: String) : OpenUiValue
    data class Num(val v: Double) : OpenUiValue
    data class Bool(val v: Boolean) : OpenUiValue
    data class Arr(val v: List<OpenUiValue>) : OpenUiValue
    data class Obj(val v: Map<String, OpenUiValue>) : OpenUiValue

    /** 组件实例。props = 位置参数已按库 schema 映射为命名属性。statementId = 定义它的语句名（错误定位用）。 */
    data class ComponentVal(val type: String, val props: Map<String, OpenUiValue>, val statementId: String) : OpenUiValue

    /** 动作表达式求值结果：一个或多个动作。 */
    data class ActionVal(val actions: List<OpenUiAction>) : OpenUiValue

    /** $var 传给可绑定属性时的绑定引用。 */
    data class Binding(val name: String) : OpenUiValue
}

/** 动作调用（@Set/@Reset/@ToAssistant/@OpenUrl）。args 为已求值参数；@Set/@Reset 的 target 为变量名。 */
data class OpenUiAction(val name: String, val args: List<OpenUiValue>, val target: String? = null)
