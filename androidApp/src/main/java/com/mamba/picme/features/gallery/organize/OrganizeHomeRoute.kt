package com.mamba.picme.features.gallery.organize

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.features.gallery.components.TagGenerationControlScreen
import com.mamba.picme.features.gallery.dedup.DedupHomeRoute
import com.mamba.picme.features.gallery.dedup.DedupUiState
import com.mamba.picme.features.gallery.dedup.DedupViewModel
import com.mamba.picme.features.main.MAIN_PAGE_DEDUP
import com.mamba.picme.features.main.MainFloatingBottomBar

/**
 * 整理 + 扫描合并页（2026-09-06，主页面 Pager 页 1）：顶部居中胶囊分段开关切换
 * 「整理」（[DedupHomeRoute]，去重 2.0 四态 hub）与「扫描」（[TagGenerationControlScreen]，
 * TAG 3-Pass 控制）。原 `tag_control` NavHost 路由已并入本页；外部入口（设置页等）经
 * organizeTabRequest 预选 Tab。2026-09-06 导航统一：升根页挂悬浮底 bar（整理项高亮，
 * 五项与 Pager 页序 1:1）。
 *
 * - 单根 Box：HorizontalPager 会把 page 多根节点沿主轴平铺到屏外（Pager 页单根铁律）
 * - 子页 embedded 模式：两子页保留各自顶栏（标题/取消扫描/保留规则等 actions 零丢失），
 *   仅关闭内置状态栏避让，由本页胶囊条统一避让；根页无返回箭头（DedupTopBar/TagGen
 *   顶栏 navigationIcon 已移除）
 * - bar 可见性门控：Dedup 子页 Scanning/Results/Cleaned 态自带底部 CTA，此时隐藏悬浮
 *   bar 防遮挡（对齐相册页详情态隐藏先例）；hub（Config）态与 SCAN tab 显示
 * - 子页条件组合：SCAN tab 的进页副作用（启动前台 Service + 刷新统计）只在切到该 tab
 *   时执行，避免 Pager 常驻组合导致 app 启动即拉起扫描服务；切 tab 销毁子页组合，状态由
 *   Activity 级 DedupViewModel / Service 会话态承载，重组后恢复
 */
@Suppress("LongParameterList")
@Composable
fun OrganizeHomeRoute(
    dedupViewModel: DedupViewModel,
    selectedTab: OrganizeTab,
    onSelectTab: (OrganizeTab) -> Unit,
    useOpencl: Boolean,
    onUseOpenclChange: (Boolean) -> Unit,
    /** Dedup 扫描态「后台运行」按钮离页回相册（根页无返回箭头，此回调仅服务该按钮语义） */
    onLeaveToGallery: () -> Unit,
    onQuickTidy: () -> Unit,
    onOpenCategory: (OrganizeCategory) -> Unit,
    onNavigateToTagViewer: () -> Unit,
    /** 底 bar 页切换出口（整理项=本页，高亮且点击空操作） */
    onSwitchMainPage: (Int) -> Unit,
) {
    // bar 可见性：Dedup 自带底部 CTA 的三态隐藏；hub（Config）与 SCAN tab 显示
    val dedupState by dedupViewModel.uiState.collectAsState()
    val showBottomBar = selectedTab == OrganizeTab.SCAN || dedupState is DedupUiState.Config

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OrganizeTabSwitch(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    // bar 显示时内容底部避让（对齐 Memory 页 96dp 预留）；CTA 态 bar 隐藏时归零
                    .padding(bottom = if (showBottomBar) 96.dp else 0.dp),
            ) {
                when (selectedTab) {
                    OrganizeTab.ORGANIZE -> DedupHomeRoute(
                        viewModel = dedupViewModel,
                        onNavigateBack = onLeaveToGallery,
                        onQuickTidy = onQuickTidy,
                        onOpenCategory = onOpenCategory,
                        embedded = true,
                    )
                    OrganizeTab.SCAN -> TagGenerationControlScreen(
                        onNavigateToTagViewer = onNavigateToTagViewer,
                        useOpencl = useOpencl,
                        onUseOpenclChange = onUseOpenclChange,
                        embedded = true,
                    )
                }
            }
        }

        // 悬浮底 bar：整理项高亮（门控见 showBottomBar）
        if (showBottomBar) {
            MainFloatingBottomBar(
                selectedMainPage = MAIN_PAGE_DEDUP,
                onSwitchPage = onSwitchMainPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
            )
        }
    }
}

/** 顶部居中胶囊分段开关（整理/扫描），样式同回忆详情页「精选/全部」胶囊。 */
@Composable
private fun OrganizeTabSwitch(
    selectedTab: OrganizeTab,
    onSelectTab: (OrganizeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            listOf(
                OrganizeTab.ORGANIZE to R.string.org_title,
                OrganizeTab.SCAN to R.string.tag_section_scan,
            ).forEach { pair ->
                val isSelected = selectedTab == pair.first
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                        )
                        .clickable { onSelectTab(pair.first) }
                        .semantics {
                            role = Role.Button
                            selected = isSelected
                        }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(pair.second),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
