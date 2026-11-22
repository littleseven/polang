package com.mamba.picme.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.entity.ChatImageCacheEntity
import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.PartState
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
 * MIGRATION_27_28 端到端测试（Robolectric，spec《chat-value-domain-ideal》§4）：
 * chat 值域终态聊天表重置——chat 两表 DROP 重建为空，非聊天表内容逐行保留，
 * chat_image_cache 显式保留不清。
 *
 * 构造策略（本工程 exportSchema=false，无 schema JSON 可供 MigrationTestHelper）：
 * 1. 先用 Room 以 v28 现行 Entity **真建库**（全部表由 Room 生成，DDL 必然正确）并灌
 *    非聊天表 fixture；
 * 2. 裸 SQLite 把库降回 v27 存量形态：user_version=27、chat_messages 重塑为 v27 形状
 *    （partsJson 可空 / role 带 DEFAULT 'agent' 安全网，即 MIGRATION_24_25/25_26 的产物）
 *    并灌 legacy 行（NULL partsJson、legacy 13 值、退役横滑卡）、清空 room_master 身份；
 * 3. 重新经 Room 打开（挂 MIGRATION_27_28）——onUpgrade 执行迁移，随后身份缺失触发
 *    **全库 schema 校验**（onValidateSchema：重建 DDL 与 v28 Entity 逐字段比对，不一致
 *    直接抛 IllegalStateException 使本测试失败）；
 * 4. 断言重置语义 + 重建表可正常走 parts-first 写读往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Migration2728Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun teardown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `v27 legacy database upgrades to v28 resetting chat tables and keeping others`() = runTest {
        // ── Step 1：Room 真建 v28 库 + 非聊天表 fixture（含 chat 两表初始数据，降级前铺垫）──
        val created = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        val mediaId = created.mediaDao().insertMedia(
            MediaEntity(uri = "content://media/1", type = MediaType.PHOTO, captureDate = 100L, fileName = "sea.jpg"),
        )
        created.memoryFactDao().insert(
            MemoryFactEntity(content = "喜欢海边照片", category = "偏好", source = "CHAT_TOOL"),
        )
        created.chatImageCacheDao().upsert(
            ChatImageCacheEntity(
                filePath = "/data/user/0/chat_edit_cache/edit_1.jpg",
                sessionId = "s1",
                createdAt = 1L,
                lastAccessedAt = 2L,
                sizeBytes = 1234L,
                status = "ACTIVE",
            ),
        )
        created.chatSessionDao().insertSession(ChatSessionEntity(sessionId = "s1", title = "旧会话", createdAt = 1L, updatedAt = 2L))
        created.chatMessageDao().insertMessage(
            ChatMessageEntity(
                id = "fresh-1", sessionId = "s1", type = "text", role = "agent",
                content = "v28 行", timestamp = 1L,
                partsJson = """[{"type":"text","partId":"p0","markdown":"v28 行","state":"DONE"}]""",
            ),
        )
        created.close()

        // ── Step 2：降回 v27 存量形态（chat_messages 重塑 + legacy 行 + 身份清空）──
        downgradeToV27Fixture()

        // ── Step 3：Room 重开——执行 MIGRATION_27_28 并做全库 schema 校验（DDL 不匹配即抛）──
        // 库版本 29 起须补挂 MIGRATION_28_29（media_assets 加两列，对 chat 重置语义无影响）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_27_28, AppDatabase.MIGRATION_28_29)
            .allowMainThreadQueries()
            .build()
        val messageDao = db.chatMessageDao()

        // ── Step 4：断言重置语义 ──
        assertEquals(0, messageDao.getRecentMessages("s1", 100).size)
        assertEquals(emptyList<ChatSessionEntity>(), db.chatSessionDao().getAllSessionsNow())

        val raw = db.openHelper.writableDatabase
        assertEquals(mediaId, queryLong(raw, "SELECT `id` FROM `media_assets` WHERE `uri` = 'content://media/1'"))
        assertEquals(1, queryLong(raw, "SELECT COUNT(*) FROM `memory_facts` WHERE `content` = '喜欢海边照片'"))
        assertEquals(
            1,
            queryLong(
                raw,
                "SELECT COUNT(*) FROM `chat_image_cache` WHERE `filePath` = '/data/user/0/chat_edit_cache/edit_1.jpg' AND `status` = 'ACTIVE'",
            ),
        )
        // 其余表结构原样（local_handle 等 v27 既有表随迁移不动，仍在）
        assertEquals(
            1,
            queryLong(
                raw,
                "SELECT COUNT(*) FROM `sqlite_master` WHERE `type`='table' AND `name`='local_handle'",
            ),
        )

        // ── 重建表可正常走 parts-first 写读往返 ──
        val id = messageDao.insertPartsMessage(
            sessionId = "s1",
            role = ModelInputRole.USER,
            parts = listOf(MessagePart.Text("p0", "重置后的新消息", state = PartState.DONE)),
        )
        val row = messageDao.getMessageById(id)!!
        assertEquals("重置后的新消息", row.content)
        assertEquals("text", row.type)
        db.close()
    }

    /** 把 v28 库降回 v27 存量形态：版本号、chat_messages 形状与 legacy 行、room_master 身份；media_assets 去 v29 两列。 */
    private fun downgradeToV27Fixture() {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.use { raw ->
            raw.execSQL("PRAGMA user_version = 27")
            // media_assets 回到 v28 前形态（relativePath/sizeBytes 是 v29 才加的列）
            MediaAssetsPreV29Downgrader.downgrade(raw)
            // chat_sessions 为 v28 形态即可（27→28 对它无条件 DROP，形状不影响结果），保留 Step 1 行。
            raw.execSQL("DROP TABLE IF EXISTS `chat_messages`")
            raw.execSQL(
                """
                CREATE TABLE `chat_messages` (
                    `id` TEXT NOT NULL PRIMARY KEY,
                    `sessionId` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `modelUsed` TEXT,
                    `metadata` TEXT,
                    `partsJson` TEXT,
                    `role` TEXT NOT NULL DEFAULT 'agent'
                )
                """.trimIndent(),
            )
            // legacy 行样本：v26 改写前的老值 + NULL partsJson（24_25/25_26 瘦身后升级链不再回填）+
            // 退役横滑卡行——v28 重置后均应消亡
            raw.execSQL(
                "INSERT INTO `chat_messages` (`id`, `sessionId`, `type`, `content`, `timestamp`, `role`) VALUES ('m1', 's1', 'user_text', '帮我找海边的照片', 1000, 'user')",
            )
            raw.execSQL(
                "INSERT INTO `chat_messages` (`id`, `sessionId`, `type`, `content`, `timestamp`, `metadata`, `role`) VALUES ('m2', 's1', 'media_results', '[{\"id\":7}]', 1001, '{\"query\":\"海边\"}', 'agent')",
            )
            // 身份哈希清空：重开时强制走全库 onValidateSchema（迁移 DDL 与 Entity 逐字段比对）
            raw.execSQL("DELETE FROM `room_master_table`")
        }
    }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    private companion object {
        const val DB_NAME = "migration-2728-test.db"
    }
}
