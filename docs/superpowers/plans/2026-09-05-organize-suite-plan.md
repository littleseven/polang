# 整理套件（F1 整理中心 + F2 手势整理 + F3 回忆）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-09-05-gallery-organization-5-features-design.md` 落地 F1（整理中心 hub + 类目详情批量清理）、F2（手势快速整理，AI 预排序）、F3（回忆 Memories 端侧生成）。F4/F5 不在本计划范围。

**Architecture:** 三个 feature 共用一条新抽的通用回收站删除编排（`TrashSessionController`，从 `DedupViewModel` 内嵌逻辑提炼，`DedupTrashManager` 零改动复用）。F1 把「相册整理」Pager 页 1 的 `DedupUiState.Config` 态升级为整理中心 hub（类目卡 + dedup 折叠区），类目详情走新 NavHost 路由；F2 为独立全屏路由 `swipe_review`，队列由纯函数 `SwipeQueueBuilder` 构建；F3 为纯查询编排的 `MemoriesGenerator`（零新增推理、v1 不落 Room 表、内存态 + 每日重算），以 `MediaGrid` 新增 header 槽挂载到相册首页顶部，详情走新路由 `memory_detail/{id}`。

**Tech Stack:** Kotlin / Jetpack Compose (Material3) / Room(KSP) / Coil / coroutines Flow / JUnit4 + kotlinx-coroutines-test。

**工作区：** `.worktrees/feat-organize-suite`（分支 `feat/organize-suite`），所有路径相对该 worktree 根。

**设计稿：** Ardot `polang-ui-spec` 文件 Organize/Memories 页 + Chat 页（帧 ID 见 spec 文末）；留档截图在 **主仓** `tmp/ardot-org/shots/`（worktree 外的 `/Users/guoshuai/AndroidStudioProjects/polang/tmp/ardot-org/shots/`，截图文件名含 node id）。UI 任务以截图 + spec 为视觉基准。

**V1 明确不做：**
- F3 回忆不落 `memories` Room 表（spec 原文的 Room 缓存降级为内存态 + 进入相册页时重算；重算为纯 Room 查询编排，成本低）。spec §F3 验收 AC-F3-3 的「已隐藏列表」v1 只做「隐藏此回忆」（DataStore 持久化隐藏 ID 集合），不做设置页管理列表。
- F1「闭眼人像」用 `faceQualityScore` 低分代理（无显式闭眼信号，探索已确认），类目命名与文案按「Low-quality portraits」口径。
- F2 连拍桶依赖 dedup SCENE 现算：v1 不做（SCENE 组不持久化），队列桶 = 截图 > 低美学分 > 低人脸质量 > 普通（时间倒序）。
- F1 Hero 的「预计可释放总量」= 各类目 sizeBytes 之和（截图/大视频有 MediaStore SIZE；模糊/人像用估算：该类目命中媒体的 SIZE 之和；同一媒体跨类目不去重——Hero 文案用 "Estimated reclaimable" 已是估算口径，可接受）。
- F1「数据覆盖不足时类目卡显示需要先扫描引导」v1 不做——未打标时 BLURRY/LOW_QUALITY_PORTRAITS 卡不渲染；后续版本补引导卡。

**关键现状事实（探索已核实，执行者必须知道）：**

- **Pager 页单根铁律**：`DedupHomeRoute`（`features/gallery/dedup/DedupHomeScreen.kt:131-134`）必须单个 `Box(fillMaxSize)` 包裹全部内容/覆盖层；`GalleryScreen` 根 Box 在 `GalleryScreen.kt:565`。新增覆盖层一律挂进该 Box。
- **DedupUiState**（`DedupViewModel.kt:32-58`）：`Config(config)` / `Scanning(...)` / `Results(groups, selectedTab, policy)` / `Cleaned(...)`。Config 态内容 = `DedupConfigContent`（`DedupHomeScreen.kt:289-400`：Hero 卡 → 尺度勾选 `DedupLevelRow` ×3 → 保留规则入口 Card → 开始扫描 Button → 隐私 caption）。hub 改造点就位。
- **DedupTrashManager**（`domain/dedup/DedupTrashManager.kt`，45 行，仅依赖 Context，与组模型零耦合）：
  ```kotlin
  class DedupTrashManager(private val context: Context) {
      val isSupported: Boolean                                     // API 30+
      fun buildTrashIntent(uris: List<String>): IntentSender       // createTrashRequest(true)，同步 IPC，勿主线程
      fun buildRestoreIntent(uris: List<String>): IntentSender     // createTrashRequest(false)
      fun queryExisting(uris: List<String>): List<String>          // 残留复查必须读 IS_TRASHED（HyperOS 陷阱）
  }
  ```
- **DedupViewModel 删除编排（抽取蓝本）**：`deleteSelected()`（`DedupViewModel.kt:268-296`：IO 线程 buildTrashIntent → 置 `pendingTrash`）→ UI 层 `rememberLauncherForActivityResult(StartIntentSenderForResult())` 拉起 → `onTrashResult(ok)`（:340-366：`queryExisting` 复查 → 残留置 `partialTrashNotice`）；undo = `buildRestoreIntent` + `pendingRestore` + `onRestoreResult`（:399-411）。`PendingTrash(uris, intentSender, groupId: String?)`（:61）。
- **DedupViewModel 托管**：Activity 级，`MainActivity.kt:132-139` `viewModel(factory = app.container.createDedupViewModelFactory { uris -> ... legacyDeleter ... })`；工厂在 `di/AppContainer.kt:135` 声明、`:759` 实现。新 VM 照此模式。
- **media_assets 表**（`data/model/MediaEntity.kt:9-59`，AppDatabase version=21）：有 `aestheticScore`(1~10 NIMA) / `faceQualityScore`(~0~1 eDifFIQA) / `city` / `hasFace` / `ocrText` / `labels` / `captureDate`(索引) / `type` / `duration`；**没有** width/height/sizeBytes/relativePath 列。null 分 = 未评分，**查询必须 `IS NOT NULL AND score < 阈值`**。
- **截图/大小判定**：`features/gallery/dedup/DedupMediaSource.kt:150-210` `queryImageMeta`（MediaStore 投影：Q+ `RELATIVE_PATH`，兜底 `DATA`；join key = content uri 字符串）；`detectContentType`（:82-96 纯函数）：SCREENSHOT = `path.contains("screenshots", ignoreCase=true)`。
- **跨数据源 join 一律用 uri 字符串**，不用 id（系统相册来源 id 是 syntheticMediaId 负值编码，`MediaRepositoryImpl.kt:552-558`）。
- **MediaDao**（`data/local/MediaDao.kt`）现有聚合仅 `getFaceGroups()`(:233) 和 `getCityGroups(limit)`(:452)；无 sizeBytes/分数阈值/时间分组查询——需新增（见 Task 3）。
- **Room migration 惯例**：`AppDatabase.kt` companion 内 `private val MIGRATION_X_Y = object : Migration(X, Y) { override fun migrate(db) { db.execSQL(...) } }`，`:91-99` 注册。本计划**不需要新 migration**（不加表不加列）。
- **导航样板**：`navigation/Screen.kt` sealed class（带参范例 `PhotoEditor` :57-78）；`MainActivity.kt` NavHost 注册（带参范例 :316-354，含 `URLDecoder.decode`、VM factory 注入）。
- **MediaGrid**（`features/gallery/components/MediaGrid.kt:66-82`）：`LazyVerticalGrid(GridCells.Adaptive(110.dp))`，分组头全 span item（:163-173），item key=`asset.id`。F3 给它加可选 `header` 槽（全 span item，在分组之前 emit）。
- **Coil 缩略图铁律**（`MediaGrid.kt:238-252`）：`AsyncImage(model = ImageRequest.Builder(context).data(uri).size(360).crossfade(false).build(), contentScale = ContentScale.Crop, placeholder/error = ColorPainter(colorScheme.surface))`——**crossfade 必须 false**（recycled bitmap 崩溃）。
- **设计系统**：`core/designsystem/`——卡片 = M3 `Card` + `AppShapes.card`(12) 或 `panel/lg`(16) + `colorScheme.surfaceContainer`；渐变按钮 = `ChatBubbleTokens.brandGradientStart/End` + `Brush.linearGradient`（参照 tag_control 页）；横向小卡规格参考 `ChatCarouselTokens`（120×150 r12）；顶栏复用 `features/common/topbar/AppTopBar.kt`（`AppTopBar`/`AppTopBarAction`/`AppTopBarNavBack`）。**禁止手改 DesignTokens.kt 等生成物**；本计划不需要新 token。
- **i18n**：五语手工同步（无校验脚本）：`androidApp/src/main/res/values/strings.xml`（EN）+ `values-zh-rCN` / `values-zh-rTW` / `values-es` / `values-fr`。dedup 键块范例 `values/strings.xml:351-435`。本计划键前缀：`org_*`（F1）/ `swipe_*`（F2）/ `memory_*`（F3）。
- **测试范式**：`androidApp/src/test/java/com/mamba/picme/`，JUnit4 + 反引号命名 + 确定性夹具（范例 `core/common/PerceptualHashTest.kt`、`domain/dedup/KeepPolicyEngineTest.kt`）。
- **既有可复用 UI 原子件**（`features/gallery/dedup/DedupComponents.kt`）：`DedupThumb(uri, isKept, role, ...)`(:124)、`ContentTypeBadge`(:78)、`formatBytes`(:324，英文单位硬编码，可沿用)。

