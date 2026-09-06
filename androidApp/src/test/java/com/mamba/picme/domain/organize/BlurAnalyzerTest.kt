package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlurAnalyzerTest {

    private val size = 64

    /** 纯色图：零纹理，Laplacian 方差应趋近 0。 */
    private fun flatImage(gray: Int): IntArray = IntArray(size * size) { gray }

    /** 随机噪点图：高频纹理，方差应很大。 */
    private fun noisyImage(seed: Int = 42): IntArray =
        IntArray(size * size) { index -> Random(seed + index).nextInt(256) }

    /** 棋盘格：强边缘，方差大。 */
    private fun checkerImage(): IntArray = IntArray(size * size) { index ->
        val x = index % size
        val y = index / size
        if ((x / 8 + y / 8) % 2 == 0) 255 else 0
    }

    @Test
    fun `flat image has near-zero blur variance`() {
        val variance = BlurAnalyzer.laplacianVariance(flatImage(128), size, size)
        assertTrue("flat variance=$variance should be < 1", variance < 1.0f)
    }

    @Test
    fun `noisy and checker images have high variance, far above threshold`() {
        val noisy = BlurAnalyzer.laplacianVariance(noisyImage(), size, size)
        val checker = BlurAnalyzer.laplacianVariance(checkerImage(), size, size)
        assertTrue("noisy=$noisy", noisy > OrganizeThresholds.BLUR_VARIANCE_LOW * 10)
        assertTrue("checker=$checker", checker > OrganizeThresholds.BLUR_VARIANCE_LOW * 10)
    }

    @Test
    fun `mean luminance normalized 0 to 1`() {
        assertEquals(0.0f, BlurAnalyzer.meanLuminance(flatImage(0)), 0.001f)
        assertEquals(1.0f, BlurAnalyzer.meanLuminance(flatImage(255)), 0.001f)
        assertEquals(128 / 255.0f, BlurAnalyzer.meanLuminance(flatImage(128)), 0.01f)
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertEquals(0.0f, BlurAnalyzer.laplacianVariance(IntArray(0), 0, 0), 0.0f)
        assertEquals(0.0f, BlurAnalyzer.meanLuminance(IntArray(0)), 0.0f)
    }
}
