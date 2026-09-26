# 用户任务协议 M1 实现计划（TAG 扫描 + 模型下载接入 + 任务中心双 Tab）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/superpowers/specs/2026-09-26-user-task-protocol-design.md` M1——统一用户任务协议（`UserTask` 模型 + `UserTaskRegistry` 混合注册表 + 适配器），TAG 扫描与模型下载首批接入，任务中心扩展为「工程师任务 | 后台任务」双 Tab，顶栏角标合并计数。

**Architecture:** 统一发生在协议层不在引擎层：适配器订阅体系既有状态流（`TagGenerationService.sessionProgress` / `LlmModelDownloadManager.downloadStates`）翻译为 `UserTask` 写入注册表；注册表 = Room `user_task` 表（身份，仅状态迁移落库）+ 内存进度流（高频刷新）+ 合并流对 UI；UI 只依赖注册表单一接口。

**Tech Stack:** Kotlin / Jetpack Compose / Room（AppDatabase v23→24）/ kotlinx-coroutines / JUnit4 JVM 单测（fake DAO/control，无 Robolectric）。

**Worktree:** `/Users/guoshuai/AndroidStudioProjects/polang/.worktrees/task-center`，分支 `feat/task-center`。所有命令在该目录执行。

**关键既有契约（已核实，行号为当前 worktree）：**
- `TagGenerationService.sessionProgress`: 静态 `StateFlow<TagScanSessionProgress?>`（经 `StartTagScanUseCase.kt:91` 证实可读）
- `TagScanSessionProgress`（`domain/tag/scan/TagScanSessionProgress.kt`）：sessionId/state/currentPass/processed/total/pending/failed/estimatedRemainingMs；`ScanSessionState` = IDLE/RUNNING/PAUSING/PAUSED/CANCELLING/CANCELLED/COMPLETED
- `StartTagScanUseCase(context)`：`suspend operator fun invoke(action: String, ...)`，action ∈ start/pause/resume/cancel/query
- `LlmModelDownloadManager`（`data/download/LlmModelDownloadManager.kt`）：`val downloadStates: StateFlow<Map<String, DownloadState>>`（:213-214）；`DownloadState(modelId, status, downloadedBytes, totalBytes)`；`DownloadStatus` = PENDING/DOWNLOADING/PAUSED/COMPLETED/FAILED/CANCELLED（:1600-1612）；`fun pauseDownload(modelId)`（:749）、`fun resumeDownload(modelId, modelConfig=null): Flow<DownloadProgress>`（:772，冷流需 collect）、`fun cancelDownload(modelId)`（:585）、`fun downloadModel(modelId, modelConfig=null): Flow<DownloadProgress>`（:938，冷流需 collect）
- `AppDatabase`（`data/local/AppDatabase.kt`）：version 23，entities 列表 :38-58，migrations 注册 :91-99，最新 `MIGRATION_22_23`（:489）
- `AppContainer`（`di/AppContainer.kt`）：`database`、`llmModelDownloadManager`（:664）、`startTagScanUseCase`（:743，private）、`createTaskCenterViewModelFactory()`（:933）；`TaskCenterViewModelFactory`（:255，构造参数 chatMessageDao/chatSessionDao）
- `TaskCenterViewModel`（`features/chat/taskcenter/TaskCenterViewModel.kt`）：`taskList: StateFlow<TaskCenterList?>`
- `TaskCenterScreen`（同包，337 行）：`TaskCenterScreen(taskCenterViewModel, chatViewModel, onNavigateBack, onOpenTaskInChat)`，Scaffold+AppTopBar+LazyColumn 双分区
- ChatScreen 顶栏角标：`ChatScreen` 内 collect `activeEngineerTaskCount`（:263），传入顶栏 BadgedBox（:1006-1028），角标参数名 `activeTaskCount`
- MainActivity：`composable(Screen.TaskCenter.route)` 块 :757-774；`organizeTabRequest` 一次性请求态 :215；`switchMainPage(index)` :220-226；整理页 = `MAIN_PAGE_DEDUP`(=1，`features/main/MainPagerHost.kt:38`)，`OrganizeTab.SCAN`；模型中心路由 `Screen.ModelCenter.createRoute(categoryTag)`（`navigation/Screen.kt:66`，空串 = "model_center/"）
- 主页面承载：`MainPagerHost`（`features/main/MainPagerHost.kt`），ChatScreen 在其内被调用

---

## 文件结构

| 文件 | 责任 | 新建/修改 |
|---|---|---|
| `domain/usertask/UserTask.kt` | 协议模型：UserTask/枚举/TaskProgressSnapshot | 新建 |
| `domain/usertask/UserTaskMapping.kt` | 纯函数：状态映射×2 + 能力集推导 + isActive + destinationFor | 新建 |
| `data/local/entity/UserTaskEntity.kt` | Room 实体 user_task | 新建 |
| `data/local/dao/UserTaskDao.kt` | upsert/observeAll/getById/activeIdsOfKind/trimHistory | 新建 |
| `data/local/AppDatabase.kt` | 注册实体+DAO+version 24+MIGRATION_23_24 | 修改 |
| `domain/usertask/UserTaskRegistry.kt` | 混合注册表：Room⋈内存合并流/activeCount/perform 分发 | 新建 |
| `domain/usertask/UserTaskAdapter.kt` | 适配器接口 | 新建 |
| `domain/usertask/TagScanTaskAdapter.kt` | TAG 扫描适配器（含 TagScanControl fun interface） | 新建 |
| `domain/usertask/ModelDownloadTaskAdapter.kt` | 下载适配器 + 启动对账（含 ModelDownloadControl 接口） | 新建 |
| `di/AppContainer.kt` | registry/适配器/控制接口实现/工厂改参/startUserTaskAdapters | 修改 |
| `PoLangApplication.kt` | onCreate 调 startUserTaskAdapters | 修改 |
| `features/chat/taskcenter/TaskCenterViewModel.kt` | +userTasks 流 +performUserTaskAction | 修改 |
| `features/chat/taskcenter/UserTaskCard.kt` | 用户任务卡片 Composable | 新建 |
| `features/chat/taskcenter/TaskCenterScreen.kt` | 双 Tab 改造（工程师 Tab 抽函数原样） | 修改 |
| `features/chat/ChatScreen.kt` | +activeUserTaskCount 参数并入角标 | 修改 |
| `features/main/MainPagerHost.kt` | 透传 activeUserTaskCount | 修改 |
| `MainActivity.kt` | 角标数据源 + onOpenUserTaskDestination 导航 | 修改 |
| `res/values[-es/-fr/-zh-rCN/-zh-rTW]/strings.xml` | 新增文案五语 | 修改 |
| `androidApp/src/test/.../usertask/*Test.kt` | 映射/注册表/适配器 JVM 单测 | 新建 |

---

## Task 1: 协议模型 + 状态映射纯函数（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/usertask/UserTask.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/domain/usertask/UserTaskMapping.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/usertask/UserTaskMappingTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.domain.tag.scan.ScanSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTaskMappingTest {

    @Test
    fun `tag scan 会话态映射全分支`() {
        assertNull(UserTaskMapping.fromTagScanState(ScanSessionState.IDLE))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromTagScanState(ScanSessionState.RUNNING))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromTagScanState(ScanSessionState.PAUSING))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromTagScanState(ScanSessionState.CANCELLING))
        assertEquals(UserTaskStatus.PAUSED, UserTaskMapping.fromTagScanState(ScanSessionState.PAUSED))
        assertEquals(UserTaskStatus.COMPLETED, UserTaskMapping.fromTagScanState(ScanSessionState.COMPLETED))
        assertEquals(UserTaskStatus.CANCELLED, UserTaskMapping.fromTagScanState(ScanSessionState.CANCELLED))
    }

    @Test
    fun `下载状态 1 比 1 映射`() {
        assertEquals(UserTaskStatus.PENDING, UserTaskMapping.fromDownloadStatus(DownloadStatus.PENDING))
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromDownloadStatus(DownloadStatus.DOWNLOADING))
        assertEquals(UserTaskStatus.PAUSED, UserTaskMapping.fromDownloadStatus(DownloadStatus.PAUSED))
        assertEquals(UserTaskStatus.COMPLETED, UserTaskMapping.fromDownloadStatus(DownloadStatus.COMPLETED))
        assertEquals(UserTaskStatus.FAILED, UserTaskMapping.fromDownloadStatus(DownloadStatus.FAILED))
        assertEquals(UserTaskStatus.CANCELLED, UserTaskMapping.fromDownloadStatus(DownloadStatus.CANCELLED))
    }

    @Test
    fun `能力集推导矩阵`() {
        assertEquals(setOf(UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.PENDING))
        assertEquals(setOf(UserTaskAction.PAUSE, UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.RUNNING))
        assertEquals(setOf(UserTaskAction.RESUME, UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.PAUSED))
        assertEquals(setOf(UserTaskAction.RETRY), UserTaskMapping.actionsFor(UserTaskStatus.FAILED))
        assertTrue(UserTaskMapping.actionsFor(UserTaskStatus.COMPLETED).isEmpty())
        assertTrue(UserTaskMapping.actionsFor(UserTaskStatus.CANCELLED).isEmpty())
    }

    @Test
    fun `活动态判据与 destination 推导`() {
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.RUNNING))
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.PAUSED))
        assertTrue(!UserTaskMapping.isActive(UserTaskStatus.COMPLETED))
        assertEquals(UserTaskDestination.TAG_SCAN_CONTROL, UserTaskMapping.destinationFor(UserTaskKind.TAG_SCAN))
        assertEquals(UserTaskDestination.MODEL_CENTER, UserTaskMapping.destinationFor(UserTaskKind.MODEL_DOWNLOAD))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.UserTaskMappingTest"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现模型与映射**

