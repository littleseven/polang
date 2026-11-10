import Foundation
import SharedKit

// MARK: - 备份导出/导入编排（对标 Android TagDataBackupRepository）
//
// 数据源分两路：
// - SQLite 11 段 + personRelations：TagDatabase+Backup（单事务恢复）；
// - chatSessions/chatMessages：ChatHistoryStore 文件存储（Documents 索引 + 每会话
//   envelope v6），partsJson 直通——文件内 parts 数组 JSON 原样放字段，恢复侧经
//   shared ChatHistoryStoreCodec 重建归一化。
// preferences/memoryFacts/photoEditRecipes：iOS v1 恒空数组 + 导入忽略（C7 决策：
// key 集不同源 / 无对应存储，盲写有冲写风险）。

final class BackupRestoreService {
    static let shared = BackupRestoreService()

    private init() {}

    private static var documentsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    // MARK: - 导出

    /// 构建完整备份容器。
    /// v1 一次性构建整个容器 JSON（Data 载荷交 fileExporter 落盘）；流式导出留优化
    /// （对齐 Android okio 流式写盘的防 OOM 语义）。
    /// - Returns: data = 容器 JSON；filename = `picme_tag_backup_yyyyMMdd_HHmmss.json`。
    func exportBackup() throws -> (data: Data, filename: String, summary: BackupExportSummary) {
        let exportedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let sqlite = try TagDatabase.shared.backupExportSegments(exportedAt: exportedAt)
        let chat = exportChatSegments()

        var container = TagDataBackupContainer()
        container.version = TagDataBackupContainer.currentVersion
        container.exportedAt = exportedAt
        container.tags = sqlite.tags
        container.mediaTagMetadata = sqlite.mediaTagMetadata
        container.crossRefs = sqlite.crossRefs
        container.scanTasks = sqlite.scanTasks
        container.persons = sqlite.persons
        container.faceEmbeddings = sqlite.faceEmbeddings
        container.ocrWords = sqlite.ocrWords
        container.ocrWordOccurrences = sqlite.ocrWordOccurrences
        container.locationHierarchy = sqlite.locationHierarchy
        container.mediaLocations = sqlite.mediaLocations
        container.mediaFeedback = sqlite.mediaFeedback
        container.preferences = BackupPreferences()
        container.chatSessions = chat.sessions
        container.chatMessages = chat.messages
        container.personRelations = sqlite.personRelations
        container.memoryFacts = []
        container.photoEditRecipes = []

        let data = try JSONEncoder().encode(container)

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        let filename = "picme_tag_backup_\(formatter.string(from: Date())).json"

        var summary = BackupExportSummary()
        summary.tagCount = sqlite.tags.count
        summary.mediaCount = sqlite.mediaTagMetadata.count
        summary.crossRefCount = sqlite.crossRefs.count
        summary.scanTaskCount = sqlite.scanTasks.count
        summary.personCount = sqlite.persons.count
        summary.faceEmbeddingCount = sqlite.faceEmbeddings.count
        summary.ocrWordCount = sqlite.ocrWords.count
        summary.ocrWordOccurrenceCount = sqlite.ocrWordOccurrences.count
        summary.locationCount = sqlite.locationHierarchy.count
        summary.mediaLocationCount = sqlite.mediaLocations.count
        summary.mediaFeedbackCount = sqlite.mediaFeedback.count
        summary.personRelationCount = sqlite.personRelations.count
        summary.chatSessionCount = chat.sessions.count
        summary.chatMessageCount = chat.messages.count

        NSLog("[PoLang:BackupRestore] export ok tags=%d media=%d persons=%d sessions=%d messages=%d bytes=%d",
              summary.tagCount, summary.mediaCount, summary.personCount,
              summary.chatSessionCount, summary.chatMessageCount, data.count)
        return (data, filename, summary)
    }

    // MARK: - 导入

