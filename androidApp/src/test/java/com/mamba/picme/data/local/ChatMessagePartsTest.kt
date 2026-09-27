package com.mamba.picme.data.local

import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.PartState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M1 双写/双读接缝（[withPartsJson] / [decodePartsOrLegacy] / [toModelInputItems]）JVM 单测
 * + 全仓双写守卫（所有 chat_messages 写入必须走 WithParts 入口）。
 */
class ChatMessagePartsTest {

    // ── 双写 ───────────────────────────────────────────────────

    @Test
    fun `withPartsJson fills partsJson derived from legacy columns`() {
        val entity = ChatMessageEntity(
            id = "m1",
            sessionId = "s",
            type = "agent_text",
            content = "你好",
            timestamp = 1L,
        )
        val written = entity.withPartsJson()
        assertEquals(
            listOf(MessagePart.Text("p0", "你好", PartState.DONE)),
            MessagePartsCodec.decode(written.partsJson),
        )
    }

    @Test
    fun `withPartsJson recomputes on metadata rewrite so parts never go stale`() {
        val original = ChatMessageEntity(
            id = "m2",
            sessionId = "s",
            type = "html_card",
            content = "<html/>",
            timestamp = 1L,
            metadata = """{"html_card":{"display":"inline"}}""",
        ).withPartsJson()

        // 端侧终判回写（displayMode/measuredHeightPx 合并进 metadata）后 parts 必须随迁
        val updated = original
            .copy(metadata = """{"html_card":{"display":"inline","displayMode":"INLINE","measuredHeightPx":320}}""")
            .withPartsJson()

        val part = decode(updated).single() as MessagePart.HtmlCard
        assertEquals(HtmlCardDisplayMode.INLINE, part.meta.displayMode)
        assertEquals(320, part.meta.measuredHeightPx)
    }

    // ── 双读 ───────────────────────────────────────────────────

    @Test
    fun `decodePartsOrLegacy prefers partsJson when present`() {
        val entity = ChatMessageEntity(
            id = "m3",
            sessionId = "s",
            type = "agent_text",
            content = "legacy 原文",
            timestamp = 1L,
            partsJson = MessagePartsCodec.encode(
                listOf(MessagePart.Text("p0", "parts 原文", PartState.DONE)),
            ),
        )
        assertEquals(
            listOf(MessagePart.Text("p0", "parts 原文", PartState.DONE)),
            entity.decodePartsOrLegacy(),
        )
    }

    @Test
    fun `decodePartsOrLegacy falls back to legacy columns when partsJson missing or corrupt`() {
        val missing = ChatMessageEntity(
            id = "m4",
            sessionId = "s",
            type = "chart",
            content = "<svg/>",
            timestamp = 1L,
        )
        assertEquals(listOf(MessagePart.Chart("p0", "<svg/>")), missing.decodePartsOrLegacy())

        val corrupt = missing.copy(partsJson = "{broken")
        assertEquals(listOf(MessagePart.Chart("p0", "<svg/>")), corrupt.decodePartsOrLegacy())
    }

    // ── 回灌桥（实体 → ModelInputItem） ─────────────────────────

    @Test
    fun `toModelInputItems derives role from legacy type prefix`() {
        val user = ChatMessageEntity(id = "u", sessionId = "s", type = "user_text", content = "问", timestamp = 1L)
        val agent = ChatMessageEntity(id = "a", sessionId = "s", type = "agent_text", content = "答", timestamp = 2L)
        assertEquals(listOf(ModelInputItem.TextMessage(ModelInputRole.USER, "问")), user.toModelInputItems())
        assertEquals(listOf(ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "答")), agent.toModelInputItems())
    }

    @Test
    fun `toModelInputItems drops media results from context`() {
        val entity = ChatMessageEntity(
            id = "m5",
            sessionId = "s",
            type = "media_results",
            content = """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""",
            timestamp = 1L,
            metadata = """{"query":"q","totalCount":1,"isRefinement":false}""",
        )
        assertEquals(emptyList<ModelInputItem>(), entity.toModelInputItems())
    }

    // ── 全仓双写守卫 ────────────────────────────────────────────

    /**
     * 守卫：chat_messages 的一切写入（Room insert）必须走 insertMessageWithParts /
     * insertMessagesWithParts 接缝（partsJson 双写）。直接调用 DAO 原生 insert 即违规。
     * 豁免：DAO 接口声明（ChatMessageDao.kt）与接缝实现（ChatMessageParts.kt）。
     */
    @Test
    fun `all chat message writes go through the WithParts seam`() {
        val mainSrc = File("src/main/java/com/mamba/picme")
        assertTrue("守卫需在 :androidApp 模块根目录运行", mainSrc.isDirectory)
        val offenders = mutableListOf<String>()
        mainSrc.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> file.name != "ChatMessageDao.kt" && file.name != "ChatMessageParts.kt" }
            .forEach { file ->
                file.readText().lineSequence().forEachIndexed { index, line ->
                    if (".insertMessage(" in line || ".insertMessages(" in line) {
                        offenders += "${file.relativeTo(mainSrc)}:${index + 1} → ${line.trim()}"
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            fail(
                "chat_messages 写入绕过 parts 双写接缝（ADR-016 M1）：\n" +
                    offenders.joinToString("\n") + "\n" +
                    "请改用 insertMessageWithParts / insertMessagesWithParts。",
            )
        }
    }

    private fun decode(entity: ChatMessageEntity): List<MessagePart> =
        entity.decodePartsOrLegacy().also { parts ->
            assertNotNull(parts)
        }
}
