package com.mamba.picme.domain.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.local.PartsMessageRow
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.data.local.insertPartsMessages
import com.mamba.picme.domain.backup.model.BackupChatMessage
import com.mamba.picme.domain.backup.model.BackupChatSession
import com.mamba.picme.domain.backup.model.BackupMemoryFact
import com.mamba.picme.domain.backup.model.BackupTag
import com.mamba.picme.domain.backup.model.TagDataBackup
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.OptimizeCandidateGroup
import com.mamba.picme.domain.chat.ToolPartState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份恢复 v6 版本门禁测试（spec《chat-value-domain-ideal》§4 第 3 条）：
 * - v5 及以下旧包 → 备份模块内一次性粗映射表（终态值直通 / legacy 别名映射 /
 *   退役卡 Tombstone / 不可识别跳过行），非聊天段不受影响；
 * - v6 包 → partsJson 直通往返；缺失/损坏行降级 Tombstone/Text 兜底不丢消息。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupChatRestoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TagDataBackupRepository
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var prefsFile: File
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefsFile = File.createTempFile("backup_restore_test", ".preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { prefsFile }
        repository = TagDataBackupRepository(
            database = db,
            mediaDao = db.mediaDao(),
            tagDao = db.tagDao(),
            tagScanTaskDao = db.tagScanTaskDao(),
            personDao = db.personDao(),
            ocrWordDao = db.ocrWordDao(),
            locationDao = db.locationDao(),
            mediaFeedbackDao = db.mediaFeedbackDao(),
            chatMessageDao = db.chatMessageDao(),
            chatSessionDao = db.chatSessionDao(),
            personRelationDao = db.personRelationDao(),
            memoryFactDao = db.memoryFactDao(),
            photoEditRecipeDao = db.photoEditRecipeDao(),
            dataStore = dataStore,
            moshi = moshi,
        )
    }

    @After
    fun teardown() {
        db.close()
        dataStoreScope.cancel()
        prefsFile.delete()
    }

    private fun backupFile(backup: TagDataBackup): File =
        File.createTempFile("backup_fixture", ".json").apply {
            writeText(moshi.adapter(TagDataBackup::class.java).toJson(backup))
        }

    private fun chatRow(
        id: String,
        type: String,
        content: String,
        metadata: String? = null,
        role: String? = null,
        partsJson: String? = null,
        timestamp: Long = 1L,
    ) = BackupChatMessage(
        id = id,
        sessionId = "s1",
        type = type,
        content = content,
        timestamp = timestamp,
        metadata = metadata,
        role = role,
        partsJson = partsJson,
    )

    private suspend fun import(backup: TagDataBackup) =
        repository.importFromFile(backupFile(backup))

    @Test
    fun `v5 package coarse-maps legacy, retired and terminal rows`() = runTest {
        val backup = TagDataBackup(
            exportedAt = 1L,
            tags = emptyList(),
            mediaTagMetadata = emptyList(),
            crossRefs = emptyList(),
            scanTasks = emptyList(),
            version = 5,
            chatSessions = listOf(BackupChatSession("s1", "会话", 1L, 1L)),
            chatMessages = listOf(
                // legacy 文本/图片类：直通 text/image（role 缺省按前缀推断）
                chatRow("m1", "user_text", "帮我整理照片"),
                chatRow("m2", "agent_text", "找到了 3 张"),
                chatRow("m3", "user_image", "/data/img.jpg"),
                chatRow("m4", "user_image_text", "看这张", """{"imageUri":"/i.jpg"}"""),
                // legacy 卡类：直通对应 tool_*/data_*
                chatRow("m5", "chart", "<svg/>"),
                chatRow(
                    "m6", "task_card", "",
                    """{"engineer_task":{"taskId":"t1","sourceText":"修一下","status":"COMPLETED","resultSummary":"已交付","startedAtMs":1,"updatedAtMs":2}}""",
                ),
                chatRow(
                    "m7", "optimize_candidates", "",
                    """{"sourceImageUri":"u","scene":"GENERAL","recommendedIndex":1,"drawIndex":1,"candidates":[{"direction":"base","thumbPath":"/t.jpg","nimaScore":6.5,"rejected":false}],"usedFingerprints":[]}""",
                ),
                // 退役卡：机器 JSON → Tombstone（data_ui 摘要提升可读字段）
                chatRow("m8", "media_results", """{"items":[{"uri":"content://1"}]}"""),
                chatRow("m9", "data_ui", """{"type":"data_ui","fallbackSummary":"A2UI 卡摘要"}"""),
                chatRow("m10", "data_media_results", """{"items":[]}"""),
                // v5 包中的终态值行（M1 后导出）：wire 直通
                chatRow("m11", "tool_chart", "<svg id=\"terminal\"/>"),
                // 不可识别旧值：整行跳过
                chatRow("m12", "claude_session", "{}"),
            ),
        )

        val result = import(backup)

        // 12 行中 1 行不可识别被跳过，其余全部恢复
        assertEquals(11, result.restoredChatMessageCount)
        assertEquals(11, db.chatMessageDao().getAllMessages().size)

        // 文本/图片直通（role 前缀推断：user_text → user）
        val m1 = db.chatMessageDao().getMessageById("m1")!!
        assertEquals(listOf(MessagePart.Text("p0", "帮我整理照片")), m1.decodeParts())
        assertEquals("user", m1.role)

        val m3 = db.chatMessageDao().getMessageById("m3")!!
        assertEquals(listOf(MessagePart.Image("p0", "/data/img.jpg")), m3.decodeParts())

        val m4 = db.chatMessageDao().getMessageById("m4")!!
        assertEquals(
            listOf(MessagePart.Image("p0", "/i.jpg"), MessagePart.Text("p1", "看这张")),
            m4.decodeParts(),
        )

        // 卡类直通：chart → Chart part（Room type 投影为终态值）
        val m5 = db.chatMessageDao().getMessageById("m5")!!
        assertEquals(listOf(MessagePart.Chart("p0", "<svg/>")), m5.decodeParts())
        assertEquals("tool_chart", m5.type)

        // task_card → TaskCard part（状态投影 COMPLETED → OUTPUT_AVAILABLE）
        val m6Parts = db.chatMessageDao().getMessageById("m6")!!.decodeParts()
        val taskPart = m6Parts.single() as MessagePart.TaskCard
        assertEquals("t1", taskPart.toolCallId)
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, taskPart.state)
        assertEquals("已交付", taskPart.task.resultSummary)

        // optimize_candidates → OptimizeCandidates part
        val m7Parts = db.chatMessageDao().getMessageById("m7")!!.decodeParts()
        val gacha = m7Parts.single() as MessagePart.OptimizeCandidates
        assertEquals("u", gacha.group.sourceImageUri)
        assertEquals(1, gacha.group.candidates.size)
        assertEquals(6.5f, gacha.group.candidates.single().nimaScore!!)

        // 退役卡 → Tombstone 不崩（originalType=旧值；data_ui 摘要提升）
        val m8Parts = db.chatMessageDao().getMessageById("m8")!!.decodeParts()
        assertEquals(MessagePart.Tombstone("p0", originalType = "media_results"), m8Parts.single())
        val m9Parts = db.chatMessageDao().getMessageById("m9")!!.decodeParts()
        assertEquals(
            MessagePart.Tombstone("p0", originalType = "data_ui", summary = "A2UI 卡摘要"),
            m9Parts.single(),
        )
        val m10Parts = db.chatMessageDao().getMessageById("m10")!!.decodeParts()
        assertEquals(MessagePart.Tombstone("p0", originalType = "data_media_results"), m10Parts.single())

        // 终态值行 wire 直通
        val m11 = db.chatMessageDao().getMessageById("m11")!!
        assertEquals(listOf(MessagePart.Chart("p0", "<svg id=\"terminal\"/>")), m11.decodeParts())
    }

    @Test
    fun `v5 package leaves non-chat sections intact`() = runTest {
        val backup = TagDataBackup(
            version = 5,
            exportedAt = 1L,
            tags = listOf(BackupTag(1, "海边", "scene")),
            mediaTagMetadata = emptyList(),
            crossRefs = emptyList(),
            scanTasks = emptyList(),
            memoryFacts = listOf(
                BackupMemoryFact("我喜欢拍日落", null, "chat", 1L, 1L),
            ),
        )

        val result = import(backup)

        assertEquals(1, result.restoredTagCount)
        assertEquals(1, result.restoredMemoryFactCount)
        assertEquals(1, db.tagDao().getAllTags().size)
    }

    @Test
    fun `v6 round-trips partsJson verbatim through export and import`() = runTest {
        val partsByRow = mapOf(
            "r1" to listOf(
                MessagePart.Reasoning("p0", "先想"),
                MessagePart.Text("p1", "3 月共 42 张"),
                MessagePart.SourceUrl("p2", "https://a.com", "https://a.com", "A"),
            ),
            "r2" to listOf(
                MessagePart.TaskCard(
                    "p0", "t1", ToolPartState.OUTPUT_AVAILABLE,
                    engineerTask(),
                ),
            ),
            "r3" to listOf(MessagePart.Tombstone("p0", "data_future_x", "未来卡")),
        )
        db.chatMessageDao().insertPartsMessages(
            partsByRow.map { (id, parts) ->
                PartsMessageRow(id = id, sessionId = "s1", role = ModelInputRole.ASSISTANT, parts = parts, timestamp = 1L)
            },
        )
        db.chatSessionDao().insertSession(ChatSessionEntity("s1", "会话", 1L, 1L))

        val exportFile = File.createTempFile("backup_v6", ".json")
        val export = repository.exportToFile(exportFile)

        // 导出包为 v6 且 chat 段携带 partsJson
        val exported = org.json.JSONObject(exportFile.readText())
        assertEquals(TagDataBackup.CURRENT_VERSION, exported.getInt("version"))
        assertTrue(exported.getJSONArray("chatMessages").getJSONObject(0).has("partsJson"))
        assertEquals(3, export.chatMessageCount)

        // 清库后恢复：parts 逐行等值（partsJson 直通，不经粗映射）
        db.chatMessageDao().deleteAllMessagesBySession("s1")
        val result = repository.importFromFile(exportFile)

        assertEquals(3, result.restoredChatMessageCount)
        partsByRow.forEach { (id, parts) ->
            assertEquals("row=$id", parts, db.chatMessageDao().getMessageById(id)!!.decodeParts())
        }
    }

    @Test
    fun `v6 row with missing or corrupt partsJson degrades without losing message`() = runTest {
        val backup = TagDataBackup(
            version = TagDataBackup.CURRENT_VERSION,
            exportedAt = 1L,
            tags = emptyList(),
            mediaTagMetadata = emptyList(),
            crossRefs = emptyList(),
            scanTasks = emptyList(),
            chatMessages = listOf(
                // partsJson 缺失（v5 字段形态混入 v6 容器）：纯文本 → Text 兜底
                chatRow("d1", "text", "纯文本说明"),
                // partsJson 损坏 + content 为机器 JSON blob → Tombstone 兜底
                chatRow("d2", "data_future_x", """{"fallbackSummary":"机器内容"}""", partsJson = "{broken"),
                // partsJson 空数组 → 降级 Text 兜底
                chatRow("d3", "text", "空 parts 行", partsJson = "[]"),
            ),
        )

        val result = import(backup)

        assertEquals(3, result.restoredChatMessageCount)
        assertEquals(
            listOf(MessagePart.Text("p0", "纯文本说明")),
            db.chatMessageDao().getMessageById("d1")!!.decodeParts(),
        )
        assertEquals(
            listOf(MessagePart.Tombstone("p0", originalType = "data_future_x", summary = "机器内容")),
            db.chatMessageDao().getMessageById("d2")!!.decodeParts(),
        )
        assertEquals(
            listOf(MessagePart.Text("p0", "空 parts 行")),
            db.chatMessageDao().getMessageById("d3")!!.decodeParts(),
        )
    }

    private fun engineerTask() = EngineerTaskState(
        taskId = "t1",
        sourceText = "修一下",
        status = EngineerTaskStatus.COMPLETED,
        resultSummary = "已交付",
        startedAtMs = 1L,
        updatedAtMs = 2L,
    )
}
