import Foundation
import Photos

/// 头像拍摄目标（对标 Android AvatarCaptureTarget）。
enum AvatarCaptureTarget: Equatable {
    /// 指定人物聚类的封面
    case person(Int64)
    /// 「我」的头像（persons.is_self 标记的人物）
    case selfTarget
}

/// 头像拍摄来源页（仅作诊断记录；返回由 cover 栈自然落回来源页，不按 origin 分别切页）。
enum AvatarCaptureOrigin {
    case peoplePage
    case galleryPage
    case settingsPage
}

/// 一次待处理的头像拍摄请求（beginMs 仅作诊断/超时参考，不作为照片识别依据）。
struct PendingAvatarCapture: Equatable {
    let target: AvatarCaptureTarget
    let origin: AvatarCaptureOrigin
    let beginMs: Int64
}

/// 头像拍摄会话控制器（全局单例，对标 Android AvatarCaptureController）。
///
/// 相机为 fullScreenCover 路由（2026-09-16 导航统一，此前是 Pager 页 0），人物编辑页与相机之间
/// 无法靠视图层级传参，故用进程内单例传递「待拍头像」意图：`begin()` 登记 → MainTabView 观察
/// pending 弹出相机 cover → CameraPreviewView 进头像拍摄态（默认前置 + 提示文案）→ 拍照落库后
/// `AvatarCaptureFinisher` 把新照片设为目标封面并 `clear()`。
final class AvatarCaptureController: ObservableObject {
    static let shared = AvatarCaptureController()

    /// 当前待处理的头像拍摄请求；nil = 非头像拍摄态
    @Published private(set) var pending: PendingAvatarCapture?

    /// 头像拍摄态是否已在相机页实际激活（前置切换与提示文案已生效）。
    /// 作为「页面失活即取消」的前置条件：登记 pending 后到激活前的窗口内不得误清 pending。
    @Published private(set) var activated = false

    private init() {}

    /// 登记一次头像拍摄请求；重复调用覆盖旧请求（以最后一次点击为准）。
    func begin(target: AvatarCaptureTarget, origin: AvatarCaptureOrigin) {
        activated = false
        pending = PendingAvatarCapture(
            target: target, origin: origin,
            beginMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    /// 相机页实际进入头像拍摄态时置位；无 pending 时为 no-op。
    func markActivated() {
        if pending != nil { activated = true }
    }

    /// 结束头像拍摄态（完成或取消）。幂等。
    func clear() {
        pending = nil
        activated = false
    }
}

extension Notification.Name {
    /// 头像拍摄完成、人物封面已更新（PersonInfoView 监听刷新封面显示）。
    static let avatarCoverUpdated = Notification.Name("PoLangAvatarCoverUpdated")
}

/// 头像拍摄收尾（对标 Android AvatarCaptureFinisher）：
/// 拍照保存回调不带新照片 id，采用「快门时刻下界反查」——查 creationDate ≥ 快门时刻的最新照片
/// （下界前让 2s 容忍时钟/落库偏差）→ get-or-create TagDatabase 行 → 设为目标人物封面。
enum AvatarCaptureFinisher {

    /// 完成一次头像拍摄：找新照片 → 设封面 → 广播 avatarCoverUpdated。任何一步失败静默放弃
    /// （对齐 Android runCatching 语义：失败不崩溃，仅本次不生效）。
    static func finish(target: AvatarCaptureTarget, shutterDate: Date) async {
        let lowerBound = shutterDate.addingTimeInterval(-2)
        guard let asset = latestPhoto(since: lowerBound) else { return }
        let lid = asset.localIdentifier
        let captureMs = Int64((asset.creationDate ?? Date()).timeIntervalSince1970 * 1000)
        let fileName = PHAssetResource.assetResources(for: asset).first?.originalFilename ?? lid

        // TagDatabase 读写走其内部串行 queue.sync，任意线程可调
        let mediaId = await Task.detached(priority: .userInitiated) { () -> Int64 in
            TagDatabase.shared.getOrCreateMedia(
                localIdentifier: lid, type: "IMAGE",
                captureDateMs: captureMs, fileName: fileName
            )
        }.value
        guard mediaId > 0 else { return }

        let personId: Int64
        switch target {
        case .person(let id):
            personId = id
        case .selfTarget:
            // 未标记「我」则放弃（对齐 Android：getSelfPersonId 为 null 时短路）
            let selfTask = Task.detached(priority: .userInitiated) {
                TagDatabase.shared.selfPersonId()
            }
            guard let sid = await selfTask.value else { return }
            personId = sid
        }

        await Task.detached(priority: .userInitiated) {
            PersonRepository.shared.updateCover(personId: personId, mediaId: mediaId)
        }.value
        await MainActor.run {
            NotificationCenter.default.post(name: .avatarCoverUpdated, object: nil)
        }
    }

    /// 查快门时刻之后新入库的最新照片（PHFetch 精确反查，替代 Android 的 Room 轮询）。
    private static func latestPhoto(since lowerBound: Date) -> PHAsset? {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "mediaType == %d AND creationDate >= %@",
            PHAssetMediaType.image.rawValue, lowerBound as NSDate
        )
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 1
        return PHAsset.fetchAssets(with: options).firstObject
    }
}
