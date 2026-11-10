import Foundation
import SQLite3

// MARK: - TagDatabase · 备份 v6 导出/恢复（对标 Android TagDataBackupRepository）
//
// 11 个 SQLite 段的全量导出（SELECT 逐段读）与恢复（单 BEGIN IMMEDIATE 事务，
// 语义照搬 Android：tags 按 name 去重 / scanTasks 重置 PENDING / persons old→new
// id 映射 / mediaTagMetadata 三列 COALESCE / uri 不匹配跳过）。
// 聊天段（文件存储）不在此层，由 BackupRestoreService 编排。

/// SQLITE_TRANSIENT：让 sqlite 在 bind 时立即拷贝文本/blob（Swift String 跨 bind→step 不保证指针有效）。
/// （与 TagDatabase+Person.swift 同定义；private 为文件级，各扩展文件需各自声明。）
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// 导出段集合（11 个 SQLite 段 + personRelations；计数由调用方按数组推导）。
struct SqliteBackupSegments {
    var tags: [BackupTag] = []
    var mediaTagMetadata: [BackupMediaTagMetadata] = []
    var crossRefs: [BackupMediaTagCrossRef] = []
    var scanTasks: [BackupTagScanTask] = []
    var persons: [BackupPerson] = []
    var faceEmbeddings: [BackupFaceEmbedding] = []
    var ocrWords: [BackupOcrWord] = []
    var ocrWordOccurrences: [BackupOcrWordOccurrence] = []
    var locationHierarchy: [BackupLocationHierarchy] = []
    var mediaLocations: [BackupMediaLocation] = []
    var mediaFeedback: [BackupMediaFeedback] = []
    var personRelations: [BackupPersonRelation] = []
}

// MARK: - 列读取工具（可选类型；与 TagDatabase+Person.swift 同口径，文件级私有）

@inline(__always)
private func backupColText(_ stmt: OpaquePointer?, _ idx: Int32) -> String? {
    if sqlite3_column_type(stmt, idx) == SQLITE_NULL { return nil }
    guard let cString = sqlite3_column_text(stmt, idx) else { return nil }
    return String(cString: cString)
}

@inline(__always)
private func backupColInt64OrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Int64? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, idx)
}

@inline(__always)
private func backupColDoubleOrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Double? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : sqlite3_column_double(stmt, idx)
}

@inline(__always)
private func backupColFloatOrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Float? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : Float(sqlite3_column_double(stmt, idx))
}

// MARK: - 绑定工具

@inline(__always)
private func backupBindText(_ stmt: OpaquePointer?, _ idx: Int32, _ value: String?) {
    if let value {
        sqlite3_bind_text(stmt, idx, value, -1, SQLITE_TRANSIENT)
    } else {
        sqlite3_bind_null(stmt, idx)
    }
}

@inline(__always)
private func backupBindInt64(_ stmt: OpaquePointer?, _ idx: Int32, _ value: Int64?) {
    if let value {
        sqlite3_bind_int64(stmt, idx, value)
    } else {
        sqlite3_bind_null(stmt, idx)
    }
}

@inline(__always)
private func backupBindDouble(_ stmt: OpaquePointer?, _ idx: Int32, _ value: Double?) {
    if let value {
        sqlite3_bind_double(stmt, idx, value)
    } else {
        sqlite3_bind_null(stmt, idx)
    }
}

@inline(__always)
private func backupBindFloat(_ stmt: OpaquePointer?, _ idx: Int32, _ value: Float?) {
    backupBindDouble(stmt, idx, value.map(Double.init))
}

extension TagDatabase {

    // MARK: - 导出（11 段 + personRelations 全量读）

