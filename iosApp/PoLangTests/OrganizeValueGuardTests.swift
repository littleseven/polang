import XCTest
@testable import PoLang

/// 价值保护测试（语义移植 Android ValueGuardTest + 保守偏置锁定）。
final class OrganizeValueGuardTests: XCTestCase {

    private let now: Int64 = 1_800_000_000_000 // 固定参考时间，测试确定性

    private func item(
        captureDate: Int64? = nil,
        lastViewedAt: Int64? = nil,
        isFavorite: Bool = false,
        personPhotoCount: Int? = nil
    ) -> OrganizeItem {
        OrganizeItem(
            uri: "a", isVideo: false, captureDate: captureDate ?? now, sizeBytes: 1_000_000,
            pixelArea: 12_000_000, blurScore: 10.0,
            lastViewedAt: lastViewedAt, isFavorite: isFavorite,
            personPhotoCount: personPhotoCount
        )
    }

    func testOldPhotoProtectedWithBoundary() {
        let fiveYearsAgo = now - OrganizeThresholds.oldPhotoYears * OrganizeThresholds.yearMillis
        let old = ValueGuard.assess(item(captureDate: fiveYearsAgo - 1),
                                    category: .lowQualityPhotos, now: now)
        XCTAssertTrue(old.isProtected)
        XCTAssertEqual([.oldPhoto], old.reasons)
        // 边界：恰好 5 年不保护（判定为「早于」）
        let boundary = ValueGuard.assess(item(captureDate: fiveYearsAgo),
                                         category: .lowQualityPhotos, now: now)
        XCTAssertFalse(boundary.reasons.contains(.oldPhoto))
        XCTAssertFalse(boundary.isProtected)
    }

    func testUnknownCaptureDateZeroAlwaysProtectedAsOldPhoto() {
        // 保守偏置：captureDate = 0（未知时间戳，下载件常见）恒判老照片进保护区
        // ——有意为之：宁可不预选，不可误删
        let verdict = ValueGuard.assess(item(captureDate: 0),
                                        category: .lowQualityPhotos, now: now)
        XCTAssertEqual([.oldPhoto], verdict.reasons)
    }

    func testScarcePersonProtectedWithBoundary() {
        let scarce = ValueGuard.assess(item(personPhotoCount: 3),
                                       category: .lowQualityPortraits, now: now)
        XCTAssertEqual([.scarcePerson], scarce.reasons)
        let notScarce = ValueGuard.assess(item(personPhotoCount: 4),
                                          category: .lowQualityPortraits, now: now)
        XCTAssertFalse(notScarce.reasons.contains(.scarcePerson))
        // 无人物信息不触发
        let noPerson = ValueGuard.assess(item(personPhotoCount: nil),
                                         category: .lowQualityPortraits, now: now)
        XCTAssertFalse(noPerson.reasons.contains(.scarcePerson))
        // 计数 0 = 异常数据（计数不可能为 0），视为无信号不触发
        let zeroCount = ValueGuard.assess(item(personPhotoCount: 0),
                                          category: .lowQualityPortraits, now: now)
        XCTAssertFalse(zeroCount.reasons.contains(.scarcePerson))
    }

    func testFavoriteOrViewedProtected() {
        let fav = ValueGuard.assess(item(isFavorite: true),
                                    category: .lowQualityPhotos, now: now)
        XCTAssertEqual([.userEngaged], fav.reasons)
        let viewed = ValueGuard.assess(item(lastViewedAt: now - 1000),
                                       category: .lowQualityPhotos, now: now)
        XCTAssertEqual([.userEngaged], viewed.reasons)
    }

    func testProtectionOnlyAppliesToQualityCategories() {
        // 5 年前的截图不保护（价值保护只适用 LOW_QUALITY_PHOTOS / LOW_QUALITY_PORTRAITS）
        let screenshot = ValueGuard.assess(item(captureDate: now - 6 * OrganizeThresholds.yearMillis),
                                           category: .screenContent, now: now)
        XCTAssertFalse(screenshot.isProtected)
        XCTAssertTrue(screenshot.reasons.isEmpty)
    }

    func testMultipleReasonsAccumulate() {
        let verdict = ValueGuard.assess(
            item(captureDate: now - 6 * OrganizeThresholds.yearMillis,
                 isFavorite: true, personPhotoCount: 1),
            category: .lowQualityPhotos, now: now
        )
        XCTAssertEqual([.oldPhoto, .userEngaged, .scarcePerson], verdict.reasons)
    }
}
