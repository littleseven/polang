package com.mamba.picme.features.chat.capability

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.inference.remote.tool.ChatHistoryRecall
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.insertPartsMessage
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.PartState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * recall_chat_history 端到端（DAO 查询 + Capability 组装，Robolectric in-memory AppDatabase，
 * 仿 `ChatMessageDaoTest` 先例）：写路径走 parts-first 接缝（insertPartsMessage），
 * 验证 `type='text'` 投影行 LIKE 召回 / 会话隔离 / 倒序取最近 + 正序渲染 / limit 钳制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatHistoryRecallCapabilityTest {

    private lateinit var db: AppDatabase
    private lateinit var capability: ChatHistoryRecallCapability

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        capability = ChatHistoryRecallCapability(chatMessageDao = db.chatMessageDao())
    }

    @After
    fun teardown() = db.close()

    private suspend fun seedText(
        sessionId: String,
        role: ModelInputRole,
        text: String,
        timestamp: Long,
    ) {
        db.chatMessageDao().insertPartsMessage(
            sessionId = sessionId,
            role = role,
            parts = listOf(MessagePart.Text(partId = "p0", markdown = text, state = PartState.DONE)),
            timestamp = timestamp,
            id = "$sessionId-$timestamp",
        )
    }

    @Test
    fun `recall returns matching text rows ascending with session isolation`() = runTest {
        seedText("s1", ModelInputRole.USER, "帮我找海边的照片", 100)
        seedText("s1", ModelInputRole.ASSISTANT, "找到了 12 张海边照片", 200)
        seedText("s1", ModelInputRole.USER, "只要去年的", 300)
        seedText("s2", ModelInputRole.USER, "海边的照片（别的会话）", 150)
        // 非文本投影行（图集卡，type=tool_html）不参与召回——即便 html 里含关键词
        db.chatMessageDao().insertPartsMessage(
            sessionId = "s1",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.HtmlCard(partId = "p0", html = "<div>海边</div>")),
            timestamp = 400,
            id = "s1-card",
        )

        val result = capability.execute(
            AgentCommand.RecallChatHistory(query = "海边", limit = 20),
            AgentContext(scene = AgentScene.CHAT, memorySessionId = "s1"),
            null,
        ).getOrNull()

        val reply = result as AgentAction.TextReply
        assertEquals(
            "[历史召回] 共 2 条（按时间正序）：\n" +
                "user: 帮我找海边的照片\n" +
                "agent: 找到了 12 张海边照片",
            reply.message,
        )
    }

    @Test
    fun `blank query takes most recent N rows newest-first then renders ascending`() = runTest {
        (1..25).forEach { idx ->
            seedText("s1", ModelInputRole.USER, "消息-$idx", timestamp = idx * 10L)
        }

        val result = capability.execute(
            AgentCommand.RecallChatHistory(query = "", limit = 5),
            AgentContext(scene = AgentScene.CHAT, memorySessionId = "s1"),
            null,
        ).getOrNull()

        val reply = result as AgentAction.TextReply
        assertTrue(reply.message.startsWith("[历史召回] 共 5 条（按时间正序）："))
        assertTrue("应取最近 5 条（21~25）", reply.message.contains("消息-21"))
        assertFalse("更早的历史不应混入（防前缀误配）", reply.message.contains("消息-1\n"))
        assertTrue("正序渲染最后一条应为最新", reply.message.endsWith("消息-25"))
    }

    @Test
    fun `limit is clamped to range 1 to 50 at capability layer too`() = runTest {
        seedText("s1", ModelInputRole.USER, "hello", 100)
        val result = capability.execute(
            AgentCommand.RecallChatHistory(query = "hello", limit = 0),
            AgentContext(scene = AgentScene.CHAT, memorySessionId = "s1"),
            null,
        ).getOrNull()
        val reply = result as AgentAction.TextReply
        assertTrue("limit=0 应钳为 1 仍可召回", reply.message.startsWith("[历史召回] 共 1 条"))
        // MAX_LIMIT 上界已在 shared commonTest 钉住（ChatHistoryRecallTest）
        assertEquals(50, ChatHistoryRecall.clampLimit(999))
    }

    @Test
    fun `unsupported command reports method not found`() = runTest {
        val result = capability.execute(
            AgentCommand.RecallMemory(query = "x"),
            AgentContext(scene = AgentScene.CHAT, memorySessionId = "s1"),
            null,
        ).getOrNull()
        assertTrue(result is AgentAction.Error)
    }
}
