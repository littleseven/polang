# Agent 清理 M1（预分析基础设施 + 存储叙事 + 去重扫描收编）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec M1——`relativePath`/`sizeBytes` 落库（Room v29）、App 来源映射、存储空间叙事（hub 呈现）、去重扫描服务化（FGS + 会话单例）并收编为 `DEDUP_SCAN` 用户任务、后台预分析调度（充电+闲置/每日闲时/手动）。

**Architecture:** 领域逻辑全部落 `domain/` 纯 Kotlin（JVM 可测）：`AppSource`、`StorageStory`、`PreAnalysisConditions`、用户任务状态投影；去重扫描执行抽为 `DedupScanSessionManager`（仿 `TagScanOrchestrator` 轻量版，无 DB 会话行——进程死亡靠 `dedup_hash` 缓存重启），FGS 包装 `DedupScanService`（仿 `TagGenerationService`），UI 经 intent 控制面（仿 `StartTagScanUseCase`）观察服务 companion 流；`DedupViewModel` 从「自驱扫描」改为「观察会话」。

**Tech Stack:** Kotlin / Jetpack Compose / Room / StateFlow / AlarmManager / BroadcastReceiver。不引入 WorkManager。

**Spec:** `docs/superpowers/specs/2026-11-22-agent-cleanup-design.md`（§1~§9、§11、§12 M1、§15 D1~D8）

**工作区:** worktree `.worktrees/agent-cleanup-m1`，分支 `feat/agent-cleanup-m1`（自 main `3cca78ac7`）。以下所有路径相对 worktree 根。

**范围声明：** M2（Agent 对话闭环：CleanupPlan/方案卡/审批页/CLEANUP_EXECUTE/回联）与 M3（来源调校/空间预测/阈值设置化）不在本计划。快照表不做——账本经 `observeItems()` 活算（board 已同路径），「新鲜度」用时间戳表达（spec §7 意图保留、机制简化）。

## Global Constraints

- Kotlin 4 空格缩进；lambda 参数禁止 `it`（显式命名）；日志标签 `PoLang:[Feature]`；禁止完全限定名与通配符 import。
- [I18N]：用户可见字符串五语同步 `values/`（EN）+ `values-zh-rCN/` + `values-zh-rTW/` + `values-es/` + `values-fr/`。
- [PRIVACY]：全部端侧，无新增网络面。
- [AGENT-FIRST]：领域逻辑纯函数/枚举穷尽；UI 零业务逻辑。
- 过期截图阈值 M1 = 常量 365 天（spec §15，设置化属 M3）。
- 通知渠道新辟 `picme_dedup_scan`，id `10044`（`10042` 下载 / `10043` TAG 已占）；FGS type `dataSync`。
- shared 侧枚举变更有 SKIE 透出（iOS 消费同一 commonMain），只加值不改名。
- JVM 单测：`./gradlew :androidApp:testDebugUnitTest --tests "全限定类名"`；shared 侧：`./gradlew :shared:testDebugUnitTest --tests "..."`。

## Review Focus

1. API < 29 无 `RELATIVE_PATH` 列 → 映射必须走 `DATA` 绝对路径兜底（含中文 OEM 目录如 `截屏`）——test in Task 2。
2. `sizeBytes = 0`（未知大小）与空账本 → 叙事聚合不产生除零/负数/崩溃——test in Task 3。
3. 进程死于去重扫描中 → 适配器启动对账置 `FAILED(PROCESS_TERMINATED)` + {RETRY}——test in Task 5。
4. 预分析触发时 TAG 扫描正在跑（I/O 竞争）→ 条件函数含 tag-scan-active 否决——test in Task 6。
5. 去重服务已有一份扫描在跑时再次收到 start（hub 手动 + 预分析撞车）→ 会话管理器重入守卫不重启扫描——test in Task 4。

---

## Task 1: Room v29——media_assets 加 relativePath/sizeBytes + 回填

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`（version 28→29 + MIGRATION_28_29 + addMigrations 注册）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/model/MediaEntity.kt`（两列）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/MediaDao.kt`（OrganizeRow 投影 + 回填查询/写入）
- Modify: `androidApp/src/main/java/com/mamba/picme/data/repository/MediaRepositoryImpl.kt`（同步插入路径带两列：queryImages/VideosFromMediaStore 投影扩展 + toEntity）
- Create: `androidApp/src/main/java/com/mamba/picme/data/repository/MediaMetadataBackfiller.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/repository/OrganizeRepositoryImpl.kt`（优先 Room 列，MediaStore meta 兜底）
- Modify: `androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt`（触发回填，LocationIndexer 先例 :301 附近）
- Test: `androidApp/src/test/java/com/mamba/picme/data/repository/MediaMetadataBackfillerTest.kt`（新建）

**Interfaces:**
- Consumes: 既有 `OrganizeRepositoryImpl.queryCollectionMeta` 的 MediaStore 列集（`_ID/SIZE/WIDTH/HEIGHT/DATA` + Q+ `RELATIVE_PATH/IS_FAVORITE`，:171-220）。
- Produces: `MediaEntity.relativePath: String?`、`MediaEntity.sizeBytes: Long?`；`MediaMetadataBackfiller.runUntilDone(batchLimit: Int = 500): Int`（返回补写行数）；`OrganizeRow` 投影新增 `relativePath`/`sizeBytes`。

- [ ] **Step 1: 写失败测试**

`MediaMetadataBackfillerTest.kt`：回填器核心是可测纯逻辑——「Room 行缺元数据 + MediaStore meta map → 待写行集」。仿 `LocationIndexer` 模式：backfiller 注入 `mediaDao` 与 `metaProvider: suspend (List<String>) -> Map<String, Pair<String?, Long>>`（uri → path/size），测试用 fake：

```kotlin
@Test
fun `runUntilDone fills only rows missing metadata and stops when no candidates`() = runTest {
    val writes = mutableListOf<Pair<String, Pair<String?, Long>>>()
    val backfiller = MediaMetadataBackfiller(
        candidateUris = { limit -> listOf("uri://a", "uri://b") },
        metaProvider = { uris -> uris.associateWith { u -> "DCIM/Camera" to 1024L } },
        writer = { uri, meta -> writes.add(uri to meta) },
    )
    assertEquals(2, backfiller.runUntilDone(batchLimit = 500))
    assertEquals(listOf("uri://a", "uri://b"), writes.map { w -> w.first })
}

@Test
fun `meta missing from provider leaves row untouched but counts as attempted`() = runTest {
    val writes = mutableListOf<Pair<String, Pair<String?, Long>>>()
    val backfiller = MediaMetadataBackfiller(
        candidateUris = { listOf("uri://gone") },
        metaProvider = { emptyMap() },
        writer = { uri, meta -> writes.add(uri to meta) },
    )
    assertEquals(0, backfiller.runUntilDone())
    assertTrue(writes.isEmpty())
}
```

（构造签名以实现时定的接口为准——注入三函数是为了 JVM 可测；语义不变式：只补 null 行、provider 缺失不写。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.data.repository.MediaMetadataBackfillerTest"`
Expected: 编译失败（`MediaMetadataBackfiller` 不存在）

- [ ] **Step 3: Migration + Entity + DAO**

