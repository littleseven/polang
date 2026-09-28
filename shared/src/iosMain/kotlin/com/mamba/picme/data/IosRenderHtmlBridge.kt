package com.mamba.picme.data

/**
 * Swift → Kotlin 的 render_html 卡片渲染桥协议（M5 B4，chat.yaml §13）。
 *
 * Swift 实现（iosApp `RenderHtmlBridge`/NSObject）把 HTML 经 Swift 自有通道交给
 * `ChatViewModel` 落 HtmlCard 消息并渲染（Inline/Fullpage 双形态）；[summary] 经
 * [onResult] 回传给能力层，作为远程 LLM 的 observation（做文字总结）。
 *
 * SharedBridge 铁律（同 [IosChartBridge]）：
 * - Swift 实现侧绝不抛异常跨边界（逃逸会 signal 6 / SIGABRT）；
 * - [onResult] **必须**被调用（成功或失败），否则 Kotlin 侧 suspendCancellableCoroutine
 *   永久挂起；失败时回传兜底 summary。
 *
 * 与 draw_chart 的差异：render_html 的 html 本体即 LLM 产物，端侧只做 sanitize
 * （commonMain `HtmlCardSanitizer`）+ WebView 渲染，无端侧生成步骤。
 *
 * [PRIVACY]：html 为远程 LLM 生成的文本内容（非用户媒体文件），端侧渲染不触发
 * 任何媒体上传。
 */
interface IosRenderHtmlBridge {

    /**
     * 端侧渲染一张 HTML 卡。
     *
     * @param html LLM 生成的 HTML 片段（未清洗，sanitize 在 Swift 侧 ViewModel 落消息前执行）
     * @param summary 卡片摘要（LLM 附带；可作 touchThread 预览与 Fullpage 封面兜底文案）；无则 null
     * @param display 形态声明：fullpage 强制长卡预览形态；无声明（null）时按测高分流
     * @param onResult summary 回调（回传 LLM）；渲染被拒/失败时回传拒绝原因（tool observation）
     */
    fun renderHtml(
        html: String,
        summary: String?,
        display: String?,
        onResult: (summary: String) -> Unit
    )
}
