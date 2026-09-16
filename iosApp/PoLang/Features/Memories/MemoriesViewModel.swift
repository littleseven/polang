import Foundation
import Photos
import SharedKit
import SwiftUI

/// 回忆页 VM（spec memories.yaml §1 data_chain.ios + §2/§3 数据口径）。
///
/// 数据链：TagDatabase.media_assets（type=IMAGE，PHAsset 存活集过滤回收站）+ persons 表
/// → MemoriesGenerator.generate（后台 utility 队列）→ 主线程发布。
/// ⚠️ TagDatabase 无变更通知机制——本批以 onAppear reload 轮询为准（预览删除后
/// fullScreenCover onDismiss 触发 reload 兜底）；PHPhotoLibrary 观察接入留待后续批次。
///
/// 隐藏存储：UserDefaults string array（key `memory_hidden_ids`，spec §1 hidden_store.ios）。
/// allGenerated 为未过滤全集（详情页直达/已隐藏条目可复原），memories 展示集剔除隐藏 id。
@MainActor
final class MemoriesViewModel: ObservableObject {

    /// 展示集（剔除隐藏 id）。
    @Published var memories: [Memory] = []
    /// 未过滤全集（observeMemory(id) 数据源）。
    @Published private(set) var allGenerated: [Memory] = []
    /// uri → SharedKit MediaAsset 反查索引（详情页预览用；仅覆盖回忆引用的 uri）。
    @Published var assetsByUri: [String: MediaAsset] = [:]

    /// 时钟注入点（测试可固定 now）。
    var nowProvider: () -> Date = { Date() }

    static let hiddenIdsKey = "memory_hidden_ids"

    private var hiddenIds: Set<String>
    private var reloadTask: Task<Void, Never>?

    init() {
        hiddenIds = Set(UserDefaults.standard.stringArray(forKey: Self.hiddenIdsKey) ?? [])
    }

    deinit {
        reloadTask?.cancel()
    }

    // MARK: - 加载

    /// 后台生成 → 主线程发布。重复调用取消旧任务（onAppear 多次触发防重入）。
    func reload() {
        reloadTask?.cancel()
        let now = nowProvider()
        let timeZone = TimeZone.current
        reloadTask = Task { [weak self] in
            let snapshot = await Task.detached(priority: .utility) { () -> Snapshot in
                let db = TagDatabase.shared
                let rows = db.memoryPhotoRows()
                // 回收站排除：全集以 PHAsset 枚举为准（fetchAssets 默认排除 trashed，
                // 对齐 organize 批 fetchOrganizeAssetMeta 口径），DB 行按存活 lid 集过滤。
                let alive = Self.aliveImageLocalIdentifiers()
                let rowsByUri = Dictionary(
                    rows.filter { alive.contains($0.uri) }.map { ($0.uri, $0) },
                    uniquingKeysWith: { _, new in new })
                let inputs = rowsByUri.values.map {
                    MemoryInput(uri: $0.uri, captureDate: $0.captureDate,
                                aestheticScore: $0.aestheticScore.map(Float.init),
                                city: $0.city, personId: $0.faceId)
                }
                let persons = db.memoryNamedPersons().map {
                    NamedPerson(personId: $0.personId, name: $0.name, isSelf: $0.isSelf)
                }
                let memories = MemoriesGenerator.generate(
                    inputs: inputs, persons: persons, now: now, timeZone: timeZone)
                // assetsByUri 仅覆盖回忆引用的 uri（详情页预览反查；未解析项由消费侧剔除）
                var assets: [String: MediaAsset] = [:]
                for uri in Set(memories.flatMap(\.allItemUris)) {
                    guard let row = rowsByUri[uri] else { continue }
                    assets[uri] = MediaAsset(
                        id: Self.stableId(forUri: uri),
                        uri: row.uri,
                        type: MediaType.photo,
                        captureDate: row.captureDate,
                        fileName: row.fileName,
                        duration: nil,
                        hasFace: false,
                        faceId: nil,
                        source: nil,
                        labels: nil,
                        ocrText: nil,
                        latitude: nil,
                        longitude: nil,
                        locationName: nil,
                        city: nil,
                        indexedAt: nil,
                        faceFocusY: nil,
                        aestheticScore: nil,
                        faceQualityScore: nil)
                }
                return Snapshot(memories: memories, assetsByUri: assets)
            }.value
            guard !Task.isCancelled else { return }
            self?.apply(snapshot)
        }
    }

    /// 生成快照（后台产出 → 主线程发布载体）。
    private struct Snapshot {
        let memories: [Memory]
        let assetsByUri: [String: MediaAsset]
    }

    private func apply(_ snapshot: Snapshot) {
        allGenerated = snapshot.memories
        assetsByUri = snapshot.assetsByUri
        memories = snapshot.memories.filter { !hiddenIds.contains($0.id) }
    }

    /// 未过滤全集查询（已隐藏条目可复原；id 失效返回 nil → 详情页空态）。
    func memory(id: String) -> Memory? {
        allGenerated.first { $0.id == id }
    }

    // MARK: - 隐藏（spec §1 hidden_store：整体读写、幂等 hide、展示层过滤）

    /// 隐藏一条回忆：幂等；写失败不崩溃（UserDefaults set 无异常，隐藏集为内存兜底）。
    func hideMemory(id: String) {
        guard !hiddenIds.contains(id) else { return }
        hiddenIds.insert(id)
        UserDefaults.standard.set(Array(hiddenIds), forKey: Self.hiddenIdsKey)
        memories = allGenerated.filter { !hiddenIds.contains($0.id) }
    }

    // MARK: - 平台工具

    /// 存活照片 localIdentifier 集（PHAsset 默认枚举排除回收站/隐藏项）。
    /// [PRIVACY] 纯本地元数据枚举，零网络。
    /// nonisolated：纯 PHAsset 枚举，供后台 detached 任务直调（类为 @MainActor）。
    nonisolated private static func aliveImageLocalIdentifiers() -> Set<String> {
        let opts = PHFetchOptions()
        opts.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        let result = PHAsset.fetchAssets(with: opts)
        var out = Set<String>()
        out.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in out.insert(asset.localIdentifier) }
        return out
    }

    /// uri → 稳定 id：UTF-16 Java String.hashCode（对齐 shared IosMediaRepository 的
    /// `localIdentifier.hashCode().toLong()` 派生口径；仅进程内查找/删除定位用，不持久化）。
    /// nonisolated：纯函数，供后台 detached 任务直调（类为 @MainActor）。
    nonisolated static func stableId(forUri uri: String) -> Int64 {
        var h: Int64 = 0
        for unit in uri.utf16 {
            h = h &* 31 &+ Int64(unit)
        }
        return Int64(Int32(truncatingIfNeeded: h))
    }
}
