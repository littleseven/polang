# 整理页类目体系重构（v2）+ UI 优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将整理中心从「散规则集合语义」重构为「信号分层管线（互斥裁定 + 价值保护 + 置信度分级）」，并重做类目卡/详情页 UI（置信度徽标、三段分组、引导态）。

**Architecture:** 四层纯函数管线（SignalCollector → CategoryArbiter → ValueGuard → ConfidenceGrader）落在 `domain/organize/`，全部 JVM 可测；Android 信号源（Laplacian 模糊/曝光、MediaStore 收藏、dedup_hash 重复组、人物计数）在 data 层补齐后回写 Room；UI 只消费管线产出的 `OrganizeBoard`/`ClassifiedItem`，零判定逻辑。

**Tech Stack:** Kotlin、Room（v21→v22）、Jetpack Compose、JUnit4（JVM 单测）、Coil。

**Spec SSOT:** `docs/superpowers/specs/2026-09-06-organize-category-redesign-design.md`

**全局约束（每个 Task 都适用）：**
- 工作区：按根 AGENTS.md §3.4 在 `.worktrees/` 建隔离 worktree + 专用分支（如 `feat/organize-v2`）后动工
- 包名禁止完全限定名、禁止通配符 import、lambda 参数显式命名（禁 `it`）、日志标签 `PoLang:Organize`
- 用户可见文案全部走 strings.xml 五语（EN/zh-CN/zh-TW/ES/FR）
- 测试命令统一：`./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.*"`（单类用全限定类名）
- 编译验证：`./gradlew :androidApp:compileDebugKotlin`

---

## 文件结构总览

**新增（domain，纯 Kotlin 零 Android 依赖）：**
- `androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeThresholds.kt` — 全部阈值常量集中
- `androidApp/src/main/java/com/mamba/picme/domain/organize/CategoryArbiter.kt` — 互斥类目裁定
- `androidApp/src/main/java/com/mamba/picme/domain/organize/ValueGuard.kt` — 价值保护
- `androidApp/src/main/java/com/mamba/picme/domain/organize/ConfidenceGrader.kt` — 置信度分级
- `androidApp/src/main/java/com/mamba/picme/domain/organize/DuplicateGrouper.kt` — dedup_hash → 重复组成员信息
- `androidApp/src/main/java/com/mamba/picme/domain/organize/BlurAnalyzer.kt` — Laplacian 方差 + 亮度直方图（IntArray 灰度输入，JVM 可测）

**重构：**
- `domain/organize/OrganizeModels.kt` — 枚举 v2 + `OrganizeItem` 扩展 + 管线产出模型
- `domain/organize/OrganizeCategorizer.kt` — 管线编排 Facade（`classifyAll` / `board`）
- `data/repository/OrganizeRepositoryImpl.kt` — 新信号合并 + 惰性补算
- `features/gallery/dedup/DedupViewModel.kt` — stats → OrganizeBoard
- `features/gallery/dedup/DedupHomeHub.kt` — 类目卡 v2 + Hero 修正 + 引导态
- `features/gallery/organize/OrganizeCategoryViewModel.kt` — 三段分组 + 置信筛选
- `features/gallery/organize/OrganizeCategoryScreen.kt` — 详情页 v2
- `domain/swipe/SwipeQueueBuilder.kt` — 消费管线产出（口径同源）

**数据层：**
- `data/local/AppDatabase.kt` — version 22 + MIGRATION_21_22
- `data/model/MediaEntity.kt` — +blurScore/exposureScore/lastViewedAt
- `data/local/MediaDao.kt` — OrganizeRow 加列 + 3 个新方法
- `data/local/DedupHashDao.kt` — +getAllHashes

**测试：**
- `androidApp/src/test/java/com/mamba/picme/domain/organize/CategoryArbiterTest.kt`
- `androidApp/src/test/java/com/mamba/picme/domain/organize/ValueGuardTest.kt`
- `androidApp/src/test/java/com/mamba/picme/domain/organize/ConfidenceGraderTest.kt`
- `androidApp/src/test/java/com/mamba/picme/domain/organize/DuplicateGrouperTest.kt`
- `androidApp/src/test/java/com/mamba/picme/domain/organize/BlurAnalyzerTest.kt`
- `androidApp/src/test/java/com/mamba/picme/domain/organize/OrganizeCategorizerTest.kt`（重写为管线集成测试）

---

## Task 1: Room v22 迁移 + DAO 扩展

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/model/MediaEntity.kt:50-59`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt:59,97-98,470`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/MediaDao.kt:444,668-678`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/DedupHashDao.kt`

- [ ] **Step 1: MediaEntity 新增三列**

`MediaEntity.kt` 在 `faceQualityScore` 声明之后追加：

```kotlin
    /** Laplacian 方差模糊分（越大越清晰；null=未计算）。整理中心 v2 LOW_QUALITY_PHOTOS 主信号。 */
    val blurScore: Float? = null,
    /** 平均亮度归一（0~1，0.5 附近为正常曝光；null=未计算）。 */
    val exposureScore: Float? = null,
    /** 用户在查看器最近一次打开时间戳（null=从未查看）。整理中心 v2 价值保护信号。 */
    val lastViewedAt: Long? = null,
```

- [ ] **Step 2: AppDatabase 版本与迁移**

`AppDatabase.kt`：
- `version = 21` 改为 `version = 22`
- `addMigrations(...)` 列表末尾 `MIGRATION_20_21` 后追加 `, MIGRATION_21_22`
- companion 内 `MIGRATION_20_21` 之后追加：

```kotlin
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
```

- [ ] **Step 3: MediaDao 投影加列 + 新方法**

`MediaDao.kt:444` 的 `observeOrganizeRows` 查询改为：

```kotlin
    @Query(
        "SELECT uri, type, captureDate, ocrText, labels, hasFace, aestheticScore, " +
            "faceQualityScore, blurScore, exposureScore, lastViewedAt, faceId FROM media_assets"
    )
    fun observeOrganizeRows(): Flow<List<OrganizeRow>>
```

`OrganizeRow`（文件底部 :668-678）改为：

```kotlin
/** 整理中心轻量投影行：仅类目判定所需列（大列 embedding/faceRoiResult 不加载）。 */
data class OrganizeRow(
    val uri: String,
    val type: String,
    val captureDate: Long,
    val ocrText: String?,
    val labels: String?,
    val hasFace: Boolean,
    val aestheticScore: Float?,
    val faceQualityScore: Float?,
    val blurScore: Float?,
    val exposureScore: Float?,
    val lastViewedAt: Long?,
    val faceId: String?,
)
```

MediaDao 整理中心区块追加三个方法 + 一个投影类：

```kotlin
    /** 回写模糊/曝光分（整理中心 v2 惰性补算产出）。 */
    @Query("UPDATE media_assets SET blurScore = :blur, exposureScore = :exposure WHERE uri = :uri")
    suspend fun updateQualityScores(uri: String, blur: Float?, exposure: Float?)

    /** 回写最近一次查看时间（查看器打开时调用）。 */
    @Query("UPDATE media_assets SET lastViewedAt = :timestamp WHERE uri = :uri")
    suspend fun updateLastViewedAt(uri: String, timestamp: Long)

    /** 各人物聚类的照片总数（faceId → 计数），整理中心 v2 人物稀缺信号。 */
    @Query(
        "SELECT faceId, COUNT(*) AS cnt FROM media_assets " +
            "WHERE faceId IS NOT NULL AND faceId != '' GROUP BY faceId"
    )
    suspend fun getPersonPhotoCounts(): List<PersonPhotoCount>
```

文件底部追加：

```kotlin
/** 人物聚类照片计数投影（整理中心 v2 人物稀缺信号）。 */
data class PersonPhotoCount(
    val faceId: String,
    val cnt: Int,
)
```

- [ ] **Step 4: DedupHashDao 新增全量哈希查询**

`DedupHashDao.kt` 追加：

```kotlin
    /** 全部已有哈希（md5/phash 至少其一非空），整理中心 v2 重复组判定输入。 */
    @Query("SELECT uri, md5, phash FROM dedup_hash WHERE md5 IS NOT NULL OR phash IS NOT NULL")
    suspend fun getAllHashes(): List<DedupHashRow>
```

同文件追加投影类（若文件内无 data class 先例则放文件底部顶层）：

```kotlin
/** dedup_hash 轻量投影（整理中心 v2 重复组判定）。 */
data class DedupHashRow(
    val uri: String,
    val md5: String?,
    val phash: Long?,
)
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（Room 编译期会校验 projection 与 OrganizeRow 字段对齐；`OrganizeRepositoryImpl` 构造 `OrganizeItem` 处会暂时报参数缺失——Task 2 扩展 OrganizeItem 时一并修，此处允许中间态编译失败仅限该类，若失败确认错误仅为 OrganizeItem 构造参数不匹配）

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/
git commit -m "feat(organize): Room v22 新增整理 v2 信号列（blur/exposure/lastViewedAt）+ DAO 扩展"
```

---

## Task 2: 领域模型 v2（枚举改名 + OrganizeItem 扩展 + 阈值集中 + 管线产出模型）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeModels.kt`（全量重写）
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeThresholds.kt`

- [ ] **Step 1: 写 OrganizeThresholds.kt**

```kotlin
package com.mamba.picme.domain.organize

/**
 * 整理中心 v2 全部判定阈值（单一事实来源，校准只改这里）。
 * 置信度边界以「×主阈值」的倍数表达（见 ConfidenceGrader）。
 */
object OrganizeThresholds {

    // ── 沿用 v1 ─────────────────────────────────────────────
    /** eDifFIQA 人脸质量分（0~1）低于该值判低质量人像。 */
    const val FACE_QUALITY_LOW = 0.35f

    /** 大文件：视频字节阈值（100 MiB）。 */
    const val LARGE_VIDEO_BYTES = 100L * 1024 * 1024

    /** NIMA 美学分（1~10）低于该值作为 LOW 置信辅助信号（不再单独定类）。 */
    const val AESTHETIC_LOW = 3.5f

    // ── 新增：模糊/曝光（BlurAnalyzer 产出）──────────────────
    /**
     * Laplacian 方差模糊阈值（256px 灰度图口径）：低于判真模糊。
     * 初始值按典型手机实拍分布取 100.0，落地后用 BlurAnalyzerTest 样本校准。
     */
    const val BLUR_VARIANCE_LOW = 100.0f

    /** 平均亮度（0~1）低于判欠曝。 */
    const val EXPOSURE_UNDER = 0.15f

    /** 平均亮度（0~1）高于判过曝。 */
    const val EXPOSURE_OVER = 0.85f

    /** 置信度分级：强命中 = 主阈值 × 该系数（blur/faceQuality 等越低越差型信号）。 */
    const val STRONG_SIGNAL_FACTOR = 0.5f

    // ── 新增：大文件（超分辨率照片）──────────────────────────
    /** 超高像素面积阈值（50 MP）。 */
    const val LARGE_PHOTO_PIXEL_AREA = 50_000_000L

    /** 超分辨率照片大小阈值（20 MB，与像素面积同时满足才判大文件）。 */
    const val LARGE_PHOTO_BYTES = 20L * 1024 * 1024

    // ── 价值保护 ────────────────────────────────────────────
    /** 年代久远：拍摄时间早于 now − 该年数判老照片。 */
    const val OLD_PHOTO_YEARS = 5

    /** 人物稀缺：所属人物聚类照片总数 ≤ 该值判稀缺。 */
    const val PERSON_SCARCE_MAX = 3

    /** OCR 强命中倍数：文字密度 ≥ 主阈值 × 该系数 → HIGH 置信。 */
    const val OCR_STRONG_FACTOR = 2
}

/** 一年毫秒数（365 天，ValueGuard 老照片判定用，避免引入 java.time 依赖）。 */
internal const val YEAR_MILLIS = 365L * 24 * 60 * 60 * 1000
```

- [ ] **Step 2: 全量重写 OrganizeModels.kt**

```kotlin
package com.mamba.picme.domain.organize

/**
 * 整理中心 v2 可清理类目（互斥：一张媒体只进一个桶，CategoryArbiter 按声明顺序裁定）。
 * 枚举名是 `organize_category/{category}` 路由段与 strings 资源映射键，改名需全量收口。
 */
enum class OrganizeCategory {
    DUPLICATES,             // 重复与相似（dedup_hash MD5/pHash 组）
    SCREEN_CONTENT,         // 截图 + 录屏
    DOCUMENTS,              // 文档与票据（OCR 密度 / VLM 标签）
    LOW_QUALITY_PORTRAITS,  // 低质人像（eDifFIQA 低分）
    LOW_QUALITY_PHOTOS,     // 低质量照片（真模糊 / 曝光异常；NIMA 仅作置信辅助）
    LARGE_FILES,            // 大视频 + 超分辨率照片
}

