package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仅声明 [ChatMessageEntity] 的测试专用 Database：让 Room 真打开迁移后的库，
 * 触发其对 chat_messages 表的 schema 校验（列名/类型/可空性/默认值逐字段比对实体声明）。
 * 不用 AppDatabase 本体的原因：手写 v24 fixture 库只有 chat_messages 一张表，Room 对
 * AppDatabase 其余 20 个实体的校验必然失败——那不是本次迁移要验证的东西。
 */
@Database(entities = [ChatMessageEntity::class], version = 25, exportSchema = false)
abstract class ChatMessagesOnlyDatabase : RoomDatabase()

/**
 * MIGRATION_24_25 端到端迁移测试（真机/instrumented）：v24 schema → ALTER ADD partsJson → 全量回填。
 *
 * 两段式：
 * 1. 用 SupportSQLiteOpenHelper 以版本 24 建 v24 形状的 chat_messages 表（文件库）并灌 fixture；
 * 2. 经 [Room.databaseBuilder]（[ChatMessagesOnlyDatabase]）真打开该库——Room 找到 24→25 迁移
 *    路径后执行真实 [AppDatabase.MIGRATION_24_25]，随后对迁移结果做 **schema 校验**
 *    （onValidateSchema：列名/类型/可空性/默认值与 @Entity 声明逐字段比对，不一致直接抛
 *    IllegalStateException 使本测试失败）。
 *
 * 覆盖：13 类抽样 + 未知类型 + 损坏 metadata → 全部不丢消息（parts 非空，坏行 Text 兜底）。
 */
