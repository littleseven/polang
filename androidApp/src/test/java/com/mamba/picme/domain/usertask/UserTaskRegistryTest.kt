package com.mamba.picme.domain.usertask

import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
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
            rows.value = rows.value.filterNot { row -> row.id == task.id } + task
        }

        override fun observeAll(): Flow<List<UserTaskEntity>> = rows

        override suspend fun getById(id: String): UserTaskEntity? = rows.value.firstOrNull { row -> row.id == id }

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

        override fun start() {
            started = true
        }

        override suspend fun perform(taskId: String, action: UserTaskAction) {
            performed += taskId to action
        }
    }

    // stateIn(Eagerly) 的收集协程需即时跑完才能让 .value 断言同步可见：
    // runTest 默认 StandardTestDispatcher 不会自动推进，故用共享 testScheduler 的 Unconfined scope。
    private fun registry(dao: FakeDao, testScheduler: TestCoroutineScheduler) =
        UserTaskRegistry(
            dao = dao,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            clock = { 1000L },
        )

    @Test
    fun `upsertStatus 落库且 tasks 合并内存进度`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, testScheduler)
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
        val registry = registry(dao, testScheduler)
        registry.upsertStatus("download:a", UserTaskKind.MODEL_DOWNLOAD, "a", UserTaskStatus.RUNNING)
        registry.upsertStatus("download:b", UserTaskKind.MODEL_DOWNLOAD, "b", UserTaskStatus.PAUSED)
        registry.upsertStatus("download:c", UserTaskKind.MODEL_DOWNLOAD, "c", UserTaskStatus.COMPLETED)
        assertEquals(2, registry.activeCount.value)
    }

    @Test
    fun `终态 upsert 触发历史修剪且封顶 50`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, testScheduler)
        repeat(55) { i ->
            registry.upsertStatus("download:m$i", UserTaskKind.MODEL_DOWNLOAD, "m$i", UserTaskStatus.COMPLETED)
        }
        assertTrue(dao.trimCalls >= 55)
        assertEquals(50, registry.tasks.value.size)
    }

    @Test
    fun `perform 按 kind 分发到对应适配器`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, testScheduler)
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
        val registry = registry(dao, testScheduler)
        registry.registerAdapter(FakeAdapter(UserTaskKind.TAG_SCAN))
        registry.perform("ghost", UserTaskAction.PAUSE) // 不抛异常即通过
    }

    @Test
    fun `进度快照清除后回落为 null`() = runTest {
        val dao = FakeDao()
        val registry = registry(dao, testScheduler)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        registry.updateProgress("tagscan:main", TaskProgressSnapshot(0.3f, "3/10", null))
        registry.updateProgress("tagscan:main", null)
        assertNull(registry.tasks.value.single().progress)
    }
}