- `AppDatabase.kt`：`version = 29`；companion 加 `MIGRATION_28_29`（仿 `MIGRATION_21_22` 纯 ALTER）：
  ```sql
  ALTER TABLE media_assets ADD COLUMN relativePath TEXT
  ALTER TABLE media_assets ADD COLUMN sizeBytes INTEGER
  ```
  `addMigrations(...)` 末尾追加 `MIGRATION_28_29`。
- `MediaEntity.kt`：加 `val relativePath: String? = null`、`val sizeBytes: Long? = null`。
- `MediaDao.kt`：`OrganizeRow`（:754-767）加两字段；`observeOrganizeRows`（:472-476）投影加两列。新增：
  ```kotlin
  @Query("SELECT uri FROM media_assets WHERE relativePath IS NULL LIMIT :limit")
  suspend fun getMediaNeedingMetaBackfill(limit: Int): List<String>

  @Query("UPDATE media_assets SET relativePath = :path, sizeBytes = :size WHERE uri = :uri")
  suspend fun updateMediaMeta(uri: String, path: String?, size: Long)
  ```

- [ ] **Step 4: 同步插入路径 + 回填器 + 兜底选择**

- `MediaRepositoryImpl.kt`：`queryImagesFromMediaStore`/`queryVideosFromMediaStore`（:420-521）投影加 `SIZE` 与 API 29+ gate 的 `RELATIVE_PATH`（低 API 用 `DATA`），`MediaAsset`/`toEntity()`（:658-679）穿透两字段。
- `MediaMetadataBackfiller.kt`：三函数注入（见 Step 1），生产装配在 `PoLangApplication`：`candidateUris = mediaDao::getMediaNeedingMetaBackfill`，`metaProvider` = 一次性 MediaStore 批查（复用 `OrganizeRepositoryImpl` 的列集思路，按 uri 建 map），`writer = mediaDao::updateMediaMeta`；Application onCreate 在 `LocationIndexer` 触发点旁启动（IO 协程，异常降级日志）。
- `OrganizeRepositoryImpl.mergeRows`（:63-96）：`relativePath`/`sizeBytes` 取值改为「Room 行非 null 优先，null 回退现有 `queryMediaStoreMeta` 结果」——`MediaMeta` 查询保留（还承载 `isFavorite`/pixelArea），只是 path/size 优先用 Room。

- [ ] **Step 5: 跑测试 + 回归**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.data.repository.*" --tests "com.mamba.picme.domain.organize.*"`
Expected: 新测试 PASS，既有全绿

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/ androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt androidApp/src/test/java/com/mamba/picme/data/
git commit -m "feat(data): media_assets 持久化 relativePath/sizeBytes（Room v29 + 回填 + organize 兜底优先 Room）"
```

---

## Task 2: AppSource 来源映射（domain 纯函数）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/AppSource.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/AppSourceTest.kt`（新建）

**Interfaces:**
- Produces: `enum class AppSource { CAMERA, WECHAT, QQ, WHATSAPP, TELEGRAM, INSTAGRAM, MESSENGER, DOWNLOAD, SCREENSHOTS, OTHER }`；`fun appSourceOf(relativePath: String?): AppSource`；`val AppSource.defaultTier: SourceTier`，`enum class SourceTier { PROTECTED, LOW_VALUE, NEUTRAL }`（CAMERA→PROTECTED；WECHAT/QQ/WHATSAPP/TELEGRAM/INSTAGRAM/MESSENGER→LOW_VALUE；其余→NEUTRAL）。

- [ ] **Step 1: 写失败测试**

`AppSourceTest.kt`（输入统一 upper-case 后 contains 匹配；大小写不敏感）：

```kotlin
@Test fun `camera dcim is protected`() {
    assertEquals(AppSource.CAMERA, appSourceOf("DCIM/Camera"))
    assertEquals(SourceTier.PROTECTED, AppSource.CAMERA.defaultTier)
}
@Test fun `wechat dirs both legacy and scoped storage`() {
    assertEquals(AppSource.WECHAT, appSourceOf("Pictures/WeChat"))
    assertEquals(AppSource.WECHAT, appSourceOf("Android/media/com.tencent.mm/MicroMsg/WeiXin"))
}
@Test fun `overseas social apps`() {
    assertEquals(AppSource.WHATSAPP, appSourceOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"))
    assertEquals(AppSource.TELEGRAM, appSourceOf("Pictures/Telegram"))
    assertEquals(AppSource.INSTAGRAM, appSourceOf("Pictures/Instagram"))
}
@Test fun `screenshots including chinese oem dir`() {
    assertEquals(AppSource.SCREENSHOTS, appSourceOf("Pictures/Screenshots"))
    assertEquals(AppSource.SCREENSHOTS, appSourceOf("DCIM/Screenshots"))
    assertEquals(AppSource.SCREENSHOTS, appSourceOf("Pictures/截屏"))
}
@Test fun `download and fallback`() {
    assertEquals(AppSource.DOWNLOAD, appSourceOf("Download"))
    assertEquals(AppSource.OTHER, appSourceOf("Pictures/UnknownApp"))
    assertEquals(AppSource.OTHER, appSourceOf(null))
}
@Test fun `absolute data path fallback (API under 29)`() {
    assertEquals(AppSource.WECHAT, appSourceOf("/storage/emulated/0/Pictures/WeChat/img.jpg"))
    assertEquals(AppSource.CAMERA, appSourceOf("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.AppSourceTest"`
Expected: 编译失败

- [ ] **Step 3: 实现 AppSource**

`appSourceOf`：取路径 uppercase；按序 contains 匹配——`SCREENSHOTS`/`截屏`→SCREENSHOTS；`DCIM`（且非 Screenshots 子目录）→CAMERA；`COM.TENCENT.MM` 或 `WECHAT` 或 `MICROMSG`→WECHAT；`COM.TENCENT.MOBILEQQ` 或 `QQ` →QQ；`COM.WHATSAPP`→WHATSAPP；`TELEGRAM`→TELEGRAM；`INSTAGRAM`→INSTAGRAM；`COM.FACEBOOK.ORCA` 或 `MESSENGER`→MESSENGER；`DOWNLOAD`→DOWNLOAD；否则 OTHER。匹配顺序即裁定优先级（截图先于 DCIM）。

- [ ] **Step 4: 跑测试确认通过**

Run: 同上；Expected: 6 用例 PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/AppSource.kt androidApp/src/test/java/com/mamba/picme/domain/organize/AppSourceTest.kt
git commit -m "feat(organize): AppSource 来源映射纯函数（默认重要度档）"
```

---

## Task 3: StorageStory 叙事聚合（domain 纯函数）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/StorageStory.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/StorageStoryTest.kt`（新建）

