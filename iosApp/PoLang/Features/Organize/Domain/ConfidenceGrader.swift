import Foundation

/// 置信度分级（纯函数）：决定详情页三段分组与默认勾选口径。
/// HIGH = 强信号命中（默认勾选）；MEDIUM = 单信号接近阈值（仅列出）；
/// LOW = 弱信号（美学分等辅助信号单独命中）。
/// 直译 Android domain/organize/ConfidenceGrader.kt。
/// 前置：item 已经 CategoryArbiter 裁定入 category，本层只评估信号强度。
enum ConfidenceGrader {

    static func grade(_ item: OrganizeItem, category: OrganizeCategory) -> OrganizeConfidence {
        switch category {
        case .duplicates:
            return item.exactDupGroupSize >= 2 ? .high : .medium

        // 路径/子类型判定为确定性强信号
        case .screenContent:
            return .high

        case .documents:
            return gradeDocument(item)

        case .lowQualityPortraits:
            let faceQuality = item.faceQualityScore ?? 1.0
            return faceQuality < OrganizeThresholds.faceQualityLow * OrganizeThresholds.strongSignalFactor
                ? .high : .medium

        case .lowQualityPhotos:
            return gradeLowQualityPhoto(item)

        case .largeFiles:
            return item.sizeBytes >= OrganizeThresholds.largeFileStrongFactor * largeFileThreshold(item)
                ? .high : .medium
        }
    }

    /// OCR 强命中（≥2×密度阈值）HIGH；OCR 命中或仅标签命中 MEDIUM。
    private static func gradeDocument(_ item: OrganizeItem) -> OrganizeConfidence {
        let chars = Int64(item.ocrText?.count ?? 0)
        if chars == 0 { return .medium } // labels 关键词命中（无 OCR 佐证）
        if let area = item.pixelArea, area > 0 {
            // 与 Android 同一面积归一口径的 2×（先乘后除，避免 sub-MP 整数截断塌缩为 0）
            return chars * 1_000_000 >= area * OrganizeThresholds.ocrDensityPerMegapixel * OrganizeThresholds.ocrStrongFactor
                ? .high : .medium
        }
        return chars >= OrganizeThresholds.ocrDensityFallbackChars * OrganizeThresholds.ocrStrongFactor
            ? .high : .medium
    }

    /// 模糊强命中 HIGH；模糊/曝光任一边界命中 MEDIUM；其余（含 nil 信号）LOW。
    private static func gradeLowQualityPhoto(_ item: OrganizeItem) -> OrganizeConfidence {
        if let blur = item.blurScore,
           blur < OrganizeThresholds.blurVarianceLow * OrganizeThresholds.strongSignalFactor {
            return .high
        }
        let blurred = (item.blurScore ?? .infinity) < OrganizeThresholds.blurVarianceLow
        let badExposure: Bool
        if let exposure = item.exposureScore {
            badExposure = exposure < OrganizeThresholds.exposureUnder || exposure > OrganizeThresholds.exposureOver
        } else {
            badExposure = false
        }
        if blurred || badExposure { return .medium }
        // 防御性兜底：arbiter 不定类 NIMA-only 项，正常管线不可达；nil 信号落最低档方向安全
        return .low
    }

    /// 大文件主阈值：视频按字节，照片按字节（像素面积门槛已在 CategoryArbiter 裁定）。
    private static func largeFileThreshold(_ item: OrganizeItem) -> Int64 {
        item.isVideo ? OrganizeThresholds.largeVideoBytes : OrganizeThresholds.largePhotoBytes
    }
}
