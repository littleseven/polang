package com.mamba.picme.data.repository

import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import com.mamba.picme.domain.person.RelationPredicate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelationDisplayItemTest {
    private fun person(id: Long, name: String?) = PersonEntity(personId = id, name = name)

    private fun relation(predicate: String, customLabel: String?) =
        PersonRelationEntity(
            relationId = 1,
            subjectPersonId = 5,
            objectPersonId = 1,
            predicate = predicate,
            source = "RENAME_DIALOG",
            customLabel = customLabel
        )

    @Test
    fun null_relation_returns_null() {
        assertNull(RelationDisplayItem.from(person(5, "小宝"), null))
    }

    @Test
    fun unknown_predicate_returns_null() {
        assertNull(RelationDisplayItem.from(person(5, "小宝"), relation("BOGUS", null)))
    }

    @Test
    fun valid_predicate_no_custom_label() {
        val item = RelationDisplayItem.from(person(5, "小宝"), relation("DAUGHTER", null))
        assertNotNull(item)
        assertEquals(RelationPredicate.DAUGHTER, item!!.predicate)
        assertNull(item.customLabel)
        assertEquals("小宝", item.subjectName)
        assertEquals(5L, item.subjectPersonId)
    }

    @Test
    fun blank_custom_label_normalized_to_null() {
        val item = RelationDisplayItem.from(person(5, "小宝"), relation("OTHER", "   "))
        assertNotNull(item)
        assertNull(item!!.customLabel)
    }

    @Test
    fun non_blank_custom_label_trimmed() {
        val item = RelationDisplayItem.from(person(5, "小宝"), relation("OTHER", "  小甜甜  "))
        assertEquals("小甜甜", item!!.customLabel)
    }

    @Test
    fun null_person_name_falls_back_to_hash_id() {
        val item = RelationDisplayItem.from(person(5, null), relation("SON", null))
        assertEquals("#5", item!!.subjectName)
    }
}