`UserTask.kt`:

```kotlin
package com.mamba.picme.domain.usertask

/** 用户任务协议模型（spec 2026-09-26-user-task-protocol-design.md §4）。 */
data class UserTask(
    val id: String,
    val kind: UserTaskKind,
    /** 体系自带名字（如下载的 modelId），原样展示不参与 i18n；null 时 UI 按 kind 取 string resource */
    val displayName: String?,
    val status: UserTaskStatus,
    val progress: Float?,
    val progressText: String?,
    val etaMs: Long?,
    val errorCode: UserTaskErrorCode?,
    val errorDetail: String?,
    val supportedActions: Set<UserTaskAction>,
    val destination: UserTaskDestination,
    val updatedAt: Long,
)

enum class UserTaskKind { TAG_SCAN, MODEL_DOWNLOAD }
enum class UserTaskStatus { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class UserTaskAction { PAUSE, RESUME, CANCEL, RETRY }
enum class UserTaskErrorCode { PROCESS_TERMINATED, PARTIAL_FAILURES }
enum class UserTaskDestination { TAG_SCAN_CONTROL, MODEL_CENTER }

/** 高频进度快照（仅内存，不落库——spec §5 混合注册表）。 */
data class TaskProgressSnapshot(
    val progress: Float?,
    val progressText: String?,
    val etaMs: Long?,
)
```

`UserTaskMapping.kt`:

```kotlin
package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.domain.tag.scan.ScanSessionState

/** 体系状态 → 协议状态的纯映射（spec §4.1/§4.2）；UI 零逻辑，全部可单测。 */
object UserTaskMapping {

    /** TAG 扫描会话 7 态 → 协议 6 态；IDLE = 无任务（返回 null 不发射）。 */
    fun fromTagScanState(state: ScanSessionState): UserTaskStatus? = when (state) {
        ScanSessionState.IDLE -> null
        ScanSessionState.RUNNING,
        ScanSessionState.PAUSING,
        ScanSessionState.CANCELLING -> UserTaskStatus.RUNNING
        ScanSessionState.PAUSED -> UserTaskStatus.PAUSED
        ScanSessionState.COMPLETED -> UserTaskStatus.COMPLETED
        ScanSessionState.CANCELLED -> UserTaskStatus.CANCELLED
    }

    fun fromDownloadStatus(status: DownloadStatus): UserTaskStatus = when (status) {
        DownloadStatus.PENDING -> UserTaskStatus.PENDING
        DownloadStatus.DOWNLOADING -> UserTaskStatus.RUNNING
        DownloadStatus.PAUSED -> UserTaskStatus.PAUSED
        DownloadStatus.COMPLETED -> UserTaskStatus.COMPLETED
        DownloadStatus.FAILED -> UserTaskStatus.FAILED
        DownloadStatus.CANCELLED -> UserTaskStatus.CANCELLED
    }

    fun actionsFor(status: UserTaskStatus): Set<UserTaskAction> = when (status) {
        UserTaskStatus.PENDING -> setOf(UserTaskAction.CANCEL)
        UserTaskStatus.RUNNING -> setOf(UserTaskAction.PAUSE, UserTaskAction.CANCEL)
        UserTaskStatus.PAUSED -> setOf(UserTaskAction.RESUME, UserTaskAction.CANCEL)
        UserTaskStatus.FAILED -> setOf(UserTaskAction.RETRY)
        UserTaskStatus.COMPLETED, UserTaskStatus.CANCELLED -> emptySet()
    }

    fun isActive(status: UserTaskStatus): Boolean =
        status == UserTaskStatus.PENDING || status == UserTaskStatus.RUNNING || status == UserTaskStatus.PAUSED

    fun destinationFor(kind: UserTaskKind): UserTaskDestination = when (kind) {
        UserTaskKind.TAG_SCAN -> UserTaskDestination.TAG_SCAN_CONTROL
        UserTaskKind.MODEL_DOWNLOAD -> UserTaskDestination.MODEL_CENTER
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.UserTaskMappingTest"`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/usertask/ androidApp/src/test/java/com/mamba/picme/domain/usertask/
git commit -m "feat(usertask): 协议模型 + 状态映射纯函数（spec §4）"
```

---

## Task 2: Room 实体 + DAO + 迁移 23→24

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/data/local/entity/UserTaskEntity.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/data/local/dao/UserTaskDao.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`

- [ ] **Step 1: 实体与 DAO**

`UserTaskEntity.kt`:

```kotlin
package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户任务身份行（spec §5：元数据落 Room，进度走内存）。
 * 静态文案不入库（I18N 红线）——标题由 UI 按 kind 取 string resource。
 */
@Entity(tableName = "user_task")
data class UserTaskEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val displayName: String?,
    val status: String,
    val errorCode: String?,
    val errorDetail: String?,
    val destination: String,
    val updatedAt: Long,
    val completedAt: Long?,
)
```

`UserTaskDao.kt`:

```kotlin
package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: UserTaskEntity)

    @Query("SELECT * FROM user_task ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<UserTaskEntity>>

    @Query("SELECT * FROM user_task WHERE id = :id")
    suspend fun getById(id: String): UserTaskEntity?

    @Query("SELECT id FROM user_task WHERE kind = :kind AND status IN ('PENDING', 'RUNNING', 'PAUSED')")
    suspend fun activeIdsOfKind(kind: String): List<String>

    /** 历史封顶：仅保留最近 [keep] 条终态任务（spec §5 历史语义）。 */
    @Query(
        "DELETE FROM user_task WHERE id IN (" +
            "SELECT id FROM user_task WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
            "ORDER BY completedAt DESC LIMIT -1 OFFSET :keep)"
    )
    suspend fun trimHistory(keep: Int)
}
```

- [ ] **Step 2: AppDatabase 注册 + 迁移**

修改 `AppDatabase.kt`：

1. import 追加 `com.mamba.picme.data.local.dao.UserTaskDao` 与 `com.mamba.picme.data.local.entity.UserTaskEntity`
2. entities 列表末尾（`DedupHashEntity::class` 后）加 `,\n        UserTaskEntity::class`
3. `version = 23` → `version = 24`
4. 抽象方法加 `abstract fun userTaskDao(): UserTaskDao`
5. addMigrations 列表末尾 `MIGRATION_22_23` 后加 `, MIGRATION_23_24`
6. companion 内 `MIGRATION_22_23` 之后追加：

```kotlin
        /**
         * Migration 23 → 24：新增 user_task 表（用户任务注册表身份行，spec §5）。
         */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_task` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `kind` TEXT NOT NULL,
                        `displayName` TEXT,
                        `status` TEXT NOT NULL,
                        `errorCode` TEXT,
                        `errorDetail` TEXT,
                        `destination` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（Room 注解处理通过即表结构合法）

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/local/
git commit -m "feat(usertask): user_task Room 表 + DAO + 迁移 23→24（spec §5）"
```

---

## Task 3: UserTaskRegistry 混合注册表（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/usertask/UserTaskAdapter.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/domain/usertask/UserTaskRegistry.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/usertask/UserTaskRegistryTest.kt`

- [ ] **Step 1: 适配器接口（先建，测试要引用）**

`UserTaskAdapter.kt`:

```kotlin
package com.mamba.picme.domain.usertask

/** 体系与协议之间的唯一接缝（spec §6）：订阅体系状态 → 翻译进注册表；统一动词 → 体系原生 API。 */
interface UserTaskAdapter {
    val kind: UserTaskKind

    /** 开始订阅体系状态流（含启动对账）。由注册表在 registerAdapter 时调用一次。 */
    fun start()

    /** 统一动词分发。失败不乐观更新——状态以体系为准，体系流会纠正注册表。 */
    suspend fun perform(taskId: String, action: UserTaskAction)
}
```