    /// 全量导出 SQLite 段（单次 queue.sync 串行读，避免段间穿插写）。
    /// - Parameter exportedAt: 导出时刻（personRelations 无时间戳列，以此填充）。
    func backupExportSegments(exportedAt: Int64) throws -> SqliteBackupSegments {
        try queue.sync {
            guard let db = db else { throw BackupRestoreError.sqlFailed("database not open") }
            var out = SqliteBackupSegments()

            // ── media_assets：uri 映射 + TAG 元数据段（isTagRelatedEmpty 过滤，对齐 Android）──
            var uriById: [Int64: String] = [:]
            let metadataStmt = try backupPrepare(db, """
                SELECT id, uri, labels, labelsEn, labelsZh, mlKitLabels, mlKitLabelsZh, ocrText,
                       latitude, longitude, locationName, faceRoiResult, semanticEmbedding,
                       lastTagScanAt, lastTagScanPasses, hasFace, faceId, city, faceFocusY,
                       aestheticScore, faceQualityScore, blurScore, exposureScore, lastViewedAt
                FROM media_assets;
                """)
            defer { sqlite3_finalize(metadataStmt) }
            while sqlite3_step(metadataStmt) == SQLITE_ROW {
                let id = sqlite3_column_int64(metadataStmt, 0)
                guard let uri = backupColText(metadataStmt, 1) else { continue }
                uriById[id] = uri
                let meta = BackupMediaTagMetadata(
                    uri: uri,
                    labels: backupColText(metadataStmt, 2),
                    labelsEn: backupColText(metadataStmt, 3),
                    labelsZh: backupColText(metadataStmt, 4),
                    mlKitLabels: backupColText(metadataStmt, 5),
                    mlKitLabelsZh: backupColText(metadataStmt, 6),
                    ocrText: backupColText(metadataStmt, 7),
                    latitude: backupColDoubleOrNil(metadataStmt, 8),
                    longitude: backupColDoubleOrNil(metadataStmt, 9),
                    locationName: backupColText(metadataStmt, 10),
                    faceRoiResult: backupColText(metadataStmt, 11),
                    semanticEmbedding: backupColText(metadataStmt, 12),
                    lastTagScanAt: backupColInt64OrNil(metadataStmt, 13),
                    lastTagScanPasses: backupColText(metadataStmt, 14),
                    hasFace: sqlite3_column_int(metadataStmt, 15) != 0,
                    faceId: backupColText(metadataStmt, 16),
                    city: backupColText(metadataStmt, 17),
                    faceFocusY: backupColFloatOrNil(metadataStmt, 18),
                    aestheticScore: backupColFloatOrNil(metadataStmt, 19),
                    faceQualityScore: backupColFloatOrNil(metadataStmt, 20),
                    blurScore: backupColFloatOrNil(metadataStmt, 21),
                    exposureScore: backupColFloatOrNil(metadataStmt, 22),
                    lastViewedAt: backupColInt64OrNil(metadataStmt, 23))
                if !Self.backupIsTagRelatedEmpty(meta) {
                    out.mediaTagMetadata.append(meta)
                }
            }

            // ── tags ──
            let tagsStmt = try backupPrepare(db, "SELECT tagId, name, category FROM tags;")
            defer { sqlite3_finalize(tagsStmt) }
            while sqlite3_step(tagsStmt) == SQLITE_ROW {
                out.tags.append(BackupTag(
                    tagId: sqlite3_column_int64(tagsStmt, 0),
                    name: backupColText(tagsStmt, 1) ?? "",
                    category: backupColText(tagsStmt, 2) ?? "scene"))
            }

            // ── crossRefs（join 出 uri + tagName）──
            let crossStmt = try backupPrepare(db, """
                SELECT ma.uri, t.name, mtc.confidence FROM media_tag_cross_ref mtc
                JOIN tags t ON t.tagId = mtc.tagId
                JOIN media_assets ma ON ma.id = mtc.mediaId;
                """)
            defer { sqlite3_finalize(crossStmt) }
            while sqlite3_step(crossStmt) == SQLITE_ROW {
                out.crossRefs.append(BackupMediaTagCrossRef(
                    uri: backupColText(crossStmt, 0) ?? "",
                    tagName: backupColText(crossStmt, 1) ?? "",
                    confidence: backupColFloatOrNil(crossStmt, 2)))
            }

            // ── scanTasks（media 缺失时 uri 落空串，对齐 Android `mediaIdToUri[it.mediaId] ?: ""`）──
            let scanStmt = try backupPrepare(db, """
                SELECT tst.sessionId, ma.uri, tst.pass, tst.tagCategories, tst.status, tst.priority,
                       tst.attemptCount, tst.createdAt, tst.scheduledAt, tst.startedAt, tst.completedAt, tst.errorMessage
                FROM tag_scan_tasks tst LEFT JOIN media_assets ma ON ma.id = tst.mediaId;
                """)
            defer { sqlite3_finalize(scanStmt) }
            while sqlite3_step(scanStmt) == SQLITE_ROW {
                out.scanTasks.append(BackupTagScanTask(
                    sessionId: backupColText(scanStmt, 0) ?? "",
                    uri: backupColText(scanStmt, 1) ?? "",
                    pass: backupColText(scanStmt, 2) ?? "",
                    tagCategories: backupColText(scanStmt, 3),
                    status: backupColText(scanStmt, 4) ?? "",
                    priority: Int(sqlite3_column_int(scanStmt, 5)),
                    attemptCount: Int(sqlite3_column_int(scanStmt, 6)),
                    createdAt: sqlite3_column_int64(scanStmt, 7),
                    scheduledAt: backupColInt64OrNil(scanStmt, 8),
                    startedAt: backupColInt64OrNil(scanStmt, 9),
                    completedAt: backupColInt64OrNil(scanStmt, 10),
                    errorMessage: backupColText(scanStmt, 11)))
            }

            // ── persons（cover_media_id → uri 映射）──
            let personStmt = try backupPrepare(db, """
                SELECT p.person_id, p.name, p.cover_media_id, p.face_count, p.created_at, p.updated_at, ma.uri
                FROM persons p LEFT JOIN media_assets ma ON ma.id = p.cover_media_id;
                """)
            defer { sqlite3_finalize(personStmt) }
            while sqlite3_step(personStmt) == SQLITE_ROW {
                out.persons.append(BackupPerson(
                    personId: sqlite3_column_int64(personStmt, 0),
                    name: backupColText(personStmt, 1),
                    coverMediaUri: backupColText(personStmt, 6),
                    faceCount: Int(sqlite3_column_int(personStmt, 3)),
                    createdAt: sqlite3_column_int64(personStmt, 4),
                    updatedAt: sqlite3_column_int64(personStmt, 5)))
            }

            // ── faceEmbeddings（BLOB → Base64 NO_WRAP，对齐 Android）──
            let embStmt = try backupPrepare(db, """
                SELECT ma.uri, fe.person_id, fe.embedding, fe.created_at FROM face_embeddings fe
                JOIN media_assets ma ON ma.id = fe.media_id;
                """)
            defer { sqlite3_finalize(embStmt) }
            while sqlite3_step(embStmt) == SQLITE_ROW {
                let blobSize = Int(sqlite3_column_bytes(embStmt, 2))
                let blob = blobSize > 0
                    ? Data(bytes: sqlite3_column_blob(embStmt, 2), count: blobSize)
                    : Data()
                out.faceEmbeddings.append(BackupFaceEmbedding(
                    mediaUri: backupColText(embStmt, 0) ?? "",
                    personId: backupColInt64OrNil(embStmt, 1),
                    embedding: blob.base64EncodedString(),
                    createdAt: sqlite3_column_int64(embStmt, 3)))
            }

            // ── ocrWords + occurrences（occurrence 经 word/媒体 join 物化键）──
            let wordStmt = try backupPrepare(db, "SELECT wordId, word, normalizedWord FROM ocr_words;")
            defer { sqlite3_finalize(wordStmt) }
            while sqlite3_step(wordStmt) == SQLITE_ROW {
                out.ocrWords.append(BackupOcrWord(
                    wordId: sqlite3_column_int64(wordStmt, 0),
                    word: backupColText(wordStmt, 1) ?? "",
                    normalizedWord: backupColText(wordStmt, 2) ?? ""))
            }
            let occStmt = try backupPrepare(db, """
                SELECT w.normalizedWord, ma.uri, o.confidence, o.boundingBox FROM ocr_word_occurrences o
                JOIN ocr_words w ON w.wordId = o.wordId
                JOIN media_assets ma ON ma.id = o.mediaId;
                """)
            defer { sqlite3_finalize(occStmt) }
            while sqlite3_step(occStmt) == SQLITE_ROW {
                out.ocrWordOccurrences.append(BackupOcrWordOccurrence(
                    normalizedWord: backupColText(occStmt, 0) ?? "",
                    mediaUri: backupColText(occStmt, 1) ?? "",
                    confidence: backupColFloatOrNil(occStmt, 2),
                    boundingBox: backupColText(occStmt, 3)))
            }

            // ── locationHierarchy + mediaLocations（lat/lon 取层级行坐标，对齐 Android 导出查询）──
            let locStmt = try backupPrepare(db, """
                SELECT locationId, country, province, city, district, poi, latitude, longitude FROM location_hierarchy;
                """)
            defer { sqlite3_finalize(locStmt) }
            while sqlite3_step(locStmt) == SQLITE_ROW {
                out.locationHierarchy.append(BackupLocationHierarchy(
                    locationId: sqlite3_column_int64(locStmt, 0),
                    country: backupColText(locStmt, 1),
                    province: backupColText(locStmt, 2),
                    city: backupColText(locStmt, 3),
                    district: backupColText(locStmt, 4),
                    poi: backupColText(locStmt, 5),
                    latitude: backupColDoubleOrNil(locStmt, 6),
                    longitude: backupColDoubleOrNil(locStmt, 7)))
            }
            let mediaLocStmt = try backupPrepare(db, """
                SELECT ma.uri, lh.latitude, lh.longitude, ml.accuracy FROM media_locations ml
                JOIN location_hierarchy lh ON lh.locationId = ml.locationId
                JOIN media_assets ma ON ma.id = ml.mediaId;
                """)
            defer { sqlite3_finalize(mediaLocStmt) }
            while sqlite3_step(mediaLocStmt) == SQLITE_ROW {
                out.mediaLocations.append(BackupMediaLocation(
                    mediaUri: backupColText(mediaLocStmt, 0) ?? "",
                    latitude: backupColDoubleOrNil(mediaLocStmt, 1),
                    longitude: backupColDoubleOrNil(mediaLocStmt, 2),
                    accuracy: backupColFloatOrNil(mediaLocStmt, 3)))
            }

            // ── mediaFeedback（media_id 存 media id 字符串 → join 回 uri 作跨安装键）──
            let fbStmt = try backupPrepare(db, """
                SELECT ma.uri, mf.feedback_type, mf.query_text, mf.session_id, mf.created_at FROM media_feedback mf
                JOIN media_assets ma ON ma.id = CAST(mf.media_id AS INTEGER);
                """)
            defer { sqlite3_finalize(fbStmt) }
            while sqlite3_step(fbStmt) == SQLITE_ROW {
                out.mediaFeedback.append(BackupMediaFeedback(
                    mediaUri: backupColText(fbStmt, 0) ?? "",
                    feedbackType: backupColText(fbStmt, 1) ?? "",
                    queryText: backupColText(fbStmt, 2) ?? "",
                    sessionId: backupColText(fbStmt, 3) ?? "",
                    createdAt: sqlite3_column_int64(fbStmt, 4)))
            }

            // ── personRelations（iOS 表当前只有 self 边；无时间戳列 → 以导出时刻填充）──
            let relStmt = try backupPrepare(db, """
                SELECT subjectPersonId, objectPersonId, predicate, source, customLabel, confidence FROM person_relations;
                """)
            defer { sqlite3_finalize(relStmt) }
            while sqlite3_step(relStmt) == SQLITE_ROW {
                out.personRelations.append(BackupPersonRelation(
                    subjectPersonId: sqlite3_column_int64(relStmt, 0),
                    objectPersonId: sqlite3_column_int64(relStmt, 1),
                    predicate: backupColText(relStmt, 2) ?? "",
                    source: backupColText(relStmt, 3) ?? "",
                    customLabel: backupColText(relStmt, 4),
                    confidence: backupColFloatOrNil(relStmt, 5) ?? 1.0,
                    createdAt: exportedAt,
                    updatedAt: exportedAt))
            }

            return out
        }
    }

