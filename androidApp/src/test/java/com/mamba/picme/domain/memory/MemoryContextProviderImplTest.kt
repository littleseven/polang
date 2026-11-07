package com.mamba.picme.domain.memory

import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.data.repository.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextProviderImplTest {

    @Test
    fun mapsEntities_relationUsesPredicateLabelZh() {
        val relations = listOf(
            RelationDisplayItem(
                relationId = 1, subjectPersonId = 2, subjectName = "小宝",
                predicate = RelationPredicate.DAUGHTER, customLabel = null
            )
        )
        val facts = listOf(
            MemoryFactEntity(content = "小宝对花粉过敏", category = "健康", source = "CHAT_TOOL", createdAt = 100L)
        )
        val snap = MemoryContextProviderImpl.formatMemoryContextFromEntities(facts, relations)
        assertTrue(snap.contains("小宝=女儿"))
        assertTrue(snap.contains("- 小宝对花粉过敏（健康）"))
    }

    @Test
    fun mapsEntities_customLabelOverridesPredicate() {
        val relations = listOf(
            RelationDisplayItem(
                relationId = 1, subjectPersonId = 2, subjectName = "大宝",
                predicate = RelationPredicate.OTHER, customLabel = "发小"
            )
        )
        val snap = MemoryContextProviderImpl.formatMemoryContextFromEntities(emptyList(), relations)
        assertTrue(snap.contains("大宝=发小"))
        assertTrue(!snap.contains("大宝=其他"))
    }

    @Test
    fun mapsEntities_emptyFactsAndRelations_returnsBlank() {
        assertEquals("", MemoryContextProviderImpl.formatMemoryContextFromEntities(emptyList(), emptyList()))
    }
}
