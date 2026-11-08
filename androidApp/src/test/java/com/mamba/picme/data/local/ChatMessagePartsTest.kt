package com.mamba.picme.data.local

import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.OptimizeCandidateGroup
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.toToolPartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parts 单一权威接缝单测（v28）：构造投影、parts 更新重投影、读取容错。
 */
class ChatMessagePartsTest {

    private fun textPart(id: String = "p0", md: String = "hi") =
        MessagePart.Text(id, md, PartState.DONE)

    @Test
    fun `chatMessageEntity derives type projection and encodes partsJson at construction`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "user",
            parts = listOf(MessagePart.Image("p0", "file://a"), textPart("p1", "看图")),
        )
        assertEquals("image", entity.type)
        assertEquals("user", entity.role)
        assertEquals(
            listOf(MessagePart.Image("p0", "file://a"), textPart("p1", "看图")),
            entity.decodeParts(),
        )
    }

    @Test
    fun `withUpdatedParts re-derives type projection when primary part changes`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "agent",
            parts = listOf(MessagePart.OptimizeCandidates("p0", gachaGroup())),
        )
        assertEquals("data_optimize_candidates", entity.type)
        val confirmed = entity.withUpdatedParts {
            listOf(MessagePart.Image("p0", ref = "content://x", saved = false))
        }
        assertEquals("image", confirmed.type)
        assertEquals(listOf(MessagePart.Image("p0", ref = "content://x", saved = false)), confirmed.decodeParts())
    }

    @Test
    fun `withUpdatedParts transforms in place and preserves message fields`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "agent", modelUsed = "html_card",
            metadata = """{"claude_agent_state":{}}""",
            parts = listOf(MessagePart.HtmlCard("p0", "<p/>")),
        )
        val updated = entity.withUpdatedParts { parts ->
            parts.map { part ->
                if (part is MessagePart.HtmlCard) {
                    part.copy(meta = part.meta.copy(displayMode = HtmlCardDisplayMode.FULLPAGE, measuredHeightPx = 900))
                } else {
                    part
                }
            }
        }
        assertEquals("tool_html", updated.type)
        assertEquals("html_card", updated.modelUsed)
        assertEquals("""{"claude_agent_state":{}}""", updated.metadata)
        val card = updated.decodeParts().filterIsInstance<MessagePart.HtmlCard>().single()
        assertEquals(HtmlCardDisplayMode.FULLPAGE, card.meta.displayMode)
        assertEquals(900, card.meta.measuredHeightPx)
    }

    @Test
    fun `decodeParts falls back to Unsupported on corrupt partsJson`() {
        val entity = chatMessageEntity(
            id = "m1", sessionId = "s1", role = "agent", parts = listOf(textPart()),
        ).copy(partsJson = "{{{")
        val parts = entity.decodeParts()
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MessagePart.Unsupported)
    }

    @Test
    fun `taskCardStateOrNull reads task from TaskCard part`() {
        val state = EngineerTaskState(
            taskId = "t1", sourceText = "src", status = EngineerTaskStatus.RUNNING,
            startedAtMs = 0L, updatedAtMs = 0L,
        )
        val entity = chatMessageEntity(
            id = "t1", sessionId = "s1", role = "agent",
            parts = listOf(MessagePart.TaskCard("p0", "t1", state.status.toToolPartState(), state)),
        )
        assertEquals(state, entity.taskCardStateOrNull())
        assertEquals("tool_task", entity.type)
        assertNull(chatMessageEntity(id = "m", sessionId = "s1", role = "agent", parts = listOf(textPart())).taskCardStateOrNull())
    }

    private fun gachaGroup() = OptimizeCandidateGroup("u", "scene", 0, emptyList(), emptyList(), 0)
}
