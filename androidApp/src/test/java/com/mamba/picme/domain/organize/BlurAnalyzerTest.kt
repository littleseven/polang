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
    fun `non-square, short array and border-only inputs handled`() {
        // 非方阵 8×4 竖直边缘图（左半 255 / 右半 0，非对称图案锁 width/height 不传反）。
        val w = 8
        val h = 4
        val verticalEdge = IntArray(w * h) { index -> if (index % w < w / 2) 255 else 0 }
        val edgeVariance = BlurAnalyzer.laplacianVariance(verticalEdge, w, h)
        assertTrue("edge=$edgeVariance", edgeVariance > OrganizeThresholds.BLUR_VARIANCE_LOW)
        // 手算：内部每行 lap=[0,0,255,-255,0,0]，共 2 行 → 方差 = 260100/12 = 21675；
        // 转置传参（w=4,h=8）得到不同值 260100，借此锁参数顺序。
        assertEquals(21675.0f, edgeVariance, 0.5f)
        assertEquals(260100.0f, BlurAnalyzer.laplacianVariance(verticalEdge, h, w), 0.5f)

        // 短数组：10 < 8*4=32 → 哨兵 0。
        assertEquals(0.0f, BlurAnalyzer.laplacianVariance(IntArray(10), w, h), 0.0f)

        // 8×8 仅四角非零、内部纯色：角像素既不作卷积中心，
        // 也不落在任何内部中心的 4 邻域 → 方差恰为 0（锁最外圈不参与卷积）。
        val cornerOnly = IntArray(64)
        cornerOnly[0] = 255
        cornerOnly[7] = 255
        cornerOnly[56] = 255
        cornerOnly[63] = 255
        assertEquals(0.0f, BlurAnalyzer.laplacianVariance(cornerOnly, 8, 8), 0.0f)

        // 8×8 整圈边界 255、内部纯色：边界不作中心但作为最内圈中心的邻域参与，
        // 手算方差 = 2080800/36 - 170² = 28900（精确锁边界口径）。
        val ring = IntArray(64) { index ->
            val x = index % 8
            val y = index / 8
            if (x == 0 || x == 7 || y == 0 || y == 7) 255 else 0
        }
        assertEquals(28900.0f, BlurAnalyzer.laplacianVariance(ring, 8, 8), 0.5f)
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertEquals(0.0f, BlurAnalyzer.laplacianVariance(IntArray(0), 0, 0), 0.0f)
        assertEquals(0.0f, BlurAnalyzer.meanLuminance(IntArray(0)), 0.0f)
    }

    @Test
    fun `inSampleSize downsamples so long edge is at most target`() {
        // 4000×3000：4000/16=250、3000/16=187，长边 ≤256 → sample=16（8 时 500>256 继续翻倍）
        assertEquals(16, computeInSampleSize(4000, 3000))
        // 500×400：500/2=250、400/2=200 → sample=2
        assertEquals(2, computeInSampleSize(500, 400))
        // 恰好 target：不放大（256>256 为 false）
        assertEquals(1, computeInSampleSize(256, 256))
        // 小于 target：保持 1
        assertEquals(1, computeInSampleSize(100, 80))
        // 自定义 target：4000/8=500 ≤512 停（/4=1000>512 需再翻倍）→ sample=8
        assertEquals(8, computeInSampleSize(4000, 3000, target = 512))
    }
}
