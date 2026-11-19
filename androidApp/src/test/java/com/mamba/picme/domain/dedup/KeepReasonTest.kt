package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepReasonTest {

    private fun member(
        uri: String,
        pixelArea: Int = 4_000_000,
        sizeBytes: Long = 2_000_000L,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
        contentType: DedupContentType = DedupContentType.GENERAL,
        role: VersionRole = VersionRole.ORIGINAL,
    ) = DedupMember(
        uri = uri,
        sizeBytes = sizeBytes,
        mime = "image/jpeg",
        captureDate = 1_700_000_000_000L,
        modifiedAt = 1_700_000_000_000L,
        pixelArea = pixelArea,
        aestheticScore = aestheticScore,
        role = role,
        md5 = null,
        phash = null,
        contentType = contentType,
        faceQualityScore = faceQualityScore,
    )

    private fun group(
        members: List<DedupMember>,
        keepUri: String,
        userOverride: Boolean = false,
        contentType: DedupContentType = DedupContentType.GENERAL,
    ) = DedupGroup(
        id = "g1",
        level = DedupLevel.VISUAL,
        members = members,
        keepUri = keepUri,
        userOverride = userOverride,
        contentType = contentType,
    )

    @Test
    fun `user override always yields USER_CHOICE`() {
        val g = group(listOf(member("a"), member("b")), keepUri = "b", userOverride = true)
        assertEquals(KeepReason.USER_CHOICE, keepReasonOf(g, KeepPolicy.BEST_QUALITY))
        assertEquals(KeepReason.USER_CHOICE, keepReasonOf(g, KeepPolicy.LATEST))
    }

    @Test
    fun `non quality policies map to their own reason`() {
        val g = group(listOf(member("a"), member("b")), keepUri = "a")
        assertEquals(KeepReason.ORIGINAL, keepReasonOf(g, KeepPolicy.ORIGINAL))
        assertEquals(KeepReason.LATEST, keepReasonOf(g, KeepPolicy.LATEST))
    }

    @Test
    fun `edited policy with edited keeper yields EDITED`() {
        // EDITED 正向归因前提：keep 成员角色确为 EDITED（角色失配回落另测）
        val g = group(
            members = listOf(
                member("a", role = VersionRole.EDITED),
                member("b", role = VersionRole.ORIGINAL),
            ),
            keepUri = "a",
        )
        assertEquals(KeepReason.EDITED, keepReasonOf(g, KeepPolicy.EDITED))
    }

    @Test
    fun `portrait group with strictly best face quality yields FACE_QUALITY`() {
        val g = group(
            members = listOf(
                member("a", faceQualityScore = 0.9f, contentType = DedupContentType.PORTRAIT),
                member("b", faceQualityScore = 0.4f, contentType = DedupContentType.PORTRAIT),
            ),
            keepUri = "a",
            contentType = DedupContentType.PORTRAIT,
        )
        assertEquals(KeepReason.FACE_QUALITY, keepReasonOf(g, KeepPolicy.BEST_QUALITY))
    }

    @Test
    fun `portrait group without face score falls through to resolution`() {
        val g = group(
            members = listOf(
                member("a", contentType = DedupContentType.PORTRAIT),
                member("b", contentType = DedupContentType.PORTRAIT),
            ),
            keepUri = "a",
            contentType = DedupContentType.PORTRAIT,
        )
        assertEquals(KeepReason.HIGHEST_RESOLUTION, keepReasonOf(g, KeepPolicy.BEST_QUALITY))
    }

    @Test
    fun `resolution tie with strictly best aesthetic yields AESTHETIC`() {
        val g = group(
            members = listOf(
                member("a", aestheticScore = 7.5f),
                member("b", aestheticScore = 5.0f),
            ),
            keepUri = "a",
        )
        assertEquals(KeepReason.AESTHETIC, keepReasonOf(g, KeepPolicy.BEST_QUALITY))
    }

    @Test
    fun `strict resolution win yields HIGHEST_RESOLUTION`() {
        val g = group(
            members = listOf(
                member("a", pixelArea = 12_000_000, sizeBytes = 8_000_000L, aestheticScore = 5.0f),
                member("b", pixelArea = 4_000_000, sizeBytes = 2_000_000L, aestheticScore = 9.0f),
            ),
            keepUri = "a",
        )
        assertEquals(KeepReason.HIGHEST_RESOLUTION, keepReasonOf(g, KeepPolicy.BEST_QUALITY))
    }

    @Test
    fun `portrait face quality tie falls through to resolution`() {
        val g = group(
            members = listOf(
                member("a", pixelArea = 12_000_000, faceQualityScore = 0.9f, contentType = DedupContentType.PORTRAIT),
                member("b", pixelArea = 4_000_000, faceQualityScore = 0.9f, contentType = DedupContentType.PORTRAIT),
            ),
            keepUri = "a",
            contentType = DedupContentType.PORTRAIT,
        )
        assertEquals(KeepReason.HIGHEST_RESOLUTION, keepReasonOf(g, KeepPolicy.BEST_QUALITY))
    }

    @Test
    fun `aesthetic attribution requires keep to win against all others`() {
        // keep 与 b 分辨率平局、aesthetic 胜 b；但 c 虽低分辨率 aesthetic 更高 → 保守归主键
        val g = group(
            members = listOf(
                member("a", pixelArea = 4_000_000, sizeBytes = 2_000_000L, aestheticScore = 7.5f),
                member("b", pixelArea = 4_000_000, sizeBytes = 2_000_000L, aestheticScore = 5.0f),
                member("c", pixelArea = 1_000_000, sizeBytes = 500_000L, aestheticScore = 9.0f),
            ),
            keepUri = "a",
        )
        assertEquals(KeepReason.HIGHEST_RESOLUTION, keepReasonOf(g, KeepPolicy.BEST_QUALITY))
    }

    @Test
    fun `original policy with edited keeper falls back to quality reason`() {
        // 全组 classify 为 EDITED（modifiedAt - captureDate > 6h）时无 ORIGINAL 成员可选，
        // keep 角色非 ORIGINAL → 不归因「原始版本」，回落画质口径（分辨率严格胜 → HIGHEST_RESOLUTION）
        val g = group(
            members = listOf(
                member("a", pixelArea = 12_000_000, sizeBytes = 8_000_000L, role = VersionRole.EDITED),
                member("b", pixelArea = 4_000_000, sizeBytes = 2_000_000L, role = VersionRole.EDITED),
            ),
            keepUri = "a",
        )
        assertEquals(KeepReason.HIGHEST_RESOLUTION, keepReasonOf(g, KeepPolicy.ORIGINAL))
    }

    @Test
    fun `edited policy with original keeper falls back to quality reason`() {
        // 全组无 EDITED 成员（均未二次编辑）时策略排序 keep 角色为 ORIGINAL，
        // 不归因「编辑版本」，回落画质口径（分辨率严格胜 → HIGHEST_RESOLUTION）
        val g = group(
            members = listOf(
                member("a", pixelArea = 12_000_000, sizeBytes = 8_000_000L, role = VersionRole.ORIGINAL),
                member("b", pixelArea = 4_000_000, sizeBytes = 2_000_000L, role = VersionRole.ORIGINAL),
            ),
            keepUri = "a",
        )
        assertEquals(KeepReason.HIGHEST_RESOLUTION, keepReasonOf(g, KeepPolicy.EDITED))
    }
}
