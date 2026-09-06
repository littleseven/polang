package com.mamba.picme.features.gallery.dedup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.organize.CategoryBoard
import com.mamba.picme.domain.organize.OrganizeBoard
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.SignalCoverage

/** 品牌渐变（青玉）：hub 大数字 / 主按钮同源（与 tagcontrol 设计稿同一口径）。 */
private val orgBrandGradient: Brush
    get() = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
    )

/**
 * 整理中心 hub（F1，Pager 页 1 的 Config 态）：Hero 可释放估算（HIGH 置信非保护去重并集）→
 * Quick tidy up 主按钮 → 类目卡列表（board 恒 6 卡，按 highBytes 建议优先级降序）。
 * 「重复与相似照片」卡固定渲染为可展开入口（展开 = 原去重 Config：尺度勾选 + 保留规则 +
 * 开始扫描），原功能零回归；board 的 DUPLICATES 卡由该卡承接（meta 行同源），列表内不重复渲染。
 *
 * 渲染契约（Task 7 审查修正）：零命中且信号 READY 的类目不渲染；NEEDS_SCAN 渲染半透明
 * 引导卡（点击经 onOpenScan 切 SCAN tab）；board.categories 为空 = 看板未加载（stateIn
 * 初值），此时 Hero 与类目卡不渲染，避免 0B 假数据。
 *
 * 布局结构自上而下：
 * Hero 卡 → 渐变主按钮 → Categories 分组标题 → 重复卡（可展开）→ 类目卡 → 隐私 caption。
 */
