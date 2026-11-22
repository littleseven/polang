package com.mamba.picme.features.gallery.organize

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.ui.graphics.vector.ImageVector
import com.mamba.picme.R
import com.mamba.picme.domain.organize.AppSource
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.ProtectReason

/**
 * 整理中心 v2 类目 → 资源映射（hub 类目卡 + 详情页顶栏同源共享）。
 * 键名即 `organize_category/{category}` 路由段，改枚举名需全量收口。
 */
fun organizeCategoryLabelRes(category: OrganizeCategory): Int = when (category) {
    OrganizeCategory.DUPLICATES -> R.string.org_cat_duplicates
    OrganizeCategory.SCREEN_CONTENT -> R.string.org_cat_screen_content
    OrganizeCategory.DOCUMENTS -> R.string.org_cat_documents
    OrganizeCategory.LOW_QUALITY_PORTRAITS -> R.string.org_cat_portraits
    OrganizeCategory.LOW_QUALITY_PHOTOS -> R.string.org_cat_blurry
    OrganizeCategory.LARGE_FILES -> R.string.org_cat_large_files
}

/** 整理中心 v2 类目 → 图标映射（hub 类目卡使用；详情页暂仅用文案映射）。 */
fun organizeCategoryIcon(category: OrganizeCategory): ImageVector = when (category) {
    OrganizeCategory.DUPLICATES -> Icons.Outlined.BurstMode
    OrganizeCategory.SCREEN_CONTENT -> Icons.Outlined.ScreenshotMonitor
    OrganizeCategory.DOCUMENTS -> Icons.Outlined.Description
    OrganizeCategory.LOW_QUALITY_PORTRAITS -> Icons.Outlined.Face
    OrganizeCategory.LOW_QUALITY_PHOTOS -> Icons.Outlined.BlurOn
    OrganizeCategory.LARGE_FILES -> Icons.Outlined.Videocam
}

/**
 * 来源 App → 文案映射（hub 来源账 + 来源详情页顶栏同源共享；Task 7.5）。
 * 键名不含路由语义（AppSource 枚举名才是 `organize_source/{source}` 路由段）。
 */
fun appSourceLabelRes(source: AppSource): Int = when (source) {
    AppSource.CAMERA -> R.string.org_source_camera
    AppSource.WECHAT -> R.string.org_source_wechat
    AppSource.QQ -> R.string.org_source_qq
    AppSource.WHATSAPP -> R.string.org_source_whatsapp
    AppSource.TELEGRAM -> R.string.org_source_telegram
    AppSource.INSTAGRAM -> R.string.org_source_instagram
    AppSource.MESSENGER -> R.string.org_source_messenger
    AppSource.DOWNLOAD -> R.string.org_source_download
    AppSource.SCREENSHOTS -> R.string.org_source_screenshots
    AppSource.OTHER -> R.string.org_source_other
}

/** 来源 App → 图标映射（hub 来源列表行使用；IM 六家共用会话气泡语义图标）。 */
fun appSourceIcon(source: AppSource): ImageVector = when (source) {
    AppSource.CAMERA -> Icons.Rounded.PhotoCamera
    AppSource.WECHAT,
    AppSource.QQ,
    AppSource.WHATSAPP,
    AppSource.TELEGRAM,
    AppSource.INSTAGRAM,
    AppSource.MESSENGER,
    -> Icons.Rounded.Forum
    AppSource.DOWNLOAD -> Icons.Rounded.Download
    AppSource.SCREENSHOTS -> Icons.Outlined.ScreenshotMonitor
    AppSource.OTHER -> Icons.Rounded.Apps
}

/** 保护原因 → 文案映射（详情页保护区 label；Task 7.6 五语同步）。 */
fun protectReasonLabelRes(reason: ProtectReason): Int = when (reason) {
    ProtectReason.OLD_PHOTO -> R.string.org_protect_old_photo
    ProtectReason.SCARCE_PERSON -> R.string.org_protect_scarce_person
    ProtectReason.USER_ENGAGED -> R.string.org_protect_user_engaged
    ProtectReason.SENSITIVE_SCREENSHOT -> R.string.org_protect_sensitive_screenshot
}