- [ ] **Step 2: 写失败测试**

```kotlin
package com.mamba.picme.domain.usertask

import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserTaskRegistryTest {

    private class FakeDao : UserTaskDao {
        val rows = MutableStateFlow<List<UserTaskEntity>>(emptyList())
        var trimCalls = 0
            private set

        override suspend fun upsert(task: UserTaskEntity) {
            rows.value = rows.value.filterNot { it.id == task.id } + task
        }

        override fun observeAll(): Flow<List<UserTaskEntity>> = rows

        override suspend fun getById(id: String): UserTaskEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun activeIdsOfKind(kind: String): List<String> =
            rows.value.filter { it.kind == kind && it.status in setOf("PENDING", "RUNNING", "PAUSED") }
                .map { it.id }

        override suspend fun trimHistory(keep: Int) {
            trimCalls++
            val terminal = rows.value.filter { it.status in setOf("COMPLETED", "FAILED", "CANCELLED") }
                .sortedByDescending { it.completedAt ?: 0L }
            val surplus = terminal.drop(keep).map { it.id }.toSet()
            rows.value = rows.value.filterNot { it.id in surplus }
        }
    }

    private class FakeAdapter(override val kind: UserTaskKind) : UserTaskAdapter {
        val performed = mutableListOf<Pair<String, UserTaskAction>>()
        var started = false
            private set

        override fun start() {
            started = true
        }

        override suspend fun perform(taskId: String, action: UserTaskAction) {
            performed += taskId to action
        }
    }

    private fun registry(dao: FakeDao, scope: kotlinx.coroutines.CoroutineScope) =
        UserTaskRegistry(dao = dao, scope = scope, clock = { 1000L })

    @Test
    fun `upsertStatus 落库且 tasks 合并内存进度`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, backgroundScope)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        registry.updateProgress("tagscan:main", TaskProgressSnapshot(0.5f, "10/20", 3000L))

        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertEquals(0.5f, task.progress)
        assertEquals("10/20", task.progressText)
        assertEquals(3000L, task.etaMs)
        assertEquals(setOf(UserTaskAction.PAUSE, UserTaskAction.CANCEL), task.supportedActions)
        assertEquals(UserTaskDestination.TAG_SCAN_CONTROL, task.destination)
    }

    @Test
    fun `activeCount 只计活动态`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, backgroundScope)
        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.RUNNING)
        registry.upsertStatus("download:b", UserTaskKind.MODEL_DOWNLOAD, "b", UserTaskStatus.PAUSED)
        registry.upsertStatus("download:c", UserTaskKind.MODEL_DOWNLOAD, "c", UserTaskStatus.COMPLETED)
        assertEquals(2, registry.activeCount.value)
    }

    @Test
    fun `终态 upsert 触发历史修剪且封顶 50`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, backgroundScope)
        repeat(55) { i ->
            registry.upsertStatus("download:m$i", UserTaskKind.MODEL_DOWNLOAD, "m$i", UserTaskStatus.COMPLETED)
        }
        assertTrue(dao.trimCalls >= 55)
        assertEquals(50, registry.tasks.value.size)
    }

    @Test
    fun `perform 按 kind 分发到对应适配器`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, backgroundScope)
        val tagAdapter = FakeAdapter(UserTaskKind.TAG_SCAN)
        val downloadAdapter = FakeAdapter(UserTaskKind.MODEL_DOWNLOAD)
        registry.registerAdapter(tagAdapter)
        registry.registerAdapter(downloadAdapter)
        assertTrue(tagAdapter.started && downloadAdapter.started)

        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.RUNNING)
        registry.perform("download:a", UserTaskAction.PAUSE)

        assertEquals(listOf("download:a" to UserTaskAction.PAUSE), downloadAdapter.performed)
        assertTrue(tagAdapter.performed.isEmpty())
    }

    @Test
    fun `perform 未知任务静默返回`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, backgroundScope)
        registry.registerAdapter(FakeAdapter(UserTaskKind.TAG_SCAN))
        registry.perform("ghost", UserTaskAction.PAUSE) // 不抛异常即通过
    }

    @Test
    fun `进度快照清除后回落为 null`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, backgroundScope)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        registry.updateProgress("tagscan:main", TaskProgressSnapshot(0.3f, "3/10", null))
        registry.updateProgress("tagscan:main", null)
        assertNull(registry.tasks.value.single().progress)
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.UserTaskRegistryTest"`
Expected: 编译失败（UserTaskRegistry 不存在）

- [ ] **Step 4: 实现注册表**

`UserTaskRegistry.kt`:

```kotlin
package com.mamba.picme.domain.usertask

import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * 用户任务混合注册表（spec §5）：Room 存身份（仅状态迁移落库）+ 内存走进度 + 合并流对 UI。
 * UI 只依赖本类的 tasks/activeCount，不感知背后体系（Agent First：显式优于隐式）。
 */
class UserTaskRegistry(
    private val dao: UserTaskDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _progress = MutableStateFlow<Map<String, TaskProgressSnapshot>>(emptyMap())
    private val adapters = mutableMapOf<UserTaskKind, UserTaskAdapter>()

    val tasks: StateFlow<List<UserTask>> =
        combine(dao.observeAll(), _progress) { rows, snapshots ->
            rows.map { row -> row.toUserTask(snapshots[row.id]) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeCount: StateFlow<Int> =
        tasks.map { list -> list.count { UserTaskMapping.isActive(it.status) } }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    fun registerAdapter(adapter: UserTaskAdapter) {
        adapters[adapter.kind] = adapter
        adapter.start()
    }

    /** 统一动词分发：按任务 kind 路由到对应适配器；未知任务/未知 kind 静默返回（spec §9-1）。 */
    suspend fun perform(taskId: String, action: UserTaskAction) {
        val row = dao.getById(taskId) ?: return
        val kind = runCatching { UserTaskKind.valueOf(row.kind) }.getOrNull() ?: return
        adapters[kind]?.perform(taskId, action)
    }

    /** 状态迁移（落 Room；终态顺带修剪历史）。errorCode/errorDetail 仅在异常语义时传。 */
    suspend fun upsertStatus(
        id: String,
        kind: UserTaskKind,
        displayName: String?,
        status: UserTaskStatus,
        errorCode: UserTaskErrorCode? = null,
        errorDetail: String? = null,
    ) {
        val now = clock()
        dao.upsert(
            UserTaskEntity(
                id = id,
                kind = kind.name,
                displayName = displayName,
                status = status.name,
                errorCode = errorCode?.name,
                errorDetail = errorDetail,
                destination = UserTaskMapping.destinationFor(kind).name,
                updatedAt = now,
                completedAt = if (UserTaskMapping.isActive(status)) null else now,
            )
        )
        if (!UserTaskMapping.isActive(status)) dao.trimHistory(HISTORY_KEEP)
    }

    /** 高频进度（仅内存）；传 null 清除快照。 */
    fun updateProgress(taskId: String, snapshot: TaskProgressSnapshot?) {
        _progress.update { current ->
            if (snapshot == null) current - taskId else current + (taskId to snapshot)
        }
    }

    /** 适配器对账用：注册表中某 kind 的活动态任务 id。 */
    suspend fun activeIdsOfKind(kind: UserTaskKind): List<String> = dao.activeIdsOfKind(kind.name)

    /** 适配器对账用：注册表中某任务的当前状态（无行返回 null）。 */
    suspend fun currentStatus(taskId: String): UserTaskStatus? =
        dao.getById(taskId)?.let { row ->
            runCatching { UserTaskStatus.valueOf(row.status) }.getOrNull()
        }

    private fun UserTaskEntity.toUserTask(snapshot: TaskProgressSnapshot?): UserTask {
        val kind = UserTaskKind.valueOf(kind)
        val status = UserTaskStatus.valueOf(status)
        return UserTask(
            id = id,
            kind = kind,
            displayName = displayName,
            status = status,
            progress = snapshot?.progress,
            progressText = snapshot?.progressText,
            etaMs = snapshot?.etaMs,
            errorCode = errorCode?.let { runCatching { UserTaskErrorCode.valueOf(it) }.getOrNull() },
            errorDetail = errorDetail,
            supportedActions = UserTaskMapping.actionsFor(status),
            destination = UserTaskMapping.destinationFor(kind),
            updatedAt = updatedAt,
        )
    }

    companion object {
        const val HISTORY_KEEP = 50
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.*"`
Expected: 10 tests PASS（mapping 4 + registry 6）

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/usertask/ androidApp/src/test/java/com/mamba/picme/domain/usertask/
git commit -m "feat(usertask): UserTaskRegistry 混合注册表（Room⋈内存合并流 + 动词分发 + 历史封顶）"
```

---

## Task 4: TagScanTaskAdapter（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/usertask/TagScanTaskAdapter.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/usertask/TagScanTaskAdapterTest.kt`