    /// 解析 + 恢复备份。SQLite 段单事务（失败整体回滚）；聊天段在其后走文件层。
    func restoreBackup(from data: Data) throws -> BackupRestoreSummary {
        let backup: TagDataBackupContainer
        do {
            backup = try JSONDecoder().decode(TagDataBackupContainer.self, from: data)
        } catch {
            NSLog("[PoLang:BackupRestore] parse failed: \(error)")
            throw BackupRestoreError.parseFailed(String(describing: error))
        }
        // 版本门禁：iOS v1 仅接受 v6（Android 兼容 v5 粗映射，iOS 不做——contracts C7）
        guard backup.version == TagDataBackupContainer.currentVersion else {
            throw BackupRestoreError.unsupportedVersion(backup.version)
        }

        var summary = try TagDatabase.shared.backupRestoreSqliteSegments(backup)

        // 聊天段在 SQLite 事务之外（iOS 聊天存储是 JSON 文件非 Room 表）
        let chat = try restoreChatSegments(sessions: backup.chatSessions, messages: backup.chatMessages)
        summary.restoredChatSessionCount = chat.sessions
        summary.restoredChatMessageCount = chat.messages

        // preferences/memoryFacts/photoEditRecipes：iOS v1 忽略（C7 决策，登记技术债）
        NSLog("[PoLang:BackupRestore] restore ok matched=%d unmatched=%d tags=%d persons=%d sessions=%d messages=%d",
              summary.matchedMediaCount, summary.unmatchedUriCount, summary.restoredTagCount,
              summary.restoredPersonCount, chat.sessions, chat.messages)
        return summary
    }

    // MARK: - 聊天段：导出

    private struct ChatExportResult {
        var sessions: [BackupChatSession] = []
        var messages: [BackupChatMessage] = []
    }

    /// 聊天段导出：索引 + 每会话 envelope v6 文件 → Android DTO 形态。
    /// 不可解码文件（version < 6 / 结构损坏）整体跳过，与 ChatHistoryStore.loadMessages 门禁同语义。
    private func exportChatSegments() -> ChatExportResult {
        var out = ChatExportResult()
        for thread in ChatHistoryStore.shared.loadThreads() {
            let updatedAtMs = Int64(thread.updatedAt.timeIntervalSince1970 * 1000)
            var session = BackupChatSession(
                sessionId: thread.sessionId,
                title: thread.title,
                createdAt: updatedAtMs,   // 索引无 createdAt，首条消息时间戳缺失时回退 updatedAt
                updatedAt: updatedAtMs)
            if let rows = readEnvelopeMessages(sessionId: thread.sessionId) {
                var firstTs: Int64? = nil
                var lastTs: Int64? = nil
                for row in rows {
                    guard let id = row["id"] as? String,
                          let type = row["type"] as? String,
                          let content = row["content"] as? String,
                          let timestamp = (row["timestamp"] as? NSNumber)?.int64Value else { continue }
                    out.messages.append(BackupChatMessage(
                        id: id,
                        sessionId: thread.sessionId,
                        type: type,
                        content: content,
                        timestamp: timestamp,
                        modelUsed: row["modelUsed"] as? String,
                        metadata: nil,          // iOS envelope 无 metadata blob，恒 nil
                        role: row["role"] as? String,
                        partsJson: encodePartsJson(row["parts"])))
                    if firstTs == nil || timestamp < firstTs! { firstTs = timestamp }
                    if lastTs == nil || timestamp > lastTs! { lastTs = timestamp }
                }
                session.createdAt = firstTs ?? updatedAtMs
                if let lastTs { session.updatedAt = max(updatedAtMs, lastTs) }
            }
            out.sessions.append(session)
        }
        return out
    }