/**
 * 类目判定输入：Room 列 + MediaStore meta + dedup_hash 组信息 + 人物计数合并后的扁平快照。
 * null = 信号未覆盖（绝不参与判定，由引导态承接）。
 */
data class OrganizeItem(
    val uri: String,
    val isVideo: Boolean,
    val captureDate: Long,
    val sizeBytes: Long,                // 未知 = 0
    val relativePath: String?,
    val ocrText: String?,
    val pixelArea: Long?,
    val labels: String?,
    val hasFace: Boolean,
    val aestheticScore: Float?,         // null = 未评分
    val faceQualityScore: Float?,       // null = 未评分
    // ── v2 新增信号 ──────────────────────────────────────────
    val blurScore: Float? = null,       // Laplacian 方差；null = 未计算
    val exposureScore: Float? = null,   // 平均亮度 0~1；null = 未计算
    val lastViewedAt: Long? = null,     // null = 从未在查看器打开
    val isFavorite: Boolean = false,    // MediaStore IS_FAVORITE
    /** 所属人物聚类的照片总数；null/0 = 无人脸或未聚类。 */
    val personPhotoCount: Int? = null,
    /** 精确重复组大小（同 MD5 组成员数）；< 2 = 不在重复组。 */
    val exactDupGroupSize: Int = 0,
    /** 视觉相似组大小（pHash 簇成员数）；< 2 = 不在相似组。 */
    val similarDupGroupSize: Int = 0,
)

/** 置信度分级（详情页三段分组 + 预选口径）。 */
enum class OrganizeConfidence { HIGH, MEDIUM, LOW }

/** 价值保护原因（详情页角标文案键）。 */
enum class ProtectReason { OLD_PHOTO, SCARCE_PERSON, USER_ENGAGED }

/** 管线产出：单媒体裁定结果。 */
data class ClassifiedItem(
    val item: OrganizeItem,
    val category: OrganizeCategory,
    val confidence: OrganizeConfidence,
    val protected: Boolean,
    val protectReasons: Set<ProtectReason> = emptySet(),
)

/** 类目信号覆盖度：驱动 hub 类目卡「需先扫描」引导态（修复 v1 类目静默消失）。 */
enum class SignalCoverage { READY, NEEDS_SCAN }

/** 类目卡聚合（hub 渲染输入）。 */
data class CategoryBoard(
    val category: OrganizeCategory,
    val totalCount: Int,
    val totalBytes: Long,
    /** HIGH 置信且非 protected：建议删除数。 */
    val highCount: Int,
    val highBytes: Long,
    /** MEDIUM+LOW 且非 protected：待确认数。 */
    val reviewCount: Int,
    val protectedCount: Int,
    val previewUris: List<String>,      // 前 4 张
    val coverage: SignalCoverage,
)

/** hub 整体产出。 */
data class OrganizeBoard(
    /** 按 highBytes 降序（建议优先级）。 */
    val categories: List<CategoryBoard>,
    /** Hero 主数字：全类目 HIGH 且非 protected 去重并集字节（互斥裁定保证天然去重）。 */
    val heroReclaimBytes: Long,
    /** Hero 副行：MEDIUM+LOW 且非 protected 总数。 */
    val heroReviewCount: Int,
)
```

- [ ] **Step 3: Commit（编译错误留给 Task 3-6 收口，此处仅提交模型）**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeModels.kt androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeThresholds.kt
git commit -m "feat(organize): 领域模型 v2——互斥类目枚举 + 扩展信号 + 管线产出模型 + 阈值集中"
```

---