@RunWith(AndroidJUnit4::class)
class ChatMessagePartsMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var roomDb: ChatMessagesOnlyDatabase? = null

    @After
    fun tearDown() {
        roomDb?.close()
        context.deleteDatabase(DB_NAME)
    }

    /**
     * v24 形状（无 partsJson 列）。
     * 注意：列定义必须与 Room 由实体生成的真实 v24 产物一致——Kotlin 属性默认值不产生
     * SQL DEFAULT（无 @ColumnInfo defaultValue），写 `DEFAULT 'default'` 会过不了 §2 的校验。
     */
    private fun createV24DatabaseFile() {
        context.deleteDatabase(DB_NAME)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                override fun onConfigure(db: SupportSQLiteDatabase) {}
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
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
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).apply {
            writableDatabase.insertFixtures()
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertFixtures() {
        insertLegacyRow("m-user", "user_text", "帮我找海边的照片")
        insertLegacyRow("m-agent", "agent_text", "找到了 3 张", """{"prompt_len":10}""")
        insertLegacyRow("m-img", "user_image", "/data/img.jpg")
        insertLegacyRow("m-imgtext", "user_image_text", "调亮一点", """{"imageUri":"file:///x.jpg"}""")
        insertLegacyRow("m-aimg", "agent_image", "已生成", """{"imageUri":"file:///y.jpg","saved":true}""")
        insertLegacyRow("m-edit", "agent_edit_result", "已提亮", """{"imageUri":"file:///z.jpg","suggestions":["微调"]}""")
        insertLegacyRow("m-cmd", "command", "search:猫")
        insertLegacyRow("m-plan", "plan_preview", "计划预览")
        insertLegacyRow(
            "m-media", "media_results",
            """[{"id":7,"uri":"content://m/7","type":"PHOTO","captureDate":123,"fileName":"a.jpg"}]""",
            """{"query":"海边","totalCount":99,"isRefinement":false}""",
        )
        insertLegacyRow("m-chart", "chart", "<svg/>")
        insertLegacyRow("m-html", "html_card", "<html/>", """{"html_card":{"display":"inline","summary":"周报"}}""")
        insertLegacyRow(
            "m-task", "task_card", "修复编译",
            """{"engineer_task":{"taskId":"t1","sourceText":"修复编译","status":"RUNNING","startedAtMs":1,"updatedAtMs":2}}""",
        )
        insertLegacyRow(
            "m-gacha", "optimize_candidates", "挑一张",
            """{"sourceImageUri":"u","scene":"人像","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}""",
        )
        // 边界行：未知类型 + 损坏 metadata（必须兜底不丢消息）
        insertLegacyRow("m-unknown", "future_type", "未来类型原文")
        insertLegacyRow("m-broken", "task_card", "损坏的任务卡", """{"engineer_task":{oops""")
    }

    private fun SupportSQLiteDatabase.insertLegacyRow(
        id: String,
        type: String,
        content: String,
        metadata: String? = null,
    ) {
        execSQL(
            "INSERT INTO `chat_messages` (`id`, `sessionId`, `type`, `content`, `timestamp`, `metadata`) VALUES (?, 's', ?, ?, 1000, ?)",
            arrayOf(id, type, content, metadata),
        )
    }

    private fun readParts(db: SupportSQLiteDatabase, id: String): List<MessagePart> {
        val cursor = db.query("SELECT `partsJson` FROM `chat_messages` WHERE `id` = ?", arrayOf(id))
        cursor.use {
            assertTrue("row $id missing after migration", it.moveToFirst())
            assertEquals("partsJson column missing for $id", 0, it.getColumnIndex("partsJson"))
            val json = if (it.isNull(0)) null else it.getString(0)
            val parts = MessagePartsCodec.decode(json)
            assertNotNull("row $id partsJson undecodable: $json", parts)
            return parts!!
        }
    }

    @Test
    fun migrationBackfillsAllLegacyTypesWithoutLosingMessages() {
        createV24DatabaseFile()

        // Room 真打开：执行真实 MIGRATION_24_25 并做 schema 校验（校验不过此处直接抛异常）
        val db = Room
            .databaseBuilder(context, ChatMessagesOnlyDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_24_25)
            .build()
            .also { roomDb = it }
        db.openHelper.writableDatabase.let { writable ->
            // 行数不变：不丢消息
            val countCursor = writable.query("SELECT COUNT(*) FROM `chat_messages`")
            countCursor.use {
                it.moveToFirst()
                assertEquals(15, it.getInt(0))
            }
        }

        val readable = db.openHelper.readableDatabase
        assertEquals("帮我找海边的照片", (readParts(readable, "m-user").single() as MessagePart.Text).markdown)
        assertEquals("找到了 3 张", (readParts(readable, "m-agent").single() as MessagePart.Text).markdown)

        assertEquals("/data/img.jpg", (readParts(readable, "m-img").single() as MessagePart.Image).ref)

        val imgText = readParts(readable, "m-imgtext")
        assertEquals(2, imgText.size)
        assertEquals("file:///x.jpg", (imgText[0] as MessagePart.Image).ref)
        assertEquals("调亮一点", (imgText[1] as MessagePart.Text).markdown)

        val aimg = readParts(readable, "m-aimg").single() as MessagePart.Image
        assertEquals("file:///y.jpg", aimg.ref)
        assertEquals(true, aimg.saved)

        val edit = readParts(readable, "m-edit").single() as MessagePart.EditResult
        assertEquals("file:///z.jpg", edit.ref)
        assertEquals(listOf("微调"), edit.suggestions)

        assertEquals("search:猫", (readParts(readable, "m-cmd").single() as MessagePart.Text).markdown)
        assertEquals("计划预览", (readParts(readable, "m-plan").single() as MessagePart.Text).markdown)

        val media = readParts(readable, "m-media").single() as MessagePart.MediaResults
        assertEquals("海边", media.results.query)
        assertEquals(99, media.results.totalCount)
        assertEquals(1, media.results.assets.size)

        assertEquals("<svg/>", (readParts(readable, "m-chart").single() as MessagePart.Chart).svg)

        val html = readParts(readable, "m-html").single() as MessagePart.HtmlCard
        assertEquals("<html/>", html.html)
        assertEquals("inline", html.meta.display)
        assertEquals("周报", html.meta.summary)

        val task = readParts(readable, "m-task").single() as MessagePart.TaskCard
        assertEquals("t1", task.toolCallId)
        assertEquals("修复编译", task.task.sourceText)

        val gacha = readParts(readable, "m-gacha").single() as MessagePart.OptimizeCandidates
        assertEquals("人像", gacha.group.scene)

        // 兜底行：原文保留为 Text part
        assertEquals("未来类型原文", (readParts(readable, "m-unknown").single() as MessagePart.Text).markdown)
        assertEquals("损坏的任务卡", (readParts(readable, "m-broken").single() as MessagePart.Text).markdown)
    }

    private companion object {
        const val DB_NAME = "chat-parts-migration-test.db"
    }
}
