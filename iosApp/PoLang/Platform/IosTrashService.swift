import Foundation
import Photos

/// 删除批次产出（moveToTrash 主线程回调）。
///
/// iOS PHAsset 删除经 performChanges 原子提交：系统确认后整批生效，用户取消/系统错误
/// 整批不发生——无 Android TrashSessionController「授权后残留」式真部分失败
/// （organize.yaml §8 platform_differences 台账已登记）。
struct TrashBatchResult {
    /// 请求删除的 localIdentifier（入参保序去重后）。
    let requested: [String]
    /// 已从图库移除（系统确认删除 ∪ 本就不在库中的项——目标态一致，按已移除计）。
    let deleted: [String]
    /// 未删除（用户在系统确认框取消或系统错误）；详情页据非空弹 org_partial_trash 口径提示。
    let failed: [String]

    var isFullyDeleted: Bool { failed.isEmpty }
}

/// 整理中心删除通路（T3）：PHAssetChangeRequest.deleteAssets 薄封装。
///
/// 平台差异（organize.yaml §8 platform_differences / organize-port-plan 决策锁定，勿「修复」对齐）：
/// - 系统强制确认框，无授权队列/静默快路径（Android createTrashRequest / MANAGE_MEDIA 无 iOS 等价）；
/// - 无 Undo：iOS 无 API 从「最近删除」程序化恢复，恢复走系统相册（30 天保留），
///   cleaned 屏 Undo 整段裁剪；
/// - TagDatabase 快照清理由 PhMediaBridge 删除通路内联（防线1），本类不重复。
final class IosTrashService {
    private let bridge: PhMediaBridge

    init(bridge: PhMediaBridge = PhMediaBridge()) {
        self.bridge = bridge
    }

    /// 批量移入「最近删除」；completion 恒在主线程回调（空输入亦同步切主线程回调）。
    func moveToTrash(_ localIdentifiers: [String],
                     completion: @escaping (TrashBatchResult) -> Void) {
        // 保序去重
        var seen = Set<String>()
        let ids = localIdentifiers.filter { seen.insert($0).inserted }
        guard !ids.isEmpty else {
            DispatchQueue.main.async {
                completion(TrashBatchResult(requested: [], deleted: [], failed: []))
            }
            return
        }
        // 复用 PhMediaBridge.deleteMediaAwaitingOutcome 通路：系统确认成功后内联清理
        // TagDatabase 快照（防线1），回调已在主线程。
        let started = bridge.deleteMediaAwaitingOutcome(localIdentifiers: ids) { success in
            completion(TrashBatchResult(
                requested: ids,
                deleted: success ? ids : [],
                failed: success ? [] : ids
            ))
        }
        if !started {
            // 全部不在库（已删/从未存在）：目标态已达成，按已移除计，不进失败集。
            DispatchQueue.main.async {
                completion(TrashBatchResult(requested: ids, deleted: ids, failed: []))
            }
        }
    }
}
