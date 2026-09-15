import XCTest
@testable import PoLang

/// 置信度分级测试（语义移植 Android ConfidenceGraderTest，含全部边界锁定）。
final class OrganizeConfidenceGraderTests: XCTestCase {

    private func item(
        uri: String = "a",
        isVideo: Bool = false,
        sizeBytes: Int64 = 1_000_000,
        ocrText: String? = nil,
        pixelArea: Int64? = 12_000_000,
        labels: String? = nil,
        hasFace: Bool = false,
        aestheticScore: Double? = nil,
        faceQualityScore: Double? = nil,
        blurScore: Double? = nil,
        exposureScore: Double? = nil,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
        isScreenshot: Bool = false
    ) -> OrganizeItem {
        OrganizeItem(
            uri: uri, isVideo: isVideo, captureDate: 1_000, sizeBytes: sizeBytes,
            ocrText: ocrText, pixelArea: pixelArea, labels: labels, hasFace: hasFace,
            aestheticScore: aestheticScore, faceQualityScore: faceQualityScore,
            blurScore: blurScore, exposureScore: exposureScore,
            exactDupGroupSize: exactDupGroupSize, similarDupGroupSize: similarDupGroupSize,
            isScreenshot: isScreenshot
        )
    }

    func testDuplicatesExactHighSimilarMedium() {
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(exactDupGroupSize: 2), category: .duplicates))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(similarDupGroupSize: 3), category: .duplicates))
    }

    func testScreenContentIsHigh() {
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(isScreenshot: true), category: .screenContent))
    }

    func testDocumentsStrongOcrHighBorderlineMediumLabelsOnlyMedium() {
        // 12MP 图上密度阈值 20 字符/MP = 240 字符；2× = 480
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(ocrText: String(repeating: "x", count: 600)), category: .documents))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(ocrText: String(repeating: "x", count: 300)), category: .documents))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(labels: #"["文档","纸张"]"#), category: .documents))
        // sub-MP 回归：0.9MP 真阈值 0.9×20×2=36 字符，20 < 36 → MEDIUM（先除后乘会塌缩为 0 误判 HIGH）
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(ocrText: String(repeating: "x", count: 20), pixelArea: 900_000), category: .documents))
        // pixelArea=nil 兜底分支：500 ≥ 200×2=400 → HIGH；300 < 400 → MEDIUM
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(ocrText: String(repeating: "x", count: 500), pixelArea: nil), category: .documents))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(ocrText: String(repeating: "x", count: 300), pixelArea: nil), category: .documents))
    }

    func testLowQualityPortraitsStrongFaceQualityHighElseMedium() {
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(hasFace: true, faceQualityScore: 0.1), category: .lowQualityPortraits))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(hasFace: true, faceQualityScore: 0.3), category: .lowQualityPortraits))
    }

    func testLowQualityPhotosGradingBoundaries() {
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(blurScore: 20.0), category: .lowQualityPhotos))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(blurScore: 80.0), category: .lowQualityPhotos))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(exposureScore: 0.05), category: .lowQualityPhotos))
        // 过曝侧（OR 右半边）
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(exposureScore: 0.9), category: .lowQualityPhotos))
        // 等值边界锁定：blur = 100×0.5=50 时 `<` 严格不命中 HIGH → MEDIUM
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(blurScore: 50.0), category: .lowQualityPhotos))
        // 防御性兜底分支（该输入经 arbiter 不会进此类目，此处锁定 grader 独立语义）
        XCTAssertEqual(.low,
                       ConfidenceGrader.grade(
                           item(aestheticScore: 2.0, blurScore: 500.0, exposureScore: 0.5),
                           category: .lowQualityPhotos))
    }

    func testLargeFilesDoubleThresholdHighElseMedium() {
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(isVideo: true, sizeBytes: 250 * 1024 * 1024), category: .largeFiles))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(isVideo: true, sizeBytes: 120 * 1024 * 1024), category: .largeFiles))
        // 照片分支：≥2×20MB → HIGH；1.25× → MEDIUM
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(sizeBytes: 45 * 1024 * 1024, pixelArea: 60_000_000), category: .largeFiles))
        XCTAssertEqual(.medium,
                       ConfidenceGrader.grade(item(sizeBytes: 25 * 1024 * 1024, pixelArea: 60_000_000), category: .largeFiles))
        // 等值边界锁定：2×100MiB=200MiB 时 `>=` 恰等命中 HIGH
        XCTAssertEqual(.high,
                       ConfidenceGrader.grade(item(isVideo: true, sizeBytes: 200 * 1024 * 1024), category: .largeFiles))
    }
}
