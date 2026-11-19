package com.mamba.picme.features.gallery.dedup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.KeepReason
import com.mamba.picme.domain.dedup.VersionRole

private val KeepGreen = Color(0xFF4CAF50)
private val VisualBlue = Color(0xFF2196F3)
private val SceneOrange = Color(0xFFFF9800)

/** 缩略图加载/失败占位，使用主题 surface 色（与 MediaGrid 同一惯例） */
@Composable
private fun dedupThumbPlaceholderPainter(): Painter = ColorPainter(MaterialTheme.colorScheme.surface)

/** 去重级别 badge：圆角 6，彩色 15% 透明底 + 彩色文字 */
@Composable
fun LevelBadge(level: DedupLevel, modifier: Modifier = Modifier) {
    val (color, labelRes) = when (level) {
        DedupLevel.EXACT -> KeepGreen to R.string.dedup_scale_exact
        DedupLevel.VISUAL -> VisualBlue to R.string.dedup_scale_visual
        DedupLevel.SCENE -> SceneOrange to R.string.dedup_scale_scene
    }
    Text(
        text = stringResource(labelRes),
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium
    )
}

/** 内容类型 badge（spec §10.4）：中性描边小胶囊，与彩色级别 badge 区分；GENERAL 不渲染（避免噪音） */
@Composable
fun ContentTypeBadge(contentType: DedupContentType, modifier: Modifier = Modifier) {
    val labelRes = when (contentType) {
        DedupContentType.SCREENSHOT -> R.string.dedup_type_screenshot
        DedupContentType.PORTRAIT -> R.string.dedup_type_portrait
        DedupContentType.DOCUMENT -> R.string.dedup_type_document
        DedupContentType.GENERAL -> return
    }
    Text(
        text = stringResource(labelRes),
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium
    )
}

/** 版本角色 badge：半透明黑底白字小标；UNKNOWN 不渲染 */
@Composable
fun RoleBadge(role: VersionRole, modifier: Modifier = Modifier) {
    val labelRes = when (role) {
        VersionRole.ORIGINAL -> R.string.dedup_role_original
        VersionRole.EDITED -> R.string.dedup_role_edited
        VersionRole.COMPRESSED -> R.string.dedup_role_compressed
        VersionRole.UNKNOWN -> return
    }
    Text(
        text = stringResource(labelRes),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
}

/**
 * 去重缩略图：
 * - 保留项：绿色 2.5dp 描边 + 左上角绿色实心「保留」小标
 * - 待删项：右上角半透明黑圆 + 白色 ×
 * - 左下角叠加 [RoleBadge]
 * - [showMarks]=false（未预选组的卡片预览，spec §10.3）时保留框/✕ 均不渲染
 */
@Composable
fun DedupThumb(
    uri: String,
    isKept: Boolean,
    role: VersionRole,
    modifier: Modifier = Modifier,
    showMarks: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    val markedKept = showMarks && isKept
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (markedKept) Modifier.border(2.5.dp, KeepGreen, shape) else Modifier
            )
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .size(360)
                // 关闭缩略图交叉淡入淡出：避免旧 Bitmap 在动画期间被回收/替换
                // 导致 "Canvas: trying to use a recycled bitmap" 崩溃
                .crossfade(false)
                .build(),
            contentDescription = stringResource(
                when {
                    !showMarks -> R.string.dedup_thumb_neutral_cd
                    isKept -> R.string.dedup_thumb_kept_cd
                    else -> R.string.dedup_thumb_delete_cd
                }
            ),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = dedupThumbPlaceholderPainter(),
            error = dedupThumbPlaceholderPainter()
        )

        if (showMarks) {
            if (isKept) {
                Text(
                    text = stringResource(R.string.dedup_keep_badge),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(
                            KeepGreen,
                            RoundedCornerShape(topStart = 10.dp, bottomEnd = 6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.White
                    )
                }
            }
        }

        RoleBadge(
            role = role,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
        )
    }
}

