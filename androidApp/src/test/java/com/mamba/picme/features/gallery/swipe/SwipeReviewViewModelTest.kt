package com.mamba.picme.features.gallery.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.swipe.SwipeKeepHistory
import com.mamba.picme.domain.swipe.SwipeKeepHistoryStore
import com.mamba.picme.domain.swipe.SwipeReason
import com.mamba.picme.domain.trash.TrashBackend
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
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
class SwipeReviewViewModelTest {

    /** 普通照片（DCIM 相机目录 → RECENT 桶），队列内按 captureDate 倒序。 */
    private fun photo(uri: String, sizeBytes: Long = 1_000_000L, captureDate: Long = 0L) = OrganizeItem(
        uri = uri,
        isVideo = false,
        captureDate = captureDate,
        sizeBytes = sizeBytes,
        relativePath = "DCIM/Camera/",
        ocrText = null,
        pixelArea = 12_000_000L,
        labels = null,
        hasFace = false,
        aestheticScore = null,
        faceQualityScore = null,
    )

    private fun photos(count: Int, sizeBytes: Long = 1_000_000L): List<OrganizeItem> =
        (1..count).map { index -> photo("p$index", sizeBytes, captureDate = index.toLong()) }

    private class FakeBackend : TrashBackend {
        override val isSupported: Boolean = true
        val trashed = mutableSetOf<String>()
        var failNextBuild = false

        override fun buildTrashToken(uris: List<String>): Any {
            if (failNextBuild) throw IOException("ipc fail")
            return "trash-token"
        }

        override fun buildRestoreToken(uris: List<String>): Any = "restore-token"

        // 已 trash 的不算残留（IS_TRASHED 语义，同 TrashSessionControllerTest.FakeBackend）
        override fun queryExisting(uris: List<String>): List<String> =
            uris.filter { uri -> uri !in trashed }

        override suspend fun trySilentTrash(uris: List<String>): List<String>? = null
    }

    /** API<30 语义：不支持回收站授权。 */
    private class UnsupportedBackend : TrashBackend {
        override val isSupported: Boolean = false
        override fun buildTrashToken(uris: List<String>): Any = error("unreachable")
        override fun buildRestoreToken(uris: List<String>): Any = error("unreachable")
        override fun queryExisting(uris: List<String>): List<String> = emptyList()
        override suspend fun trySilentTrash(uris: List<String>): List<String>? = null
    }

    private class FakeKeepHistory : SwipeKeepHistoryStore {
        var entries: Set<String> = emptySet()
        override suspend fun load(): Set<String> = entries
        override suspend fun save(entries: Set<String>) {
            this.entries = entries
        }
    }

    /** JVM 单测无 Room/MediaStore：mockk 拦截 loadItems，answers 读 var 支持 restart 重载。 */
    private class FakeRepo {
        var items: List<OrganizeItem> = emptyList()
        val mock: OrganizeRepository = mockk {
            coEvery { loadItems() } answers { items }
        }
    }

    private fun viewModel(
        scope: TestScope,
        repo: FakeRepo,
        backend: TrashBackend,
        keepHistory: FakeKeepHistory = FakeKeepHistory(),
        nowMs: () -> Long = { 1_000_000_000_000L },
    ): SwipeReviewViewModel {
        // VM 内 outcomes/errorEvent collect 是无限订阅：挂独立 TestScope（非 runTest 子作用域），
        // 避免 runTest teardown 等待永活 collector 报 UncompletedCoroutinesError
        val vmScope = TestScope(scope.testScheduler)
        return SwipeReviewViewModel(
            organizeRepository = repo.mock,
            trashBackend = backend,
            keepHistoryStore = keepHistory,
            coroutineScope = vmScope,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            nowMs = nowMs,
        )
    }

    private fun reviewing(vm: SwipeReviewViewModel): SwipeUiState.Reviewing =
        vm.uiState.value as SwipeUiState.Reviewing

    private fun done(vm: SwipeReviewViewModel): SwipeUiState.Done =
        vm.uiState.value as SwipeUiState.Done

