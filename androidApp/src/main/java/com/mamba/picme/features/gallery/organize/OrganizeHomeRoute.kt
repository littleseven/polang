package com.mamba.picme.features.gallery.organize

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.mamba.picme.features.gallery.dedup.DedupViewModel

/**
 * 整理 + 扫描合并页（2026-09-06，主页面 Pager 页 1）：顶部居中胶囊分段开关切换
 * 「整理」（[DedupHomeRoute]，去重 2.0 四态 hub）与「扫描」（[TagGenerationControlScreen]，
 * TAG 3-Pass 控制）。原 `tag_control` NavHost 路由已并入本页；悬浮底栏「相册整理」
 * 「TAG 扫描」两图标与设置入口均落到本页对应 Tab（外部经 organizeTabRequest 驱动）。
 *
 * - 单根 Box：HorizontalPager 会把 page 多根节点沿主轴平铺到屏外（Pager 页单根铁律）
 * - 子页 embedded 模式：两子页保留各自顶栏（标题/取消扫描/保留规则等 actions 零丢失），
 *   仅关闭内置状态栏避让，由本页胶囊条统一避让
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
    onNavigateBack: () -> Unit,
    onQuickTidy: () -> Unit,
    onOpenCategory: (OrganizeCategory) -> Unit,
    onNavigateToTagViewer: () -> Unit,
) {
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
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    OrganizeTab.ORGANIZE -> DedupHomeRoute(
                        viewModel = dedupViewModel,
                        onNavigateBack = onNavigateBack,
                        onQuickTidy = onQuickTidy,
                        onOpenCategory = onOpenCategory,
                        embedded = true,
                    )
                    OrganizeTab.SCAN -> TagGenerationControlScreen(
                        onNavigateBack = onNavigateBack,
                        onNavigateToTagViewer = onNavigateToTagViewer,
                        useOpencl = useOpencl,
                        onUseOpenclChange = onUseOpenclChange,
                        embedded = true,
                    )
                }
            }
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
