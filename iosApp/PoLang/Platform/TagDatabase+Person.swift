import Foundation
import SQLite3

/// SQLITE_TRANSIENT：让 sqlite 在 bind 时立即拷贝文本/blob（Swift String 跨 bind→step 不保证指针有效）。
/// （与 TagDatabase+Scan.swift 同定义；private 为文件级，各扩展文件需各自声明。）
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// MARK: - TagDatabase · 人物域查询（对标 Android PersonDao/PersonRepository）
//
// 人物页（列表+详情）数据访问层。聚类模型：persons 由 Pass2 聚类产出；
// photoCount = cluster distinct media（命名人物 ∪ label-mention）；关系存 person_relations。
// 所有方法经 `queue.sync` 串行化，等价 Android Room suspend-DAO + Dispatchers.IO。

// MARK: - 行模型

struct PersonDbRow: Equatable {
    let personId: Int64
    let name: String?
    let coverMediaId: Int64?
    let faceCount: Int
    let isSelf: Bool
    let createdAt: Int64
    let updatedAt: Int64
}

/// 人物对己关系（subject = 该人物，object = is_self 人物）。
struct PersonRelationDb: Equatable {
    let relationId: Int64
    let predicate: String       // RelationPredicate.name
    let customLabel: String?
}

/// 媒体封面信息（localIdentifier = media_assets.uri；faceFocusY 供人脸感知裁切）。
struct MediaCoverInfo: Equatable {
    let mediaId: Int64
    let localIdentifier: String
    let faceFocusY: Float?
}

// MARK: - 列读取工具（可选类型）

@inline(__always)
private func dbColText(_ stmt: OpaquePointer?, _ idx: Int32) -> String? {
    if sqlite3_column_type(stmt, idx) == SQLITE_NULL { return nil }
    guard let cs = sqlite3_column_text(stmt, idx) else { return nil }
    return String(cString: cs)
}

@inline(__always)
private func dbColInt64OrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Int64? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, idx)
}

@inline(__always)
private func dbColFloatOrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Float? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : Float(sqlite3_column_double(stmt, idx))
}

extension TagDatabase {

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: - persons 读取

