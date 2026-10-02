import XCTest
@testable import PoLang

final class TagDatabaseScanTests: XCTestCase {
    /// 用临时库测试，避免污染 Documents/polang_tag.db。
    func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "tag_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    // MARK: media_assets get-or-create
    func testGetOrCreateIsStable() {
        let db = makeDb()
        let id1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE",
                                      captureDateMs: 1_000, fileName: "a.jpg")
        let id2 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE",
                                      captureDateMs: 1_000, fileName: "a.jpg")
        XCTAssertEqual(id1, id2, "同一 localIdentifier 必须返回同一 id")
        XCTAssertGreaterThan(id1, 0)
    }
    func testGetOrCreateDistinct() {
        let db = makeDb()
        let a = db.getOrCreateMedia(localIdentifier: "A", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let b = db.getOrCreateMedia(localIdentifier: "B", type: "IMAGE", captureDateMs: 2, fileName: "b")
        XCTAssertNotEqual(a, b)
    }
    func testUpdateScanFieldsWritesAndCounts() {
        let db = makeDb()
        let id = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        db.updateMediaAssetsScanFields(
            mediaId: id, hasFace: true, faceRoiResult: "{\"boxes\":[]}",
            faceFocusY: 0.4, semanticEmbedding: "BASE64",
            lastTagScanPasses: "{\"1\":1234}"
        )
        XCTAssertEqual(db.pass1CoveredMediaIds(), Set([id]))
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 1)
        XCTAssertEqual(stats.withFace, 1)
        XCTAssertEqual(stats.withSemantic, 1)
        XCTAssertEqual(stats.faceEmbeddingCount, 0)
        XCTAssertEqual(stats.remainingPass1, 0, "已 Pass1 覆盖，剩余应为 0")
    }
    func testRemainingPass1CountsUnscanned() {
        let db = makeDb()
        _ = db.getOrCreateMedia(localIdentifier: "A", type: "IMAGE", captureDateMs: 1, fileName: "a")
        _ = db.getOrCreateMedia(localIdentifier: "B", type: "IMAGE", captureDateMs: 2, fileName: "b")
        XCTAssertEqual(db.scanStats().remainingPass1, 2)
        XCTAssertEqual(db.scanStats().totalMedia, 2)
    }
    func testAllImageMediaIdsDescByCaptureDate() {
        let db = makeDb()
        let a = db.getOrCreateMedia(localIdentifier: "A", type: "IMAGE", captureDateMs: 100, fileName: "a")
        let b = db.getOrCreateMedia(localIdentifier: "B", type: "IMAGE", captureDateMs: 200, fileName: "b")
        XCTAssertEqual(db.allImageMediaIds(), [b, a], "按 captureDate 降序")
    }

    // MARK: tag_scan_tasks
    func testEnqueueAndPollFifo() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [10, 20, 30], now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 3)
        let first = db.pollNextPending(sessionId: "S1", now: now)
        XCTAssertEqual(first?.mediaId, 10)
        XCTAssertNotNil(first?.taskId)
        XCTAssertEqual(first?.pass, "FACE_DETECTION")
        db.markRunning(taskId: first!.taskId, now: now)
        let second = db.pollNextPending(sessionId: "S1", now: now)
        XCTAssertEqual(second?.mediaId, 20)
    }
    func testMarkCompletedReducesPending() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markRunning(taskId: t.taskId, now: now)
        db.markCompleted(taskId: t.taskId, now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "COMPLETED"), 1)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 1)
    }
    func testMarkFailedSetsBackoff() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markFailed(taskId: t.taskId, now: now, errorMessage: "boom", backoffMs: 5_000)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "FAILED"), 1)
        XCTAssertNil(db.pollNextPending(sessionId: "S1", now: now + 1_000), "backoff 未到不应 poll")
        XCTAssertNotNil(db.pollNextPending(sessionId: "S1", now: now + 6_000), "backoff 过后可 poll")
    }
    func testPauseCancelSession() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2, 3], now: now)
        db.pauseSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PAUSED"), 3)
        XCTAssertNil(db.pollNextPending(sessionId: "S1", now: now), "PAUSED 不应被 poll")
        db.resumeSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 3)
        db.cancelSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "CANCELLED"), 3)
    }
    func testResetRunningToPending() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markRunning(taskId: t.taskId, now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "RUNNING"), 1)
        db.resetRunningToPending(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 2)
    }
    func testUnfinishedSessionDetected() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        XCTAssertNil(db.unfinishedSessionId())
        db.enqueuePass1Tasks(sessionId: "S7", mediaIds: [1], now: now)
        XCTAssertEqual(db.unfinishedSessionId(), "S7")
        db.cancelSession(sessionId: "S7")
        XCTAssertNil(db.unfinishedSessionId(), "全部 CANCELLED 后无未完成 session")
    }

    // MARK: persons / Pass2 clustering DAO
    func testInsertPersonAndAssign() {
        let db = makeDb()
        let mid1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let mid2 = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        let emb = Data(repeating: 0, count: 2048) // 512 Float32
        db.insertEmbeddings(mediaId: mid1, embeddings: [emb])
        db.insertEmbeddings(mediaId: mid2, embeddings: [emb])

        let pid = db.insertPerson(name: nil, coverMediaId: mid1, faceCount: 2, isSelf: false)
        XCTAssertGreaterThan(pid, 0)

        let mediaIds = [mid1, mid2]
        db.assignEmbeddingsByMediaIds(mediaIds, personId: pid)
        db.updateFaceIdBatch(mediaIds, faceId: String(pid))

        let map = db.faceIdByLocalIdentifier()
        XCTAssertEqual(map["L-1"], String(pid))
        XCTAssertEqual(map["L-2"], String(pid))

        XCTAssertTrue(db.getUnassignedEmbeddings().isEmpty, "赋值后无未分配 embedding")
    }

    func testClearAndReset() {
        let db = makeDb()
        let mid = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        db.insertEmbeddings(mediaId: mid, embeddings: [Data(repeating: 0, count: 2048)])
        let pid = db.insertPerson(name: "Test", coverMediaId: mid, faceCount: 1, isSelf: false)
        db.assignEmbeddingsByMediaIds([mid], personId: pid)
        db.updateFaceIdBatch([mid], faceId: String(pid))

        // 清空
        db.clearAllPersons()
        db.resetAllEmbeddingAssignments()
        db.resetAllFaceIds()

        XCTAssertTrue(db.faceIdByLocalIdentifier().isEmpty, "清空后无 faceId")
        XCTAssertFalse(db.getUnassignedEmbeddings().isEmpty, "reset 后 embedding 重新未分配")
    }

    // MARK: deleteMediaByLocalIdentifiers / reconcileMediaAssets（防线1/2：删图同步清 media_assets）
    func testDeleteMediaByLocalIdentifiersCascades() {
        let db = makeDb()
        let l1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let l2 = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        _ = db.getOrCreateMedia(localIdentifier: "L-3", type: "IMAGE", captureDateMs: 3, fileName: "c")
        let emb = Data(repeating: 0, count: 2048) // 512 Float32
        db.insertEmbeddings(mediaId: l1, embeddings: [emb])
        db.insertEmbeddings(mediaId: l2, embeddings: [emb])
        let p1 = db.insertPerson(name: nil, coverMediaId: l1, faceCount: 2, isSelf: false)
        db.assignEmbeddingsByMediaIds([l1, l2], personId: p1)

        // 删 L-1：totalMedia 减 1，L-1 的 embedding 级联删，P1 仍有 L-2 → face_count 重算为 1、不删
        db.deleteMediaByLocalIdentifiers(["L-1"])
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 2, "删 1 张后剩 2 张")
        XCTAssertEqual(stats.faceEmbeddingCount, 1, "L-1 的 embedding 应级联删除")
        XCTAssertEqual(stats.personCount, 1, "P1 仍有 L-2 的 embedding，不应被删")
    }

    func testDeleteOrphanRemovesEmptyPerson() {
        let db = makeDb()
        let l1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let l2 = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        let l3 = db.getOrCreateMedia(localIdentifier: "L-3", type: "IMAGE", captureDateMs: 3, fileName: "c")
        let emb = Data(repeating: 0, count: 2048)
        db.insertEmbeddings(mediaId: l1, embeddings: [emb])
        db.insertEmbeddings(mediaId: l2, embeddings: [emb])
        db.insertEmbeddings(mediaId: l3, embeddings: [emb])
        let p1 = db.insertPerson(name: nil, coverMediaId: l1, faceCount: 2, isSelf: false)
        let p2 = db.insertPerson(name: nil, coverMediaId: l3, faceCount: 1, isSelf: false)
        db.assignEmbeddingsByMediaIds([l1, l2], personId: p1)
        db.assignEmbeddingsByMediaIds([l3], personId: p2)
        XCTAssertEqual(db.scanStats().personCount, 2)

        // 删 L-1 + L-2：P1 无 embedding → 被 reconcile 删；P2 保留
        db.deleteMediaByLocalIdentifiers(["L-1", "L-2"])
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 1)
        XCTAssertEqual(stats.personCount, 1, "P1 空簇应被删，只剩 P2")
        XCTAssertEqual(stats.faceEmbeddingCount, 1, "只剩 L-3 的 embedding")
    }

    func testReconcileMediaAssetsDropsMissing() {
        let db = makeDb()
        _ = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        _ = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        _ = db.getOrCreateMedia(localIdentifier: "L-3", type: "IMAGE", captureDateMs: 3, fileName: "c")
        XCTAssertEqual(db.scanStats().totalMedia, 3)

        // 系统里只剩 L-3 → L-1/L-2 是孤儿，应被清
        db.reconcileMediaAssets(keepLocalIdentifiers: ["L-3"])
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 1, "孤儿行应被清理")
        XCTAssertEqual(db.allImageMediaIds().count, 1)
    }

    // MARK: scanStats 成果格三字段（v4.1：cityCount / groupPhotoCount / selfPhotoCount）
    func testScanStatsAchievementCounts() {
        let db = makeDb()
        // 空库：三字段恒 0
        let empty = db.scanStats()
        XCTAssertEqual(empty.cityCount, 0)
        XCTAssertEqual(empty.groupPhotoCount, 0)
        XCTAssertEqual(empty.selfPhotoCount, 0)

        let m1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let m2 = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        let m3 = db.getOrCreateMedia(localIdentifier: "L-3", type: "IMAGE", captureDateMs: 3, fileName: "c")
        let emb = Data(repeating: 0, count: 2048)
        db.insertEmbeddings(mediaId: m1, embeddings: [emb, emb])
        db.insertEmbeddings(mediaId: m2, embeddings: [emb, emb])
        db.insertEmbeddings(mediaId: m3, embeddings: [emb])
        let p1 = db.insertPerson(name: nil, coverMediaId: m1, faceCount: 3, isSelf: false)
        let p2 = db.insertPerson(name: nil, coverMediaId: m3, faceCount: 2, isSelf: true)

        // m1 = 合照（embedding 分属 p1/p2）；m2 = 同一人两张脸（非合照）；m3 = 「我」照片。
        // DAO 无单 embedding 赋值 API，测试直接走 exec 精确构造。
        db.queue.sync {
            db.exec("""
                UPDATE face_embeddings SET person_id = \(p1)
                WHERE media_id = \(m1) AND embedding_id = (
                    SELECT MIN(embedding_id) FROM face_embeddings WHERE media_id = \(m1));
                """)
            db.exec("""
                UPDATE face_embeddings SET person_id = \(p2)
                WHERE media_id = \(m1) AND embedding_id = (
                    SELECT MAX(embedding_id) FROM face_embeddings WHERE media_id = \(m1));
                """)
            db.exec("UPDATE face_embeddings SET person_id = \(p1) WHERE media_id = \(m2);")
            db.exec("UPDATE face_embeddings SET person_id = \(p2) WHERE media_id = \(m3);")
            // city：m1/m2 同城、一条空串（不计）、m3 空串（不计）
            db.exec("UPDATE media_assets SET city = 'Shanghai' WHERE id IN (\(m1), \(m2));")
            db.exec("UPDATE media_assets SET city = '' WHERE id = \(m3);")
        }

        let stats = db.scanStats()
        XCTAssertEqual(stats.groupPhotoCount, 1, "仅 m1 同照片聚到 ≥2 人物")
        XCTAssertEqual(stats.selfPhotoCount, 2, "is_self 人物 p2 出现在 m1 与 m3")
        XCTAssertEqual(stats.cityCount, 1, "DISTINCT 非空 city 只有 Shanghai")
    }

    func testScanStatsSelfUnsetIsZero() {
        let db = makeDb()
        let m1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        db.insertEmbeddings(mediaId: m1, embeddings: [Data(repeating: 0, count: 2048)])
        let p1 = db.insertPerson(name: nil, coverMediaId: m1, faceCount: 1, isSelf: false)
        db.assignEmbeddingsByMediaIds([m1], personId: p1)
        XCTAssertEqual(db.scanStats().selfPhotoCount, 0, "未标记 is_self 时恒 0")
    }
}