**Interfaces:**
- Consumes: `OrganizeItem`、`OrganizeBoard`/`CategoryBoard`（`OrganizeModels.kt`）、`appSourceOf`（Task 2）。
- Produces:
  ```kotlin
  data class SourceLedger(val source: AppSource, val count: Int, val bytes: Long)
  data class StorageStory(
      val totalBytes: Long, val photoBytes: Long, val videoBytes: Long,
      val screenshotBytes: Long,                       // 全部截图（含录屏）
      val staleScreenshotCount: Int, val staleScreenshotBytes: Long,  // ≥365 天、仅照片
      val sourceLedgers: List<SourceLedger>,           // bytes 降序，OTHER 并卷
      val reclaimBytes: Long,                          // Agent 面口径：hero - 低质量两类 highBytes
  )
  object StorageStory {
      const val STALE_SCREENSHOT_AGE_MS: Long = 365L * 24 * 3600 * 1000
      fun of(items: List<OrganizeItem>, board: OrganizeBoard, now: Long): StorageStory
  }
  ```

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `ledger buckets by media kind and source`() {
    val items = listOf(
        item("a", isVideo = false, size = 100, path = "DCIM/Camera"),
        item("b", isVideo = true, size = 900, path = "DCIM/Camera"),
        item("c", isVideo = false, size = 200, path = "Pictures/WeChat"),
        item("d", isVideo = false, size = 50, path = "Pictures/Screenshots"),
    )
    val story = StorageStory.of(items, emptyBoard(), now = NOW)
    assertEquals(1250L, story.totalBytes)
    assertEquals(350L, story.photoBytes)
    assertEquals(900L, story.videoBytes)
    assertEquals(50L, story.screenshotBytes)
    assertEquals(
        listOf(
            SourceLedger(AppSource.CAMERA, 2, 1000L),
            SourceLedger(AppSource.WECHAT, 1, 200L),
            SourceLedger(AppSource.SCREENSHOTS, 1, 50L),
        ),
        story.sourceLedgers,
    )
}

