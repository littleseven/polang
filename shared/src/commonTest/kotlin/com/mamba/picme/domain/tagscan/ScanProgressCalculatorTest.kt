package com.mamba.picme.domain.tagscan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanProgressCalculatorTest {

    private val weights = ScanPassWeights(faceDetectionMs = 800, imageTaggingMs = 7_000)

    // ── 任务域 ─────────────────────────────────────────────

    @Test
    fun `task progress starts at zero with full baseline remaining`() {
        val result = computeScanTaskProgress(
            baselineRemainingPass1 = 100, baselineRemainingPass3 = 100,
            remainingPass1 = 100, remainingPass3 = 100,
            weights = weights,
        )
        assertEquals(0f, result.fraction!!, 1e-5f)
        // eta = 100×800 + 100×7000 = 780_000
        assertEquals(780_000L, result.etaMs)
    }

    @Test
    fun `pass1 done pass3 untouched yields weight ratio not count ratio`() {
        // 人脸全过、打标未动：按张数是 50%，按工作量 = 800/(800+7000) ≈ 10.26%
        val result = computeScanTaskProgress(
            baselineRemainingPass1 = 100, baselineRemainingPass3 = 100,
            remainingPass1 = 0, remainingPass3 = 100,
            weights = weights,
        )
        assertEquals(800f / 7800f, result.fraction!!, 1e-4f)
        assertEquals(700_000L, result.etaMs)
    }

    @Test
    fun `new photos mid sweep are clamped to baseline and do not regress`() {
        // 基线 100/100，中途新增 50 张未扫照片涌入媒体表
        val result = computeScanTaskProgress(
            baselineRemainingPass1 = 100, baselineRemainingPass3 = 100,
            remainingPass1 = 150, remainingPass3 = 150,
            weights = weights,
        )
        assertEquals(0f, result.fraction!!, 1e-5f)
    }

    @Test
    fun `task progress completes at one`() {
        val result = computeScanTaskProgress(
            baselineRemainingPass1 = 100, baselineRemainingPass3 = 100,
            remainingPass1 = 0, remainingPass3 = 0,
            weights = weights,
        )
        assertEquals(1f, result.fraction!!, 1e-5f)
        assertEquals(0L, result.etaMs)
    }

    @Test
    fun `zero baseline yields null fraction for indeterminate rendering`() {
        val result = computeScanTaskProgress(
            baselineRemainingPass1 = 0, baselineRemainingPass3 = 0,
            remainingPass1 = 0, remainingPass3 = 0,
            weights = weights,
        )
        assertNull(result.fraction)
        assertNull(result.etaMs)
    }

    @Test
    fun `pass only in baseline that is not covered stays zero contribution`() {
        // 只扫 Pass1 的策略：基线 tagging = 0，人脸过半 → 50%
        val result = computeScanTaskProgress(
            baselineRemainingPass1 = 100, baselineRemainingPass3 = 0,
            remainingPass1 = 50, remainingPass3 = 0,
            weights = weights,
        )
        assertEquals(0.5f, result.fraction!!, 1e-5f)
    }

    // ── 库域 ─────────────────────────────────────────────

    @Test
    fun `library completion weights both passes`() {
        // 1000 张照片：人脸全过、打标缺 500
        // = (800×1000 + 7000×500) / (7800×1000) = 4_300_000/7_800_000 ≈ 0.5513
        val fraction = computeLibraryCompletionFraction(
            totalPhotos = 1000, remainingPass1 = 0, remainingPass3 = 500,
            weights = weights,
        )
        assertEquals(4_300_000f / 7_800_000f, fraction, 1e-4f)
    }

    @Test
    fun `library completion full and empty`() {
        assertEquals(
            1f,
            computeLibraryCompletionFraction(500, 0, 0, weights),
            1e-5f,
        )
        assertEquals(
            0f,
            computeLibraryCompletionFraction(500, 500, 500, weights),
            1e-5f,
        )
        assertEquals(
            0f,
            computeLibraryCompletionFraction(0, 0, 0, weights),
            1e-5f,
        )
    }

    @Test
    fun `library completion clamps remaining above total`() {
        // 数据不一致（如删除照片后剩余统计滞后）不崩、不越界
        val fraction = computeLibraryCompletionFraction(
            totalPhotos = 100, remainingPass1 = 150, remainingPass3 = 150,
            weights = weights,
        )
        assertEquals(0f, fraction, 1e-5f)
    }
}
