import Foundation
import SQLite3

/// SQLITE_TRANSIENT：让 sqlite 在 bind 时立即拷贝文本/blob（Swift String 跨 bind→step 不保证指针有效）。
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// MARK: - 扫描 DB 统计（对齐 Android TagScanDbStats；SP-B 未实现列恒 0）
struct ScanDbStats: Sendable, Equatable {
    let totalMedia: Int
    let withFace: Int
    let withLabels: Int      // SP-D
    let withSemantic: Int
    let personCount: Int     // SP-C
    let namedPersonCount: Int// SP-C
    let faceEmbeddingCount: Int
    let remainingPass1: Int
    let remainingPass3: Int  // SP-D
    let cityCount: Int       // DISTINCT 非空 city（成果格，对齐 Android getDistinctCityCount）
    let groupPhotoCount: Int // 同照片聚到 ≥2 人物（成果格，对齐 Android getGroupPhotoMediaIds().size）
    let selfPhotoCount: Int  // is_self 人物照片数（成果格，未标记=0，对齐 Android getSelfPersonMediaIds().size）

    init(totalMedia: Int, withFace: Int, withLabels: Int, withSemantic: Int,
         personCount: Int, namedPersonCount: Int, faceEmbeddingCount: Int,
         remainingPass1: Int, remainingPass3: Int,
         cityCount: Int = 0, groupPhotoCount: Int = 0, selfPhotoCount: Int = 0) {
        self.totalMedia = totalMedia
        self.withFace = withFace
        self.withLabels = withLabels
        self.withSemantic = withSemantic
        self.personCount = personCount
        self.namedPersonCount = namedPersonCount
        self.faceEmbeddingCount = faceEmbeddingCount
        self.remainingPass1 = remainingPass1
        self.remainingPass3 = remainingPass3
        self.cityCount = cityCount
        self.groupPhotoCount = groupPhotoCount
        self.selfPhotoCount = selfPhotoCount
    }
}

extension TagDatabase {

    // MARK: - media_assets: get-or-create / 更新 / 查询

