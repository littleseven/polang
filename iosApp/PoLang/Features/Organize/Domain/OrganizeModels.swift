import Foundation

// MARK: - 整理中心 v2 领域模型（直译 Android domain/organize/OrganizeModels.kt）
// 契约 SSOT：docs/08-UI-SPECS/screens/organize.yaml §1 领域口径。
// 纯值类型，零 UIKit/SwiftUI/Photos 依赖，可 XCTest 直测。

/// 整理中心 v2 可清理类目（互斥：一张媒体只进一个桶，CategoryArbiter 按声明顺序裁定）。
/// rawValue 对齐 Android 枚举名（organize_category/{category} 路由段与 i18n 键映射基准）。
/// ⚠️ 声明序 = 裁定优先级，变更必须同步 CategoryArbiter.classify 的 if 序列（有顺序守卫测试）。
enum OrganizeCategory: String, CaseIterable {
    case duplicates = "DUPLICATES"                   // 重复与相似（dedup_hash MD5/pHash 组）
    case screenContent = "SCREEN_CONTENT"            // 截图 + 录屏
    case documents = "DOCUMENTS"                     // 文档与票据（OCR 密度 / VLM 标签）
    case lowQualityPortraits = "LOW_QUALITY_PORTRAITS" // 低质人像（eDifFIQA 低分）
    case lowQualityPhotos = "LOW_QUALITY_PHOTOS"     // 低质量照片（真模糊 / 曝光异常）
    case largeFiles = "LARGE_FILES"                  // 大视频 + 超分辨率照片
}

/// 类目判定输入：TagDatabase 列 + PHAsset meta + dedup_hash 组信息 + 人物计数合并后的扁平快照。
/// nil = 信号未覆盖（绝不参与判定，由引导态承接）。
struct OrganizeItem {
    let uri: String
    let isVideo: Bool
    let captureDate: Int64          // epoch 毫秒；0 = 未知时间戳（保守偏置见 ValueGuard）
    let sizeBytes: Int64            // 未知 = 0
    /// Android 为 MediaStore RELATIVE_PATH；iOS PHAsset 无路径概念，预留 nil——
    /// 截图/录屏判定改走 isScreenshot/isScreenRecording（mediaSubtypes，见下）。
    let relativePath: String?
    let ocrText: String?
    let pixelArea: Int64?
    let labels: String?
    let hasFace: Bool
    let aestheticScore: Double?     // nil = 未评分（预留，当前管线不消费）
    let faceQualityScore: Double?   // nil = 未评分
    // ── v2 信号 ──────────────────────────────────────────────
    let blurScore: Double?          // Laplacian 方差，越大越清晰；nil = 未计算
    let exposureScore: Double?      // 平均亮度 0~1；nil = 未计算
    let lastViewedAt: Int64?        // epoch 毫秒；nil = 从未在查看器打开
    let isFavorite: Bool            // PHAsset.isFavorite
    /// 所属人物聚类的照片总数；nil = 无 faceId（无人脸或聚类未跑），此信号不参与保护判定
    /// ——与类级「nil=未覆盖不判定」契约一致；「无人脸」已由 hasFace=false 表达。
    let personPhotoCount: Int?
    /// 精确重复组大小（同 MD5 组成员数）；< 2 = 不在重复组。
    let exactDupGroupSize: Int
    /// 视觉相似组大小（pHash 簇成员数）；< 2 = 不在相似组。
    let similarDupGroupSize: Int
    /// 精确组标识（组内共享 MD5，仅 exactDupGroupSize ≥ 2 时非空）；hub 聚合按组扣 keeper 用。
    let exactDupGroupKey: String?
    // ── iOS 截图/录屏信号（替代 Android 路径关键词口径，已登记 platform_differences）──
    /// PHAsset.mediaSubtypes 含 .photoScreenshot（由 T2 数据源层填充，本批预留）。
    let isScreenshot: Bool
    /// 视频且 PHAsset.mediaSubtypes 含 .videoScreenRecording（由 T2 数据源层填充，本批预留）。
    let isScreenRecording: Bool