---

## Part A：共享基础（回收站删除编排抽取）

### Task 1: `TrashSessionController`——通用回收站删除/恢复编排（TDD）

**动机**：`DedupViewModel` 里的「IO 构建 trash intent → pendingTrash → UI 授权 → queryExisting 复查 → 部分拒绝提示 / undo」编排要复用到 F1 类目删除和 F2 手势删除，但现实现硬编码在 DedupUiState.Results 状态机里。抽成与 UI 状态无关的会话控制器，DedupViewModel 后续可迁移（本计划**不迁移 DedupViewModel**，只新增共用件，避免动已稳定的去重流程）。

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/trash/TrashSessionController.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/trash/TrashSessionControllerTest.kt`

**设计**：接口 `TrashBackend` 隔离 MediaStore IPC（JVM 可测）；控制器持有 pending 槽位与一次性事件。回调语义与 DedupViewModel 现状完全对齐（含 IS_TRASHED 复查、部分拒绝、undo）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.trash

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashSessionControllerTest {

    private class FakeBackend : TrashBackend {
        override val isSupported: Boolean = true
        var trashed = mutableSetOf<String>()
        var failNextBuild = false
        override fun buildTrashToken(uris: List<String>): Any {
            if (failNextBuild) throw RuntimeException("ipc fail")
            return "trash-token"
        }
        override fun buildRestoreToken(uris: List<String>): Any = "restore-token"
        override fun queryExisting(uris: List<String>): List<String> =
            uris.filter { it !in trashed } // 已 trash 的不算残留（IS_TRASHED 语义）
        fun simulateUserAllowed(uris: List<String>) { trashed.addAll(uris) }
    }

    private fun newController(scope: TestScope, backend: FakeBackend): TrashSessionController =
        TrashSessionController(backend, scope, StandardTestDispatcher(scope.testScheduler))

    @Test
    fun `requestTrash exposes pending request with uris and token`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a", "b"), tag = "cat:screenshots")
        advanceUntilIdle()
        val pending = c.pendingRequest.value
        assertEquals(listOf("a", "b"), pending?.uris)
        assertEquals("trash-token", pending?.token)
        assertEquals("cat:screenshots", pending?.tag)
    }

    @Test
    fun `requestTrash while pending is ignored`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        c.requestTrash(listOf("b"))
        advanceUntilIdle()
        assertEquals(listOf("a"), c.pendingRequest.value?.uris)
    }

    @Test
    fun `build failure keeps state clean and emits error event`() = runTest {
        val backend = FakeBackend().apply { failNextBuild = true }
        val c = newController(this, backend)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertTrue(c.consumeErrorEvent())
        assertFalse(c.consumeErrorEvent()) // 一次性
    }

    @Test
    fun `user allowed with full success clears pending and reports trashed uris`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a", "b"))
        advanceUntilIdle()
        backend.simulateUserAllowed(listOf("a", "b"))
        val outcome = c.onTrashResult(ok = true)
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertEquals(listOf("a", "b"), (outcome as? TrashOutcome.Trashed)?.trashedUris)
        assertFalse(c.partialNotice.value)
    }

    @Test
    fun `partial rejection sets notice and reports only actually trashed`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a", "b"))
        advanceUntilIdle()
        backend.simulateUserAllowed(listOf("a")) // b 被拒
        val outcome = c.onTrashResult(ok = true)
        advanceUntilIdle()
        assertEquals(listOf("a"), (outcome as? TrashOutcome.Trashed)?.trashedUris)
        assertTrue(c.partialNotice.value)
        c.consumePartialNotice()
        assertFalse(c.partialNotice.value)
    }

    @Test
    fun `user denied keeps nothing trashed and no notice`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        val outcome = c.onTrashResult(ok = false)
        advanceUntilIdle()
        assertEquals(TrashOutcome.Cancelled, outcome)
        assertFalse(c.partialNotice.value)
    }

    @Test
    fun `restore flow trashes back to library`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestRestore(listOf("a"))
        advanceUntilIdle()
        assertEquals("restore-token", c.pendingRequest.value?.token)
        backend.trashed.remove("a")
        val outcome = c.onRestoreResult(ok = true)
        advanceUntilIdle()
        assertEquals(listOf("a"), (outcome as? TrashOutcome.Restored)?.restoredUris)
        assertNull(c.pendingRequest.value)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.trash.TrashSessionControllerTest" --console=plain`
Expected: 编译失败（`TrashSessionController` 不存在）

- [ ] **Step 3: 实现**

