import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// MARK: - pending_deletes 存储读写（预览上滑删除会话批量标记，gallery-grid.yaml §16b ios_note）
// 全部经既有串行 queue；记录持久化——进程被杀不丢，恢复后由 PendingDeleteExecutor 任务化执行。

extension TagDatabase {

    /// 落一条待删标记（重复标记同 uri 幂等覆盖，刷新标记时间）。
    func addPendingDelete(uri: String, source: String,
                          markedAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, """
                INSERT OR REPLACE INTO pending_deletes (uri, marked_at, source) VALUES (?, ?, ?);
                """, -1, &stmt, nil) == SQLITE_OK else {
                assertionFailure("[TagDatabase] addPendingDelete prepare failed")
                return
            }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, uri, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 2, markedAtMs)
            sqlite3_bind_text(stmt, 3, source, -1, SQLITE_TRANSIENT)
            if sqlite3_step(stmt) != SQLITE_DONE {
                assertionFailure("[TagDatabase] addPendingDelete step failed")
            }
        }
    }

    /// 全部待删 uri（按标记时间升序 = 标记顺序）。
    func pendingDeleteUris() -> [String] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db,
                "SELECT uri FROM pending_deletes ORDER BY marked_at ASC;",
                -1, &stmt, nil) == SQLITE_OK else {
                assertionFailure("[TagDatabase] pendingDeleteUris prepare failed")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            var uris: [String] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let cStr = sqlite3_column_text(stmt, 0) {
                    uris.append(String(cString: cStr))
                }
            }
            return uris
        }
    }

    func pendingDeleteCount() -> Int {
        queue.sync {
            guard let db = db else { return 0 }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM pending_deletes;",
                                     -1, &stmt, nil) == SQLITE_OK else {
                assertionFailure("[TagDatabase] pendingDeleteCount prepare failed")
                return 0
            }
            defer { sqlite3_finalize(stmt) }
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
        }
    }

    /// 移除已结清记录（执行成功 / 目标已不在库中按已移除计的子集）。
    func removePendingDeletes(_ uris: [String]) {
        guard !uris.isEmpty else { return }
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "DELETE FROM pending_deletes WHERE uri = ?;",
                                     -1, &stmt, nil) == SQLITE_OK else {
                assertionFailure("[TagDatabase] removePendingDeletes prepare failed")
                return
            }
            defer { sqlite3_finalize(stmt) }
            for uri in uris {
                sqlite3_reset(stmt)
                sqlite3_bind_text(stmt, 1, uri, -1, SQLITE_TRANSIENT)
                if sqlite3_step(stmt) != SQLITE_DONE {
                    assertionFailure("[TagDatabase] removePendingDeletes step failed")
                }
            }
        }
    }

    /// 清空全部待删记录（DEBUG 测试钩子专用，-clearPendingDeletes）。
    func clearPendingDeletes() {
        queue.sync {
            exec("DELETE FROM pending_deletes;")
        }
    }
}
