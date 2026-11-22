package com.mamba.picme.domain.organize

import java.util.Locale

/**
 * 截图内容重要度分级（Agent 清理 M1 Task 7.6，用户裁定 2026-11-22）：
 * 银行/医疗/政务截图保护不轻易删；外卖/电商截图低价值。
 *
 * 双信号：截图文件名尾段包名（MIUI `Screenshot_YYYY-..._<package>.jpg`，实测含
 * `-edit_<package>` 编辑变体）为主信号；[OrganizeItem.ocrText] 关键词为兜底信号
 * （无包名文件名形态的截图）。两信号冲突时**保护档优先**（保守偏置，与 ValueGuard
 * 「宁可不预选不可误删」一致）。VLM 内容理解留 M2+。纯函数，JVM 可测。
 */
sealed interface ScreenshotImportance {
    /** 命中信号原文（归一化包名或 OCR 关键词）；[Neutral] 无信号。 */
    val matchedSignal: String?

    /** 金融（银行/支付/券商）。 */
    data class ProtectedFinance(override val matchedSignal: String) : ScreenshotImportance

    /** 医疗健康（挂号/问诊/病历）。 */
    data class ProtectedHealth(override val matchedSignal: String) : ScreenshotImportance

    /** 政务（交管/证照）。 */
    data class ProtectedGov(override val matchedSignal: String) : ScreenshotImportance

    /** 低价值（外卖/电商/本地生活）。 */
    data class LowValue(override val matchedSignal: String) : ScreenshotImportance

    /** 无信号：非截图、或包名与 OCR 均未命中。 */
    data object Neutral : ScreenshotImportance {
        override val matchedSignal: String? get() = null
    }
}

/** 保护档（三档 PROTECTED_*）：ValueGuard → SENSITIVE_SCREENSHOT；StorageStory stale 口径剔除。 */
val ScreenshotImportance.isProtectedTier: Boolean
    get() = this is ScreenshotImportance.ProtectedFinance ||
        this is ScreenshotImportance.ProtectedHealth ||
        this is ScreenshotImportance.ProtectedGov

/**
 * 单截图重要度分级。只对截图生效（[appSourceOf] == [AppSource.SCREENSHOTS]），
 * 非截图恒 [ScreenshotImportance.Neutral]（避免对相机照片误解析文件名）。
 */
fun screenshotImportanceOf(item: OrganizeItem): ScreenshotImportance {
    if (appSourceOf(item.relativePath) != AppSource.SCREENSHOTS) return ScreenshotImportance.Neutral
    val byPackage = parseScreenshotPackage(item.displayName)?.let { matched -> matchPackage(matched) }
    if (byPackage != null && byPackage.isProtectedTier) return byPackage
    val byOcr = matchOcrKeywords(item.ocrText)
    // 冲突保护优先：包名低价值 + OCR 保护档 → 保护档
    if (byOcr != null && byOcr.isProtectedTier) return byOcr
    return byPackage ?: byOcr ?: ScreenshotImportance.Neutral
}

/** 合法包名形状：字母开头标识符 + 至少一段 `.` 分隔（如 `cn.futu.trader`、`cmb.pb`）。 */
private val PACKAGE_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

/**
 * 截图文件名尾段包名解析：去扩展名后取最后一个 `_` 段（`-edit_` 编辑变体的
 * `-edit_` 前缀被 `_` 分隔自然切掉）。尾段非包名形状（纯序号/时间戳等）返回 null，
 * 由 OCR 关键词兜底。null / 空文件名返回 null。
 */
internal fun parseScreenshotPackage(fileName: String?): String? {
    if (fileName.isNullOrBlank()) return null
    val lastSegment = fileName.substringBeforeLast('.').substringAfterLast('_')
    return lastSegment.takeIf { segment -> PACKAGE_NAME_REGEX.matches(segment) }
}

