package com.mamba.picme.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MIGRATION_24_25 端到端迁移测试（真机/instrumented）：v24 schema → ALTER ADD partsJson → 全量回填。
 *
 * 不依赖 room-testing 的 MigrationTestHelper：直接用 SupportSQLiteOpenHelper 以版本 24
 * 建 v24 形状的 chat_messages 表，执行真实 [AppDatabase.MIGRATION_24_25]，再逐行断言。
 * 覆盖：13 类抽样 + 未知类型 + 损坏 metadata → 全部不丢消息（parts 非空，坏行 Text 兜底）。
 */
@RunWith(AndroidJUnit4::class)
class ChatMessagePartsMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** v24 形状（无 partsJson 列）。 */
    private fun createV24Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                override fun onConfigure(db: SupportSQLiteDatabase) {}
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE `chat_messages` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `sessionId` TEXT NOT NULL DEFAULT 'default',
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
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun insertLegacyRow(
        db: SupportSQLiteDatabase,
        id: String,
        type: String,
        content: String,
        metadata: String? = null,
    ) {
        db.execSQL(
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
        val db = createV24Database()
        insertLegacyRow(db, "m-user", "user_text", "帮我找海边的照片")
        insertLegacyRow(db, "m-agent", "agent_text", "找到了 3 张", """{"prompt_len":10}""")
        insertLegacyRow(db, "m-img", "user_image", "/data/img.jpg")
        insertLegacyRow(db, "m-imgtext", "user_image_text", "调亮一点", """{"imageUri":"file:///x.jpg"}""")
        insertLegacyRow(db, "m-aimg", "agent_image", "已生成", """{"imageUri":"file:///y.jpg","saved":true}""")
        insertLegacyRow(db, "m-edit", "agent_edit_result", "已提亮", """{"imageUri":"file:///z.jpg","suggestions":["微调"]}""")
        insertLegacyRow(db, "m-cmd", "command", "search:猫")
        insertLegacyRow(db, "m-plan", "plan_preview", "计划预览")
        insertLegacyRow(
            db, "m-media", "media_results",
            """[{"id":7,"uri":"content://m/7","type":"PHOTO","captureDate":123,"fileName":"a.jpg"}]""",
            """{"query":"海边","totalCount":99,"isRefinement":false}""",
        )
        insertLegacyRow(db, "m-chart", "chart", "<svg/>")
        insertLegacyRow(db, "m-html", "html_card", "<html/>", """{"html_card":{"display":"inline","summary":"周报"}}""")
        insertLegacyRow(
            db, "m-task", "task_card", "修复编译",
            """{"engineer_task":{"taskId":"t1","sourceText":"修复编译","status":"RUNNING","startedAtMs":1,"updatedAtMs":2}}""",
        )
        insertLegacyRow(
            db, "m-gacha", "optimize_candidates", "挑一张",
            """{"sourceImageUri":"u","scene":"人像","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}""",
        )
        // 边界行：未知类型 + 损坏 metadata（必须兜底不丢消息）
        insertLegacyRow(db, "m-unknown", "future_type", "未来类型原文")
        insertLegacyRow(db, "m-broken", "task_card", "损坏的任务卡", """{"engineer_task":{oops""")

        AppDatabase.MIGRATION_24_25.migrate(db)

        // 行数不变：不丢消息
        val countCursor = db.query("SELECT COUNT(*) FROM `chat_messages`")
        countCursor.use {
            it.moveToFirst()
            assertEquals(15, it.getInt(0))
        }

        assertEquals("帮我找海边的照片", (readParts(db, "m-user").single() as MessagePart.Text).markdown)
        assertEquals("找到了 3 张", (readParts(db, "m-agent").single() as MessagePart.Text).markdown)

        assertEquals("/data/img.jpg", (readParts(db, "m-img").single() as MessagePart.Image).ref)

        val imgText = readParts(db, "m-imgtext")
        assertEquals(2, imgText.size)
        assertEquals("file:///x.jpg", (imgText[0] as MessagePart.Image).ref)
        assertEquals("调亮一点", (imgText[1] as MessagePart.Text).markdown)

        val aimg = readParts(db, "m-aimg").single() as MessagePart.Image
        assertEquals("file:///y.jpg", aimg.ref)
        assertEquals(true, aimg.saved)

        val edit = readParts(db, "m-edit").single() as MessagePart.EditResult
        assertEquals("file:///z.jpg", edit.ref)
        assertEquals(listOf("微调"), edit.suggestions)

        assertEquals("search:猫", (readParts(db, "m-cmd").single() as MessagePart.Text).markdown)
        assertEquals("计划预览", (readParts(db, "m-plan").single() as MessagePart.Text).markdown)

        val media = readParts(db, "m-media").single() as MessagePart.MediaResults
        assertEquals("海边", media.results.query)
        assertEquals(99, media.results.totalCount)
        assertEquals(1, media.results.assets.size)

        assertEquals("<svg/>", (readParts(db, "m-chart").single() as MessagePart.Chart).svg)

        val html = readParts(db, "m-html").single() as MessagePart.HtmlCard
        assertEquals("<html/>", html.html)
        assertEquals("inline", html.meta.display)
        assertEquals("周报", html.meta.summary)

        val task = readParts(db, "m-task").single() as MessagePart.TaskCard
        assertEquals("t1", task.toolCallId)
        assertEquals("修复编译", task.task.sourceText)

        val gacha = readParts(db, "m-gacha").single() as MessagePart.OptimizeCandidates
        assertEquals("人像", gacha.group.scene)

        // 兜底行：原文保留为 Text part
        assertEquals("未来类型原文", (readParts(db, "m-unknown").single() as MessagePart.Text).markdown)
        assertEquals("损坏的任务卡", (readParts(db, "m-broken").single() as MessagePart.Text).markdown)
    }
}
