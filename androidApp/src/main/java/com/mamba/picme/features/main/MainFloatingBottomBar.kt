package com.mamba.picme.features.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.features.common.components.FloatingBottomTab
import com.mamba.picme.features.common.components.FloatingBottomTabItem

/**
 * 主页面悬浮底 bar（2026-09-06 导航统一）：五项与 Pager 页序 1:1——
 * 相册/整理/聊天/人物/回忆。渲染于四个根页（相册/整理/人物/回忆，各自高亮自身项）；
 * 聊天页沉浸式不渲染。打标图标已移除（TAG 扫描并入整理页 SCAN tab，
 * 外部深链仍走 organizeTabRequest）。
 *
 * - 表驱动：icon + contentDescription + 页索引；选中项高亮且点击空操作
 * - 目标为整理页（[MAIN_PAGE_DEDUP]）时由调用方包装回调预选 ORGANIZE tab（见 MainPagerHost）
 */
@Composable
fun MainFloatingBottomBar(
    selectedMainPage: Int,
    onSwitchPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingBottomTab(
        items = listOf(
            bottomBarItem(
                icon = Icons.Outlined.PhotoLibrary,
                labelRes = R.string.tab_gallery,
                page = MAIN_PAGE_GALLERY,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.CleaningServices,
                labelRes = R.string.gallery_cleanup,
                page = MAIN_PAGE_DEDUP,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.ChatBubble,
                labelRes = R.string.chat,
                page = MAIN_PAGE_CHAT,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.AccountCircle,
                labelRes = R.string.gallery_people_entry,
                page = MAIN_PAGE_PEOPLE,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
            bottomBarItem(
                icon = Icons.Outlined.AutoAwesome,
                labelRes = R.string.tab_memories,
                page = MAIN_PAGE_MEMORY,
                selectedMainPage = selectedMainPage,
                onSwitchPage = onSwitchPage,
            ),
        ),
        modifier = modifier,
    )
}

/** 单项构造：选中项高亮、点击空操作；未选中项点击回调切页。 */
@Composable
private fun bottomBarItem(
    icon: ImageVector,
    labelRes: Int,
    page: Int,
    selectedMainPage: Int,
    onSwitchPage: (Int) -> Unit,
): FloatingBottomTabItem {
    val isSelected = selectedMainPage == page
    val label = stringResource(labelRes)
    return FloatingBottomTabItem(
        icon = icon,
        label = label,
        contentDescription = label,
        selected = isSelected,
        onClick = { if (!isSelected) onSwitchPage(page) },
    )
}