/**
 * 去重分组卡片：header（级别 + 内容类型 badge + 元信息 + 手动标记 + chevron）+ 最多 3 张缩略图
 * + footer（保留策略提示，spec §10.4 按内容类型差异化）。
 * 未预选组（autoPreselected=false 且未改选）缩略图不显示保留框/✕ 删除标记。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupGroupCard(
    group: DedupGroup,
    policy: KeepPolicy,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showMarks = showPreselection(group)
    Card(
        onClick = onOpenDetail,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LevelBadge(level = group.level)
                ContentTypeBadge(contentType = group.contentType)
                if (!group.userOverride) {
                    Text(
                        text = stringResource(R.string.dedup_ai_pick_badge),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = dedupGroupMetaText(group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (group.userOverride) {
                    Text(
                        text = stringResource(R.string.dedup_manual_mark),
                        color = SceneOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.members.take(3).forEach { member ->
                    DedupThumb(
                        uri = member.uri,
                        isKept = member.uri == group.keepUri,
                        role = member.role,
                        showMarks = showMarks,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                    )
                }
            }

            Text(
                text = dedupGroupFooterText(group = group, policy = policy),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 组卡片 footer 文案（spec §10.4）：截图/文档未预选组与人像默认规则组用类型专属策略文案，
 * 其余（含用户已改选、EXACT 截图/文档组）沿用现行默认文案。
 */
@Composable
private fun dedupGroupFooterText(group: DedupGroup, policy: KeepPolicy): String = when {
    !showPreselection(group) && group.contentType == DedupContentType.SCREENSHOT ->
        stringResource(R.string.dedup_hint_screenshot)
    !showPreselection(group) && group.contentType == DedupContentType.DOCUMENT ->
        stringResource(R.string.dedup_hint_document)
    group.contentType == DedupContentType.PORTRAIT &&
        policy == KeepPolicy.BEST_QUALITY && !group.userOverride ->
        stringResource(R.string.dedup_hint_portrait)
    else -> stringResource(R.string.dedup_rule_hint_default, stringResource(keepPolicyLabelRes(policy)))
}

/** 组当前是否呈现预选勾选状态（未预选且未改选 = 无保留/删除标记）。 */
internal fun showPreselection(group: DedupGroup): Boolean = group.autoPreselected || group.userOverride

/**
 * 组 meta 文案：预选组显示实际可释放量；未预选且未改选组 reclaimBytes 恒 0，
 * 改显示按当前 keepUri 派生的预计可释放量（「预计」措辞），避免「0 B」误导。
 */
@Composable
internal fun dedupGroupMetaText(group: DedupGroup): String =
    if (showPreselection(group)) {
        stringResource(R.string.dedup_group_meta, group.members.size, formatBytes(group.reclaimBytes))
    } else {
        stringResource(
            R.string.dedup_group_meta_estimated,
            group.members.size,
            formatBytes(group.potentialReclaimBytes)
        )
    }

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.1f MB".format(bytes / mb)
        bytes >= kb -> "%.1f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}

internal fun keepPolicyLabelRes(policy: KeepPolicy): Int = when (policy) {
    KeepPolicy.BEST_QUALITY -> R.string.dedup_policy_quality
    KeepPolicy.ORIGINAL -> R.string.dedup_policy_original
    KeepPolicy.EDITED -> R.string.dedup_policy_edited
    KeepPolicy.LATEST -> R.string.dedup_policy_latest
}

internal fun keepPolicyDescRes(policy: KeepPolicy): Int = when (policy) {
    KeepPolicy.BEST_QUALITY -> R.string.dedup_policy_quality_desc
    KeepPolicy.ORIGINAL -> R.string.dedup_policy_original_desc
    KeepPolicy.EDITED -> R.string.dedup_policy_edited_desc
    KeepPolicy.LATEST -> R.string.dedup_policy_latest_desc
}

internal fun keepReasonLabelRes(reason: KeepReason): Int = when (reason) {
    KeepReason.USER_CHOICE -> R.string.dedup_keep_reason_user
    KeepReason.FACE_QUALITY -> R.string.dedup_keep_reason_face
    KeepReason.AESTHETIC -> R.string.dedup_keep_reason_aesthetic
    KeepReason.HIGHEST_RESOLUTION -> R.string.dedup_keep_reason_resolution
    KeepReason.ORIGINAL -> R.string.dedup_keep_reason_original
    KeepReason.EDITED -> R.string.dedup_keep_reason_edited
    KeepReason.LATEST -> R.string.dedup_keep_reason_latest
}
