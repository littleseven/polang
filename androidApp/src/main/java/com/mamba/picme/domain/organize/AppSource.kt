package com.mamba.picme.domain.organize

/**
 * 媒体来源 App（Agent 清理 M1）：按存储路径关键词判定产图 App，
 * 供存储空间叙事 / 清理预分析按来源聚合。Task 3（StorageStory）消费 `appSourceOf` 与 [defaultTier]。
 */
enum class AppSource {
    CAMERA,      // 系统相机（DCIM）
    WECHAT,      // 微信（legacy 私有目录 + scoped storage 双形态）
    QQ,          // QQ
    WHATSAPP,    // WhatsApp
    TELEGRAM,    // Telegram
    INSTAGRAM,   // Instagram
    MESSENGER,   // Facebook Messenger
    DOWNLOAD,    // 下载目录
    SCREENSHOTS, // 截图（含国产 OEM「截屏」目录）
    OTHER,       // 未识别来源
}

/** 来源默认重要度档：低价值来源进清理候选叙事，相机档默认保护。 */
enum class SourceTier { PROTECTED, LOW_VALUE, NEUTRAL }

/** 来源 → 默认重要度档（CAMERA→PROTECTED；IM 六家→LOW_VALUE；其余→NEUTRAL）。 */
val AppSource.defaultTier: SourceTier
    get() = when (this) {
        AppSource.CAMERA -> SourceTier.PROTECTED
        AppSource.WECHAT,
        AppSource.QQ,
        AppSource.WHATSAPP,
        AppSource.TELEGRAM,
        AppSource.INSTAGRAM,
        AppSource.MESSENGER,
        -> SourceTier.LOW_VALUE
        AppSource.DOWNLOAD,
        AppSource.SCREENSHOTS,
        AppSource.OTHER,
        -> SourceTier.NEUTRAL
    }

/**
 * 相对路径 → 来源 App（大小写不敏感；null / 未识别 → OTHER）。
 *
 * 匹配顺序即裁定优先级（先命中先裁定）：截图先于 DCIM——`DCIM/Screenshots` 判 SCREENSHOTS
 * 而非 CAMERA。路径整体 upper-case 后 contains 匹配，故 API<29 场景直接喂绝对 DATA 路径
 * （`/storage/emulated/0/...`）同样命中。纯函数，JVM 可测。
 */
fun appSourceOf(relativePath: String?): AppSource {
    if (relativePath == null) return AppSource.OTHER
    val path = relativePath.uppercase()
    return when {
        "SCREENSHOTS" in path || "截屏" in path -> AppSource.SCREENSHOTS
        "DCIM" in path -> AppSource.CAMERA
        "COM.TENCENT.MM" in path || "WECHAT" in path || "MICROMSG" in path -> AppSource.WECHAT
        "COM.TENCENT.MOBILEQQ" in path || "QQ" in path -> AppSource.QQ
        "COM.WHATSAPP" in path -> AppSource.WHATSAPP
        "TELEGRAM" in path -> AppSource.TELEGRAM
        "INSTAGRAM" in path -> AppSource.INSTAGRAM
        "COM.FACEBOOK.ORCA" in path || "MESSENGER" in path -> AppSource.MESSENGER
        "DOWNLOAD" in path -> AppSource.DOWNLOAD
        else -> AppSource.OTHER
    }
}