## Task 3: CategoryArbiter（互斥裁定）TDD

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/CategoryArbiter.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/CategoryArbiterTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryArbiterTest {

    @Suppress("LongParameterList") // 测试夹具工厂：与 OrganizeItem 构造参数一一对应
    private fun item(
        uri: String = "a",
        isVideo: Boolean = false,
        sizeBytes: Long = 1_000_000,
        relativePath: String? = "DCIM/Camera/",
        ocrText: String? = null,
        pixelArea: Long? = 12_000_000,
        labels: String? = null,
        hasFace: Boolean = false,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
        blurScore: Float? = null,
        exposureScore: Float? = null,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = 1_000L, sizeBytes = sizeBytes,
        relativePath = relativePath, ocrText = ocrText, pixelArea = pixelArea,
        labels = labels, hasFace = hasFace, aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore, blurScore = blurScore,
        exposureScore = exposureScore, exactDupGroupSize = exactDupGroupSize,
        similarDupGroupSize = similarDupGroupSize,
    )

    @Test
    fun `exact duplicate group member wins over everything`() {
        // 截图路径 + 重复组 → DUPLICATES（优先级 1 截走，修复 v1 多属重复计账）
        val result = CategoryArbiter.classify(
            item(relativePath = "Pictures/Screenshots/", exactDupGroupSize = 3)
        )
        assertEquals(OrganizeCategory.DUPLICATES, result)
    }

    @Test
    fun `screenshot path classified SCREEN_CONTENT and never blurry`() {
        // v1 痛点：截图低美学分误落 BLURRY；v2 必须先被 SCREEN_CONTENT 截走
        val result = CategoryArbiter.classify(
            item(relativePath = "Pictures/Screenshots/", aestheticScore = 1.0f, blurScore = 1.0f)
        )
        assertEquals(OrganizeCategory.SCREEN_CONTENT, result)
    }

    @Test
    fun `screen recording video by path keyword`() {
        val result = CategoryArbiter.classify(
            item(isVideo = true, relativePath = "Movies/ScreenRecorder/", sizeBytes = 5_000_000)
        )
        assertEquals(OrganizeCategory.SCREEN_CONTENT, result)
    }

    @Test
    fun `document via OCR density beats low quality photo`() {
        // 文档翻拍天然模糊/低美学 → 必须落 DOCUMENTS 而非 LOW_QUALITY_PHOTOS
        val dense = "x".repeat(500)
        val result = CategoryArbiter.classify(
            item(ocrText = dense, pixelArea = 12_000_000, blurScore = 1.0f)
        )
        assertEquals(OrganizeCategory.DOCUMENTS, result)
    }

    @Test
    fun `low face quality portrait`() {
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PORTRAITS,
            CategoryArbiter.classify(item(hasFace = true, faceQualityScore = 0.2f))
        )
        // 阈值边界：恰好等于不命中
        assertNull(CategoryArbiter.classify(item(hasFace = true, faceQualityScore = 0.35f)))
        // null 评分不判定
        assertNull(CategoryArbiter.classify(item(hasFace = true, faceQualityScore = null)))
    }

    @Test
    fun `true blur and exposure anomaly classified LOW_QUALITY_PHOTOS`() {
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PHOTOS,
            CategoryArbiter.classify(item(blurScore = 20.0f))
        )
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PHOTOS,
            CategoryArbiter.classify(item(exposureScore = 0.05f))
        )
        assertEquals(
            OrganizeCategory.LOW_QUALITY_PHOTOS,
            CategoryArbiter.classify(item(exposureScore = 0.95f))
        )
        // NIMA 低分单独不再定类（v1 BLURRY 名实错位修复）
        assertNull(CategoryArbiter.classify(item(aestheticScore = 1.0f)))
    }

    @Test
    fun `large video and super resolution photo are LARGE_FILES`() {
        assertEquals(
            OrganizeCategory.LARGE_FILES,
            CategoryArbiter.classify(item(isVideo = true, sizeBytes = 150L * 1024 * 1024))
        )
        assertEquals(
            OrganizeCategory.LARGE_FILES,
            CategoryArbiter.classify(
                item(pixelArea = 60_000_000, sizeBytes = 25L * 1024 * 1024)
            )
        )
        // 高像素但体积小（高效压缩）不判
        assertNull(
            CategoryArbiter.classify(item(pixelArea = 60_000_000, sizeBytes = 5L * 1024 * 1024))
        )
    }

    @Test
    fun `domain constraint - videos never enter document or quality categories`() {
        val video = item(
            isVideo = true, ocrText = "x".repeat(500),
            faceQualityScore = 0.1f, hasFace = true, blurScore = 1.0f, sizeBytes = 1_000,
        )
        assertNull(CategoryArbiter.classify(video))
    }

    @Test
    fun `uncategorized returns null`() {
        assertNull(CategoryArbiter.classify(item()))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.CategoryArbiterTest"`
Expected: 编译失败（CategoryArbiter 不存在）

- [ ] **Step 3: 实现 CategoryArbiter.kt**

```kotlin
package com.mamba.picme.domain.organize

import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.dedup.detectContentType

/** MediaStore 截图目录约定（路径 contains，大小写不敏感；与 dedup 侧同一规则）。 */
private const val SCREENSHOT_DIR_KEYWORD = "screenshots"

/** 录屏目录/文件名关键词（各 ROM 录屏应用目录约定，大小写不敏感）。 */
private val SCREEN_RECORDING_KEYWORDS = listOf("screenrecord", "screen_record", "录屏")

/**
 * 整理中心 v2 互斥类目裁定（纯函数，零 Android 依赖，JVM 可测）：
 * 按 [OrganizeCategory] 声明顺序（=优先级）先命中先得，一张媒体只进一个桶。
 * 裁定域约束：视频仅参与 DUPLICATES / SCREEN_CONTENT / LARGE_FILES。
 */
object CategoryArbiter {

    /** 返回唯一命中类目；无任何命中返回 null（不属任何清理类目）。 */
    fun classify(item: OrganizeItem): OrganizeCategory? {
        // 1. 重复与相似
        if (item.exactDupGroupSize >= 2 || item.similarDupGroupSize >= 2) {
            return OrganizeCategory.DUPLICATES
        }
        // 2. 屏幕内容（截图 + 录屏）
        if (isScreenContent(item)) return OrganizeCategory.SCREEN_CONTENT
        if (!item.isVideo) {
            // 3. 文档与票据（沿用 dedup 内容类型判定，同引避免分层倒置）
            val contentType = detectContentType(
                path = item.relativePath,
                ocrText = item.ocrText,
                pixelArea = item.pixelArea,
                labels = item.labels,
                hasFace = item.hasFace,
                faceQualityScore = item.faceQualityScore,
            )
            if (contentType == DedupContentType.DOCUMENT) return OrganizeCategory.DOCUMENTS
            // 4. 低质人像
            val faceQuality = item.faceQualityScore
            if (item.hasFace && faceQuality != null &&
                faceQuality < OrganizeThresholds.FACE_QUALITY_LOW
            ) {
                return OrganizeCategory.LOW_QUALITY_PORTRAITS
            }
            // 5. 低质量照片（真模糊 / 曝光异常；NIMA 仅作置信辅助，不定类）
            if (isLowQualityPhoto(item)) return OrganizeCategory.LOW_QUALITY_PHOTOS
        }
        // 6. 大文件
        if (isLargeFile(item)) return OrganizeCategory.LARGE_FILES
        return null
    }

    private fun isScreenContent(item: OrganizeItem): Boolean {
        val path = item.relativePath ?: return false
        if (path.contains(SCREENSHOT_DIR_KEYWORD, ignoreCase = true)) return true
        return item.isVideo && SCREEN_RECORDING_KEYWORDS.any { keyword ->
            path.contains(keyword, ignoreCase = true)
        }
    }

    private fun isLowQualityPhoto(item: OrganizeItem): Boolean {
        val blur = item.blurScore
        if (blur != null && blur < OrganizeThresholds.BLUR_VARIANCE_LOW) return true
        val exposure = item.exposureScore
        return exposure != null &&
            (exposure < OrganizeThresholds.EXPOSURE_UNDER || exposure > OrganizeThresholds.EXPOSURE_OVER)
    }

    private fun isLargeFile(item: OrganizeItem): Boolean =
        if (item.isVideo) {
            item.sizeBytes >= OrganizeThresholds.LARGE_VIDEO_BYTES
        } else {
            val area = item.pixelArea ?: 0L
            area >= OrganizeThresholds.LARGE_PHOTO_PIXEL_AREA &&
                item.sizeBytes >= OrganizeThresholds.LARGE_PHOTO_BYTES
        }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.CategoryArbiterTest"`
Expected: 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/CategoryArbiter.kt androidApp/src/test/java/com/mamba/picme/domain/organize/CategoryArbiterTest.kt
git commit -m "feat(organize): CategoryArbiter 互斥类目裁定（优先级先命中先得）"
```

---

## Task 4: ValueGuard（价值保护）TDD

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/ValueGuard.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/ValueGuardTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueGuardTest {

    private val now = 1_800_000_000_000L // 固定参考时间，测试确定性

    @Suppress("LongParameterList")
    private fun item(
        captureDate: Long = now,
        lastViewedAt: Long? = null,
        isFavorite: Boolean = false,
        personPhotoCount: Int? = null,
    ) = OrganizeItem(
        uri = "a", isVideo = false, captureDate = captureDate, sizeBytes = 1_000_000,
        relativePath = "DCIM/Camera/", ocrText = null, pixelArea = 12_000_000,
        labels = null, hasFace = false, aestheticScore = null, faceQualityScore = null,
        blurScore = 10.0f, lastViewedAt = lastViewedAt, isFavorite = isFavorite,
        personPhotoCount = personPhotoCount,
    )

    @Test
    fun `old photo protected with boundary`() {
        val fiveYearsAgo = now - OrganizeThresholds.OLD_PHOTO_YEARS * YEAR_MILLIS
        val old = ValueGuard.assess(
            item(captureDate = fiveYearsAgo - 1), OrganizeCategory.LOW_QUALITY_PHOTOS, now
        )
        assertTrue(old.protected)
        assertEquals(setOf(ProtectReason.OLD_PHOTO), old.reasons)
        // 边界：恰好 5 年不保护（判定为「早于」）
        val boundary = ValueGuard.assess(
            item(captureDate = fiveYearsAgo), OrganizeCategory.LOW_QUALITY_PHOTOS, now
        )
        assertTrue(ProtectReason.OLD_PHOTO !in boundary.reasons)
    }

    @Test
    fun `scarce person protected with boundary`() {
        val scarce = ValueGuard.assess(item(personPhotoCount = 3), OrganizeCategory.LOW_QUALITY_PORTRAITS, now)
        assertEquals(setOf(ProtectReason.SCARCE_PERSON), scarce.reasons)
        val notScarce = ValueGuard.assess(item(personPhotoCount = 4), OrganizeCategory.LOW_QUALITY_PORTRAITS, now)
        assertTrue(ProtectReason.SCARCE_PERSON !in notScarce.reasons)
        // 无人物信息不触发
        val noPerson = ValueGuard.assess(item(personPhotoCount = null), OrganizeCategory.LOW_QUALITY_PORTRAITS, now)
        assertTrue(ProtectReason.SCARCE_PERSON !in noPerson.reasons)
    }

    @Test
    fun `favorite or viewed protected`() {
        val fav = ValueGuard.assess(item(isFavorite = true), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        assertEquals(setOf(ProtectReason.USER_ENGAGED), fav.reasons)
        val viewed = ValueGuard.assess(item(lastViewedAt = now - 1000), OrganizeCategory.LOW_QUALITY_PHOTOS, now)
        assertEquals(setOf(ProtectReason.USER_ENGAGED), viewed.reasons)
    }

    @Test
    fun `protection only applies to quality categories`() {
        // 5 年前的截图不保护（价值保护只适用 LOW_QUALITY_PHOTOS / LOW_QUALITY_PORTRAITS）
        val screenshot = ValueGuard.assess(
            item(captureDate = now - 6 * YEAR_MILLIS), OrganizeCategory.SCREEN_CONTENT, now
        )
        assertTrue(!screenshot.protected && screenshot.reasons.isEmpty())
    }

    @Test
    fun `multiple reasons accumulate`() {
        val verdict = ValueGuard.assess(
            item(captureDate = now - 6 * YEAR_MILLIS, isFavorite = true, personPhotoCount = 1),
            OrganizeCategory.LOW_QUALITY_PHOTOS, now,
        )
        assertEquals(
            setOf(ProtectReason.OLD_PHOTO, ProtectReason.USER_ENGAGED, ProtectReason.SCARCE_PERSON),
            verdict.reasons,
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.ValueGuardTest"`
Expected: 编译失败（ValueGuard 不存在）

- [ ] **Step 3: 实现 ValueGuard.kt**

```kotlin
package com.mamba.picme.domain.organize

/** 价值保护判定结果。 */
data class ProtectVerdict(
    val protected: Boolean,
    val reasons: Set<ProtectReason>,
)

/**
 * 价值保护（用户决策 2026-09-06：低质 ≠ 可删，老照片/稀缺照片有情感价值）：
 * 仅对 [OrganizeCategory.LOW_QUALITY_PHOTOS] / [OrganizeCategory.LOW_QUALITY_PORTRAITS]
 * 生效；命中任一保护信号 → protected（不默认勾选、不计入 Hero、排详情页保护区）。
 * 纯函数，[now] 注入保证测试确定性。
 */
object ValueGuard {

    private val GUARDED_CATEGORIES = setOf(
        OrganizeCategory.LOW_QUALITY_PHOTOS,
        OrganizeCategory.LOW_QUALITY_PORTRAITS,
    )

    fun assess(item: OrganizeItem, category: OrganizeCategory, now: Long): ProtectVerdict {
        if (category !in GUARDED_CATEGORIES) return ProtectVerdict(protected = false, reasons = emptySet())
        val reasons = mutableSetOf<ProtectReason>()
        if (item.captureDate < now - OrganizeThresholds.OLD_PHOTO_YEARS * YEAR_MILLIS) {
            reasons += ProtectReason.OLD_PHOTO
        }
        val personCount = item.personPhotoCount
        if (personCount != null && personCount in 1..OrganizeThresholds.PERSON_SCARCE_MAX) {
            reasons += ProtectReason.SCARCE_PERSON
        }
        if (item.isFavorite || item.lastViewedAt != null) {
            reasons += ProtectReason.USER_ENGAGED
        }
        return ProtectVerdict(protected = reasons.isNotEmpty(), reasons = reasons)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.ValueGuardTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/ValueGuard.kt androidApp/src/test/java/com/mamba/picme/domain/organize/ValueGuardTest.kt
git commit -m "feat(organize): ValueGuard 价值保护（老照片/人物稀缺/用户互动三信号）"
```

---

## Task 5: ConfidenceGrader（置信度分级）TDD

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/ConfidenceGrader.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/ConfidenceGraderTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidenceGraderTest {

    @Suppress("LongParameterList")
    private fun item(
        isVideo: Boolean = false,
        sizeBytes: Long = 1_000_000,
        relativePath: String? = "DCIM/Camera/",
        ocrText: String? = null,
        pixelArea: Long? = 12_000_000,
        labels: String? = null,
        hasFace: Boolean = false,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
        blurScore: Float? = null,
        exposureScore: Float? = null,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
    ) = OrganizeItem(
        uri = "a", isVideo = isVideo, captureDate = 1_000L, sizeBytes = sizeBytes,
        relativePath = relativePath, ocrText = ocrText, pixelArea = pixelArea,
        labels = labels, hasFace = hasFace, aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore, blurScore = blurScore,
        exposureScore = exposureScore, exactDupGroupSize = exactDupGroupSize,
        similarDupGroupSize = similarDupGroupSize,
    )

    @Test
    fun `duplicates - exact HIGH, similar MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(exactDupGroupSize = 2), OrganizeCategory.DUPLICATES)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(similarDupGroupSize = 3), OrganizeCategory.DUPLICATES)
        )
    }

    @Test
    fun `screen content by path is HIGH`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(relativePath = "Pictures/Screenshots/"), OrganizeCategory.SCREEN_CONTENT
            )
        )
    }

    @Test
    fun `documents - strong OCR HIGH, borderline MEDIUM, labels only MEDIUM`() {
        // 12MP 图上密度阈值 20 字符/MP = 240 字符；2× = 480
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(ocrText = "x".repeat(600)), OrganizeCategory.DOCUMENTS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(ocrText = "x".repeat(300)), OrganizeCategory.DOCUMENTS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(labels = """["文档","纸张"]"""), OrganizeCategory.DOCUMENTS)
        )
    }

    @Test
    fun `low quality portraits - strong face quality HIGH else MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(hasFace = true, faceQualityScore = 0.1f), OrganizeCategory.LOW_QUALITY_PORTRAITS
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(hasFace = true, faceQualityScore = 0.3f), OrganizeCategory.LOW_QUALITY_PORTRAITS
            )
        )
    }

    @Test
    fun `low quality photos - strong blur HIGH, borderline blur or exposure MEDIUM, aesthetic only LOW`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(item(blurScore = 20.0f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(blurScore = 80.0f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(item(exposureScore = 0.05f), OrganizeCategory.LOW_QUALITY_PHOTOS)
        )
        // 美学分低但模糊/曝光正常 → LOW（不默认勾选，仅列出）
        assertEquals(
            OrganizeConfidence.LOW,
            ConfidenceGrader.grade(
                item(blurScore = 500.0f, exposureScore = 0.5f, aestheticScore = 2.0f),
                OrganizeCategory.LOW_QUALITY_PHOTOS,
            )
        )
    }

    @Test
    fun `large files - double threshold HIGH else MEDIUM`() {
        assertEquals(
            OrganizeConfidence.HIGH,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 250L * 1024 * 1024), OrganizeCategory.LARGE_FILES
            )
        )
        assertEquals(
            OrganizeConfidence.MEDIUM,
            ConfidenceGrader.grade(
                item(isVideo = true, sizeBytes = 120L * 1024 * 1024), OrganizeCategory.LARGE_FILES
            )
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.ConfidenceGraderTest"`
Expected: 编译失败（ConfidenceGrader 不存在）

- [ ] **Step 3: 实现 ConfidenceGrader.kt**

```kotlin
package com.mamba.picme.domain.organize

/**
 * 置信度分级（纯函数）：决定详情页三段分组与默认勾选口径。
 * HIGH = 强信号命中（默认勾选）；MEDIUM = 单信号接近阈值（仅列出）；
 * LOW = 弱信号（美学分等辅助信号单独命中）。
 * 前置：item 已经 CategoryArbiter 裁定入 [category]，本层只评估信号强度。
 */
object ConfidenceGrader {

    fun grade(item: OrganizeItem, category: OrganizeCategory): OrganizeConfidence =
        when (category) {
            OrganizeCategory.DUPLICATES ->
                if (item.exactDupGroupSize >= 2) OrganizeConfidence.HIGH else OrganizeConfidence.MEDIUM

            // 路径判定为确定性强信号
            OrganizeCategory.SCREEN_CONTENT -> OrganizeConfidence.HIGH

            OrganizeCategory.DOCUMENTS -> gradeDocument(item)

            OrganizeCategory.LOW_QUALITY_PORTRAITS -> {
                val faceQuality = item.faceQualityScore ?: 1.0f
                if (faceQuality < OrganizeThresholds.FACE_QUALITY_LOW * OrganizeThresholds.STRONG_SIGNAL_FACTOR) {
                    OrganizeConfidence.HIGH
                } else {
                    OrganizeConfidence.MEDIUM
                }
            }

            OrganizeCategory.LOW_QUALITY_PHOTOS -> gradeLowQualityPhoto(item)

            OrganizeCategory.LARGE_FILES ->
                if (item.sizeBytes >= 2 * largeFileThreshold(item)) {
                    OrganizeConfidence.HIGH
                } else {
                    OrganizeConfidence.MEDIUM
                }
        }

    /** OCR 强命中（≥2×密度阈值）HIGH；OCR 命中或仅标签命中 MEDIUM。 */
    private fun gradeDocument(item: OrganizeItem): OrganizeConfidence {
        val chars = item.ocrText?.length ?: 0
        if (chars == 0) return OrganizeConfidence.MEDIUM // labels 关键词命中（无 OCR 佐证）
        val area = item.pixelArea
        val strongThreshold = if (area != null && area > 0) {
            // 与 DedupContentTypeDetector 同一面积归一口径的 2×
            area / 1_000_000L * 20 * OrganizeThresholds.OCR_STRONG_FACTOR
        } else {
            200L * OrganizeThresholds.OCR_STRONG_FACTOR
        }
        return if (chars.toLong() >= strongThreshold) {
            OrganizeConfidence.HIGH
        } else {
            OrganizeConfidence.MEDIUM
        }
    }

    private fun gradeLowQualityPhoto(item: OrganizeItem): OrganizeConfidence {
        val blur = item.blurScore
        if (blur != null && blur < OrganizeThresholds.BLUR_VARIANCE_LOW * OrganizeThresholds.STRONG_SIGNAL_FACTOR) {
            return OrganizeConfidence.HIGH
        }
        val blurred = blur != null && blur < OrganizeThresholds.BLUR_VARIANCE_LOW
        val exposure = item.exposureScore
        val badExposure = exposure != null &&
            (exposure < OrganizeThresholds.EXPOSURE_UNDER || exposure > OrganizeThresholds.EXPOSURE_OVER)
        if (blurred || badExposure) return OrganizeConfidence.MEDIUM
        // 模糊/曝光正常或未计算，仅 NIMA 低分 → 弱信号
        return OrganizeConfidence.LOW
    }

    private fun largeFileThreshold(item: OrganizeItem): Long =
        if (item.isVideo) {
            OrganizeThresholds.LARGE_VIDEO_BYTES
        } else {
            OrganizeThresholds.LARGE_PHOTO_BYTES
        }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.ConfidenceGraderTest"`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/ConfidenceGrader.kt androidApp/src/test/java/com/mamba/picme/domain/organize/ConfidenceGraderTest.kt
git commit -m "feat(organize): ConfidenceGrader 置信度三级分类"
```

---

## Task 6: DuplicateGrouper（dedup_hash → 重复组成员信息）TDD

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/DuplicateGrouper.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/DuplicateGrouperTest.kt`

**说明**：DUPLICATES 类目的判定输入来自 `dedup_hash` 缓存表（去重 2.0 扫描产物），本组件把哈希行聚合为「uri → 组大小」。纯函数，复用 `PerceptualHash.clusterByHamming`（`core/common/PerceptualHash.kt:81`，签名见 Step 3）。

- [ ] **Step 1: 确认 PerceptualHash.clusterByHamming 签名**

Run: `sed -n '75,110p' androidApp/src/main/java/com/mamba/picme/core/common/PerceptualHash.kt`
Expected: 看到 `fun clusterByHamming(items: List<...>, threshold: Int, ...)` 的实际签名——按真实签名适配 Step 3 的调用（若签名与本计划不符，以源码为准调整参数顺序/类型）。

- [ ] **Step 2: 写失败测试**

```kotlin
package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateGrouperTest {

    private fun row(uri: String, md5: String? = null, phash: Long? = null) =
        DuplicateGrouper.HashInput(uri = uri, md5 = md5, phash = phash)

    @Test
    fun `same md5 forms exact group`() {
        val groups = DuplicateGrouper.group(
            listOf(row("a", md5 = "x"), row("b", md5 = "x"), row("c", md5 = "y"))
        )
        assertEquals(2, groups.getValue("a").exactGroupSize)
        assertEquals(2, groups.getValue("b").exactGroupSize)
        assertEquals(0, groups.getValue("c").exactGroupSize)
    }

    @Test
    fun `close phash forms similar group, far phash does not`() {
        // 汉明距离 ≤5 成簇（与去重 2.0 VISUAL 阈值一致）
        val base = 0b1111000011110000111100001111000011110000111100001111000011110000L
        val close = base xor 0b11L          // 距离 2
        val far = base xor 0b1111111111L    // 距离 10
        val groups = DuplicateGrouper.group(
            listOf(row("a", phash = base), row("b", phash = close), row("c", phash = far))
        )
        assertEquals(2, groups.getValue("a").similarGroupSize)
        assertEquals(2, groups.getValue("b").similarGroupSize)
        assertEquals(0, groups.getValue("c").similarGroupSize)
    }

    @Test
    fun `missing hashes are ignored`() {
        val groups = DuplicateGrouper.group(listOf(row("a"), row("b", md5 = "x")))
        assertEquals(0, groups.getValue("a").exactGroupSize)
        assertEquals(0, groups.getValue("a").similarGroupSize)
    }
}
```

- [ ] **Step 3: 实现 DuplicateGrouper.kt**

```kotlin
package com.mamba.picme.domain.organize

import com.mamba.picme.core.common.PerceptualHash

/**
 * dedup_hash 缓存 → 重复组成员信息（纯函数）：
 * 精确组 = 同 MD5 且成员 ≥2；相似组 = pHash 汉明距离 ≤ [PerceptualHash.SIMILAR_HAMMING_THRESHOLD]
 * 并查集聚类且成员 ≥2（与去重 2.0 VISUAL 同一阈值口径）。
 * 输入仅依赖 data 层投影出的 uri/md5/phash 三元组，不依赖 Room 实体（保持 domain 纯净）。
 */
object DuplicateGrouper {

    data class HashInput(val uri: String, val md5: String?, val phash: Long?)

    data class DupInfo(
        val exactGroupSize: Int = 0,
        val similarGroupSize: Int = 0,
    )

    fun group(inputs: List<HashInput>): Map<String, DupInfo> {
        val exactSizeByUri = inputs
            .filter { input -> input.md5 != null }
            .groupBy { input -> input.md5!! }
            .filterValues { group -> group.size >= 2 }
            .flatMap { (_, group) -> group.map { input -> input.uri to group.size } }
            .toMap()

        val phashUris = inputs.mapNotNull { input ->
            input.phash?.let { phash -> input.uri to phash }
        }
        val similarSizeByUri = mutableMapOf<String, Int>()
        if (phashUris.size >= 2) {
            val clusters = PerceptualHash.clusterByHamming(
                items = phashUris,
                threshold = PerceptualHash.SIMILAR_HAMMING_THRESHOLD,
                hash = { pair -> pair.second },
            )
            clusters.filter { cluster -> cluster.size >= 2 }.forEach { cluster ->
                cluster.forEach { pair -> similarSizeByUri[pair.first] = cluster.size }
            }
        }

        return inputs.associate { input ->
            input.uri to DupInfo(
                exactGroupSize = exactSizeByUri[input.uri] ?: 0,
                similarGroupSize = similarSizeByUri[input.uri] ?: 0,
            )
        }
    }
}
```

> 若 Step 1 确认的 `clusterByHamming` 真实签名不是 `(items, threshold, hash)` 三参形式（例如返回类型是 `List<List<Int>>` 索引簇），按真实签名改写上述调用，语义不变：pHash 两两距离 ≤5 并查集成簇。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.DuplicateGrouperTest"`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/DuplicateGrouper.kt androidApp/src/test/java/com/mamba/picme/domain/organize/DuplicateGrouperTest.kt
git commit -m "feat(organize): DuplicateGrouper——dedup_hash 缓存聚合重复组成员信息"
```

---

## Task 7: OrganizeCategorizer 重构为管线 Facade TDD

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeCategorizer.kt`（全量重写）
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/OrganizeCategorizerTest.kt`（全量重写）

**说明**：对外唯一入口两个方法——`classifyAll`（详情页用，逐媒体裁定结果）与 `board`（hub 用，聚合卡片 + Hero 口径）。旧 `categoriesOf`/`stats`/`CategoryStat` 删除，调用方（DedupViewModel/SwipeQueueBuilder/OrganizeCategoryViewModel）在 Task 10/12/13 切换。

- [ ] **Step 1: 全量重写测试为管线集成测试**

```kotlin
package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeCategorizerTest {

    private val now = 1_800_000_000_000L

    @Suppress("LongParameterList")
    private fun item(
        uri: String,
        isVideo: Boolean = false,
        captureDate: Long = now,
        sizeBytes: Long = 1_000_000,
        relativePath: String? = "DCIM/Camera/",
        ocrText: String? = null,
        pixelArea: Long? = 12_000_000,
        labels: String? = null,
        hasFace: Boolean = false,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
        blurScore: Float? = null,
        exposureScore: Float? = null,
        lastViewedAt: Long? = null,
        isFavorite: Boolean = false,
        personPhotoCount: Int? = null,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = captureDate, sizeBytes = sizeBytes,
        relativePath = relativePath, ocrText = ocrText, pixelArea = pixelArea,
        labels = labels, hasFace = hasFace, aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore, blurScore = blurScore,
        exposureScore = exposureScore, lastViewedAt = lastViewedAt, isFavorite = isFavorite,
        personPhotoCount = personPhotoCount, exactDupGroupSize = exactDupGroupSize,
        similarDupGroupSize = similarDupGroupSize,
    )

    @Test
    fun `mutex - screenshot with low scores lands only in SCREEN_CONTENT`() {
        val classified = OrganizeCategorizer.classifyAll(
            listOf(
                item(
                    "shot", relativePath = "Pictures/Screenshots/",
                    aestheticScore = 1.0f, blurScore = 1.0f, ocrText = "x".repeat(600),
                )
            ),
            now = now,
        )
        assertEquals(1, classified.size)
        assertEquals(OrganizeCategory.SCREEN_CONTENT, classified[0].category)
    }

    @Test
    fun `hero reclaim is HIGH non-protected union - no double count regression AC-F1-1`() {
        // 同一张图在 v1 会被截图+模糊+文档计 3 次；v2 互斥后 Hero 只计 1 次
        val board = OrganizeCategorizer.board(
            listOf(
                item("shot", relativePath = "Pictures/Screenshots/", sizeBytes = 100),
                item("big", isVideo = true, sizeBytes = 250L * 1024 * 1024),
                item("normal"),
            ),
            now = now,
        )
        assertEquals(100L + 250L * 1024 * 1024, board.heroReclaimBytes)
    }

    @Test
    fun `protected items excluded from hero and never high-counted`() {
        val board = OrganizeCategorizer.board(
            listOf(
                // 6 年前的模糊老照片：HIGH 置信但 protected → 不进 Hero、不进 highCount
                item(
                    "old", captureDate = now - 6 * YEAR_MILLIS,
                    blurScore = 10.0f, sizeBytes = 500,
                ),
            ),
            now = now,
        )
        assertEquals(0L, board.heroReclaimBytes)
        val card = board.categories.single { card -> card.category == OrganizeCategory.LOW_QUALITY_PHOTOS }
        assertEquals(1, card.totalCount)
        assertEquals(0, card.highCount)
        assertEquals(1, card.protectedCount)
    }

    @Test
    fun `categories sorted by high confidence bytes descending`() {
        val board = OrganizeCategorizer.board(
            listOf(
                item("shot", relativePath = "Pictures/Screenshots/", sizeBytes = 100),
                item("big", isVideo = true, sizeBytes = 250L * 1024 * 1024),
            ),
            now = now,
        )
        assertEquals(
            listOf(OrganizeCategory.LARGE_FILES, OrganizeCategory.SCREEN_CONTENT),
            board.categories.map { card -> card.category },
        )
    }

    @Test
    fun `needs-scan coverage when no library item has the signal`() {
        val board = OrganizeCategorizer.board(
            listOf(item("a")), // 全库无 blurScore
            now = now,
        )
        // 无命中时类目卡不渲染（totalCount=0 不生成卡），但覆盖度可查询
        assertEquals(
            SignalCoverage.NEEDS_SCAN,
            OrganizeCategorizer.coverageOf(OrganizeCategory.LOW_QUALITY_PHOTOS, listOf(item("a"))),
        )
        assertEquals(
            SignalCoverage.READY,
            OrganizeCategorizer.coverageOf(OrganizeCategory.SCREEN_CONTENT, listOf(item("a"))),
        )
        assertTrue(board.categories.isEmpty())
    }

    @Test
    fun `review count aggregates MEDIUM and LOW non-protected`() {
        val board = OrganizeCategorizer.board(
            listOf(
                item("borderline", blurScore = 80.0f, sizeBytes = 10),       // MEDIUM
                item("aesthetic", blurScore = 500.0f, exposureScore = 0.5f,
                    aestheticScore = 2.0f, sizeBytes = 10),                 // 不进类目（无强信号）
            ),
            now = now,
        )
        assertEquals(1, board.heroReviewCount)
    }
}
```

> 注：第 6 个用例中「仅 NIMA 低分」的图在 v2 裁定下不进任何类目（arbiter 不定类），`heroReviewCount = 1` 只来自 borderline 那张。这符合 spec §2（NIMA 不再单独定类）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.OrganizeCategorizerTest"`
Expected: 编译失败（classifyAll/board/coverageOf 不存在；同时旧调用方 DedupViewModel 等报 `stats` 缺失——属预期，Task 10 起收口）

- [ ] **Step 3: 全量重写 OrganizeCategorizer.kt**

```kotlin
package com.mamba.picme.domain.organize

/** hub 卡片预览缩略图张数。 */
private const val PREVIEW_LIMIT = 4

/**
 * 整理中心 v2 管线编排 Facade（纯函数，对外唯一入口）：
 * CategoryArbiter（互斥裁定）→ ValueGuard（价值保护）→ ConfidenceGrader（置信分级）。
 * 旧集合语义 categoriesOf/stats/CategoryStat 已删除——类目页与 SwipeReview 同源消费本管线。
 */
object OrganizeCategorizer {

    /** 逐媒体裁定（类目详情页输入）。未命中任何类目的媒体不出现在结果中。 */
    fun classifyAll(items: List<OrganizeItem>, now: Long): List<ClassifiedItem> =
        items.mapNotNull { item ->
            val category = CategoryArbiter.classify(item) ?: return@mapNotNull null
            val verdict = ValueGuard.assess(item, category, now)
            ClassifiedItem(
                item = item,
                category = category,
                confidence = ConfidenceGrader.grade(item, category),
                protected = verdict.protected,
                protectReasons = verdict.reasons,
            )
        }

    /** hub 聚合：类目卡（按建议优先级降序）+ Hero 口径（HIGH 非 protected 去重并集）。 */
    fun board(items: List<OrganizeItem>, now: Long): OrganizeBoard {
        val classified = classifyAll(items, now)
        val cards = classified
            .groupBy { entry -> entry.category }
            .map { (category, entries) ->
                val high = entries.filter { entry ->
                    entry.confidence == OrganizeConfidence.HIGH && !entry.protected
                }
                val review = entries.filter { entry ->
                    entry.confidence != OrganizeConfidence.HIGH && !entry.protected
                }
                CategoryBoard(
                    category = category,
                    totalCount = entries.size,
                    totalBytes = entries.sumOf { entry -> entry.item.sizeBytes },
                    highCount = high.size,
                    highBytes = high.sumOf { entry -> entry.item.sizeBytes },
                    reviewCount = review.size,
                    protectedCount = entries.count { entry -> entry.protected },
                    previewUris = entries.take(PREVIEW_LIMIT).map { entry -> entry.item.uri },
                    coverage = coverageOf(category, items),
                )
            }
            .sortedByDescending { card -> card.highBytes }
        return OrganizeBoard(
            categories = cards,
            // 互斥裁定保证一媒体一卡，high 求和即并集（AC-F1-1 回归防线）
            heroReclaimBytes = cards.sumOf { card -> card.highBytes },
            heroReviewCount = cards.sumOf { card -> card.reviewCount },
        )
    }

    /**
     * 类目信号覆盖度：全库任一媒体持有该类目关键信号 → READY，否则 NEEDS_SCAN
     * （驱动 hub「需先扫描」引导态，修复 v1 类目静默消失）。
     * 路径/大小类信号（SCREEN_CONTENT/LARGE_FILES/DUPLICATES）MediaStore 常备，恒 READY。
     */
    fun coverageOf(category: OrganizeCategory, items: List<OrganizeItem>): SignalCoverage =
        when (category) {
            OrganizeCategory.DUPLICATES,
            OrganizeCategory.SCREEN_CONTENT,
            OrganizeCategory.LARGE_FILES,
            -> SignalCoverage.READY
            OrganizeCategory.DOCUMENTS ->
                if (items.any { item -> item.ocrText != null || item.labels != null }) {
                    SignalCoverage.READY
                } else {
                    SignalCoverage.NEEDS_SCAN
                }
            OrganizeCategory.LOW_QUALITY_PORTRAITS ->
                if (items.any { item -> item.faceQualityScore != null }) {
                    SignalCoverage.READY
                } else {
                    SignalCoverage.NEEDS_SCAN
                }
            OrganizeCategory.LOW_QUALITY_PHOTOS ->
                if (items.any { item -> item.blurScore != null }) {
                    SignalCoverage.READY
                } else {
                    SignalCoverage.NEEDS_SCAN
                }
        }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.OrganizeCategorizerTest"`
Expected: 6 tests PASS（模块整体编译仍红：DedupViewModel/SwipeQueueBuilder/OrganizeCategoryViewModel/DedupHomeHub/OrganizeCategoryScreen 引用旧 API——后续 Task 收口，JVM 测试任务可能因整模块编译失败而无法运行，此时先推进 Task 8-13 再统一回归；或在 Task 10-13 完成后再执行本步。执行者按实际编译状态选择顺序，不要为此返工本 Task 代码）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeCategorizer.kt androidApp/src/test/java/com/mamba/picme/domain/organize/OrganizeCategorizerTest.kt
git commit -m "refactor(organize): OrganizeCategorizer 重构为管线 Facade（classifyAll/board/coverageOf）"
```

---

## Task 8: BlurAnalyzer（真模糊 + 曝光检测）TDD

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/BlurAnalyzer.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/BlurAnalyzerTest.kt`

**说明**：算法核心输入为灰度 IntArray（纯 Kotlin，JVM 可测）；Bitmap 解码/灰度化在 data 层（Task 9）。阈值 `BLUR_VARIANCE_LOW=100.0f` 为初始值，本 Task 用合成样本定标后如有偏差回改 `OrganizeThresholds`。

- [ ] **Step 1: 写失败测试（合成灰度图定标）**

```kotlin
package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlurAnalyzerTest {

    private val size = 64

    /** 纯色图：零纹理，Laplacian 方差应趋近 0。 */
    private fun flatImage(gray: Int): IntArray = IntArray(size * size) { gray }

    /** 随机噪点图：高频纹理，方差应很大。 */
    private fun noisyImage(seed: Int = 42): IntArray =
        IntArray(size * size) { Random(seed + it).nextInt(256) }

    /** 棋盘格：强边缘，方差大。 */
    private fun checkerImage(): IntArray = IntArray(size * size) { index ->
        val x = index % size
        val y = index / size
        if ((x / 8 + y / 8) % 2 == 0) 255 else 0
    }

    @Test
    fun `flat image has near-zero blur variance`() {
        val variance = BlurAnalyzer.laplacianVariance(flatImage(128), size, size)
        assertTrue("flat variance=$variance should be < 1", variance < 1.0f)
    }

    @Test
    fun `noisy and checker images have high variance, far above threshold`() {
        val noisy = BlurAnalyzer.laplacianVariance(noisyImage(), size, size)
        val checker = BlurAnalyzer.laplacianVariance(checkerImage(), size, size)
        assertTrue("noisy=$noisy", noisy > OrganizeThresholds.BLUR_VARIANCE_LOW * 10)
        assertTrue("checker=$checker", checker > OrganizeThresholds.BLUR_VARIANCE_LOW * 10)
    }

    @Test
    fun `mean luminance normalized 0 to 1`() {
        assertEquals(0.0f, BlurAnalyzer.meanLuminance(flatImage(0)), 0.001f)
        assertEquals(1.0f, BlurAnalyzer.meanLuminance(flatImage(255)), 0.001f)
        assertEquals(128 / 255.0f, BlurAnalyzer.meanLuminance(flatImage(128)), 0.01f)
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertEquals(0.0f, BlurAnalyzer.laplacianVariance(IntArray(0), 0, 0), 0.0f)
        assertEquals(0.0f, BlurAnalyzer.meanLuminance(IntArray(0)), 0.0f)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.BlurAnalyzerTest"`
Expected: 编译失败（BlurAnalyzer 不存在）

- [ ] **Step 3: 实现 BlurAnalyzer.kt**

```kotlin
package com.mamba.picme.domain.organize

/**
 * 真模糊/曝光检测（纯 Kotlin，灰度 IntArray 输入，JVM 可测）：
 * - 模糊 = Laplacian 4 邻域卷积的方差（越大越清晰；运动/失焦模糊会抹平高频 → 方差塌缩）
 * - 曝光 = 平均亮度归一（0~1）
 * 输入约定：≤256px 降采样灰度图（data 层 Bitmap 提取负责），毫秒级完成。
 * 升级位（spec §11 方案 2 预留）：未来可在此接口后替换为 MNN 模糊检测模型。
 */
object BlurAnalyzer {

    /** Laplacian 方差；尺寸非法或像素数不足返回 0。 */
    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || gray.size < width * height) return 0.0f
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val center = gray[row + x]
                val lap = 4 * center -
                    gray[row + x - 1] - gray[row + x + 1] -
                    gray[row + x - width] - gray[row + x + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                count++
            }
        }
        if (count == 0) return 0.0f
        val mean = sum / count
        return (sumSq / count - mean * mean).toFloat()
    }

    /** 平均亮度归一（0~1）；空输入返回 0。 */
    fun meanLuminance(gray: IntArray): Float {
        if (gray.isEmpty()) return 0.0f
        var sum = 0L
        gray.forEach { value -> sum += value }
        return sum.toFloat() / gray.size / 255.0f
    }
}
```

- [ ] **Step 4: 跑测试确认通过并按需校准阈值**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.BlurAnalyzerTest"`
Expected: 4 tests PASS。若 noisy/checker 未超阈值 10×，检查实现；不要在测试里放水改断言。

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/BlurAnalyzer.kt androidApp/src/test/java/com/mamba/picme/domain/organize/BlurAnalyzerTest.kt
git commit -m "feat(organize): BlurAnalyzer——Laplacian 方差真模糊 + 亮度曝光检测（纯 Kotlin 可 JVM 测）"
```

---

## Task 9: OrganizeRepositoryImpl 扩展（新信号合并 + 惰性补算）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/repository/OrganizeRepositoryImpl.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/repository/OrganizeRepository.kt`

- [ ] **Step 1: 接口扩展**

`OrganizeRepository.kt` 追加：

```kotlin
    /**
     * 惰性补算模糊/曝光分：对 blurScore 为 null 的图片分批计算并回写 Room
     * （Room Flow 自动驱动 UI 刷新）。返回本次补算张数。幂等，可反复调用。
     */
    suspend fun backfillQualitySignals(batchLimit: Int = 200): Int
```

- [ ] **Step 2: mergeRows 合并新信号**

`OrganizeRepositoryImpl.kt` 的 `mergeRows`（:49-70）改为合并全部 v2 信号：

```kotlin
    private suspend fun mergeRows(rows: List<OrganizeRow>): List<OrganizeItem> = withContext(ioDispatcher) {
        if (rows.isEmpty()) return@withContext emptyList()
        val metaByUri = queryMediaStoreMeta(appContext.contentResolver)
        val pixelAreaByUri = queryCachedPixelAreas(rows.map { row -> row.uri })
        val dupInfoByUri = queryDuplicateInfo()
        val personCountByFaceId = mediaDao.getPersonPhotoCounts()
            .associate { count -> count.faceId to count.cnt }
        rows.map { row ->
            val meta = metaByUri[row.uri]
            val dup = dupInfoByUri[row.uri]
            OrganizeItem(
                uri = row.uri,
                // isVideo 以 Room type 为准（MediaStore collection 来源仅作 meta 补充）
                isVideo = row.type == MediaType.VIDEO.name,
                captureDate = row.captureDate,
                sizeBytes = meta?.sizeBytes ?: 0L,
                relativePath = meta?.path,
                ocrText = row.ocrText,
                pixelArea = pixelAreaByUri[row.uri] ?: meta?.pixelArea,
                labels = row.labels,
                hasFace = row.hasFace,
                aestheticScore = row.aestheticScore,
                faceQualityScore = row.faceQualityScore,
                blurScore = row.blurScore,
                exposureScore = row.exposureScore,
                lastViewedAt = row.lastViewedAt,
                isFavorite = meta?.isFavorite ?: false,
                personPhotoCount = row.faceId?.let { faceId -> personCountByFaceId[faceId] },
                exactDupGroupSize = dup?.exactGroupSize ?: 0,
                similarDupGroupSize = dup?.similarGroupSize ?: 0,
            )
        }
    }

    /** dedup_hash 全量哈希 → 重复组成员信息（空表/未扫描时全零，类目自然不出现）。 */
    private suspend fun queryDuplicateInfo(): Map<String, DuplicateGrouper.DupInfo> =
        runCatching {
            val hashes = dedupHashDao.getAllHashes()
            if (hashes.isEmpty()) {
                emptyMap()
            } else {
                DuplicateGrouper.group(
                    hashes.map { row -> DuplicateGrouper.HashInput(row.uri, row.md5, row.phash) }
                )
            }
        }.getOrElse { error ->
            Logger.w(TAG, "query duplicate info failed", error)
            emptyMap()
        }
```

`MediaMeta`（:81-87）加 `isFavorite`：

```kotlin
    private data class MediaMeta(
        val sizeBytes: Long,
        /** 截图目录判定路径：RELATIVE_PATH（API 29+），缺失时 DATA 列兜底。 */
        val path: String?,
        /** 像素面积（WIDTH×HEIGHT；列缺失或脏值 ≤0 为 null，OCR 判定退回绝对阈值）。 */
        val pixelArea: Long?,
        /** MediaStore 收藏标记（API 29+；低版本恒 false）。 */
        val isFavorite: Boolean,
    )
```

`queryCollectionMeta`（:98-140）projection 与读列处追加 IS_FAVORITE（Q-only，与 RELATIVE_PATH 同守卫）：

```kotlin
            if (hasQColumns) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.IS_FAVORITE)
            }
```
```kotlin
                val favoriteCol = if (hasQColumns) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
                } else {
                    -1
                }
```
```kotlin
                    out[uri] = MediaMeta(
                        sizeBytes = if (size > 0) size else 0L,
                        path = relativePath ?: dataPath,
                        pixelArea = if (width > 0 && height > 0) width * height else null,
                        isFavorite = favoriteCol >= 0 && cursor.getInt(favoriteCol) != 0,
                    )
```

import 追加 `com.mamba.picme.domain.organize.DuplicateGrouper`。

- [ ] **Step 3: 惰性补算实现**

`OrganizeRepositoryImpl` 追加（Bitmap 解码走 `ContentResolver` + `BitmapFactory`，inSampleSize 降到 ≤256px）：

```kotlin
    /** 单批补算输入投影（缺 blurScore 的图片）。 */
    private data class BackfillRow(val uri: String, val isVideo: Boolean)

    override suspend fun backfillQualitySignals(batchLimit: Int): Int = withContext(ioDispatcher) {
        val pending = mediaDao.observeOrganizeRows().first()
            .filter { row -> row.blurScore == null && row.type != MediaType.VIDEO.name }
            .take(batchLimit)
        var done = 0
        pending.forEach { row ->
            val scores = runCatching { computeQualityScores(row.uri) }.getOrNull()
            if (scores != null) {
                mediaDao.updateQualityScores(row.uri, scores.first, scores.second)
                done++
            }
        }
        if (done > 0) Logger.d(TAG, "backfill quality signals: $done/${pending.size}")
        done
    }

    /** 解码 ≤256px 灰度图 → (blurScore, exposureScore)；解码失败返回 null（不阻断批次）。 */
    private fun computeQualityScores(uri: String): Pair<Float, Float>? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256 || bounds.outHeight / (sample * 2) >= 256) {
            sample *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = appContext.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        // BT.601 灰度化
        val gray = IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            (299 * r + 587 * g + 114 * b) / 1000
        }
        return BlurAnalyzer.laplacianVariance(gray, width, height) to BlurAnalyzer.meanLuminance(gray)
    }
```

import 追加 `com.mamba.picme.domain.organize.BlurAnalyzer`、`kotlinx.coroutines.flow.first`（若已有则跳过）。

> Lambda 参数规范：全文不得出现隐式 `it`（本 Task 示例已遵守，移植时保持）。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: 与 Task 7 遗留错误一致（仅旧 API 引用处红），本文件无新增错误。

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/repository/OrganizeRepositoryImpl.kt androidApp/src/main/java/com/mamba/picme/domain/repository/OrganizeRepository.kt
git commit -m "feat(organize): 仓储层合并 v2 信号（收藏/人物计数/重复组）+ 模糊分惰性补算回写"
```

---

## Task 10: lastViewedAt 查看回写链路

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt:207-214`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/MediaViewModel.kt`
- Modify: AndroidMediaRepository 接口与实现（执行者先 `grep -rn "AndroidMediaRepository" androidApp/src/main/java/com/mamba/picme/domain/repository/ androidApp/src/main/java/com/mamba/picme/data/repository/` 定位接口与 impl 文件）
- Modify: MediaPager 各调用方（GalleryScreen / MemoryDetailScreen / ChatScreen 等，`grep -rn "MediaPager(" androidApp/src/main/java/com/mamba/picme/features/` 找全）

- [ ] **Step 1: 仓储接口 + 实现加方法**

`AndroidMediaRepository` 接口追加：

```kotlin
    /** 记录媒体在查看器被打开（整理中心 v2 价值保护「用户互动」信号）。 */
    suspend fun markMediaViewed(uri: String)
```

实现类（MediaRepositoryImpl 或同名实现）：

```kotlin
    override suspend fun markMediaViewed(uri: String) = withContext(ioDispatcher) {
        runCatching { mediaDao.updateLastViewedAt(uri, System.currentTimeMillis()) }
            .onFailure { error -> Logger.w(TAG, "markMediaViewed failed: $uri", error) }
    }
```

（若实现类内 dispatcher/DAO 字段名不同，以其现有写法为准；失败静默 + 日志，不影响查看体验。）

- [ ] **Step 2: MediaViewModel 加入口**

```kotlin
    /** 查看器页切换/打开时回写查看时间（fire-and-forget，不阻塞 UI）。 */
    fun markMediaViewed(uri: String) {
        viewModelScope.launch {
            repository.markMediaViewed(uri)
        }
    }
```

- [ ] **Step 3: MediaPager 加回调并在翻页处触发**

`MediaPager` 函数签名（:132-147）追加参数（带默认值，调用方可渐进接入）：

```kotlin
    onPageViewed: (String) -> Unit = {},
```

现有 `LaunchedEffect(pagerState.currentPage)`（:207-214）内追加一行：

```kotlin
        LaunchedEffect(pagerState.currentPage) {
            currentPageZoomed = false
            if (currentAsset?.type != MediaType.PHOTO) {
                showLandmarkOverlay = false
            }
            currentAsset?.let { asset -> onPageViewed(asset.uri) }
            // 按需触发 summary：批量用 ML Kit（无 summary），此处单张 tagger（默认 Florence-2）生成并缓存
            currentAsset?.let { asset -> onTriggerSummary(asset.id) }
        }
```

- [ ] **Step 4: 接线调用方**

GalleryScreen 等宿主页的 `MediaPager(...)` 调用处追加：

```kotlin
                onPageViewed = { uri -> viewModel.markMediaViewed(uri) },
```

（宿主 VM 不是 MediaViewModel 的页面——如 MemoryDetail 经 Activity 级 MediaViewModel——同样接 Activity 级实例。）

- [ ] **Step 5: 编译验证 + Commit**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: 本链路无新增错误（Task 7 遗留错误除外）。

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/ androidApp/src/main/java/com/mamba/picme/domain/repository/ androidApp/src/main/java/com/mamba/picme/data/repository/
git commit -m "feat(organize): 查看器 lastViewedAt 回写链路（价值保护用户互动信号）"
```

---

## Task 11: DedupViewModel hub 数据流切换（stats → board）+ 补算触发

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupViewModel.kt:93-100`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeScreen.kt`（Route 处 stats 形参传递，执行者先读该文件定位 `categoryStats` 消费点）

- [ ] **Step 1: categoryStats 替换为 organizeBoard**

`DedupViewModel.kt:93-100` 替换为：

```kotlin
    /** 整理中心 hub 看板（Config 态由 UI 订阅）：类目卡聚合 + Hero 口径，管线单一事实来源。 */
    val organizeBoard: StateFlow<OrganizeBoard> =
        organizeRepository.observeItems()
            .map { items -> OrganizeCategorizer.board(items, now = System.currentTimeMillis()) }
            // 媒体库任何写都会触发 observeItems 重算，相同结果不下发（防抖重组）
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), OrganizeBoard(emptyList(), 0L, 0))
```

import 清理/追加：删 `CategoryStat`，加 `com.mamba.picme.domain.organize.OrganizeBoard`。

- [ ] **Step 2: init 触发后台补算**

`DedupViewModel` init（或首个方法前）追加：

```kotlin
    init {
        // 整理中心 v2：后台分批补算模糊/曝光分（缺分即 LOW 覆盖，hub 引导态承接；补算回写经 Room Flow 自动刷新）
        scope.launch(ioDispatcher) {
            runCatching {
                var remaining = organizeRepository.backfillQualitySignals()
                while (remaining > 0) {
                    remaining = organizeRepository.backfillQualitySignals()
                }
            }.onFailure { error -> Logger.w(TAG, "backfill quality signals failed", error) }
        }
    }
```

（若 DedupViewModel 已有 init 块则合入；确认类内存在 `Logger`/`TAG` 常量，无则加 `private companion object { const val TAG = "PoLang:Organize" }`——先读文件确认已有 TAG 值并沿用。）

- [ ] **Step 3: DedupHomeScreen Route 透传改名**

Route 内 `val stats by viewModel.categoryStats.collectAsState()` 改为 `organizeBoard`，`DedupHubContent(stats = ...)` 形参改为 `board = ...`（Task 12 改签名）。本步仅做机械改名，保证 Task 12 前的编译一致性由两 Task 连续完成后统一验证。

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/
git commit -m "refactor(organize): DedupViewModel hub 数据流切 OrganizeBoard + 后台质量分补算"
```

---

## Task 12: DedupHomeHub UI v2（类目卡置信度徽标 + 优先级排序 + 引导态 + Hero 修正）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeHub.kt`

- [ ] **Step 1: DedupHubContent 签名与数据源切换**

```kotlin
@Composable
fun DedupHubContent(
    config: DedupScanConfig,
    policy: KeepPolicy,
    board: OrganizeBoard,
    onQuickTidy: () -> Unit,
    onOpenCategory: (OrganizeCategory) -> Unit,
    onOpenScan: () -> Unit,
    onStartScan: (DedupScanConfig) -> Unit,
    onOpenKeepRules: () -> Unit,
) {
```

（`onOpenScan` 为新增回调：引导态点击切到 SCAN tab；在 `OrganizeHomeRoute`/`DedupHomeRoute` 接线处注入——执行者读 `OrganizeHomeRoute.kt` 找到 tab 切换回调注入点，embedded 模式下经 `organizeTabRequest` 或直接回调切页。）

Column 内 `OrgHeroCard(stats = stats)` → `OrgHeroCard(board = board)`；类目卡渲染改为：

```kotlin
        board.categories.forEach { card ->
            OrganizeCategoryCard(
                card = card,
                onClick = {
                    if (card.coverage == SignalCoverage.NEEDS_SCAN) {
                        onOpenScan()
                    } else {
                        onOpenCategory(card.category)
                    }
                }
            )
        }
```

- [ ] **Step 2: Hero 卡修正（真实并集口径 + 待确认副行）**

`OrgHeroCard` 重写：

```kotlin
/** Hero 卡：label + 品牌渐变大数字（HIGH 置信非保护去重并集）+ 待确认副行 + 覆盖类目数。 */
@Composable
private fun OrgHeroCard(board: OrganizeBoard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.lg,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.org_hero_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatBytes(board.heroReclaimBytes),
                style = TextStyle(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    brush = orgBrandGradient
                )
            )
            Text(
                text = stringResource(R.string.org_hero_across, board.categories.size + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (board.heroReviewCount > 0) {
                Text(
                    text = stringResource(R.string.org_hero_review, board.heroReviewCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 3: 类目卡 v2（置信度徽标行 + 独立可释放数 + 引导态）**

`OrganizeCategoryCard` 重写：

```kotlin
/**
 * 类目卡 v2：图标块 + 标题 + meta 行 + 置信度徽标行（● 高置信 / ○ 需确认）+ 4 缩略图条 + chevron。
 * NEEDS_SCAN 引导态：半透明 + 「需先扫描」徽标，点击跳 SCAN tab（修复 v1 类目静默消失）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizeCategoryCard(
    card: CategoryBoard,
    onClick: () -> Unit,
) {
    val needsScan = card.coverage == SignalCoverage.NEEDS_SCAN
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        alpha = if (needsScan) 0.6f else 1f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OrgCategoryIconBlock(icon = organizeCategoryIcon(card.category))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(organizeCategoryLabelRes(card.category)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (needsScan) {
                    Text(
                        text = stringResource(R.string.org_needs_scan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.org_cat_meta,
                            card.totalCount,
                            formatBytes(card.totalBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 置信度徽标行：高置信（primary 实心点）+ 需确认（空心点）+ 高置信可释放
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConfidenceDot(filled = true)
                        Text(
                            text = stringResource(R.string.org_confidence_high, card.highCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (card.reviewCount > 0) {
                            ConfidenceDot(filled = false)
                            Text(
                                text = stringResource(R.string.org_confidence_review, card.reviewCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (card.protectedCount > 0) {
                            Text(
                                text = stringResource(R.string.org_protected_count, card.protectedCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (!needsScan) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        card.previewUris.forEach { uri -> OrgPreviewThumb(uri = uri) }
                    }
                    if (card.highBytes > 0) {
                        Text(
                            text = stringResource(R.string.org_cat_reclaim, formatBytes(card.highBytes)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 置信度小圆点：filled=高置信（primary 实色），否则描边空心。 */
@Composable
private fun ConfidenceDot(filled: Boolean) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(
                if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
            )
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
    )
}
```

import 追加：`androidx.compose.foundation.shape.CircleShape`、`androidx.compose.foundation.border`、`androidx.compose.material3.Card` 的 `alpha` 参数在 material3 Card 可用（若无 alpha 形参，改用 `Modifier.alpha(0.6f)`——执行者按所用 material3 版本校准）。

- [ ] **Step 4: 资源映射更新（枚举改名）**

`organizeCategoryIcon` / `organizeCategoryLabelRes` 两个 when 改为：

```kotlin
private fun organizeCategoryIcon(category: OrganizeCategory): ImageVector = when (category) {
    OrganizeCategory.DUPLICATES -> Icons.Outlined.BurstMode
    OrganizeCategory.SCREEN_CONTENT -> Icons.Outlined.ScreenshotMonitor
    OrganizeCategory.DOCUMENTS -> Icons.Outlined.Description
    OrganizeCategory.LOW_QUALITY_PORTRAITS -> Icons.Outlined.Face
    OrganizeCategory.LOW_QUALITY_PHOTOS -> Icons.Outlined.BlurOn
    OrganizeCategory.LARGE_FILES -> Icons.Outlined.Videocam
}

