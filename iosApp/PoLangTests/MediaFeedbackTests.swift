import XCTest
@testable import PoLang

/// 反馈加权测试（contracts.md §8，读路径）。
/// 覆盖：scoreAdjustments 权重计算（LIKE_BONUS=DISLIKE_PENALTY=0.15、delta==0 不出现）、
/// query_text 精确等值（R10）。
/// 2026-11-08 横滑相册卡退役：record/resolveTarget 写路径用例随生产代码整删（唯一
/// 消费方=横滑卡三反馈钮已退役；测试数据直接走 db.insertMediaFeedback 落库）。
final class MediaFeedbackTests: XCTestCase {

    private func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "feedback_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    // MARK: - scoreAdjustments（§8 权重公式）

    func testScoreAdjustmentsWeight() {
        let db = makeDb()
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s2")
        db.insertMediaFeedback(mediaId: "42", feedbackType: "dislike", queryText: "猫", sessionId: "s3")
        db.insertMediaFeedback(mediaId: "7", feedbackType: "dislike", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "9", feedbackType: "like", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "9", feedbackType: "dislike", queryText: "猫", sessionId: "s1")

        let adj = MediaFeedbackUseCase(db: db).scoreAdjustments(queryText: "猫")
        // delta = like*0.15 - dislike*0.15
        XCTAssertEqual(adj["42"] ?? 0, 0.15, accuracy: 1e-6, "2 like - 1 dislike = 0.15")
        XCTAssertEqual(adj["7"] ?? 0, -0.15, accuracy: 1e-6)
        XCTAssertNil(adj["9"], "like/dislike 相等 → delta == 0 不出现（§8：delta == 0 不动）")
    }

    func testScoreAdjustmentsExactQueryMatch() {
        let db = makeDb()
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "7", feedbackType: "like", queryText: "猫咪", sessionId: "s1")
        let uc = MediaFeedbackUseCase(db: db)
        // R10：query_text 精确等值——"猫咪" 的反馈不影响 "猫" 的调整分
        XCTAssertEqual(uc.scoreAdjustments(queryText: "猫")["42"] ?? 0, 0.15, accuracy: 1e-6)
        XCTAssertNil(uc.scoreAdjustments(queryText: "猫")["7"])
        XCTAssertEqual(uc.scoreAdjustments(queryText: "猫咪")["7"] ?? 0, 0.15, accuracy: 1e-6)
        XCTAssertTrue(uc.scoreAdjustments(queryText: "狗").isEmpty)
    }
}
