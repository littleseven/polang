package com.mamba.picme.domain.memories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset

class MemoriesGeneratorTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** 固定 now：2026-09-05 12:00 UTC。 */
    private val now: Long = LocalDateTime.of(2026, 9, 5, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 8): Long =
        LocalDateTime.of(year, month, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun photo(
        uri: String,
        date: Long,
        score: Float? = null,
        city: String? = null,
        personId: String? = null,
    ) = MemoryInput(
        uri = uri,
        captureDate = date,
        aestheticScore = score,
        city = city,
        personId = personId,
    )

    private fun generate(
        inputs: List<MemoryInput>,
        persons: List<NamedPerson> = emptyList(),
        maxCarousel: Int = MemoriesGenerator.MAX_CAROUSEL,
    ) = MemoriesGenerator.generate(inputs, persons, now, zone, maxCarousel)

    @Test
    fun `on this day aggregates across years carrying month day and latest year`() {
        val inputs = listOf(
            photo("a", at(2023, 9, 5), score = 5f),
            photo("b", at(2024, 9, 5), score = 6f),
            photo("c", at(2025, 9, 5), score = 7f),
            photo("d", at(2025, 9, 5, 20), score = 4f),
            photo("same-year", at(2026, 9, 5, 6)),
            photo("other-day", at(2025, 9, 4)),
        )
        val memories = generate(inputs)
        assertEquals(1, memories.size)
        val memory = memories.single()
        assertEquals(MemoryType.ON_THIS_DAY, memory.type)
        assertEquals("on_this_day:09-05", memory.id)
        assertNull(memory.label)
        assertEquals(MonthDay.of(9, 5), memory.monthDay)
        assertEquals(2025, memory.latestYear)
        assertEquals(4, memory.hitCount)
        assertEquals(4, memory.itemUris.size)
        assertTrue("same-year" !in memory.itemUris)
    }

    @Test
    fun `on this day not generated below min count`() {
        val inputs = listOf(
            photo("a", at(2023, 9, 5)),
            photo("b", at(2024, 9, 5)),
            photo("c", at(2025, 9, 5)),
        )
        assertTrue(generate(inputs).isEmpty())
    }

    @Test
    fun `recent highlights from scored photos in window, null score excluded`() {
        val scored = (1..6).map { index ->
            photo("r$index", at(2026, 8, 20) + index, score = 5f + index)
        }
        val inputs = scored + listOf(
            photo("null-score", at(2026, 9, 1)),
            photo("too-old", at(2026, 7, 1), score = 9.9f),
        )
        val memories = generate(inputs)
        assertEquals(1, memories.size)
        val memory = memories.single()
        assertEquals(MemoryType.RECENT_HIGHLIGHTS, memory.type)
        assertEquals("recent:2026-09", memory.id)
        assertNull(memory.label)
        assertNull(memory.monthDay)
        assertNull(memory.latestYear)
        assertEquals(6, memory.hitCount)
        assertEquals(6, memory.itemUris.size)
        assertEquals("r6", memory.itemUris.first())
        assertEquals("r6", memory.coverUri)
        assertTrue("null-score" !in memory.itemUris)
        assertTrue("too-old" !in memory.itemUris)
    }

    @Test
    fun `recent highlights not generated below min count even with null scored extras`() {
        val scored = (1..5).map { index ->
            photo("r$index", at(2026, 8, 20) + index, score = 8f)
        }
        val unscored = (1..3).map { index -> photo("u$index", at(2026, 9, 1) + index) }
        assertTrue(generate(scored + unscored).isEmpty())
    }

    @Test
    fun `named person with enough photos generates moments memory`() {
        val persons = listOf(NamedPerson(personId = "p1", name = "妈妈", isSelf = false))
        val inputs = (1..6).map { index -> photo("m$index", at(2024, 3, index), personId = "p1") }
        val memories = generate(inputs, persons)
        assertEquals(1, memories.size)
        val memory = memories.single()
        assertEquals(MemoryType.PERSON, memory.type)
        assertEquals("person:p1", memory.id)
        assertEquals("妈妈", memory.label)
        assertNull(memory.monthDay)
        assertNull(memory.latestYear)
        assertEquals(6, memory.hitCount)
        assertEquals(6, memory.itemUris.size)
    }

    @Test
    fun `unnamed person does not generate memory`() {
        val inputs = (1..6).map { index -> photo("m$index", at(2024, 3, index), personId = "ghost") }
        assertTrue(generate(inputs, emptyList()).isEmpty())
    }

    @Test
    fun `self person does not generate memory`() {
        val persons = listOf(NamedPerson(personId = "me", name = "Me", isSelf = true))
        val inputs = (1..8).map { index -> photo("m$index", at(2024, 3, index), personId = "me") }
        assertTrue(generate(inputs, persons).isEmpty())
    }

    @Test
    fun `person memories capped at top 3 by media count`() {
        val persons = listOf(
            NamedPerson(personId = "p1", name = "Ann", isSelf = false),
            NamedPerson(personId = "p2", name = "Bob", isSelf = false),
            NamedPerson(personId = "p3", name = "Cat", isSelf = false),
            NamedPerson(personId = "p4", name = "Dan", isSelf = false),
        )
        val inputs = buildList {
            repeat(9) { index -> add(photo("a$index", at(2024, 1, 1) + index, personId = "p1")) }
            repeat(8) { index -> add(photo("b$index", at(2024, 2, 1) + index, personId = "p2")) }
            repeat(7) { index -> add(photo("c$index", at(2024, 3, 1) + index, personId = "p3")) }
            repeat(6) { index -> add(photo("d$index", at(2024, 4, 1) + index, personId = "p4")) }
        }
        val memories = generate(inputs, persons)
        assertEquals(3, memories.size)
        assertEquals(listOf("person:p1", "person:p2", "person:p3"), memories.map { memory -> memory.id })
    }

    @Test
    fun `city memories capped at top 2 by photo count`() {
        val inputs = buildList {
            repeat(7) { index -> add(photo("t$index", at(2024, 5, 1) + index, city = "Tokyo")) }
            repeat(6) { index -> add(photo("p$index", at(2024, 6, 1) + index, city = "Paris")) }
            repeat(6) { index -> add(photo("l$index", at(2024, 7, 1) + index, city = "London")) }
            repeat(5) { index -> add(photo("b$index", at(2024, 8, 1) + index, city = "Berlin")) }
        }
        val memories = generate(inputs)
        assertEquals(2, memories.size)
        assertEquals(listOf("Tokyo", "Paris"), memories.map { memory -> memory.label })
        assertEquals("city:Tokyo", memories[0].id)
        assertEquals(7, memories[0].hitCount)
        assertNull(memories[0].monthDay)
        assertTrue(memories.all { memory -> memory.type == MemoryType.CITY })
    }

    @Test
    fun `selection sorts by score desc, nulls last, newer first on tie, truncated to detail limit`() {
        val inputs = buildList {
            add(photo("high-old", at(2024, 1, 1), score = 9f, city = "Rome"))
            add(photo("high-new", at(2025, 1, 1), score = 9f, city = "Rome"))
            repeat(8) { index -> add(photo("mid$index", at(2023, 1, 1) + index, score = 5f, city = "Rome")) }
            add(photo("low", at(2022, 1, 1), score = 3f, city = "Rome"))
            add(photo("null-old", at(2021, 1, 1), city = "Rome"))
            add(photo("null-new", at(2021, 6, 1), city = "Rome"))
        }
        val memory = generate(inputs).single()
        assertEquals(MemoriesGenerator.DETAIL_LIMIT, memory.itemUris.size)
        assertEquals("high-new", memory.itemUris[0])
        assertEquals("high-old", memory.itemUris[1])
        assertEquals("high-new", memory.coverUri)
        assertEquals("null-new", memory.itemUris.last())
        assertTrue("null-old" !in memory.itemUris)
    }

    @Test
    fun `output ordered on this day, recent, person, city`() {
        val persons = listOf(NamedPerson(personId = "p1", name = "Ann", isSelf = false))
        val inputs = buildList {
            repeat(4) { index -> add(photo("otd$index", at(2020 + index, 9, 5))) }
            repeat(6) { index -> add(photo("recent$index", at(2026, 8, 20) + index, score = 7f)) }
            repeat(6) { index -> add(photo("person$index", at(2023, 5, 1) + index, personId = "p1")) }
            repeat(6) { index -> add(photo("city$index", at(2022, 4, 1) + index, city = "Tokyo")) }
        }
        val memories = generate(inputs, persons)
        assertEquals(
            listOf(MemoryType.ON_THIS_DAY, MemoryType.RECENT_HIGHLIGHTS, MemoryType.PERSON, MemoryType.CITY),
            memories.map { memory -> memory.type },
        )
    }

    @Test
    fun `carousel truncated to max with type order preserved`() {
        val persons = (1..3).map { index ->
            NamedPerson(personId = "p$index", name = "P$index", isSelf = false)
        }
        val inputs = buildList {
            repeat(4) { index -> add(photo("otd$index", at(2020 + index, 9, 5))) }
            repeat(6) { index -> add(photo("recent$index", at(2026, 8, 20) + index, score = 7f)) }
            persons.forEachIndexed { personIndex, person ->
                repeat(6) { index ->
                    add(photo("person${personIndex}_$index", at(2023, 1 + personIndex, 1) + index, personId = person.personId))
                }
            }
            repeat(7) { index -> add(photo("t$index", at(2022, 5, 1) + index, city = "Tokyo")) }
            repeat(6) { index -> add(photo("p$index", at(2022, 6, 1) + index, city = "Paris")) }
        }
        val full = generate(inputs, persons)
        assertEquals(7, full.size)
        assertTrue(full.size <= MemoriesGenerator.MAX_CAROUSEL)
        val capped = generate(inputs, persons, maxCarousel = 3)
        assertEquals(3, capped.size)
        assertEquals(
            listOf(MemoryType.ON_THIS_DAY, MemoryType.RECENT_HIGHLIGHTS, MemoryType.PERSON),
            capped.map { memory -> memory.type },
        )
    }

    @Test
    fun `empty input yields empty list`() {
        assertTrue(generate(emptyList()).isEmpty())
    }
}
