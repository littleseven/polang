import XCTest
@testable import PoLang

/// 管线 Facade 聚合测试（语义移植 Android OrganizeCategorizerTest：
/// keeper 扣减 / coverage / hero 并集 / 排序键 / 预览稳定序）。
final class OrganizeCategorizerTests: XCTestCase {

    private let now: Int64 = 1_800_000_000_000

    private func item(
        _ uri: String,
        isVideo: Bool = false,
        captureDate: Int64? = nil,
        sizeBytes: Int64 = 1_000_000,
        ocrText: String? = nil,
        pixelArea: Int64? = 12_000_000,
        labels: String? = nil,
        hasFace: Bool = false,
        aestheticScore: Double? = nil,
        faceQualityScore: Double? = nil,
        blurScore: Double? = nil,
        exposureScore: Double? = nil,
        lastViewedAt: Int64? = nil,
        isFavorite: Bool = false,
        personPhotoCount: Int? = nil,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
        exactDupGroupKey: String? = nil,
        isScreenshot: Bool = false
    ) -> OrganizeItem {
        OrganizeItem(
            uri: uri, isVideo: isVideo, captureDate: captureDate ?? now, sizeBytes: sizeBytes,
            ocrText: ocrText, pixelArea: pixelArea, labels: labels, hasFace: hasFace,
            aestheticScore: aestheticScore, faceQualityScore: faceQualityScore,
            blurScore: blurScore, exposureScore: exposureScore,
            lastViewedAt: lastViewedAt, isFavorite: isFavorite,
            personPhotoCount: personPhotoCount, exactDupGroupSize: exactDupGroupSize,
            similarDupGroupSize: similarDupGroupSize, exactDupGroupKey: exactDupGroupKey,
            isScreenshot: isScreenshot
        )
    }

    private func cardOf(_ board: OrganizeBoard, _ category: OrganizeCategory) -> CategoryBoard {
        board.categories.first { $0.category == category }!
    }

    func testMutexScreenshotWithLowScoresLandsOnlyInScreenContent() {
        let classified = OrganizeCategorizer.classifyAll(
            [item("shot", ocrText: String(repeating: "x", count: 600),
                  aestheticScore: 1.0, blurScore: 1.0, isScreenshot: true)],
            now: now
        )
        XCTAssertEqual(1, classified.count)
        XCTAssertEqual(.screenContent, classified[0].category)
    }

    func testHeroReclaimIsHighNonProtectedUnionNoDoubleCount() {
        // 同一张图在 v1 会被截图+模糊+文档计 3 次；v2 互斥后 Hero 只计 1 次（AC-F1-1）
        let board = OrganizeCategorizer.board(
            [
                item("shot", sizeBytes: 100, ocrText: String(repeating: "x", count: 600),
                     blurScore: 1.0, isScreenshot: true),
                item("big", isVideo: true, sizeBytes: 250 * 1024 * 1024),
                item("normal"),
            ],
            now: now
        )
        XCTAssertEqual(100 + 250 * 1024 * 1024, board.heroReclaimBytes)
    }

    func testProtectedItemsExcludedFromHeroAndNeverHighCounted() {
        let board = OrganizeCategorizer.board(
            [
                // 6 年前的模糊老照片：HIGH 置信但 protected → 不进 Hero、不进 highCount
                item("old", captureDate: now - 6 * OrganizeThresholds.yearMillis,
                     sizeBytes: 500, blurScore: 10.0),
            ],
            now: now
        )
        XCTAssertEqual(0, board.heroReclaimBytes)
        let card = cardOf(board, .lowQualityPhotos)
        XCTAssertEqual(1, card.totalCount)
        XCTAssertEqual(0, card.highCount)
        XCTAssertEqual(1, card.protectedCount)
    }