    @Test
    fun `init loads queue into reviewing`() = runTest {
        val repo = FakeRepo().apply { items = photos(3) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = reviewing(vm)
        assertEquals(listOf("p3", "p2", "p1"), state.queue.map { candidate -> candidate.uri })
        assertEquals(0, state.index)
        assertTrue(state.decisions.isEmpty())
        assertEquals("p3", state.current?.uri)
        assertEquals(SwipeReason.RECENT, state.current?.reason)
        assertEquals(0L, state.freedBytes)
    }

    @Test
    fun `empty queue goes straight to done`() = runTest {
        val repo = FakeRepo().apply { items = emptyList() }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(0, state.kept)
        assertEquals(0, state.deleted)
        assertEquals(0, state.skipped)
        assertEquals(0L, state.freedBytes)
        assertTrue(state.trashedUris.isEmpty())
    }

    @Test
    fun `decide advances index and accumulates freed bytes for deletes`() = runTest {
        val repo = FakeRepo().apply {
            items = listOf(
                photo("a", sizeBytes = 100L, captureDate = 3),
                photo("b", sizeBytes = 200L, captureDate = 2),
                photo("c", sizeBytes = 300L, captureDate = 1),
            )
        }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP)
        vm.decide(SwipeDecision.DELETE)
        val state = reviewing(vm)
        assertEquals(2, state.index)
        assertEquals(
            listOf("a" to SwipeDecision.KEEP, "b" to SwipeDecision.DELETE),
            state.decisions,
        )
        assertEquals(200L, state.freedBytes)
        assertEquals("c", state.current?.uri)
        assertNull(vm.trashController.pendingRequest.value) // DELETE 只标记不即时删
    }

    @Test
    fun `walking past queue end finishes into done without deletes`() = runTest {
        val repo = FakeRepo().apply { items = photos(2) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP)
        vm.decide(SwipeDecision.SKIP)
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(1, state.kept)
        assertEquals(1, state.skipped)
        assertEquals(0, state.deleted)
        assertEquals(0L, state.freedBytes)
        assertTrue(state.trashedUris.isEmpty())
        assertNull(vm.trashController.pendingRequest.value)
    }

    @Test
    fun `undo pops last decision and steps index back`() = runTest {
        val repo = FakeRepo().apply { items = photos(3) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP)
        vm.decide(SwipeDecision.DELETE)
        assertEquals(1_000_000L, reviewing(vm).freedBytes)
        vm.undo()
        val state = reviewing(vm)
        assertEquals(1, state.index)
        assertEquals(listOf("p3" to SwipeDecision.KEEP), state.decisions)
        assertEquals(0L, state.freedBytes)
    }