private fun organizeCategoryLabelRes(category: OrganizeCategory): Int = when (category) {
    OrganizeCategory.DUPLICATES -> R.string.org_cat_duplicates
    OrganizeCategory.SCREEN_CONTENT -> R.string.org_cat_screen_content
    OrganizeCategory.DOCUMENTS -> R.string.org_cat_documents
    OrganizeCategory.LOW_QUALITY_PORTRAITS -> R.string.org_cat_portraits
    OrganizeCategory.LOW_QUALITY_PHOTOS -> R.string.org_cat_blurry
    OrganizeCategory.LARGE_FILES -> R.string.org_cat_large_files
}
```

（strings 新键 `org_cat_screen_content` / `org_cat_large_files` / `org_hero_review` / `org_needs_scan` / `org_confidence_high` / `org_confidence_review` / `org_protected_count` / `org_cat_reclaim` 在 Task 16 统一五语落地；本 Task 先在 `values/strings.xml` 加英文键保证编译。）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeHub.kt androidApp/src/main/res/values/strings.xml
git commit -m "feat(organize): hub 类目卡 v2——置信度徽标/建议优先级排序/引导态/Hero 真实并集口径"
```

---

## Task 13: OrganizeCategoryViewModel v2（三段分组 + 置信筛选）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/organize/OrganizeCategoryViewModel.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/gallery/organize/OrganizeCategoryViewModelTest.kt`（更新既有用例 + 新增三段分组断言）