@Composable
fun DedupHubContent(
    config: DedupScanConfig,
    policy: KeepPolicy,
    board: OrganizeBoard,
    onQuickTidy: () -> Unit,
    onOpenCategory: (OrganizeCategory) -> Unit,
    onOpenScan: () -> Unit,
    onStartScan: (DedupScanConfig) -> Unit,
    onOpenKeepRules: () -> Unit,
) {
    var dedupExpanded by rememberSaveable { mutableStateOf(false) }
    // 看板未加载（stateIn 初值空列表）：Hero 与类目卡不渲染，防 0B 假 Hero
    val boardLoaded = board.categories.isNotEmpty()
    val duplicatesCard = board.categories.firstOrNull { card ->
        card.category == OrganizeCategory.DUPLICATES
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Config 态无 bottomBar（Scaffold Unit）：滚动内容自行让出底部虚拟导航栏
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (boardLoaded) {
            OrgHeroCard(board = board)
        }

        // Quick tidy up 渐变主按钮（F2 路由未点亮，回调当前为空占位）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(orgBrandGradient)
                .clickable(onClick = onQuickTidy),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.org_quick_tidy),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Text(
            text = stringResource(R.string.org_categories),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 「重复与相似照片」卡固定渲染：收起 = 单行摘要卡；展开 = 原去重 Config 三件套
        DedupCategoryCard(
            expanded = dedupExpanded,
            onToggle = { dedupExpanded = !dedupExpanded },
            board = duplicatesCard,
        )
        if (dedupExpanded) {
            DedupScanOptions(
                config = config,
                policy = policy,
                onStartScan = onStartScan,
                onOpenKeepRules = onOpenKeepRules,
            )
        }

        if (boardLoaded) {
            board.categories.forEach { card ->
                // DUPLICATES 由上方可展开卡承接，列表内不重复渲染
                if (card.category == OrganizeCategory.DUPLICATES) return@forEach
                // 零命中且信号已就绪的类目不渲染（已扫描无内容）；NEEDS_SCAN 渲染引导卡
                if (card.totalCount == 0 && card.coverage == SignalCoverage.READY) return@forEach
                OrganizeCategoryCard(
                    card = card,
                    onClick = {
                        if (card.coverage == SignalCoverage.NEEDS_SCAN) {
                            onOpenScan()
                        } else {
                            onOpenCategory(card.category)
                        }
                    }
                )
            }
        }

        Text(
            text = stringResource(R.string.org_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Hero 卡：label + 品牌渐变大数字（HIGH 置信非保护去重并集）+ 待确认副行 + 有内容的覆盖类目数。 */
@Composable
private fun OrgHeroCard(board: OrganizeBoard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.lg,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.org_hero_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatBytes(board.heroReclaimBytes),
                style = TextStyle(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    brush = orgBrandGradient
                )
            )
            Text(
                // board 恒 6 卡后 size 恒为 6 语义失真；口径改为有内容的类目数
                // （原 +1 是固定重复卡的旧口径，v2 重复卡已并入 board，不再 +1）
                text = stringResource(
                    R.string.org_hero_across,
                    board.categories.count { card -> card.totalCount > 0 }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (board.heroReviewCount > 0) {
                Text(
                    text = stringResource(R.string.org_hero_review, board.heroReviewCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 「重复与相似照片」单行卡（64dp 紧凑行）：图标块 + 标题/摘要 + chevron；点击展开/收起。
 * 摘要行同源 board 的 DUPLICATES 卡：已有扫描结果时显示真实统计，否则沿用「从未扫描」口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupCategoryCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    board: CategoryBoard?,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OrgCategoryIconBlock(icon = organizeCategoryIcon(OrganizeCategory.DUPLICATES))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.org_cat_duplicates),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // dedup 摘要：board 已有重复组统计时显示真实口径，否则沿用「从未扫描」
                Text(
                    text = if (board != null && board.totalCount > 0) {
                        stringResource(
                            R.string.org_cat_meta,
                            board.totalCount,
                            formatBytes(board.totalBytes)
                        )
                    } else {
                        stringResource(R.string.dedup_never_scanned)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Rounded.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 类目卡 v2：图标块 + 标题 + meta 行 + 置信度徽标行（● 高置信 / ○ 需确认）+ 4 缩略图条 + chevron。
 * NEEDS_SCAN 引导态：半透明 + 「需先扫描」徽标，点击跳 SCAN tab（修复 v1 类目静默消失）。
 * material3 Card 无 alpha 形参，半透明用 Modifier.alpha 兜底。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizeCategoryCard(
    card: CategoryBoard,
    onClick: () -> Unit,
) {
    val needsScan = card.coverage == SignalCoverage.NEEDS_SCAN
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (needsScan) 0.6f else 1f),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OrgCategoryIconBlock(icon = organizeCategoryIcon(card.category))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(organizeCategoryLabelRes(card.category)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (needsScan) {
                    Text(
                        text = stringResource(R.string.org_needs_scan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.org_cat_meta,
                            card.totalCount,
                            formatBytes(card.totalBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 置信度徽标行：高置信（primary 实心点）+ 需确认（空心点）+ 保护数
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConfidenceDot(filled = true)
                        Text(
                            text = stringResource(R.string.org_confidence_high, card.highCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (card.reviewCount > 0) {
                            ConfidenceDot(filled = false)
                            Text(
                                text = stringResource(R.string.org_confidence_review, card.reviewCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (card.protectedCount > 0) {
                            Text(
                                text = stringResource(R.string.org_protected_count, card.protectedCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (!needsScan) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        card.previewUris.forEach { uri -> OrgPreviewThumb(uri = uri) }
                    }
                    if (card.highBytes > 0) {
                        Text(
                            text = stringResource(R.string.org_cat_reclaim, formatBytes(card.highBytes)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 置信度小圆点：filled=高置信（primary 实色），否则描边空心。 */
@Composable
private fun ConfidenceDot(filled: Boolean) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(
                if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
            )
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
    )
}

/** 类目图标块：surfaceVariant 圆角方块 + Material Icon。 */
@Composable
private fun OrgCategoryIconBlock(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 类目卡 30dp 预览缩略图（Coil 铁律：size(360) + crossfade(false) 防 recycled bitmap 崩溃）。 */
@Composable
private fun OrgPreviewThumb(uri: String) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(uri)
            .size(360)
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

// ---------- 重复卡展开态：原去重 Config 三件套（尺度勾选 + 保留规则 + 开始扫描） ----------

/** 原 DedupConfigContent 去 Hero/隐私 caption 后的扫描配置区，功能与文案零回归。 */
@Composable
private fun DedupScanOptions(
    config: DedupScanConfig,
    policy: KeepPolicy,
    onStartScan: (DedupScanConfig) -> Unit,
    onOpenKeepRules: () -> Unit,
) {
    // Set<DedupLevel> 非 Bundle 可存类型，沿用原 Config 的 remember（进程重建回默认勾选）
    var selectedLevels by remember { mutableStateOf(config.levels) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.dedup_scale_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DedupLevel.entries.forEach { level ->
            DedupLevelRow(
                level = level,
                selected = level in selectedLevels,
                onToggle = { checked ->
                    selectedLevels = if (checked) {
                        selectedLevels + level
                    } else {
                        selectedLevels - level
                    }
                }
            )
        }

        // 保留规则入口（打开规则弹层，当前值为 VM 级 policy）
        Card(
            onClick = onOpenKeepRules,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dedup_keep_rule),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(keepPolicyLabelRes(policy)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = { onStartScan(config.copy(levels = selectedLevels)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedLevels.isNotEmpty()
        ) {
            Text(stringResource(R.string.dedup_start_scan))
        }
    }
}

/** 尺度勾选行（自原 DedupConfigContent 迁入，视觉/交互零改动）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupLevelRow(
    level: DedupLevel,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        onClick = { onToggle(!selected) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = dedupLevelIcon(level),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(dedupLevelLabelRes(level)),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(dedupLevelDescRes(level)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(checked = selected, onCheckedChange = onToggle)
        }
    }
}

// ---------- 资源映射 ----------

private fun organizeCategoryIcon(category: OrganizeCategory): ImageVector = when (category) {
    OrganizeCategory.DUPLICATES -> Icons.Outlined.BurstMode
    OrganizeCategory.SCREEN_CONTENT -> Icons.Outlined.ScreenshotMonitor
    OrganizeCategory.DOCUMENTS -> Icons.Outlined.Description
    OrganizeCategory.LOW_QUALITY_PORTRAITS -> Icons.Outlined.Face
    OrganizeCategory.LOW_QUALITY_PHOTOS -> Icons.Outlined.BlurOn
    OrganizeCategory.LARGE_FILES -> Icons.Outlined.Videocam
}

private fun organizeCategoryLabelRes(category: OrganizeCategory): Int = when (category) {
    OrganizeCategory.DUPLICATES -> R.string.org_cat_duplicates
    OrganizeCategory.SCREEN_CONTENT -> R.string.org_cat_screen_content
    OrganizeCategory.DOCUMENTS -> R.string.org_cat_documents
    OrganizeCategory.LOW_QUALITY_PORTRAITS -> R.string.org_cat_portraits
    OrganizeCategory.LOW_QUALITY_PHOTOS -> R.string.org_cat_blurry
    OrganizeCategory.LARGE_FILES -> R.string.org_cat_large_files
}
