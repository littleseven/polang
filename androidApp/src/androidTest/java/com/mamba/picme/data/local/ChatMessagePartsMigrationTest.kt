package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.PartState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仅声明 [ChatMessageEntity] 的测试专用 Database：让 Room 真打开迁移后的库，
 * 触发其对 chat_messages 表的 schema 校验（列名/类型/可空性/主键逐字段比对实体声明）。
 * 不用 AppDatabase 本体的原因：手写 fixture 库只有 chat_messages 一张表，Room 对
 * AppDatabase 其余实体的校验必然失败——那不是本次迁移要验证的东西
 * （全库级重置断言见 Robolectric `Migration2728Test`）。
 */
@Database(entities = [ChatMessageEntity::class], version = 28, exportSchema = false)
abstract class ChatMessagesOnlyDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
}

/**
 * chat_messages 迁移测试（真机/instrumented），2026-11-08 值域终态后语义：
 * - MIGRATION_24_25 / 25_26 已瘦身为**纯 ADD COLUMN DDL**（原存量回填/全量改写数据工作
 *   随 legacy 机器出清删除——中间版本的 partsJson 内容不再有任何读者）；
 * - MIGRATION_27_28 **DROP + 按 v28 Entity 重建**：聊天行（legacy 13 值 / NULL partsJson /
 *   退役横滑卡）全部消亡，重建表可正常走 parts-first 写读。
 *
 * 每个用例两段式：
 * 1. 用 SupportSQLiteOpenHelper 按目标旧版本手建 chat_messages 表（本工程不导出
 *    schema JSON，无法用 MigrationTestHelper.createDatabase）并灌 fixture；
 * 2. 经 [Room.databaseBuilder]（[ChatMessagesOnlyDatabase]）真打开该库——Room 执行真实
 *    迁移链后做 **schema 校验**（onValidateSchema：重建 DDL 与 v28 Entity 逐字段比对，
 *    不一致直接抛 IllegalStateException 使本测试失败）。
 */
@RunWith(AndroidJUnit4::class)
class ChatMessagePartsMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var roomDb: ChatMessagesOnlyDatabase? = null

    /** v24 形状（无 partsJson / role 列）。列定义与 Room 实体产物一致（Kotlin 默认值不产生 SQL DEFAULT）。 */
    private val v24TableSql =
        """
        CREATE TABLE `chat_messages` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `sessionId` TEXT NOT NULL,
            `type` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `modelUsed` TEXT,
            `metadata` TEXT
        )
        """.trimIndent()

    /** v27 形状 = MIGRATION_24_25/25_26 的产物（partsJson 可空 + role 带 DEFAULT 'agent' 安全网）。 */
    private val v27TableSql =
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
        """.trimIndent()

    @After
    fun tearDown() {
        roomDb?.close()
        context.deleteDatabase(V24_CHAIN_DB_NAME)
        context.deleteDatabase(V27_DIRECT_DB_NAME)
    }

    /** v24 旧库经 24→25→26→27→28 全链升级：升级链不断（纯 DDL），v28 重置聊天表为空。 */
    @Test
    fun v24ChainedMigrationsReachV28WithChatTableReset() {
        createLegacyDatabaseFile(V24_CHAIN_DB_NAME, 24, v24TableSql) {
            insertLegacyRow("m-user", "user_text", "帮我找海边的照片")
            insertLegacyRow("m-task", "task_card", "修复编译", """{"engineer_task":{"taskId":"t1"}}""")
        }

        val db = openMigrated(
            V24_CHAIN_DB_NAME,
            AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_25_26,
            AppDatabase.MIGRATION_26_27, AppDatabase.MIGRATION_27_28,
        )

        assertChatTableReset(db)
    }

    /** v27 存量库直迁 28（现役设备主路径）：legacy 行/NULL partsJson 行全部消亡。 */
    @Test
    fun v27MigrationResetsChatTableAndKeepsChainUnbroken() {
        createLegacyDatabaseFile(V27_DIRECT_DB_NAME, 27, v27TableSql) {
            insertLegacyRow("m-legacy", "user_text", "老文本", withLegacyColumns = true)
            insertLegacyRow(
                "m-media", "media_results",
                """[{"id":7,"uri":"content://m/7"}]""",
                """{"query":"海边"}""",
                withLegacyColumns = true,
            )
            insertLegacyRow("m-nullparts", "text", "瘦身迁移后 partsJson 留空的行", withLegacyColumns = true)
        }

        val db = openMigrated(V27_DIRECT_DB_NAME, AppDatabase.MIGRATION_27_28)

        assertChatTableReset(db)
    }

    // ── fixture 构造 ─────────────────────────────────────────────

    private fun createLegacyDatabaseFile(
        dbName: String,
        version: Int,
        createTableSql: String,
        insertFixtures: SupportSQLiteDatabase.() -> Unit,
    ) {
        context.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onConfigure(db: SupportSQLiteDatabase) {}
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createTableSql)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).apply {
            writableDatabase.insertFixtures()
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertLegacyRow(
        id: String,
        type: String,
        content: String,
        metadata: String? = null,
        withLegacyColumns: Boolean = false,
    ) {
        val legacyColumns = if (withLegacyColumns) ", `partsJson`, `role`" else ""
        val legacyValues = if (withLegacyColumns) ", NULL, 'agent'" else ""
        execSQL(
            "INSERT INTO `chat_messages` (`id`, `sessionId`, `type`, `content`, `timestamp`, `metadata`$legacyColumns) VALUES (?, 's', ?, ?, 1000, ?$legacyValues)",
            arrayOf(id, type, content, metadata),
        )
    }

    // ── Room 打开 + 断言 ─────────────────────────────────────────

    /** Room 真打开：执行传入的真实迁移并做 schema 校验（校验不过此处直接抛异常）。 */
    private fun openMigrated(dbName: String, vararg migrations: Migration): ChatMessagesOnlyDatabase =
        Room.databaseBuilder(context, ChatMessagesOnlyDatabase::class.java, dbName)
            .addMigrations(*migrations)
            .build()
            .also { roomDb = it }

    /** v28 重置语义：表空；重建表可正常走 parts-first 写读往返（含 NOT NULL partsJson）。 */
    private fun assertChatTableReset(db: ChatMessagesOnlyDatabase) = runBlocking {
        val dao = db.chatMessageDao()
        assertTrue(dao.getRecentMessages("s", 100).isEmpty())
        assertTrue(dao.getMessageCount("s") == 0)

        val id = dao.insertPartsMessage(
            sessionId = "s",
            role = ModelInputRole.USER,
            parts = listOf(MessagePart.Text("p0", "重置后的新消息", state = PartState.DONE)),
        )
        val row = dao.getMessageById(id)!!
        assertEquals("重置后的新消息", row.content)
        assertEquals("text", row.type)
        assertEquals("user", row.role)
    }

    private companion object {
        const val V24_CHAIN_DB_NAME = "chat-parts-migration-test.db"
        const val V27_DIRECT_DB_NAME = "chat-v28-reset-migration-test.db"
    }
}