- [ ] **Step 1: 状态模型切换为 ClassifiedItem**

`OrganizeCategoryUiState.Ready` 的 `items: List<OrganizeItem>` 改为 `items: List<ClassifiedItem>`（其余字段不变）。派生分组作为 UI/VM 共享纯函数，放在 VM 文件顶层：

```kotlin
/** 详情页三段分组（spec §6.3）：建议删除（HIGH 非保护）/ 请确认（MEDIUM+LOW 非保护）/ 珍贵保护。 */
data class CategorySections(
    val suggested: List<ClassifiedItem>,
    val review: List<ClassifiedItem>,
    val protectedItems: List<ClassifiedItem>,
)

fun List<ClassifiedItem>.toSections(): CategorySections = CategorySections(
    suggested = filter { entry -> entry.confidence == OrganizeConfidence.HIGH && !entry.protected },
    review = filter { entry -> entry.confidence != OrganizeConfidence.HIGH && !entry.protected },
    protectedItems = filter { entry -> entry.protected },
)
```

- [ ] **Step 2: 加载与预选口径修正**

`loadCategoryItems`（:96-99）改为：

```kotlin
    private suspend fun loadCategoryItems(): List<ClassifiedItem> = withContext(ioDispatcher) {
        OrganizeCategorizer.classifyAll(organizeRepository.loadItems(), now = System.currentTimeMillis())
            .filter { entry -> entry.category == category }
    }
```