    func testCategoriesSortedByHighConfidenceBytesDescending() {
        // 排序键是 highBytes 而非 totalBytes：SCREEN_CONTENT 两截图全 HIGH（highBytes=150=totalBytes）；
        // LOW_QUALITY_PHOTOS highBytes=100 但 totalBytes=5100。按 highBytes → SCREEN_CONTENT 在前。
        let board = OrganizeCategorizer.board(
            [
                item("s1", sizeBytes: 100, isScreenshot: true),
                item("s2", sizeBytes: 50, isScreenshot: true),
                item("blurHigh", sizeBytes: 100, blurScore: 10.0),   // HIGH
                item("blurMid", sizeBytes: 5000, blurScore: 80.0),   // MEDIUM
            ],
            now: now
        )
        XCTAssertEqual([.screenContent, .lowQualityPhotos],
                       board.categories.prefix(2).map { $0.category })
        let screenCard = cardOf(board, .screenContent)
        XCTAssertEqual(150, screenCard.highBytes)
        XCTAssertEqual(150, screenCard.totalBytes)
        let photosCard = cardOf(board, .lowQualityPhotos)
        XCTAssertEqual(100, photosCard.highBytes)
        XCTAssertEqual(5100, photosCard.totalBytes)
    }

    func testDuplicatesKeeperDeductionExactGroupOf3Yields2xHighBytes() {
        // 3 张同 MD5 精确重复各 5MB：只计 2 张可释放（保留 1 张 keeper）
        let board = OrganizeCategorizer.board(
            [
                item("d1", sizeBytes: 5_000_000, exactDupGroupSize: 3, exactDupGroupKey: "md5a"),
                item("d2", sizeBytes: 5_000_000, exactDupGroupSize: 3, exactDupGroupKey: "md5a"),
                item("d3", sizeBytes: 5_000_000, exactDupGroupSize: 3, exactDupGroupKey: "md5a"),
            ],
            now: now
        )
        let card = cardOf(board, .duplicates)
        XCTAssertEqual(3, card.highCount)
        XCTAssertEqual(3, card.totalCount)
        XCTAssertEqual(15_000_000, card.totalBytes)
        XCTAssertEqual(10_000_000, card.highBytes)
        XCTAssertEqual(10_000_000, board.heroReclaimBytes)
    }

    func testDuplicatesKeeperDeductionScopesPerGroup() {
        // 两组精确重复（2×5MB + 3×2MB）各扣 1 张：(5 + 2×2) = 9MB
        let board = OrganizeCategorizer.board(
            [
                item("a1", sizeBytes: 5_000_000, exactDupGroupSize: 2, exactDupGroupKey: "md5a"),
                item("a2", sizeBytes: 5_000_000, exactDupGroupSize: 2, exactDupGroupKey: "md5a"),
                item("b1", sizeBytes: 2_000_000, exactDupGroupSize: 3, exactDupGroupKey: "md5b"),
                item("b2", sizeBytes: 2_000_000, exactDupGroupSize: 3, exactDupGroupKey: "md5b"),
                item("b3", sizeBytes: 2_000_000, exactDupGroupSize: 3, exactDupGroupKey: "md5b"),
            ],
            now: now
        )
        XCTAssertEqual(9_000_000, cardOf(board, .duplicates).highBytes)
    }

    func testKeeperDeductedAfterInLibraryConvergence() {
        // 聚类输入已按库内 uri 收敛：「3 hash / 2 在库」到达领域层时已是 2 人组 → 正常扣 1 张
        let board = OrganizeCategorizer.board(
            [
                item("d1", sizeBytes: 5_000_000, exactDupGroupSize: 2, exactDupGroupKey: "md5a"),
                item("d2", sizeBytes: 5_000_000, exactDupGroupSize: 2, exactDupGroupKey: "md5a"),
            ],
            now: now
        )
        XCTAssertEqual(5_000_000, cardOf(board, .duplicates).highBytes)
        XCTAssertEqual(5_000_000, board.heroReclaimBytes)
    }

    func testSoleSurvivingKeeperIsNotSuggested() {
        // 组内其余已删、唯一幸存 keeper 收敛后 exactDupGroupSize 塌缩为 1 → 不成组
        let board = OrganizeCategorizer.board(
            [item("keeper", sizeBytes: 5_000_000, exactDupGroupSize: 0)],
            now: now
        )
        let card = cardOf(board, .duplicates)
        XCTAssertEqual(0, card.totalCount)
        XCTAssertEqual(0, card.highBytes)
        XCTAssertEqual(0, board.heroReclaimBytes)
    }

