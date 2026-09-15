import Foundation
import Photos
import ImageIO
import CoreGraphics

/// 单批质量分补算产出（对齐 Android BackfillBatchResult）：
/// attempted = 本批待算张数，written = 成功回写张数；
/// failedUris = 本批 attempted 但解码/回写失败的 uri（调用方并入排除集跨批跳过）。
struct BackfillBatchResult {
    let attempted: Int
    let written: Int
    let failedUris: [String]
}

/// 整理中心 v2 数据源层（T2）：PHAsset 全量枚举 + TagDatabase 轻量投影按
/// localIdentifier（= media_assets.uri，对齐 Android uri join key 语义）合并为
/// [OrganizeItem] 快照；并承载 blur/exposure 后台回填与 lastViewedAt 回写挂钩。
///
/// 架构参考 Android data/repository/OrganizeRepositoryImpl.kt；差异（organize-port-plan）：
/// - 全集以 PHAsset 枚举为准（Android 以 Room 行为准）——iOS media_assets 仅扫描时索引
///   照片，未索引媒体不入 DB 但仍应参与路径/大小类目判定；
/// - dedup 聚类（DuplicateGrouper/dupCache 失效键防线）本批随 DUPLICATES 类目裁剪，
///   exactDup/similarDup 信号恒 0/nil，T8 扫描器落地时补；
/// - [PRIVACY] 全程端侧：回填解码 isNetworkAccessAllowed=false，iCloud 云端-only 资产跳过。
final class OrganizeRepository: @unchecked Sendable {
    static let shared = OrganizeRepository()

    private let db: TagDatabase
    private let media: PhMediaBridge

    init(db: TagDatabase = .shared, media: PhMediaBridge = PhMediaBridge()) {
        self.db = db
        self.media = media
    }

    // MARK: - 常量（对齐 Android 口径）

    /// 单批补算上限（对齐 Android backfillQualitySignals batchLimit 默认值 200）。
    static let backfillBatchLimit = 200
    /// 回填循环批次上限（对齐 DedupViewModel BACKFILL_MAX_BATCHES：200/批 × 1000 = 20 万张覆盖）。
    static let backfillMaxBatches = 1000
    /// 跨批失败排除集容量（对齐 BACKFILL_FAILED_URIS_CAP；超出最早逐出，被逐出行下轮可重试一次）。
    static let backfillFailedUrisCap = 500
    /// 回填缩略图最长边（BlurAnalyzer 输入约定；256px 降采样口径，阈值 BLUR_VARIANCE_LOW 同基线）。
    private static let backfillThumbMaxPixel = 256

    // MARK: - items / board

    /// 类目详情全量（一次性）。内部为同步阻塞 IO（Photos 枚举 + sqlite），
    /// 经 Task.detached(.utility) 离主线程执行。
    func loadItems() async -> [OrganizeItem] {
        await Task.detached(priority: .utility) { self.loadItemsBlocking() }.value
    }

    /// hub 看板构建入口：OrganizeCategorizer.board 的 async 封装。
    /// 全量聚类只在本显式调用时跑（本批聚类已随 DUPLICATES 裁剪，无 dupCache 失效键防线需求）。
    func board(now: Int64) async -> OrganizeBoard {
        let items = await loadItems()
        return OrganizeCategorizer.board(items, now: now)
    }

