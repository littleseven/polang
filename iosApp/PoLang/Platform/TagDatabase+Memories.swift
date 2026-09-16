import Foundation
import SQLite3

// MARK: - TagDatabase · 回忆域查询（spec memories.yaml §1 data_chain.ios）
//
// 回忆生成数据源：media_assets 照片投影（type='IMAGE'；uri = localIdentifier）+
// persons 表已命名人物（name 非空白）。回收站排除不经 SQL——iOS 废纸篓状态在 PHAsset
// （PHAsset.fetchAssets 默认排除 trashed，同 organize 批 fetchOrganizeAssetMeta 口径），
// 由 MemoriesViewModel 以「存活 lid 集」过滤 DB 行（全集以 PHAsset 枚举为准）。
extension TagDatabase {

    // MARK: - 行模型

    /// 回忆生成的照片投影（MemoryInput 五字段 + MediaAsset 反查所需基础字段）。
    struct MemoryDbRow {
        let uri: String              // = localIdentifier
        let captureDate: Int64       // epoch ms
        let fileName: String
        let aestheticScore: Double?
        let city: String?
        let faceId: String?          // = persons.person_id 字符串
    }

    /// 已命名人物（name 非空白；含 is_self——生成器侧排除本人）。
    struct MemoryPersonDbRow {
        let personId: String         // String(person_id)，与 media_assets.faceId 同 key 域
        let name: String
        let isSelf: Bool
    }

    // MARK: - 查询

    /// 全部照片行（type='IMAGE'；视频由上游过滤，生成器不判定类型）。
    func memoryPhotoRows() -> [MemoryDbRow] {
        queue.sync {
            guard let db = db else { return [] }
            var out: [MemoryDbRow] = []
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
                SELECT uri, captureDate, fileName, aestheticScore, city, faceId
                FROM media_assets WHERE type = 'IMAGE';
                """, -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare memoryPhotoRows: \(String(cString: sqlite3_errmsg(db)))")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            while sqlite3_step(stmt) == SQLITE_ROW {
                func text(_ i: Int32) -> String? {
                    sqlite3_column_text(stmt, i).map { String(cString: $0) }
                }
                out.append(MemoryDbRow(
                    uri: text(0) ?? "",
                    captureDate: sqlite3_column_int64(stmt, 1),
                    fileName: text(2) ?? "",
                    aestheticScore: sqlite3_column_type(stmt, 3) == SQLITE_NULL
                        ? nil : sqlite3_column_double(stmt, 3),
                    city: text(4),
                    faceId: text(5)))
            }
            return out
        }
    }

    /// 已命名人物（name 非空白；trim 口径对齐生成器 NamedPerson 契约）。
    func memoryNamedPersons() -> [MemoryPersonDbRow] {
        queue.sync {
            guard let db = db else { return [] }
            var out: [MemoryPersonDbRow] = []
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
                SELECT person_id, name, is_self FROM persons
                WHERE name IS NOT NULL AND TRIM(name) != '';
                """, -1, &stmt, nil) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare memoryNamedPersons: \(String(cString: sqlite3_errmsg(db)))")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let name = sqlite3_column_text(stmt, 1).map({ String(cString: $0) }) else { continue }
                out.append(MemoryPersonDbRow(
                    personId: String(sqlite3_column_int64(stmt, 0)),
                    name: name,
                    isSelf: sqlite3_column_int(stmt, 2) == 1))
            }
            return out
        }
    }
}
