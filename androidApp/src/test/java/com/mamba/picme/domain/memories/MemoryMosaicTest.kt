package com.mamba.picme.domain.memories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMosaicTest {

    private val uris = (1..20).map { index -> "content://media/$index" }

    @Test
    fun `empty input yields empty layout`() {
        assertTrue(MemoryMosaic.layout("on_this_day:05-18", emptyList()).isEmpty())
    }

    @Test
    fun `layout consumes all uris in original order`() {
        val blocks = MemoryMosaic.layout("person:42", uris)
        assertEquals(uris, blocks.flatMap { block -> block.uris })
    }

    @Test
    fun `block capacities are respected except tail square row`() {
        val blocks = MemoryMosaic.layout("city:hangzhou", uris)
        blocks.dropLast(1).forEach { block ->
            assertEquals(block.type.capacity, block.uris.size)
        }
        val last = blocks.last()
        assertTrue(last.uris.size <= last.type.capacity)
    }

    @Test
    fun `same orientation never adjacent`() {
        listOf("on_this_day:01-01", "recent:2026-09", "person:7", "city:tokyo", "city:paris")
            .forEach { id ->
                val blocks = MemoryMosaic.layout(id, uris)
                blocks.zipWithNext().forEach { (prev, next) ->
                    if (prev.type.orientation != MosaicOrientation.NONE) {
                        assertNotEquals(
                            "adjacent same orientation in $id",
                            prev.type.orientation,
                            next.type.orientation,
                        )
                    }
                }
            }
    }

    @Test
    fun `same id yields identical layout across calls`() {
        val first = MemoryMosaic.layout("person:42", uris)
        val second = MemoryMosaic.layout("person:42", uris)
        assertEquals(first, second)
    }

    @Test
    fun `different ids produce different sequences`() {
        val ids = (0..9).map { index -> "person:$index" }
        val signatures = ids.map { id ->
            MemoryMosaic.layout(id, uris).map { block -> block.type }.joinToString(",")
        }
        assertTrue("expected at least 2 distinct sequences", signatures.toSet().size >= 2)
    }

    @Test
    fun `tail of three or fewer falls back to square row`() {
        (1..3).forEach { tailSize ->
            val blocks = MemoryMosaic.layout("person:42", uris.take(tailSize))
            assertEquals(1, blocks.size)
            assertEquals(MosaicBlockType.SQUARE_ROW, blocks[0].type)
            assertEquals(tailSize, blocks[0].uris.size)
        }
    }

    @Test
    fun `fnv1a32 is stable and input sensitive`() {
        assertEquals(MemoryMosaic.fnv1a32("person:42"), MemoryMosaic.fnv1a32("person:42"))
        assertNotEquals(MemoryMosaic.fnv1a32("person:42"), MemoryMosaic.fnv1a32("person:43"))
        assertNotEquals(MemoryMosaic.fnv1a32("person:42"), MemoryMosaic.fnv1a32("city:42"))
    }
}
