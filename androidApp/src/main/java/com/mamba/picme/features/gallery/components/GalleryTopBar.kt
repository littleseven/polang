package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.domain.model.GroupingMode
import com.mamba.picme.domain.model.GroupingMode.DATE
import com.mamba.picme.domain.model.GroupingMode.FACE
import com.mamba.picme.domain.model.GroupingMode.LANDSCAPE
import com.mamba.picme.domain.model.GroupingMode.LOCATION
import com.mamba.picme.domain.model.GroupingMode.NONE
import com.mamba.picme.domain.model.GroupingMode.PERSON
import com.mamba.picme.domain.model.GroupingMode.SEXY
import com.mamba.picme.domain.model.GroupingMode.SWIMWEAR
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.service.tag.TagGenerationService

@Composable
fun GalleryTopBar(
    isSelectionMode: Boolean,
    selectedCount: Int,
    groupingMode: GroupingMode,
    onNavigateBack: (() -> Unit)? = null,
    onNavigateToSettings: () -> Unit = {},
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onGroupingModeSelected: (GroupingMode) -> Unit,
    onSearchClick: () -> Unit = {},
    onTagScanClick: () -> Unit = {},
    onToggleScan: () -> Unit = {},
    onNavigateToModelCenter: () -> Unit = {}
) {
    val isScanning by TagGenerationService.isScanning.collectAsState(false)
    AppTopBar(
        title = {
            // 标题左对齐（2026-09-26 弃居中）；无返回键时距左缘即 Row 水平 padding 8dp，
            // 与设计画布正典（title x=8）一致，无需额外补偿
            Text(
                text = if (isSelectionMode) {
                    stringResource(R.string.selected_items, selectedCount)
                } else {
                    stringResource(R.string.gallery)
                }
            )
        },
        navigationIcon = {
            if (isSelectionMode || onNavigateBack != null) {
                AppTopBarNavBack(onClick = {
                    if (isSelectionMode) {
                        onToggleSelectionMode()
                    } else {
                        onNavigateBack?.invoke()
                    }
                })
            }
        },
        actions = {
            if (isSelectionMode) {
                AppTopBarAction(Icons.Outlined.SelectAll, stringResource(R.string.select_all), onSelectAll)
                AppTopBarAction(Icons.Outlined.Share, stringResource(R.string.ocr_share), onShareSelected)
                AppTopBarAction(Icons.Outlined.Delete, stringResource(R.string.delete), onDeleteSelected)
            } else {
                val scanTint = if (isScanning) MaterialTheme.colorScheme.primary else null
                // 模型中心入口（从悬浮入口移入顶栏，置最左）
                AppTopBarAction(
                    icon = Icons.Outlined.CloudDownload,
                    contentDescription = stringResource(R.string.model_center),
                    onClick = onNavigateToModelCenter
                )
                AppTopBarAction(
                    icon = if (isScanning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isScanning) {
                        stringResource(R.string.pause)
                    } else {
                        stringResource(R.string.start_scan)
                    },
                    onClick = onToggleScan,
                    tint = scanTint
                )
                AppTopBarAction(Icons.Outlined.Search, stringResource(R.string.search_photos), onSearchClick)
                GroupingMenu(currentMode = groupingMode, onModeSelected = onGroupingModeSelected)
                AppTopBarAction(Icons.Outlined.Settings, stringResource(R.string.settings), onNavigateToSettings)
            }
        }
    )
}

@Composable
private fun GroupingMenu(
    currentMode: GroupingMode,
    onModeSelected: (GroupingMode) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        AppTopBarAction(
            icon = Icons.AutoMirrored.Outlined.Sort,
            contentDescription = stringResource(R.string.group_by),
            onClick = { showMenu = true }
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            GroupingMode.entries
                .filter { mode -> mode != SWIMWEAR && mode != SEXY }
                .forEach { mode ->
                val label = when (mode) {
                    NONE -> stringResource(R.string.group_none)
                    DATE -> stringResource(R.string.group_date)
                    FACE -> stringResource(R.string.group_face)
                    PERSON -> stringResource(R.string.group_person)
                    LANDSCAPE -> stringResource(R.string.landscape)
                    SWIMWEAR -> stringResource(R.string.swimwear)
                    SEXY -> stringResource(R.string.sexy)
                    LOCATION -> stringResource(R.string.gallery_group_location)
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeSelected(mode)
                        showMenu = false
                    },
                    leadingIcon = {
                        if (currentMode == mode) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    }
                )
            }
        }
    }
}
