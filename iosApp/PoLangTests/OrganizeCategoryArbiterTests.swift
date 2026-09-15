import XCTest
@testable import PoLang

/// 互斥类目裁定测试（语义移植 Android CategoryArbiterTest；
/// 截图/录屏信号按 iOS 口径改走 isScreenshot/isScreenRecording 布尔——
/// Android 的 relativePath "Pictures/Screenshots/" 路径关键词用例一一对应替换）。
final class OrganizeCategoryArbiterTests: XCTestCase {

    /// 测试夹具工厂：除保护信号（不参与裁定）外与 OrganizeItem 构造参数一一对应。
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
        isScreenshot: Bool = false,
        isScreenRecording: Bool = false
    ) -> OrganizeItem {
        OrganizeItem(
            uri: uri, isVideo: isVideo, captureDate: 1_000, sizeBytes: sizeBytes,
            relativePath: nil, ocrText: ocrText, pixelArea: pixelArea,
            labels: labels, hasFace: hasFace, aestheticScore: aestheticScore,
            faceQualityScore: faceQualityScore, blurScore: blurScore,
            exposureScore: exposureScore, exactDupGroupSize: exactDupGroupSize,
            similarDupGroupSize: similarDupGroupSize,
            isScreenshot: isScreenshot, isScreenRecording: isScreenRecording
        )
    }

    func testExactDuplicateGroupMemberWinsOverEverything() {
        // 截图 + 重复组 → DUPLICATES（优先级 1 截走，修复 v1 多属重复计账）
        let result = CategoryArbiter.classify(item(exactDupGroupSize: 3, isScreenshot: true))
        XCTAssertEqual(result, .duplicates)
    }

    func testScreenshotClassifiedScreenContentAndNeverBlurry() {
        // v1 痛点：截图低美学分误落 BLURRY；v2 必须先被 SCREEN_CONTENT 截走
        let result = CategoryArbiter.classify(
            item(aestheticScore: 1.0, blurScore: 1.0, isScreenshot: true)
        )
        XCTAssertEqual(result, .screenContent)
    }

    func testScreenRecordingVideoBySubtype() {
        let result = CategoryArbiter.classify(
            item(isVideo: true, sizeBytes: 5_000_000, isScreenRecording: true)
        )
        XCTAssertEqual(result, .screenContent)
    }

    func testDocumentViaOcrDensityBeatsLowQualityPhoto() {
        // 文档翻拍天然模糊/低美学 → 必须落 DOCUMENTS 而非 LOW_QUALITY_PHOTOS
        let dense = String(repeating: "x", count: 500)
        let result = CategoryArbiter.classify(
            item(ocrText: dense, pixelArea: 12_000_000, blurScore: 1.0)
        )
        XCTAssertEqual(result, .documents)
    }

    func testDocumentViaLabelsKeywords() {
        // labels 中文子串 / 英文整词双通道（沿用 dedup detectContentType 口径）
        XCTAssertEqual(.documents, CategoryArbiter.classify(item(labels: #"["文档","纸张"]"#)))
        XCTAssertEqual(.documents, CategoryArbiter.classify(item(labels: #"["receipts"]"#)))
        // 英文整词防线：context/texture 不被子串 "text" 误伤
        XCTAssertNil(CategoryArbiter.classify(item(labels: #"["context","texture"]"#)))
    }

    func testLowFaceQualityPortrait() {
        XCTAssertEqual(.lowQualityPortraits,
                       CategoryArbiter.classify(item(hasFace: true, faceQualityScore: 0.2)))
        // 阈值边界：恰好等于不命中
        XCTAssertNil(CategoryArbiter.classify(item(hasFace: true, faceQualityScore: 0.35)))
        // nil 评分不判定
        XCTAssertNil(CategoryArbiter.classify(item(hasFace: true, faceQualityScore: nil)))
    }

    func testTrueBlurAndExposureAnomalyClassifiedLowQualityPhotos() {
        XCTAssertEqual(.lowQualityPhotos, CategoryArbiter.classify(item(blurScore: 20.0)))
        XCTAssertEqual(.lowQualityPhotos, CategoryArbiter.classify(item(exposureScore: 0.05)))
        XCTAssertEqual(.lowQualityPhotos, CategoryArbiter.classify(item(exposureScore: 0.95)))
        // NIMA 低分单独不再定类（v1 BLURRY 名实错位修复）
        XCTAssertNil(CategoryArbiter.classify(item(aestheticScore: 1.0)))
    }

    func testLargeVideoAndSuperResolutionPhotoAreLargeFiles() {
        XCTAssertEqual(.largeFiles,
                       CategoryArbiter.classify(item(isVideo: true, sizeBytes: 150 * 1024 * 1024)))
        XCTAssertEqual(.largeFiles,
                       CategoryArbiter.classify(item(sizeBytes: 25 * 1024 * 1024, pixelArea: 60_000_000)))
        // 高像素但体积小（高效压缩）不判
        XCTAssertNil(CategoryArbiter.classify(item(sizeBytes: 5 * 1024 * 1024, pixelArea: 60_000_000)))
    }

    func testDomainConstraintVideosNeverEnterDocumentOrQualityCategories() {
        let video = item(
            isVideo: true, sizeBytes: 1_000, ocrText: String(repeating: "x", count: 500),
            hasFace: true, faceQualityScore: 0.1, blurScore: 1.0
        )
        XCTAssertNil(CategoryArbiter.classify(video))
    }

    func testCategoryDeclarationOrderMatchesArbitrationPriority() {
        // 声明序 = 裁定优先级（先命中先得）；变更须同步 CategoryArbiter.classify 的 if 序列
        XCTAssertEqual(
            ["DUPLICATES", "SCREEN_CONTENT", "DOCUMENTS",
             "LOW_QUALITY_PORTRAITS", "LOW_QUALITY_PHOTOS", "LARGE_FILES"],
            OrganizeCategory.allCases.map { $0.rawValue }
        )
    }

    func testUncategorizedReturnsNil() {
        XCTAssertNil(CategoryArbiter.classify(item()))
    }
}
