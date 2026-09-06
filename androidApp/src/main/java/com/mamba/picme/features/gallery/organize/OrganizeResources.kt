package com.mamba.picme.features.gallery.organize

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.mamba.picme.R
import com.mamba.picme.domain.organize.OrganizeCategory

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
