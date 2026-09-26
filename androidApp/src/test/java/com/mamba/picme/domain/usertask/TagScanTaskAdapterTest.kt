package com.mamba.picme.domain.usertask

import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
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

    // stateIn(Eagerly) 的收集协程需即时跑完才能让 .value 断言同步可见：
    // runTest 默认 StandardTestDispatcher 不会自动推进，故用共享 testScheduler 的 Unconfined scope（同 UserTaskRegistryTest 先例）。
    private fun fixture(scheduler: TestCoroutineScheduler): Triple<UserTaskRegistry, TagScanTaskAdapter, FakeControl> {
        val scope = TestScope(UnconfinedTestDispatcher(scheduler))
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
        val (registry, adapter, _) = fixture(testScheduler)
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
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(progress(ScanSessionState.COMPLETED, processed = 18, total = 20, failed = 2, eta = 5000L))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.COMPLETED, task.status)
        assertEquals(UserTaskErrorCode.PARTIAL_FAILURES, task.errorCode)
        assertEquals("2", task.errorDetail)
        // 终态不回写进度快照：无幽灵 ETA、重启前后表现一致
        assertNull(task.progress)
        assertNull(task.progressText)
        assertNull(task.etaMs)
    }

    @Test
    fun `COMPLETED 零失败时无错误码`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(progress(ScanSessionState.COMPLETED, processed = 20, total = 20, failed = 0))
        val task = registry.tasks.value.single()
        assertNull(task.errorCode)
        assertNull(task.progress)
        assertNull(task.etaMs)
    }

    @Test
    fun `首帧 null 且注册表残留活动态行时置 CANCELLED 对账`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        adapter.sync(null, isFirstEmission = true)
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.CANCELLED, task.status)
        assertEquals(UserTaskErrorCode.PROCESS_TERMINATED, task.errorCode)
        assertNull(task.progress)
    }

    @Test
    fun `非首帧 null 不动状态仅清快照`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        registry.updateProgress("tagscan:main", TaskProgressSnapshot(0.5f, "10/20", 3000L))
        adapter.sync(null)
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertNull(task.errorCode)
        assertNull(task.progress)
    }

    @Test
    fun `IDLE 对象不误判进程死亡仅清快照`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        registry.upsertStatus("tagscan:main", UserTaskKind.TAG_SCAN, null, UserTaskStatus.RUNNING)
        registry.updateProgress("tagscan:main", TaskProgressSnapshot(0.5f, "10/20", 3000L))
        adapter.sync(progress(ScanSessionState.IDLE, processed = 10, total = 20))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertNull(task.errorCode)
        assertNull(task.progress)
    }

    @Test
    fun `null 进度且注册表无行时不产生任务`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(null, isFirstEmission = true)
        assertTrue(registry.tasks.value.isEmpty())
    }

    @Test
    fun `total 为 0 时进度与文案均为 null 不显示 0 比 0`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(progress(ScanSessionState.RUNNING, processed = 0, total = 0))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertNull(task.progress)
        assertNull(task.progressText)
    }

    @Test
    fun `统一动词转发为扫描控制动作`() = runTest {
        val (_, adapter, control) = fixture(testScheduler)
        adapter.perform("tagscan:main", UserTaskAction.PAUSE)
        adapter.perform("tagscan:main", UserTaskAction.RESUME)
        adapter.perform("tagscan:main", UserTaskAction.CANCEL)
        adapter.perform("tagscan:main", UserTaskAction.RETRY)
        assertEquals(listOf("pause", "resume", "cancel", "start"), control.actions)
    }
}