    /// get-or-create：按 localIdentifier 返回稳定 Int64 id（对齐 Android media_assets.id）。
    @discardableResult
    func getOrCreateMedia(localIdentifier: String, type: String,
                          captureDateMs: Int64, fileName: String) -> Int64 {
        queue.sync {
            guard let db = db else { return -1 }
            // 先查
            var id: Int64 = -1
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE localIdentifier = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, localIdentifier, -1, SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW { id = sqlite3_column_int64(stmt, 0) }
            sqlite3_finalize(stmt)
            if id > 0 { return id }
            // 插入
            sqlite3_prepare_v2(db, """
            INSERT INTO media_assets (uri, type, captureDate, fileName, localIdentifier)
            VALUES (?, ?, ?, ?, ?);
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, localIdentifier, -1, SQLITE_TRANSIENT) // uri = localIdentifier
            sqlite3_bind_text(stmt, 2, type, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 3, captureDateMs)
            sqlite3_bind_text(stmt, 4, fileName, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 5, localIdentifier, -1, SQLITE_TRANSIENT)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// 写 Pass1 产出列（目标 media_assets）。
    func updateMediaAssetsScanFields(mediaId: Int64, hasFace: Bool,
                                     faceRoiResult: String?, faceFocusY: Double?,
                                     semanticEmbedding: String?, lastTagScanPasses: String?) {
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }

            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO media_assets(id) VALUES (?);", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)

            sqlite3_prepare_v2(db, """
            UPDATE media_assets SET hasFace=?, faceRoiResult=?, faceFocusY=?,
                semanticEmbedding=?, lastTagScanPasses=?, lastTagScanAt=?
            WHERE id=?;
            """, -1, &stmt, nil)
            sqlite3_bind_int(stmt, 1, hasFace ? 1 : 0)
            if let s = faceRoiResult { sqlite3_bind_text(stmt, 2, s, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 2) }
            if let f = faceFocusY { sqlite3_bind_double(stmt, 3, f) } else { sqlite3_bind_null(stmt, 3) }
            if let e = semanticEmbedding { sqlite3_bind_text(stmt, 4, e, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 4) }
            if let p = lastTagScanPasses { sqlite3_bind_text(stmt, 5, p, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 5) }
            sqlite3_bind_int64(stmt, 6, Int64(Date().timeIntervalSince1970 * 1000))
            sqlite3_bind_int64(stmt, 7, mediaId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 已完成 Pass1 的 mediaId 集合（lastTagScanPasses 含 "1"）。
    func pass1CoveredMediaIds() -> Set<Int64> {
        queue.sync {
            guard let db = db else { return [] }
            var out = Set<Int64>()
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id, lastTagScanPasses FROM media_assets WHERE lastTagScanPasses IS NOT NULL;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = sqlite3_column_int64(stmt, 0)
                if let cs = sqlite3_column_text(stmt, 1) {
                    if String(cString: cs).contains("\"1\"") { out.insert(id) }
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 所有图片 mediaId（按 captureDate 降序，对齐 fetchAllMedia 排序）。
    func allImageMediaIds() -> [Int64] {
        queue.sync {
            guard let db = db else { return [] }
            var out: [Int64] = []
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE type = 'IMAGE' ORDER BY captureDate DESC;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW { out.append(sqlite3_column_int64(stmt, 0)) }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// localIdentifier → id。
    func mediaId(forLocalIdentifier lid: String) -> Int64? {
        queue.sync {
            guard let db = db else { return nil }
            var id: Int64 = -1
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE localIdentifier = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, lid, -1, SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW { id = sqlite3_column_int64(stmt, 0) }
            sqlite3_finalize(stmt)
            return id > 0 ? id : nil
        }
    }

    /// mediaId → uri（=localIdentifier），给扫描循环加载图片用。
    func localIdentifier(forMediaId mediaId: Int64) -> String? {
        queue.sync {
            guard let db = db else { return nil }
            var lid: String?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri FROM media_assets WHERE id=? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            if sqlite3_step(stmt) == SQLITE_ROW, let cs = sqlite3_column_text(stmt, 0) {
                lid = String(cString: cs)
            }
            sqlite3_finalize(stmt)
            return lid
        }
    }

    /// 统计（对齐 Android TagScanDbStats；SP-B 未实现列返回 0）。
    func scanStats() -> ScanDbStats {
        queue.sync {
            guard let db = db else {
                return ScanDbStats(totalMedia: 0, withFace: 0, withLabels: 0, withSemantic: 0,
                                   personCount: 0, namedPersonCount: 0, faceEmbeddingCount: 0,
                                   remainingPass1: 0, remainingPass3: 0)
            }
            func countInt(_ sql: String) -> Int {
                var n = 0
                var stmt: OpaquePointer?
                sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
                if sqlite3_step(stmt) == SQLITE_ROW { n = Int(sqlite3_column_int(stmt, 0)) }
                sqlite3_finalize(stmt)
                return n
            }
            return ScanDbStats(
                totalMedia: countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE';"),
                withFace: countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND hasFace=1;"),
                withLabels: countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND labelsEn IS NOT NULL;"),
                withSemantic: countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND semanticEmbedding IS NOT NULL;"),
                personCount: countInt("SELECT COUNT(*) FROM persons;"),
                namedPersonCount: countInt("SELECT COUNT(*) FROM persons WHERE name IS NOT NULL AND name != '';"),
                faceEmbeddingCount: countInt("SELECT COUNT(*) FROM face_embeddings;"),
                remainingPass1: countInt("""
                    SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND (
                        lastTagScanPasses IS NULL OR lastTagScanPasses NOT LIKE '%\"1\"%');
                    """),
                remainingPass3: countInt("""
                    SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND labelsEn IS NULL;
                    """),
                cityCount: countInt("""
                    SELECT COUNT(DISTINCT city) FROM media_assets WHERE city IS NOT NULL AND city != '';
                    """),
                groupPhotoCount: countInt("""
                    SELECT COUNT(*) FROM (
                        SELECT media_id FROM face_embeddings
                        GROUP BY media_id
                        HAVING COUNT(DISTINCT person_id) >= 2);
                    """),
                selfPhotoCount: countInt("""
                    SELECT COUNT(DISTINCT media_id) FROM face_embeddings
                    WHERE person_id = (SELECT person_id FROM persons WHERE is_self = 1 LIMIT 1);
                    """)
            )
        }
    }

    // MARK: - media_assets: 删除 / reconcile（对齐 Android deleteMediaByIds + syncSystemMediaToDb）

    /// 按系统 localIdentifier 删除 media_assets 快照行，并级联修 face_embeddings / persons。
    /// 防线1：用户删图后由 PhMediaBridge.deleteMedia 的 success 回调调用。
    /// media_assets.uri = localIdentifier（见 getOrCreateMedia 绑定），按 uri 删与
    /// hasFaceLocalIdentifiers() 的 SELECT uri 口径一致。
    func deleteMediaByLocalIdentifiers(_ lids: [String]) {
        guard !lids.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }
            // 分批绑定，避开 SQLite SQLITE_MAX_VARIABLE_NUMBER（默认 999）上限。
            let batchSize = 500
            for start in stride(from: 0, to: lids.count, by: batchSize) {
                let end = min(start + batchSize, lids.count)
                let chunk = Array(lids[start..<end])
                var stmt: OpaquePointer?
                let placeholders = Array(repeating: "?", count: chunk.count).joined(separator: ",")
                sqlite3_prepare_v2(db, "DELETE FROM media_assets WHERE uri IN (\(placeholders));", -1, &stmt, nil)
                for (i, lid) in chunk.enumerated() {
                    sqlite3_bind_text(stmt, Int32(i + 1), lid, -1, SQLITE_TRANSIENT)
                }
                sqlite3_step(stmt)
                sqlite3_finalize(stmt)
            }
        }
        // 级联修 face_embeddings / persons / cover_media_id：reconcilePersons() 幂等。
        // ⚠️ queue.sync 不可重入 → 必须在本方法的 queue.sync 块之外调用
        // （reconcilePersons 内部已分多段 queue.sync；见 sessionCounts 同类约束）。
        // 级联修 face_embeddings / persons / cover_media_id：reconcilePersons() 幂等。
        // ⚠️ queue.sync 不可重入 → 必须在本方法的 queue.sync 块之外调用
        // （reconcilePersons 内部已分多段 queue.sync；见 sessionCounts 同类约束）。
        reconcilePersons()
    }

    /// 删除 media_assets 中已不存在于系统相册的孤儿行（扫描开始时自愈）。
    /// 防线2：兜底任何来源的删除（系统相册 app / 其它 app）。
    /// 在 Swift 端求差集（DB 有 − 系统有 = orphans），避免 NOT IN (?,?…) 受绑定变量上限影响。
    func reconcileMediaAssets(keepLocalIdentifiers keep: Set<String>) {
        var orphans: [String] = []
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri FROM media_assets WHERE type='IMAGE';", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let cs = sqlite3_column_text(stmt, 0) {
                    let lid = String(cString: cs)
                    if !keep.contains(lid) { orphans.append(lid) }
                }
            }
            sqlite3_finalize(stmt)
        }
        guard !orphans.isEmpty else { return }
        deleteMediaByLocalIdentifiers(orphans)
    }

    // MARK: - tag_scan_tasks: 队列

    struct QueuedTask: Sendable {
        let taskId: Int64
        let mediaId: Int64
        let pass: String
    }

    /// 批量入队 Pass1 任务。
    func enqueuePass1Tasks(sessionId: String, mediaIds: [Int64], now: Int64) {
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            INSERT INTO tag_scan_tasks (sessionId, mediaId, pass, status, priority, attemptCount, createdAt, scheduledAt)
            VALUES (?, ?, 'FACE_DETECTION', 'PENDING', 0, 0, ?, ?);
            """, -1, &stmt, nil)
            for mid in mediaIds {
                sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
                sqlite3_bind_int64(stmt, 2, mid)
                sqlite3_bind_int64(stmt, 3, now)
                sqlite3_bind_int64(stmt, 4, now)
                sqlite3_step(stmt)
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }

    /// 取下一条可执行 PENDING（status=PENDING 且 scheduledAt<=now），FIFO。poll 不改状态。
    func pollNextPending(sessionId: String, now: Int64) -> QueuedTask? {
        queue.sync {
            guard let db = db else { return nil }
            var out: QueuedTask?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT id, mediaId, pass FROM tag_scan_tasks
            WHERE sessionId = ?
              AND (status = 'PENDING' OR (status = 'FAILED' AND attemptCount < 3))
              AND (scheduledAt IS NULL OR scheduledAt <= ?)
            ORDER BY priority ASC, id ASC LIMIT 1;
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 2, now)
            if sqlite3_step(stmt) == SQLITE_ROW {
                out = QueuedTask(taskId: sqlite3_column_int64(stmt, 0),
                                 mediaId: sqlite3_column_int64(stmt, 1),
                                 pass: String(cString: sqlite3_column_text(stmt, 2)))
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 原子地「取下一条可执行任务并置为 RUNNING」（SELECT+UPDATE 在同一 queue.sync 内）。
    /// 防止两个并发运行循环抢到同一任务导致重复处理。
    func pollAndMarkRunning(sessionId: String, now: Int64) -> QueuedTask? {
        queue.sync {
            guard let db = db else { return nil }
            var task: QueuedTask?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT id, mediaId, pass FROM tag_scan_tasks
            WHERE sessionId = ?
              AND (status = 'PENDING' OR (status = 'FAILED' AND attemptCount < 3))
              AND (scheduledAt IS NULL OR scheduledAt <= ?)
            ORDER BY priority ASC, id ASC LIMIT 1;
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 2, now)
            if sqlite3_step(stmt) == SQLITE_ROW {
                task = QueuedTask(taskId: sqlite3_column_int64(stmt, 0),
                                  mediaId: sqlite3_column_int64(stmt, 1),
                                  pass: String(cString: sqlite3_column_text(stmt, 2)))
            }
            sqlite3_finalize(stmt)
            if let t = task {
                exec("UPDATE tag_scan_tasks SET status='RUNNING', startedAt=\(now) WHERE id=\(t.taskId);")
            }
            return task
        }
    }

    func markRunning(taskId: Int64, now: Int64) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='RUNNING', startedAt=\(now) WHERE id=\(taskId);") }
    }
    func markCompleted(taskId: Int64, now: Int64) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='COMPLETED', completedAt=\(now) WHERE id=\(taskId);") }
    }
    func markFailed(taskId: Int64, now: Int64, errorMessage: String, backoffMs: Int64) {
        queue.sync {
            let escaped = errorMessage.replacingOccurrences(of: "'", with: "''")
            let sql = "UPDATE tag_scan_tasks SET status='FAILED', completedAt=\(now), " +
                      "errorMessage='\(escaped)', scheduledAt=\(now + backoffMs), " +
                      "attemptCount=(SELECT attemptCount FROM tag_scan_tasks WHERE id=\(taskId))+1 " +
                      "WHERE id=\(taskId);"
            exec(sql)
        }
    }
    /// 清空全部扫描任务（新会话开始时调用，防跨会话累积——否则旧 PENDING/COMPLETED 任务堆积，
    /// 导致任务数远超照片数、扫描不终止；tag_scan_tasks 仅为当前会话的工作队列，无历史价值）。
    func clearAllTasks() {
        queue.sync { exec("DELETE FROM tag_scan_tasks;") }
    }

    func pauseSession(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PAUSED' WHERE sessionId='\(escaped)' AND status IN ('PENDING','RUNNING');")
        }
    }
    func resumeSession(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PENDING' WHERE sessionId='\(escaped)' AND status='PAUSED';")
        }
    }
    func cancelSession(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='CANCELLED' WHERE sessionId='\(escaped)' AND status IN ('PENDING','RUNNING','PAUSED');")
        }
    }
    func resetRunningToPending(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PENDING' WHERE sessionId='\(escaped)' AND status='RUNNING';")
        }
    }
    func retryFailed(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PENDING', errorMessage=NULL WHERE sessionId='\(escaped)' AND status='FAILED';")
        }
    }
    func countTasks(sessionId: String, status: String) -> Int {
        queue.sync {
            guard let db = db else { return 0 }
            var n = 0
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM tag_scan_tasks WHERE sessionId=? AND status=?;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, status, -1, SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW { n = Int(sqlite3_column_int(stmt, 0)) }
            sqlite3_finalize(stmt)
            return n
        }
    }

    /// session 内各状态计数（GROUP BY，避免 countTasks 重入死锁）。
    func sessionCounts(_ sessionId: String) -> (pending: Int, running: Int, completed: Int, failed: Int, total: Int) {
        queue.sync {
            guard let db = db else { return (0,0,0,0,0) }
            var p = 0, r = 0, co = 0, f = 0
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT status, COUNT(*) FROM tag_scan_tasks WHERE sessionId=? GROUP BY status;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let st = String(cString: sqlite3_column_text(stmt, 0))
                let n = Int(sqlite3_column_int(stmt, 1))
                switch st {
                case "PENDING", "PAUSED": p += n
                case "RUNNING": r += n
                case "COMPLETED": co += n
                case "FAILED": f += n
                default: break
                }
            }
            sqlite3_finalize(stmt)
            return (p, r, co, f, p + r + co + f)
        }
    }

    /// 含人脸的 localIdentifier 集合（= media_assets.uri，供相册「按人脸分组」用）。
    func hasFaceLocalIdentifiers() -> Set<String> {
        queue.sync {
            guard let db = db else { return [] }
            var out = Set<String>()
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri FROM media_assets WHERE hasFace=1;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let cs = sqlite3_column_text(stmt, 0) { out.insert(String(cString: cs)) }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    // MARK: - persons / 聚类赋值（Pass2 DBSCAN）

    /// 插入一个人物，返回 person_id。
    @discardableResult
    func insertPerson(name: String?, coverMediaId: Int64?, faceCount: Int, isSelf: Bool) -> Int64 {
        queue.sync {
            guard let db = db else { return -1 }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT INTO persons (name, cover_media_id, face_count, is_self, created_at, updated_at) VALUES (?,?,?,?,?,?);", -1, &stmt, nil)
            if let n = name { sqlite3_bind_text(stmt, 1, n, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 1) }
            if let c = coverMediaId { sqlite3_bind_int64(stmt, 2, c) } else { sqlite3_bind_null(stmt, 2) }
            sqlite3_bind_int(stmt, 3, Int32(faceCount))
            sqlite3_bind_int(stmt, 4, isSelf ? 1 : 0)
            sqlite3_bind_int64(stmt, 5, now)
            sqlite3_bind_int64(stmt, 6, now)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// 把这些 media 的全部 embedding 赋给 personId。
    func assignEmbeddingsByMediaIds(_ mediaIds: [Int64], personId: Int64) {
        guard !mediaIds.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;"); defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE face_embeddings SET person_id=? WHERE media_id=?;", -1, &stmt, nil)
            for mid in mediaIds {
                sqlite3_bind_int64(stmt, 1, personId)
                sqlite3_bind_int64(stmt, 2, mid)
                sqlite3_step(stmt); sqlite3_reset(stmt); sqlite3_clear_bindings(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }

    /// 批量写 media_assets.faceId（= personId 字符串）。
    func updateFaceIdBatch(_ mediaIds: [Int64], faceId: String) {
        guard !mediaIds.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;"); defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE media_assets SET faceId=? WHERE id=?;", -1, &stmt, nil)
            for mid in mediaIds {
                sqlite3_bind_text(stmt, 1, faceId, -1, SQLITE_TRANSIENT)
                sqlite3_bind_int64(stmt, 2, mid)
                sqlite3_step(stmt); sqlite3_reset(stmt); sqlite3_clear_bindings(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }

    /// 全量重聚类前的清空（对标 Android isFullRescan=true）。
    func clearAllPersons() { queue.sync { exec("DELETE FROM persons;") } }
    func resetAllEmbeddingAssignments() { queue.sync { exec("UPDATE face_embeddings SET person_id=NULL;") } }
    func resetAllFaceIds() { queue.sync { exec("UPDATE media_assets SET faceId=NULL;") } }

    /// 「全量扫描」硬重置：仅清空本次扫描会重新产出的数据——face_embeddings / persons / person_relations。
    /// 不触碰 media_assets 的字段（那些由 Pass1 在扫描过程中按图逐张更新，属扫描过程更新而非启动重置），
    /// 也不触碰非扫描产物（如画质/美学评分）。从 0 重新计数，结果只反映本次扫描。
    func clearAllFaceClusteringData() {
        queue.sync {
            exec("DELETE FROM face_embeddings;")
            exec("DELETE FROM persons;")
            exec("DELETE FROM person_relations;")
        }
    }

    /// localIdentifier(=uri) → faceId，供相册「按人物分组」。仅含已聚类（faceId 非空）的 media。
    func faceIdByLocalIdentifier() -> [String: String] {
        queue.sync {
            guard let db = db else { return [:] }
            var out: [String: String] = [:]
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri, faceId FROM media_assets WHERE faceId IS NOT NULL AND faceId != '';", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let uri = sqlite3_column_text(stmt, 0), let fid = sqlite3_column_text(stmt, 1) {
                    out[String(cString: uri)] = String(cString: fid)
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// localIdentifier(=uri) → labels JSON 原文（{scene,activity,objects[],tags[]}），
    /// 供相册「按风景分组」关键词匹配（对齐 Android GetGroupedMediaUseCase）。
    func labelsByLocalIdentifier() -> [String: String] {
        queue.sync {
            guard let db = db else { return [:] }
            var out: [String: String] = [:]
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri, labels FROM media_assets WHERE labels IS NOT NULL AND labels != '';", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let uri = sqlite3_column_text(stmt, 0), let labels = sqlite3_column_text(stmt, 1) {
                    out[String(cString: uri)] = String(cString: labels)
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// localIdentifier(=uri) → city，供相册「按位置分组」。仅含 city 非空 media。
    func cityByLocalIdentifier() -> [String: String] {
        queue.sync {
            guard let db = db else { return [:] }
            var out: [String: String] = [:]
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri, city FROM media_assets WHERE city IS NOT NULL AND city != '';", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let uri = sqlite3_column_text(stmt, 0), let city = sqlite3_column_text(stmt, 1) {
                    out[String(cString: uri)] = String(cString: city)
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// PhotoInfo 面板数据行（media_assets 单条，spec photo_info_dialog 字段源）。
    struct MediaInfoRow {
        public var source: String?
        public var labelsJson: String?
        public var ocrText: String?
        public var latitude: Double?
        public var longitude: Double?
        public var locationName: String?
        public var city: String?
        public var aestheticScore: Double?
        public var faceQualityScore: Double?
        public var hasFace: Bool
        public var faceId: String?
    }

    /// 按 localIdentifier(=uri) 查单条 media_assets 的信息面板字段；未扫描返回 nil。
    func mediaInfoByLocalIdentifier(_ uri: String) -> MediaInfoRow? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT source, labels, ocrText, latitude, longitude, locationName, city,
                       aestheticScore, faceQualityScore, hasFace, faceId
                FROM media_assets WHERE uri = ? LIMIT 1;
                """, -1, &stmt, nil)
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, uri, -1, nil)
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            func text(_ i: Int32) -> String? {
                sqlite3_column_text(stmt, i).map { String(cString: $0) }
            }
            func double(_ i: Int32) -> Double? {
                sqlite3_column_type(stmt, i) != SQLITE_NULL ? sqlite3_column_double(stmt, i) : nil
            }
            return MediaInfoRow(
                source: text(0), labelsJson: text(1), ocrText: text(2),
                latitude: double(3), longitude: double(4),
                locationName: text(5), city: text(6),
                aestheticScore: double(7), faceQualityScore: double(8),
                hasFace: sqlite3_column_int(stmt, 9) == 1,
                faceId: text(10)
            )
        }
    }

    /// 是否存在未完成 session（扫描页中断恢复提示用）。
    func unfinishedSessionId() -> String? {
        queue.sync {
            guard let db = db else { return nil }
            var sid: String?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT sessionId FROM tag_scan_tasks
            WHERE status IN ('PENDING','RUNNING','PAUSED','FAILED') LIMIT 1;
            """, -1, &stmt, nil)
            if sqlite3_step(stmt) == SQLITE_ROW, let cs = sqlite3_column_text(stmt, 0) {
                sid = String(cString: cs)
            }
            sqlite3_finalize(stmt)
            return sid
        }
    }

    // MARK: - Pass3（Florence-2 内容打标）: labelsEn 写入 / 查询 / 队列

    /// 写 media_assets.labelsEn（JSON 数组字符串，如 `["dog","park"]`）。
    func updateLabelsEn(mediaId: Int64, labelsEn: String?) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE media_assets SET labelsEn=? WHERE id=?;", -1, &stmt, nil)
            if let s = labelsEn {
                sqlite3_bind_text(stmt, 1, s, -1, SQLITE_TRANSIENT)
            } else {
                sqlite3_bind_null(stmt, 1)
            }
            sqlite3_bind_int64(stmt, 2, mediaId)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    /// 已有 labelsEn 的 mediaId 集合（Pass3 增量去重用）。
    func labelsEnCoveredMediaIds() -> Set<Int64> {
        queue.sync {
            guard let db = db else { return [] }
            var out = Set<Int64>()
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT id FROM media_assets WHERE type='IMAGE' AND labelsEn IS NOT NULL;
            """, -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                out.insert(sqlite3_column_int64(stmt, 0))
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 批量入队 Pass3 内容打标任务（pass='IMAGE_TAGGING'）。
    func enqueueImageTaggingTasks(sessionId: String, mediaIds: [Int64], now: Int64) {
        guard !mediaIds.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            INSERT INTO tag_scan_tasks (sessionId, mediaId, pass, status, priority, attemptCount, createdAt, scheduledAt)
            VALUES (?, ?, 'IMAGE_TAGGING', 'PENDING', 0, 0, ?, ?);
            """, -1, &stmt, nil)
            for mid in mediaIds {
                sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
                sqlite3_bind_int64(stmt, 2, mid)
                sqlite3_bind_int64(stmt, 3, now)
                sqlite3_bind_int64(stmt, 4, now)
                sqlite3_step(stmt)
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }
}
