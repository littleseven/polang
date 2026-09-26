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
    /** 建议 inline 单卡内容高度（CSS px，约 2/3 屏高；可读性建议值，超 1.0 屏会被强制转预览形态）。 */
    val cardSuggestedHeightCssPx: Int,
    /** 预览卡固定高度（CSS px，0.5 屏高；fullpage 卡在聊天流内的外显高度）。 */
    val previewCardHeightCssPx: Int,
    /** 分流阈值（CSS px，1.0 屏高；未声明 fullpage 但测高超过此值的卡强制转预览形态）。 */
    val fullpageThresholdCssPx: Int,
) {
    /** prompt 段文本（与规则段一致，全中文、面向远程模型）。 */
    fun toPromptSegment(): String =
        "【HTML 卡片渲染环境】设备：$deviceType；屏幕 ${screenWidthPx}x${screenHeightPx} px" +
            "（${screenWidthDp}x${screenHeightDp} dp）；卡片内容区最大宽度约 $cardContentWidthCssPx CSS px；" +
            "卡片有两种展示形态：inline 卡（display 缺省）在聊天内完整撑开展示全部内容" +
            "（卡片自身不滚动，随外层聊天列表滚动），为聊天可读性建议单卡内容高度控制在约" +
            " $cardSuggestedHeightCssPx CSS px（约 2/3 屏高）以内；" +
            "fullpage 卡（display=\"fullpage\"）在聊天内外显为固定高上半部预览" +
            "（高约 $previewCardHeightCssPx CSS px，0.5 屏高，底部渐隐），用户点击后进全屏查看器交互；" +
            "未声明 fullpage 但内容测高超过约 $fullpageThresholdCssPx CSS px（1.0 屏高）的卡会被端侧强制转为预览形态。" +
            "render_html 的 html 请按此响应式排版：宽度用百分比或 max-width:100%，不写死超过卡片宽度的固定 px；" +
            "长报告/多屏图文请声明 display=\"fullpage\" 并把标题/导航/关键交互设计在内容顶部。"
}
