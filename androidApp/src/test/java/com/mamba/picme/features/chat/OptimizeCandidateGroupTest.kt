package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.OptimizeCandidateGroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimizeCandidateGroupTest {

    private fun group() = OptimizeCandidateGroup(
        sourceImageUri = "content://media/1",
        scene = "GENERAL",
        recommendedIndex = 1,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate("base", "file:///a.jpg", 6.0f, rejected = false),
            OptimizeCandidateGroup.Candidate("warm", "file:///b.jpg", nimaScore = null, rejected = true)
        ),
        usedFingerprints = listOf("fp1", "fp2"),
        drawIndex = 2
    )

    @Test
    fun `toJson fromJson roundtrip preserves all fields`() {
        val restored = OptimizeCandidateGroup.fromJson(group().toJson())!!

        assertEquals("content://media/1", restored.sourceImageUri)
        assertEquals("GENERAL", restored.scene)
        assertEquals(1, restored.recommendedIndex)
        assertEquals(2, restored.drawIndex)
        assertEquals(listOf("fp1", "fp2"), restored.usedFingerprints)
        assertEquals(2, restored.candidates.size)
        assertEquals("base", restored.candidates[0].direction)
        assertEquals("file:///a.jpg", restored.candidates[0].thumbPath)
        assertEquals(6.0f, restored.candidates[0].nimaScore!!, 0.001f)
        assertEquals(false, restored.candidates[0].rejected)
    }

    @Test
    fun `nimaScore null survives roundtrip`() {
        val restored = OptimizeCandidateGroup.fromJson(group().toJson())!!
        assertNull(restored.candidates[1].nimaScore)
        assertEquals(true, restored.candidates[1].rejected)
    }

    @Test
    fun `recommendedIndex -1 (KeepOriginal) survives roundtrip`() {
        val restored = OptimizeCandidateGroup.fromJson(
            group().copy(recommendedIndex = -1).toJson()
        )!!
        assertEquals(-1, restored.recommendedIndex)
    }

    @Test
    fun `fromJson returns null for null or blank input`() {
        assertNull(OptimizeCandidateGroup.fromJson(null))
        assertNull(OptimizeCandidateGroup.fromJson(""))
        assertNull(OptimizeCandidateGroup.fromJson("   "))
    }

    @Test
    fun `fromJson returns null for malformed json`() {
        assertNull(OptimizeCandidateGroup.fromJson("{not json"))
    }

    @Test
    fun `fromJson tolerates missing optional fields`() {
        val json = """
            {"sourceImageUri":"content://x","candidates":[]}
        """.trimIndent()
        val restored = OptimizeCandidateGroup.fromJson(json)!!
        assertEquals("content://x", restored.sourceImageUri)
        assertEquals("", restored.scene)
        assertEquals(-1, restored.recommendedIndex)
        assertEquals(1, restored.drawIndex)
        assertEquals(emptyList<String>(), restored.usedFingerprints)
    }

    @Test
    fun `corrupt nimaScore degrades to null for that candidate only`() {
        val json = """
            {"sourceImageUri":"content://x","scene":"GENERAL","recommendedIndex":0,
             "candidates":[{"direction":"base","thumbPath":"file:///a.jpg","nimaScore":"high","rejected":false}]}
        """.trimIndent()
        val restored = OptimizeCandidateGroup.fromJson(json)!!
        assertNull(restored.candidates[0].nimaScore)
        assertEquals("base", restored.candidates[0].direction)
    }
}
