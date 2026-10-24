package com.mamba.picme.domain.chat.openui

/**
 * OpenUI 线格式契约（字面量 SSOT）。
 *
 * 三方消费：androidApp（渲染/落库/工具注册）、server `RenderOpenUiToolService`
 * （@LLMDescription 字面量副本，改动须双向同步核对）、云端 LLM 行为约定。
 * 与 [com.mamba.picme.domain.chat.a2ui.A2uiCardContract] 对偶（两条 GenUI 线格式并存）。
 */
object OpenUiContract {

    /** ChatMessageEntity.type / converter 分支键 / MessagePart serialName。 */
    const val PART_TYPE = "data_ui_openui"

    /** 端上宿主工具名（Query("local_handle", ...) → HandleLookup 端侧补全，隐私闸对偶）。 */
    const val TOOL_LOCAL_HANDLE = "local_handle"

    /** BarChart 选中桶的绑定 state 变量名。 */
    const val STATE_SELECTED = "\$selected"

    /**
     * scan 句柄的 #photos fragment（spec《openui-image-component-design》D2）：
     * `local://scan/<id>#photos` → 端侧投影 [{id, date}]（ScanPhotoProjection，cap 60）。
     * server `RenderOpenUiToolService` 描述文本持字面量副本，改动须双向同步核对。
     */
    const val FRAGMENT_PHOTOS = "photos"

    /** viewState 线格式："selected=<label>"（与 A2UI 线同约定）。 */
    fun viewStateOfSelected(label: String): String = "selected=$label"

    fun selectedOfViewState(viewState: String?): String? =
        viewState?.removePrefix("selected=")?.takeIf { it != viewState && it.isNotEmpty() }
}
