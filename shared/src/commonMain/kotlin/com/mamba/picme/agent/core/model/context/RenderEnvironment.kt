package com.mamba.picme.agent.core.model.context

/**
 * HTML 卡片渲染环境（render_html 排版上下文）。
 *
 * 由平台组合根注入（Android：PoLangApplication 按 DisplayMetrics 构建；
 * iOS 跟随期不注入），拼进 chat system prompt 动态尾段
 * （`RemoteChatEngine.buildPromptSuffix`），引导 LLM 按真实卡片尺寸做响应式排版。
 * 未注入时 prompt 不含该段，行为与旧版逐字节一致。
 *
 * WebView 侧经 viewport meta（width=device-width）保证 1 CSS px ≈ 1 dp，
 * 故宽度/高度直接用 CSS px 表述。
 */
data class RenderEnvironment(
    /** 设备形态描述（如 "Android 手机"）。 */
    val deviceType: String,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    /** 卡片内容区最大宽度（CSS px，已扣除列表/卡片边距的近似值）。 */
    val cardContentWidthCssPx: Int,
    /** 卡片在列表内的最大展示高度（CSS px），超出部分卡片内滚动查看。 */
    val cardMaxHeightCssPx: Int,
) {
    /** prompt 段文本（与规则段一致，全中文、面向远程模型）。 */
    fun toPromptSegment(): String =
        "【HTML 卡片渲染环境】设备：$deviceType；屏幕 ${screenWidthPx}x${screenHeightPx} px" +
            "（${screenWidthDp}x${screenHeightDp} dp）；卡片内容区最大宽度约 $cardContentWidthCssPx CSS px，" +
            "列表内最大展示高度约 $cardMaxHeightCssPx CSS px（超出部分用户在卡片内滚动查看）。" +
            "render_html 的 html 请按此响应式排版：宽度用百分比或 max-width:100%，不写死超过卡片宽度的固定 px；" +
            "整体高度尽量控制在最大展示高度以内。"
}
