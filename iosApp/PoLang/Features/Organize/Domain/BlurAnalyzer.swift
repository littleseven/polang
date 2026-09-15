import Foundation

/// 真模糊/曝光检测（纯函数，灰度像素数组输入，可 XCTest 直测）：
/// - 模糊 = Laplacian 4 邻域卷积的方差（越大越清晰；运动/失焦模糊会抹平高频 → 方差塌缩）
/// - 曝光 = 平均亮度归一（0~1）
/// 直译 Android domain/organize/BlurAnalyzer.kt。
/// 输入约定：≤256px 降采样灰度图（数据层 CGImageSource 缩略图提取负责，T2），毫秒级完成。
/// 升级位（spec §11 方案 2 预留）：未来可在此接口后替换为 MNN 模糊检测模型。
enum BlurAnalyzer {

    /// Laplacian 方差；尺寸非法或像素数不足返回 0。
    /// ⚠️ 0 = 退化输入哨兵（与极模糊/纯黑真值重合），调用方解码失败必须写 nil 入库，不可写 0。
    static func laplacianVariance(_ gray: [Int], width: Int, height: Int) -> Double {
        if width < 3 || height < 3 || gray.count < width * height { return 0 }
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for y in 1..<(height - 1) {
            let row = y * width
            for x in 1..<(width - 1) {
                let center = gray[row + x]
                let lap = 4 * center -
                    gray[row + x - 1] - gray[row + x + 1] -
                    gray[row + x - width] - gray[row + x + width]
                sum += Double(lap)
                sumSq += Double(lap) * Double(lap)
                count += 1
            }
        }
        if count == 0 { return 0 }
        let mean = sum / Double(count)
        return sumSq / Double(count) - mean * mean
    }

    /// 平均亮度归一（0~1）；空输入返回 0。
    /// ⚠️ 0 = 退化输入哨兵（与极模糊/纯黑真值重合），调用方解码失败必须写 nil 入库，不可写 0。
    static func meanLuminance(_ gray: [Int]) -> Double {
        if gray.isEmpty { return 0 }
        var sum = 0
        for value in gray { sum += value }
        return Double(sum) / Double(gray.count) / 255.0
    }

    /// 一次性产出 (blurScore, exposureScore)（数据层回写入库用）。
    static func analyze(gray: [Int], width: Int, height: Int) -> (blurScore: Double, exposureScore: Double) {
        (laplacianVariance(gray, width: width, height: height), meanLuminance(gray))
    }

    /// BT.601 灰度化（整数口径，对齐 Android OrganizeRepositoryImpl.computeQualityScores：
    /// `(299 * r + 587 * g + 114 * b) / 1000`）。T2 从 RGBA 像素提取灰度数组时用。
    static func bt601Gray(r: Int, g: Int, b: Int) -> Int {
        (299 * r + 587 * g + 114 * b) / 1000
    }
}