`reload` 的预选口径（:85-92）改为**仅默认勾选 HIGH 非 protected**（修复 v1「AI 预选=全选」）：

```kotlin
                selected = if (preselect && _aiPreselectEnabled.value) {
                    items.filter { entry ->
                        entry.confidence == OrganizeConfidence.HIGH && !entry.protected
                    }.map { entry -> entry.item.uri }.toSet()
                } else {
                    emptySet()
                },
```

`setAiPreselect`（:122-129）同步改为同口径（开 = 选 HIGH 非保护；关 = 清空）。`selectAll` 保持全选（用户显式行为），`toggle`/`deleteSelected`/outcome 结算逻辑不变，仅把 `item.uri` 取值改为 `entry.item.uri`、`sumOf { item -> item.sizeBytes }` 改为 `sumOf { entry -> entry.item.sizeBytes }`。

- [ ] **Step 3: 更新既有 VM 测试 + 新增三段用例**

既有 `OrganizeCategoryViewModelTest` 中构造 `OrganizeItem` 的夹具适配新字段（默认值即可），预选断言从「全选」改为「仅 HIGH 非保护」。新增用例（完整代码，FakeRepo/作用域注入模式沿用该文件既有写法）：

```kotlin
    @Test
    fun `preselect only picks HIGH confidence non-protected items`() = runTest {
        val now = System.currentTimeMillis()
        val items = listOf(
            // HIGH：路径截图（强信号）
            organizeItem(uri = "shot", relativePath = "Pictures/Screenshots/"),
            // MEDIUM：边界模糊（0.5×阈值 < blur < 1×阈值）
            organizeItem(uri = "blur", blurScore = 80.0f),
            // HIGH 但 protected：6 年前 + 强模糊
            organizeItem(uri = "old", captureDate = now - 6 * 365L * 24 * 3600 * 1000, blurScore = 10.0f),
        )
        val viewModel = OrganizeCategoryViewModel(
            category = OrganizeCategory.SCREEN_CONTENT, // 仅 shot 命中该类目
            organizeRepository = FakeOrganizeRepository(items),
            trashBackend = fakeTrashBackend,
            coroutineScope = backgroundScope,
        )
        val ready = viewModel.uiState.first { state -> state is OrganizeCategoryUiState.Ready }
            as OrganizeCategoryUiState.Ready
        assertEquals(setOf("shot"), ready.selected)
    }

    @Test
    fun `toSections splits suggested review protected disjoint and complete`() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            classified("a", OrganizeConfidence.HIGH, protected = false),
            classified("b", OrganizeConfidence.MEDIUM, protected = false),
            classified("c", OrganizeConfidence.LOW, protected = false),
            classified("d", OrganizeConfidence.HIGH, protected = true),
        )
        val sections = entries.toSections()
        assertEquals(listOf("a"), sections.suggested.map { entry -> entry.item.uri })
        assertEquals(listOf("b", "c"), sections.review.map { entry -> entry.item.uri })
        assertEquals(listOf("d"), sections.protectedItems.map { entry -> entry.item.uri })
        assertEquals(entries.size, sections.suggested.size + sections.review.size + sections.protectedItems.size)
    }
```

