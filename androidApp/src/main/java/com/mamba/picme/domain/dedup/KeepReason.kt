package com.mamba.picme.domain.dedup

/** 保留推荐理由（组详情页一行说明的可解释性来源，Agent First：枚举全部合法原因）。 */
enum class KeepReason {
    USER_CHOICE,
    FACE_QUALITY,
    AESTHETIC,
    HIGHEST_RESOLUTION,
    ORIGINAL,
    EDITED,
    LATEST,
}

/**
 * 推荐理由推导（纯函数）：与 [KeepPolicyEngine.recommend] 同口径——
 * BEST_QUALITY 主键为像素面积→大小→美学分→拍摄日期（PORTRAIT 组前置 faceQualityScore）；
 * 仅当保留项在平局维度上被次键「定胜负」时才归因次键，否则归因主键（分辨率）。
 * ORIGINAL/EDITED 策略先校验 keep 成员实际角色（classify 可能全组无该角色成员），
 * 失配时回落画质口径，避免理由与 RoleBadge 矛盾。
 */
fun keepReasonOf(group: DedupGroup, policy: KeepPolicy): KeepReason {
    if (group.userOverride) return KeepReason.USER_CHOICE
    return when (policy) {
        KeepPolicy.ORIGINAL ->
            if (keepRoleIs(group, VersionRole.ORIGINAL)) KeepReason.ORIGINAL else bestQualityReason(group)
        KeepPolicy.EDITED ->
            if (keepRoleIs(group, VersionRole.EDITED)) KeepReason.EDITED else bestQualityReason(group)
        KeepPolicy.LATEST -> KeepReason.LATEST
        KeepPolicy.BEST_QUALITY -> bestQualityReason(group)
    }
}

/** keep 成员角色判定：classify 可能产生全组无 ORIGINAL（或无 EDITED）的组，此时策略归因失配。 */
private fun keepRoleIs(group: DedupGroup, role: VersionRole): Boolean {
    val keep = group.members.firstOrNull { member -> member.uri == group.keepUri } ?: return false
    return keep.role == role
}

private fun bestQualityReason(group: DedupGroup): KeepReason {
    val keep = group.members.firstOrNull { member -> member.uri == group.keepUri }
        ?: return KeepReason.HIGHEST_RESOLUTION
    val others = group.members.filter { member -> member.uri != group.keepUri }
    if (others.isEmpty()) return KeepReason.HIGHEST_RESOLUTION

    if (group.members.any { member -> member.contentType == DedupContentType.PORTRAIT }) {
        val keepFace = keep.faceQualityScore
        if (keepFace != null && others.all { member -> (member.faceQualityScore ?: -1f) < keepFace }) {
            return KeepReason.FACE_QUALITY
        }
    }

    val tiedOnResolution = others.any { member ->
        member.pixelArea == keep.pixelArea && member.sizeBytes == keep.sizeBytes
    }
    val keepAesthetic = keep.aestheticScore
    if (tiedOnResolution && keepAesthetic != null &&
        others.all { member -> (member.aestheticScore ?: -1f) < keepAesthetic }
    ) {
        return KeepReason.AESTHETIC
    }
    return KeepReason.HIGHEST_RESOLUTION
}
