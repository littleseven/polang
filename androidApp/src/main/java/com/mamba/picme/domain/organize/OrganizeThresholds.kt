package com.mamba.picme.domain.organize

/**
 * 整理中心 v2 全部判定阈值（单一事实来源，校准只改这里）。
 * 置信度边界以「×主阈值」的倍数表达（见 ConfidenceGrader）。
 */
object OrganizeThresholds {

    // ── 沿用 v1 ─────────────────────────────────────────────
    /** eDifFIQA 人脸质量分（0~1）低于该值判低质量人像。 */
    const val FACE_QUALITY_LOW = 0.35f

    /** 大文件：视频字节阈值（100 MiB）。 */
    const val LARGE_VIDEO_BYTES = 100L * 1024 * 1024

    /** NIMA 美学分（1~10）低于该值作为 LOW 置信辅助信号（不再单独定类）。 */
    const val AESTHETIC_LOW = 3.5f

    // ── 新增：模糊/曝光（BlurAnalyzer 产出）──────────────────
    /**
     * Laplacian 方差模糊阈值（256px 灰度图口径）：低于判真模糊。
     * 初始值按典型手机实拍分布取 100.0，落地后用 BlurAnalyzerTest 样本校准。
     */
    const val BLUR_VARIANCE_LOW = 100.0f

    /** 平均亮度（0~1）低于判欠曝。 */
    const val EXPOSURE_UNDER = 0.15f

    /** 平均亮度（0~1）高于判过曝。 */
    const val EXPOSURE_OVER = 0.85f

    /** 置信度分级：强命中 = 主阈值 × 该系数（blur/faceQuality 等越低越差型信号）。 */
    const val STRONG_SIGNAL_FACTOR = 0.5f

    // ── 新增：大文件（超分辨率照片）──────────────────────────
    /** 超高像素面积阈值（50 MP）。 */
    const val LARGE_PHOTO_PIXEL_AREA = 50_000_000L

    /** 超分辨率照片大小阈值（20 MB，与像素面积同时满足才判大文件）。 */
    const val LARGE_PHOTO_BYTES = 20L * 1024 * 1024

    // ── 价值保护 ────────────────────────────────────────────
    /** 年代久远：拍摄时间早于 now − 该年数判老照片。 */
    const val OLD_PHOTO_YEARS = 5

    /** 人物稀缺：所属人物聚类照片总数 ≤ 该值判稀缺。 */
    const val PERSON_SCARCE_MAX = 3

    /** OCR 强命中倍数：文字密度 ≥ 主阈值 × 该系数 → HIGH 置信。 */
    const val OCR_STRONG_FACTOR = 2
}

/** 一年毫秒数（365 天，ValueGuard 老照片判定用，避免引入 java.time 依赖）。 */
internal const val YEAR_MILLIS = 365L * 24 * 60 * 60 * 1000
