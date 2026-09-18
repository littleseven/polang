import Foundation
import SQLite3

/// SQLITE_TRANSIENT：让 sqlite 在 bind 时立即拷贝文本（同 TagDatabase+Scan.swift 定义，fileprivate 不跨文件）。
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// MARK: - organize v2 存储读写（对齐 Android MediaDao organize 段 + DedupHashDao）
// 全部经既有串行 queue；批量写单事务（对齐 Android @Transaction 合批语义）；
// prepare 失败一律 guard + assertionFailure 提前返回（对齐 TagDatabase.getUnassignedEmbeddings 既有模式）。

/// dedup_hash 行（uri 主键；字段可空 = 对应哈希尚未算出，T8 扫描器分阶段回填）。
struct DedupHashEntry: Equatable {
    let uri: String
    let md5: String?
    let phash: Int64?
    let pixelArea: Int64?
    let sizeBytes: Int64?
}

/// 模糊/曝光回写条目（BlurAnalyzer 产出；nil = 解码失败，列保持 NULL 不写 0 哨兵）。
struct QualitySignalEntry {
    let uri: String
    let blurScore: Double?
    let exposureScore: Double?
}

extension TagDatabase {

    // MARK: - media_assets: blur/exposure/lastViewedAt

    /// 批量回写模糊/曝光分：单事务一次提交（对齐 Android updateQualityScoresBatch，
    /// 避免逐行事务）。
    func updateQualitySignals(_ entries: [QualitySignalEntry]) {
        guard !entries.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(
                db,
                "UPDATE media_assets SET blurScore = ?, exposureScore = ? WHERE uri = ?;",
                -1, &stmt, nil
            ) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare updateQualitySignals: \(String(cString: sqlite3_errmsg(db)))")
                return
            }
            defer { sqlite3_finalize(stmt) }
            for entry in entries {
                if let blur = entry.blurScore { sqlite3_bind_double(stmt, 1, blur) } else { sqlite3_bind_null(stmt, 1) }
                if let exposure = entry.exposureScore { sqlite3_bind_double(stmt, 2, exposure) } else { sqlite3_bind_null(stmt, 2) }
                sqlite3_bind_text(stmt, 3, entry.uri, -1, SQLITE_TRANSIENT)
                sqlite3_step(stmt)
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
            }
        }
    }

    /// 回写最近一次查看时间（查看器打开时调用）。
    /// 60s 节流窗对齐 Android updateLastViewedAt：消除 60s 内重复查看同一媒体的无效触发；
    /// ValueGuard 只判 `!= null`，守卫零语义损失。
    func updateLastViewedAt(uri: String, epochMs: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
            UPDATE media_assets SET lastViewedAt = ?
            WHERE uri = ? AND (lastViewedAt IS NULL OR lastViewedAt < ? - 60000);
            """, -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare updateLastViewedAt: \(String(cString: sqlite3_errmsg(db)))")
                return
            }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, epochMs)
            sqlite3_bind_text(stmt, 2, uri, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 3, epochMs)
            sqlite3_step(stmt)
        }
    }

    /// 读取指定 uri 的质量信号（T2 join 用；列 NULL 返回 nil）。
    func qualitySignals(forUris uris: [String]) -> [String: QualitySignalEntry] {
        guard !uris.isEmpty else { return [:] }
        return queue.sync {
            guard let db = db else { return [:] }
            var out: [String: QualitySignalEntry] = [:]
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(
                db,
                "SELECT uri, blurScore, exposureScore FROM media_assets WHERE uri IN (\(uris.map { _ in "?" }.joined(separator: ",")));",
                -1, &stmt, nil
            ) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare qualitySignals: \(String(cString: sqlite3_errmsg(db)))")
                return [:]
            }
            defer { sqlite3_finalize(stmt) }
            for (index, uri) in uris.enumerated() {
                sqlite3_bind_text(stmt, Int32(index + 1), uri, -1, SQLITE_TRANSIENT)
            }
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let cUri = sqlite3_column_text(stmt, 0) else { continue }
                let uri = String(cString: cUri)
                let blur = sqlite3_column_type(stmt, 1) == SQLITE_NULL ? nil : sqlite3_column_double(stmt, 1)
                let exposure = sqlite3_column_type(stmt, 2) == SQLITE_NULL ? nil : sqlite3_column_double(stmt, 2)
                out[uri] = QualitySignalEntry(uri: uri, blurScore: blur, exposureScore: exposure)
            }
            return out
        }
    }

    // MARK: - organize v2 轻量投影读（T2 join 输入；列序对齐 Android OrganizeRow）

    /// 整理中心类目判定的轻量投影行（避开 semanticEmbedding/faceRoiResult 等大列）。
    struct OrganizeDbRow {
        let uri: String              // = localIdentifier
        let type: String             // 'IMAGE' / 'VIDEO'
        let captureDate: Int64
        let ocrText: String?
        let labels: String?
        let hasFace: Bool
        let aestheticScore: Double?
        let faceQualityScore: Double?
        let blurScore: Double?
        let exposureScore: Double?
        let lastViewedAt: Int64?
        let faceId: String?
    }

    /// 全量投影（对齐 Android observeOrganizeRows 的 SELECT 列）。
    func allOrganizeRows() -> [OrganizeDbRow] {
        queue.sync {
            guard let db = db else { return [] }
            var out: [OrganizeDbRow] = []
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
                SELECT uri, type, captureDate, ocrText, labels, hasFace, aestheticScore,
                       faceQualityScore, blurScore, exposureScore, lastViewedAt, faceId
                FROM media_assets;
                """, -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare allOrganizeRows: \(String(cString: sqlite3_errmsg(db)))")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            while sqlite3_step(stmt) == SQLITE_ROW {
                func text(_ i: Int32) -> String? {
                    sqlite3_column_text(stmt, i).map { String(cString: $0) }
                }
                func double(_ i: Int32) -> Double? {
                    sqlite3_column_type(stmt, i) != SQLITE_NULL ? sqlite3_column_double(stmt, i) : nil
                }
                out.append(OrganizeDbRow(
                    uri: text(0) ?? "",
                    type: text(1) ?? "",
                    captureDate: sqlite3_column_int64(stmt, 2),
                    ocrText: text(3),
                    labels: text(4),
                    hasFace: sqlite3_column_int(stmt, 5) == 1,
                    aestheticScore: double(6),
                    faceQualityScore: double(7),
                    blurScore: double(8),
                    exposureScore: double(9),
                    lastViewedAt: sqlite3_column_type(stmt, 10) == SQLITE_NULL
                        ? nil : sqlite3_column_int64(stmt, 10),
                    faceId: text(11)
                ))
            }
            return out
        }
    }

    /// 各人物聚类的照片总数（faceId → 计数；SCARCE_PERSON 信号）。
    /// ⚠️ 口径对齐 Android getPersonPhotoCounts：media_assets 按 faceId GROUP BY 计**照片数**，
    /// 而非 persons.face_count（那是 face_embeddings 条数，一照片多人脸会放大）——
    /// spec §1 value_guard 规则原文「所属人物聚类照片总数 ∈ 1..3」。
    func personPhotoCounts() -> [String: Int] {
        queue.sync {
            guard let db = db else { return [:] }
            var out: [String: Int] = [:]
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
                SELECT faceId, COUNT(*) FROM media_assets
                WHERE faceId IS NOT NULL AND faceId != '' GROUP BY faceId;
                """, -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare personPhotoCounts: \(String(cString: sqlite3_errmsg(db)))")
                return [:]
            }
            defer { sqlite3_finalize(stmt) }
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let cs = sqlite3_column_text(stmt, 0) {
                    out[String(cString: cs)] = Int(sqlite3_column_int(stmt, 1))
                }
            }
            return out
        }
    }

    /// 质量分回填候选（对齐 Android backfill pending 口径：blurScore 未算且非视频）。
    /// exclude/take 由调用方在 Swift 侧做（绕开 SQLite IN 变量上限 999 的分批复杂度）。
    func pendingQualitySignalUris() -> [String] {
        queue.sync {
            guard let db = db else { return [] }
            var out: [String] = []
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
                SELECT uri FROM media_assets WHERE type != 'VIDEO' AND blurScore IS NULL;
                """, -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare pendingQualitySignalUris: \(String(cString: sqlite3_errmsg(db)))")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let cs = sqlite3_column_text(stmt, 0) { out.append(String(cString: cs)) }
            }
            return out
        }
    }

    // MARK: - dedup_hash: upsert / 查询（本批不消费，T8 扫描器用）

    /// 批量 upsert（INSERT OR REPLACE，uri 主键冲突整行覆盖；单事务）。
    func upsertDedupHashes(_ entries: [DedupHashEntry]) {
        guard !entries.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
            INSERT OR REPLACE INTO dedup_hash (uri, md5, phash, pixelArea, sizeBytes)
            VALUES (?, ?, ?, ?, ?);
            """, -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare upsertDedupHashes: \(String(cString: sqlite3_errmsg(db)))")
                return
            }
            defer { sqlite3_finalize(stmt) }
            for entry in entries {
                sqlite3_bind_text(stmt, 1, entry.uri, -1, SQLITE_TRANSIENT)
                if let md5 = entry.md5 { sqlite3_bind_text(stmt, 2, md5, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 2) }
                if let phash = entry.phash { sqlite3_bind_int64(stmt, 3, phash) } else { sqlite3_bind_null(stmt, 3) }
                if let area = entry.pixelArea { sqlite3_bind_int64(stmt, 4, area) } else { sqlite3_bind_null(stmt, 4) }
                if let size = entry.sizeBytes { sqlite3_bind_int64(stmt, 5, size) } else { sqlite3_bind_null(stmt, 5) }
                sqlite3_step(stmt)
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
            }
        }
    }

    /// 全量读取（T8 聚类输入；调用方负责按库内 uri 收敛过滤幽灵行）。
    func allDedupHashes() -> [DedupHashEntry] {
        queue.sync {
            guard let db = db else { return [] }
            var out: [DedupHashEntry] = []
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT uri, md5, phash, pixelArea, sizeBytes FROM dedup_hash;", -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare allDedupHashes: \(String(cString: sqlite3_errmsg(db)))")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            while sqlite3_step(stmt) == SQLITE_ROW {
                let uri = sqlite3_column_text(stmt, 0).map { String(cString: $0) } ?? ""
                let md5 = sqlite3_column_text(stmt, 1).map { String(cString: $0) }
                let phash = sqlite3_column_type(stmt, 2) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 2)
                let area = sqlite3_column_type(stmt, 3) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 3)
                let size = sqlite3_column_type(stmt, 4) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 4)
                out.append(DedupHashEntry(uri: uri, md5: md5, phash: phash, pixelArea: area, sizeBytes: size))
            }
            return out
        }
    }
}
