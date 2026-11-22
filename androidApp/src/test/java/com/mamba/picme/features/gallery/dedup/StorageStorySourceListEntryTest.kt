package com.mamba.picme.features.gallery.dedup

import com.mamba.picme.domain.organize.AppSource
import com.mamba.picme.domain.organize.OrganizeCategorizer
import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.organize.SourceTier
import com.mamba.picme.domain.organize.StorageStory
import com.mamba.picme.domain.organize.defaultTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hub 来源列表「查看全部」入口判定（fix round 1）：非零来源总数（含 OTHER）> Top3
 * 即出现——OTHER 的唯一入口是展开列表，命名来源 ≤3 且无 IM（LOW_VALUE 行不渲染）
 * 时不得让 OTHER 不可达。
 */
class StorageStorySourceListEntryTest {

    private fun item(uri: String, relativePath: String) = OrganizeItem(
        uri = uri,
        isVideo = false,
        captureDate = 1_000L,
        sizeBytes = 1_000_000L,
        relativePath = relativePath,
        ocrText = null,
        pixelArea = 12_000_000L,
        labels = null,
        hasFace = false,
        aestheticScore = null,
        faceQualityScore = null,
    )

    private fun storyOf(vararg items: OrganizeItem): StorageStory {
        val now = 1_000_000L
        return StorageStory.of(items.toList(), OrganizeCategorizer.board(items.toList(), now), now)
    }

    @Test
    fun `three named sources without IM plus OTHER shows entry and list covers OTHER`() {
        // 评审缺口场景：命名来源恰 3（无 IM → LOW_VALUE 行不渲染）+ OTHER 占位
        val story = storyOf(
            item("c1", "DCIM/Camera/"),
            item("s1", "Pictures/Screenshots/"),
            item("d1", "Download/"),
            item("o1", "Pictures/random/"),
        )
        // 无 LOW_VALUE 来源（IM 六家缺席），LOW_VALUE 聚合行不出现
        assertTrue(
            story.sourceLedgers.none { ledger -> ledger.source.defaultTier == SourceTier.LOW_VALUE }
        )
        // 「查看全部」入口出现（总数 4 > 3，含 OTHER 计数）
        assertTrue(shouldShowSourceListEntry(story.sourceLedgers))
        // 展开列表含 OTHER——完整列表是 OTHER 详情页的唯一入口
        assertTrue(story.sourceLedgers.any { ledger -> ledger.source == AppSource.OTHER })
    }

    @Test
    fun `exactly three sources without OTHER hides entry`() {
        // Top3 已承载全部来源：无多余可展开，入口不出现
        val story = storyOf(
            item("c1", "DCIM/Camera/"),
            item("s1", "Pictures/Screenshots/"),
            item("d1", "Download/"),
        )
        assertFalse(shouldShowSourceListEntry(story.sourceLedgers))
    }

    @Test
    fun `two named sources plus OTHER hides entry at total three`() {
        // 总数恰 3（含 OTHER）：Top3 容量内，入口按 > 3 边界不出现
        val story = storyOf(
            item("c1", "DCIM/Camera/"),
            item("s1", "Pictures/Screenshots/"),
            item("o1", "Pictures/random/"),
        )
        assertFalse(shouldShowSourceListEntry(story.sourceLedgers))
    }

    @Test
    fun `single OTHER library hides entry and top3 area`() {
        // 全 OTHER 库：namedSources 为空整区不渲染（无 chips 可示），总数 1 亦不达入口阈值
        val story = storyOf(item("o1", "Pictures/random/"), item("o2", "Movies/clips/"))
        val namedSources = story.sourceLedgers.filter { ledger -> ledger.source != AppSource.OTHER }
        assertTrue(namedSources.isEmpty())
        assertFalse(shouldShowSourceListEntry(story.sourceLedgers))
    }
}