```kotlin
package com.mamba.picme.domain.trash

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MediaStore 回收站 IPC 抽象；生产实现包装 DedupTrashManager（IntentSender 即 token）。 */
interface TrashBackend {
    val isSupported: Boolean
    fun buildTrashToken(uris: List<String>): Any
    fun buildRestoreToken(uris: List<String>): Any
    fun queryExisting(uris: List<String>): List<String>
}

data class PendingTrashRequest(
    val uris: List<String>,
    val token: Any,
    val isRestore: Boolean,
    val tag: String?,
)

sealed interface TrashOutcome {
    data class Trashed(val trashedUris: List<String>, val tag: String?) : TrashOutcome
    data class Restored(val restoredUris: List<String>) : TrashOutcome
    data object Cancelled : TrashOutcome
    data object Unsupported : TrashOutcome
}

/**
 * 通用回收站删除/恢复编排（从 DedupViewModel 提炼，供整理中心/手势整理复用）。
 * 线程：build/query 走 ioDispatcher；pendingRequest 由 UI 层以 StartIntentSenderForResult 拉起。
 */
class TrashSessionController(
    private val backend: TrashBackend,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _pendingRequest = MutableStateFlow<PendingTrashRequest?>(null)
    val pendingRequest: StateFlow<PendingTrashRequest?> = _pendingRequest

    private val _partialNotice = MutableStateFlow(false)
    val partialNotice: StateFlow<Boolean> = _partialNotice

    private var errorEvent = false

    val isSupported: Boolean get() = backend.isSupported

    fun requestTrash(uris: List<String>, tag: String? = null) =
        request(uris, isRestore = false, tag = tag)

    fun requestRestore(uris: List<String>) =
        request(uris, isRestore = true, tag = null)

    private fun request(uris: List<String>, isRestore: Boolean, tag: String?) {
        if (uris.isEmpty() || _pendingRequest.value != null) return
        if (!backend.isSupported) { errorEvent = true; return }
        scope.launch {
            val token = withContext(ioDispatcher) {
                runCatching {
                    if (isRestore) backend.buildRestoreToken(uris) else backend.buildTrashToken(uris)
                }.getOrNull()
            }
            if (token == null) {
                errorEvent = true
            } else {
                _pendingRequest.value = PendingTrashRequest(uris, token, isRestore, tag)
            }
        }
    }

    /** UI 授权回调。ok=false → Cancelled；ok=true → 复查 IS_TRASHED 残留。 */
    fun onTrashResult(ok: Boolean): TrashOutcome {
        val pending = _pendingRequest.value ?: return TrashOutcome.Cancelled
        _pendingRequest.value = null
        if (!ok) return TrashOutcome.Cancelled
        val remaining = backend.queryExisting(pending.uris)
        val trashed = pending.uris - remaining.toSet()
        if (remaining.isNotEmpty()) _partialNotice.value = true
        return TrashOutcome.Trashed(trashed, pending.tag)
    }

    fun onRestoreResult(ok: Boolean): TrashOutcome {
        val pending = _pendingRequest.value ?: return TrashOutcome.Cancelled
        _pendingRequest.value = null
        if (!ok) return TrashOutcome.Cancelled
        val remaining = backend.queryExisting(pending.uris)
        return TrashOutcome.Restored(pending.uris - remaining.toSet())
    }

    fun consumePartialNotice() { _partialNotice.value = false }
    fun consumeErrorEvent(): Boolean = errorEvent.also { errorEvent = false }
}
```

> 说明：`queryExisting` 在 `onTrashResult` 里同步调用——保持与 DedupViewModel 现状一致的语义；其内部是单次 MediaStore 查询，耗时可忽略（DedupTrashManager 现状如此）。`token` 用 `Any` 占位避免 domain 层依赖 `IntentSender`；UI 层强转。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.trash.TrashSessionControllerTest" --console=plain`
Expected: 7 tests PASS

- [ ] **Step 5: 生产 Backend 适配器**

Create `androidApp/src/main/java/com/mamba/picme/domain/trash/DedupTrashBackend.kt`：

```kotlin
package com.mamba.picme.domain.trash

import com.mamba.picme.domain.dedup.DedupTrashManager

/** TrashBackend 生产实现：委托 DedupTrashManager（IntentSender 作为 token 透传）。 */
class DedupTrashBackend(private val trashManager: DedupTrashManager) : TrashBackend {
    override val isSupported: Boolean get() = trashManager.isSupported
    override fun buildTrashToken(uris: List<String>): Any = trashManager.buildTrashIntent(uris)
    override fun buildRestoreToken(uris: List<String>): Any = trashManager.buildRestoreIntent(uris)
    override fun queryExisting(uris: List<String>): List<String> = trashManager.queryExisting(uris)
}
```

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/trash/ androidApp/src/test/java/com/mamba/picme/domain/trash/
git commit -m "feat(trash): 通用回收站删除/恢复编排 TrashSessionController（整理套件共用）"
```

---

## Part B：F1 整理中心（hub + 类目详情）

### Task 2: `domain/organize` 类目模型与统计纯函数（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeModels.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/domain/organize/OrganizeCategorizer.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/organize/OrganizeCategorizerTest.kt`

**输入模型**（屏蔽数据来源差异——Room 列 + MediaStore meta 合并后的扁平结构）：

```kotlin
package com.mamba.picme.domain.organize

enum class OrganizeCategory { DUPLICATES, SCREENSHOTS, BLURRY, LOW_QUALITY_PORTRAITS, LARGE_VIDEOS, DOCUMENTS }

/** 类目判定输入：Room 列 + MediaStore 批量 meta（path/sizeBytes）合并后的扁平快照。 */
data class OrganizeItem(
    val uri: String,
    val isVideo: Boolean,
    val captureDate: Long,
    val sizeBytes: Long,            // 未知 = 0（统计时计入 0，不影响正确性）
    val relativePath: String?,      // MediaStore RELATIVE_PATH / DATA 兜底
    val ocrText: String?,
    val pixelArea: Long?,           // dedup_hash.pixelArea 或 MediaStore W×H；未知 null
    val labels: String?,
    val hasFace: Boolean,
    val aestheticScore: Float?,     // null = 未评分（必须排除，不能当低分）
    val faceQualityScore: Float?,   // null 同上
)

data class CategoryStat(
    val category: OrganizeCategory,
    val count: Int,
    val totalBytes: Long,
    val previewUris: List<String>,  // 前 4 张，卡片缩略图条
)
```

**判定规则**（阈值常量化，实测定档；顺序 = 卡片展示顺序；DUPLICATES 不在此处判定——它走进 dedup 流程，hub 卡只展示上次扫描摘要）：

| 类目 | 规则 | 阈值常量 |
|---|---|---|
| SCREENSHOTS | `relativePath?.contains("screenshots", true) == true`（与 `DedupMediaSource.detectContentType` 同口径） | — |
| BLURRY | `!isVideo && aestheticScore != null && aestheticScore < 3.5f` | `AESTHETIC_LOW_THRESHOLD = 3.5f` |
| LOW_QUALITY_PORTRAITS | `!isVideo && hasFace && faceQualityScore != null && faceQualityScore < 0.35f` | `FACE_QUALITY_LOW_THRESHOLD = 0.35f` |
| LARGE_VIDEOS | `isVideo && sizeBytes >= 100 * 1024 * 1024` | `LARGE_VIDEO_BYTES = 100L * 1024 * 1024` |
| DOCUMENTS | `!isVideo` 且（OCR 密度：`ocrText.length * 1_000_000 > (pixelArea ?: 0) * 20`，pixelArea 未知时 `ocrText.length > 200`；或 labels 命中 document 关键词——**直接复用** `DedupMediaSource.kt` 的 `detectContentType`（见 Step 3） | — |

同一媒体可归多个类目（v1 Hero 不去重，见计划头「V1 明确不做」）。

- [ ] **Step 1: 写失败测试**（`OrganizeCategorizerTest.kt`）