    /// 全部人物行（原样，未过滤/排序）。
    func allPersonRows() -> [PersonDbRow] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT person_id, name, cover_media_id, face_count, is_self, created_at, updated_at FROM persons;",
                -1, &stmt, nil)
            defer { sqlite3_finalize(stmt) }
            var rows: [PersonDbRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(PersonDbRow(
                    personId: sqlite3_column_int64(stmt, 0),
                    name: dbColText(stmt, 1),
                    coverMediaId: dbColInt64OrNil(stmt, 2),
                    faceCount: Int(sqlite3_column_int(stmt, 3)),
                    isSelf: sqlite3_column_int(stmt, 4) == 1,
                    createdAt: sqlite3_column_int64(stmt, 5),
                    updatedAt: sqlite3_column_int64(stmt, 6)))
            }
            return rows
        }
    }

    /// 单人物行。
    func personRow(_ personId: Int64) -> PersonDbRow? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT person_id, name, cover_media_id, face_count, is_self, created_at, updated_at FROM persons WHERE person_id=?;",
                -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return PersonDbRow(
                personId: sqlite3_column_int64(stmt, 0),
                name: dbColText(stmt, 1),
                coverMediaId: dbColInt64OrNil(stmt, 2),
                faceCount: Int(sqlite3_column_int(stmt, 3)),
                isSelf: sqlite3_column_int(stmt, 4) == 1,
                createdAt: sqlite3_column_int64(stmt, 5),
                updatedAt: sqlite3_column_int64(stmt, 6))
        }
    }

    /// is_self 人物 id（全局唯一"我"）。
    func selfPersonId() -> Int64? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT person_id FROM persons WHERE is_self=1 LIMIT 1;", -1, &stmt, nil)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return sqlite3_column_int64(stmt, 0)
        }
    }

    // MARK: - reconcile（单事务修复，对标 Android reconcilePersons）

    /// 修复孤儿/悬空：删孤儿 embedding、删无 embedding 的 person、重算 face_count、
    /// 修悬空 cover_media_id（指向失效媒体→置空→回填簇内存活 media）。幂等。
    func reconcilePersons() {
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }

            // 1. 删孤儿 embedding（media 已删）
            exec("DELETE FROM face_embeddings WHERE media_id NOT IN (SELECT id FROM media_assets);")
            // 2. 重算 face_count
            exec("UPDATE persons SET face_count = COALESCE((SELECT COUNT(*) FROM face_embeddings fe WHERE fe.person_id = persons.person_id), 0);")
            // 3. 删无 embedding 的 person（孤儿簇；FK 级联清 person_relations）
            exec("DELETE FROM persons WHERE face_count = 0;")
            // 4. 悬空 cover（指向失效媒体）置空
            exec("UPDATE persons SET cover_media_id = NULL WHERE cover_media_id IS NOT NULL AND cover_media_id NOT IN (SELECT id FROM media_assets);")
        }
        // 5. 回填空 cover：每人物取簇内首张存活 media（需逐行，事务外做）
        let needCover = queue.sync { () -> [(personId: Int64, firstMedia: Int64?)] in
            guard let db = db else { return [] }
            var rows: [(Int64, Int64?)] = []
            var s1: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT person_id FROM persons WHERE cover_media_id IS NULL;", -1, &s1, nil)
            while sqlite3_step(s1) == SQLITE_ROW {
                let pid = sqlite3_column_int64(s1, 0)
                var firstMedia: Int64?
                var s2: OpaquePointer?
                sqlite3_prepare_v2(db, "SELECT media_id FROM face_embeddings WHERE person_id=? AND media_id IN (SELECT id FROM media_assets) LIMIT 1;", -1, &s2, nil)
                sqlite3_bind_int64(s2, 1, pid)
                if sqlite3_step(s2) == SQLITE_ROW { firstMedia = sqlite3_column_int64(s2, 0) }
                sqlite3_finalize(s2)
                rows.append((pid, firstMedia))
            }
            sqlite3_finalize(s1)
            return rows
        }
        guard !needCover.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;"); defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE persons SET cover_media_id=?, updated_at=? WHERE person_id=?;", -1, &stmt, nil)
            let now = Self.nowMs()
            for (pid, firstMedia) in needCover {
                if let mid = firstMedia {
                    sqlite3_bind_int64(stmt, 1, mid)
                } else {
                    sqlite3_bind_null(stmt, 1)
                }
                sqlite3_bind_int64(stmt, 2, now)
                sqlite3_bind_int64(stmt, 3, pid)
                sqlite3_step(stmt); sqlite3_reset(stmt); sqlite3_clear_bindings(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }

    // MARK: - photoCount

    /// 该人物簇的 distinct 照片数（face_embeddings 不同 media_id 计数）。
    func distinctMediaCount(personId: Int64) -> Int {
        queue.sync {
            guard let db = db else { return 0 }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT COUNT(DISTINCT media_id) FROM face_embeddings WHERE person_id=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
            return Int(sqlite3_column_int(stmt, 0))
        }
    }

    /// 命名人物的 cluster ∪ label-mention 并集数（UNION DISTINCT，与 Android getPersonMediaIds 对齐，
    /// 不重复计数交叉项；须与相册详情计数一致）。label-mention：media_assets.labels/labelsEn/labelsZh
    /// 包含 name（子串）。未命名人物直接返回 cluster distinct 计数。
    func photoCountForPerson(personId: Int64, name: String?) -> Int {
        guard let name = name, !name.isEmpty else {
            return distinctMediaCount(personId: personId)
        }
        return queue.sync {
            guard let db = db else { return 0 }
            let like = "%" + name + "%"
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT COUNT(*) FROM ("
                + "SELECT DISTINCT media_id FROM face_embeddings WHERE person_id=? "
                + "UNION "
                + "SELECT id FROM media_assets WHERE labels LIKE ? OR labelsEn LIKE ? OR labelsZh LIKE ?"
                + ");",
                -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            sqlite3_bind_text(stmt, 2, like, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 3, like, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 4, like, -1, SQLITE_TRANSIENT)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
            return Int(sqlite3_column_int(stmt, 0))
        }
    }

    // MARK: - 封面信息

    /// 单媒体封面信息（localIdentifier + faceFocusY）。
    func coverInfo(mediaId: Int64) -> MediaCoverInfo? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id, uri, faceFocusY FROM media_assets WHERE id=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW,
                  let uri = dbColText(stmt, 1) else { return nil }
            return MediaCoverInfo(mediaId: sqlite3_column_int64(stmt, 0),
                                  localIdentifier: uri,
                                  faceFocusY: dbColFloatOrNil(stmt, 2))
        }
    }

    /// 批量封面信息（避免列表 N 次查询）。仅返回命中的 mediaId。
    func coverInfos(for mediaIds: [Int64]) -> [Int64: MediaCoverInfo] {
        let unique = Array(Set(mediaIds))
        guard !unique.isEmpty else { return [:] }
        let placeholders = unique.map { _ in "?" }.joined(separator: ",")
        return queue.sync {
            guard let db = db else { return [:] }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT id, uri, faceFocusY FROM media_assets WHERE id IN (\(placeholders));",
                -1, &stmt, nil)
            for (i, mid) in unique.enumerated() {
                sqlite3_bind_int64(stmt, Int32(i + 1), mid)
            }
            defer { sqlite3_finalize(stmt) }
            var out: [Int64: MediaCoverInfo] = [:]
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = sqlite3_column_int64(stmt, 0)
                if let uri = dbColText(stmt, 1) {
                    out[id] = MediaCoverInfo(mediaId: id, localIdentifier: uri, faceFocusY: dbColFloatOrNil(stmt, 2))
                }
            }
            return out
        }
    }

    /// localIdentifier(=uri) → faceFocusY，供相册网格「人脸感知裁切」。
    /// 仅含已扫描出人脸（faceFocusY 非空）的 media；未扫描/无人脸的不入表（渲染退回居中裁切）。
    /// 对齐 Android：Room 驱动的 MediaAsset.faceFocusY 由 tag 生成回填；iOS PHAsset 不带此数据，
    /// 需从独立 TagDatabase 批量读后注入网格（防「砍头杀」，见 gallery-grid.yaml R3）。
    func faceFocusYByLocalIdentifier() -> [String: Float] {
        queue.sync {
            guard let db = db else { return [:] }
            var out: [String: Float] = [:]
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri, faceFocusY FROM media_assets WHERE faceFocusY IS NOT NULL;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let uri = sqlite3_column_text(stmt, 0), let f = dbColFloatOrNil(stmt, 1) {
                    out[String(cString: uri)] = f
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 该人物簇的封面候选 media（去重，按拍摄时间倒序）。
    /// 对标 Android getMediaByPersonOrderedForCover（单人脸优先的精修 Stage1 暂缓，按时间倒序）。
    func coverCandidates(personId: Int64) -> [MediaCoverInfo] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT ma.id, ma.uri, ma.faceFocusY FROM face_embeddings fe "
                + "JOIN media_assets ma ON ma.id = fe.media_id "
                + "WHERE fe.person_id = ? GROUP BY ma.id ORDER BY ma.captureDate DESC;",
                -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            defer { sqlite3_finalize(stmt) }
            var out: [MediaCoverInfo] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let uri = dbColText(stmt, 1) else { continue }
                out.append(MediaCoverInfo(mediaId: sqlite3_column_int64(stmt, 0),
                                          localIdentifier: uri,
                                          faceFocusY: dbColFloatOrNil(stmt, 2)))
            }
            return out
        }
    }

    // MARK: - persons 写

    /// 改名（trim 由调用方负责；空名传 nil 代表未命名）。
    func renamePerson(personId: Int64, name: String?) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE persons SET name=?, updated_at=? WHERE person_id=?;", -1, &stmt, nil)
            if let n = name { sqlite3_bind_text(stmt, 1, n, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 1) }
            sqlite3_bind_int64(stmt, 2, Self.nowMs())
            sqlite3_bind_int64(stmt, 3, personId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 改封面。
    func updatePersonCover(personId: Int64, mediaId: Int64?) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE persons SET cover_media_id=?, updated_at=? WHERE person_id=?;", -1, &stmt, nil)
            if let mid = mediaId { sqlite3_bind_int64(stmt, 1, mid) } else { sqlite3_bind_null(stmt, 1) }
            sqlite3_bind_int64(stmt, 2, Self.nowMs())
            sqlite3_bind_int64(stmt, 3, personId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 设/取消本人。isSelf=true 时先清除其他人物的 self（全局唯一"我"）。
    func setSelf(personId: Int64, isSelf: Bool) {
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;"); defer { exec("COMMIT;") }
            if isSelf {
                exec("UPDATE persons SET is_self=0;")
                var s1: OpaquePointer?
                sqlite3_prepare_v2(db, "UPDATE persons SET is_self=1, updated_at=? WHERE person_id=?;", -1, &s1, nil)
                sqlite3_bind_int64(s1, 1, Self.nowMs())
                sqlite3_bind_int64(s1, 2, personId)
                sqlite3_step(s1); sqlite3_finalize(s1)
            } else {
                var s2: OpaquePointer?
                sqlite3_prepare_v2(db, "UPDATE persons SET is_self=0, updated_at=? WHERE person_id=?;", -1, &s2, nil)
                sqlite3_bind_int64(s2, 1, Self.nowMs())
                sqlite3_bind_int64(s2, 2, personId)
                sqlite3_step(s2); sqlite3_finalize(s2)
            }
        }
    }

    // MARK: - person_relations

    /// 该人物的对己关系（subject=personId，object=selfId）。无 self 或无关系→nil。
    func relationToSelf(personId: Int64) -> PersonRelationDb? {
        guard let selfId = selfPersonId() else { return nil }
        return queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT relationId, predicate, customLabel FROM person_relations WHERE subjectPersonId=? AND objectPersonId=? LIMIT 1;",
                -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            sqlite3_bind_int64(stmt, 2, selfId)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return PersonRelationDb(relationId: sqlite3_column_int64(stmt, 0),
                                    predicate: dbColText(stmt, 1) ?? "",
                                    customLabel: dbColText(stmt, 2))
        }
    }

    /// 幂等 upsert 对己关系（object 隐式 = selfId；无 self 时拒绝）。source 见 RelationSource。
    @discardableResult
    func upsertRelationToSelf(subjectPersonId: Int64, predicate: String, source: String, customLabel: String?) -> Bool {
        guard let selfId = selfPersonId() else { return false }
        return queue.sync {
            guard let db = db else { return false }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "INSERT INTO person_relations (subjectPersonId, objectPersonId, predicate, source, customLabel, confidence) "
                + "VALUES (?, ?, ?, ?, ?, 1.0) "
                + "ON CONFLICT(subjectPersonId, predicate, objectPersonId) DO UPDATE SET "
                + "source=excluded.source, customLabel=excluded.customLabel, confidence=excluded.confidence;",
                -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, subjectPersonId)
            sqlite3_bind_int64(stmt, 2, selfId)
            sqlite3_bind_text(stmt, 3, predicate, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 4, source, -1, SQLITE_TRANSIENT)
            if let cl = customLabel { sqlite3_bind_text(stmt, 5, cl, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 5) }
            sqlite3_step(stmt); sqlite3_finalize(stmt)
            return true
        }
    }

    /// 删除关系。
    func deleteRelation(relationId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM person_relations WHERE relationId=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, relationId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 清空某人物的对己关系（详情页 reset 按钮用）。
    func clearRelationToSelf(personId: Int64) {
        guard let selfId = selfPersonId() else { return }
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM person_relations WHERE subjectPersonId=? AND objectPersonId=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            sqlite3_bind_int64(stmt, 2, selfId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 清除该人物**全部**关系（subject=personId，不限 object；对标 Android removeAllRelationsOf）。
    /// 详情保存 relation=nil 路径用：当前库内关系均为对己行，语义上与 clearRelationToSelf 等价，
    /// 但契约要求「清除所有」——若未来引入多 object 关系亦正确。
    func deleteAllRelationsOfPerson(personId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM person_relations WHERE subjectPersonId=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }
}
