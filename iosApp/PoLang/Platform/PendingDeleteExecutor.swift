import Foundation

/// 待删任务执行器（gallery-grid.yaml §16b ios_note 会话批量语义）：
/// pending_deletes 表中的待删标记整批一次系统确认框（每触发至多 1 次）。
///
/// 触发分两类：
/// - `.userAction`（预览退出）：用户刚加过标记，期待确认——恒执行；
/// - `.recovery`（冷启动 / 回前台）：杀进程恢复——执行一次；
///   被用户取消后会话内抑制，避免「取消 → 立刻重弹」，下次回前台复位。
///
/// 取消语义：整批不删（照片从未离开图库），记录保留待下次触发；
/// 成功/目标已不在库 → 记录结清移除。
final class PendingDeleteExecutor {
    enum Trigger {
        case userAction
        case recovery
    }

    static let shared = PendingDeleteExecutor()

    private let trash: IosTrashService
    private let db: TagDatabase
    /// 执行飞行中守卫：onDisappear 与回前台钩子可能紧邻双触发，去重。
    private var isExecuting = false
    /// recovery 触发会话内抑制（用户取消后置位，下次 onAppForeground 复位）。
    private var recoverySuspended = false

    init(trash: IosTrashService = IosTrashService(), db: TagDatabase = .shared) {
        self.trash = trash
        self.db = db
    }

    /// 冷启动 / 回前台入口：复位 recovery 抑制并尝试恢复执行。
    func onAppForeground() {
        recoverySuspended = false
        executePendingIfAny(trigger: .recovery)
    }

    /// 有记录则整批执行一次（系统确认框至多 1 次）；无记录零开销。
    func executePendingIfAny(trigger: Trigger) {
        if trigger == .recovery, recoverySuspended { return }
        guard !isExecuting else { return }
        let uris = db.pendingDeleteUris()
        guard !uris.isEmpty else { return }
        isExecuting = true
        trash.moveToTrash(uris) { [weak self] result in
            guard let self else { return }
            self.isExecuting = false
            if result.isFullyDeleted {
                // 含「目标已不在库按已移除计」子集（IosTrashService 口径），记录全部结清。
                self.db.removePendingDeletes(result.requested)
            } else {
                // 用户取消：已真实移除的子集结清，其余保留；recovery 触发会话内抑制。
                self.db.removePendingDeletes(result.deleted)
                self.recoverySuspended = true
            }
        }
    }
}
