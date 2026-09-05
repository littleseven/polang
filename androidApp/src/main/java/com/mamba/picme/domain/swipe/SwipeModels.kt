package com.mamba.picme.domain.swipe

/** 入列原因（卡片角标 + 可解释性）。 */
enum class SwipeReason { SCREENSHOT, BLURRY, LOW_QUALITY_PORTRAIT, RECENT }

data class SwipeCandidate(
    val uri: String,
    val sizeBytes: Long,
    val captureDate: Long,
    val reason: SwipeReason,
)
