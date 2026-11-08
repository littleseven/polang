package com.mamba.picme.data.local

import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.ModelInputItem
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.PartState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * parts-first 持久接缝（[decodeParts] / [toModelInputItems]）JVM 单测 + 全仓写接缝守卫
 * （所有 chat_messages 写入必须走 Parts 入口）。
 *
 * D1a（2026-11-08）：partsJson 为权威列（非空），type/content/metadata 为
 * [com.mamba.picme.domain.chat.projectChatRow] 派生投影列。
 */
class ChatMessagePartsTest {

    // ── 读侧（partsJson 权威直读） ──────────────────────────────

    @Test
    fun `decodeParts returns parts from authoritative partsJson`() {
        val entity = ChatMessageEntity(
            id = "m1",
            sessionId = "s",
            type = "text",
            role = "agent",
            content = "投影正文",
            timestamp = 1L,
            partsJson = MessagePartsCodec.encode(
                listOf(MessagePart.Text("p0", "parts 原文", PartState.DONE)),
            ),
        )
        assertEquals(
            listOf(MessagePart.Text("p0", "parts 原文", PartState.DONE)),
            entity.decodeParts(),
        )
    }

    @Test
    fun `decodeParts falls back to content text when partsJson corrupt`() {
        // partsJson 非空列：唯一兜底路径 = 损坏/空行降级原文 Text part（不丢消息）
        val corrupt = ChatMessageEntity(
            id = "m2",
            sessionId = "s",
            type = "tool_chart",
            role = "agent",
            content = "<svg/>",
            timestamp = 1L,
            partsJson = "{broken",
        )
        assertEquals(listOf(MessagePart.Text("p0", "<svg/>", PartState.DONE)), corrupt.decodeParts())

        val blank = corrupt.copy(partsJson = "  ")
        assertEquals(listOf(MessagePart.Text("p0", "<svg/>", PartState.DONE)), blank.decodeParts())
    }

    // ── 回灌桥（实体 → ModelInputItem） ─────────────────────────

    @Test
    fun `toModelInputItems derives role from role column`() {
        val user = ChatMessageEntity(
            id = "u", sessionId = "s", type = "text", role = "user", content = "问", timestamp = 1L,
            partsJson = MessagePartsCodec.encode(listOf(MessagePart.Text("p0", "问", PartState.DONE))),
        )
        val agent = ChatMessageEntity(
            id = "a", sessionId = "s", type = "text", role = "agent", content = "答", timestamp = 2L,
            partsJson = MessagePartsCodec.encode(listOf(MessagePart.Text("p0", "答", PartState.DONE))),
        )
        assertEquals(listOf(ModelInputItem.TextMessage(ModelInputRole.USER, "问")), user.toModelInputItems())
        assertEquals(listOf(ModelInputItem.TextMessage(ModelInputRole.ASSISTANT, "答")), agent.toModelInputItems())
    }

    @Test
    fun `toModelInputItems drops media results from context`() {
        // 退役横滑卡行整行跳过（RETIRED 过滤保留至 Task 4 v28 重置收口）
        val entity = ChatMessageEntity(
            id = "m5",
            sessionId = "s",
            type = "data_media_results",
            role = "agent",
            content = """[{"id":1,"uri":"u","type":"PHOTO","captureDate":1,"fileName":"f"}]""",
            timestamp = 1L,
            metadata = """{"query":"q","totalCount":1,"isRefinement":false}""",
            partsJson = "[]",
        )
        assertEquals(emptyList<ModelInputItem>(), entity.toModelInputItems())
    }

    // ── 全仓写接缝守卫 ──────────────────────────────────────────

    /**
     * 守卫：chat_messages 的一切写入（Room insert）必须走 parts-first 接缝
     * （insertPartsMessage / insertPartsMessages / replacePartsMessage——parts 直建 +
     * 投影落列）。直接调用 DAO 原生 insert 即违规。
     * 豁免：DAO 接口声明（ChatMessageDao.kt）与接缝实现（ChatMessageParts.kt）。
     */
    @Test
    fun `all chat message writes go through the parts-first seam`() {
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
                "chat_messages 写入绕过 parts-first 接缝（D1a）：\n" +
                    offenders.joinToString("\n") + "\n" +
                    "请改用 insertPartsMessage / insertPartsMessages / replacePartsMessage。",
            )
        }
    }
}
