import Foundation
import SharedKit

/// `IosUserTaskStoreBridge` 的文件持久化实现：Documents/user_tasks.json。
/// 线格式 = UserTaskRow 字段直序 JSON（枚举 name 字符串），与 Android Room user_task 表列口径一致。
///
/// SharedBridge 铁律（同 `PhMediaBridge`）：本类方法绝不抛异常跨边界——
/// 读失败返回 []，写失败返回 false（Kotlin 侧按降级语义处理：内存态保留、下次变更重试）。
@objc final class UserTaskStoreBridge: NSObject, IosUserTaskStoreBridge {

    /// 线格式 DTO（与 UserTaskRow 字段一一对应）。
    private struct RowDto: Codable {
        let id: String
        let kind: String
        let displayName: String?
        let status: String
        let errorCode: String?
        let errorDetail: String?
        let destination: String
        let updatedAt: Int64
        let completedAt: Int64?
    }

    private let fileURL: URL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("user_tasks.json")

    /// 读写串行化（Kotlin 侧从 Default dispatcher 协程调用，可能并发）。
    private let lock = NSLock()

    /// 启动全量加载（重启对账降级：行原样呈现，终态历史保留，无进程死亡对账——台账已登记）。
    func loadAll() -> [UserTaskRow] {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: fileURL), !data.isEmpty,
              let dtos = try? JSONDecoder().decode([RowDto].self, from: data) else {
            return []
        }
        return dtos.map { dto in
            UserTaskRow(
                id: dto.id,
                kind: dto.kind,
                displayName: dto.displayName,
                status: dto.status,
                errorCode: dto.errorCode,
                errorDetail: dto.errorDetail,
                destination: dto.destination,
                updatedAt: dto.updatedAt,
                completedAt: dto.completedAt.map { KotlinLong(longLong: $0) })
        }
    }

    /// 全量覆盖落盘（行数封顶 HISTORY_KEEP + 活动行，量级小，原子整写）。
    func persistAll(rows: [UserTaskRow]) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let dtos = rows.map { row in
            RowDto(
                id: row.id,
                kind: row.kind,
                displayName: row.displayName,
                status: row.status,
                errorCode: row.errorCode,
                errorDetail: row.errorDetail,
                destination: row.destination,
                updatedAt: row.updatedAt,
                completedAt: row.completedAt?.int64Value)
        }
        guard let data = try? JSONEncoder().encode(dtos) else { return false }
        do {
            try data.write(to: fileURL, options: .atomic)
            return true
        } catch {
            NSLog("PoLang:UserTaskStore persistAll failed: \(error.localizedDescription)")
            return false
        }
    }
}
