package com.mamba.picme.features.gallery.organize

import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.trash.TrashBackend
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
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
class OrganizeCategoryViewModelTest {

    /** 截图类目样例：relativePath 命中 Screenshots 即归 SCREENSHOTS。 */
    private fun shot(uri: String, sizeBytes: Long = 1_000_000L) = OrganizeItem(
        uri = uri,
        isVideo = false,
        captureDate = 1_000L,
        sizeBytes = sizeBytes,
        relativePath = "Pictures/Screenshots/",
        ocrText = null,
        pixelArea = 12_000_000L,
        labels = null,
        hasFace = false,
        aestheticScore = null,
        faceQualityScore = null,
    )

    /** 非截图样例（DCIM 相机目录），用于验证类目过滤。 */
    private fun photo(uri: String) = shot(uri).copy(relativePath = "DCIM/Camera/")

    private class FakeBackend : TrashBackend {
        override val isSupported: Boolean = true
        val trashed = mutableSetOf<String>()

        override fun buildTrashToken(uris: List<String>): Any = "trash-token"
        override fun buildRestoreToken(uris: List<String>): Any = "restore-token"

        // 已 trash 的不算残留（IS_TRASHED 语义，同 TrashSessionControllerTest.FakeBackend）
        override fun queryExisting(uris: List<String>): List<String> =
            uris.filter { uri -> uri !in trashed }
    }

    /** JVM 单测无 Room/MediaStore：mockk 拦截 loadItems，answers 读 var 支持恢复后重载换数据。 */
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
    ): OrganizeCategoryViewModel {
        // VM 内 outcomes collect 是无限订阅：挂独立 TestScope（非 runTest 子作用域），
        // 避免 runTest teardown 等待永活 collector 报 UncompletedCoroutinesError
        val vmScope = TestScope(scope.testScheduler)
        return OrganizeCategoryViewModel(
            category = OrganizeCategory.SCREENSHOTS,
            organizeRepository = repo.mock,
            trashBackend = backend,
            coroutineScope = vmScope,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun ready(vm: OrganizeCategoryViewModel): OrganizeCategoryUiState.Ready =
        vm.uiState.value as OrganizeCategoryUiState.Ready

    @Test
    fun `init filters category items and ai-preselects all`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b"), photo("c")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = ready(vm)
        assertEquals(listOf("a", "b"), state.items.map { item -> item.uri })
        assertEquals(setOf("a", "b"), state.selected)
        assertFalse(state.trashed)
    }

    @Test
    fun `toggle flips uri selection`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.toggle("a")
        assertEquals(setOf("b"), ready(vm).selected)
        vm.toggle("a")
        assertEquals(setOf("a", "b"), ready(vm).selected)
    }

    @Test
    fun `selectAll and deselectAll`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.deselectAll()
        assertTrue(ready(vm).selected.isEmpty())
        vm.selectAll()
        assertEquals(setOf("a", "b"), ready(vm).selected)
    }

    @Test
    fun `deleteSelected issues pending trash request with category tag`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.toggle("b") // 只删 a
        vm.deleteSelected()
        advanceUntilIdle()
        val pending = vm.trashController.pendingRequest.value
        assertEquals(listOf("a"), pending?.uris)
        assertEquals(OrganizeCategory.SCREENSHOTS.name, pending?.tag)
        assertFalse(pending?.isRestore ?: true)
    }

    @Test
    fun `full trash success empties list into completion state`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a", 100L), shot("b", 200L)) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.deleteSelected()
        advanceUntilIdle()
        backend.trashed.addAll(listOf("a", "b")) // 用户允许
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = ready(vm)
        assertTrue(state.trashed)
        assertTrue(state.items.isEmpty())
        assertTrue(state.selected.isEmpty())
        assertEquals(2, state.trashedCount)
        assertEquals(300L, state.trashedBytes)
        assertEquals(listOf("a", "b"), state.lastTrashedUris)
        assertNull(vm.trashController.pendingRequest.value)
    }

    @Test
    fun `partial trash removes only trashed items and stays in list state`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b")) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.deleteSelected()
        advanceUntilIdle()
        backend.trashed.add("a") // b 被系统保留
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = ready(vm)
        assertFalse(state.trashed)
        assertEquals(listOf("b"), state.items.map { item -> item.uri })
        assertEquals(setOf("b"), state.selected) // 已删项从选中集剔除
        assertTrue(vm.trashController.partialNotice.value) // 部分拒绝提示由 UI 消费
    }

    @Test
    fun `user denial leaves list unchanged`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.deleteSelected()
        advanceUntilIdle()
        vm.onTrashResult(ok = false)
        advanceUntilIdle()
        val state = ready(vm)
        assertFalse(state.trashed)
        assertEquals(listOf("a", "b"), state.items.map { item -> item.uri })
        assertEquals(setOf("a", "b"), state.selected)
    }

    @Test
    fun `undo restores items and clears completion state`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b")) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.deleteSelected()
        advanceUntilIdle()
        backend.trashed.addAll(listOf("a", "b"))
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        assertTrue(ready(vm).trashed)

        vm.undoLastTrash()
        advanceUntilIdle()
        assertTrue(vm.trashController.pendingRequest.value?.isRestore ?: false)
        assertEquals(listOf("a", "b"), vm.trashController.pendingRequest.value?.uris)

        backend.trashed.clear() // 用户允许恢复
        repo.items = listOf(shot("a"), shot("b")) // 媒体库重新可见
        vm.onRestoreResult(ok = true)
        advanceUntilIdle()
        val state = ready(vm)
        assertFalse(state.trashed)
        assertEquals(listOf("a", "b"), state.items.map { item -> item.uri })
        assertEquals(setOf("a", "b"), state.selected)
        assertEquals(0, state.trashedCount)
        assertEquals(0L, state.trashedBytes)
        assertTrue(state.lastTrashedUris.isEmpty())
    }

    @Test
    fun `ai preselect off loads without selection, toggling on selects all`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b")) }
        val vm = viewModel(this, repo, FakeBackend())
        // init 的 load 在 StandardTestDispatcher 上未推进，先关开关模拟「进入时不预选」
        vm.setAiPreselect(false)
        advanceUntilIdle()
        assertTrue(ready(vm).selected.isEmpty())
        assertFalse(vm.aiPreselectEnabled.value)

        vm.setAiPreselect(true)
        assertEquals(setOf("a", "b"), ready(vm).selected)

        // 完成态不受开关影响
        val backend = FakeBackend()
        val vm2 = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm2.deleteSelected()
        advanceUntilIdle()
        backend.trashed.addAll(listOf("a", "b"))
        vm2.onTrashResult(ok = true)
        advanceUntilIdle()
        vm2.setAiPreselect(true)
        assertTrue(ready(vm2).selected.isEmpty())
    }

    @Test
    fun `empty category loads ready with empty selection`() = runTest {
        val repo = FakeRepo().apply { items = listOf(photo("c")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = ready(vm)
        assertTrue(state.items.isEmpty())
        assertTrue(state.selected.isEmpty())
        assertFalse(state.trashed) // 空类目 ≠ 完成态
    }
}
