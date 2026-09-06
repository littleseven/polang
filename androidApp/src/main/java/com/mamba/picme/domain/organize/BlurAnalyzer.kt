package com.mamba.picme.domain.organize

/**
 * 真模糊/曝光检测（纯 Kotlin，灰度 IntArray 输入，JVM 可测）：
 * - 模糊 = Laplacian 4 邻域卷积的方差（越大越清晰；运动/失焦模糊会抹平高频 → 方差塌缩）
 * - 曝光 = 平均亮度归一（0~1）
 * 输入约定：≤256px 降采样灰度图（data 层 Bitmap 提取负责），毫秒级完成。
 * 升级位（spec §11 方案 2 预留）：未来可在此接口后替换为 MNN 模糊检测模型。
 */
object BlurAnalyzer {

    /**
     * Laplacian 方差；尺寸非法或像素数不足返回 0。
     * ⚠️ 0 = 退化输入哨兵（与极模糊/纯黑真值重合），调用方解码失败必须写 null 入库，不可写 0。
     */
    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || gray.size < width * height) return 0.0f
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val center = gray[row + x]
                val lap = 4 * center -
                    gray[row + x - 1] - gray[row + x + 1] -
                    gray[row + x - width] - gray[row + x + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                count++
            }
        }
        if (count == 0) return 0.0f
        val mean = sum / count
        return (sumSq / count - mean * mean).toFloat()
    }

    /**
     * 平均亮度归一（0~1）；空输入返回 0。
     * ⚠️ 0 = 退化输入哨兵（与极模糊/纯黑真值重合），调用方解码失败必须写 null 入库，不可写 0。
     */
    fun meanLuminance(gray: IntArray): Float {
        if (gray.isEmpty()) return 0.0f
        var sum = 0L
        gray.forEach { value -> sum += value }
        return sum.toFloat() / gray.size / 255.0f
    }
}