```kotlin
package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeCategorizerTest {

    private fun item(
        uri: String,
        isVideo: Boolean = false,
        sizeBytes: Long = 1_000_000,
        relativePath: String? = "DCIM/Camera/",
        ocrText: String? = null,
        pixelArea: Long? = 12_000_000,
        labels: String? = null,
        hasFace: Boolean = false,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
    ) = OrganizeItem(uri, isVideo, 1_000L, sizeBytes, relativePath, ocrText, pixelArea, labels, hasFace, aestheticScore, faceQualityScore)

    private fun categoriesOf(vararg items: OrganizeItem): Map<String, Set<OrganizeCategory>> =
        items.associate { it.uri to OrganizeCategorizer.categoriesOf(it) }

    @Test
    fun `screenshot path detected case-insensitively`() {
        val cats = categoriesOf(item("a", relativePath = "Pictures/Screenshots/"))
        assertTrue(OrganizeCategory.SCREENSHOTS in cats.getValue("a"))
    }

    @Test
    fun `null aesthetic score is not blurry`() {
        val cats = categoriesOf(item("a", aestheticScore = null))
        assertTrue(OrganizeCategory.BLURRY !in cats.getValue("a"))
    }

    @Test
    fun `low aesthetic score below threshold is blurry`() {
        val cats = categoriesOf(item("a", aestheticScore = 2.1f), item("b", aestheticScore = 3.5f))
        assertTrue(OrganizeCategory.BLURRY in cats.getValue("a"))
        assertTrue(OrganizeCategory.BLURRY !in cats.getValue("b")) // 阈值边界不含
    }

    @Test
    fun `low face quality portrait detected, null excluded`() {
        val cats = categoriesOf(
            item("a", hasFace = true, faceQualityScore = 0.2f),
            item("b", hasFace = true, faceQualityScore = null),
            item("c", hasFace = false, faceQualityScore = 0.2f),
        )
        assertTrue(OrganizeCategory.LOW_QUALITY_PORTRAITS in cats.getValue("a"))
        assertTrue(OrganizeCategory.LOW_QUALITY_PORTRAITS !in cats.getValue("b"))
        assertTrue(OrganizeCategory.LOW_QUALITY_PORTRAITS !in cats.getValue("c"))
    }

    @Test
    fun `large video threshold`() {
        val big = item("a", isVideo = true, sizeBytes = 150L * 1024 * 1024)
        val small = item("b", isVideo = true, sizeBytes = 50L * 1024 * 1024)
        val photo = item("c", isVideo = false, sizeBytes = 150L * 1024 * 1024)
        val cats = categoriesOf(big, small, photo)
        assertTrue(OrganizeCategory.LARGE_VIDEOS in cats.getValue("a"))
        assertTrue(OrganizeCategory.LARGE_VIDEOS !in cats.getValue("b"))
        assertTrue(OrganizeCategory.LARGE_VIDEOS !in cats.getValue("c"))
    }

    @Test
    fun `document by ocr density and fallback`() {
        val dense = item("a", ocrText = "x".repeat(500), pixelArea = 12_000_000)
        val unknownArea = item("b", ocrText = "x".repeat(250), pixelArea = null)
        val sparse = item("c", ocrText = "hi", pixelArea = 12_000_000)
        val byLabel = item("d", labels = """["receipt","shop"]""")
        val cats = categoriesOf(dense, unknownArea, sparse, byLabel)
        assertTrue(OrganizeCategory.DOCUMENTS in cats.getValue("a"))
        assertTrue(OrganizeCategory.DOCUMENTS in cats.getValue("b"))
        assertTrue(OrganizeCategory.DOCUMENTS !in cats.getValue("c"))
        assertTrue(OrganizeCategory.DOCUMENTS in cats.getValue("d"))
    }

    @Test
    fun `stats aggregate count bytes and previews`() {
        val items = listOf(
            item("s1", relativePath = "Pictures/Screenshots/", sizeBytes = 100),
            item("s2", relativePath = "Pictures/Screenshots/", sizeBytes = 200),
            item("p1"),
        )
        val stats = OrganizeCategorizer.stats(items)
        val shot = stats.first { it.category == OrganizeCategory.SCREENSHOTS }
        assertEquals(2, shot.count)
        assertEquals(300, shot.totalBytes)
        assertEquals(listOf("s1", "s2"), shot.previewUris)
        assertTrue(stats.none { it.category == OrganizeCategory.DUPLICATES })
        assertTrue(stats.none { it.count == 0 }) // 空类目不出卡
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.organize.OrganizeCategorizerTest" --console=plain`
Expected: 编译失败

- [ ] **Step 3: 实现**

`OrganizeCategorizer`（object）：`categoriesOf(item): Set<OrganizeCategory>` + `stats(items): List<CategoryStat>`（按 enum 顺序、过滤 count==0、previewUris 取前 4）。DOCUMENTS 判定复用 dedup 同口径：把 `DedupMediaSource.kt:82-96` 的 `detectContentType` 从 `internal` 提为 `public`（签名不变），这里调它——`detectContentType(path, ocrText, pixelArea, labels, hasFace, faceQualityScore) == DedupContentType.DOCUMENT`。

- [ ] **Step 4: 跑测试确认通过** → 7 tests PASS

- [ ] **Step 5: Commit** `feat(organize): F1 类目判定与统计纯函数`

---

### Task 3: 类目数据源——DAO 增补 + MediaStore meta 泛化

**目标**：产出 `Flow<List<OrganizeItem>>`（hub 统计）与 `List<OrganizeItem>`（类目详情全量）。数据源合并策略：Room `media_assets` 行（uri/type/captureDate/ocrText/labels/hasFace/scores）+ MediaStore 批量 meta（uri→relativePath, sizeBytes, width×height）按 **uri join**；`dedup_hash.pixelArea` 有则用、无则 W×H 现算。

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/MediaDao.kt`（加投影查询）
- Create: `androidApp/src/main/java/com/mamba/picme/data/repository/OrganizeRepository.kt`
- Test: 无（Room/MediaStore 查询靠编译 + 真机验证；纯判定已在 Task 2 覆盖）

- [ ] **Step 1: MediaDao 加轻量投影**（避免拖 512 维 embedding 等大列）：

```kotlin
// MediaDao.kt 追加（配合文件顶部 data class 定义）
data class OrganizeRow(
    val uri: String,
    val type: String,
    val captureDate: Long,
    val ocrText: String?,
    val labels: String?,
    val hasFace: Boolean,
    val aestheticScore: Float?,
    val faceQualityScore: Float?,
)

@Query("SELECT uri, type, captureDate, ocrText, labels, hasFace, aestheticScore, faceQualityScore FROM media_assets")
fun observeOrganizeRows(): Flow<List<OrganizeRow>>
```

- [ ] **Step 2: MediaStore meta 批量查询泛化**：`DedupMediaSource.kt:150-210` 的 `queryImageMeta` 是 dedup 内部 `private`/`internal`。在 `OrganizeRepository` 内实现等价查询（不改动 dedup 文件，避免交叉影响）：投影 `_ID/DATE_ADDED?` 不需要——join key 用 content uri 字符串：`ContentUris.withAppendedId(collection, id)` 重建，列 = `_ID, SIZE, WIDTH, HEIGHT, RELATIVE_PATH(Q+) / DATA(兜底)`；Images 与 Video 两个 collection 各查一次合并。照抄 dedup 的版本判定与 DATA 兜底写法。

- [ ] **Step 3: `OrganizeRepository`**：

```kotlin
package com.mamba.picme.data.repository

