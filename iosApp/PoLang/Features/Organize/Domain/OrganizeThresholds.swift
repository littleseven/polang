import Foundation

/// 整理中心 v2 全部判定阈值（单一事实来源，校准只改这里）。
/// 直译 Android domain/organize/OrganizeThresholds.kt；置信度边界以「×主阈值」的倍数表达
/// （见 ConfidenceGrader）。
enum OrganizeThresholds {

    // ── 沿用 v1 ─────────────────────────────────────────────
    /// eDifFIQA 人脸质量分（0~1）低于该值判低质量人像。
    static let faceQualityLow = 0.35

    /// 大文件：视频字节阈值（100 MiB）。
    static let largeVideoBytes: Int64 = 100 * 1024 * 1024

    /// NIMA 美学分（1~10）低于该值作为预留常量（不定类、不参与置信分级，见 organize.yaml §1）。
    static let aestheticLow = 3.5

    // ── 模糊/曝光（BlurAnalyzer 产出）────────────────────────
    /// Laplacian 方差模糊阈值（256px 灰度图口径）：低于判真模糊。
    /// 初始值按典型手机实拍分布取 100.0，落地后用样本校准（spec §8 blur_threshold_calibration）。
    static let blurVarianceLow = 100.0

    /// 平均亮度（0~1）低于判欠曝。
    static let exposureUnder = 0.15

    /// 平均亮度（0~1）高于判过曝。
    static let exposureOver = 0.85

    /// 置信度分级：强命中 = 主阈值 × 该系数（blur/faceQuality 等越低越差型信号）。
    /// 适用于「越低越差」型信号；「越高越强」型用 ocrStrongFactor / largeFileStrongFactor。
    static let strongSignalFactor = 0.5

    // ── 大文件（超分辨率照片）────────────────────────────────
    /// 超高像素面积阈值（50 MP）。
    static let largePhotoPixelArea: Int64 = 50_000_000

    /// 超分辨率照片大小阈值（20 MB，与像素面积同时满足才判大文件）。
    static let largePhotoBytes: Int64 = 20 * 1024 * 1024

    /// LARGE_FILES 强命中倍数：大小 ≥ 主阈值 × 该系数 → HIGH 置信。
    static let largeFileStrongFactor: Int64 = 2

    // ── 价值保护 ────────────────────────────────────────────
    /// 年代久远：拍摄时间早于 now − 该年数判老照片。
    static let oldPhotoYears: Int64 = 5

    /// 人物稀缺：所属人物聚类照片总数 ≤ 该值判稀缺。
    static let personScarceMax = 3

    /// OCR 强命中倍数：文字密度 ≥ 主阈值 × 该系数 → HIGH 置信。
    static let ocrStrongFactor: Int64 = 2

    /// DOCUMENT OCR 文字密度主阈值（字符数/百万像素）。
    /// 值固化自 Android dedup 侧 SSOT `DedupContentTypeDetector.DOCUMENT_OCR_DENSITY_PER_MEGAPIXEL = 20`
    /// （androidApp/.../domain/dedup/DedupContentTypeDetector.kt；Android OrganizeThresholds 仅别名引用）。
    /// 依据：旧绝对阈值 200 字符在典型 12MP 照片上等价约 17 字符/MP，取整 20。
    static let ocrDensityPerMegapixel: Int64 = 20

    /// DOCUMENT OCR 绝对字符数兜底阈值（尺寸未知时）。
    /// 值固化自 Android dedup 侧 SSOT `DedupContentTypeDetector.DOCUMENT_OCR_CHAR_THRESHOLD = 200`（同上出处）。
    static let ocrDensityFallbackChars: Int64 = 200

    /// 一年毫秒数（365 天近似，老照片判定用）。
    static let yearMillis: Int64 = 365 * 24 * 60 * 60 * 1000
}
