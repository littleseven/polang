package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizeCategorizerBoardTest {

    private fun dupItem(
        uri: String,
        sizeBytes: Long,
        groupKey: String,
        groupSize: Int,
    ) = OrganizeItem(
        uri = uri,
        isVideo = false,
        captureDate = 1_700_000_000_000L,
        sizeBytes = sizeBytes,
        relativePath = "DCIM/Camera",
        ocrText = null,
        pixelArea = 12_000_000L,
        labels = null,
        hasFace = false,
        aestheticScore = null,
        faceQualityScore = null,
        exactDupGroupSize = groupSize,
        similarDupGroupSize = 0,
        exactDupGroupKey = groupKey,
    )

    @Test
    fun `exact dup group deducts one keeper from both highCount and highBytes`() {
        val items = listOf(
            dupItem("uri://a", sizeBytes = 3_000_000L, groupKey = "md5-1", groupSize = 2),
            dupItem("uri://b", sizeBytes = 3_000_000L, groupKey = "md5-1", groupSize = 2),
        )
        val board = OrganizeCategorizer.board(items, now = 1_800_000_000_000L)
        val dup = board.categories.first { card -> card.category == OrganizeCategory.DUPLICATES }
        assertEquals(1, dup.highCount)
        assertEquals(3_000_000L, dup.highBytes)
    }

    @Test
    fun `partial group in suggestion set does not deduct`() {
        // 组声明 3 人但仅 2 人在建议集（防御分支）：不扣 keeper，按全组计
        val items = listOf(
            dupItem("uri://a", sizeBytes = 3_000_000L, groupKey = "md5-1", groupSize = 3),
            dupItem("uri://b", sizeBytes = 3_000_000L, groupKey = "md5-1", groupSize = 3),
        )
        val board = OrganizeCategorizer.board(items, now = 1_800_000_000_000L)
        val dup = board.categories.first { card -> card.category == OrganizeCategory.DUPLICATES }
        assertEquals(2, dup.highCount)
        assertEquals(6_000_000L, dup.highBytes)
    }
}