class OrganizeRepository(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val dedupHashDao: DedupHashDao,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** hub 统计流：Room 行 + MediaStore meta 合并；媒体库任何写触发重算。 */
    fun observeItems(): Flow<List<OrganizeItem>>

    /** 类目详情全量（一次性）。 */
    suspend fun loadItems(): List<OrganizeItem>
}
```

实现要点：合并走 `withContext(ioDispatcher)`；MediaStore meta 按 uri join；`pixelArea = dedup_hash[uri]?.pixelArea ?: (width*height 若均>0)`；`sizeBytes` 来自 MediaStore `SIZE`（0 = 未知）。`DedupHashDao` 现有按 uri 查能力若无则加 `@Query("SELECT * FROM dedup_hash WHERE uri IN (:uris)")`（分批 500）。

- [ ] **Step 4: 编译** `./gradlew :androidApp:compileDebugKotlin --console=plain` → BUILD SUCCESSFUL

- [ ] **Step 5: Commit** `feat(organize): F1 类目数据源 OrganizeRepository（Room+MediaStore 按 uri 合并）`

---

### Task 4: hub 升级——`DedupUiState.Config` 态改造为整理中心

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeScreen.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupViewModel.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（工厂注入 OrganizeRepository）
- Modify: `androidApp/src/main/res/values/strings.xml` 等五语（见 Task 5）

**视觉基准**：`tmp/ardot-org/shots/screenshot-267_24-*.png`（主仓路径）。结构：顶栏（标题改「整理」`org_title`）→ Hero 卡（`Estimated reclaimable` + 大数字渐变 + `Across N categories`）→ `Quick tidy up` 主按钮（渐变，进 F2；F2 未接时先 Gone，由 Task 10 点亮）→ `Categories` 分组标题 → 类目卡列表 → 「重复与相似照片」卡（展开=原 `DedupConfigContent` 的尺度勾选/保留规则/开始扫描，收起=单行卡显示上次摘要）→ 隐私 caption `org_privacy_note`（"All analysis runs on-device · your photos never leave this phone"）。

**类目卡**（64px 紧凑行卡，对齐 Ardot）：左图标块（surfaceVariant 圆角方块 + Material Icon）+ 标题/meta（`86 items · 312 MB`）+ 4×30px 缩略图条（Coil，铁律 crossfade(false)）+ chevron。点击 → `onOpenCategory(category)` 回调。

- [ ] **Step 1: DedupViewModel 加统计流**：

```kotlin
// DedupViewModel.kt 追加（构造加 organizeRepository: OrganizeRepository）
val categoryStats: StateFlow<List<CategoryStat>> =
    organizeRepository.observeItems()
        .map { OrganizeCategorizer.stats(it) }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

hub 仅在 Config 态可见，统计流订阅开销由 WhileSubscribed 控制。

- [ ] **Step 2: `DedupConfigContent` 改造**：新 composable `OrganizeHubContent`（同文件或拆 `DedupHomeHub.kt`，>200 行则拆）替换 Config 分支调用；原尺度勾选/保留规则/开始扫描收进「重复与相似照片」展开卡（`var dedupExpanded by remember { mutableStateOf(false) }`）。Hero 数字 = `stats.sumOf { it.totalBytes }` 用 `formatBytes`；类目卡 `stats.forEach`（DUPLICATES 卡固定渲染，显示 dedup 的 `dedup_estimate`/`dedup_never_scanned` 摘要——复用现有 Config Hero 的数据源逻辑）。

- [ ] **Step 3: 入口回调穿透**：`DedupHomeRoute` 新增 `onOpenCategory: (OrganizeCategory) -> Unit`、`onQuickTidy: () -> Unit` 参数 → `MainPagerHost.kt:126-129` 传入 → `MainActivity.kt` 实现（`navController.navigate(Screen.OrganizeCategory.createRoute(category.name))`；`onQuickTidy` 先传 `{}`，Task 10 点亮）。**保持 Box 单根铁律**。

- [ ] **Step 4: 编译 + 真机冒烟**：装到设备，进「相册整理」页确认 hub 渲染、数字非负、dedup 展开卡展开/收起正常、开始扫描流程不回归。

- [ ] **Step 5: Commit** `feat(organize): F1 整理中心 hub（Pager页1 Config 态升级）`

---

### Task 5: F1 strings 五语 + 类目详情页 + 路由

**Files:**
- Modify: `navigation/Screen.kt`（加 `OrganizeCategory`）
- Modify: `MainActivity.kt`（NavHost 注册 + VM factory）
- Modify: `di/AppContainer.kt`（`createOrganizeCategoryViewModelFactory`）
- Create: `features/gallery/organize/OrganizeCategoryScreen.kt`、`OrganizeCategoryViewModel.kt`
- Modify: 五语 strings.xml

**Screen.kt**：

```kotlin
data object OrganizeCategory : Screen("organize_category/{category}") {
    fun createRoute(category: String): String = "organize_category/$category"
}
```

**VM**（结构规格）：构造 `(category: OrganizeCategory, organizeRepository, trashSessionController, ioDispatcher)`；`uiState: StateFlow<OrganizeCategoryUiState>`：

```kotlin
sealed interface OrganizeCategoryUiState {
    data object Loading : OrganizeCategoryUiState
    data class Ready(
        val items: List<OrganizeItem>,
        val selected: Set<String>,          // uri 集合；AI 预选 = 默认全选该类目命中项
        val trashed: Boolean = false,       // 完成态（页内结果视图，对齐 Ardot organize/cleaned）
        val trashedCount: Int = 0,
        val trashedBytes: Long = 0,
        val lastTrashedUris: List<String> = emptyList(), // undo 用
    ) : OrganizeCategoryUiState
}
```

方法：`toggle(uri)` / `selectAll()` / `deselectAll()` / `deleteSelected()`（→ `trashSessionController.requestTrash(uris, tag=category.name)`）/ `undoLastTrash()` / `consumePartialNotice()`。授权回调 `onTrashResult(ok)`：成功→按 outcome.trashedUris 从 items 移除、置 trashed 结果态；残留→partialNotice snackbar（`org_partial_trash`）。

**UI**（视觉基准 `screenshot-267_150-*.png`）：`AppTopBar`（`AppTopBarNavBack` + 类目标题 + 右上 `AI preselect on/off` 文本钮）→ 副标题行 `86 items · 312 MB` / `52 AI-preselected` → `LazyVerticalGrid(3 列)` 缩略图带选中勾（复用 `MediaItem` 的选中语义样式或 `DedupThumb` 的勾选视觉；点按=toggle）→ 底部 CTA `Move to trash N items · Free X MB`（error 色容器，对齐 Ardot 粉）+ caption `Items stay in Trash for 30 days`。完成态（`screenshot-267_280-*.png`）：✅ + `All clean!` + 大数字 + Trash 卡 + `Undo` / `View trash`（View trash v1 = 跳系统相册，可省——隐藏该按钮）。

**UI 授权接线**（与 DedupHomeScreen 同款）：`rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult())` 观察 `trashSessionController.pendingRequest`，`token as IntentSender` 拉起；结果回 `viewModel.onTrashResult(...)`。API < 30（`isSupported=false`）：走 `consumeErrorEvent` → snackbar `org_trash_unsupported`（v1 降级提示，不接入 legacyDeleter——类目删除低版本提示不支持即可，记录在 spec 校准）。

**strings（EN 基准，五语同步，键前缀 `org_`）**：

