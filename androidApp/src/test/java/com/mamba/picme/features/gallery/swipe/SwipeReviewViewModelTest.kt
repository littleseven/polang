package com.mamba.picme.features.gallery.swipe

import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.swipe.SwipeReason
import com.mamba.picme.domain.trash.TrashBackend
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

        override fun buildTrashToken(uris: List<String>): Any = "trash-token"
        override fun buildRestoreToken(uris: List<String>): Any = "restore-token"

        // 已 trash 的不算残留（IS_TRASHED 语义，同 TrashSessionControllerTest.FakeBackend）
        override fun queryExisting(uris: List<String>): List<String> =
            uris.filter { uri -> uri !in trashed }
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
        backend: FakeBackend,
    ): SwipeReviewViewModel {
        // VM 内 outcomes collect 是无限订阅：挂独立 TestScope（非 runTest 子作用域），
        // 避免 runTest teardown 等待永活 collector 报 UncompletedCoroutinesError
        val vmScope = TestScope(scope.testScheduler)
        return SwipeReviewViewModel(
            organizeRepository = repo.mock,
            trashBackend = backend,
            coroutineScope = vmScope,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
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
        // 已提交批次不可 undo
        vm.undo()
        val state = reviewing(vm)
        assertEquals(20, state.index)
        assertEquals(20, state.decisions.size)
        // 未提交部分仍可 undo
        vm.decide(SwipeDecision.DELETE)
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

        vm.onTrashResult(ok = false) // 用户拒绝
        advanceUntilIdle()
        val state = reviewing(vm) // 回到 Reviewing 原地
        assertEquals(1, state.index)
        assertEquals(listOf("p3" to SwipeDecision.DELETE), state.decisions)
        assertEquals(1_000_000L, state.freedBytes) // 未提交批保留

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
        vm.onTrashResult(ok = true)
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
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        assertEquals(listOf("p2"), done(vm).trashedUris)

        vm.undoAll()
        advanceUntilIdle()
        val restorePending = vm.trashController.pendingRequest.value
        assertEquals(true, restorePending?.isRestore)
        assertEquals(listOf("p2"), restorePending?.uris)

        backend.trashed.clear() // 用户允许恢复
        vm.onRestoreResult(ok = true)
        advanceUntilIdle()
        val state = reviewing(vm) // restart 重载建队
        assertEquals(0, state.index)
        assertTrue(state.decisions.isEmpty())
        assertEquals(listOf("p2", "p1"), state.queue.map { candidate -> candidate.uri })
        assertNull(vm.trashController.pendingRequest.value)
    }

    @Test
    fun `oneMoreRound restarts queue from done`() = runTest {
        val repo = FakeRepo().apply { items = photos(2) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.decide(SwipeDecision.KEEP)
        vm.decide(SwipeDecision.KEEP)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SwipeUiState.Done)

        vm.oneMoreRound()
        advanceUntilIdle()
        val state = reviewing(vm)
        assertEquals(0, state.index)
        assertTrue(state.decisions.isEmpty())
        assertEquals(2, state.queue.size)
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
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SwipeUiState.Reviewing)

        vm.decide(SwipeDecision.DELETE) // 第 21 张
        vm.decide(SwipeDecision.SKIP) // 第 22 张，队列走完自动 finish
        advanceUntilIdle()
        assertEquals(listOf(reviewingQueueUri(vm, 20)), vm.trashController.pendingRequest.value?.uris)
        backend.trashed.add(reviewingQueueUri(vm, 20))
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = done(vm)
        assertEquals(21, state.deleted)
        assertEquals(1, state.skipped)
        assertEquals(210L, state.freedBytes)
        assertEquals(21, state.trashedUris.size)
    }

    /** 队列第 index 张的 uri（Reviewing 态辅助）。 */
    private fun reviewingQueueUri(vm: SwipeReviewViewModel, index: Int): String =
        reviewing(vm).queue[index].uri
}