@Test fun `stale screenshots count photos older than 365 days only`() {
    val old = NOW - 400L * 24 * 3600 * 1000
    val items = listOf(
        item("old-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = old),
        item("new-shot", isVideo = false, size = 10, path = "Pictures/Screenshots", captureDate = NOW),
        item("old-video", isVideo = true, size = 10, path = "Pictures/Screenshots", captureDate = old),
    )
    val story = StorageStory.of(items, emptyBoard(), now = NOW)
    assertEquals(1, story.staleScreenshotCount)
    assertEquals(10L, story.staleScreenshotBytes)
}

@Test fun `reclaim excludes both low quality categories from hero`() {
    val board = OrganizeBoard(
        categories = listOf(
            card(OrganizeCategory.DUPLICATES, highBytes = 500),
            card(OrganizeCategory.LOW_QUALITY_PHOTOS, highBytes = 300),
            card(OrganizeCategory.LOW_QUALITY_PORTRAITS, highBytes = 200),
        ),
        heroReclaimBytes = 1000L, heroReviewCount = 0,
    )
    assertEquals(500L, StorageStory.of(emptyList(), board, NOW).reclaimBytes)
}

@Test fun `zero size and empty inputs do not crash`() {
    val story = StorageStory.of(listOf(item("x", size = 0, path = null)), emptyBoard(), NOW)
    assertEquals(0L, story.totalBytes)
    assertEquals(0L, story.reclaimBytes)
}
```

（`item(...)`/`emptyBoard()`/`card(...)` 测试工厂按 `OrganizeItem`/`CategoryBoard` 实际签名写全具名参数；`NOW` 为常量。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.StorageStoryTest"`
Expected: 编译失败

- [ ] **Step 3: 实现 StorageStory**

聚合规则：`totalBytes = sum(sizeBytes)`；`videoBytes = isVideo 合计`；`photoBytes = 非 video 合计`；`screenshotBytes = appSourceOf==SCREENSHOTS 合计`；stale = SCREENSHOTS ∧ 非 video ∧ `captureDate > 0 && now - captureDate >= STALE_SCREENSHOT_AGE_MS`；`sourceLedgers = groupBy(appSourceOf).map{count,bytes} 按 bytes 降序`（不过滤 CAMERA——账本呈现「相机占多少」也是叙事）；`reclaimBytes = board.heroReclaimBytes - board.categories.filter{低质量两类}.sumOf{highBytes}`，下限 coerceAtLeast(0)。

- [ ] **Step 4: 跑测试确认通过**

Run: 同上；Expected: 4 用例 PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/StorageStory.kt androidApp/src/test/java/com/mamba/picme/domain/organize/StorageStoryTest.kt
git commit -m "feat(organize): StorageStory 叙事聚合纯函数（总账/来源账/过期截图/Agent 面可释放口径）"
```

---

## Task 4: 去重扫描服务化（DedupScanService FGS + 会话单例 + VM 观察化）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/dedup/DedupScanSession.kt`（进度模型 + 会话接口）
- Create: `androidApp/src/main/java/com/mamba/picme/service/dedup/DedupScanSessionManager.kt`（执行核心）
- Create: `androidApp/src/main/java/com/mamba/picme/service/dedup/DedupScanService.kt`（FGS 包装）
- Create: `androidApp/src/main/java/com/mamba/picme/service/dedup/ServiceBackedDedupScanSession.kt`（intent 控制面，仿 `StartTagScanUseCase`）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupViewModel.kt`（观察会话替代自驱扫描）
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（session 接缝 + 工厂签名）
- Modify: `androidApp/src/main/AndroidManifest.xml`（service 声明）
- Modify: `androidApp/src/main/java/com/mamba/picme/MainActivity.kt`（DedupViewModel 工厂调用适配）
- Test: `androidApp/src/test/java/com/mamba/picme/service/dedup/DedupScanSessionManagerTest.kt`（新建）
- Modify: `androidApp/src/test/java/com/mamba/picme/features/gallery/dedup/DedupViewModelTest.kt`（fake seam 适配）
- Modify: 五个语言目录 strings.xml（通知渠道名 + 通知文案）

**Interfaces:**
- Consumes: `DedupScanner`（`AppContainer.dedupScanner` 已有单例）、`DedupMediaSource`（`MediaStoreDedupMediaSource`）、`DedupScanEvent`（domain/dedup/DedupScanEvent.kt）。
- Produces:
  ```kotlin
  enum class DedupScanState { RUNNING, PAUSED, COMPLETED, CANCELLED }
  data class DedupScanProgress(
      val state: DedupScanState,
      val phase: DedupLevel, val phaseIndex: Int, val phaseCount: Int,
      val scanned: Int, val total: Int,
      val foundGroups: List<DedupGroup>,
      val resultGroups: List<DedupGroup>?,   // COMPLETED 时非空
  )
  interface DedupScanSession {               // VM/适配器消费的接缝（JVM fake 友好）
      val progress: StateFlow<DedupScanProgress?>
      fun startScan(config: DedupScanConfig)
      fun pauseScan(); fun resumeScan(); fun cancelScan()
  }
  ```
  `DedupScanService` companion：`val sessionProgress: MutableStateFlow<DedupScanProgress?>` + action 常量 `ACTION_START/PAUSE/RESUME/CANCEL` + `fun intent*(context): Intent`。

- [ ] **Step 1: 写失败测试（SessionManager）**

`DedupScanSessionManagerTest.kt`：manager 注入 `scanner: DedupScanController`、`mediaSource: DedupMediaSource`、`scope`、`ioDispatcher`，测试用 fake scanner（`scan()` 回 `flow { emit(Progress…); emit(Done(groups)) }`）：

```kotlin
@Test fun `start emits running then completed with result groups`() = runTest { … }
@Test fun `reentrant start while running is ignored`() = runTest {
    // Review Focus #5：RUNNING/PAUSED 态再调 startScan → 不重启（第二次调用时 fake scanner 的 scan 调用计数仍为 1）
}
@Test fun `cancel maps to cancelled state and clears progress`() = runTest { … }
@Test fun `pause then resume flips state and forwards to scanner`() = runTest { … }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.service.dedup.DedupScanSessionManagerTest"`
Expected: 编译失败

- [ ] **Step 3: 实现会话模型 + Manager**

`DedupScanSession.kt`：模型 + 接口（签名如上）。`DedupScanSessionManager(context? 不需要—纯 Kotlin, scanner, mediaSource, scope, ioDispatcher)`：
- `private val _progress = MutableStateFlow<DedupScanProgress?>(null)`；`startScan` 重入守卫（当前 state RUNNING/PAUSED 直接 return）；`scanJob = scope.launch { val items = mediaSource.photoScanItems(); scanner.scan(items, config).flowOn(ioDispatcher).collect { event -> ... } }`，事件映射：Progress→`RUNNING(scanned,total)`、PhaseChanged→更新 phase 三元组、GroupFound→prepend foundGroups、Done→`COMPLETED(resultGroups=groups)`、Cancelled→`CANCELLED`。
- `pauseScan()` → `scanner.pauseRequested = true` + state=PAUSED；`resumeScan()` → `scanner.resume()` + state=RUNNING；`cancelScan()` → `scanner.resume()` + `scanJob?.cancel()` + 置 `CANCELLED` 后 `_progress.value = null`（终态清空，与「IDLE 无任务」语义对齐）。

- [ ] **Step 4: FGS 服务 + intent 控制面**

`DedupScanService.kt`：仿 `TagGenerationService` 精简版——
- companion：`CHANNEL_ID = "picme_dedup_scan"`、`NOTIFICATION_ID = 10044`、`sessionProgress` MutableStateFlow、`@Volatile managerRef`、action 常量与 `intentStart(context, config)`/`intentPause/Resume/Cancel`。
- `onCreate`：建渠道；从 `(application as PoLangApplication).container` 取 `dedupScanner` 与 `dedupMediaSource`（容器加这两个的公开 getter——已有）构造 `DedupScanSessionManager(serviceScope, Dispatchers.IO)`；`serviceScope.launch { manager.progress.collect { p -> sessionProgress.value = p; updateNotification(p); if (p == null || p.state 终态) 考虑 stopSelf 延时 } }`。
- `onStartCommand`：`enterForegroundSafely()`（仿 TAG：34+ `FOREGROUND_SERVICE_TYPE_DATA_SYNC`，`ForegroundServiceStartNotAllowedException` → stopSelf 降级日志）→ action 分发 manager。`START_STICKY`。
- 扫描持 `PARTIAL_WAKE_LOCK`（仿 `TagScanOrchestrator`），COMPLETED/CANCELLED 释放。
- 通知：进度条 `scanned/total`，标题五语；`contentIntent` → MainActivity。
- `ServiceBackedDedupScanSession(context)`：`progress = DedupScanService.sessionProgress`；`startScan` → `ContextCompat.startForegroundService(intentStart(...))`；pause/resume/cancel → `context.startService(intent*)`。

Manifest：`<service android:name=".service.dedup.DedupScanService" android:exported="false" android:foregroundServiceType="dataSync"/>`（权限 `FOREGROUND_SERVICE_DATA_SYNC` 已有）。

- [ ] **Step 5: DedupViewModel 观察化**

- 构造签名：`scanner: DedupScanController` 参数换成 `session: DedupScanSession`（`mediaSource` 参数删除——取数职责上移 manager）；`AppContainer.createDedupViewModelFactory` 内部改为注入 `ServiceBackedDedupScanSession(context)`，`DedupViewModelFactory`（AppContainer.kt:206-228）同步。
- `startScan(config)` → `session.startScan(config)`（uiState 不再自置 Scanning，等会话流回推）；`init` 增加 `scope.launch { session.progress.collect { p -> onSessionProgress(p) } }`：
  - `RUNNING/PAUSED` → `DedupUiState.Scanning(phase…, scanned, total, paused = state==PAUSED, foundGroups)`
  - `COMPLETED` → `Results(resultGroups.orEmpty(), …)`（沿用既有排序/选 Tab 逻辑 :207 附近的 Done 分支）
  - `CANCELLED`/null → 若当前为 Scanning → Config
- `pauseScan/resumeScan/cancelScan` 改转发 session；`onCleared` 不再 resume scanner（会话归服务所有）。
- `DedupViewModelTest`：`scanner` fake 换成 `DedupScanSession` fake（`MutableStateFlow<DedupScanProgress?>` + 调用计数）；扫描相关用例改为「推会话进度 → 断言 uiState 迁移」；非扫描用例（backfill/删除/规则）不动。

- [ ] **Step 6: 五语字符串**

新增（通知渠道与通知文案）：
`values/strings.xml`：`dedup_scan_channel_name`=“Photo cleanup scan”、`dedup_scan_notification_title`=“Scanning for duplicates”、`dedup_scan_notification_text`=“%1$d/%2$d scanned”；
zh-CN：“相册整理扫描”/“正在扫描重复照片”/“已扫描 %1$d/%2$d”；zh-TW：“相簿整理掃描”/“正在掃描重複照片”/“已掃描 %1$d/%2$d”；es：“Análisis de limpieza”/“Buscando duplicados”/“%1$d/%2$d analizados”；fr：“Analyse de nettoyage”/“Recherche de doublons”/“%1$d/%2$d analysés”。

- [ ] **Step 7: 编译 + 全部相关测试**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --tests "com.mamba.picme.service.dedup.*" --tests "com.mamba.picme.features.gallery.dedup.*" --tests "com.mamba.picme.domain.dedup.*"`
Expected: BUILD SUCCESSFUL，全部 PASS

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/dedup/DedupScanSession.kt \
        androidApp/src/main/java/com/mamba/picme/service/dedup/ \
        androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/ \
        androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt \
        androidApp/src/main/java/com/mamba/picme/MainActivity.kt \
        androidApp/src/main/AndroidManifest.xml \
        androidApp/src/main/res/values*/strings.xml \
        androidApp/src/test/java/com/mamba/picme/
git commit -m "feat(dedup): 扫描服务化——DedupScanService FGS + 会话单例 + DedupViewModel 观察化"
```

---

## Task 5: DEDUP_SCAN 用户任务收编

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/usertask/UserTask.kt`（kind/destination 加值）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/domain/usertask/UserTaskMapping.kt`（`destinationFor` 穷尽分支）
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/usertask/UserTaskMapping.kt`（`fromDedupScanState` 投影扩展）
- Create: `androidApp/src/main/java/com/mamba/picme/domain/usertask/DedupScanTaskAdapter.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（`startUserTaskAdapters` 注册）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/UserTaskCard.kt`（title/icon 穷尽分支）
- Modify: `androidApp/src/main/java/com/mamba/picme/MainActivity.kt`（destination 穷尽分支 :863-874）
- Modify: 五个语言目录 strings.xml（`user_task_title_dedup_scan`）
- Test: `androidApp/src/test/java/com/mamba/picme/domain/usertask/DedupScanTaskAdapterTest.kt`（新建）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/domain/usertask/`（destinationFor 分支断言，若已有测试文件则追加）

**Interfaces:**
- Consumes: `DedupScanSession`/`DedupScanProgress`（Task 4）、`UserTaskRegistry`（shared）、适配器先例 `TagScanTaskAdapter`。
- Produces: `UserTaskKind.DEDUP_SCAN`、`UserTaskDestination.ORGANIZE_HUB`、`fun UserTaskMapping.fromDedupScanState(state: DedupScanState?): UserTaskStatus?`；任务 id 固定 `dedupscan:main`。

- [ ] **Step 1: 写失败测试（投影 + 适配器）**

`DedupScanTaskAdapterTest.kt`（仿 `TagScanTaskAdapter` 结构：注入 `progressFlow: Flow<DedupScanProgress?>`、fake registry、fake control）：

```kotlin
@Test fun `running progress upserts RUNNING with progress snapshot`() = runTest { … }
@Test fun `completed upserts COMPLETED and clears progress`() = runTest { … }
@Test fun `first null emission with active registry row reconciles to FAILED process terminated`() = runTest {
    // Review Focus #3：registry 预置 RUNNING 行 + 首帧 null → FAILED(PROCESS_TERMINATED)，动作集 {RETRY}
}
@Test fun `perform maps verbs to control actions`() = runTest {
    // PAUSE→"pause" RESUME→"resume" CANCEL→"cancel" RETRY→"start"
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.DedupScanTaskAdapterTest"`
Expected: 编译失败

- [ ] **Step 3: shared 枚举 + 映射**

`UserTask.kt`：`UserTaskKind` 加 `DEDUP_SCAN`；`UserTaskDestination` 加 `ORGANIZE_HUB`。`UserTaskMapping.destinationFor` 加 `DEDUP_SCAN -> UserTaskDestination.ORGANIZE_HUB`。Android 侧 `UserTaskMapping.kt` 加：
```kotlin
fun UserTaskMapping.fromDedupScanState(state: DedupScanState?): UserTaskStatus? = when (state) {
    DedupScanState.RUNNING -> UserTaskStatus.RUNNING
    DedupScanState.PAUSED -> UserTaskStatus.PAUSED
    DedupScanState.COMPLETED -> UserTaskStatus.COMPLETED
    DedupScanState.CANCELLED -> UserTaskStatus.CANCELLED
    null -> null
}
```

- [ ] **Step 4: 适配器 + 注册**

`DedupScanTaskAdapter(registry, control: DedupScanControl, scope, progressFlow: Flow<DedupScanProgress?> = DedupScanService.sessionProgress)`：`fun interface DedupScanControl { suspend operator fun invoke(action: String) }`（`"start"/"pause"/"resume"/"cancel"`）；`start()` 收集流映射 upsert（固定 id `dedupscan:main`，displayName=null）+ 首帧 null 对账（同 `TagScanTaskAdapter.sync` :47-94 语义）；`perform` 动词映射（RETRY→"start"）。`AppContainer.startUserTaskAdapters()` 内注册：control 实现 = 经 `ContextCompat.startForegroundService/startService` 发 `DedupScanService` intent。

- [ ] **Step 5: 卡片与导航穷尽分支 + 五语**

`UserTaskCard.kt`：title `when` 加 `DEDUP_SCAN -> R.string.user_task_title_dedup_scan`；`kindIcon` 加分支（`Icons.Rounded.CleaningServices`——与底栏整理项图标一致）。`MainActivity.kt:863-874` destination `when` 加 `ORGANIZE_HUB -> { organizeTabRequest = OrganizeTab.ORGANIZE; switchMainPage(MAIN_PAGE_DEDUP) }`。五语 `user_task_title_dedup_scan`：EN “Duplicate scan” / zh-CN “重复照片扫描” / zh-TW “重複照片掃描” / es “Búsqueda de duplicados” / fr “Recherche de doublons”。

- [ ] **Step 6: 编译 + 测试（含 shared）**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.*" && ./gradlew :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS（含既有适配器测试零回归）

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/domain/usertask/ shared/src/commonTest/ \
        androidApp/src/main/java/com/mamba/picme/domain/usertask/ \
        androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt \
        androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/UserTaskCard.kt \
        androidApp/src/main/java/com/mamba/picme/MainActivity.kt \
        androidApp/src/main/res/values*/strings.xml \
        androidApp/src/test/java/com/mamba/picme/domain/usertask/
git commit -m "feat(usertask): 去重扫描收编 DEDUP_SCAN 用户任务（适配器 + 对账 + 任务中心呈现）"
```

---

## Task 6: 后台预分析调度（充电+闲置 / 每日闲时 / 条件守卫）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/PreAnalysisConditions.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/service/organize/PreAnalysisScheduler.kt`（条件采集 + 触发链 + lastRunAt 记录）
- Create: `androidApp/src/main/java/com/mamba/picme/service/organize/PreAnalysisReceiver.kt`（`ACTION_POWER_CONNECTED` + 闹钟 fire 共用入口）
- Modify: `androidApp/src/main/AndroidManifest.xml`（receiver 声明）
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt` + `androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`（`organizePreAnalysisRanAtMsFlow` + `updateOrganizePreAnalysisRanAt(nowMs)`，long 键先例 `glEngineRecoveryAvailableAtMsFlow`）
- Modify: `androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt`（调度每日闹钟 + 手动入口接缝）
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/PreAnalysisConditionsTest.kt`（新建）

**Interfaces:**
- Consumes: `MediaMetadataBackfiller`（Task 1）、`DedupScanService`（Task 4）、`TagGenerationService.isScanning`。
- Produces:
  ```kotlin
  data class PreAnalysisInputs(
      val isCharging: Boolean, val isScreenOn: Boolean,
      val tagScanActive: Boolean, val dedupScanActive: Boolean,
      val lastDedupScanCompletedAt: Long?, val now: Long,
  )
  enum class PreAnalysisDecision { RUN_METADATA_AND_DEDUP, RUN_METADATA_ONLY, SKIP }
  object PreAnalysisConditions {
      const val DEDUP_STALE_MS: Long = 24L * 3600 * 1000
      fun decide(inputs: PreAnalysisInputs): PreAnalysisDecision
  }
  ```
  `PreAnalysisScheduler.runNow(trigger: String)`（手动入口 = hub「立即分析」，Task 7 接线）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `charging and idle with stale dedup runs everything`() {
    val d = PreAnalysisConditions.decide(inputs(isCharging = true, isScreenOn = false, lastDedup = null))
    assertEquals(PreAnalysisDecision.RUN_METADATA_AND_DEDUP, d)
}
@Test fun `screen on skips even when charging`() {
    assertEquals(PreAnalysisDecision.SKIP, PreAnalysisConditions.decide(inputs(isCharging = true, isScreenOn = true)))
}
@Test fun `not charging skips`() {
    assertEquals(PreAnalysisDecision.SKIP, PreAnalysisConditions.decide(inputs(isCharging = false, isScreenOn = false)))
}
@Test fun `fresh dedup within 24h runs metadata only`() {
    val d = PreAnalysisConditions.decide(inputs(isCharging = true, isScreenOn = false, lastDedup = NOW - 3600_000))
    assertEquals(PreAnalysisDecision.RUN_METADATA_ONLY, d)
}
@Test fun `tag scan active defers everything`() {
    // Review Focus #4
    assertEquals(PreAnalysisDecision.SKIP, PreAnalysisConditions.decide(inputs(tagActive = true)))
}
@Test fun `dedup already running skips retrigger but allows metadata`() {
    val d = PreAnalysisConditions.decide(inputs(dedupActive = true))
    assertEquals(PreAnalysisDecision.RUN_METADATA_ONLY, d)
}
```

（`inputs(...)` 工厂默认值：charging=true、screenOn=false、tagActive=false、dedupActive=false、lastDedup=null、now=NOW。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.PreAnalysisConditionsTest"`
Expected: 编译失败

- [ ] **Step 3: 实现条件函数**

`decide`：非充电∨亮屏∨tagActive → SKIP；dedupActive → RUN_METADATA_ONLY；`lastDedupScanCompletedAt == null || now - it >= DEDUP_STALE_MS` → RUN_METADATA_AND_DEDUP；否则 RUN_METADATA_ONLY。

- [ ] **Step 4: 调度器 + Receiver + 闹钟 + 记录**

- `PreAnalysisScheduler(context, backfiller, settingsRepository, scope)`：`runNow(trigger)`——采集条件（`Intent(ACTION_BATTERY_CHANGED)` sticky 读充电、`PowerManager.isInteractive` 读亮屏、`TagGenerationService.isScanning.value`、`DedupScanService.sessionProgress.value?.state` 活性、`organizePreAnalysisRanAtMsFlow.first()`/dedup 完成时间）→ `decide` → METADATA：`backfiller.runUntilDone()`；DEDUP：`ContextCompat.startForegroundService(DedupScanService.intentStart(context, DedupScanConfig()))`；结束 `updateOrganizePreAnalysisRanAt(now)`。`scheduleDailyAlarm()`：AlarmManager `setAndAllowWhileIdle`（非精确即可，仿 `TagScanRescheduleReceiver.scheduleResume` 的 `canScheduleExactAlarms` 守卫），默认 03:00 本地，fire 时 `decide` 不满足即静默跳过。
- `PreAnalysisReceiver`：manifest 注册 `android.intent.action.ACTION_POWER_CONNECTED` + 自定义 `com.mamba.picme.organize.PRE_ANALYSIS_ALARM`；`onReceive` → `goAsync` + `PreAnalysisScheduler.runNow(trigger)`。
- `PoLangApplication.onCreate`：`PreAnalysisScheduler.scheduleDailyAlarm()`。
- 设置仓储：接口 + DataStore `longPreferencesKey("organize_pre_analysis_ran_at_ms")`，先例 :294-311。

- [ ] **Step 5: 编译 + 测试**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.*"`
Expected: BUILD SUCCESSFUL，PASS

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/organize/PreAnalysisConditions.kt \
        androidApp/src/main/java/com/mamba/picme/service/organize/ \
        androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt \
        androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt \
        androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt \
        androidApp/src/main/AndroidManifest.xml \
        androidApp/src/test/java/com/mamba/picme/domain/organize/PreAnalysisConditionsTest.kt
git commit -m "feat(organize): 后台预分析调度（充电+闲置/每日闲时/条件守卫 + 元数据回填与去重触发）"
```

---

## Task 7: hub 存储叙事区 + 手动分析入口 + 五语

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupViewModel.kt`（`storageStory` 流 + `preAnalysisRanAt` 流）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeHub.kt`（叙事区 composable + 接线）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeScreen.kt`（Config 态订阅穿透）
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（`PreAnalysisScheduler` 容器接缝，hub 手动入口用）
- Modify: `androidApp/src/main/res/values*/strings.xml`（五语）
- Modify: `docs/08-UI-SPECS/screens/organize.yaml`（叙事区契约登记，UI SSOT）

**Interfaces:**
- Consumes: `StorageStory`（Task 3）、`PreAnalysisScheduler.runNow`（Task 6）、`DedupScanService.sessionProgress`（Task 4）、`user_task` 流（Task 5）。
- Produces: `DedupViewModel.storageStory: StateFlow<StorageStory?>`（null=未加载，hub 按 `boardLoaded` 同款守卫）；`DedupViewModel.preAnalysisRanAt: StateFlow<Long>`；hub 手动回调 `onAnalyzeNow: () -> Unit`。

- [ ] **Step 1: DedupViewModel 叙事流**

```kotlin
val storageStory: StateFlow<StorageStory?> = organizeRepository.observeItems()
    .map { items -> StorageStory.of(items, OrganizeCategorizer.board(items, now = System.currentTimeMillis()), System.currentTimeMillis()) }
    .distinctUntilChanged()
    .flowOn(ioDispatcher)
    .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)
```

（与 `organizeBoard` 共享一次 `board()` 计算更佳——实现时合并为单个 `observeItems().map{ items -> board to story }` 派生双流，避免双份 O(n²) 聚类查询；语义不变。）

- [ ] **Step 2: hub 叙事区**

`DedupHomeHub.kt`：`DedupHubContent` 签名加 `storageStory: StorageStory?`、`preAnalysisRanAt: Long`、`onAnalyzeNow: () -> Unit`；在 Hero 卡与「滑动整理」按钮之间插入 `StorageStorySection(story, preAnalysisRanAt, onAnalyzeNow)`：
- 账本行：照片 X · 视频 Y · 截图 Z（`FormatBytes.format`，用 `core/common/FormatBytes.kt`，不用 dedup internal 副本）；
- 来源账 Top 3：`appSource 名 + bytes`（来源名五语字符串，按枚举映射 stringRes，OTHER 不展示）；
- 建议行（冗余确定性序）：过期截图 `staleScreenshotCount` 非零时一行（点击 → `onOpenCategory(OrganizeCategory.SCREEN_CONTENT)`）；来源 LOW_VALUE 档账本一行（M1 纯展示，不进详情——详情页属 M2 方案面）；
- 新鲜度行：`preAnalysisRanAt > 0` → 「上次分析：相对时间」，右侧「立即分析」TextButton（`onAnalyzeNow`）；去重扫描活性时按钮禁用。
- `boardLoaded` 同款守卫：`storageStory == null` 不渲染。

- [ ] **Step 3: 穿透 + 手动入口**

`DedupHomeScreen.kt` Config 态订阅 `storageStory`/`preAnalysisRanAt` 并穿透；`OrganizeHomeRoute`/`MainPagerHost` 签名加 `onAnalyzeNow: () -> Unit = {}` 透传；`MainActivity` 提供 `{ app.container.preAnalysisScheduler.runNow("manual") }`（`AppContainer` 加 `preAnalysisScheduler` lazy 公开 getter，Task 6 类）。

- [ ] **Step 4: 五语字符串**

| key | EN | zh-CN | zh-TW | ES | FR |
|---|---|---|---|---|---|
| `org_story_ledger` | Photos %1$s · Videos %2$s · Screenshots %3$s | 照片 %1$s · 视频 %2$s · 截图 %3$s | 照片 %1$s · 影片 %2$s · 截圖 %3$s | Fotos %1$s · Vídeos %2$s · Capturas %3$s | Photos %1$s · Vidéos %2$s · Captures %3$s |
| `org_story_stale_screenshots` | %1$d screenshots over a year old · %2$s | %1$d 张一年前截图 · %2$s | %1$d 張一年前截圖 · %2$s | %1$d capturas de hace un año · %2$s | %1$d captures de plus d'un an · %2$s |
| `org_story_last_analysis` | Last analysis: %1$s | 上次分析：%1$s | 上次分析：%1$s | Último análisis: %1$s | Dernière analyse : %1$s |
| `org_story_analyze_now` | Analyze now | 立即分析 | 立即分析 | Analizar ahora | Analyser |
| `org_story_source_%`（10 个来源名） | Camera / WeChat / QQ / WhatsApp / Telegram / Instagram / Messenger / Downloads / Screenshots / Other | 相机 / 微信 / QQ / WhatsApp / Telegram / Instagram / Messenger / 下载 / 截图 / 其他 | 相機 / 微信 / QQ / WhatsApp / Telegram / Instagram / Messenger / 下載 / 截圖 / 其他 | Cámara / WeChat / QQ / WhatsApp / Telegram / Instagram / Messenger / Descargas / Capturas / Otros | Appareil photo / WeChat / QQ / WhatsApp / Telegram / Instagram / Messenger / Téléchargements / Captures / Autres |

（来源名 key 逐一命名 `org_source_camera`…`org_source_other`；相对时间用既有工具或 `DateUtils.getRelativeTimeSpanString`。）

- [ ] **Step 5: organize.yaml 契约登记**

`docs/08-UI-SPECS/screens/organize.yaml` hub 节补叙事区契约（位置：Hero 卡与滑动整理按钮之间；数据口径：StorageStory；新鲜度行与手动入口语义）。

- [ ] **Step 6: 编译 + 测试**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.*" --tests "com.mamba.picme.features.gallery.*"`
Expected: BUILD SUCCESSFUL，PASS

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/ \
        androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt \
        androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt \
        androidApp/src/main/java/com/mamba/picme/MainActivity.kt \
        androidApp/src/main/res/values*/strings.xml \
        docs/08-UI-SPECS/screens/organize.yaml
git commit -m "feat(organize): hub 存储叙事区（总账/来源账/过期截图建议/新鲜度 + 手动立即分析）"
```

---

## Task 7.5: 来源详情页（按 App 浏览 + 删除，2026-11-22 用户追加）

**背景**：用户验收时发现整理模块无法按 App 查看截图/保存的图。M1 原 spec §7 有意将来源账定为纯展示（详情页划 M2），用户现裁定提前到本分支做掉。

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeHub.kt`（来源账行可点 + 查看全部）
- Modify: `DedupHomeScreen.kt` / `features/gallery/organize/OrganizeHomeRoute.kt` / `features/main/MainPagerHost.kt` / `MainActivity.kt`（`onOpenSource` 穿透 + 路由，与 `onOpenCategory` 同模式）
- New: `features/gallery/organize/OrganizeSourceViewModel.kt` + `OrganizeSourceScreen.kt`（按 AppSource 过滤的详情页）
- Modify: `androidApp/src/main/res/values*/strings.xml`（五语）
- Modify: `docs/08-UI-SPECS/screens/organize.yaml`（来源详情页契约）

**Interfaces:**
- Consumes: `appSourceOf`（Task 2）、`OrganizeRepository.observeItems()`、`ValueGuard` 保护规则、`TrashSessionController` 回收站删除链路（系统确认 + undo）。
- Produces: `onOpenSource(AppSource)` 回调（hub→路由穿透）；`OrganizeSourceViewModel`（过滤/分组/删除）。

- [ ] **Step 1: OrganizeSourceViewModel**

按 `AppSource` 过滤 `observeItems()`（`appSourceOf(relativePath) == source`），产出与类目详情页同构的 UiState（Loading/Ready(items, selected, trashed…)）。保护规则走 `ValueGuard`（人脸/老照片红线不变）；**AI 预选保守化：selected 默认空集**（来源页不做「建议删除」预选，全靠用户手选——删除谨慎原则）。删除复用 `TrashSessionController`（系统确认弹窗 + undo）。

- [ ] **Step 2: OrganizeSourceScreen**

复用 `OrganizeCategoryScreen` 的网格/Section/删除栏组件；耦合过重则抽取共用子组件到同包。三段分组简化为两段：保护项（ValueGuard 命中）单独一段不可选，其余按日期降序一段。

- [ ] **Step 3: hub 入口**

来源账 Top3 行逐行可点 → `onOpenSource(对应 AppSource)`；LOW_VALUE 聚合行可点 → 来源列表（见 Step 4）；来源种类 >3 时「查看全部」入口。`boardLoaded` 同款 null 守卫不变。

- [ ] **Step 4: 来源列表**

全部非零来源的完整列表（图标/名称/件数/字节），每行进详情页。实现形态二选一由实现者裁定并记报告：hub 内展开 或 独立 `OrganizeSourceListScreen`。五语标题。

- [ ] **Step 5: 穿透 + 路由**

`onOpenSource: (AppSource) -> Unit` 沿 onOpenCategory 既有链路穿透；详情页导航目标登记（与类目详情页同一路由机制）。

- [ ] **Step 6: 五语 + organize.yaml**

新 key：`org_sources_view_all`、`org_source_detail_title`（带来源名参数）、来源列表页标题等；organize.yaml 登记来源列表/详情页契约（入口、数据口径、删除语义）。

- [ ] **Step 7: 测试 + 编译**

VM 过滤正确性（多来源混杂 → 只出目标 source）、保护项不可选、删除走 Trash 链路单测；`./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug` 全绿。

- [ ] **Step 8: Commit**

`feat(organize): 来源详情页（按 App 浏览媒体 + 回收站删除，hub 来源账可点）`

---

## Task 7.6: 截图重要度分级（金融/医疗/政务保护 + 外卖/电商低价值，2026-11-22 用户追加）

**背景**：用户裁定截图价值按内容来源分级——银行/医院截图不轻易删，外卖/淘宝截图价值低。机制（已勘察定稿）：截图文件名包名解析为主信号（MIUI `Screenshot_YYYY-..._<package>.jpg`，设备实测含 `-edit_` 变体与 `cn.futu.trader` 等金融 App），`ocrText` 关键词兜底（无包名文件名的截图）；VLM 内容理解留 M2+。`media_assets.fileName` 列已存在（无 Room 迁移），`OrganizeItem` 需加 `displayName` 字段穿透。

**Files:**
- New: `domain/organize/ScreenshotImportance.kt`（解析 + 分级纯函数）
- Modify: `domain/organize/OrganizeModels.kt`（`OrganizeItem.displayName: String?`；`ProtectReason` 加 `SENSITIVE_SCREENSHOT`）
- Modify: `domain/organize/ValueGuard.kt`（`SCREEN_CONTENT` 纳入保护类目）
- Modify: `domain/organize/ConfidenceGrader.kt`（LOW_VALUE + 过期 → HIGH 预选）
- Modify: `domain/organize/StorageStory.kt`（过期截图建议口径剔除保护档）
- Modify: `data/repository/OrganizeRepositoryImpl.kt`（displayName 映射）+ 查询投影行（`SystemMediaRow`）
- Modify: `OrganizeResources.kt`（新 ProtectReason 五语 label）+ `res/values*/strings.xml`
- Modify: `docs/08-UI-SPECS/screens/organize.yaml`（分级语义契约）

**Interfaces:**
- Consumes: `OrganizeItem.displayName`/`ocrText`（既有数据源，仅穿透）；`AppSource.SCREENSHOTS` 判定（`appSourceOf`，避免对非截图误解析）。
- Produces: `screenshotImportanceOf(item: OrganizeItem): ScreenshotImportance`（sealed：PROTECTED_FINANCE / PROTECTED_HEALTH / PROTECTED_GOV / LOW_VALUE / NEUTRAL，命中来源包名或关键词入 verdict）。

**设计裁定（已定，勿推翻）：**
1. 只对截图生效（`appSourceOf(relativePath) == SCREENSHOTS`），非截图恒 NEUTRAL。
2. 信号优先级：文件名包名 > ocrText 关键词；两信号冲突时**保护档优先**（保守偏置，与 ValueGuard「宁可不预选不可误删」一致）。
3. 包名前缀匹配（如 `com.icbc` 命中 `com.icbc.mobile`）；词表实现者拟定+测试钉死——金融（工行/建行/农行/中行/招行/交行/云闪付/支付宝/券商）/医疗健康（挂号/医院/微医/好大夫）/政务（12123/政务服务/个税）；低价值（美团/饿了么/淘宝/天猫/京东/拼多多/大众点评）。
4. OCR 兜底关键词：保护档（银行/转账/汇款/挂号/病历/处方/医保/身份证/护照），低价值（外卖/满减/淘宝/拼多多）；冲突同样保护优先。
5. 接线三处：ValueGuard（PROTECTED_* → `SENSITIVE_SCREENSHOT`，进保护区不预选）；ConfidenceGrader（LOW_VALUE + captureDate 超 1 年 → HIGH）；StorageStory（`staleScreenshotCount/Bytes` 剔除 PROTECTED_*，建议行只推可删的）。
6. hub 建议行文案不变；详情页保护原因 label 走 OrganizeResources 五语。

- [ ] **Step 1**: `ScreenshotImportance.kt` 纯函数 + 单测（包名命中/`-edit_` 变体/无包名 → OCR 兜底/非截图 NEUTRAL/冲突保护优先/前缀匹配/大小写）。
- [ ] **Step 2**: `OrganizeItem.displayName` 穿透（SystemMediaRow 投影 + OrganizeRepositoryImpl 映射 + 既有测试夹具补字段）。
- [ ] **Step 3**: ValueGuard / ConfidenceGrader / StorageStory 三处接线 + 各自单测（保护进区不预选；LOW_VALUE+过期 HIGH；NEUTRAL 不变；story 口径剔除）。
- [ ] **Step 4**: ProtectReason 五语 label + organize.yaml 契约。
- [ ] **Step 5**: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug` 全绿。
- [ ] **Step 6**: Commit `feat(organize): 截图重要度分级（金融/医疗/政务保护 + 外卖/电商低价值，包名+OCR 双信号）`

---

## Task 8: 文档同步 + 全量验证

**Files:**
- Modify: `androidApp/AGENTS.md`（§1.2 无新路由；§2.2 domain 表 organize/usertask 行、service 基础设施行；§2.3 Room v29 行）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md`（§2.4 叙事区 + 扫描服务化）
- Modify: `shared/AGENTS.md`（usertask 协议枚举加值）
- Modify: `docs/03-TECHNICAL-SPECS/`（按需：DEDUP_SCAN 收编事实落 `CHAT_PROTOCOL_TECH_SPEC` 不适用则免）

- [ ] **Step 1: 三处 AGENTS.md 同步**

- `androidApp/AGENTS.md`：§2.2 `domain/organize/` 行补 `AppSource`/`StorageStory`/`PreAnalysisConditions`；`domain/dedup/` 行补 `DedupScanSession`；`domain/usertask/` 行补 `DedupScanTaskAdapter`；§2.4 基础设施表加 `service/dedup/`（DedupScanService FGS 10044）与 `service/organize/`（PreAnalysisScheduler/Receiver）；§2.3 Room 行末尾加「v29 `media_assets` 加 `relativePath`/`sizeBytes`（MIGRATION_28_29 + `MediaMetadataBackfiller` 回填）」。
- `features/gallery/AGENTS.md`：§2.4 补「扫描服务化（DedupScanService 会话单例，VM 观察化）」「hub 存储叙事区（StorageStory 纯函数聚合，hero 与滑动整理之间，freshness + 立即分析）」。
- `shared/AGENTS.md`：usertask 节补 `DEDUP_SCAN`/`ORGANIZE_HUB` 枚举值。

- [ ] **Step 2: 全量单测 + 编译**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug && ./gradlew :shared:testDebugUnitTest`
Expected: 全部 PASS + APK 产出

- [ ] **Step 3: 真机闭环验证**

Run: `./scripts/auto-dev-loop.sh --quick`
手工 adb/ui-driver 补充检查点：
1. 整理页 hub 出现存储叙事区（账本/来源账/新鲜度）；点「立即分析」后出现去重扫描通知与任务中心 `DEDUP_SCAN` 任务卡（可暂停/恢复/取消）
2. 扫描中杀进程重启 → 任务中心该任务呈 FAILED(已中断) + 重试
3. 任务中心点 `DEDUP_SCAN` 卡 → 跳整理页 ORGANIZE Tab
4. 既有去重流程（Config→Scanning→Results→删除）全通，与改造前行为一致

- [ ] **Step 4: Commit**

```bash
git add androidApp/AGENTS.md androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md shared/AGENTS.md
git commit -m "docs: 同步 M1 预分析/叙事/扫描服务化实现细节（AGENTS.md 三处）"
```

---

## Self-Review 记录

- **Spec 覆盖**：§6 来源映射（Task 2）/ §7 叙事（Task 3+7，快照表简化为活算+时间戳，范围声明已注）/ §8 预分析（Task 6，D7 触发实现）/ §9 DEDUP_SCAN 收编（Task 5）/ §15 D3 服务化（Task 4）/ D6 v29（Task 1）/ D8 口径（Task 3 reclaim 测试钉死）。M2/M3 项明确排除。
- **D1/D2/D4/D5**（执行模型/类目裁定/保护规则/冷启动两段式）属 M2 CleanupPlan 面，本计划不涉及，无遗漏。
- **类型一致性**：`DedupScanSession`/`DedupScanProgress`/`DedupScanState`（Task 4 定义，Task 5/6/7 消费）；`StorageStory.of(items, board, now)`（Task 3 定义，Task 7 消费）；`MediaMetadataBackfiller.runUntilDone`（Task 1 定义，Task 6 消费）；`PreAnalysisScheduler.runNow(trigger)`（Task 6 定义，Task 7 消费）；`user_task_title_dedup_scan`/`ORGANIZE_HUB`（Task 5 三处穷尽分支）。
- **占位符扫描**：Task 1 Step 1 与 Task 3 Step 1 的测试工厂注明「按实际签名写全」系构造参数锚点，语义断言已钉死。