| 键 | EN |
|---|---|
| org_title | Organize |
| org_hero_label | Estimated reclaimable |
| org_hero_across | Across %1$d categories |
| org_quick_tidy | Quick tidy up |
| org_categories | Categories |
| org_cat_duplicates | Duplicates & similar |
| org_cat_screenshots | Screenshots |
| org_cat_blurry | Blurry & low quality |
| org_cat_portraits | Low-quality portraits |
| org_cat_large_videos | Large videos |
| org_cat_documents | Documents & receipts |
| org_cat_meta | %1$d items · %2$s |
| org_privacy_note | All analysis runs on-device · your photos never leave this phone |
| org_ai_preselect_on / org_ai_preselect_off | AI preselect on / AI preselect off |
| org_ai_preselected | %1$d AI-preselected |
| org_move_to_trash | Move to trash %1$d items · Free %2$s |
| org_trash_note | Items stay in Trash for 30 days |
| org_cleaned_title | All clean! |
| org_cleaned_meta | %1$d items moved to Trash |
| org_cleaned_freed | %1$s freed |
| org_undo | Undo |
| org_partial_trash | Some items need extra permission and were kept |
| org_trash_unsupported | Trash needs Android 11+; please delete from the gallery |
| org_empty_category | Nothing to clean here |

- [ ] **Step 1**: Screen.kt + MainActivity 路由注册（带参 `navArgument("category")`，`OrganizeCategory.valueOf` 解析，非法值 popBackStack）
- [ ] **Step 2**: VM + 单测（选中/全选/取消、删除后列表剔除、undo 还原——`OrganizeCategoryViewModelTest` 用 fake repository + `TrashSessionController` + FakeBackend，TDD）
- [ ] **Step 3**: Screen UI + 五语 strings
- [ ] **Step 4**: 编译 + 真机验证（进 Screenshots 类目：勾选/全选/删除/回收站恢复）
- [ ] **Step 5**: Commit `feat(organize): F1 类目详情批量清理（回收站通路 + AI 预选）`

---

## Part C：F2 手势快速整理

### Task 6: `SwipeQueueBuilder` 纯函数（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/swipe/SwipeModels.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/domain/swipe/SwipeQueueBuilder.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/swipe/SwipeQueueBuilderTest.kt`

**模型**：

```kotlin
package com.mamba.picme.domain.swipe

/** 入列原因（卡片角标 + 可解释性，spec AC-F2-2）。 */
enum class SwipeReason { SCREENSHOT, BLURRY, LOW_QUALITY_PORTRAIT, RECENT }

data class SwipeCandidate(
    val uri: String,
    val sizeBytes: Long,
    val captureDate: Long,
    val reason: SwipeReason,
)
```

**规则**：输入 `List<OrganizeItem>`（复用 F1 模型）→ 输出有序 `List<SwipeCandidate>`：
- 桶 1 SCREENSHOT：OrganizeCategorizer 命中 SCREENSHOTS
- 桶 2 BLURRY：命中 BLURRY
- 桶 3 LOW_QUALITY_PORTRAIT：命中 LOW_QUALITY_PORTRAITS
- 桶 4 RECENT：其余 PHOTO（视频不入队——v1 手势整理只处理照片），按 captureDate 倒序
- 每媒体只进**一个**桶（按桶优先级取首个命中）；桶内按 captureDate 倒序

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeQueueBuilderTest {

    private fun item(
        uri: String, isVideo: Boolean = false, captureDate: Long = 0L,
        relativePath: String? = "DCIM/Camera/",
        hasFace: Boolean = false, aestheticScore: Float? = null, faceQualityScore: Float? = null,
    ) = OrganizeItem(uri, isVideo, captureDate, 1_000_000, relativePath, null, 12_000_000, null, hasFace, aestheticScore, faceQualityScore)

    @Test
    fun `buckets ordered screenshot then blurry then portrait then recent`() {
        val queue = SwipeQueueBuilder.build(listOf(
            item("recent", captureDate = 9),
            item("portrait", hasFace = true, faceQualityScore = 0.1f, captureDate = 8),
            item("blurry", aestheticScore = 1.5f, captureDate = 7),
            item("shot", relativePath = "Pictures/Screenshots/", captureDate = 6),
        ))
        assertEquals(listOf("shot", "blurry", "portrait", "recent"), queue.map { it.uri })
        assertEquals(SwipeReason.SCREENSHOT, queue[0].reason)
        assertEquals(SwipeReason.RECENT, queue[3].reason)
    }

    @Test
    fun `item lands in first matching bucket only`() {
        // 截图且模糊 → 只进 SCREENSHOT 桶
        val queue = SwipeQueueBuilder.build(listOf(
            item("both", relativePath = "Pictures/Screenshots/", aestheticScore = 1.0f),
        ))
        assertEquals(1, queue.size)
        assertEquals(SwipeReason.SCREENSHOT, queue[0].reason)
    }

    @Test
    fun `videos never enqueued`() {
        val queue = SwipeQueueBuilder.build(listOf(item("v", isVideo = true, captureDate = 5)))
        assertEquals(emptyList<SwipeCandidate>(), queue)
    }

    @Test
    fun `within bucket newest first`() {
        val queue = SwipeQueueBuilder.build(listOf(
            item("old", captureDate = 1), item("new", captureDate = 3), item("mid", captureDate = 2),
        ))
        assertEquals(listOf("new", "mid", "old"), queue.map { it.uri })
    }
}
```

- [ ] **Step 2: 确认失败** → `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.swipe.SwipeQueueBuilderTest" --console=plain` 编译失败
- [ ] **Step 3: 实现**（`SwipeQueueBuilder.build(items)`：先 `OrganizeCategorizer.categoriesOf` 打标，再按桶优先级分配排序）
- [ ] **Step 4: 确认通过** → 4 tests PASS
- [ ] **Step 5: Commit** `feat(swipe): F2 队列构建纯函数（废片优先桶）`

---

### Task 7: `SwipeReviewViewModel` 会话逻辑（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/gallery/swipe/SwipeReviewViewModel.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/gallery/swipe/SwipeReviewViewModelTest.kt`

**状态与行为**：

```kotlin
enum class SwipeDecision { KEEP, DELETE, SKIP }

sealed interface SwipeUiState {
    data object Loading : SwipeUiState
    data class Reviewing(
        val queue: List<SwipeCandidate>,
        val index: Int,                          // 当前卡片
        val decisions: List<Pair<String, SwipeDecision>>,  // 决策历史（undo 栈）
        val pendingDeleteBytes: Long,            // 已标记删除的字节合计（顶部「45 MB freed」实时=标记口径）
    ) : SwipeUiState {
        val current: SwipeCandidate? get() = queue.getOrNull(index)
        val freedBytes: Long get() = decisions.filter { it.second == SwipeDecision.DELETE }
            .sumOf { d -> queue.first { it.uri == d.first }.sizeBytes }
    }
    data class Done(
        val kept: Int, val deleted: Int, val skipped: Int, val freedBytes: Long,
        val trashedUris: List<String>,           // undo 用
    ) : SwipeUiState
}
```

- `decide(SwipeDecision)`：记录决策、index+1；DELETE 只标记不即时删（批量收集，spec：结束或满 20 张一次性 `createTrashRequest`——`pendingDeleteUris.size >= 20` 时自动触发 `trashController.requestTrash(batch)`）。
- `undo()`：回退最后决策（index-1、移除历史尾条；若撤销的是 DELETE 且该批已提交回收站，v1 语义：已提交批不可经 undo 回退——undo 只覆盖未提交部分；Done 页 Undo 走 `requestRestore` 整批恢复）。
- 队列空 → `finish()`：若 pendingDelete 非空先触发 trash 授权，授权完成后进 Done；无删除直接 Done。
- 数据源：构造注入 `OrganizeRepository`（loadItems）+ `TrashSessionController`；`SwipeQueueBuilder.build` 产出队列。
- VM 单测覆盖：决策推进、undo 回退、满 20 自动批提交、finish 汇总、授权拒绝后 pendingDelete 保留可重试。

