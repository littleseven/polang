package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.HtmlCardDisplayMode

/**
 * HTML 卡双形态分流判定（纯函数，JVM 可单测；spec《HTML 卡双形态》§4 混合判定）。
 *
 * 规则（优先级从高到低）：
 * 1. LLM 经 render_html `display` 参数声明 fullpage → [HtmlCardDisplayMode.FULLPAGE]
 *    （尊重 LLM 意图，哪怕内容偏短、未测高）；
 * 2. 测高异常（≤ 0）或阈值不可用（≤ 0）→ [HtmlCardDisplayMode.INLINE]（降级链 §9.4，
 *    测不出高度时不触发强制预览）；
 * 3. 内容测高 > [fullpageThresholdPx]（1.0 × 聊天可用屏高）→ [HtmlCardDisplayMode.FULLPAGE]
 *    （端侧兜底，修正 LLM 应声明而未声明 fullpage 的长卡；= 阈值仍为 INLINE）；
 * 4. 其余 → [HtmlCardDisplayMode.INLINE]。
 *
 * 判定结果随消息 metadata 持久化（displayMode + measuredHeightPx），会话重开/列表回收后
 * 形态不跳变；一旦判定 INLINE，后续交互撑高（手风琴等）不再重新判定。
 */
object HtmlCardDisplay {

    /** render_html display 参数取值：全屏形态。 */
    const val DISPLAY_FULLPAGE = "fullpage"

    /** 预览卡固定高占聊天可用屏高比例（0.5 屏，spec §3）。 */
    const val PREVIEW_HEIGHT_FRACTION = 0.5f

    /** 分流阈值比例：测高超过 1.0 × 可用屏高 → 强制预览形态（spec §4）。 */
    const val FULLPAGE_THRESHOLD_FRACTION = 1.0f

    /** 预览卡底部渐隐遮罩高度（向卡底色渐隐）。 */
    const val PREVIEW_FADE_HEIGHT_DP = 120

    /**
     * 判定展示形态。[declaredDisplay] 为 LLM 声明原值（可任意大小写/带空白）；
     * [measuredHeightPx] 为内容测高（CSS px ≈ dp），≤ 0 视为测高异常；
     * [fullpageThresholdPx] 为分流阈值（1.0 × 可用屏高）。
     */
    fun resolveDisplayMode(
        declaredDisplay: String?,
        measuredHeightPx: Int,
        fullpageThresholdPx: Int
    ): HtmlCardDisplayMode {
        if (declaredDisplay?.trim()?.lowercase() == DISPLAY_FULLPAGE) return HtmlCardDisplayMode.FULLPAGE
        if (measuredHeightPx <= 0 || fullpageThresholdPx <= 0) return HtmlCardDisplayMode.INLINE
        return if (measuredHeightPx > fullpageThresholdPx) {
            HtmlCardDisplayMode.FULLPAGE
        } else {
            HtmlCardDisplayMode.INLINE
        }
    }

    /** LLM 声明是否为 fullpage（用于免测高直判）。 */
    fun isDeclaredFullpage(declaredDisplay: String?): Boolean =
        declaredDisplay?.trim()?.lowercase() == DISPLAY_FULLPAGE
}
