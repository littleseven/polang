import Foundation

/// 整理中心 v2 互斥类目裁定（纯函数，零平台依赖）：
/// 按 OrganizeCategory 声明顺序（=优先级）先命中先得，一张媒体只进一个桶。
/// 直译 Android domain/organize/CategoryArbiter.kt；裁定域约束：视频仅参与
/// DUPLICATES / SCREEN_CONTENT / LARGE_FILES。
///
/// iOS 口径差（已登记 platform_differences）：截图/录屏判定用 mediaSubtypes 布尔
/// （OrganizeItem.isScreenshot/isScreenRecording，T2 从 PHAsset 填充）替代 Android
/// RELATIVE_PATH 关键词（"screenshots"/"screenrecord" 等）；信号更强，路径关键词不移植。
enum CategoryArbiter {

    /// 返回唯一命中类目；无任何命中返回 nil（不属任何清理类目）。
    /// ⚠️ OrganizeCategory 声明序变更必须同步本方法的 if 序列（有顺序守卫测试）。
    static func classify(_ item: OrganizeItem) -> OrganizeCategory? {
        // 1. 重复与相似
        if item.exactDupGroupSize >= 2 || item.similarDupGroupSize >= 2 {
            return .duplicates
        }
        // 2. 屏幕内容（截图 + 录屏）
        if isScreenContent(item) { return .screenContent }
        if !item.isVideo {
            // 3. 文档与票据（OCR 密度 / labels 关键词，与 Android DedupContentTypeDetector 同口径）
            if isDocument(item) { return .documents }
            // 4. 低质人像
            if let faceQuality = item.faceQualityScore,
               item.hasFace, faceQuality < OrganizeThresholds.faceQualityLow {
                return .lowQualityPortraits
            }
            // 5. 低质量照片（真模糊 / 曝光异常；NIMA 仅预留常量，不定类）
            if isLowQualityPhoto(item) { return .lowQualityPhotos }
        }
        // 6. 大文件
        if isLargeFile(item) { return .largeFiles }
        return nil
    }

    /// iOS：mediaSubtypes 信号（截图照片 / 录屏视频）。
    private static func isScreenContent(_ item: OrganizeItem) -> Bool {
        if item.isScreenshot { return true }
        return item.isVideo && item.isScreenRecording
    }

    private static func isLowQualityPhoto(_ item: OrganizeItem) -> Bool {
        if let blur = item.blurScore, blur < OrganizeThresholds.blurVarianceLow { return true }
        guard let exposure = item.exposureScore else { return false }
        return exposure < OrganizeThresholds.exposureUnder || exposure > OrganizeThresholds.exposureOver
    }

    private static func isLargeFile(_ item: OrganizeItem) -> Bool {
        if item.isVideo {
            return item.sizeBytes >= OrganizeThresholds.largeVideoBytes
        }
        let area = item.pixelArea ?? 0
        return area >= OrganizeThresholds.largePhotoPixelArea &&
            item.sizeBytes >= OrganizeThresholds.largePhotoBytes
    }
}

// MARK: - DOCUMENT 判定（直译 Android DedupContentTypeDetector 的 DOCUMENT 分支）

/// DOCUMENT 标签关键词启发式：labels 为 TAG Pass 3 产出的自由文本（中英混合）。
/// 英文按整词（token）匹配并容忍可选复数后缀（documents/receipts/texts），
/// 避免 `context`/`texture`/`textile` 被子串 "text" 误伤；
/// 中文无词边界，按子串匹配（"截图文字" 等复合词由 "文字" 覆盖，不单独列死条目）。
private let documentLabelKeywordsEN = ["document", "receipt", "text", "screenshot_text"]
private let documentLabelKeywordsZH = ["文档", "证件", "票据", "文字"]
private let labelTokenRegex = try! NSRegularExpression(pattern: "[a-z0-9_]+")

extension CategoryArbiter {

    /// OCR 文字密度或 labels 关键词命中 → DOCUMENT。
    static func isDocument(_ item: OrganizeItem) -> Bool {
        if isDocumentText(ocrText: item.ocrText, pixelArea: item.pixelArea) { return true }
        guard let labels = item.labels else { return false }
        return labelsIndicateDocument(labels)
    }

    /// OCR 文字密度判定（spec §10.2 面积归一）：pixelArea 可用时按字符数/图面积，
    /// 否则（尺寸列缺失或脏值）退回绝对字符数兜底。
    /// 字符数按 UTF-16 code unit 口径（对齐 Kotlin String.length；Swift .count 是
    /// grapheme cluster 口径，分解重音 é(e+U+0301) 会少数一半，🟡-6）。
    static func isDocumentText(ocrText: String?, pixelArea: Int64?) -> Bool {
        let chars = Int64(ocrText?.utf16.count ?? 0)
        if chars == 0 { return false }
        if let area = pixelArea, area > 0 {
            return chars * 1_000_000 > area * OrganizeThresholds.ocrDensityPerMegapixel
        }
        return chars > OrganizeThresholds.ocrDensityFallbackChars
    }

    /// labels 命中文档关键词：中文子串 + 英文整词双通道。
    static func labelsIndicateDocument(_ labels: String) -> Bool {
        let lower = labels.lowercased()
        if documentLabelKeywordsZH.contains(where: { lower.contains($0) }) { return true }
        let nsRange = NSRange(lower.startIndex..., in: lower)
        let tokens = Set(labelTokenRegex.matches(in: lower, range: nsRange).map { match in
            String(lower[Range(match.range, in: lower)!])
        })
        return documentLabelKeywordsEN.contains { keyword in
            tokens.contains(keyword) || tokens.contains(keyword + "s")
        }
    }
}