    /// PHAsset meta 全集 left join TagDatabase 投影行 → OrganizeItem（creationDate 降序，
    /// 对齐 fetchAllMedia / Android MediaStore 排序）。
    private func loadItemsBlocking() -> [OrganizeItem] {
        let metaById = media.fetchOrganizeAssetMeta()
        guard !metaById.isEmpty else { return [] }
        // media_assets.uri 非唯一索引：重复行 last-wins（保留最新快照，
        // 对齐 SwipeQueueBuilder 的 uri 去重语义；uniqueKeysWithValues 撞重复 uri 会崩）
        let rowsByUri = Dictionary(
            db.allOrganizeRows().map { ($0.uri, $0) },
            uniquingKeysWith: { _, new in new })
        let personCountByFaceId = db.personPhotoCounts()
        // captureDate 降序；平局回退枚举下标（Swift sort 不稳定，显式 tie-breaker
        // 对齐 OrganizeCategorizer.board 的做法，防同秒照片每次加载互换）
        return metaById.values
            .enumerated()
            .sorted { lhs, rhs in
                if lhs.element.captureDateMs != rhs.element.captureDateMs {
                    return lhs.element.captureDateMs > rhs.element.captureDateMs
                }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
            .map { meta in
                let row = rowsByUri[meta.localIdentifier]
                return OrganizeItem(
                    uri: meta.localIdentifier,
                    isVideo: meta.isVideo,
                    captureDate: meta.captureDateMs,
                    sizeBytes: meta.sizeBytes,
                    // iOS PHAsset 无路径概念（OrganizeItem.relativePath 字段注记；截图判定走 mediaSubtypes）
                    relativePath: nil,
                    ocrText: row?.ocrText,
                    pixelArea: meta.pixelArea,
                    labels: row?.labels,
                    hasFace: row?.hasFace ?? false,
                    aestheticScore: row?.aestheticScore,
                    faceQualityScore: row?.faceQualityScore,
                    blurScore: row?.blurScore,
                    exposureScore: row?.exposureScore,
                    lastViewedAt: row?.lastViewedAt,
                    isFavorite: meta.isFavorite,
                    // 无 faceId（无人脸/未聚类）→ nil，信号不参与保护判定（类级 nil=未覆盖契约）
                    personPhotoCount: row?.faceId.flatMap { personCountByFaceId[$0] },
                    // T8 dedup 扫描器本批裁剪：组信号恒 0/nil，DUPLICATES 不命中（决策锁定）
                    exactDupGroupSize: 0,
                    similarDupGroupSize: 0,
                    exactDupGroupKey: nil,
                    isScreenshot: meta.isScreenshot,
                    isScreenRecording: meta.isScreenRecording
                )
            }
    }

    // MARK: - lastViewedAt 回写挂钩（USER_ENGAGED 信号）

    /// 查看器打开当前页时调用（MediaPagerView 已接线 onAppear/翻页）。
    /// 60s 节流由 TagDatabase+Organize.updateLastViewedAt 承担（重复翻页零写放大）；
    /// 未索引媒体（无 media_assets 行）UPDATE 空命中，与 Android 同语义。
    func markMediaViewed(uri: String) {
        guard !uri.isEmpty else { return }
        db.updateLastViewedAt(uri: uri, epochMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    // MARK: - blur/exposure 后台回填（对齐 Android backfillQualitySignals + DedupViewModel 循环）

    /// 单批补算：blurScore 为 nil 的照片（视频不回填）排除已知失败后取前 batchLimit 张，
    /// 逐张解码算分、单事务合批回写。幂等，可反复调用。
    @discardableResult
    func backfillQualitySignals(
        batchLimit: Int = OrganizeRepository.backfillBatchLimit,
        excludeUris: Set<String> = []
    ) async -> BackfillBatchResult {
        await Task.detached(priority: .utility) {
            self.backfillQualitySignalsBlocking(batchLimit: batchLimit, excludeUris: excludeUris)
        }.value
    }

    /// 后台回填驱动（对齐 Android DedupViewModel.init 回填循环）：分批补算直到无待算行
    /// 或达批次上限；失败行跨批累计排除（容量封顶，FIFO 逐出）。
    /// 错峰（规划风险 2）：存在活跃 tag 扫描会话（含暂停）时整体跳过/中途让路。
    func runQualitySignalBackfill() async {
        guard !TagScanOrchestrator.shared.isSessionActive else {
            NSLog("PoLang:Organize backfill skipped (tag scan session active)")
            return
        }
        var failedOrder: [String] = []
        var failedSet = Set<String>()
        func addFailed(_ uris: [String]) {
            for uri in uris where !failedSet.contains(uri) {
                failedSet.insert(uri)
                failedOrder.append(uri)
            }
            while failedOrder.count > OrganizeRepository.backfillFailedUrisCap {
                failedSet.remove(failedOrder.removeFirst())
            }
        }
        var batch = await backfillQualitySignals(excludeUris: failedSet)
        addFailed(batch.failedUris)
        var batches = 1
        // 循环条件按 attempted（排除已知失败后仍有待算行）：全败批次不提前退出；
        // backfillMaxBatches 兜底防永久失败行反复入批放大为死循环。
        while batch.attempted > 0 && batches < OrganizeRepository.backfillMaxBatches {
            if TagScanOrchestrator.shared.isSessionActive {
                NSLog("PoLang:Organize backfill yielding (tag scan session started), batches=\(batches)")
                return
            }
            batch = await backfillQualitySignals(excludeUris: failedSet)
            batches += 1
            addFailed(batch.failedUris)
        }
        if !failedSet.isEmpty || batch.attempted > 0 {
            // 循环结束仍有未补算残留：失败行 blurScore 恒 nil 不进 LOW_QUALITY_PHOTOS，
            // 或命中批次上限仍有积压——如实上报，真机排查看此日志
            NSLog("PoLang:Organize backfill residual: \(failedSet.count) rows failed decode " +
                  "(skipped, blurScore stays null); lastBatch attempted=\(batch.attempted) " +
                  "written=\(batch.written), batches=\(batches)")
        }
    }

    private func backfillQualitySignalsBlocking(
        batchLimit: Int,
        excludeUris: Set<String>
    ) -> BackfillBatchResult {
        // 排除集 Swift 侧过滤（对齐 Android：行集在内存，绕开 SQLite IN 上限分批复杂度）
        let pending = db.pendingQualitySignalUris()
            .filter { !excludeUris.contains($0) }
            .prefix(batchLimit)
        var entries: [QualitySignalEntry] = []
        var failed: [String] = []
        entries.reserveCapacity(pending.count)
        for uri in pending {
            if let scores = computeQualitySignals(localIdentifier: uri) {
                entries.append(QualitySignalEntry(
                    uri: uri, blurScore: scores.blurScore, exposureScore: scores.exposureScore))
            } else {
                failed.append(uri)
            }
        }
        // 先逐张算分收集成功项，最后单事务合批回写（对齐 Android，避免逐行事务）
        if !entries.isEmpty {
            db.updateQualitySignals(entries)
            NSLog("PoLang:Organize backfill quality signals: \(entries.count)/\(pending.count)")
        }
        return BackfillBatchResult(attempted: pending.count, written: entries.count, failedUris: failed)
    }

    /// 解码 ≤256px 灰度图 → (blurScore, exposureScore)；解码失败/云端-only 返回 nil
    /// （跳过回写，绝不写 0——BlurAnalyzer 的 0 是退化输入哨兵，不可入库；
    /// 对齐 Android computeQualityScores 的 null 语义）。
    /// ⚠️ 同步 PHImageManager 请求，必须后台线程调用（本类经 Task.detached 保证）。
    /// [PRIVACY] isNetworkAccessAllowed=false：iCloud 云端-only 资产不触发网络拉取，
    /// data 为 nil → 归失败集跨批跳过（规划风险 2「云端项跳过」）。
    private func computeQualitySignals(localIdentifier: String) -> (blurScore: Double, exposureScore: Double)? {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = assets.firstObject, asset.mediaType == .image else { return nil }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = false
        opts.isSynchronous = true
        opts.deliveryMode = .fastFormat
        var imageData: Data?
        PHImageManager.default().requestImageDataAndOrientation(for: asset, options: opts) { data, _, _, _ in
            imageData = data
        }
        guard let data = imageData,
              let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        // CGImageSource 缩略图：子采样解码 ≤256px（不全量解码原图）
        let thumbOpts: [CFString: Any] = [
            kCGImageSourceThumbnailMaxPixelSize: OrganizeRepository.backfillThumbMaxPixel,
            kCGImageSourceCreateThumbnailFromImageAlways: true,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOpts as CFDictionary),
              let (gray, width, height) = Self.grayscalePixels(cgImage) else { return nil }
        // 退化位图（<3×3）直接跳过：BlurAnalyzer 会回 0 哨兵，不可入库
        if width < 3 || height < 3 { return nil }
        return BlurAnalyzer.analyze(gray: gray, width: width, height: height)
    }

    /// CGImage → BT.601 整数灰度数组（口径对齐 Android computeQualityScores 的
    /// `(299*r + 587*g + 114*b) / 1000`，经 BlurAnalyzer.bt601Gray 单一事实来源）。
    private static func grayscalePixels(_ image: CGImage) -> (pixels: [Int], width: Int, height: Int)? {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else { return nil }
        guard let ctx = CGContext(
            data: nil, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let base = ctx.data else { return nil }
        let buf = base.bindMemory(to: UInt8.self, capacity: width * height * 4)
        var gray = [Int]()
        gray.reserveCapacity(width * height)
        for i in 0 ..< width * height {
            gray.append(BlurAnalyzer.bt601Gray(
                r: Int(buf[i * 4]), g: Int(buf[i * 4 + 1]), b: Int(buf[i * 4 + 2])))
        }
        return (gray, width, height)
    }
}
