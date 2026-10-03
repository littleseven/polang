package com.mamba.picme.domain.usertask

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UserTaskRegistryTest {

    private class FakeStore : UserTaskStore {
        val rows = MutableStateFlow<List<UserTaskRow>>(emptyList())
        var trimCalls = 0
            private set
        var upsertCalls = 0
            private set
        var failOnUpsert = false

        override suspend fun upsert(row: UserTaskRow) {
            upsertCalls++
            if (failOnUpsert) error("disk full")
            rows.value = rows.value.filterNot { existing -> existing.id == row.id } + row
        }

        override fun observeAll(): Flow<List<UserTaskRow>> = rows

        override suspend fun getById(id: String): UserTaskRow? = rows.value.firstOrNull { row -> row.id == id }

        override suspend fun activeIdsOfKind(kind: String): List<String> =
            rows.value.filter { row -> row.kind == kind && row.status in setOf("PENDING", "RUNNING", "PAUSED") }
                .map { row -> row.id }

        override suspend fun trimHistory(keep: Int) {
            trimCalls++
            val terminal = rows.value.filter { row -> row.status in setOf("COMPLETED", "FAILED", "CANCELLED") }
                .sortedByDescending { row -> row.completedAt ?: 0L }
            val surplus = terminal.drop(keep).map { row -> row.id }.toSet()
            rows.value = rows.value.filterNot { row -> row.id in surplus }
        }
    }

    private class FakeAdapter(override val kind: UserTaskKind) : UserTaskAdapter {
        val performed = mutableListOf<Pair<String, UserTaskAction>>()
        var started = false
            private set
        var failOnPerform = false

        override fun start() {
            started = true
        }

        override suspend fun perform(taskId: String, action: UserTaskAction) {
            if (failOnPerform) error("FGS start rejected")
            performed += taskId to action
        }
    }

    // stateIn(Eagerly) 的收集协程需即时跑完才能让 .value 断言同步可见：
    // runTest 默认 StandardTestDispatcher 不会自动推进，故用共享 testScheduler 的 Unconfined scope。
    private fun registry(store: FakeStore, testScheduler: TestCoroutineScheduler) =
        UserTaskRegistry(
            store = store,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            clock = { 1000L },
        )

    @Test
    fun `upsertStatus 落库且 tasks 合并内存进度`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
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
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.RUNNING)
        registry.upsertStatus("download:b", UserTaskKind.MODEL_DOWNLOAD, "b", UserTaskStatus.PAUSED)
        registry.upsertStatus("download:c", UserTaskKind.MODEL_DOWNLOAD, "c", UserTaskStatus.COMPLETED)
        assertEquals(2, registry.activeCount.value)
    }

    @Test
    fun `终态 upsert 触发历史修剪且封顶 50`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        repeat(55) { i ->
            registry.upsertStatus("download:m$i", UserTaskKind.MODEL_DOWNLOAD, "m$i", UserTaskStatus.COMPLETED)
        }
        assertTrue(store.trimCalls >= 55)
        assertEquals(50, registry.tasks.value.size)
    }

    @Test
    fun `perform 按 kind 分发到对应适配器`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
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
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        registry.registerAdapter(FakeAdapter(UserTaskKind.TAG_SCAN))
        registry.perform("ghost", UserTaskAction.PAUSE) // 不抛异常即通过
    }

    @Test
    fun `perform 适配器抛异常降级为日志不穿透且任务状态不受影响`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        val tagAdapter = FakeAdapter(UserTaskKind.TAG_SCAN).apply { failOnPerform = true }
        registry.registerAdapter(tagAdapter)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)

        registry.perform("tagscan:main", UserTaskAction.PAUSE) // 不抛异常即通过

        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertEquals(1, registry.activeCount.value)
    }

    @Test
    fun `进度快照清除后回落为 null`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        registry.updateProgress("tagscan:main", TaskProgressSnapshot(0.3f, "3/10", null))
        registry.updateProgress("tagscan:main", null)
        assertNull(registry.tasks.value.single().progress)
    }

    @Test
    fun `写库失败记日志降级不抛出且 tasks 不受影响`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.RUNNING)
        assertEquals(1, registry.tasks.value.size)

        store.failOnUpsert = true
        registry.upsertStatus("download:b", UserTaskKind.MODEL_DOWNLOAD, "b", UserTaskStatus.RUNNING) // 不抛出即通过

        assertEquals(listOf("download:a"), registry.tasks.value.map { task -> task.id })
    }

    @Test
    fun `连续同态 upsertStatus 节流为单次落库`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        // 同态 5 连写（模拟下载 500ms 帧 / 扫描每张一帧的同态刷新）只落库 1 次
        repeat(5) {
            registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.RUNNING)
        }
        assertEquals(1, store.upsertCalls)

        // 状态迁移不节流
        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.PAUSED)
        assertEquals(2, store.upsertCalls)

        // 写失败不入缓存：恢复后同态调用仍会重试落库
        store.failOnUpsert = true
        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.COMPLETED)
        assertEquals(3, store.upsertCalls)
        store.failOnUpsert = false
        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.COMPLETED)
        assertEquals(4, store.upsertCalls)
        assertEquals(UserTaskStatus.COMPLETED, registry.tasks.value.single().status)
    }

    @Test
    fun `perform 行存在但 kind 未注册适配器静默返回`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        val downloadAdapter = FakeAdapter(UserTaskKind.MODEL_DOWNLOAD)
        registry.registerAdapter(downloadAdapter)

        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        registry.perform("tagscan:main", UserTaskAction.PAUSE) // 不抛异常即通过

        assertTrue(downloadAdapter.performed.isEmpty())
    }

    @Test
    fun `perform 非法 kind 行静默返回且该行被 tasks 丢弃`() = runTest {
        val store = FakeStore()
        val registry = registry(store, testScheduler)
        val tagAdapter = FakeAdapter(UserTaskKind.TAG_SCAN)
        registry.registerAdapter(tagAdapter)
        store.rows.value = listOf(
            UserTaskRow(
                id = "ghost-row",
                kind = "GHOST",
                displayName = null,
                status = "RUNNING",
                errorCode = null,
                errorDetail = null,
                destination = "TAG_SCAN_CONTROL",
                updatedAt = 1000L,
                completedAt = null,
            )
        )
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)

        registry.perform("ghost-row", UserTaskAction.PAUSE) // 不抛异常即通过

        assertTrue(tagAdapter.performed.isEmpty())
        assertEquals(listOf("tagscan:main"), registry.tasks.value.map { task -> task.id })
        assertEquals(1, registry.activeCount.value)
    }
}