（`organizeItem`/`classified`/`FakeOrganizeRepository`/`fakeTrashBackend` 夹具若该测试文件已有等价物则复用其命名；没有则按既有测试的 fake 模式补齐——fake 仓库只需返回固定 `List<OrganizeItem>`。）

- [ ] **Step 4: 跑 VM 测试**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.gallery.organize.OrganizeCategoryViewModelTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/organize/OrganizeCategoryViewModel.kt androidApp/src/test/java/com/mamba/picme/features/gallery/organize/OrganizeCategoryViewModelTest.kt
git commit -m "feat(organize): 详情页 VM v2——HIGH 置信预选口径 + 三段分组模型"
```

---

## Task 14: OrganizeCategoryScreen UI v2（三段分组网格）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/organize/OrganizeCategoryScreen.kt`

- [ ] **Step 1: 网格态改三段 LazyVerticalGrid**

`OrganizeGridContent` 重写为三段（用 `GridItemSpan` 标题行，沿用 `LazyVerticalGrid`）：

```kotlin
@Composable
private fun OrganizeGridContent(
    state: OrganizeCategoryUiState.Ready,
    onToggle: (String) -> Unit,
) {
    val sections = state.items.toSections()
    Column(modifier = Modifier.fillMaxSize()) {
        // 副行：左「N items · X MB」+ 右「N AI-preselected」（保持 v1 结构）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.org_cat_meta,
                    state.items.size,
                    formatBytes(state.items.sumOf { entry -> entry.item.sizeBytes })
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.org_ai_preselected, state.selected.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (sections.suggested.isNotEmpty()) {
                item(key = "header_suggested", span = { GridItemSpan(3) }) {
                    OrganizeSectionHeader(
                        text = stringResource(R.string.org_section_suggested, sections.suggested.size)
                    )
                }
                items(items = sections.suggested, key = { entry -> entry.item.uri }) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = emptySet(),
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
            if (sections.review.isNotEmpty()) {
                item(key = "header_review", span = { GridItemSpan(3) }) {
                    OrganizeSectionHeader(
                        text = stringResource(R.string.org_section_review, sections.review.size)
                    )
                }
                items(items = sections.review, key = { entry -> entry.item.uri }) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = emptySet(),
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
            if (sections.protectedItems.isNotEmpty()) {
                item(key = "header_protected", span = { GridItemSpan(3) }) {
                    OrganizeSectionHeader(
                        text = stringResource(
                            R.string.org_section_protected, sections.protectedItems.size
                        ),
                        isProtected = true,
                    )
                }
                items(items = sections.protectedItems, key = { entry -> entry.item.uri }) { entry ->
                    OrganizeThumb(
                        item = entry.item,
                        selected = entry.item.uri in state.selected,
                        protectReasons = entry.protectReasons,
                        onToggle = { onToggle(entry.item.uri) },
                    )
                }
            }
        }
    }
}

/** 三段分组标题行。 */
@Composable
private fun OrganizeSectionHeader(text: String, isProtected: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = if (isProtected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
```

import 追加 `androidx.compose.foundation.lazy.grid.GridItemSpan`。

- [ ] **Step 2: OrganizeThumb 加保护角标**

签名加 `protectReasons: Set<ProtectReason>`；protected 项左下角加角标（收藏/老照片/稀缺取首个原因的文案首字母缩写图标，v1 简化：统一一个小 Shield 图标）：

```kotlin
        if (protectReasons.isNotEmpty()) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = stringResource(R.string.org_protected_badge),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(16.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(2.dp)
            )
        }
```

import 追加 `androidx.compose.material.icons.outlined.Shield`（若该图标在依赖版本不存在，改 `Icons.Outlined.FavoriteBorder`，执行者校准其一）。

- [ ] **Step 3: 顶栏「AI 预选」开关文案保留、资源映射更新**

`organizeCategoryLabelRes`（:520-527）替换为 Task 12 Step 4 同款 v2 映射（两个文件保持同一映射——考虑把该函数上移到共享文件 `features/gallery/organize/OrganizeResources.kt` 消除重复，本 Task 一并做：DedupHomeHub 与 OrganizeCategoryScreen 同引）。

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/organize/ androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/
git commit -m "feat(organize): 详情页三段分组（建议删除/请确认/珍贵保护）+ 保护角标"
```

---

## Task 15: SwipeQueueBuilder 同源化（消费管线产出）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/swipe/SwipeQueueBuilder.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/swipe/SwipeQueueBuilderTest.kt`（若已存在则更新；不存在则新建）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/swipe/SwipeReviewViewModel.kt`（调用处适配新签名）

- [ ] **Step 1: 重写 SwipeQueueBuilder**

```kotlin
package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeCategorizer
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.OrganizeConfidence
import com.mamba.picme.domain.organize.OrganizeItem

/**
 * 手势快速整理（F2）队列构建纯函数：与整理中心类目页同一管线产出（口径同源，
 * 修复 v1 桶规则与类目页口径漂移）。废片桶只收 HIGH/MEDIUM 且非 protected 项
 * （LOW 弱信号与受保护项落 RECENT 兜底，由用户全手动决策）。
 * 桶内 captureDate 倒序；视频永不入队（LARGE_FILES 走类目页）。
 */
object SwipeQueueBuilder {

    /** 桶优先级声明顺序即出队顺序。 */
    private data class Bucket(val category: OrganizeCategory?, val reason: SwipeReason)

    private val BUCKETS = listOf(
        Bucket(OrganizeCategory.SCREEN_CONTENT, SwipeReason.SCREENSHOT),
        Bucket(OrganizeCategory.LOW_QUALITY_PHOTOS, SwipeReason.BLURRY),
        Bucket(OrganizeCategory.LOW_QUALITY_PORTRAITS, SwipeReason.LOW_QUALITY_PORTRAIT),
        Bucket(null, SwipeReason.RECENT), // 兜底桶：其余照片
    )