    init(
        uri: String,
        isVideo: Bool = false,
        captureDate: Int64 = 0,
        sizeBytes: Int64 = 0,
        relativePath: String? = nil,
        ocrText: String? = nil,
        pixelArea: Int64? = nil,
        labels: String? = nil,
        hasFace: Bool = false,
        aestheticScore: Double? = nil,
        faceQualityScore: Double? = nil,
        blurScore: Double? = nil,
        exposureScore: Double? = nil,
        lastViewedAt: Int64? = nil,
        isFavorite: Bool = false,
        personPhotoCount: Int? = nil,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
        exactDupGroupKey: String? = nil,
        isScreenshot: Bool = false,
        isScreenRecording: Bool = false
    ) {
        self.uri = uri
        self.isVideo = isVideo
        self.captureDate = captureDate
        self.sizeBytes = sizeBytes
        self.relativePath = relativePath
        self.ocrText = ocrText
        self.pixelArea = pixelArea
        self.labels = labels
        self.hasFace = hasFace
        self.aestheticScore = aestheticScore
        self.faceQualityScore = faceQualityScore
        self.blurScore = blurScore
        self.exposureScore = exposureScore
        self.lastViewedAt = lastViewedAt
        self.isFavorite = isFavorite
        self.personPhotoCount = personPhotoCount
        self.exactDupGroupSize = exactDupGroupSize
        self.similarDupGroupSize = similarDupGroupSize
        self.exactDupGroupKey = exactDupGroupKey
        self.isScreenshot = isScreenshot
        self.isScreenRecording = isScreenRecording
    }
}

/// 置信度分级（详情页三段分组 + 预选口径）。
enum OrganizeConfidence: String {
    case high = "HIGH"
    case medium = "MEDIUM"
    case low = "LOW"
}

/// 价值保护原因（详情页角标文案键）。
enum ProtectReason: String {
    case oldPhoto = "OLD_PHOTO"
    case scarcePerson = "SCARCE_PERSON"
    case userEngaged = "USER_ENGAGED"
}

/// 管线产出：单媒体裁定结果。
struct ClassifiedItem {
    let item: OrganizeItem
    let category: OrganizeCategory
    let confidence: OrganizeConfidence
    let protectReasons: Set<ProtectReason>

    /// 是否受价值保护（由保护原因集派生，单一事实来源）。
    var isProtected: Bool { !protectReasons.isEmpty }
}

/// 类目信号覆盖度：驱动 hub 类目卡「需先扫描」引导态（修复 v1 类目静默消失）。
enum SignalCoverage: String {
    case ready = "READY"
    case needsScan = "NEEDS_SCAN"
}

/// 类目卡聚合（hub 渲染输入）。
struct CategoryBoard {
    let category: OrganizeCategory
    let totalCount: Int
    let totalBytes: Int64
    /// HIGH 置信且非 protected：建议删除数。
    let highCount: Int
    /// 建议删除字节；DUPLICATES 额外按精确组扣 1 张 keeper（全员进建议集时保留一张不可删）。
    let highBytes: Int64
    /// MEDIUM+LOW 且非 protected：待确认数。
    let reviewCount: Int
    let protectedCount: Int
    let previewUris: [String]        // 前 4 张
    let coverage: SignalCoverage
}

/// hub 整体产出。
struct OrganizeBoard {
    /// 按 highBytes 降序（建议优先级）。
    let categories: [CategoryBoard]
    /// Hero 主数字：全类目 HIGH 且非 protected 去重并集字节（互斥裁定保证天然去重；DUPLICATES 已扣 keeper）。
    let heroReclaimBytes: Int64
    /// Hero 副行：MEDIUM+LOW 且非 protected 总数。
    let heroReviewCount: Int
}
