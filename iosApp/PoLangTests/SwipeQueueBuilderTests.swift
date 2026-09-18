import XCTest
@testable import PoLang

/// 手势整理队列构建测试（语义移植 Android SwipeQueueBuilderTest：
/// 桶序 / 废片准入 / uri 去重 / 桶内 captureDate 倒序）。
final class SwipeQueueBuilderTests: XCTestCase {

    private let now: Int64 = 1_800_000_000_000

    private func item(
        _ uri: String,
        isVideo: Bool = false,
        captureDate: Int64? = nil,
        blurScore: Double? = nil,
        faceQualityScore: Double? = nil,
        hasFace: Bool = false,
        isFavorite: Bool = false,
        exactDupGroupSize: Int = 0,
        isScreenshot: Bool = false
    ) -> OrganizeItem {
        OrganizeItem(
            uri: uri, isVideo: isVideo, captureDate: captureDate ?? now, sizeBytes: 1_000_000,
            pixelArea: 12_000_000, hasFace: hasFace, faceQualityScore: faceQualityScore,
            blurScore: blurScore, isFavorite: isFavorite,
            exactDupGroupSize: exactDupGroupSize, isScreenshot: isScreenshot
        )
    }

    func testBucketsOrderedScreenshotThenBlurryThenRecent() {
        let queue = SwipeQueueBuilder.build(
            [
                item("recent"),                          // 无类目命中 → RECENT
                item("blur", blurScore: 10.0),
                item("shot", isScreenshot: true),
            ],
            now: now
        )
        XCTAssertEqual(["shot", "blur", "recent"], queue.map { $0.uri })
    }

    func testProtectedLowQualityPhotoFallsBackToRecentBucket() {
        // 收藏过的模糊照片：类目页进保护区，手势队列同样不往废片桶塞（口径一致）
        let queue = SwipeQueueBuilder.build(
            [item("favBlur", blurScore: 10.0, isFavorite: true)],
            now: now
        )
        XCTAssertEqual(.recent, queue.first?.reason)
        XCTAssertEqual(1, queue.count)
    }

    func testVideosNeverEnqueued() {
        let queue = SwipeQueueBuilder.build(
            [item("v", isVideo: true, isScreenshot: true)],
            now: now
        )
        XCTAssertEqual(0, queue.count)
    }

    func testItemLandsInFirstMatchingBucketOnly() {
        let queue = SwipeQueueBuilder.build(
            [item("both", blurScore: 10.0, isScreenshot: true)],
            now: now
        )
        XCTAssertEqual(1, queue.count)
        XCTAssertEqual(.screenshot, queue.first?.reason)
    }

    func testWithinBucketNewestFirst() {
        let queue = SwipeQueueBuilder.build(
            [
                item("old", captureDate: now - 3_000),
                item("new", captureDate: now - 1_000),
                item("mid", captureDate: now - 2_000),
            ],
            now: now
        )
        XCTAssertEqual(["new", "mid", "old"], queue.map { $0.uri })
    }

    func testDuplicatesPhotoFallsBackToRecentBucket() {
        // DUPLICATES 不是 F2 废片桶（去重走类目页），照片落 RECENT 兜底
        let queue = SwipeQueueBuilder.build(
            [item("dup", exactDupGroupSize: 2)],
            now: now
        )
        XCTAssertEqual(.recent, queue.first?.reason)
    }

    func testDuplicateUriRowsProduceSingleCandidate() {
        // media_assets.uri 非唯一索引：重复行进队列只出一张卡（保留最后一条快照）
        let queue = SwipeQueueBuilder.build(
            [
                item("dup", captureDate: now - 1_000),
                item("dup", captureDate: now - 2_000),
                item("other", captureDate: now - 3_000),
            ],
            now: now
        )
        XCTAssertEqual(["dup", "other"], queue.map { $0.uri })
        XCTAssertEqual(now - 2_000, queue.first?.captureDate)
    }

    func testLowQualityPortraitBucketOrderedAfterBlurry() {
        let queue = SwipeQueueBuilder.build(
            [
                item("portrait", faceQualityScore: 0.1, hasFace: true),
                item("blur", blurScore: 10.0),
            ],
            now: now
        )
        XCTAssertEqual(["blur", "portrait"], queue.map { $0.uri })
        XCTAssertEqual(.lowQualityPortrait, queue[1].reason)
    }
}
