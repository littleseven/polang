package com.mamba.picme.domain.usertask

import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.DownloadStatus
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

    // stateIn(Eagerly) 的收集协程需即时跑完才能让 .value 断言同步可见：
    // runTest 默认 StandardTestDispatcher 不会自动推进，故用共享 testScheduler 的 Unconfined scope（同 UserTaskRegistryTest 先例）。
    private fun fixture(scheduler: TestCoroutineScheduler): Triple<UserTaskRegistry, ModelDownloadTaskAdapter, FakeControl> {
        val scope = TestScope(UnconfinedTestDispatcher(scheduler))
        val registry = UserTaskRegistry(dao = FakeDao(), scope = scope, clock = { 1000L })
        val control = FakeControl()
        val adapter = ModelDownloadTaskAdapter(registry = registry, control = control, scope = scope)
        return Triple(registry, adapter, control)
    }

    @Test
    fun `下载中状态同步为多任务含字节进度`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
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
        val (registry, adapter, control) = fixture(testScheduler)
        registry.upsertStatus("download:ghost", UserTaskKind.MODEL_DOWNLOAD, "ghost", UserTaskStatus.RUNNING)
        registry.updateProgress("download:ghost", TaskProgressSnapshot(0.5f, "5.0 MB / 10.0 MB", null))
        registry.upsertStatus("download:done", UserTaskKind.MODEL_DOWNLOAD, "done", UserTaskStatus.COMPLETED)
        control.downloadStates.value = mapOf(
            "live" to DownloadState("live", DownloadStatus.DOWNLOADING, 0L, 100L),
        )
        adapter.reconcile()
        val tasks = registry.tasks.value.associateBy { it.id }
        assertEquals(UserTaskStatus.FAILED, tasks.getValue("download:ghost").status)
        assertEquals(UserTaskErrorCode.PROCESS_TERMINATED, tasks.getValue("download:ghost").errorCode)
        // 对账顺带清掉 ghost 行的残留进度快照
        assertNull(tasks.getValue("download:ghost").progress)
        assertEquals(UserTaskStatus.COMPLETED, tasks.getValue("download:done").status)
        assertEquals(UserTaskStatus.RUNNING, tasks.getValue("download:live").status)
    }

    @Test
    fun `map 中消失的活动任务置 CANCELLED 终态且不留 RETRY`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(mapOf("m1" to DownloadState("m1", DownloadStatus.DOWNLOADING, 5L, 10L)))
        adapter.sync(emptyMap())
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.CANCELLED, task.status)
        assertNull(task.errorCode)
        assertNull(task.progress)
        assertTrue(task.supportedActions.isEmpty())
    }

    @Test
    fun `对账后的 sync 不误伤 ghost 行——保持 FAILED 不被 CANCELLED 覆盖`() = runTest {
        val (registry, adapter, control) = fixture(testScheduler)
        registry.upsertStatus("download:ghost", UserTaskKind.MODEL_DOWNLOAD, "ghost", UserTaskStatus.RUNNING)
        control.downloadStates.value = emptyMap()
        adapter.reconcile() // 内部含一次 sync（lastActiveIds 初为空，不触发消失判定）
        adapter.sync(emptyMap()) // 再来一轮外部 sync：ghost 从未进过 lastActiveIds，仍不受影响
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.FAILED, task.status)
        assertEquals(UserTaskErrorCode.PROCESS_TERMINATED, task.errorCode)
    }

    @Test
    fun `totalBytes 为 0 或进度超界时快照兜底`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(mapOf("m1" to DownloadState("m1", DownloadStatus.DOWNLOADING, 0L, 0L)))
        var task = registry.tasks.value.single()
        assertNull(task.progress)
        assertNull(task.progressText)

        // manager resumeDownload 预存双计 bug 可致 downloadedBytes > totalBytes，适配器钳制到 1f
        adapter.sync(mapOf("m1" to DownloadState("m1", DownloadStatus.DOWNLOADING, 20L, 10L)))
        task = registry.tasks.value.single()
        assertEquals(1f, task.progress)
    }

    @Test
    fun `统一动词按 modelId 转发`() = runTest {
        val (_, adapter, control) = fixture(testScheduler)
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
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(mapOf("m1" to DownloadState("m1", DownloadStatus.DOWNLOADING, 5L, 10L)))
        adapter.sync(mapOf("m1" to DownloadState("m1", DownloadStatus.COMPLETED, 10L, 10L)))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.COMPLETED, task.status)
        assertNull(task.progress)
    }
}