/**
 * 包名词表（小写**前缀**匹配，如 `com.icbc` 命中 `com.icbc.mobile`；词表校准只改这里）。
 * 词条要求特异性：宁可少收让位 OCR 兜底，不可错收误保护/误降档。
 */
private val FINANCE_PACKAGES = listOf(
    "com.icbc",                    // 工商银行（com.icbc.mobile 等）
    "com.chinamworld.main",        // 建设银行
    "com.android.bankabc",         // 农业银行
    "com.chinamworld.bocmbci",     // 中国银行
    "cmb.pb",                      // 招商银行
    "com.cmbchina",                // 招商银行系（掌上生活等）
    "com.bankcomm",                // 交通银行
    "com.unionpay",                // 云闪付
    "com.eg.android.alipaygphone", // 支付宝
    "cn.futu.trader",              // 富途牛牛（设备实测）
    "com.hexin.plat.android",      // 同花顺
)

private val HEALTH_PACKAGES = listOf(
    "com.haodf.android",           // 好大夫在线
)

private val GOV_PACKAGES = listOf(
    "com.tmri.app.main",           // 交管 12123
)

private val LOW_VALUE_PACKAGES = listOf(
    "com.sankuai.meituan",         // 美团
    "me.ele.android.eleme",        // 饿了么
    "com.taobao.taobao",           // 淘宝
    "com.tmall.wireless",          // 天猫
    "com.jingdong.app.mall",       // 京东
    "com.xunmeng.pinduoduo",       // 拼多多
    "com.dianping.v1",             // 大众点评
)

/** OCR 兜底关键词（contains 匹配；词表校准只改这里）。 */
private val FINANCE_OCR_KEYWORDS = listOf("银行", "转账", "汇款")
private val HEALTH_OCR_KEYWORDS = listOf("挂号", "病历", "处方", "医保")
private val GOV_OCR_KEYWORDS = listOf("身份证", "护照")
private val LOW_VALUE_OCR_KEYWORDS = listOf("外卖", "满减", "淘宝", "拼多多")

private fun matchPackage(packageName: String): ScreenshotImportance? {
    val normalized = packageName.lowercase(Locale.ROOT)
    return when {
        FINANCE_PACKAGES.any { prefix -> normalized.startsWith(prefix) } ->
            ScreenshotImportance.ProtectedFinance(normalized)
        HEALTH_PACKAGES.any { prefix -> normalized.startsWith(prefix) } ->
            ScreenshotImportance.ProtectedHealth(normalized)
        GOV_PACKAGES.any { prefix -> normalized.startsWith(prefix) } ->
            ScreenshotImportance.ProtectedGov(normalized)
        LOW_VALUE_PACKAGES.any { prefix -> normalized.startsWith(prefix) } ->
            ScreenshotImportance.LowValue(normalized)
        else -> null
    }
}

/** OCR 关键词匹配：档间声明序即优先级（多档同时命中取首档）。null/空文本返回 null。 */
private fun matchOcrKeywords(ocrText: String?): ScreenshotImportance? {
    if (ocrText.isNullOrEmpty()) return null
    FINANCE_OCR_KEYWORDS.firstOrNull { keyword -> ocrText.contains(keyword) }
        ?.let { keyword -> return ScreenshotImportance.ProtectedFinance(keyword) }
    HEALTH_OCR_KEYWORDS.firstOrNull { keyword -> ocrText.contains(keyword) }
        ?.let { keyword -> return ScreenshotImportance.ProtectedHealth(keyword) }
    GOV_OCR_KEYWORDS.firstOrNull { keyword -> ocrText.contains(keyword) }
        ?.let { keyword -> return ScreenshotImportance.ProtectedGov(keyword) }
    LOW_VALUE_OCR_KEYWORDS.firstOrNull { keyword -> ocrText.contains(keyword) }
        ?.let { keyword -> return ScreenshotImportance.LowValue(keyword) }
    return null
}
