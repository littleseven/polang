import XCTest
@testable import PoLang

/// pending_deletes 存储层（gallery-grid.yaml §16b ios_note 会话批量标记持久化）。
final class PendingDeleteStoreTests: XCTestCase {
    /// 用临时库测试，避免污染 Documents/polang_tag.db（同 TagDatabaseScanTests 模式）。
    private var dbPath: String!
    private var db: TagDatabase!

    override func setUp() {
        super.setUp()
        dbPath = NSTemporaryDirectory() + "pending_delete_test_\(UUID().uuidString).db"
        db = TagDatabase(dbPath: dbPath)
    }

    override func tearDown() {
        db = nil
        try? FileManager.default.removeItem(atPath: dbPath)
        super.tearDown()
    }

    func testAddAndQueryOrderedByMarkedAt() {
        db.addPendingDelete(uri: "B", source: "media_pager", markedAtMs: 200)
        db.addPendingDelete(uri: "A", source: "media_pager", markedAtMs: 100)
        db.addPendingDelete(uri: "C", source: "media_pager", markedAtMs: 300)
        XCTAssertEqual(db.pendingDeleteUris(), ["A", "B", "C"], "应按标记时间升序返回")
        XCTAssertEqual(db.pendingDeleteCount(), 3)
    }

    func testDuplicateMarkIsIdempotent() {
        db.addPendingDelete(uri: "A", source: "media_pager", markedAtMs: 100)
        db.addPendingDelete(uri: "A", source: "media_pager", markedAtMs: 500)
        XCTAssertEqual(db.pendingDeleteCount(), 1, "同 uri 重复标记应幂等覆盖")
        XCTAssertEqual(db.pendingDeleteUris(), ["A"])
    }

    func testRemoveSubset() {
        db.addPendingDelete(uri: "A", source: "media_pager", markedAtMs: 100)
        db.addPendingDelete(uri: "B", source: "media_pager", markedAtMs: 200)
        db.addPendingDelete(uri: "C", source: "media_pager", markedAtMs: 300)
        db.removePendingDeletes(["A", "C"])
        XCTAssertEqual(db.pendingDeleteUris(), ["B"])
        db.removePendingDeletes([])  // 空入参零开销不崩
        XCTAssertEqual(db.pendingDeleteCount(), 1)
    }

    /// 🔴 核心契约：进程被杀（DB 重建实例）后标记不丢，恢复执行有据。
    func testMarksSurviveDatabaseReopen() {
        db.addPendingDelete(uri: "A", source: "media_pager", markedAtMs: 100)
        db.addPendingDelete(uri: "B", source: "media_pager", markedAtMs: 200)
        db = nil  // 释放句柄（模拟进程终止）
        let reopened = TagDatabase(dbPath: dbPath)
        XCTAssertEqual(reopened.pendingDeleteUris(), ["A", "B"], "重开库标记应完好")
    }

    func testClearAll() {
        db.addPendingDelete(uri: "A", source: "media_pager", markedAtMs: 100)
        db.clearPendingDeletes()
        XCTAssertEqual(db.pendingDeleteCount(), 0)
        XCTAssertTrue(db.pendingDeleteUris().isEmpty)
    }
}
