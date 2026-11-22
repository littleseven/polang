package com.mamba.picme.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MIGRATION_28_29 端到端测试（Agent 清理 M1：media_assets 加 relativePath/sizeBytes 列）。
 * 构造策略同 [Migration2728Test]（exportSchema=false 无 schema JSON）：
 * 1. Room 以 v29 现行 Entity 真建库并灌 media fixture；
 * 2. 裸 SQLite 降回 v28 形态：rename+copy 去两列（Robolectric SQLite < 3.35 无 DROP
 *    COLUMN，见 [MediaAssetsPreV29Downgrader]）、user_version=28、清 room_master 身份；
 * 3. 重开挂 MIGRATION_28_29——onUpgrade 执行两条 ALTER，身份缺失触发全库 schema 校验
 *    （迁移 DDL 与 v29 Entity 逐字段比对，不一致直接抛 IllegalStateException）；
 * 4. 断言存量数据保留 + 回填 DAO（getMediaNeedingMetaBackfill/updateMediaMetaBatch）往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration2829Test {

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
    fun `v28 database upgrades to v29 adding metadata columns and keeping rows`() = runTest {
        // ── Step 1：Room 真建 v29 库 + fixture ──
        val created = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        val mediaId = created.mediaDao().insertMedia(
            MediaEntity(uri = "content://media/1", type = MediaType.PHOTO, captureDate = 100L, fileName = "sea.jpg"),
        )
        created.close()

        // ── Step 2：降回 v28 形态（去两列 + 版本号 + 身份清空）──
        downgradeToV28Fixture()

        // ── Step 3：重开——执行 MIGRATION_28_29 并做全库 schema 校验（DDL 不匹配即抛）──
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_28_29)
            .allowMainThreadQueries()
            .build()
        val mediaDao = db.mediaDao()

        // ── Step 4：存量行保留 + 新列 NULL 待回填 + 回填 DAO 往返 ──
        val raw = db.openHelper.writableDatabase
        assertEquals(mediaId, queryLong(raw, "SELECT `id` FROM `media_assets` WHERE `uri` = 'content://media/1'"))

        assertEquals(listOf("content://media/1"), mediaDao.getMediaNeedingMetaBackfill(10))
        mediaDao.updateMediaMetaBatch(listOf(MediaMetaEntry("content://media/1", "DCIM/Camera/", 1024L)))
        assertEquals(emptyList<String>(), mediaDao.getMediaNeedingMetaBackfill(10))
        val organizeRow = mediaDao.observeOrganizeRows().first().single()
        assertEquals("DCIM/Camera/", organizeRow.relativePath)
        assertEquals(1024L, organizeRow.sizeBytes)
        assertNull(organizeRow.ocrText)
        assertFalse(organizeRow.hasFace)
        db.close()
    }

    /** 把 v29 库降回 v28 形态：media_assets 去两列（[MediaAssetsPreV29Downgrader]）、版本号 28、room_master 身份清空。 */
    private fun downgradeToV28Fixture() {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.use { raw ->
            MediaAssetsPreV29Downgrader.downgrade(raw)
            raw.execSQL("PRAGMA user_version = 28")
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
        const val DB_NAME = "migration-2829-test.db"
    }
}
