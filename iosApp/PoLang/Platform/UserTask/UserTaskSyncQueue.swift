import Foundation

/// 适配器同步串行化（对齐 Android 适配器单 collect 协程语义）：事件按到达序逐个执行，
/// 防 registry suspend 调用（upsertStatus/updateProgress）并发乱序。
final class UserTaskSyncQueue: @unchecked Sendable {
    private let lock = NSLock()
    private var tail: Task<Void, Never>?

    /// 追加一个同步单元；在上一单元完成后执行（任务链尾插，取消语义：链上任务互不取消）。
    func enqueue(_ op: @escaping () async -> Void) {
        lock.lock()
        let prev = tail
        tail = Task {
            _ = await prev?.value
            await op()
        }
        lock.unlock()
    }
}