    /// 读 envelope v6 会话文件（JSONSerialization 解析以保留 parts 数组原貌供直通）。
    private func readEnvelopeMessages(sessionId: String) -> [[String: Any]]? {
        let url = Self.documentsDirectory.appendingPathComponent("chat_history_\(sessionId).json")
        guard let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data),
              let envelope = object as? [String: Any],
              let version = envelope["version"] as? Int, version >= 6,
              let messages = envelope["messages"] as? [[String: Any]] else { return nil }
        return messages
    }

    /// parts 数组（JSONSerialization 已解析形态）→ 紧凑 JSON 字符串（Android partsJson 直通）。
    private func encodePartsJson(_ parts: Any?) -> String? {
        guard let parts = parts as? [Any],
              let data = try? JSONSerialization.data(withJSONObject: parts, options: [.withoutEscapingSlashes]),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    // MARK: - 聊天段：恢复

    /// 逐会话重建 envelope v6 文件（经 shared ChatHistoryStoreCodec decode/encode 归一化：
    /// 未知 part → Tombstone 占位、损坏行降级 Text，不丢消息）+ 索引合并
    /// （同 id 会话覆盖，对齐 Android「REPLACE 幂等」语义）。
    private func restoreChatSegments(sessions: [BackupChatSession], messages: [BackupChatMessage]) throws -> (sessions: Int, messages: Int) {
        guard !sessions.isEmpty else { return (0, 0) }
        let messagesBySession = Dictionary(grouping: messages, by: \.sessionId)
        let store = ChatHistoryStore.shared
        var threads = store.loadThreads()

        var restoredSessions = 0
        var restoredMessages = 0
        for session in sessions {
            let rows = messagesBySession[session.sessionId] ?? []

            // 重建 envelope v6：partsJson 解回 parts 数组直通；role 缺失按 type 前缀推断
            // （codec DTO 的 role 为必填列）；partsJson 缺失/空数组时省略键，交 codec
            // 自平铺字段重建（belt-and-braces 路径，与 Android v5 物化器同口径）。
            let messageDicts: [[String: Any]] = rows.compactMap { row in
                var dict: [String: Any] = [
                    "id": row.id,
                    "type": row.type,
                    "role": row.role ?? Self.inferRole(type: row.type),
                    "content": row.content,
                    "timestamp": row.timestamp,
                ]
                if let modelUsed = row.modelUsed { dict["modelUsed"] = modelUsed }
                if let partsJson = row.partsJson,
                   let partsData = partsJson.data(using: .utf8),
                   let partsObject = try? JSONSerialization.jsonObject(with: partsData),
                   let partsArray = partsObject as? [Any], !partsArray.isEmpty {
                    dict["parts"] = partsArray
                }
                return dict
            }
            let envelope: [String: Any] = ["version": 6, "messages": messageDicts]
            guard let envelopeData = try? JSONSerialization.data(withJSONObject: envelope),
                  let text = String(data: envelopeData, encoding: .utf8),
                  let decoded = ChatHistoryStoreCodec.shared.decode(text: text) else {
                throw BackupRestoreError.chatSessionDecodeFailed(session.sessionId)
            }
            store.saveMessages(sessionId: session.sessionId, messages: decoded)

            // 索引 upsert（updatedAt 以备份为准；预览取末条消息内容前缀）
            let updatedAt = Date(timeIntervalSince1970: Double(session.updatedAt) / 1000)
            let preview = rows.last.map { Self.previewText($0.content) }
            if let idx = threads.firstIndex(where: { $0.sessionId == session.sessionId }) {
                threads[idx].title = session.title
                threads[idx].updatedAt = updatedAt
                if let preview { threads[idx].lastMessagePreview = preview }
            } else {
                threads.append(ChatThread(
                    sessionId: session.sessionId,
                    title: session.title,
                    updatedAt: updatedAt,
                    lastMessagePreview: preview ?? ""))
            }
            restoredSessions += 1
            restoredMessages += decoded.count
        }
        saveThreadsFile(threads)
        return (restoredSessions, restoredMessages)
    }

    /// chat_sessions.json 索引落盘（ChatHistoryStore.saveThreads 为 private，此处同口径重写；
    /// 读侧 loadThreads 自行排序，存储顺序无关紧要）。
    private func saveThreadsFile(_ threads: [ChatThread]) {
        let url = Self.documentsDirectory.appendingPathComponent("chat_sessions.json")
        if let data = try? JSONEncoder().encode(threads) {
            do {
                try data.write(to: url, options: .atomic)
            } catch {
                NSLog("[PoLang:BackupRestore] saveThreadsFile failed: \(error)")
            }
        }
    }

    /// 旧分类法 type 前缀推断角色（对齐 Android 恢复侧口径：user_* → user，其余 agent）。
    private static func inferRole(type: String) -> String {
        type.hasPrefix("user") ? "user" : "agent"
    }

    private static func previewText(_ content: String) -> String {
        let flattened = content.replacingOccurrences(of: "\n", with: " ")
        return String(flattened.prefix(60))
    }
}
