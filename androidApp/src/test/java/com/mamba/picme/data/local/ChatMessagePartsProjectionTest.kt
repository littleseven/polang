package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.OptimizeCandidateGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * parts-first 写接缝 DAO 级 Robolectric 往返测试（D1a）：insertPartsMessage /
 * insertPartsMessages / replacePartsMessage 落库 → 读回 → 投影列与 partsJson 一致性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessagePartsProjectionTest {

    private lateinit var db: AppDatabase
    private lateinit var messageDao: ChatMessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        messageDao = db.chatMessageDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `insertPartsMessage round-trips parts and projects scalar columns`() = runTest {
        val parts = listOf(
            MessagePart.Reasoning("p0", "先想"),
            MessagePart.Text("p1", "后答"),
        )
        val id = messageDao.insertPartsMessage(
            sessionId = "s1",
            role = ModelInputRole.ASSISTANT,
            parts = parts,
            modelUsed = "remote_deepseek",
        )

        val row = messageDao.getMessageById(id)!!
        assertEquals(parts, row.decodeParts())
        assertEquals("text", row.type)
        assertEquals("agent", row.role)
        assertEquals("后答", row.content)
        assertEquals("remote_deepseek", row.modelUsed)
    }

    @Test
    fun `insertPartsMessage merges extra metadata with projection`() = runTest {
        val task = EngineerTaskState(
            taskId = "t1", sourceText = "修一下", startedAtMs = 1L, updatedAtMs = 2L,
        )
        val parts = listOf(
            MessagePart.TaskCard("p0", "t1", com.mamba.picme.domain.chat.ToolPartState.OUTPUT_AVAILABLE, task),
        )
        val id = messageDao.insertPartsMessage(
            sessionId = "s1",
            role = ModelInputRole.ASSISTANT,
            parts = parts,
            id = "t1",
            timestamp = 1L,
            extraMetadataJson = """{"prompt_len":10,"decode_len":20}""",
        )

        val row = messageDao.getMessageById(id)!!
        // 投影键（engineer_task）与附加键（performance）共存
        assertTrue(row.metadata!!.contains("\"engineer_task\""))
        assertTrue(row.metadata.contains("\"prompt_len\":10"))
        assertEquals("", row.content)
        assertEquals("tool_task", row.type)
        // 任务中心查询通道：type 列命中 tool_task
        assertEquals(listOf("t1"), messageDao.getTaskCardMessages().first().map { it.id })
    }

    @Test
    fun `insertPartsMessages batch round-trips`() = runTest {
        val rows = listOf(
            PartsMessageRow(
                id = "b1", sessionId = "s1", role = ModelInputRole.USER,
                parts = listOf(MessagePart.Image("p0", "file://a"), MessagePart.Text("p1", "配文")),
                timestamp = 1L,
            ),
            PartsMessageRow(
                id = "b2", sessionId = "s1", role = ModelInputRole.ASSISTANT,
                parts = listOf(
                    MessagePart.OptimizeCandidates("p0", candidateGroup()),
                ),
                modelUsed = "remote_deepseek",
                timestamp = 2L,
                extraMetadataJson = """{"prompt_len":5}""",
            ),
        )
        messageDao.insertPartsMessages(rows)

        val user = messageDao.getMessageById("b1")!!
        assertEquals(rows[0].parts, user.decodeParts())
        assertEquals("image", user.type)
        assertEquals("user", user.role)
        assertEquals("配文", user.content)
        assertTrue(user.metadata!!.contains("\"imageUri\":\"file://a\""))

        val gacha = messageDao.getMessageById("b2")!!
        assertEquals(rows[1].parts, gacha.decodeParts())
        assertEquals("data_optimize_candidates", gacha.type)
        assertEquals("", gacha.content)
        // 经 merge（org.json）落库会转义 "/"，断言按 JSONObject 取值而非裸 contains
        val gachaMeta = JSONObject(gacha.metadata!!)
        assertEquals("content://media/1", gachaMeta.getString("sourceImageUri"))
        assertEquals(5, gachaMeta.getInt("prompt_len"))
    }

    @Test
    fun `replacePartsMessage keeps id and lets extra keys ride along`() = runTest {
        val group = candidateGroup()
        val id = messageDao.insertPartsMessage(
            sessionId = "s1",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.OptimizeCandidates("p0", group)),
            id = "g1",
            modelUsed = "remote_deepseek",
            timestamp = 42L,
            extraMetadataJson = """{"prompt_len":7}""",
        )

        // 换一组：parts 整体改写，同 id REPLACE
        val nextGroup = group.copy(drawIndex = 2, recommendedIndex = 0)
        val existing = messageDao.getMessageById(id)!!
        messageDao.replacePartsMessage(existing, listOf(MessagePart.OptimizeCandidates("p0", nextGroup)))

        val replaced = messageDao.getMessageById(id)!!
        assertEquals(listOf(MessagePart.OptimizeCandidates("p0", nextGroup)), replaced.decodeParts())
        // 原 id/modelUsed/timestamp 保留；旧投影键（group 字段）由新投影重建，附加键（performance）存活
        assertEquals("g1", replaced.id)
        assertEquals("remote_deepseek", replaced.modelUsed)
        assertEquals(42L, replaced.timestamp)
        assertTrue(replaced.metadata!!.contains("\"drawIndex\":2"))
        assertTrue(replaced.metadata.contains("\"prompt_len\":7"))
    }

    @Test
    fun `replacePartsMessage swapping gacha for image drops stale group keys`() = runTest {
        val id = messageDao.insertPartsMessage(
            sessionId = "s1",
            role = ModelInputRole.ASSISTANT,
            parts = listOf(MessagePart.OptimizeCandidates("p0", candidateGroup())),
            id = "g2",
            timestamp = 1L,
        )
        val existing = messageDao.getMessageById(id)!!
        messageDao.replacePartsMessage(existing, listOf(MessagePart.Image("p0", "file:///full.jpg")))

        val swapped = messageDao.getMessageById(id)!!
        assertEquals("image", swapped.type)
        assertEquals("file:///full.jpg", swapped.content)
        val metadata = swapped.metadata!!
        assertTrue(metadata.contains("\"imageUri\":\"file:///full.jpg\""))
        // 旧 group 投影键随旧 parts 出清
        assertTrue(!metadata.contains("sourceImageUri"))
        assertTrue(!metadata.contains("candidates"))
    }

    private fun candidateGroup(): OptimizeCandidateGroup = OptimizeCandidateGroup(
        sourceImageUri = "content://media/1",
        scene = "GENERAL",
        recommendedIndex = 1,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate("base", "file:///a.jpg", 6.0f, false),
        ),
        usedFingerprints = listOf("fp1"),
        drawIndex = 1,
    )
}