    @Test
    fun `undo at index zero is no-op`() = runTest {
        val repo = FakeRepo().apply { items = photos(2) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.undo()
        val state = reviewing(vm)
        assertEquals(0, state.index)
        assertTrue(state.decisions.isEmpty())
    }

    @Test
    fun `twenty pending deletes auto commit a trash batch`() = runTest {
        val repo = FakeRepo().apply { items = photos(25) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        repeat(19) { vm.decide(SwipeDecision.DELETE) }
        advanceUntilIdle()
        assertNull(vm.trashController.pendingRequest.value) // 19 张未达阈值
        vm.decide(SwipeDecision.DELETE)
        advanceUntilIdle()
        val pending = vm.trashController.pendingRequest.value
        assertEquals(20, pending?.uris?.size)
        assertEquals(false, pending?.isRestore)
        // 已提交批次不可 undo（canUndo 同步为 false）
        assertFalse(vm.canUndo)
        vm.undo()
        val state = reviewing(vm)
        assertEquals(20, state.index)
        assertEquals(20, state.decisions.size)
        // 未提交部分仍可 undo
        vm.decide(SwipeDecision.DELETE)
        assertTrue(vm.canUndo)
        vm.undo()
        assertEquals(20, reviewing(vm).decisions.size)
        assertEquals(20, reviewing(vm).index)
    }

    @Test
    fun `denied auth stays in reviewing with uncommitted batch retryable`() = runTest {
        val repo = FakeRepo().apply { items = photos(3) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.decide(SwipeDecision.DELETE) // p3
        vm.finish()
        advanceUntilIdle()
        assertEquals(listOf("p3"), vm.trashController.pendingRequest.value?.uris)

        vm.trashController.onTrashResult(ok = false) // 用户拒绝
        advanceUntilIdle()
        val state = reviewing(vm) // 回到 Reviewing 原地
        assertEquals(1, state.index)
        assertEquals(listOf("p3" to SwipeDecision.DELETE), state.decisions)
        assertEquals(1_000_000L, state.freedBytes) // 未提交批保留
        assertEquals(1, vm.pendingDeleteCount())

        vm.finish() // 可重试
        advanceUntilIdle()
        assertEquals(listOf("p3"), vm.trashController.pendingRequest.value?.uris)
        assertTrue(backend.trashed.isEmpty())
    }

    @Test
    fun `finish commits pending deletes and lands in done after trashed`() = runTest {
        val repo = FakeRepo().apply {
            items = listOf(
                photo("a", sizeBytes = 100L, captureDate = 3),
                photo("b", sizeBytes = 200L, captureDate = 2),
                photo("c", sizeBytes = 300L, captureDate = 1),
            )
        }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP) // a
        vm.decide(SwipeDecision.DELETE) // b
        vm.finish()
        advanceUntilIdle()
        assertEquals(listOf("b"), vm.trashController.pendingRequest.value?.uris)

        backend.trashed.add("b") // 用户允许
        vm.trashController.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(1, state.kept)
        assertEquals(1, state.deleted)
        assertEquals(0, state.skipped)
        assertEquals(200L, state.freedBytes)
        assertEquals(listOf("b"), state.trashedUris)
        assertNull(vm.trashController.pendingRequest.value)
    }

    @Test
    fun `done undoAll restores and restarts into reviewing`() = runTest {
        val repo = FakeRepo().apply { items = photos(2, sizeBytes = 100L) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.decide(SwipeDecision.DELETE) // p2
        vm.finish()
        advanceUntilIdle()
        backend.trashed.add("p2")
        vm.trashController.onTrashResult(ok = true)
        advanceUntilIdle()
        assertEquals(listOf("p2"), done(vm).trashedUris)

        vm.undoAll()
        advanceUntilIdle()
        val restorePending = vm.trashController.pendingRequest.value
        assertEquals(true, restorePending?.isRestore)
        assertEquals(listOf("p2"), restorePending?.uris)

        backend.trashed.clear() // 用户允许恢复
        vm.trashController.onRestoreResult(ok = true)
        advanceUntilIdle()
        val state = reviewing(vm) // restart 重载建队
        assertEquals(0, state.index)
        assertTrue(state.decisions.isEmpty())
        assertEquals(listOf("p2", "p1"), state.queue.map { candidate -> candidate.uri })
        assertNull(vm.trashController.pendingRequest.value)
    }

    @Test
    fun `oneMoreRound suppresses freshly kept uris and lands in done when nothing left`() = runTest {
        val repo = FakeRepo().apply { items = photos(2) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP)
        vm.decide(SwipeDecision.KEEP)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SwipeUiState.Done)

        // KEEP 30 天抑制：两张刚 KEEP 过，再来一轮队列空 → 直落 Done
        vm.oneMoreRound()
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(0, state.kept)
        assertEquals(0, state.deleted)
        assertEquals(0, state.skipped)
    }

    @Test
    fun `mid-session committed batch accumulates into final done`() = runTest {
        val repo = FakeRepo().apply { items = photos(22, sizeBytes = 10L) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        repeat(20) { vm.decide(SwipeDecision.DELETE) }
        advanceUntilIdle()
        val firstBatch = vm.trashController.pendingRequest.value?.uris.orEmpty()
        assertEquals(20, firstBatch.size)
        backend.trashed.addAll(firstBatch) // 首批授权通过，会话继续
        vm.trashController.onTrashResult(ok = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SwipeUiState.Reviewing)

        vm.decide(SwipeDecision.DELETE) // 第 21 张
        vm.decide(SwipeDecision.SKIP) // 第 22 张，队列走完自动 finish
        advanceUntilIdle()
        assertEquals(listOf(reviewingQueueUri(vm, 20)), vm.trashController.pendingRequest.value?.uris)
        backend.trashed.add(reviewingQueueUri(vm, 20))
        vm.trashController.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(21, state.deleted)
        assertEquals(1, state.skipped)
        assertEquals(210L, state.freedBytes)
        assertEquals(21, state.trashedUris.size)
    }

    // ---------- 2026-09-05 审查修复回归 ----------

    /** ① finish 时另一批在途：commitInFlight 吞并 → 首批 Trashed 后补提交剩余 → Done。 */
    @Test
    fun `finish while earlier batch in flight chains commits before done`() = runTest {
        val repo = FakeRepo().apply { items = photos(21, sizeBytes = 10L) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        repeat(20) { vm.decide(SwipeDecision.DELETE) }
        advanceUntilIdle()
        val firstBatch = vm.trashController.pendingRequest.value?.uris.orEmpty()
        assertEquals(20, firstBatch.size)

        vm.decide(SwipeDecision.DELETE) // 第 21 张，队列走完自动 finish，但首批在途
        advanceUntilIdle()
        // commitInFlight 吞并：pending 仍是首批，无二批
        assertEquals(firstBatch, vm.trashController.pendingRequest.value?.uris)

        backend.trashed.addAll(firstBatch)
        vm.trashController.onTrashResult(ok = true)
        advanceUntilIdle()
        // 首批结算后补提交剩余 1 张
        val secondBatch = vm.trashController.pendingRequest.value?.uris.orEmpty()
        assertEquals(1, secondBatch.size)
        assertFalse(firstBatch.containsAll(secondBatch))

        backend.trashed.addAll(secondBatch)
        vm.trashController.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(21, state.deleted)
        assertEquals(0, state.skipped)
        assertEquals(210L, state.freedBytes)
    }

    /** ② 末张后取消授权 → 卡住态（current==null）有重试出口，重试成功进 Done。 */
    @Test
    fun `cancelled final auth keeps pending deletes retryable from stuck state`() = runTest {
        val repo = FakeRepo().apply { items = photos(1) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.decide(SwipeDecision.DELETE) // 末张 → 自动 finish
        advanceUntilIdle()
        assertEquals(listOf("p1"), vm.trashController.pendingRequest.value?.uris)

        vm.trashController.onTrashResult(ok = false) // 用户拒绝
        advanceUntilIdle()
        val stuck = reviewing(vm)
        assertNull(stuck.current) // 队列走完，停在 Reviewing
        assertEquals(1, vm.pendingDeleteCount())
        assertTrue(vm.canUndo) // 回滚后尾条决策可 undo

        vm.finish() // 重试出口
        advanceUntilIdle()
        assertEquals(listOf("p1"), vm.trashController.pendingRequest.value?.uris)
        backend.trashed.add("p1")
        vm.trashController.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(1, state.deleted)
        assertEquals(0, state.skipped)
        assertEquals(1_000_000L, state.freedBytes)
    }

    /** ② 末张后取消授权 → 放弃出口：未提交 DELETE 改记 SKIP 进 Done。 */
    @Test
    fun `discard pending deletes lands in done as skipped`() = runTest {
        val repo = FakeRepo().apply { items = photos(1, sizeBytes = 100L) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.decide(SwipeDecision.DELETE)
        advanceUntilIdle()
        vm.trashController.onTrashResult(ok = false)
        advanceUntilIdle()
        assertEquals(1, vm.pendingDeleteCount())

        vm.discardPendingDeletes()
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(0, state.kept)
        assertEquals(0, state.deleted)
        assertEquals(1, state.skipped)
        assertEquals(0L, state.freedBytes)
        assertTrue(state.trashedUris.isEmpty())
        assertTrue(backend.trashed.isEmpty())
    }

    /** ③ API<30 不支持：finish 直接结算 Done（不死锁），未提交 DELETE 以 skipped 口径统计。 */
    @Test
    fun `unsupported device finishes into done without deadlock`() = runTest {
        val repo = FakeRepo().apply { items = photos(2, sizeBytes = 100L) }
        val vm = viewModel(this, repo, UnsupportedBackend())
        advanceUntilIdle()
        vm.decide(SwipeDecision.DELETE) // p2
        vm.finish()
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(0, state.deleted)
        assertEquals(1, state.skipped)
        assertEquals(0L, state.freedBytes)
        assertTrue(state.trashedUris.isEmpty())
        assertNull(vm.trashController.pendingRequest.value)
        assertTrue(vm.trashController.errorEvent.value) // UI snackbar 链路
    }

    /** ③ token 构建失败（无 outcome）：errorEvent collect 回滚在途提交，修复后可重试。 */
    @Test
    fun `token build failure rolls back in-flight commit via error event`() = runTest {
        val repo = FakeRepo().apply { items = photos(1) }
        val backend = FakeBackend().apply { failNextBuild = true }
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.decide(SwipeDecision.DELETE) // 末张 → 自动 finish → token 构建失败
        advanceUntilIdle()
        assertNull(vm.trashController.pendingRequest.value)
        assertTrue(vm.trashController.errorEvent.value)
        // 回滚后不死锁：尾条决策回到未提交，可 undo / 可重试
        assertTrue(vm.canUndo)
        assertEquals(1, vm.pendingDeleteCount())

        backend.failNextBuild = false
        vm.finish()
        advanceUntilIdle()
        assertEquals(listOf("p1"), vm.trashController.pendingRequest.value?.uris)
    }

    /** ⑥ KEEP 30 天抑制：再来一轮不重喂刚 KEEP 的 uri；SKIP 不抑制。 */
    @Test
    fun `kept uris are suppressed from next round queue`() = runTest {
        val repo = FakeRepo().apply { items = photos(2) }
        val history = FakeKeepHistory()
        val now = 1_000_000_000_000L
        val vm = viewModel(this, repo, FakeBackend(), history) { now }
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP) // p2（captureDate 倒序队首）
        advanceUntilIdle()
        assertTrue(history.entries.any { entry -> entry.startsWith("p2|") })
        vm.decide(SwipeDecision.SKIP) // p1 → 走完 → Done
        advanceUntilIdle()
        assertEquals(1, done(vm).kept)

        vm.oneMoreRound()
        advanceUntilIdle()
        val state = reviewing(vm) // p2 抑制，p1（SKIP）不抑制
        assertEquals(listOf("p1"), state.queue.map { candidate -> candidate.uri })
    }

    /** ⑥ 过期条目（>30 天）不再抑制，且读取时被裁剪写回。 */
    @Test
    fun `expired keep entries are pruned and stop suppressing`() = runTest {
        val repo = FakeRepo().apply { items = photos(2) }
        val now = 1_000_000_000_000L
        val history = FakeKeepHistory().apply {
            entries = setOf(
                SwipeKeepHistory.encode("p3", now - SwipeKeepHistory.TTL_MS), // 恰好过期边界
                "dirty-entry-without-timestamp",
            )
        }
        val vm = viewModel(this, repo, FakeBackend(), history) { now }
        advanceUntilIdle()
        val state = reviewing(vm) // p3 不再被抑制
        assertEquals(2, state.queue.size)
        assertTrue(history.entries.isEmpty()) // 过期 + 脏条目已裁剪写回
    }

    /** ⑥ undo(KEEP) 回滚本会话抑制条目：再来一轮该 uri 重新入队。 */
    @Test
    fun `undo of keep removes session suppression entry`() = runTest {
        val repo = FakeRepo().apply { items = photos(2) }
        val history = FakeKeepHistory()
        val vm = viewModel(this, repo, FakeBackend(), history)
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP) // p2（captureDate 倒序队首）
        advanceUntilIdle()
        assertTrue(history.entries.any { entry -> entry.startsWith("p2|") })

        vm.undo()
        advanceUntilIdle()
        assertTrue(history.entries.none { entry -> entry.startsWith("p2|") })

        vm.decide(SwipeDecision.SKIP) // p2
        vm.decide(SwipeDecision.SKIP) // p1 → Done
        advanceUntilIdle()
        vm.oneMoreRound()
        advanceUntilIdle()
        assertEquals(2, reviewing(vm).queue.size) // 无 KEEP 抑制残留
    }

    /** 队列第 index 张的 uri（Reviewing 态辅助）。 */
    private fun reviewingQueueUri(vm: SwipeReviewViewModel, index: Int): String =
        reviewing(vm).queue[index].uri
}