    fun build(items: List<OrganizeItem>, now: Long = System.currentTimeMillis()): List<SwipeCandidate> {
        val classifiedByUri = OrganizeCategorizer.classifyAll(items, now)
            .associateBy { entry -> entry.item.uri }
        val grouped = LinkedHashMap<SwipeReason, MutableList<SwipeCandidate>>()
        for (bucket in BUCKETS) grouped[bucket.reason] = mutableListOf()
        for (item in items) {
            if (item.isVideo) continue
            val entry = classifiedByUri[item.uri]
            val wasteHit = entry != null && !entry.protected &&
                entry.confidence != OrganizeConfidence.LOW
            val reason = BUCKETS.first { bucket ->
                bucket.category == null || (wasteHit && entry?.category == bucket.category)
            }.reason
            grouped.getValue(reason) += SwipeCandidate(
                uri = item.uri,
                sizeBytes = item.sizeBytes,
                captureDate = item.captureDate,
                reason = reason,
            )
        }
        return grouped.values.flatMap { bucket ->
            bucket.sortedByDescending { candidate -> candidate.captureDate }
        }
    }
}
```

> lambda 显式命名注意：`associateBy { entry -> ... }` 等已遵守。

- [ ] **Step 2: 测试**

新建/更新 `SwipeQueueBuilderTest`：

```kotlin
package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeQueueBuilderTest {

    private val now = 1_800_000_000_000L

    @Suppress("LongParameterList")
    private fun item(
        uri: String,
        isVideo: Boolean = false,
        captureDate: Long = now,
        relativePath: String? = "DCIM/Camera/",
        blurScore: Float? = null,
        isFavorite: Boolean = false,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = captureDate, sizeBytes = 1_000_000,
        relativePath = relativePath, ocrText = null, pixelArea = 12_000_000, labels = null,
        hasFace = false, aestheticScore = null, faceQualityScore = null,
        blurScore = blurScore, isFavorite = isFavorite,
    )

    @Test
    fun `buckets ordered screenshot then blurry then recent`() {
        val queue = SwipeQueueBuilder.build(
            listOf(
                item("recent"),
                item("blur", blurScore = 10.0f),
                item("shot", relativePath = "Pictures/Screenshots/"),
            ),
            now = now,
        )
        assertEquals(
            listOf("shot", "blur", "recent"),
            queue.map { candidate -> candidate.uri },
        )
    }

    @Test
    fun `protected low quality photo falls back to RECENT bucket`() {
        // 收藏过的模糊照片：类目页进保护区，手势队列同样不往废片桶塞（口径一致）
        val queue = SwipeQueueBuilder.build(
            listOf(item("favBlur", blurScore = 10.0f, isFavorite = true)),
            now = now,
        )
        assertEquals(SwipeReason.RECENT, queue.single().reason)
    }

    @Test
    fun `videos never enqueued`() {
        val queue = SwipeQueueBuilder.build(
            listOf(item("v", isVideo = true, relativePath = "Pictures/Screenshots/")),
            now = now,
        )
        assertEquals(0, queue.size)
    }
}
```

- [ ] **Step 3: 跑测试 + 适配调用方**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.swipe.SwipeQueueBuilderTest"`
Expected: 3 tests PASS。`SwipeReviewViewModel` 中 `SwipeQueueBuilder.build(...)` 调用处编译自适应（新参数有默认值），若有旧 bucket 常量引用则同步清理。

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/swipe/ androidApp/src/test/java/com/mamba/picme/domain/swipe/ androidApp/src/main/java/com/mamba/picme/features/gallery/swipe/
git commit -m "refactor(organize): SwipeQueueBuilder 与类目页同管线产出（口径同源）"
```

---

## Task 16: I18N 五语文案落地

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml`（EN）
- Modify: `androidApp/src/main/res/values-zh-rCN/strings.xml`
- Modify: `androidApp/src/main/res/values-zh-rTW/strings.xml`
- Modify: `androidApp/src/main/res/values-es/strings.xml`
- Modify: `androidApp/src/main/res/values-fr/strings.xml`

- [ ] **Step 1: 新增/替换键（五个文件同步，键名一致）**

| 键 | EN | zh-CN | zh-TW | ES | FR |
|---|---|---|---|---|---|
| `org_cat_screen_content`（替换 `org_cat_screenshots`） | Screenshots &amp; recordings | 截图与录屏 | 截圖與錄影 | Capturas y grabaciones | Captures et enregistrements |
| `org_cat_large_files`（替换 `org_cat_large_videos`） | Large files | 大文件 | 大型檔案 | Archivos grandes | Fichiers volumineux |
| `org_hero_review` | %1$d more to review | 另有 %1$d 张待确认 | 另有 %1$d 項待確認 | %1$d más por revisar | %1$d de plus à vérifier |
| `org_needs_scan` | Scan needed · tap to scan | 需先扫描 · 点击前往 | 需先掃描 · 點擊前往 | Escaneo necesario | Analyse requise |
| `org_confidence_high` | %1$d confident | %1$d 高置信 | %1$d 高信心 | %1$d fiables | %1$d fiables |
| `org_confidence_review` | %1$d to review | %1$d 待确认 | %1$d 待確認 | %1$d por revisar | %1$d à vérifier |
| `org_protected_count` | %1$d protected | %1$d 受保护 | %1$d 受保護 | %1$d protegidos | %1$d protégés |
| `org_cat_reclaim` | Free %1$s | 可释放 %1$s | 可釋放 %1$s | Libera %1$s | Libérer %1$s |
| `org_section_suggested` | Suggested (%1$d) | 建议删除（%1$d） | 建議刪除（%1$d） | Sugeridos (%1$d) | Suggérés (%1$d) |
| `org_section_review` | Review (%1$d) | 请确认（%1$d） | 請確認（%1$d） | Revisar (%1$d) | À vérifier (%1$d) |
| `org_section_protected` | Likely precious (%1$d) | 可能是珍贵照片（%1$d） | 可能是珍貴照片（%1$d） | Posiblemente valiosas (%1$d) | Probablement précieuses (%1$d) |
| `org_protected_badge` | Possibly precious photo | 可能是珍贵照片 | 可能是珍貴照片 | Foto posiblemente valiosa | Photo probablement précieuse |

- `org_cat_blurry`（"Blurry & low quality"）保留复用给 LOW_QUALITY_PHOTOS，文案不变。
- 删除旧键 `org_cat_screenshots`、`org_cat_large_videos`（五个文件都删）。

- [ ] **Step 2: 键完整性校验**

Run: `for k in org_cat_screen_content org_cat_large_files org_hero_review org_needs_scan org_confidence_high org_confidence_review org_protected_count org_cat_reclaim org_section_suggested org_section_review org_section_protected org_protected_badge; do echo "== $k"; grep -l "name=\"$k\"" androidApp/src/main/res/values/strings.xml androidApp/src/main/res/values-zh-rCN/strings.xml androidApp/src/main/res/values-zh-rTW/strings.xml androidApp/src/main/res/values-es/strings.xml androidApp/src/main/res/values-fr/strings.xml | wc -l; done`
Expected: 每键输出 5（五文件全覆盖）

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/res/
git commit -m "feat(organize): v2 类目/置信度/保护文案五语同步"
```

---

## Task 17: 枚举改名全量收口 + 编译与单测全绿

**Files:** 所有残留旧枚举引用

- [ ] **Step 1: 全局搜索旧枚举名**

Run: `grep -rn "OrganizeCategory.SCREENSHOTS\|OrganizeCategory.BLURRY\|OrganizeCategory.LARGE_VIDEOS\|CategoryStat\|categoriesOf\|OrganizeCategorizer.stats" androidApp/src/`
Expected: 零命中（有命中则逐个改为 v2 API；重点复查 `MainActivity.kt` 的 `organize_category/{category}` 路由解析处 `OrganizeCategory.valueOf` 逻辑——枚举名即路由段，改名后非法值弹栈兜底应已存在，确认即可）

- [ ] **Step 2: 全量编译**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 全量 JVM 单测**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS（含既有 DedupViewModel/SwipeReviewViewModel 等测试；若有因签名变化失败的旧测试，按新语义修正断言，不要删除测试）

- [ ] **Step 4: Commit**

```bash
git add -A androidApp/src
git commit -m "refactor(organize): v1 枚举/API 残留收口，编译与单测全绿"
```

---

## Task 18: 文档同步 + UI spec 固化 + 闭环验证

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md` §2.4（整理中心条目）
- Create: `docs/08-UI-SPECS/screens/organize.yaml`
- Modify: `docs/01-PRODUCT/FEATURES.md`（整理中心相关段落）
- Modify: spec 头部状态 `docs/superpowers/specs/2026-09-06-organize-category-redesign-design.md`（状态改「已落地」，追加落地校准注记）

- [ ] **Step 1: gallery AGENTS.md §2.4 更新**

「整理中心类目详情（F1）」条目改写为 v2 口径：四层管线（CategoryArbiter 互斥裁定 → ValueGuard 价值保护 → ConfidenceGrader 置信分级，`domain/organize/`）、类目枚举 v2 六类互斥、Hero = HIGH 非保护并集、详情页三段分组、引导态、SwipeQueueBuilder 同源。遵循「模块级细节下沉」原则，不写进顶层 AGENTS.md。

- [ ] **Step 2: 固化 organize.yaml**

参照 `docs/08-UI-SPECS/screens/chat.yaml` 的既有格式（先读它学习结构）创建 `organize.yaml`，覆盖四屏：hub（Hero + 类目卡 v2 + 引导态）、category_grid（三段分组）、cleaned（沿用）、swipe/review 口径说明。这是 [PARITY] 红线要求，iOS 后续翻译以此为 SSOT。

- [ ] **Step 3: FEATURES.md 与 spec 状态更新**

FEATURES.md 中整理中心描述同步为 v2 类目与置信度语义；spec 头部状态改为「已落地」，追加落地校准注记（实际阈值、与设计的偏差）。

- [ ] **Step 4: 闭环验证（编译 → 安装 → 截屏）**

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/*.apk
adb shell am start -n com.mamba.picme/.MainActivity
# 导航到整理页（Pager 页 1）截屏
adb shell input swipe 900 1200 100 1200
adb shell screencap -p /sdcard/organize_v2.png && adb pull /sdcard/organize_v2.png tmp/
```

检查截图：Hero 真实口径、类目卡置信度徽标行、引导态（若未扫描）。按 `skills/dev-loop` 闭环。

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md docs/08-UI-SPECS/screens/organize.yaml docs/01-PRODUCT/FEATURES.md docs/superpowers/specs/2026-09-06-organize-category-redesign-design.md
git commit -m "docs(organize): v2 落地文档同步 + organize.yaml UI spec 固化"
```

---

## 验收对账（spec §10 AC 映射）

| AC | 由哪个 Task 保证 |
|---|---|
| AC-R2-1 互斥 + Hero 并集 | Task 3（裁定）+ Task 7（board 测试 `no double count regression`） |
| AC-R2-2 截图不误判 | Task 3 测试 `screenshot path classified SCREEN_CONTENT and never blurry` |
| AC-R2-3 价值保护 | Task 4 测试 + Task 7 测试 `protected items excluded from hero` |
| AC-R2-4 引导态 | Task 7 `coverageOf` + Task 12 Step 3 |
| AC-R2-5 置信度三段 | Task 5 + Task 13/14 |
| AC-R2-6 性能 | Task 9（补算后台分批，首屏不等）+ Task 18 真机验证 |
| AC-R2-7 Swipe 口径一致 | Task 15 |
| AC-R2-8 i18n/PARITY | Task 16 + Task 18 Step 2 |

## 执行顺序与依赖

**与 spec 的两处有意偏差（执行者须知，不算违规）：**
1. spec §3 文件表中的 `SignalCollector.kt` 不单列——信号采集职责由 `OrganizeRepositoryImpl.mergeRows`（信号合并）+ `DuplicateGrouper`（重复组）承担，管线第一层无独立文件（YAGNI：采集逻辑与数据源强耦合，抽层无测试收益）。
2. spec §6.4 的「置信度/保护语义色走 design-tokens.json codegen」本期降级为 Compose `colorScheme`（primary/tertiary/error）直用——新增 token 需双端 codegen 同步，收益不匹配本期范围；若后续 iOS 翻译需要，再走 token 新增流程。

```
Task 1（Room v22）→ Task 2（模型）→ Task 3~8（领域管线，可并行开发但按序提交）
→ Task 9（仓储）→ Task 10（查看回写）→ Task 11（VM 数据流）
→ Task 12（hub UI）→ Task 13（详情 VM）→ Task 14（详情 UI）→ Task 15（Swipe 同源）
→ Task 16（i18n）→ Task 17（收口全绿）→ Task 18（文档+闭环）
```

Task 7 与 Task 10-15 之间存在有意的中间编译红态（旧 API 删除早于调用方切换），按 Task 7 Step 4 注记处理：不要为中间态返工，推进到 Task 17 统一收绿。
