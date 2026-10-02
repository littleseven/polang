import XCTest
@testable import PoLang

final class LibraryCompletionTests: XCTestCase {

    // MARK: tagPassProgress 入参 clamp

    func testClampNegativeAndOverflow() {
        let p = tagPassProgress(total: -5, remaining: 10)
        XCTAssertEqual(p.total, 0)
        XCTAssertEqual(p.remaining, 0)
        XCTAssertEqual(p.processed, 0)
        XCTAssertEqual(p.fraction, 0)
        XCTAssertTrue(p.isEmpty)
        XCTAssertFalse(p.isComplete)
    }

    func testRemainingClampedToTotal() {
        let p = tagPassProgress(total: 10, remaining: 15)
        XCTAssertEqual(p.remaining, 10)
        XCTAssertEqual(p.processed, 0)
        XCTAssertFalse(p.isComplete)
    }

    func testNegativeRemainingClampedToZero() {
        let p = tagPassProgress(total: 10, remaining: -3)
        XCTAssertEqual(p.remaining, 0)
        XCTAssertEqual(p.processed, 10)
        XCTAssertEqual(p.fraction, 1)
        XCTAssertTrue(p.isComplete)
    }

    // MARK: fraction / isComplete / isEmpty

    func testFractionAndFlags() {
        let p = tagPassProgress(total: 200, remaining: 50)
        XCTAssertEqual(p.processed, 150)
        XCTAssertEqual(p.fraction, 0.75, accuracy: 0.0001)
        XCTAssertFalse(p.isComplete)
        XCTAssertFalse(p.isEmpty)
    }

    // MARK: percentRounded（Double 精确路径）

    func testPercentRoundedUsesDoublePath() {
        // 1/3：Float 路径 33.333→33，Double 精确路径同为 33；用 2/3 验证四舍五入 67
        XCTAssertEqual(tagPassProgress(total: 3, remaining: 1).percentRounded(), 67)
        XCTAssertEqual(tagPassProgress(total: 3, remaining: 2).percentRounded(), 33)
        XCTAssertEqual(tagPassProgress(total: 8, remaining: 7).percentRounded(), 13) // 12.5 → 13（half away from zero，对齐 Kotlin roundToInt）
        XCTAssertEqual(tagPassProgress(total: 0, remaining: 0).percentRounded(), 0)
        XCTAssertEqual(tagPassProgress(total: 100, remaining: 0).percentRounded(), 100)
    }

    // MARK: LibraryCompletion

    func testLibraryCompletionDelegation() {
        let c = LibraryCompletion(totalMedia: 1000, remainingPass3: 250)
        XCTAssertEqual(c.progress.processed, 750)
        XCTAssertEqual(c.fraction, 0.75, accuracy: 0.0001)
        XCTAssertEqual(c.percentRounded(), 75)
    }

    func testLibraryCompletionEmptyLibrary() {
        let c = LibraryCompletion(totalMedia: 0, remainingPass3: 0)
        XCTAssertEqual(c.percentRounded(), 0)
        XCTAssertEqual(c.fraction, 0)
        XCTAssertTrue(c.progress.isEmpty)
    }
}