- [ ] **Step 1**: 写失败测试（FakeBackend + fake repository，覆盖上列 5 组行为）
- [ ] **Step 2**: 确认失败
- [ ] **Step 3**: 实现
- [ ] **Step 4**: 确认通过
- [ ] **Step 5**: Commit `feat(swipe): F2 会话 VM（决策/undo/批量回收站提交）`

---

### Task 8: `SwipeReviewScreen` UI + 路由 + 入口接线

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/gallery/swipe/SwipeReviewScreen.kt`
- Modify: `navigation/Screen.kt`（`data object SwipeReview : Screen("swipe_review")`）
- Modify: `MainActivity.kt`（注册 + factory；点亮 Task 4 的 `onQuickTidy = { navController.navigate(Screen.SwipeReview.route) }`）
- Modify: `di/AppContainer.kt`
- Modify: 五语 strings（`swipe_*`）

**UI**（视觉基准 `screenshot-267_312-*.png` / `screenshot-267_329-*.png`）：强制深色页面包 `PoLangForcedDarkTheme`。Reviewing：顶栏（返回 + 中「12 / 86」+「45 MB freed」副行 + 右 undo 图标钮）→ 中央大图卡（Coil 大图 `size(1080)` crossfade(false)，圆角 16，左上角 `reason` 角标 capsule）→ 底部手势 hint 行「← Skip / ↑ Delete / Keep →」（`swipe_hint_skip/delete/keep`，Keep 用 primary 色）。

手势：卡片 `pointerInput` `detectHorizontalDragGestures` + `detectVerticalDragGestures`（或 `anchoredDraggable`）——水平右→KEEP、左→SKIP、上→DELETE，阈值 = 卡片宽 25% 或速度阈值；跟手位移 + 松手动画归位/飞出（`Animatable`）。**点按 = 下一张**（SKIP 语义但不记决策？不——点按 = SKIP 决策，保持队列收敛；spec「点按翻下一张（长辈模式）」）。预加载：Coil `imageLoader.enqueue` 预取 index+1..index+3（[PERF] AC-F2-1）。

Done 页（`screenshot-267_329-*.png`）：✅ 图标 + `Round complete` + 三数字（Kept/Deleted/Skipped，Deleted 用 error 色）+ `Space freed this round X MB` + 主按钮 `One more round`（重建队列）+ 文本钮 `Back to Organize`（popBackStack）。删除已提交时提供 Undo（整批 restore）。

授权接线同 Task 5（`pendingRequest` → StartIntentSenderForResult → `viewModel.onTrashResult`）。

**strings（EN 基准）**：`swipe_title`(Quick tidy) / `swipe_progress`(%1$d / %2$d) / `swipe_freed`(%1$s freed) / `swipe_undo` / `swipe_hint_skip`(← Skip) / `swipe_hint_delete`(↑ Delete) / `swipe_hint_keep`(Keep →) / `swipe_reason_screenshot`(Screenshot) / `swipe_reason_blurry`(Blurry) / `swipe_reason_portrait`(Low-quality portrait) / `swipe_reason_recent`(Recent) / `swipe_done_title`(Round complete) / `swipe_done_subtitle`(Nice pace — your library is lighter already) / `swipe_done_kept`(Kept) / `swipe_done_deleted`(Deleted) / `swipe_done_skipped`(Skipped) / `swipe_done_freed`(Space freed this round %1$s) / `swipe_done_more`(One more round) / `swipe_done_back`(Back to Organize) / `swipe_empty`(Nothing to tidy — nice!)

- [ ] **Step 1**: Screen.kt + MainActivity + AppContainer 接线（先空屏编译通过）
- [ ] **Step 2**: Reviewing/Done 两态 UI + 手势 + 预加载 + strings 五语
- [ ] **Step 3**: 编译 + 真机验证：hub「Quick tidy up」进入、四向手势、undo、20 张自动批授权、Done 统计、One more round
- [ ] **Step 4**: Commit `feat(swipe): F2 手势快速整理全屏页`

---

## Part D：F3 回忆 Memories

### Task 9: `MemoriesGenerator` 纯函数（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/memories/MemoriesModels.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/domain/memories/MemoriesGenerator.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/memories/MemoriesGeneratorTest.kt`

**模型**：

```kotlin
package com.mamba.picme.domain.memories

enum class MemoryType { ON_THIS_DAY, RECENT_HIGHLIGHTS, PERSON, CITY }

data class Memory(
    val id: String,                 // 稳定 id：type + 关键参数（如 "on_this_day:09-05"、"person:personId"）
    val type: MemoryType,
    val title: String,              // 生成时算好的展示文案（英文；UI 层直接显示）
    val subtitle: String,
    val coverUri: String,           // 美学分最高者（无分按 captureDate 最新）
    val itemUris: List<String>,     // 精选，封面在首
)
```

**生成规则**（输入 `List<MediaEntity>` 简化投影 + `persons` 命名映射 + `now: Long` 注入保证确定性；上限：carousel 最多 10 条，详情精选默认 12 张）：

| 类型 | 规则 |
|---|---|
| ON_THIS_DAY | 与 `now` 同月同日、不同年份的照片 ≥4 张 → 1 条；title=`On this day`，subtitle=`Sep 5 · 2023`（最新年份） |
| RECENT_HIGHLIGHTS | 近 30 天 `aestheticScore != null` 照片按分降序取 ≥6 张 → 1 条；title=`Recent highlights` |
| PERSON | `persons` 表 `name != null` 且媒体数 ≥6 的已命名人物，每人 1 条（最多 3 人，按媒体数降序）；title=`Moments with %1$s`（生成时填入名字） |
| CITY | `getCityGroups` 口径：同 city 照片 ≥6 张的城市，最多 2 条；title=city 名 |

排序：ON_THIS_DAY > RECENT_HIGHLIGHTS > PERSON > CITY；`coverUri`/`itemUris` 按（aestheticScore 降序，null 排后，再 captureDate 降序）精选，详情 12 张截断。

- [ ] **Step 1: 写失败测试**（确定性夹具：固定 `now`；覆盖——不足 4 张不生成 On this day、跨年聚组、未命名人物不生成、城市阈值、精选排序与截断、总条数上限 10、空库返回空列表）
- [ ] **Step 2: 确认失败**
- [ ] **Step 3: 实现**（纯 Kotlin，日期用 `java.util.Calendar`/`java.time`，注入 `Clock` 或 now 参数）
- [ ] **Step 4: 确认通过**
- [ ] **Step 5: Commit** `feat(memories): F3 回忆生成纯函数`

---

### Task 10: `MemoriesViewModel` + MediaGrid header 槽 + Gallery 接线

**Files:**
- Create: `features/gallery/memories/MemoriesViewModel.kt`
- Modify: `features/gallery/components/MediaGrid.kt`（加 header 槽）
- Modify: `features/gallery/GalleryScreen.kt`（collect + 传槽 + 点击导航）
- Modify: `di/AppContainer.kt`、`MainActivity.kt`（VM 提供）

**VM**：构造 `(mediaDao, personDao, memoriesGenerator 依赖, hiddenStore: DataStore, ioDispatcher)`；`memories: StateFlow<List<Memory>>`：

