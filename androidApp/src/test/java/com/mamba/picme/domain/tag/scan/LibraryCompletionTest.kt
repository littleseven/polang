package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tagscan.ScanPassWeights
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 口径立法契约测试（2026-10-05 修订）：
 * 全 app 唯一对外百分比 = 库域加权完成度——照片两阶段（人脸/打标）按单张预估耗时加权，
 * photo-only 分母（视频不进流水线，计入会永不收敛 100%）。
 * 计算核心 = shared `domain/tagscan/ScanProgressCalculator`（双端 SSOT，另有 commonTest 矩阵）。
 */
class LibraryCompletionTest {

    private val weights = ScanPassWeights(faceDetectionMs = 800, imageTaggingMs = 7_000)

    @Test
    fun `pass1 done pass3 half yields weighted fraction not count fraction`() {
        // 1000 张照片：人脸全过、打标缺 500
        // = (800×1000 + 7000×500) / (7800×1000) ≈ 55.13%
        val completion = LibraryCompletion(
            totalPhotos = 1000, remainingPass1 = 0, remainingPass3 = 500, weights = weights,
        )
        assertEquals(4_300_000f / 7_800_000f, completion.fraction, 1e-4f)
        assertEquals(55, completion.percentRounded())
    }

    @Test
    fun `count based would overstate early progress`() {
        // 按张数两阶段均完 50% 时也是 55.13% 的错觉不成立：人脸过半、打标未动 ≈ 55%×0.5
        val completion = LibraryCompletion(
            totalPhotos = 1000, remainingPass1 = 500, remainingPass3 = 1000, weights = weights,
        )
        // = (800×500 + 7000×0) / (7800×1000) ≈ 5.13%
        assertEquals(400_000f / 7_800_000f, completion.fraction, 1e-4f)
    }

    @Test
    fun `empty library yields zero percent and zero fraction`() {
        val completion = LibraryCompletion(totalPhotos = 0, remainingPass1 = 0, remainingPass3 = 0)
        assertEquals(0, completion.percentRounded())
        assertEquals(0f, completion.fraction, 1e-5f)
    }

    @Test
    fun `complete library yields hundred percent`() {
        val completion = LibraryCompletion(totalPhotos = 500, remainingPass1 = 0, remainingPass3 = 0)
        assertEquals(100, completion.percentRounded())
        assertEquals(1f, completion.fraction, 1e-5f)
    }

    @Test
    fun `default weights apply when not specified`() {
        // 默认 800/7000：人脸全过、打标未动 ≈ 10.26% → 10%
        val completion = LibraryCompletion(totalPhotos = 100, remainingPass1 = 0, remainingPass3 = 100)
        assertEquals(10, completion.percentRounded())
    }
}
