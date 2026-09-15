import Foundation
import Photos
import SharedKit

/// IosMediaRepositoryBridge 的 Photos framework 实现。
///
/// SharedBridge 铁律（kmp-ios-interop skill）：本类所有方法绝不抛异常跨边界——
/// 失败一律用 false / 空集合表达（Kotlin 异常逃逸到 Swift 会 signal 6 崩溃）。
@objc final class PhMediaBridge: NSObject, IosMediaRepositoryBridge {
    private var changeListener: (() -> Void)?

    override init() {
        super.init()
        PHPhotoLibrary.shared().register(self)
    }

    deinit {
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
    }

    func currentAccessState() -> AccessState {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized: return AccessStateFull.shared
        case .limited: return AccessStateLimited.shared
        case .denied, .restricted, .notDetermined:
            // AddOnly 一等检测（🟡-4 修复，此前四态退化三态）：
            // readWrite 未授权但 addOnly 已授权 → AddOnly
            if PHPhotoLibrary.authorizationStatus(for: .addOnly) == .authorized {
                return AccessStateAddOnly.shared
            }
            return AccessStateDenied.shared
        @unknown default: return AccessStateDenied.shared
        }
    }

    /// creationDate 降序，与 Android MediaStore 排序对齐（S5 双端一致）。
    /// 谓词过滤 image/video——audio 资产不得误入 PHOTO（🟡-5 修复）。
    func fetchAllMedia() -> [IosMediaItem] {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.predicate = NSPredicate(
            format: "mediaType == %d OR mediaType == %d",
            PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        let result = PHAsset.fetchAssets(with: opts)
        var items: [IosMediaItem] = []
        items.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            // fileName 取 PHAssetResource 原始文件名，对齐 Android DISPLAY_NAME（S5）
            let fileName = PHAssetResource.assetResources(for: asset).first?.originalFilename
                ?? asset.localIdentifier
            items.append(IosMediaItem(
                localIdentifier: asset.localIdentifier,
                mediaType: asset.mediaType == .video ? "VIDEO" : "PHOTO",
                captureDateMs: Int64((asset.creationDate?.timeIntervalSince1970 ?? 0) * 1000),
                durationMs: asset.mediaType == .video
                    ? KotlinLong(longLong: Int64(asset.duration * 1000))
                    : nil,
                fileName: fileName
            ))
        }
        return items
    }

    func requestReadWriteAuthorization() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] _ in
            DispatchQueue.main.async { self?.changeListener?() }
        }
    }

    func addChangeListener(listener: @escaping () -> Void) {
        self.changeListener = listener
    }

    func removeChangeListener() {
        self.changeListener = nil
    }

    /// iOS 删除走 PHAssetChangeRequest（系统弹确认窗），免 Android 11+ IntentSender 授权队列。
    /// 系统确认删除成功后同步清理 TagDatabase 的 media_assets 快照（防线1，对齐 Android deleteMediaByIds）。
    func deleteMedia(localIdentifiers: [String]) -> Bool {
        deleteMediaAwaitingOutcome(localIdentifiers: localIdentifiers) { _ in }
    }

    /// 上滑删除通路（spec gallery-grid.yaml §16b swipe_up_delete）：与 deleteMedia 同路，
    /// 但把系统确认结果回传（主线程）——大图页仅在 success（用户确认删除）后收缩预览列表，
    /// 取消/失败停留原图（§16b list_shrink: on_trashed_outcome_only）。
    func deleteMediaAwaitingOutcome(localIdentifiers: [String],
                                    completion: @escaping (Bool) -> Void) -> Bool {
        guard !localIdentifiers.isEmpty else { return false }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: localIdentifiers, options: nil)
        guard assets.count > 0 else { return false }
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.deleteAssets(assets)
        }, completionHandler: { success, _ in
            // 用户在系统弹窗确认删除后才清 DB；拒绝（success=false）不动，避免数据不一致。
            // completionHandler 在后台线程；TagDatabase.queue.sync 自身线程安全。
            if success {
                TagDatabase.shared.deleteMediaByLocalIdentifiers(localIdentifiers)
            }
            DispatchQueue.main.async { completion(success) }
        })
        return true
    }

    // MARK: - 整理中心数据源扩展（organize v2 T2）

    /// 整理中心资产 meta：`fetchAllMedia` 五字段之外的扩展投影（对应 Android
    /// `OrganizeRepositoryImpl.queryMediaStoreMeta` 的 MediaStore meta）。
    /// join key = localIdentifier（对齐 Android uri 语义：iOS media_assets.uri = localIdentifier）。
    struct OrganizeAssetMeta {
        let localIdentifier: String
        let isVideo: Bool
        /// creationDate epoch 毫秒；nil → 0（对齐 Android 未知时间戳=0 保守口径，ValueGuard OLD_PHOTO 偏置）。
        let captureDateMs: Int64
        /// PHAssetResource "fileSize"（未公开 KVC key；取不到 → 0，与 Android SIZE 未知=0 同口径）。
        /// ⚠️ 不回退 requestImageDataAndOrientation 全量读取（代价太高，organize-port-plan 风险 3）。
        let sizeBytes: Int64
        /// pixelWidth × pixelHeight（零代价字段）；任一 ≤0 → nil（对齐 Android 脏值 null 口径，
        /// OCR 密度判定退回绝对阈值）。
        let pixelArea: Int64?
        let isFavorite: Bool
        /// mediaSubtypes 含 .photoScreenshot（iOS 截图判定口径，替代 Android 路径关键词，
        /// organize.yaml §8 platform_differences 已登记）。
        let isScreenshot: Bool
        /// 录屏判定：iOS 公开 SDK（≤18.5）无 PHAssetMediaSubtype.videoScreenRecording，
        /// 亦无录屏智能相册公开 subtype——恒 false，SCREEN_CONTENT 视频子类不覆盖录屏
        ///（organize.yaml §8 platform_differences 已登记裁剪）。
        let isScreenRecording: Bool
    }

    /// 全库（image+video）整理 meta 枚举，key = localIdentifier。
    /// 纯枚举：PHAssetResource fileSize / pixelWidth / isFavorite / mediaSubtypes 均为本地元数据，
    /// 不触发任何网络请求（iCloud 云端-only 资产的跳过由解码侧 isNetworkAccessAllowed=false 承接，
    /// 见 OrganizeRepository 回填器；规划风险 2/3）。
    func fetchOrganizeAssetMeta() -> [String: OrganizeAssetMeta] {
        let opts = PHFetchOptions()
        opts.predicate = NSPredicate(
            format: "mediaType == %d OR mediaType == %d",
            PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        let result = PHAsset.fetchAssets(with: opts)
        var out: [String: OrganizeAssetMeta] = [:]
        out.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            // 主资源（与 fetchAllMedia 的 fileName 同源 .first）；配对资源不求和，防双计。
            let size = (PHAssetResource.assetResources(for: asset).first?
                .value(forKey: "fileSize") as? NSNumber)?.int64Value ?? 0
            let width = asset.pixelWidth
            let height = asset.pixelHeight
            out[asset.localIdentifier] = OrganizeAssetMeta(
                localIdentifier: asset.localIdentifier,
                isVideo: asset.mediaType == .video,
                captureDateMs: Int64((asset.creationDate?.timeIntervalSince1970 ?? 0) * 1000),
                sizeBytes: max(0, size),
                pixelArea: (width > 0 && height > 0) ? Int64(width) * Int64(height) : nil,
                isFavorite: asset.isFavorite,
                isScreenshot: asset.mediaType == .image
                    && asset.mediaSubtypes.contains(.photoScreenshot),
                isScreenRecording: false
            )
        }
        return out
    }

    /// 收藏/取消收藏（PHAssetChangeRequest 改 isFavorite，无系统确认窗）。
    func setFavorite(localIdentifier: String, favorite: Bool) -> Bool {
        guard !localIdentifier.isEmpty else { return false }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard assets.count > 0 else { return false }
        PHPhotoLibrary.shared().performChanges({
            assets.enumerateObjects { asset, _, _ in
                let req = PHAssetChangeRequest(for: asset)
                req.isFavorite = favorite
            }
        }, completionHandler: { _, _ in })
        return true
    }
}

extension PhMediaBridge: PHPhotoLibraryChangeObserver {
    func photoLibraryDidChange(_ changeInstance: PHChange) {
        DispatchQueue.main.async { [weak self] in self?.changeListener?() }
    }
}
