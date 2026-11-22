package com.mamba.picme.domain.usertask

import com.mamba.picme.data.local.RoomUserTaskStore
import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanProgress
import com.mamba.picme.domain.dedup.DedupScanState
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
class DedupScanTaskAdapterTest {

    private class FakeDao : UserTaskDao {
        val rows = MutableStateFlow<List<UserTaskEntity>>(emptyList())
        override suspend fun upsert(task: UserTaskEntity) {
            rows.value = rows.value.filterNot { row -> row.id == task.id } + task
        }
        override fun observeAll(): Flow<List<UserTaskEntity>> = rows
        override suspend fun getById(id: String): UserTaskEntity? = rows.value.firstOrNull { row -> row.id == id }
        override suspend fun activeIdsOfKind(kind: String): List<String> =
            rows.value.filter { row -> row.kind == kind && row.status in setOf("PENDING", "RUNNING", "PAUSED") }.map { row -> row.id }
        override suspend fun trimHistory(keep: Int) = Unit
    }

    private class FakeControl : DedupScanControl {
        val actions = mutableListOf<String>()
        override suspend fun invoke(action: String) {
            actions += action
        }
    }

    private data class Fixture(
        val registry: UserTaskRegistry,
        val adapter: DedupScanTaskAdapter,
        val control: FakeControl,
        val flow: MutableStateFlow<DedupScanProgress?>,
    )

    // stateIn(Eagerly) 的收集协程需即时跑完才能让 .value 断言同步可见：
    // runTest 默认 StandardTestDispatcher 不会自动推进，故用共享 testScheduler 的 Unconfined scope（同 TagScanTaskAdapterTest 先例）。
    private fun fixture(scheduler: TestCoroutineScheduler): Fixture {
        val scope = TestScope(UnconfinedTestDispatcher(scheduler))
        val flow = MutableStateFlow<DedupScanProgress?>(null)
        val registry = UserTaskRegistry(store = RoomUserTaskStore(FakeDao()), scope = scope, clock = { 1000L })
        val control = FakeControl()
        val adapter = DedupScanTaskAdapter(
            registry = registry, control = control, scope = scope, progressFlow = flow,
        )
        return Fixture(registry, adapter, control, flow)
    }

    private fun progress(
        state: DedupScanState,
        scanned: Int = 0,
        total: Int = 0,
    ) = DedupScanProgress(
        state = state,
        phase = DedupLevel.EXACT,
        phaseIndex = 0,
        phaseCount = 2,
        scanned = scanned,
        total = total,
        foundGroups = emptyList(),
        resultGroups = null,
    )

    @Test
    fun `体系状态投影矩阵`() {
        assertEquals(UserTaskStatus.RUNNING, UserTaskMapping.fromDedupScanState(DedupScanState.RUNNING))
        assertEquals(UserTaskStatus.PAUSED, UserTaskMapping.fromDedupScanState(DedupScanState.PAUSED))
        assertEquals(UserTaskStatus.COMPLETED, UserTaskMapping.fromDedupScanState(DedupScanState.COMPLETED))
        assertEquals(UserTaskStatus.CANCELLED, UserTaskMapping.fromDedupScanState(DedupScanState.CANCELLED))
        assertNull(UserTaskMapping.fromDedupScanState(null))
    }

    @Test
    fun `start 订阅 RUNNING 帧同步为活动任务含进度快照`() = runTest {
        val (registry, adapter, _, flow) = fixture(testScheduler)
        flow.value = progress(DedupScanState.RUNNING, scanned = 10, total = 20)
        adapter.start()
        val task = registry.tasks.value.single()
        assertEquals("dedupscan:main", task.id)
        assertEquals(UserTaskKind.DEDUP_SCAN, task.kind)
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertEquals(UserTaskDestination.ORGANIZE_HUB, task.destination)
        assertEquals(0.5f, task.progress!!, 1e-5f)
        assertEquals("10/20", task.progressText)
        assertNull(task.etaMs)
    }

