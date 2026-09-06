package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mamba.picme.data.local.dao.ChatImageCacheDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.MediaFeedbackDao
import com.mamba.picme.data.local.dao.MemoryFactDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.OptimizeFeedbackDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.PersonRelationDao
import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.dao.TagScanTaskDao
import com.mamba.picme.data.local.entity.ChatImageCacheEntity
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.LocationHierarchyEntity
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
import com.mamba.picme.data.local.entity.MediaLocationEntity
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.data.local.entity.OcrWordEntity
import com.mamba.picme.data.local.entity.OcrWordOccurrence
import com.mamba.picme.data.local.entity.OptimizeFeedbackEntity
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
import com.mamba.picme.data.local.entity.TagEntity
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.model.MediaEntity

@Database(
    entities = [
        MediaEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        PersonEntity::class,
        FaceEmbeddingEntity::class,
        PhotoEditRecipeEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        OcrWordEntity::class,
        OcrWordOccurrence::class,
        LocationHierarchyEntity::class,
        MediaLocationEntity::class,
        TagScanTaskEntity::class,
        MediaFeedbackEntity::class,
        PersonRelationEntity::class,
        MemoryFactEntity::class,
        ChatImageCacheEntity::class,
        OptimizeFeedbackEntity::class,
        DedupHashEntity::class
    ],
    version = 23,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun tagDao(): TagDao
    abstract fun tagScanTaskDao(): TagScanTaskDao
    abstract fun ocrWordDao(): OcrWordDao
    abstract fun personDao(): PersonDao
    abstract fun locationDao(): LocationDao
    abstract fun photoEditRecipeDao(): PhotoEditRecipeDao
    abstract fun mediaFeedbackDao(): MediaFeedbackDao
    abstract fun personRelationDao(): PersonRelationDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun chatImageCacheDao(): ChatImageCacheDao
    abstract fun optimizeFeedbackDao(): OptimizeFeedbackDao
    abstract fun dedupHashDao(): DedupHashDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "picme_database"
                )
                    .addMigrations(
                        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
                        MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Migration 2 → 3：新增 tag_scan_tasks 表，媒体表增加 lastTagScanAt / lastTagScanPasses
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 新增 tag_scan_tasks 表
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_scan_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `mediaId` INTEGER NOT NULL,
                        `pass` TEXT NOT NULL,
                        `tagCategories` TEXT,
                        `status` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `scheduledAt` INTEGER,
                        `startedAt` INTEGER,
                        `completedAt` INTEGER,
                        `errorMessage` TEXT
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_status_priority_scheduledAt` ON `tag_scan_tasks` (`status`, `priority`, `scheduledAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_mediaId_pass_status` ON `tag_scan_tasks` (`mediaId`, `pass`, `status`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tag_scan_tasks_sessionId_status` ON `tag_scan_tasks` (`sessionId`, `status`)"
                )

                // 媒体表新增字段
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `lastTagScanAt` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `lastTagScanPasses` TEXT"
                )
            }
        }
        /**
         * Migration 3 → 4：新增 media_assets.semantic_embedding 字段
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `semanticEmbedding` TEXT"
                )
            }
        }

        /**
         * Migration 4 → 5：空迁移（修复设备上数据库版本已升到5的问题）
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 无 schema 变更，仅同步版本号
            }
        }

        /**
         * Migration 5 → 6：新增 media_assets.mlKitLabels 字段
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `mlKitLabels` TEXT"
                )
            }
        }

        /**
         * Migration 6 → 7：新增 media_assets.mlKitLabelsZh 字段
         * 存储 ML Kit 英文标签对应的中文翻译，使中文搜索可直接命中 ML Kit 标签
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `mlKitLabelsZh` TEXT"
                )
            }
        }

        /**
         * Migration 7 → 8：性能优化
         * 添加 captureDate/hasFace 索引（清理旧命名 + 创建 Room 标准命名）
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 清理可能因上次 migration 回退残留的旧命名索引
                // SQLite DDL 不参与事务回退，需要显式清理
                try {
                    database.execSQL("DROP INDEX IF EXISTS `idx_media_capture_date`")
                } catch (_: Exception) { }
                try {
                    database.execSQL("DROP INDEX IF EXISTS `idx_media_has_face`")
                } catch (_: Exception) { }

                // 使用 Room 命名约定创建索引: index_<tableName>_<columnName>
                // 名称必须与 @Entity(indices = [...]) 声明一致
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_assets_captureDate` ON `media_assets`(`captureDate`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_assets_hasFace` ON `media_assets`(`hasFace`)"
                )
            }
        }

        /**
         * Migration 8 → 9：新增 photo_edit_recipes 表，保存编辑配方以实现非破坏性编辑
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_edit_recipes` (
                        `outputUri` TEXT PRIMARY KEY NOT NULL,
                        `sourceUri` TEXT NOT NULL,
                        `recipeJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 9 → 10：新增 media_feedback 表，保存用户对搜索结果的点赞/点踩反馈
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_feedback` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_id` TEXT NOT NULL,
                        `feedback_type` TEXT NOT NULL,
                        `query_text` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_feedback_lookup` ON `media_feedback` (`media_id`, `query_text`, `feedback_type`)"
                )
            }
        }

        /**
         * Migration 10 → 11：清理 ML Kit 图像标注遗留数据
         *
         * ML Kit Image Labeler 已移除，不再生成新标签。本次 migration：
         * 1. 清空 media_assets.mlKitLabels / mlKitLabelsZh
         * 2. 清空旧 ML Kit 写入的 labels（JSON 数组格式，统一规格标签为 JSON 对象）
         * 3. 清空规范化标签表 tags / media_tag_cross_ref（旧 ML Kit 与废弃 ImageTagIndexingWorker 数据）
         */
        /**
         * Migration 11 → 12：新增 media_assets.labelsEn / labelsZh（英文打标 + 双字段汉化，见 spec §3.2）。
         *
         * 只 ADD COLUMN（全版本 SQLite 安全）；labels / mlKit* 死列暂不动（懒回填，见 spec §4）。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `labelsEn` TEXT")
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `labelsZh` TEXT")
            }
        }

        /**
         * Migration 12 → 13：人物关系图谱 + 通用事实记忆库
         *
         * 1. 新增 person_relations 表（人物关系边，FK→persons CASCADE，
         *    (subject, predicate, object) 唯一索引支持幂等覆盖）
         * 2. 新增 memory_facts 表（用户显式声明的事实记忆）
         * 3. persons 表新增 is_self 列（标记"我"本人）
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `person_relations` (
                        `relationId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `subjectPersonId` INTEGER NOT NULL,
                        `objectPersonId` INTEGER NOT NULL,
                        `predicate` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `confidence` REAL NOT NULL DEFAULT 1.0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`subjectPersonId`) REFERENCES `persons`(`personId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`objectPersonId`) REFERENCES `persons`(`personId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_person_relations_subjectPersonId_predicate_objectPersonId` ON `person_relations` (`subjectPersonId`, `predicate`, `objectPersonId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_person_relations_subjectPersonId` ON `person_relations` (`subjectPersonId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_person_relations_objectPersonId` ON `person_relations` (`objectPersonId`)"
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_facts` (
                        `factId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `content` TEXT NOT NULL,
                        `category` TEXT,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    "ALTER TABLE `persons` ADD COLUMN `is_self` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Migration 13 → 14：两层关系模型 —— person_relations 新增 customLabel 列
         *
         * 用户自由输入的称呼（如"发小""二儿子"），可空；
         * 非空时优先于谓词用于查询解析与展示。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `person_relations` ADD COLUMN `customLabel` TEXT"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 清空 ML Kit 专属列
                database.execSQL("UPDATE `media_assets` SET `mlKitLabels` = NULL, `mlKitLabelsZh` = NULL")

                // 清空旧 ML Kit 写入的 labels（JSON 数组格式）
                // tagger 当前写入的是 JSON 对象，以 '{' 开头，不受影响
                database.execSQL("UPDATE `media_assets` SET `labels` = NULL WHERE `labels` LIKE '[%]'")

                // 清空规范化标签表（旧 ML Kit / 废弃 ImageTagIndexingWorker 数据）
                database.execSQL("DELETE FROM `media_tag_cross_ref`")
                database.execSQL("DELETE FROM `tags`")
            }
        }

        /**
         * Migration 14 → 15：新增 chat_image_cache 表，登记 chat 编辑/优化结果图的私有缓存行
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_image_cache` (
                        `filePath` TEXT NOT NULL PRIMARY KEY,
                        `sessionId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `status` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_image_cache_lastAccessedAt` ON `chat_image_cache` (`lastAccessedAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_image_cache_sessionId` ON `chat_image_cache` (`sessionId`)"
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // TagScanPass.QWEN_TAGGING 重命名为 IMAGE_TAGGING。tag_scan_tasks.pass 按 Room
                // 枚举名持久化，需改写旧值，否则升级后反序列化历史任务行会崩溃。
                // media_assets.lastTagScanPasses 用 pass 编号 "3"，不受枚举重命名影响。
                database.execSQL(
                    "UPDATE tag_scan_tasks SET pass = 'IMAGE_TAGGING' WHERE pass = 'QWEN_TAGGING'"
                )
            }
        }

        /**
         * Migration 16 → 17：media_assets 新增 city 列（逆地理编码城市，去范式化供按城市分组）。
         * 只 ADD COLUMN（全版本 SQLite 安全）；存量行 city 为 NULL，由位置回填 pass 写入。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `city` TEXT")
            }
        }

        /**
         * Migration 17 → 18：新增 media_assets.faceFocusY 字段（人脸纵向聚焦点，列表缩略图对齐）
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `faceFocusY` REAL"
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `aestheticScore` REAL")
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `faceQualityScore` REAL")
            }
        }

        /**
         * Migration 19 → 20：新增 optimize_feedback 表（AI 优化抽卡反馈，见 spec §7）
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `optimize_feedback` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `image_key` TEXT NOT NULL,
                        `scene` TEXT NOT NULL,
                        `candidates_json` TEXT NOT NULL,
                        `selected_index` INTEGER NOT NULL,
                        `selection_source` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 20 → 21：新增 dedup_hash 表（去重 2.0 MD5/pHash 缓存，支持增量扫描）
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dedup_hash` (
                        `uri` TEXT NOT NULL PRIMARY KEY,
                        `sizeBytes` INTEGER NOT NULL,
                        `mime` TEXT NOT NULL,
                        `modifiedAt` INTEGER NOT NULL,
                        `md5` TEXT,
                        `phash` INTEGER,
                        `pixelArea` INTEGER NOT NULL,
                        `hashedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 21 → 22：media_assets 新增整理中心 v2 信号列
         * （blurScore/exposureScore/lastViewedAt，全部可空，老数据 null=未计算/未查看）。
         */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `blurScore` REAL")
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `exposureScore` REAL")
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `lastViewedAt` INTEGER")
            }
        }

        /**
         * Migration 22 → 23：media_assets 新增 uri 非唯一索引
         * （updateLastViewedAt/updateQualityScores 的 WHERE 键，消除全表扫描）。
         * 索引名按 Room 默认规则 index_<tableName>_<columnName>，与 @Entity(indices) 声明一致。
         */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_assets_uri` ON `media_assets`(`uri`)"
                )
            }
        }
    }
}
