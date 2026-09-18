import XCTest
@testable import PoLang

/// BlurAnalyzer 测试（语义移植 Android BlurAnalyzerTest，含手算精确值锁定与退化哨兵）。
/// computeInSampleSize 未移植（Android BitmapFactory 2 的幂降采样口径；iOS T2 用
/// CGImageSource maxPixelSize 直接出 ≤256px 缩略图，无需该函数）。
final class OrganizeBlurAnalyzerTests: XCTestCase {

    private let size = 64

    /// 纯色图：零纹理，Laplacian 方差应趋近 0。
    private func flatImage(_ gray: Int) -> [Int] { [Int](repeating: gray, count: size * size) }

    /// 确定性伪随机噪点图（LCG；Kotlin Random 序列不可跨端复现，只需高频纹理方差足够大）。
    private func noisyImage() -> [Int] {
        var state: UInt64 = 42
        return (0..<(size * size)).map { _ in
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return Int((state >> 33) & 0xFF)
        }
    }

    /// 棋盘格：强边缘，方差大。
    private func checkerImage() -> [Int] {
        (0..<(size * size)).map { index in
            let x = index % size
            let y = index / size
            return ((x / 8 + y / 8) % 2 == 0) ? 255 : 0
        }
    }

    func testFlatImageHasNearZeroBlurVariance() {
        let variance = BlurAnalyzer.laplacianVariance(flatImage(128), width: size, height: size)
        XCTAssertLessThan(variance, 1.0, "flat variance=\(variance) should be < 1")
    }

    func testNoisyAndCheckerImagesHaveHighVariance() {
        let noisy = BlurAnalyzer.laplacianVariance(noisyImage(), width: size, height: size)
        let checker = BlurAnalyzer.laplacianVariance(checkerImage(), width: size, height: size)
        XCTAssertGreaterThan(noisy, OrganizeThresholds.blurVarianceLow * 10, "noisy=\(noisy)")
        XCTAssertGreaterThan(checker, OrganizeThresholds.blurVarianceLow * 10, "checker=\(checker)")
    }

    func testMeanLuminanceNormalized0To1() {
        XCTAssertEqual(0.0, BlurAnalyzer.meanLuminance(flatImage(0)), accuracy: 0.001)
        XCTAssertEqual(1.0, BlurAnalyzer.meanLuminance(flatImage(255)), accuracy: 0.001)
        XCTAssertEqual(128.0 / 255.0, BlurAnalyzer.meanLuminance(flatImage(128)), accuracy: 0.01)
    }

    func testNonSquareShortArrayAndBorderOnlyInputsHandled() {
        // 非方阵 8×4 竖直边缘图（左半 255 / 右半 0，非对称图案锁 width/height 不传反）。
        let w = 8
        let h = 4
        let verticalEdge = (0..<(w * h)).map { index in index % w < w / 2 ? 255 : 0 }
        let edgeVariance = BlurAnalyzer.laplacianVariance(verticalEdge, width: w, height: h)
        // 手算：内部每行 lap=[0,0,255,-255,0,0]，共 2 行 → 方差 = 260100/12 = 21675；
        // 转置传参（w=4,h=8）得到不同值 260100，借此锁参数顺序。
        XCTAssertEqual(21675.0, edgeVariance, accuracy: 0.5)
        XCTAssertEqual(260100.0, BlurAnalyzer.laplacianVariance(verticalEdge, width: h, height: w), accuracy: 0.5)

        // 短数组：10 < 8*4=32 → 哨兵 0。
        XCTAssertEqual(0.0, BlurAnalyzer.laplacianVariance([Int](repeating: 0, count: 10), width: w, height: h))

        // 8×8 仅四角非零、内部纯色：角像素既不作卷积中心，
        // 也不落在任何内部中心的 4 邻域 → 方差恰为 0（锁最外圈不参与卷积）。
        var cornerOnly = [Int](repeating: 0, count: 64)
        cornerOnly[0] = 255
        cornerOnly[7] = 255
        cornerOnly[56] = 255
        cornerOnly[63] = 255
        XCTAssertEqual(0.0, BlurAnalyzer.laplacianVariance(cornerOnly, width: 8, height: 8))

        // 8×8 整圈边界 255、内部纯色：边界不作中心但作为最内圈中心的邻域参与，
        // 手算方差 = 2080800/36 - 170² = 28900（精确锁边界口径）。
        let ring = (0..<64).map { index -> Int in
            let x = index % 8
            let y = index / 8
            return (x == 0 || x == 7 || y == 0 || y == 7) ? 255 : 0
        }
        XCTAssertEqual(28900.0, BlurAnalyzer.laplacianVariance(ring, width: 8, height: 8), accuracy: 0.5)
    }

    func testDegenerateInputsDoNotCrash() {
        XCTAssertEqual(0.0, BlurAnalyzer.laplacianVariance([], width: 0, height: 0))
        XCTAssertEqual(0.0, BlurAnalyzer.meanLuminance([]))
    }

    func testBt601GrayIntegerFormula() {
        // 对齐 Android (299r + 587g + 114b) / 1000 整数口径
        XCTAssertEqual(0, BlurAnalyzer.bt601Gray(r: 0, g: 0, b: 0))
        XCTAssertEqual(255, BlurAnalyzer.bt601Gray(r: 255, g: 255, b: 255))
        XCTAssertEqual(76, BlurAnalyzer.bt601Gray(r: 255, g: 0, b: 0))    // 299*255/1000 = 76
        XCTAssertEqual(149, BlurAnalyzer.bt601Gray(r: 0, g: 255, b: 0))   // 587*255/1000 = 149（整数截断）
        XCTAssertEqual(29, BlurAnalyzer.bt601Gray(r: 0, g: 0, b: 255))    // 114*255/1000 = 29
    }
}