    @Test
    fun `COMPLETED 帧同步为终态且清进度快照`() = runTest {
        val (registry, adapter, _, flow) = fixture(testScheduler)
        flow.value = progress(DedupScanState.RUNNING, scanned = 10, total = 20)
        adapter.start()
        flow.value = progress(DedupScanState.COMPLETED, scanned = 20, total = 20)
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.COMPLETED, task.status)
        assertNull(task.errorCode)
        // 终态不写进度快照（upsertStatus 内部已清）：无幽灵进度残留
        assertNull(task.progress)
        assertNull(task.progressText)
        assertNull(task.etaMs)
    }

    @Test
    fun `PAUSED 保持活跃态并回写进度快照`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(progress(DedupScanState.PAUSED, scanned = 8, total = 20))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.PAUSED, task.status)
        assertEquals(0.4f, task.progress!!, 1e-5f)
        assertEquals("8/20", task.progressText)
    }

    @Test
    fun `null 帧且注册表残留活动态行时置 FAILED 对账可重试`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        registry.upsertStatus("dedupscan:main", UserTaskKind.DEDUP_SCAN, null, UserTaskStatus.RUNNING)
        adapter.sync(null)
        val task = registry.tasks.value.single()
        // FAILED 而非 CANCELLED：actionsFor(FAILED)=RETRY，「已中断——点重试重新开始」文案有按钮可点
        assertEquals(UserTaskStatus.FAILED, task.status)
        assertEquals(UserTaskErrorCode.PROCESS_TERMINATED, task.errorCode)
        assertEquals(setOf(UserTaskAction.RETRY), task.supportedActions)
        assertNull(task.progress)
    }

    @Test
    fun `运行中 null 帧（会话失败 stop-on-null）同样对账置 FAILED 不残留 RUNNING 行`() = runTest {
        val (registry, adapter, _, flow) = fixture(testScheduler)
        flow.value = progress(DedupScanState.RUNNING, scanned = 10, total = 20)
        adapter.start()
        // Manager 失败路径：不发终态帧直接清流（stop-on-null）
        flow.value = null
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.FAILED, task.status)
        assertEquals(UserTaskErrorCode.PROCESS_TERMINATED, task.errorCode)
        assertNull(task.progress)
    }

    @Test
    fun `null 帧且行已终态时无操作仅清快照`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(progress(DedupScanState.CANCELLED, scanned = 8, total = 20))
        adapter.sync(null)
        val task = registry.tasks.value.single()
        // 终态帧先于死亡 null 到达：CANCELLED 行不被 null 帧翻写，入历史
        assertEquals(UserTaskStatus.CANCELLED, task.status)
        assertNull(task.errorCode)
        assertNull(task.progress)
    }

    @Test
    fun `null 进度且注册表无行时不产生任务`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(null)
        assertTrue(registry.tasks.value.isEmpty())
    }

    @Test
    fun `total 为 0 时进度与文案均为 null 不显示 0 比 0`() = runTest {
        val (registry, adapter, _) = fixture(testScheduler)
        adapter.sync(progress(DedupScanState.RUNNING, scanned = 0, total = 0))
        val task = registry.tasks.value.single()
        assertEquals(UserTaskStatus.RUNNING, task.status)
        assertNull(task.progress)
        assertNull(task.progressText)
    }

    @Test
    fun `统一动词转发为去重扫描控制动作`() = runTest {
        val (_, adapter, control) = fixture(testScheduler)
        adapter.perform("dedupscan:main", UserTaskAction.PAUSE)
        adapter.perform("dedupscan:main", UserTaskAction.RESUME)
        adapter.perform("dedupscan:main", UserTaskAction.CANCEL)
        adapter.perform("dedupscan:main", UserTaskAction.RETRY)
        assertEquals(listOf("pause", "resume", "cancel", "start"), control.actions)
    }
}
