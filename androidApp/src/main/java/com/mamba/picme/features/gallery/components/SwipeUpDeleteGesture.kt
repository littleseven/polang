package com.mamba.picme.features.gallery.components

import kotlin.math.abs

/**
 * MediaPager 上滑删除手势的纯判定逻辑（JVM 可测）。
 * 与滑动整理（SwipeReview）卡片同一手势语言：阈值 = 页高 25%，飞出 220ms。
 */
object SwipeUpDeleteGesture {
    const val THRESHOLD_FRACTION = 0.25f
    const val FLY_OUT_MS = 220
    const val FLY_OUT_DISTANCE_FACTOR = 1.5f

    /** 竖直位移主导才响应（abs(dy) > abs(dx)），避免抢 HorizontalPager 横滑翻页。 */
    fun isVerticalDominant(dragX: Float, dragY: Float): Boolean = abs(dragY) > abs(dragX)

    /** 松手结算：向上位移（offsetY < 0）超过页高 × [THRESHOLD_FRACTION] → 确认删除。 */
    fun shouldCommit(offsetY: Float, pageHeight: Float): Boolean =
        pageHeight > 0f && offsetY < -pageHeight * THRESHOLD_FRACTION

    /** 跟手位移：只跟向上方向，向下不跟（钳制到 0）。 */
    fun followOffset(current: Float, dragY: Float): Float = (current + dragY).coerceAtMost(0f)

    /** 飞出目标位移：向上飞出屏外。 */
    fun flyOutTargetY(pageHeight: Float): Float = -pageHeight * FLY_OUT_DISTANCE_FACTOR
}
