package com.mamba.picme.features.gallery.dedup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupMember
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.VersionRole
import com.mamba.picme.domain.dedup.keepReasonOf
import java.text.DateFormat
import java.util.Date

private val HintOrange = Color(0xFFFF9800)

/**
 * 组详情全屏页（2026-08-27 由底部半屏弹层改全屏，对齐 Ardot dedup/group_detail 整屏帧）：
 * 顶栏（返回 + 标题 + 保留规则入口）+ 组 meta + 成员两列对比（缩略图 + 大小/时间 +
 * 「保留这张」radio）+ EDITED 提示条 + 底部固定确认按钮。点「保留这张」改选后立即收起
 * 回结果列表（2026-09-05 交互修正，组卡片「已手动选择」即时反馈），不再停留待二次确认。
 * Scanning 态为只读（setKeep 仅 Results 生效）：隐藏 radio 与 CTA，
 * 不假装可交互。点缩略图经 [onPreview]（成员下标）进全屏对比预览，比较后再改选。
 */
@Composable
fun DedupGroupDetailPage(
    groupId: String,
    viewModel: DedupViewModel,
    onDismiss: () -> Unit,
    onPreview: (Int) -> Unit = {},
    onOpenKeepRules: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val policy by viewModel.policy.collectAsState()
    val group = remember(uiState) { viewModel.getGroup(groupId) }
    val editable = uiState is DedupUiState.Results

    // 全屏页自身消费一次返回键；对比预览层组合在其后，预览打开时预览的 BackHandler 优先
    BackHandler(onBack = onDismiss)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (group == null) {
            // 组已消失（状态机已离开 Results/Scanning）：直接收起
            LaunchedEffect(Unit) { onDismiss() }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                DedupGroupDetailTopBar(
                    onBack = onDismiss,
                    onOpenKeepRules = onOpenKeepRules,
                )
                DedupGroupDetailContent(
                    group = group,
                    editable = editable,
                    policy = policy,
                    // 改选保留项后直接收起详情页回结果列表（一次点选完成改选，
                    // 组卡片「已手动选择」即时反馈）；删除仍由详情 CTA / 底部批量 CTA 承担
                    onKeep = { uri ->
                        viewModel.setKeep(groupId, uri)
                        onDismiss()
                    },
                    onPreview = onPreview,
                    // 「保留所选 · 删除其余 N 张」逐组立即删除（deleteUris 为空时 no-op，
                    // 仅确认选择）；先起删除流再收详情页，授权弹窗落在结果页之上
                    onConfirm = {
                        viewModel.deleteGroup(groupId)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun DedupGroupDetailTopBar(
    onBack: () -> Unit,
    onOpenKeepRules: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text = stringResource(R.string.dedup_detail_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )
        // 保留规则入口（对齐设计稿 group_detail 顶栏右动作；KeepRulesSheet 仍为底部弹层）
        TextButton(onClick = onOpenKeepRules) {
            Text(stringResource(R.string.dedup_keep_rule))
        }
    }
}

@Composable
private fun DedupGroupDetailContent(
    group: DedupGroup,
    editable: Boolean,
    policy: KeepPolicy,
    onKeep: (String) -> Unit,
    onPreview: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LevelBadge(level = group.level)
            Text(
                text = dedupGroupMetaText(group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 推荐理由行仅 Results 态渲染：Scanning 态建组固定按 BEST_QUALITY 排序，
        // 用户 policy 为 LATEST/ORIGINAL 时理由会与实际 keep 矛盾
        if (editable) {
            Text(
                text = stringResource(keepReasonLabelRes(keepReasonOf(group, policy))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }

        if (group.members.any { member -> member.role == VersionRole.EDITED }) {
            Text(
                text = stringResource(R.string.dedup_hint_edited),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HintOrange.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = HintOrange,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }

        group.members.chunked(2).forEachIndexed { chunkIndex, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEachIndexed { indexInPair, member ->
                    DedupMemberColumn(
                        member = member,
                        isKept = member.uri == group.keepUri,
                        editable = editable,
                        onKeep = onKeep,
                        onPreview = { onPreview(chunkIndex * 2 + indexInPair) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }

        }
        // 底部固定 CTA（不随内容滚动；对齐设计稿 group_detail 底部按钮位）
        // 必须在外层 Column 内：fillMaxSize Column 之外的兄弟节点在剩余空间为 0 下测量，
        // 塌缩为零高度不可见（表象类似被底部导航栏遮挡）
        if (editable) {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                // 未预选且未改选组 deleteUris 为空：按钮不显示「删除其余 0 张」，
                // 改为「确认本组选择」中性文案（spec §10.4 逐组确认语义）
                Text(
                    if (group.deleteUris.isEmpty()) {
                        stringResource(R.string.dedup_confirm_selection)
                    } else {
                        stringResource(R.string.dedup_confirm_keep, group.deleteUris.size)
                    }
                )
            }
        } else {
            // 只读态（Scanning）无底部 CTA：留出导航栏边距即可
            Spacer(modifier = Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

/** 成员对比列：大图 + 大小/时间行 + 「保留这张」radio；点击任意处即改选保留项，
 *  点缩略图本身则进全屏对比预览（比较后再决策）。只读态无 radio、不可改选但可预览。 */
@Composable
private fun DedupMemberColumn(
    member: DedupMember,
    isKept: Boolean,
    editable: Boolean,
    onKeep: (String) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (editable) Modifier.clickable { onKeep(member.uri) } else Modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DedupThumb(
            uri = member.uri,
            isKept = isKept,
            role = member.role,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            onClick = onPreview
        )
        // DedupMember 无分辨率字段（仅 pixelArea）：统一显示大小 + 本地化日期
        Text(
            text = stringResource(
                R.string.dedup_member_meta,
                formatBytes(member.sizeBytes),
                dateFormat.format(Date(member.captureDate))
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (editable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RadioButton(selected = isKept, onClick = { onKeep(member.uri) })
                Text(
                    text = stringResource(R.string.dedup_keep_this),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 保留规则弹层：四个 policy 单选行 + 说明 + 应用按钮（应用并对 Results 组重算）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepRulesSheet(
    viewModel: DedupViewModel,
    onDismiss: () -> Unit,
) {
    val currentPolicy by viewModel.policy.collectAsState()
    var selected by remember { mutableStateOf(currentPolicy) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dedup_rules_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            KeepPolicy.entries.forEach { policy ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selected = policy }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(keepPolicyLabelRes(policy)),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(keepPolicyDescRes(policy)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RadioButton(
                        selected = selected == policy,
                        onClick = { selected = policy }
                    )
                }
            }
            Text(
                text = stringResource(R.string.dedup_rules_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    viewModel.applyPolicy(selected)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(stringResource(R.string.dedup_apply))
            }
        }
    }
}