    // MARK: - 恢复（单 BEGIN IMMEDIATE 事务，任一段失败整体回滚）

    /// 恢复 SQLite 段。聊天段（文件存储）不在此层；返回计数中聊天两项为 0，由调用方并入。
    func backupRestoreSqliteSegments(_ backup: TagDataBackupContainer) throws -> BackupRestoreSummary {
        try queue.sync {
            guard let db = db else { throw BackupRestoreError.sqlFailed("database not open") }

            if sqlite3_exec(db, "BEGIN IMMEDIATE;", nil, nil, nil) != SQLITE_OK {
                throw BackupRestoreError.sqlFailed("BEGIN IMMEDIATE: \(String(cString: sqlite3_errmsg(db)))")
            }
            do {
                let summary = try backupRestoreInsideTransaction(db, backup)
                if sqlite3_exec(db, "COMMIT;", nil, nil, nil) != SQLITE_OK {
                    throw BackupRestoreError.sqlFailed("COMMIT: \(String(cString: sqlite3_errmsg(db)))")
                }
                return summary
            } catch {
                sqlite3_exec(db, "ROLLBACK;", nil, nil, nil)
                throw error
            }
        }
    }

    /// 事务体：2~10 步恢复语义照搬 Android TagDataBackupRepository（L421-808）。
    private func backupRestoreInsideTransaction(_ db: OpaquePointer, _ backup: TagDataBackupContainer) throws -> BackupRestoreSummary {
        var summary = BackupRestoreSummary()

        // 1. uri → 本机 mediaId 映射 + matched/unmatched 汇总（七段 uri 并集）
        var uriToMediaId: [String: Int64] = [:]
        let mediaStmt = try backupPrepare(db, "SELECT id, uri FROM media_assets;")
        defer { sqlite3_finalize(mediaStmt) }
        while sqlite3_step(mediaStmt) == SQLITE_ROW {
            if let uri = backupColText(mediaStmt, 1) {
                uriToMediaId[uri] = sqlite3_column_int64(mediaStmt, 0)
            }
        }
        var allBackupUris = Set<String>()
        backup.mediaTagMetadata.forEach { allBackupUris.insert($0.uri) }
        backup.crossRefs.forEach { allBackupUris.insert($0.uri) }
        backup.scanTasks.forEach { allBackupUris.insert($0.uri) }
        backup.faceEmbeddings.forEach { allBackupUris.insert($0.mediaUri) }
        backup.ocrWordOccurrences.forEach { allBackupUris.insert($0.mediaUri) }
        backup.mediaLocations.forEach { allBackupUris.insert($0.mediaUri) }
        backup.mediaFeedback.forEach { allBackupUris.insert($0.mediaUri) }
        summary.matchedMediaCount = allBackupUris.filter { uriToMediaId[$0] != nil }.count
        summary.unmatchedUriCount = allBackupUris.count - summary.matchedMediaCount

        // 2. 重建标签（按 name 去重，已存在同名跳过）+ name → tagId 映射
        var nameToTagId: [String: Int64] = [:]
        let existingTagsStmt = try backupPrepare(db, "SELECT tagId, name FROM tags;")
        defer { sqlite3_finalize(existingTagsStmt) }
        while sqlite3_step(existingTagsStmt) == SQLITE_ROW {
            if let name = backupColText(existingTagsStmt, 1) {
                nameToTagId[name] = sqlite3_column_int64(existingTagsStmt, 0)
            }
        }
        if !backup.tags.isEmpty {
            let insertTagStmt = try backupPrepare(db, "INSERT INTO tags (name, category) VALUES (?, ?);")
            defer { sqlite3_finalize(insertTagStmt) }
            for tag in backup.tags {
                guard nameToTagId[tag.name] == nil else { continue }
                backupBindText(insertTagStmt, 1, tag.name)
                backupBindText(insertTagStmt, 2, tag.category)
                guard sqlite3_step(insertTagStmt) == SQLITE_DONE else {
                    throw BackupRestoreError.sqlFailed("insert tag \(tag.name): \(String(cString: sqlite3_errmsg(db)))")
                }
                nameToTagId[tag.name] = sqlite3_last_insert_rowid(db)
                summary.restoredTagCount += 1
                sqlite3_reset(insertTagStmt)
                sqlite3_clear_bindings(insertTagStmt)
            }
        }

        // 3. 恢复媒体-标签关联（uri/tagName 不匹配跳过；复合主键冲突 REPLACE 覆盖）
        let crossStmt = try backupPrepare(db, """
            INSERT OR REPLACE INTO media_tag_cross_ref (mediaId, tagId, confidence) VALUES (?, ?, ?);
            """)
        defer { sqlite3_finalize(crossStmt) }
        for ref in backup.crossRefs {
            guard let mediaId = uriToMediaId[ref.uri], let tagId = nameToTagId[ref.tagName] else { continue }
            sqlite3_bind_int64(crossStmt, 1, mediaId)
            sqlite3_bind_int64(crossStmt, 2, tagId)
            backupBindFloat(crossStmt, 3, ref.confidence)
            guard sqlite3_step(crossStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert crossRef: \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredCrossRefCount += 1
            sqlite3_reset(crossStmt)
            sqlite3_clear_bindings(crossStmt)
        }

        // 4. 恢复扫描任务（状态重置 PENDING / attemptCount=0 / 三时刻与错误清空，防 RUNNING 悬挂）
        let scanStmt = try backupPrepare(db, """
            INSERT INTO tag_scan_tasks (sessionId, mediaId, pass, tagCategories, status, priority,
                                        attemptCount, createdAt, scheduledAt, startedAt, completedAt, errorMessage)
            VALUES (?, ?, ?, ?, 'PENDING', ?, 0, ?, NULL, NULL, NULL, NULL);
            """)
        defer { sqlite3_finalize(scanStmt) }
        for task in backup.scanTasks {
            guard let mediaId = uriToMediaId[task.uri] else { continue }
            backupBindText(scanStmt, 1, task.sessionId)
            sqlite3_bind_int64(scanStmt, 2, mediaId)
            backupBindText(scanStmt, 3, Self.backupNormalizePass(task.pass))
            backupBindText(scanStmt, 4, task.tagCategories)
            sqlite3_bind_int(scanStmt, 5, Int32(task.priority))
            sqlite3_bind_int64(scanStmt, 6, task.createdAt)
            guard sqlite3_step(scanStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert scanTask: \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredScanTaskCount += 1
            sqlite3_reset(scanStmt)
            sqlite3_clear_bindings(scanStmt)
        }

        // 5. 恢复 TAG 元数据（uri 匹配后 UPDATE；blurScore/exposureScore/lastViewedAt COALESCE——
        //    备份值为 null 不冲掉本机已算值，对齐 Android MediaDao.updateTagMetadataFromBackup）
        let metaStmt = try backupPrepare(db, """
            UPDATE media_assets SET
                labels = ?, labelsEn = ?, labelsZh = ?, mlKitLabels = ?, mlKitLabelsZh = ?, ocrText = ?,
                latitude = ?, longitude = ?, locationName = ?, faceRoiResult = ?, semanticEmbedding = ?,
                lastTagScanAt = ?, lastTagScanPasses = ?, hasFace = ?, faceId = ?, city = ?,
                faceFocusY = ?, aestheticScore = ?, faceQualityScore = ?,
                blurScore = COALESCE(?, blurScore),
                exposureScore = COALESCE(?, exposureScore),
                lastViewedAt = COALESCE(?, lastViewedAt)
            WHERE id = ?;
            """)
        defer { sqlite3_finalize(metaStmt) }
        for meta in backup.mediaTagMetadata {
            guard let mediaId = uriToMediaId[meta.uri] else { continue }
            backupBindText(metaStmt, 1, meta.labels)
            backupBindText(metaStmt, 2, meta.labelsEn)
            backupBindText(metaStmt, 3, meta.labelsZh)
            backupBindText(metaStmt, 4, meta.mlKitLabels)
            backupBindText(metaStmt, 5, meta.mlKitLabelsZh)
            backupBindText(metaStmt, 6, meta.ocrText)
            backupBindDouble(metaStmt, 7, meta.latitude)
            backupBindDouble(metaStmt, 8, meta.longitude)
            backupBindText(metaStmt, 9, meta.locationName)
            backupBindText(metaStmt, 10, meta.faceRoiResult)
            backupBindText(metaStmt, 11, meta.semanticEmbedding)
            backupBindInt64(metaStmt, 12, meta.lastTagScanAt)
            backupBindText(metaStmt, 13, meta.lastTagScanPasses)
            sqlite3_bind_int(metaStmt, 14, meta.hasFace ? 1 : 0)
            backupBindText(metaStmt, 15, meta.faceId)
            backupBindText(metaStmt, 16, meta.city)
            backupBindFloat(metaStmt, 17, meta.faceFocusY)
            backupBindFloat(metaStmt, 18, meta.aestheticScore)
            backupBindFloat(metaStmt, 19, meta.faceQualityScore)
            backupBindFloat(metaStmt, 20, meta.blurScore)
            backupBindFloat(metaStmt, 21, meta.exposureScore)
            backupBindInt64(metaStmt, 22, meta.lastViewedAt)
            sqlite3_bind_int64(metaStmt, 23, mediaId)
            guard sqlite3_step(metaStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("update metadata \(meta.uri): \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredMetadataCount += 1
            sqlite3_reset(metaStmt)
            sqlite3_clear_bindings(metaStmt)
        }

        // 6. 恢复人物（插入新行得新 id，建 old→new 映射；is_self 不随备份走，恢复一律 0）
        let personStmt = try backupPrepare(db, """
            INSERT INTO persons (name, cover_media_id, face_count, is_self, created_at, updated_at)
            VALUES (?, ?, ?, 0, ?, ?);
            """)
        defer { sqlite3_finalize(personStmt) }
        var oldToNewPersonId: [Int64: Int64] = [:]
        for person in backup.persons {
            backupBindText(personStmt, 1, person.name)
            backupBindInt64(personStmt, 2, person.coverMediaUri.flatMap { uriToMediaId[$0] })
            sqlite3_bind_int(personStmt, 3, Int32(person.faceCount))
            sqlite3_bind_int64(personStmt, 4, person.createdAt)
            sqlite3_bind_int64(personStmt, 5, person.updatedAt)
            guard sqlite3_step(personStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert person: \(String(cString: sqlite3_errmsg(db)))")
            }
            oldToNewPersonId[person.personId] = sqlite3_last_insert_rowid(db)
            summary.restoredPersonCount += 1
            sqlite3_reset(personStmt)
            sqlite3_clear_bindings(personStmt)
        }

        // 6.5 恢复人物关系（两端 old→new 映射成功才接受；UNIQUE 冲突 REPLACE，语义子集）
        let relStmt = try backupPrepare(db, """
            INSERT OR REPLACE INTO person_relations (subjectPersonId, objectPersonId, predicate, source, customLabel, confidence)
            VALUES (?, ?, ?, ?, ?, ?);
            """)
        defer { sqlite3_finalize(relStmt) }
        for rel in backup.personRelations {
            guard let newSubject = oldToNewPersonId[rel.subjectPersonId],
                  let newObject = oldToNewPersonId[rel.objectPersonId] else { continue }
            sqlite3_bind_int64(relStmt, 1, newSubject)
            sqlite3_bind_int64(relStmt, 2, newObject)
            backupBindText(relStmt, 3, rel.predicate)
            backupBindText(relStmt, 4, rel.source)
            backupBindText(relStmt, 5, rel.customLabel)
            backupBindFloat(relStmt, 6, rel.confidence)
            guard sqlite3_step(relStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert personRelation: \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredPersonRelationCount += 1
            sqlite3_reset(relStmt)
            sqlite3_clear_bindings(relStmt)
        }

        // 7. 恢复人脸 Embedding（Base64→BLOB；personId 经映射重写；uri 不匹配跳过）
        let embStmt = try backupPrepare(db, """
            INSERT INTO face_embeddings (media_id, person_id, embedding, created_at) VALUES (?, ?, ?, ?);
            """)
        defer { sqlite3_finalize(embStmt) }
        for emb in backup.faceEmbeddings {
            guard let mediaId = uriToMediaId[emb.mediaUri] else { continue }
            guard let blob = Data(base64Encoded: emb.embedding) else {
                throw BackupRestoreError.embeddingDecodeFailed(emb.mediaUri)
            }
            sqlite3_bind_int64(embStmt, 1, mediaId)
            backupBindInt64(embStmt, 2, emb.personId.flatMap { oldToNewPersonId[$0] })
            _ = blob.withUnsafeBytes { rawBuf -> Int32 in
                sqlite3_bind_blob(embStmt, 3, rawBuf.baseAddress, Int32(blob.count), SQLITE_TRANSIENT)
                return SQLITE_OK
            }
            sqlite3_bind_int64(embStmt, 4, emb.createdAt)
            guard sqlite3_step(embStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert faceEmbedding: \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredFaceEmbeddingCount += 1
            sqlite3_reset(embStmt)
            sqlite3_clear_bindings(embStmt)
        }

        // 8. 恢复 OCR 倒排索引（词条按 normalizedWord get-or-insert，occurrence 经映射重定位）
        var normalizedToWordId: [String: Int64] = [:]
        let lookupWordStmt = try backupPrepare(db, "SELECT wordId FROM ocr_words WHERE normalizedWord = ? LIMIT 1;")
        defer { sqlite3_finalize(lookupWordStmt) }
        let insertWordStmt = try backupPrepare(db, "INSERT INTO ocr_words (word, normalizedWord) VALUES (?, ?);")
        defer { sqlite3_finalize(insertWordStmt) }
        for word in backup.ocrWords {
            backupBindText(lookupWordStmt, 1, word.normalizedWord)
            var wordId: Int64? = nil
            var existed = false
            if sqlite3_step(lookupWordStmt) == SQLITE_ROW {
                wordId = sqlite3_column_int64(lookupWordStmt, 0)
                existed = true
            }
            sqlite3_reset(lookupWordStmt)
            sqlite3_clear_bindings(lookupWordStmt)
            if wordId == nil {
                backupBindText(insertWordStmt, 1, word.word)
                backupBindText(insertWordStmt, 2, word.normalizedWord)
                guard sqlite3_step(insertWordStmt) == SQLITE_DONE else {
                    throw BackupRestoreError.sqlFailed("insert ocrWord: \(String(cString: sqlite3_errmsg(db)))")
                }
                wordId = sqlite3_last_insert_rowid(db)
                sqlite3_reset(insertWordStmt)
                sqlite3_clear_bindings(insertWordStmt)
            }
            if let wordId {
                normalizedToWordId[word.normalizedWord] = wordId
                if !existed { summary.restoredOcrWordCount += 1 }
            }
        }
        let occStmt = try backupPrepare(db, """
            INSERT OR REPLACE INTO ocr_word_occurrences (wordId, mediaId, confidence, boundingBox) VALUES (?, ?, ?, ?);
            """)
        defer { sqlite3_finalize(occStmt) }
        for occ in backup.ocrWordOccurrences {
            guard let mediaId = uriToMediaId[occ.mediaUri], let wordId = normalizedToWordId[occ.normalizedWord] else { continue }
            sqlite3_bind_int64(occStmt, 1, wordId)
            sqlite3_bind_int64(occStmt, 2, mediaId)
            backupBindFloat(occStmt, 3, occ.confidence)
            backupBindText(occStmt, 4, occ.boundingBox)
            guard sqlite3_step(occStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert ocrOccurrence: \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredOcrWordOccurrenceCount += 1
            sqlite3_reset(occStmt)
            sqlite3_clear_bindings(occStmt)
        }

        // 9. 恢复地理位置（坐标 "%.4f,%.4f" 为键 get-or-insert；mediaLocations 经映射重定位）
        var coordinateToLocationId: [String: Int64] = [:]
        let lookupLocStmt = try backupPrepare(db, "SELECT locationId FROM location_hierarchy WHERE latitude = ? AND longitude = ? LIMIT 1;")
        defer { sqlite3_finalize(lookupLocStmt) }
        let insertLocStmt = try backupPrepare(db, """
            INSERT INTO location_hierarchy (country, province, city, district, poi, latitude, longitude)
            VALUES (?, ?, ?, ?, ?, ?, ?);
            """)
        defer { sqlite3_finalize(insertLocStmt) }
        for location in backup.locationHierarchy {
            guard let key = Self.backupCoordinateKey(location.latitude, location.longitude) else { continue }
            backupBindDouble(lookupLocStmt, 1, location.latitude)
            backupBindDouble(lookupLocStmt, 2, location.longitude)
            var locationId: Int64? = nil
            var existed = false
            if sqlite3_step(lookupLocStmt) == SQLITE_ROW {
                locationId = sqlite3_column_int64(lookupLocStmt, 0)
                existed = true
            }
            sqlite3_reset(lookupLocStmt)
            sqlite3_clear_bindings(lookupLocStmt)
            if locationId == nil {
                backupBindText(insertLocStmt, 1, location.country)
                backupBindText(insertLocStmt, 2, location.province)
                backupBindText(insertLocStmt, 3, location.city)
                backupBindText(insertLocStmt, 4, location.district)
                backupBindText(insertLocStmt, 5, location.poi)
                backupBindDouble(insertLocStmt, 6, location.latitude)
                backupBindDouble(insertLocStmt, 7, location.longitude)
                guard sqlite3_step(insertLocStmt) == SQLITE_DONE else {
                    throw BackupRestoreError.sqlFailed("insert location: \(String(cString: sqlite3_errmsg(db)))")
                }
                locationId = sqlite3_last_insert_rowid(db)
                sqlite3_reset(insertLocStmt)
                sqlite3_clear_bindings(insertLocStmt)
            }
            if let locationId {
                coordinateToLocationId[key] = locationId
                if !existed { summary.restoredLocationCount += 1 }
            }
        }
        let mediaLocStmt = try backupPrepare(db, """
            INSERT OR REPLACE INTO media_locations (mediaId, locationId, accuracy) VALUES (?, ?, ?);
            """)
        defer { sqlite3_finalize(mediaLocStmt) }
        for ml in backup.mediaLocations {
            guard let mediaId = uriToMediaId[ml.mediaUri],
                  let key = Self.backupCoordinateKey(ml.latitude, ml.longitude),
                  let locationId = coordinateToLocationId[key] else { continue }
            sqlite3_bind_int64(mediaLocStmt, 1, mediaId)
            sqlite3_bind_int64(mediaLocStmt, 2, locationId)
            backupBindFloat(mediaLocStmt, 3, ml.accuracy)
            guard sqlite3_step(mediaLocStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert mediaLocation: \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredMediaLocationCount += 1
            sqlite3_reset(mediaLocStmt)
            sqlite3_clear_bindings(mediaLocStmt)
        }

        // 10. 恢复媒体反馈（uri → 本机 mediaId，media_id 列存 id 字符串形态）
        let fbStmt = try backupPrepare(db, """
            INSERT INTO media_feedback (media_id, feedback_type, query_text, session_id, created_at)
            VALUES (?, ?, ?, ?, ?);
            """)
        defer { sqlite3_finalize(fbStmt) }
        for fb in backup.mediaFeedback {
            guard let mediaId = uriToMediaId[fb.mediaUri] else { continue }
            backupBindText(fbStmt, 1, String(mediaId))
            backupBindText(fbStmt, 2, fb.feedbackType)
            backupBindText(fbStmt, 3, fb.queryText)
            backupBindText(fbStmt, 4, fb.sessionId)
            sqlite3_bind_int64(fbStmt, 5, fb.createdAt)
            guard sqlite3_step(fbStmt) == SQLITE_DONE else {
                throw BackupRestoreError.sqlFailed("insert mediaFeedback: \(String(cString: sqlite3_errmsg(db)))")
            }
            summary.restoredMediaFeedbackCount += 1
            sqlite3_reset(fbStmt)
            sqlite3_clear_bindings(fbStmt)
        }

        return summary
    }

    // MARK: - 导出/恢复共用工具

    /// prepare 包装：失败抛错（备份链路错误必须呈现给用户，不走 assertionFailure 静默）。
    private func backupPrepare(_ db: OpaquePointer, _ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let prepared = stmt else {
            throw BackupRestoreError.sqlFailed("prepare: \(String(cString: sqlite3_errmsg(db))) \(String(sql.prefix(60)))")
        }
        return prepared
    }

    /// TAG 元数据是否全空（对齐 Android MediaEntity.isTagRelatedEmpty——注意不含 labelsEn/labelsZh/经纬度）。
    private static func backupIsTagRelatedEmpty(_ m: BackupMediaTagMetadata) -> Bool {
        func isBlank(_ s: String?) -> Bool { (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return isBlank(m.labels) && isBlank(m.mlKitLabels) && isBlank(m.mlKitLabelsZh)
            && isBlank(m.ocrText) && isBlank(m.locationName) && isBlank(m.faceRoiResult)
            && isBlank(m.semanticEmbedding) && m.lastTagScanAt == nil && isBlank(m.lastTagScanPasses)
            && !m.hasFace && isBlank(m.faceId) && isBlank(m.city)
            && m.faceFocusY == nil && m.aestheticScore == nil && m.faceQualityScore == nil
            && m.blurScore == nil && m.exposureScore == nil && m.lastViewedAt == nil
    }

    /// pass 归一（对齐 Android parsePass：QWEN_TAGGING 兼容重命名前旧值；未知值回退 IMAGE_TAGGING）。
    private static func backupNormalizePass(_ name: String) -> String {
        if name == "QWEN_TAGGING" { return "IMAGE_TAGGING" }
        let knownPasses: Set<String> = ["FACE_DETECTION", "DBSCAN", "IMAGE_TAGGING", "MOBILE_CLIP_ENCODING"]
        return knownPasses.contains(name) ? name : "IMAGE_TAGGING"
    }

    /// 坐标键 "%.4f,%.4f"（对齐 Android coordinateKey；任一坐标为 nil 返回 nil = 无位置）。
    private static func backupCoordinateKey(_ latitude: Double?, _ longitude: Double?) -> String? {
        guard let latitude, let longitude else { return nil }
        return String(format: "%.4f,%.4f", latitude, longitude)
    }
}
