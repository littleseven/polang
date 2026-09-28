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
import com.mamba.picme.domain.chat.MessagePartsCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仅声明 [ChatMessageEntity] 的测试专用 Database：让 Room 真打开迁移后的库，
 * 触发其对 chat_messages 表的 schema 校验（列名/类型/可空性逐字段比对实体声明；
 * `role` 列 NOT NULL 且实体未声明 defaultValue——Room 对 NOT NULL 列不比对 SQL DEFAULT，
 * 故迁移中 `DEFAULT 'agent'` 安全网不导致校验失败）。
 * 不用 AppDatabase 本体的原因：手写 fixture 库只有 chat_messages 一张表，Room 对
 * AppDatabase 其余 20 个实体的校验必然失败——那不是本次迁移要验证的东西。
 */
@Database(entities = [ChatMessageEntity::class], version = 26, exportSchema = false)
abstract class ChatMessagesOnlyDatabase : RoomDatabase()

/**
 * chat_messages 迁移端到端测试（真机/instrumented）：
 * - v24→v25→v26 串联（[AppDatabase.MIGRATION_24_25] +partsJson 回填 → [AppDatabase.MIGRATION_25_26]
 *   +role 列 + type/role/partsJson 全量改写，legacy 13 值 → 新 8 值）；
 * - v25→v26 直迁（已是 v25 的存量库：手建 v25 schema + 全量 fixture 改写）。
 *
 * 每个用例两段式：
 * 1. 用 SupportSQLiteOpenHelper 按目标旧版本手建 chat_messages 表（文件库；本工程不导出
 *    schema JSON，无法用 MigrationTestHelper.createDatabase）并灌 fixture；
 * 2. 经 [Room.databaseBuilder]（[ChatMessagesOnlyDatabase]）真打开该库——Room 找到迁移路径后
 *    执行真实迁移，随后对迁移结果做 **schema 校验**（onValidateSchema：列名/类型/可空性与
 *    @Entity 声明逐字段比对，不一致直接抛 IllegalStateException 使本测试失败）。
 *
 * 覆盖：13 legacy 值各一行 + 未知类型 + 损坏 metadata + 空 content → 逐行断言三件套
 * （`type` 新值 / `role` / partsJson 解码语义等价），全部不丢消息。
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

    /** v25 形状（有 partsJson，无 role 列）= MIGRATION_24_25 的表形状。 */
    private val v25TableSql =
        """
        CREATE TABLE `chat_messages` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `sessionId` TEXT NOT NULL,
            `type` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `modelUsed` TEXT,
            `metadata` TEXT,
            `partsJson` TEXT
        )
        """.trimIndent()

    /**
     * 迁移后 type/role 断言表（13→8 矩阵，镜像 shared `LegacyChatTypeMigrationTest`；
     * 未知类型 → text/agent 兜底）。
     *
     * m-broken（legacy task_card + 损坏 metadata）断言 `tool_task` 而非 `text`：损坏 metadata
     * 的兜底发生在 MessagePartsConverter 行级 runCatching（parts 退化为原文 Text），而
     * type/role 映射在 `LegacyChatTypeMigration.map` 内与 metadata 无关、照常成立；
     * [migrateChatMessageTypes] 外层 ("text","agent") 双保险仅在 map 本身抛异常时可达
     * （对字符串输入实际不可达，见其源码注释"理论不可达"）。
     */
    private val expectedTypeRole: Map<String, Pair<String, String>> = mapOf(
        "m-user" to ("text" to "user"),
        "m-agent" to ("text" to "agent"),
        "m-cmd" to ("text" to "agent"),
        "m-plan" to ("text" to "agent"),
        "m-img" to ("image" to "user"),
        "m-imgtext" to ("image" to "user"),
        "m-aimg" to ("image" to "agent"),
        "m-chart" to ("tool_chart" to "agent"),
        "m-html" to ("tool_html" to "agent"),
        "m-task" to ("tool_task" to "agent"),
        "m-edit" to ("tool_image_edit" to "agent"),
        "m-media" to ("data_media_results" to "agent"),
        "m-gacha" to ("data_optimize_candidates" to "agent"),
        "m-unknown" to ("text" to "agent"),
        "m-broken" to ("tool_task" to "agent"),
        // 空文本边界行（legacy user_text + 空 content）：type/role 映射只看 legacy type，照常改写
        "m-empty" to ("text" to "user"),
    )

    @After
    fun tearDown() {
        roomDb?.close()
        context.deleteDatabase(V24_CHAIN_DB_NAME)
        context.deleteDatabase(V25_DIRECT_DB_NAME)
    }

    /** Step 1：v24 旧库经 24→25→26 双迁移串联——partsJson 仍在且为新值编码，type/role 全量改写。 */
    @Test
    fun v24ChainedMigrationsRewriteTaxonomyAndKeepParts() {
        createLegacyDatabaseFile(V24_CHAIN_DB_NAME, 24, v24TableSql) {
            insertLegacyMatrixFixtures(withPartsJsonColumn = false)
            // 空 content 行：与 v25 直迁用例对齐，双迁移链路均覆盖空文本边界
            insertLegacyRow("m-empty", "user_text", "", withPartsJsonColumn = false)
        }

        val db = openMigrated(V24_CHAIN_DB_NAME, AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_25_26)

        assertRowCount(db, 16)
        assertTaxonomyRewritten(db)
        assertRewrittenParts(db)
    }

    /** Step 2：v25 存量库直迁 26——手建 v25 schema（无 role 列），逐行断言 type/role/parts 三件套。 */
    @Test
    fun v25MigrationRewritesEveryRowWithTypeRoleAndParts() {
        createLegacyDatabaseFile(V25_DIRECT_DB_NAME, 25, v25TableSql) {
            insertLegacyMatrixFixtures(withPartsJsonColumn = true)
            // 空 content 行：兜底产出空 markdown 的 Text part，不崩溃、不丢行
            insertLegacyRow("m-empty", "user_text", "", withPartsJsonColumn = true)
        }

        val db = openMigrated(V25_DIRECT_DB_NAME, AppDatabase.MIGRATION_25_26)

        assertRowCount(db, 16)
        assertTaxonomyRewritten(db)
        assertRewrittenParts(db)
        assertEquals("", (readParts(db, "m-empty").single() as MessagePart.Text).markdown)
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

    /**
     * 迁移源 fixture：13 legacy 值各一行 + 未知类型 + 损坏 metadata。
     * [withPartsJsonColumn]：v25 fixture 显式灌 NULL partsJson（v24 表无此列）——
     * v26 改写不读旧 partsJson，从 (type, content, metadata) 现算覆盖。
     */
    private fun SupportSQLiteDatabase.insertLegacyMatrixFixtures(withPartsJsonColumn: Boolean) {
        insertLegacyRow("m-user", "user_text", "帮我找海边的照片", withPartsJsonColumn = withPartsJsonColumn)
        insertLegacyRow("m-agent", "agent_text", "找到了 3 张", """{"prompt_len":10}""", withPartsJsonColumn)
        insertLegacyRow("m-cmd", "command", "search:猫", withPartsJsonColumn = withPartsJsonColumn)
        insertLegacyRow("m-plan", "plan_preview", "计划预览", withPartsJsonColumn = withPartsJsonColumn)
        insertLegacyRow("m-img", "user_image", "/data/img.jpg", withPartsJsonColumn = withPartsJsonColumn)
        insertLegacyRow("m-imgtext", "user_image_text", "调亮一点", """{"imageUri":"file:///x.jpg"}""", withPartsJsonColumn)
        insertLegacyRow("m-aimg", "agent_image", "已生成", """{"imageUri":"file:///y.jpg","saved":true}""", withPartsJsonColumn)
        insertLegacyRow("m-edit", "agent_edit_result", "已提亮", """{"imageUri":"file:///z.jpg","suggestions":["微调"]}""", withPartsJsonColumn)
        insertLegacyRow("m-media", "media_results",
            """[{"id":7,"uri":"content://m/7","type":"PHOTO","captureDate":123,"fileName":"a.jpg"}]""",
            """{"query":"海边","totalCount":99,"isRefinement":false}""", withPartsJsonColumn)
        insertLegacyRow("m-chart", "chart", "<svg/>", withPartsJsonColumn = withPartsJsonColumn)
        insertLegacyRow("m-html", "html_card", "<html/>", """{"html_card":{"display":"inline","summary":"周报"}}""", withPartsJsonColumn)
        insertLegacyRow("m-task", "task_card", "修复编译",
            """{"engineer_task":{"taskId":"t1","sourceText":"修复编译","status":"RUNNING","startedAtMs":1,"updatedAtMs":2}}""",
            withPartsJsonColumn)
        insertLegacyRow("m-gacha", "optimize_candidates", "挑一张",
            """{"sourceImageUri":"u","scene":"人像","recommendedIndex":0,"drawIndex":1,"candidates":[],"usedFingerprints":[]}""",
            withPartsJsonColumn)
        // 边界行：未知类型 + 损坏 metadata（必须兜底不丢消息）
        insertLegacyRow("m-unknown", "future_type", "未来类型原文", withPartsJsonColumn = withPartsJsonColumn)
        insertLegacyRow("m-broken", "task_card", "损坏的任务卡", """{"engineer_task":{oops""", withPartsJsonColumn)
    }

    private fun SupportSQLiteDatabase.insertLegacyRow(
        id: String,
        type: String,
        content: String,
        metadata: String? = null,
        withPartsJsonColumn: Boolean = false,
    ) {
        val partsColumn = if (withPartsJsonColumn) ", `partsJson`" else ""
        val partsValue = if (withPartsJsonColumn) ", NULL" else ""
        execSQL(
            "INSERT INTO `chat_messages` (`id`, `sessionId`, `type`, `content`, `timestamp`, `metadata`$partsColumn) VALUES (?, 's', ?, ?, 1000, ?$partsValue)",
            arrayOf(id, type, content, metadata),
        )
    }

    // ── Room 打开 + 断言 ─────────────────────────────────────────

    /** Room 真打开：执行传入的真实迁移并做 schema 校验（校验不过此处直接抛异常）。 */
    private fun openMigrated(dbName: String, vararg migrations: Migration): SupportSQLiteDatabase {
        val db = Room.databaseBuilder(context, ChatMessagesOnlyDatabase::class.java, dbName)
            .addMigrations(*migrations)
            .build()
            .also { roomDb = it }
        return db.openHelper.writableDatabase
    }

    private fun assertRowCount(db: SupportSQLiteDatabase, expected: Int) {
        val countCursor = db.query("SELECT COUNT(*) FROM `chat_messages`")
        countCursor.use {
            it.moveToFirst()
            assertEquals(expected, it.getInt(0))
        }
    }

    /** 逐行断言 type 新值 + role（spec §8 矩阵；含 unknown/损坏行兜底）。 */
    private fun assertTaxonomyRewritten(db: SupportSQLiteDatabase) {
        expectedTypeRole.forEach { (id, expected) ->
            val cursor = db.query("SELECT `type`, `role` FROM `chat_messages` WHERE `id` = ?", arrayOf(id))
            cursor.use {
                assertTrue("row $id missing after migration", it.moveToFirst())
                assertEquals("type for $id", expected.first, it.getString(0))
                assertEquals("role for $id", expected.second, it.getString(1))
            }
        }
    }

    /** 逐行断言 partsJson 可解码且语义等价（含兜底行原文保留）。 */
    private fun assertRewrittenParts(db: SupportSQLiteDatabase) {
        assertEquals("帮我找海边的照片", (readParts(db, "m-user").single() as MessagePart.Text).markdown)
        assertEquals("找到了 3 张", (readParts(db, "m-agent").single() as MessagePart.Text).markdown)
        assertEquals("search:猫", (readParts(db, "m-cmd").single() as MessagePart.Text).markdown)
        assertEquals("计划预览", (readParts(db, "m-plan").single() as MessagePart.Text).markdown)

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

        // 兜底行：原文保留为 Text part（损坏 metadata 的 parts 级退化，见 expectedTypeRole 注释）
        assertEquals("未来类型原文", (readParts(db, "m-unknown").single() as MessagePart.Text).markdown)
        assertEquals("损坏的任务卡", (readParts(db, "m-broken").single() as MessagePart.Text).markdown)
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

    private companion object {
        const val V24_CHAIN_DB_NAME = "chat-parts-migration-test.db"
        const val V25_DIRECT_DB_NAME = "chat-taxonomy-migration-test.db"
    }
}