    func testMixedGroupSimilarOnlyMemberStaysInReview() {
        // 2 exact（HIGH）+ 1 similar-only（MEDIUM 落请确认段）同卡，highBytes 只按 exact 组扣 1 张
        let board = OrganizeCategorizer.board(
            [
                item("d1", sizeBytes: 5_000_000, exactDupGroupSize: 2, exactDupGroupKey: "md5a"),
                item("d2", sizeBytes: 5_000_000, exactDupGroupSize: 2, exactDupGroupKey: "md5a"),
                item("s1", sizeBytes: 4_000_000, similarDupGroupSize: 3),
            ],
            now: now
        )
        let card = cardOf(board, .duplicates)
        XCTAssertEqual(3, card.totalCount)
        XCTAssertEqual(2, card.highCount)
        XCTAssertEqual(1, card.reviewCount)
        XCTAssertEqual(5_000_000, card.highBytes)
    }

    func testKeeperNotDeductedWithoutGroupKeyDefensiveFallback() {
        // 防御分支：有组大小无组标识无法按组扣减 → 不扣（按全组字节计）
        let board = OrganizeCategorizer.board(
            [
                item("d1", sizeBytes: 5_000_000, exactDupGroupSize: 2),
                item("d2", sizeBytes: 5_000_000, exactDupGroupSize: 2),
            ],
            now: now
        )
        XCTAssertEqual(10_000_000, cardOf(board, .duplicates).highBytes)
    }

    func testNeedsScanCoverageWhenNoLibraryItemHasSignal() {
        let board = OrganizeCategorizer.board([item("a")], now: now) // 全库无 blurScore/exposureScore
        // 六类目全量产卡：零命中类目以 totalCount=0 + NEEDS_SCAN 引导态呈现
        XCTAssertEqual(OrganizeCategory.allCases.count, board.categories.count)
        let photosCard = cardOf(board, .lowQualityPhotos)
        XCTAssertEqual(0, photosCard.totalCount)
        XCTAssertEqual(.needsScan, photosCard.coverage)
        XCTAssertEqual(.ready, cardOf(board, .screenContent).coverage)
        // 覆盖度亦可绕开 board 直查
        XCTAssertEqual(.needsScan,
                       OrganizeCategorizer.coverageOf(.lowQualityPhotos, items: [item("a")]))
        XCTAssertEqual(.ready,
                       OrganizeCategorizer.coverageOf(.screenContent, items: [item("a")]))
        // 任一媒体持有信号即 READY
        XCTAssertEqual(.ready,
                       OrganizeCategorizer.coverageOf(.lowQualityPhotos, items: [item("a", blurScore: 10.0)]))
        XCTAssertEqual(.ready,
                       OrganizeCategorizer.coverageOf(.documents, items: [item("a", labels: "x")]))
        XCTAssertEqual(.ready,
                       OrganizeCategorizer.coverageOf(.lowQualityPortraits, items: [item("a", faceQualityScore: 0.9)]))
    }

    func testReviewCountAggregatesNonHighNonProtected() {
        let board = OrganizeCategorizer.board(
            [
                item("borderline", sizeBytes: 10, blurScore: 80.0),  // MEDIUM
                item("aesthetic", sizeBytes: 10, aestheticScore: 2.0,
                     blurScore: 500.0, exposureScore: 0.5),            // 不进类目（无强信号）
            ],
            now: now
        )
        XCTAssertEqual(1, board.heroReviewCount)
        XCTAssertEqual(1, cardOf(board, .lowQualityPhotos).reviewCount)
    }

    func testPreviewUrisTruncatedTo4InStableUriSortedOrder() {
        // 乱序输入：预览恒按 uri 字典序取前 4
        let board = OrganizeCategorizer.board(
            [
                item("s5", isScreenshot: true),
                item("s2", isScreenshot: true),
                item("s4", isScreenshot: true),
                item("s1", isScreenshot: true),
                item("s3", isScreenshot: true),
            ],
            now: now
        )
        XCTAssertEqual(["s1", "s2", "s3", "s4"], cardOf(board, .screenContent).previewUris)
    }

    func testEmptyInputYieldsSixZeroCardsAndZeroHero() {
        let board = OrganizeCategorizer.board([], now: now)
        XCTAssertEqual(OrganizeCategory.allCases.count, board.categories.count)
        for card in board.categories {
            XCTAssertEqual(0, card.totalCount)
            XCTAssertEqual(0, card.highCount)
            XCTAssertEqual(0, card.reviewCount)
            XCTAssertEqual(0, card.protectedCount)
            XCTAssertEqual(0, card.totalBytes)
            XCTAssertEqual(0, card.highBytes)
        }
        XCTAssertEqual(0, board.heroReclaimBytes)
        XCTAssertEqual(0, board.heroReviewCount)
    }
}
