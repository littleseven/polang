package com.mamba.picme.data.local

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertTrue

/**
 * 测试 fixture 工具：把 Room 以 v29+ Entity 真建的 media_assets 降回无
 * relativePath/sizeBytes 两列的 v28 前形态（rename + copy 保留存量行——
 * Robolectric SQLite < 3.35 无 DROP COLUMN）。供 [Migration2728Test] /
 * [Migration2829Test] 构造「升级前存量库」用。
 */
internal object MediaAssetsPreV29Downgrader {

    fun downgrade(raw: SQLiteDatabase) {
        val mediaDdl = raw.rawQuery(
            "SELECT `sql` FROM sqlite_master WHERE `type`='table' AND `name`='media_assets'",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        val indexDdls = raw.rawQuery(
            "SELECT `sql` FROM sqlite_master WHERE `type`='index' AND `tbl_name`='media_assets' AND `sql` IS NOT NULL",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val preV29Ddl = mediaDdl
            .replace(", `relativePath` TEXT", "")
            .replace(", `sizeBytes` INTEGER", "")
        assertTrue(preV29Ddl != mediaDdl && !preV29Ddl.contains("relativePath") && !preV29Ddl.contains("sizeBytes"))
        val columnList = raw.rawQuery("PRAGMA table_info(`media_assets`)", null).use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    if (name != "relativePath" && name != "sizeBytes") add("`$name`")
                }
            }.joinToString(", ")
        }
        raw.execSQL("ALTER TABLE `media_assets` RENAME TO `media_assets_downgrade_backup`")
        raw.execSQL(preV29Ddl)
        raw.execSQL("INSERT INTO `media_assets` ($columnList) SELECT $columnList FROM `media_assets_downgrade_backup`")
        raw.execSQL("DROP TABLE `media_assets_downgrade_backup`")
        indexDdls.forEach { ddl -> raw.execSQL(ddl) }
    }
}