```kotlin
val memories: StateFlow<List<Memory>> =
    combine(mediaDao.getAllMedia(), personDao.observePersons(), hiddenIdsFlow) { media, persons, hidden ->
        MemoriesGenerator.generate(media, persons, now = System.currentTimeMillis())
            .filter { it.id !in hidden }
    }.flowOn(ioDispatcher)
     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

fun hideMemory(id: String)  // DataStore stringSet 持久化
```

> `mediaDao.getAllMedia()` 是全表 Flow（现有方法），媒体库任何变更自动重算——满足「每日首次进入重算」（VM Activity 级重建即重算）且免 migration。若全表开销实测不可接受，后续换轻量投影（本计划接受现状复用）。

**MediaGrid header 槽**：

```kotlin
// MediaGrid.kt 签名追加可选参数
header: (LazyGridItemScope.() -> Unit)? = null,
// groupedMedia.forEach 之前：
if (header != null) {
    item(key = "memories_header", span = { GridItemSpan(maxLineSpan) }) { header() }
}
```

**GalleryScreen 接线**：`MemoriesViewModel` 由 `MainActivity` 以 Activity 级 `viewModel(factory=...)` 创建（同 DedupViewModel 模式）传入 GalleryScreen；`val memories by memoriesViewModel.memories.collectAsStateWithLifecycle()`；header 槽内容 = 新 composable `MemoriesCarousel(memories, onMemoryClick = { navController.navigate(Screen.MemoryDetail.createRoute(it.id)) }, onHide = memoriesViewModel::hideMemory)`；`memories.isEmpty()` 时传 `header = null`（AC-F3-2 不占位）。

**MemoriesCarousel**（视觉基准 `screenshot-267_356-*.png`；规格参考 `ChatCarouselTokens`）：标题行 `Memories` + 右侧 `On-device · private` caption（`memory_privacy_note`）→ `LazyRow` 卡片 120×150 r12：封面图（Coil 铁律）+ 底部渐变蒙层 + title/subtitle 白字；长按 → `Hide this memory` 确认（`memory_hide` / `memory_hide_confirm`）。

- [ ] **Step 1**: VM + DataStore 隐藏持久化 + 单测（hidden 过滤）
- [ ] **Step 2**: MediaGrid header 槽（不影响现有调用方——默认参数 null）
- [ ] **Step 3**: MemoriesCarousel + GalleryScreen 接线
- [ ] **Step 4**: 编译 + 真机验证（有/无回忆两种数据态）
- [ ] **Step 5**: Commit `feat(memories): F3 相册首页回忆 carousel`

---

### Task 11: 回忆详情页 + 路由 + 分享 + strings

**Files:**
- Create: `features/gallery/memories/MemoryDetailScreen.kt`
- Modify: `navigation/Screen.kt`（`MemoryDetail : Screen("memory_detail/{memoryId}")` + createRoute URL 编码）
- Modify: `MainActivity.kt`（注册；由 `MemoriesViewModel` 提供 `getMemory(id)`）
- Modify: 五语 strings（`memory_*`）

**UI**（视觉基准 `screenshot-267_407-*.png`）：`AppTopBar`（返回 + `Memories` + 右分享图标钮）→ 顶部封面大图（16:9 裁切）叠标题 `On this day · 2023` + 副行 `12 best shots · Sep 5 across years` → 3 列精选网格 → 底部主按钮 `Share memory`（`Intent.ACTION_SEND_MULTIPLE` 复用 `MediaViewModel` 既有分享工具方法模式，URI 授权 flag 必备）。

**strings（EN 基准）**：`memory_title`(Memories) / `memory_privacy_note`(On-device · private) / `memory_on_this_day`(On this day) / `memory_recent_highlights`(Recent highlights) / `memory_person_title`(Moments with %1$s) / `memory_best_shots`(%1$d best shots) / `memory_across_years`(%1$s across years) / `memory_share`(Share memory) / `memory_hide`(Hide this memory) / `memory_hide_confirm`(Hide this memory? You can re-enable memories anytime.) / `memory_detail_empty`(This memory is no longer available)

- [ ] **Step 1**: Screen.kt + MainActivity 注册 + `MemoriesViewModel.getMemory(id)`（从当前 memories 值查；详情页数据源 = VM 内存态，被杀后重进由 generate 重算，id 稳定可复原）
- [ ] **Step 2**: MemoryDetailScreen UI + 分享 + strings 五语
- [ ] **Step 3**: 编译 + 真机验证（carousel 点入、网格、分享调起系统分享）
- [ ] **Step 4**: Commit `feat(memories): F3 回忆详情页与分享`

---

## Part E：收口

### Task 12: 文档同步 + 全量验证

- [ ] **Step 1**: 更新 `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md`：
  - §2.4 入口描述补「整理中心 hub」（Config 态升级、类目卡、Quick tidy 入口）
  - 新增 §2.12「整理中心与手势整理」（OrganizeRepository/OrganizeCategorizer/SwipeQueueBuilder/TrashSessionController 架构说明、类目阈值常量表、API<30 降级口径）
  - 新增 §2.13「回忆 Memories」（生成规则、header 槽、隐藏持久化、v1 不落表说明）
  - 检查清单补 4 条（crossfade(false) / Box 单根 / IS_TRASHED 复查 / null 分排除）
- [ ] **Step 2**: 更新 `docs/superpowers/specs/2026-09-05-gallery-organization-5-features-design.md`：F1/F2/F3 状态→已落地（Android），补 v1 校准注记（F3 不落表、闭眼=faceQualityScore 代理、F2 无连拍桶、Hero 并集不去重、API<30 trash 降级）
- [ ] **Step 3**: `./gradlew :androidApp:assembleDebug --console=plain` + 全量单测 `./gradlew :androidApp:testDebugUnitTest --console=plain` 全绿
- [ ] **Step 4**: 真机闭环：安装 → 三 feature 主路径各走一遍（hub 统计/类目删除/undo；swipe 一轮+回收站恢复；回忆 carousel/详情/分享/隐藏）→ 截图留档 `tmp/organize-verify/`
- [ ] **Step 5**: Commit `docs(gallery): 整理套件 F1/F2/F3 文档同步与验收`

---

## Self-Review 记录（写计划时已执行）

- **Spec 覆盖**：F1 hub（Task 4）/类目详情（Task 5）/回收站通路（Task 1）；F2 队列（Task 6）/会话（Task 7）/UI（Task 8）；F3 生成（Task 9）/carousel（Task 10）/详情分享（Task 11）。F4/F5 明确不在范围。✅
- **v1 偏离 spec 处**（均在计划头「V1 明确不做」声明，Task 12 回填 spec）：F3 Room 表→内存态+DataStore 隐藏；AC-F3-3 设置页隐藏列表→不做；闭眼→代理分；F2 连拍桶→不做；Hero 并集去重→不做；F1 未打标引导卡→不做（2026-09-05 审查回填，spec 已加校准注记）。
- **类型一致性**：`TrashSessionController`（Task 1）被 Task 5/7 注入使用；`OrganizeItem`（Task 2）被 Task 3/6 复用；`SwipeCandidate/SwipeReason`（Task 6）被 Task 7/8 复用；`Memory`（Task 9）被 Task 10/11 复用。✅
- **风险点**：① Task 3 的 MediaStore meta 全量查询在大库上的耗时——hub 统计流首次发射可能 >1s（AC-F1-3 风险），缓解：首帧先渲染骨架、统计异步填充；② `mediaDao.getAllMedia()` 全表用于 memories 生成——9000 张级别内存可承受（DedupMediaSource 现状同样全量过 allMedia），实测关注。