**设计要点**：`perform` 经 `TagScanControl` fun interface 转发（`StartTagScanUseCase` 依赖 Context 无法在 JVM 单测构造，收口为接口）；`sync` 逻辑 @VisibleForTesting 直接可测。IDLE/null 语义：注册表残留活动态行 → 置 CANCELLED(PROCESS_TERMINATED)（进程被杀于扫描中的对账）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.usertask

import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagScanTaskAdapterTest {

    private class FakeDao : UserTaskDao {
        val rows = MutableStateFlow<List<UserTaskEntity>>(emptyList())
        override suspend fun upsert(task: UserTaskEntity) {
            rows.value = rows.value.filterNot { it.id == task.id } + task
        }
        override fun observeAll(): Flow<List<UserTaskEntity>> = rows
        override suspend fun getById(id: String): UserTaskEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun activeIdsOfKind(kind: String): List<String> =
            rows.value.filter { it.kind == kind && it.status in setOf("PENDING", "RUNNING", "PAUSED") }.map { it.id }
        override suspend fun trimHistory(keep: Int) = Unit
    }

    private class FakeControl : TagScanControl {
        val actions = mutableListOf<String>()
        override suspend fun invoke(action: String) {
            actions += action
        }
    }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Triple<UserTaskRegistry, TagScanTaskAdapter, FakeControl> {
        val registry = UserTaskRegistry(dao = FakeDao(), scope = scope, clock = { 1000L })
        val control = FakeControl()
        val adapter = TagScanTaskAdapter(registry = registry, control = control, scope = scope)
        return Triple(registry, adapter, control)
    }

    private fun progress(state: ScanSessionState, processed: Int = 0, total: Int = 0, failed: Int = 0, eta: Long? = null) =
        TagScanSessionProgress(
            sessionId = "s1", state = state, processed = processed, total = total,
            pending = total - processed, failed = failed, estimatedRemainingMs = eta,
        )

    @Test
    fun `RUNNING 会话同步为活动任务含进度`() = runTest {
        val (registry, adapter, _) = fixture(backgroundScope)
        adapter.sync(progress(ScanSessionState.RUNNING, processed = 10, total = 20, eta = 5000L))
        val task = registry.tasks.value.single()
        assertEquals("tagscan:main", task.id)
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertEquals(0.5f, task.progress)
        assertEquals("10/20", task.progressText)
        assertEquals(5000L, task.etaMs)
    }

    @Test
    fun `COMPLETED 含失败任务时挂 PARTIAL_FAILURES`() = runTest {
        val (registry, adapter, _) = fixture(backgroundScope)
        adapter.sync(progress(ScanSessionState.COMPLETED, processed = 18, total = 20, failed = 2))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.COMPLETED, task.status)
        assertEquals(UserTaskErrorCode.PARTIAL_FAILURES, task.errorCode)
        assertEquals("2", task.errorDetail)
    }

    @Test
    fun `COMPLETED 零失败时无错误码`() = runTest {
        val (registry, adapter, _) = fixture(backgroundScope)
        adapter.sync(progress(ScanSessionState.COMPLETED, processed = 20, total = 20, failed = 0))
        assertNull(registry.tasks.value.single().errorCode)
    }

    @Test
    fun `null 进度且注册表残留活动态行时置 CANCELLED 对账`() = runTest {
        val (registry, adapter, _) = fixture(backgroundScope)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        adapter.sync(null)
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.CANCELLED, task.status)
        assertEquals(UserTaskErrorCode.PROCESS_TERMINATED, task.errorCode)
        assertNull(task.progress)
    }

    @Test
    fun `null 进度且注册表无行时不产生任务`() = runTest {
        val (registry, adapter, _) = fixture(backgroundScope)
        adapter.sync(null)
        assertTrue(registry.tasks.value.isEmpty())
    }

    @Test
    fun `统一动词转发为扫描控制动作`() = runTest {
        val (_, adapter, control) = fixture(backgroundScope)
        adapter.perform("tagscan:main", UserTaskAction.PAUSE)
        adapter.perform("tagscan:main", UserTaskAction.RESUME)
        adapter.perform("tagscan:main", UserTaskAction.CANCEL)
        adapter.perform("tagscan:main", UserTaskAction.RETRY)
        assertEquals(listOf("pause", "resume", "cancel", "start"), control.actions)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.TagScanTaskAdapterTest"`
Expected: 编译失败

- [ ] **Step 3: 实现适配器**

`TagScanTaskAdapter.kt`:

```kotlin
package com.mamba.picme.domain.usertask

import androidx.annotation.VisibleForTesting
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** TAG 扫描控制动词收口（StartTagScanUseCase 依赖 Context 无法在 JVM 单测构造，故收为接口）。 */
fun interface TagScanControl {
    /** action ∈ start / pause / resume / cancel（对齐 StartTagScanUseCase 协议）。 */
    suspend operator fun invoke(action: String)
}

/**
 * TAG 扫描适配器（spec §6）：整个扫描会话 = 1 个 UserTask（id 固定 tagscan:main）。
 * 订阅 Service 静态进度流；动词经 [TagScanControl] 转发。引擎零侵入。
 */
class TagScanTaskAdapter(
    private val registry: UserTaskRegistry,
    private val control: TagScanControl,
    private val scope: CoroutineScope,
) : UserTaskAdapter {

    override val kind: UserTaskKind = UserTaskKind.TAG_SCAN

    override fun start() {
        scope.launch {
            TagGenerationService.sessionProgress.collect { progress -> sync(progress) }
        }
    }

    @VisibleForTesting
    internal suspend fun sync(progress: TagScanSessionProgress?) {
        val status = progress?.let { UserTaskMapping.fromTagScanState(it.state) }
        if (status == null) {
            // IDLE / 无会话：注册表残留活动态行 = 进程被杀于扫描中 → 对账置 CANCELLED
            val current = registry.currentStatus(TASK_ID)
            if (current != null && UserTaskMapping.isActive(current)) {
                registry.upsertStatus(
                    id = TASK_ID, kind = kind, displayName = null,
                    status = UserTaskStatus.CANCELLED,
                    errorCode = UserTaskErrorCode.PROCESS_TERMINATED,
                )
            }
            registry.updateProgress(TASK_ID, null)
            return
        }
        val partialFailures =
            status == UserTaskStatus.COMPLETED && progress.failed > 0
        registry.upsertStatus(
            id = TASK_ID,
            kind = kind,
            displayName = null,
            status = status,
            errorCode = if (partialFailures) UserTaskErrorCode.PARTIAL_FAILURES else null,
            errorDetail = if (partialFailures) progress.failed.toString() else null,
        )
        registry.updateProgress(
            TASK_ID,
            TaskProgressSnapshot(
                progress = if (progress.total > 0) progress.processed / progress.total.toFloat() else null,
                progressText = "${progress.processed}/${progress.total}",
                etaMs = progress.estimatedRemainingMs,
            )
        )
    }

    override suspend fun perform(taskId: String, action: UserTaskAction) {
        when (action) {
            UserTaskAction.PAUSE -> control("pause")
            UserTaskAction.RESUME -> control("resume")
            UserTaskAction.CANCEL -> control("cancel")
            // 会话级无 FAILED 态，RETRY 只出现在进程被杀对账后 → 重起增量扫描
            UserTaskAction.RETRY -> control("start")
        }
    }

    companion object {
        const val TASK_ID = "tagscan:main"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.TagScanTaskAdapterTest"`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/usertask/TagScanTaskAdapter.kt androidApp/src/test/java/com/mamba/picme/domain/usertask/TagScanTaskAdapterTest.kt
git commit -m "feat(usertask): TagScanTaskAdapter——扫描会话映射 + IDLE 对账 + 动词转发"
```

---

## Task 5: ModelDownloadTaskAdapter + 启动对账（TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/usertask/ModelDownloadTaskAdapter.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/usertask/ModelDownloadTaskAdapterTest.kt`

**设计要点**：`ModelDownloadControl` 接口收口 manager（resume/retry 的冷流 collect 由实现侧在应用 scope 内启动，适配器只发动词）；启动对账 = Room 活动态行但 downloadStates 无对应活体 → FAILED(PROCESS_TERMINATED)。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadTaskAdapterTest {

    private class FakeDao : UserTaskDao {
        val rows = MutableStateFlow<List<UserTaskEntity>>(emptyList())
        override suspend fun upsert(task: UserTaskEntity) {
            rows.value = rows.value.filterNot { it.id == task.id } + task
        }
        override fun observeAll(): Flow<List<UserTaskEntity>> = rows
        override suspend fun getById(id: String): UserTaskEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun activeIdsOfKind(kind: String): List<String> =
            rows.value.filter { it.kind == kind && it.status in setOf("PENDING", "RUNNING", "PAUSED") }.map { it.id }
        override suspend fun trimHistory(keep: Int) = Unit
    }

    private class FakeControl : ModelDownloadControl {
        override val downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
        val calls = mutableListOf<Pair<String, String>>() // verb to modelId
        override fun pause(modelId: String) { calls += "pause" to modelId }
        override fun resume(modelId: String) { calls += "resume" to modelId }
        override fun cancel(modelId: String) { calls += "cancel" to modelId }
        override fun retry(modelId: String) { calls += "retry" to modelId }
    }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Triple<UserTaskRegistry, ModelDownloadTaskAdapter, FakeControl> {
        val registry = UserTaskRegistry(dao = FakeDao(), scope = scope, clock = { 1000L })
        val control = FakeControl()
        val adapter = ModelDownloadTaskAdapter(registry = registry, control = control, scope = scope)
        return Triple(registry, adapter, control)
    }

    @Test
    fun `下载中状态同步为多任务含字节进度`() = runTest {
        val (registry, adapter, _) = fixture(backgroundScope)
        adapter.sync(
            mapOf(
                "m1" to DownloadState("m1", DownloadStatus.DOWNLOADING, 10_000_000L, 40_000_000L),
                "m2" to DownloadState("m2", DownloadStatus.PAUSED, 5_000_000L, 10_000_000L),
            )
        )
        val tasks = registry.tasks.value.sortedBy { it.id }
        assertEquals(2, tasks.size)
        assertEquals("download:m1", tasks[0].id)
        assertEquals("m1", tasks[0].displayName)
        assertEquals(UserTaskStatus.RUNNING, tasks[0].status)
        assertEquals(0.25f, tasks[0].progress)
        assertEquals(UserTaskStatus.PAUSED, tasks[1].status)
    }

    @Test
    fun `启动对账——活动态行无活体下载置 FAILED`() = runTest {
        val (registry, adapter, control) = fixture(backgroundScope)
        registry.upsertStatus("download:ghost", UserTaskKind.MODEL_DOWNLOAD, "ghost", UserTaskStatus.RUNNING)
        registry.upsertStatus("download:done", UserTaskKind.MODEL_DOWNLOAD, "done", UserTaskStatus.COMPLETED)
        control.downloadStates.value = mapOf(
            "live" to DownloadState("live", DownloadStatus.DOWNLOADING, 0L, 100L),
        )
        adapter.reconcile()
        val tasks = registry.tasks.value.associateBy { it.id }
        assertEquals(UserTaskStatus.FAILED, tasks.getValue("download:ghost").status)
        assertEquals(UserTaskErrorCode.PROCESS_TERMINATED, tasks.getValue("download:ghost").errorCode)
        assertEquals(UserTaskStatus.COMPLETED, tasks.getValue("download:done").status)
        assertEquals(UserTaskStatus.RUNNING, tasks.getValue("download:live").status)
    }

    @Test
    fun `统一动词按 modelId 转发`() = runTest {
        val (_, adapter, control) = fixture(backgroundScope)
        adapter.perform("download:m1", UserTaskAction.PAUSE)
        adapter.perform("download:m1", UserTaskAction.RESUME)
        adapter.perform("download:m1", UserTaskAction.CANCEL)
        adapter.perform("download:m1", UserTaskAction.RETRY)
        assertEquals(
            listOf("pause" to "m1", "resume" to "m1", "cancel" to "m1", "retry" to "m1"),
            control.calls,
        )
    }

    @Test
    fun `终态任务清除进度快照`() = runTest {
        val (registry, adapter, _) = fixture(backgroundScope)
        adapter.sync(mapOf("m1" to DownloadState("m1", DownloadStatus.DOWNLOADING, 5L, 10L)))
        adapter.sync(mapOf("m1" to DownloadState("m1", DownloadStatus.COMPLETED, 10L, 10L)))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.COMPLETED, task.status)
        assertEquals(null, task.progress)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.ModelDownloadTaskAdapterTest"`
Expected: 编译失败

- [ ] **Step 3: 实现适配器**

`ModelDownloadTaskAdapter.kt`:

```kotlin
package com.mamba.picme.domain.usertask

import androidx.annotation.VisibleForTesting
import com.mamba.picme.data.download.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 模型下载控制收口（LlmModelDownloadManager 依赖 Context 无法在 JVM 单测构造）。
 * resume/retry 的冷流 collect 由实现侧在应用 scope 内启动，适配器只发动词。
 */
interface ModelDownloadControl {
    val downloadStates: StateFlow<Map<String, DownloadState>>
    fun pause(modelId: String)
    fun resume(modelId: String)
    fun cancel(modelId: String)
    fun retry(modelId: String)
}

/**
 * 模型下载适配器（spec §6）：每个 modelId = 1 个 UserTask（并发多任务）。
 * 附带收益：状态迁移落注册表 + 启动对账，下载体系首次获得进程重启可见性。
 */
class ModelDownloadTaskAdapter(
    private val registry: UserTaskRegistry,
    private val control: ModelDownloadControl,
    private val scope: CoroutineScope,
) : UserTaskAdapter {

    override val kind: UserTaskKind = UserTaskKind.MODEL_DOWNLOAD

    override fun start() {
        scope.launch { reconcile() }
        scope.launch {
            control.downloadStates.collect { states -> sync(states) }
        }
    }

    /** 启动对账（spec §5）：Room 活动态行但无活体下载 → FAILED(PROCESS_TERMINATED)，可 RETRY。 */
    @VisibleForTesting
    internal suspend fun reconcile() {
        val liveIds = control.downloadStates.value.keys.map { taskId(it) }.toSet()
        for (rowId in registry.activeIdsOfKind(kind)) {
            if (rowId !in liveIds) {
                registry.upsertStatus(
                    id = rowId, kind = kind,
                    displayName = modelId(rowId),
                    status = UserTaskStatus.FAILED,
                    errorCode = UserTaskErrorCode.PROCESS_TERMINATED,
                )
                registry.updateProgress(rowId, null)
            }
        }
        // 活体下载补登记（进程重启后 manager 重新收集到状态时也会经 sync 覆盖，此处先行对齐）
        sync(control.downloadStates.value)
    }

    @VisibleForTesting
    internal suspend fun sync(states: Map<String, DownloadState>) {
        for ((id, state) in states) {
            val status = UserTaskMapping.fromDownloadStatus(state.status)
            registry.upsertStatus(
                id = taskId(id),
                kind = kind,
                displayName = id,
                status = status,
            )
            if (UserTaskMapping.isActive(status)) {
                registry.updateProgress(
                    taskId(id),
                    TaskProgressSnapshot(
                        progress = if (state.totalBytes > 0) {
                            state.downloadedBytes / state.totalBytes.toFloat()
                        } else {
                            null
                        },
                        progressText = "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                        etaMs = null,
                    )
                )
            } else {
                registry.updateProgress(taskId(id), null)
            }
        }
    }

    override suspend fun perform(taskId: String, action: UserTaskAction) {
        when (action) {
            UserTaskAction.PAUSE -> control.pause(modelId(taskId))
            UserTaskAction.RESUME -> control.resume(modelId(taskId))
            UserTaskAction.CANCEL -> control.cancel(modelId(taskId))
            UserTaskAction.RETRY -> control.retry(modelId(taskId))
        }
    }

    private fun taskId(modelId: String): String = "download:$modelId"
    private fun modelId(taskId: String): String = taskId.removePrefix("download:")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toFloat())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toFloat())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / (1L shl 10).toFloat())
        else -> "$bytes B"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.*"`
Expected: 20 tests PASS（累计）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/usertask/ModelDownloadTaskAdapter.kt androidApp/src/test/java/com/mamba/picme/domain/usertask/ModelDownloadTaskAdapterTest.kt
git commit -m "feat(usertask): ModelDownloadTaskAdapter——并发多任务映射 + 启动对账（下载首获重启可见性）"
```

---

## Task 6: AppContainer 组合根接线 + Application 启动

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt`

- [ ] **Step 1: AppContainer 加注册表与适配器**

`AppContainer` 接口（文件上部 interface 区，参照 `llmModelDownloadManager` 声明处 :277）加：

```kotlin
    /** 用户任务注册表（spec §5）：任务中心后台任务 Tab 与顶栏角标的数据源 */
    val userTaskRegistry: UserTaskRegistry
```

实现类内（参照 `llmModelDownloadManager` 实现 :664 附近）加：

```kotlin
    /** 应用级协程作用域：注册表合并流与适配器订阅的生命周期宿主（进程级单例，不随页面销毁） */
    private val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override val userTaskRegistry: UserTaskRegistry by lazy {
        UserTaskRegistry(dao = database.userTaskDao(), scope = applicationScope)
    }

    /**
     * 启动用户任务适配器（spec §6 组合根接线）。
     * 幂等：重复调用不会重复注册（注册表以 kind 为键覆盖前先移除旧适配器——
     * 本实现只注册一次，由 Application.onCreate 单点调用保证）。
     */
    fun startUserTaskAdapters() {
        userTaskRegistry.registerAdapter(
            TagScanTaskAdapter(
                registry = userTaskRegistry,
                control = TagScanControl { action -> startTagScanUseCase(action) },
                scope = applicationScope,
            )
        )
        userTaskRegistry.registerAdapter(
            ModelDownloadTaskAdapter(
                registry = userTaskRegistry,
                control = object : ModelDownloadControl {
                    override val downloadStates: StateFlow<Map<String, DownloadState>>
                        get() = llmModelDownloadManager.downloadStates

                    override fun pause(modelId: String) = llmModelDownloadManager.pauseDownload(modelId)

                    override fun resume(modelId: String) {
                        applicationScope.launch { llmModelDownloadManager.resumeDownload(modelId).collect {} }
                    }

                    override fun cancel(modelId: String) = llmModelDownloadManager.cancelDownload(modelId)

                    override fun retry(modelId: String) {
                        applicationScope.launch { llmModelDownloadManager.downloadModel(modelId).collect {} }
                    }
                },
                scope = applicationScope,
            )
        )
    }
```

import 追加：

```kotlin
import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.domain.usertask.ModelDownloadControl
import com.mamba.picme.domain.usertask.ModelDownloadTaskAdapter
import com.mamba.picme.domain.usertask.TagScanControl
import com.mamba.picme.domain.usertask.TagScanTaskAdapter
import com.mamba.picme.domain.usertask.UserTaskRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
```

（`Dispatchers`/`launch`/`StateFlow` 若已 import 则跳过重复。）

- [ ] **Step 2: Application.onCreate 调用**

`PoLangApplication.kt` onCreate 中，在既有组合根初始化之后（找 `container` 首次使用处，如 `TagGenerationService` 或 `RecommendedModelAutoDownloader` 触发点附近）加：

```kotlin
        // 用户任务适配器（spec §6）：注册表 + TAG 扫描/模型下载接入任务中心
        container.startUserTaskAdapters()
```

注意：`startUserTaskAdapters` 不在 AppContainer 接口上（实现类专有方法），若 `container` 静态类型是接口，需在接口加声明或此处用实现类引用——按 PoLangApplication 内 container 的实际静态类型选择（实现类可直接调用）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt androidApp/src/main/java/com/mamba/picme/PoLangApplication.kt
git commit -m "feat(usertask): 组合根接线——registry 单例 + 双适配器注册 + Application 启动"
```

---

## Task 7: TaskCenterViewModel 双 Tab 数据源

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/TaskCenterViewModel.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt`（TaskCenterViewModelFactory :255）

- [ ] **Step 1: VM 加用户任务流**

`TaskCenterViewModel.kt` 改为：

```kotlin
class TaskCenterViewModel(
    chatMessageDao: ChatMessageDao,
    chatSessionDao: ChatSessionDao,
    private val userTaskRegistry: UserTaskRegistry,
) : ViewModel() {

    /** 工程师任务（US-12~16）：null = 首查未回（防空态闪现）。 */
    val taskList: StateFlow<TaskCenterList?> = /* 既有实现原样保留 */

    /** 用户任务（后台任务 Tab 数据源；注册表合并流，天然 Room 驱动实时刷新）。 */
    val userTasks: StateFlow<List<UserTask>> = userTaskRegistry.tasks

    /** 用户任务活动计数（默认 Tab 落位与角标共用判据）。 */
    val activeUserTaskCount: StateFlow<Int> = userTaskRegistry.activeCount

    /** 统一动词入口：失败不乐观更新，状态以体系流纠正（spec §9-1）。 */
    fun performUserTaskAction(taskId: String, action: UserTaskAction) {
        viewModelScope.launch { userTaskRegistry.perform(taskId, action) }
    }
}
```

import 追加 `com.mamba.picme.domain.usertask.*`、`kotlinx.coroutines.launch`。

- [ ] **Step 2: 工厂改参**

`AppContainer.kt` 的 `TaskCenterViewModelFactory`（:255）：

```kotlin
class TaskCenterViewModelFactory(
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val userTaskRegistry: UserTaskRegistry,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskCenterViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TaskCenterViewModel(
                chatMessageDao = chatMessageDao,
                chatSessionDao = chatSessionDao,
                userTaskRegistry = userTaskRegistry,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
```

`createTaskCenterViewModelFactory()`（:933）调用处加参 `userTaskRegistry = userTaskRegistry`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/TaskCenterViewModel.kt androidApp/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "feat(usertask): TaskCenterViewModel 接入注册表——userTasks 流 + 统一动词入口"
```

---

## Task 8: UserTaskCard + 任务中心双 Tab UI + 五语 i18n

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/UserTaskCard.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/TaskCenterScreen.kt`
- Modify: `androidApp/src/main/res/values/strings.xml`（及 `-zh-rCN`/`-zh-rTW`/`-es`/`-fr` 四份）

- [ ] **Step 1: 五语文案（先加，UI 引用）**

values/strings.xml 追加（英文）：

```xml
    <string name="task_center_tab_engineer">Engineer</string>
    <string name="task_center_tab_background">Background</string>
    <string name="task_center_empty_user_tasks">No background tasks</string>
    <string name="user_task_title_tag_scan">Gallery tag scan</string>
    <string name="user_task_status_pending">Pending</string>
    <string name="user_task_status_running">Running</string>
    <string name="user_task_status_paused">Paused</string>
    <string name="user_task_status_completed">Completed</string>
    <string name="user_task_status_failed">Failed</string>
    <string name="user_task_status_cancelled">Cancelled</string>
    <string name="user_task_action_pause">Pause</string>
    <string name="user_task_action_resume">Resume</string>
    <string name="user_task_action_cancel">Cancel</string>
    <string name="user_task_action_retry">Retry</string>
    <string name="user_task_error_process_terminated">Interrupted — tap Retry to restart</string>
    <string name="user_task_error_partial_failures">%1$s items failed</string>
```

values-zh-rCN：

```xml
    <string name="task_center_tab_engineer">工程师任务</string>
    <string name="task_center_tab_background">后台任务</string>
    <string name="task_center_empty_user_tasks">暂无后台任务</string>
    <string name="user_task_title_tag_scan">相册标签扫描</string>
    <string name="user_task_status_pending">等待中</string>
    <string name="user_task_status_running">进行中</string>
    <string name="user_task_status_paused">已暂停</string>
    <string name="user_task_status_completed">已完成</string>
    <string name="user_task_status_failed">失败</string>
    <string name="user_task_status_cancelled">已取消</string>
    <string name="user_task_action_pause">暂停</string>
    <string name="user_task_action_resume">继续</string>
    <string name="user_task_action_cancel">取消</string>
    <string name="user_task_action_retry">重试</string>
    <string name="user_task_error_process_terminated">已中断——点重试重新开始</string>
    <string name="user_task_error_partial_failures">%1$s 项失败</string>
```

values-zh-rTW：同 zh-rCN 转繁体（「相册标签扫描」→「相簿標籤掃描」、「已中断——点重试重新开始」→「已中斷——點重試重新開始」、余类推）。

values-es：

```xml
    <string name="task_center_tab_engineer">Ingeniero</string>
    <string name="task_center_tab_background">En segundo plano</string>
    <string name="task_center_empty_user_tasks">Sin tareas en segundo plano</string>
    <string name="user_task_title_tag_scan">Escaneo de etiquetas</string>
    <string name="user_task_status_pending">Pendiente</string>
    <string name="user_task_status_running">En curso</string>
    <string name="user_task_status_paused">En pausa</string>
    <string name="user_task_status_completed">Completada</string>
    <string name="user_task_status_failed">Fallida</string>
    <string name="user_task_status_cancelled">Cancelada</string>
    <string name="user_task_action_pause">Pausar</string>
    <string name="user_task_action_resume">Reanudar</string>
    <string name="user_task_action_cancel">Cancelar</string>
    <string name="user_task_action_retry">Reintentar</string>
    <string name="user_task_error_process_terminated">Interrumpida — toca Reintentar</string>
    <string name="user_task_error_partial_failures">%1$s elementos fallaron</string>
```

values-fr：

```xml
    <string name="task_center_tab_engineer">Ingénieur</string>
    <string name="task_center_tab_background">Arrière-plan</string>
    <string name="task_center_empty_user_tasks">Aucune tâche en arrière-plan</string>
    <string name="user_task_title_tag_scan">Analyse des tags</string>
    <string name="user_task_status_pending">En attente</string>
    <string name="user_task_status_running">En cours</string>
    <string name="user_task_status_paused">En pause</string>
    <string name="user_task_status_completed">Terminée</string>
    <string name="user_task_status_failed">Échouée</string>
    <string name="user_task_status_cancelled">Annulée</string>
    <string name="user_task_action_pause">Pause</string>
    <string name="user_task_action_resume">Reprendre</string>
    <string name="user_task_action_cancel">Annuler</string>
    <string name="user_task_action_retry">Réessayer</string>
    <string name="user_task_error_process_terminated">Interrompue — touchez Réessayer</string>
    <string name="user_task_error_partial_failures">%1$s éléments en échec</string>
```

- [ ] **Step 2: UserTaskCard 组件**

`UserTaskCard.kt`：

```kotlin
package com.mamba.picme.features.chat.taskcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.usertask.UserTask
import com.mamba.picme.domain.usertask.UserTaskAction
import com.mamba.picme.domain.usertask.UserTaskErrorCode
import com.mamba.picme.domain.usertask.UserTaskKind
import com.mamba.picme.domain.usertask.UserTaskStatus

/**
 * 用户任务卡片（spec §7）：原生 Compose 列表项（照引 HTML 卡 spec §7/§15——
 * 任务中心多卡并存，WebView 实例成本不划算）。
 * 动作按钮按 task.supportedActions 声明式渲染，无体系 if-else。
 */
@Composable
fun UserTaskCard(
    task: UserTask,
    onAction: (UserTaskAction) -> Unit,
    onOpenDestination: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpenDestination,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.displayName ?: stringResource(
                        when (task.kind) {
                            UserTaskKind.TAG_SCAN -> R.string.user_task_title_tag_scan
                            UserTaskKind.MODEL_DOWNLOAD -> R.string.user_task_title_tag_scan // 不会走到：下载必有 displayName
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(statusTextRes(task.status)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (UserTaskMapping_isActive(task.status)) {
                Spacer(modifier = Modifier.height(8.dp))
                val progress = task.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                task.progressText?.let { text ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = text, style = MaterialTheme.typography.bodySmall)
                }
            }
            val errorText = when (task.errorCode) {
                UserTaskErrorCode.PROCESS_TERMINATED -> stringResource(R.string.user_task_error_process_terminated)
                UserTaskErrorCode.PARTIAL_FAILURES ->
                    stringResource(R.string.user_task_error_partial_failures, task.errorDetail ?: "")
                null -> null
            }
            errorText?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (task.supportedActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    for (action in task.supportedActions.sortedBy { it.ordinal }) {
                        TextButton(onClick = { onAction(action) }) {
                            Text(stringResource(actionTextRes(action)))
                        }
                    }
                }
            }
        }
    }
}

private fun statusTextRes(status: UserTaskStatus): Int = when (status) {
    UserTaskStatus.PENDING -> R.string.user_task_status_pending
    UserTaskStatus.RUNNING -> R.string.user_task_status_running
    UserTaskStatus.PAUSED -> R.string.user_task_status_paused
    UserTaskStatus.COMPLETED -> R.string.user_task_status_completed
    UserTaskStatus.FAILED -> R.string.user_task_status_failed
    UserTaskStatus.CANCELLED -> R.string.user_task_status_cancelled
}

private fun actionTextRes(action: UserTaskAction): Int = when (action) {
    UserTaskAction.PAUSE -> R.string.user_task_action_pause
    UserTaskAction.RESUME -> R.string.user_task_action_resume
    UserTaskAction.CANCEL -> R.string.user_task_action_cancel
    UserTaskAction.RETRY -> R.string.user_task_action_retry
}
```

注意：上面 `UserTaskMapping_isActive` 是占位写法——实现时直接 `UserTaskMapping.isActive(task.status)`（import `com.mamba.picme.domain.usertask.UserTaskMapping`）。`Card(onClick=...)` 需 `androidx.compose.material3.Card` 的 clickable 重载（material3 自带）；若工程 material3 版本无 onClick 重载，退化为 `Card(modifier = modifier.fillMaxWidth().clickable(onClick = onOpenDestination), ...)`。下载卡片标题直接用 `task.displayName`（modelId）。

- [ ] **Step 3: TaskCenterScreen 双 Tab 改造**

结构改动（保持工程师 Tab 内容原样，抽为私有 composable）：

1. 签名加参：`onOpenUserTaskDestination: (UserTaskDestination) -> Unit`
2. 函数体前部加：

```kotlin
    val userTasks by taskCenterViewModel.userTasks.collectAsStateWithLifecycle()
    val activeUserTaskCount by taskCenterViewModel.activeUserTaskCount.collectAsStateWithLifecycle()
    // 默认 Tab：工程师有活跃 → 工程师；否则后台有活跃 → 后台；均无 → 工程师（现状兼容，spec §7）
    val defaultTab = remember(taskList, activeUserTaskCount) {
        if ((taskList?.active?.size ?: 0) > 0) 0 else if (activeUserTaskCount > 0) 1 else 0
    }
    var selectedTab by rememberSaveable { mutableStateOf(defaultTab) }
```

3. Scaffold 内容改为 Column：顶部 `TabRow(selectedTabIndex = selectedTab)` 两个 `Tab`（text = `task_center_tab_engineer` / `task_center_tab_background`），下方按 tab 渲染：
   - tab 0：既有 `when { list == null -> Unit; empty -> TaskCenterEmpty; else -> LazyColumn … }` 整块原样（抽 `EngineerTaskTab` 或直接内联保持）
   - tab 1：`UserTaskTab`：

```kotlin
@Composable
private fun UserTaskTab(
    userTasks: List<UserTask>,
    onAction: (String, UserTaskAction) -> Unit,
    onOpenDestination: (UserTaskDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (active, history) = userTasks.partition { UserTaskMapping.isActive(it.status) }
    if (active.isEmpty() && history.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.task_center_empty_user_tasks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (active.isNotEmpty()) {
            item(key = "user_header_active") {
                TaskCenterSectionHeader(stringResource(R.string.task_center_section_active))
            }
            items(active, key = { "user_active_${it.id}" }) { task ->
                UserTaskCard(
                    task = task,
                    onAction = { action -> onAction(task.id, action) },
                    onOpenDestination = { onOpenDestination(task.destination) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (history.isNotEmpty()) {
            item(key = "user_header_history") {
                TaskCenterSectionHeader(stringResource(R.string.task_center_section_history))
            }
            items(history, key = { "user_history_${it.id}" }) { task ->
                UserTaskCard(
                    task = task,
                    onAction = { action -> onAction(task.id, action) },
                    onOpenDestination = { onOpenDestination(task.destination) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
```

（复用既有 `TaskCenterSectionHeader`；`task_center_section_history` 字符串在工程师任务中心已存在——实现时核实资源名，若不同则用既有名。）

- [ ] **Step 4: 编译 + 全量相关单测回归**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.*" --tests "com.mamba.picme.features.chat.engineer.*"`
Expected: BUILD SUCCESSFUL + 全 PASS

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/taskcenter/ androidApp/src/main/res/
git commit -m "feat(usertask): 任务中心双 Tab——UserTaskCard + 后台任务分区列表 + 五语 i18n"
```

---

## Task 9: MainActivity 导航接线 + 顶栏角标合并

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/MainActivity.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt`

- [ ] **Step 1: 任务中心路由块加 destination 导航**

`MainActivity.kt` :757-774 `composable(Screen.TaskCenter.route)` 块，`TaskCenterScreen(...)` 调用加参：

```kotlin
                                    onOpenUserTaskDestination = { destination ->
                                        when (destination) {
                                            UserTaskDestination.TAG_SCAN_CONTROL -> {
                                                organizeTabRequest = OrganizeTab.SCAN
                                                switchMainPage(MAIN_PAGE_DEDUP)
                                            }
                                            UserTaskDestination.MODEL_CENTER -> navController.navigate(
                                                Screen.ModelCenter.createRoute(""),
                                                navOptions { launchSingleTop = true }
                                            )
                                        }
                                    }
```

import 追加 `com.mamba.picme.domain.usertask.UserTaskDestination`（`MAIN_PAGE_DEDUP` import 已存在 :83；`OrganizeTab` 已存在 :66）。

- [ ] **Step 2: ChatScreen 角标合并**

`ChatScreen.kt`：
1. 签名加参（默认值保证其他调用点不破）：`activeUserTaskCount: Int = 0,`
2. 顶栏调用处角标入参由 `activeTaskCount = activeEngineerTaskCount` 改为 `activeTaskCount = activeEngineerTaskCount + activeUserTaskCount`（找 `activeTaskCount` 实参传递处；cd_task_center_active 与 99+ 逻辑自动继承）

`MainPagerHost.kt`：签名加参 `activeUserTaskCount: Int = 0,`，内部调用 ChatScreen 处透传 `activeUserTaskCount = activeUserTaskCount`。

`MainActivity.kt`：MainPagerHost 调用前部加：

```kotlin
                    val activeUserTaskCount by app.container.userTaskRegistry.activeCount
                        .collectAsStateWithLifecycle()
```

MainPagerHost 调用加实参 `activeUserTaskCount = activeUserTaskCount`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/MainActivity.kt androidApp/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt
git commit -m "feat(usertask): destination 导航（扫描控制页/模型中心）+ 顶栏角标合并计数"
```

---

## Task 10: 全量回归 + 文档同步

**Files:**
- Modify: `androidApp/AGENTS.md`（§2 路由表 TaskCenter 行 + §3 架构说明 Chat 行）
- Modify: `AGENTS.md`（§7 用户任务 spec 行状态）

- [ ] **Step 1: 全量相关单测回归**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.usertask.*" --tests "com.mamba.picme.features.chat.engineer.*" --tests "com.mamba.picme.features.chat.ChatViewModelEngineerTaskTest" --tests "com.mamba.picme.features.chat.ChatViewModelClaudeSidTest" --tests "com.mamba.picme.features.chat.engineer.EngineerTaskSidTest"`
Expected: 全 PASS

- [ ] **Step 2: 文档同步（DOC-SYNC 红线）**

1. `androidApp/AGENTS.md` §2 路由表 TaskCenter 行：追加「双 Tab（工程师任务 | 后台任务，2026-09-26 用户任务协议 M1）；后台 Tab 数据源 = `UserTaskRegistry`（`user_task` 表 + 内存进度合并流），适配器 TAG 扫描/模型下载经组合根 `startUserTaskAdapters()` 注册」
2. `androidApp/AGENTS.md` §3 架构说明 Chat 行或合适章节：补 `domain/usertask/` 包说明（协议模型/映射/注册表/双适配器，引擎零侵入）
3. 根 `AGENTS.md` §7 用户任务 spec 行：状态「已定稿待实施」→「M1 已落地（2026-09-26）」
4. `data/AGENTS.md` 无需改（无新规范，仅新表）

- [ ] **Step 3: 编译 + Commit**

```bash
./gradlew :androidApp:compileDebugKotlin -q
git add androidApp/AGENTS.md AGENTS.md
git commit -m "docs(usertask): M1 落地同步——AGENTS 双级 + spec 状态"
```

---

## Self-Review 记录（写计划时执行）

**Spec 覆盖核对**：
- §4 模型/映射/能力集 → Task 1 ✅
- §5 注册表（Room 身份/内存进度/合并流/历史 50/启动对账） → Task 2/3/5 ✅
- §6 适配器接口 + 两适配器 + 组合根 → Task 3/4/5/6 ✅
- §7 双 Tab/卡片原生/角标合并/默认 Tab/i18n → Task 8/9 ✅
- §9 错误处理（不乐观更新/进程死亡/写库失败降级） → Task 3 perform 静默、Task 4/5 对账 ✅（§9-5 写库失败降级未单列任务——upsertStatus 失败将沿协程异常通道上抛至 adapter 的 collect，scope 为 SupervisorJob 不会拖垮其他适配器；UI 仅丢重启可见性，符合 spec 语义，不加码）
- §12 测试决策（映射纯函数/Registry fake/VM 分区/回归/冒烟） → Task 1/3/4/5/10；真机冒烟在计划外由人工/主 agent 收尾执行（计划 Task 10 Step 4 提示）
- 排除项（WorkManager/引擎内部/通知/Agent 联动/iOS） → 计划中零触碰 ✅

**类型一致性**：`UserTaskMapping.fromTagScanState/fromDownloadStatus/actionsFor/isActive/destinationFor`、`UserTaskRegistry.upsertStatus/updateProgress/perform/activeIdsOfKind/currentStatus/registerAdapter/tasks/activeCount`、`TagScanTaskAdapter.sync/perform/TASK_ID`、`ModelDownloadTaskAdapter.sync/reconcile/perform`、`TagScanControl`/`ModelDownloadControl` 全链路已交叉核对一致。`UserTaskCard` 中 `UserTaskMapping_isActive` 占位已在步骤内注明替换为 `UserTaskMapping.isActive`。

**已知实施期决策点**（不算 placeholder，是计划内的环境适配）：
- material3 `Card(onClick=)` 重载是否存在 → Task 8 Step 2 给了退化方案
- `task_center_section_history` 资源名 → 实现时按工程师任务中心既有名核实
- PoLangApplication 中 container 静态类型 → Task 6 Step 2 给了两种处理

---

## 不阻塞遗留清单（终审 2026-09-26 固化）

以下为终审（agent-26）确认不阻塞 M1 交付的遗留项，逐项一句话登记，后续迭代按需拾起：

- `TaskCenterListItem` LongParameterList detekt 命中：工程师卡既有平移代码的历史命中，非本次新增，随工程师卡重构一并处理。
- `UserTaskCard` 动作按钮无 in-flight 禁用：双击间隙依赖体系幂等兜底（enqueue 去重 / StartTagScanUseCase 协议幂等），不加按钮态机。
- 卡片 cd 仅含标题：进度/状态未进 contentDescription，卡片级 cd 已可定位，无障碍细化留待专门 pass。
- 任务中心 Tab 索引裸 0/1：两 Tab 场景可读性可接受，抽枚举收益不抵成本。
- `perform` CancellationException rethrow 分支无测试：CE 传播路径由协程框架保证，单测构造收益低。
- adapter `start()` 双 launch 零覆盖：启动序列简单，核心路径已由 sync/reconcile 单测覆盖。
- manager enqueue collect 无 catch 窄缝（🔵-6）：manager 内部冷流 collect 异常理论上可穿透，预检已挡主路径，窄缝随 manager 改造一并收口。
- 预检 ModelScope 大小写宽于 manager 查找（🔵-6）：预检 `ignoreCase=true`、manager 精确匹配，方向安全（预检更宽只少拦不多放）。
- `defaultTab` 活页翻转 quirk（🔵-5）：后台任务 Tab 停留期间新任务到达不强制跳 Tab，属预期产品设计而非缺陷。

## 真机冒烟记录（2026-09-26，Xiaomi 24129PN74C，debug 包）

- 场景①：扫描中暂停/恢复/取消 ✓ 全通过（暂停冻结进度、恢复续跑、取消入历史「已取消」）。
- 场景①变体：扫描中杀进程重启 → 历史区「失败 + 已中断——点重试重新开始 + 重试」✓；点重试经 StartTagScanUseCase 重启扫描 ✓。
- 场景②：下载中杀进程重启 → FAILED + 重试 ✓；重试 HTTP 206 断点续传 ✓；卡片暂停/继续 ✓（日志 `Download paused at N bytes`，续传从暂停字节恢复）；完成入历史「已完成」✓。
- 场景③：角标合并计数 ✓（工程师 3 + 扫描 1 = 4）；默认 Tab 落位（工程师活跃→工程师 Tab）✓；目的地跳转双路径 ✓（下载卡→模型中心、扫描卡→整理页扫描 Tab）。「均无活跃→工程师 Tab」分支与后台 Tab 空态未覆盖（工程师任务恒有活跃，未人为清用户数据）。
- 冒烟发现并修复：暂停恢复中的下载被误判 FAILED——OkHttp 取消消息为 "stream was reset: CANCEL"，manager catch 按 `contains("cancelled")` 匹配落空；改为优先按 `pausedDownloads` 暂停意图判定（LlmModelDownloadManager 两处 catch，commit 见下行）。
- 冒烟观察（未修，登记跟踪）：① 暂停点击到状态落定有瞬态闪烁（pause 后残留的进度发射会短暂把 UI 翻回 RUNNING，流拆除后落定 PAUSED）；② 杀进程后续传的文件曾出现 SHA256 校验失败，manager 自动全量重下自愈——并行分块写入与单流续传的兼容性值得 manager 侧专项核查。
