package com.mamba.picme.features.gallery.organize

import com.mamba.picme.domain.organize.AppSource
import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.organize.OrganizeThresholds
import com.mamba.picme.domain.organize.ProtectReason
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.trash.TrashBackend
import io.mockk.coEvery
import io.mockk.mockk
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
class OrganizeSourceViewModelTest {

    /** 微信 scoped storage 路径样例（com.tencent.mm 关键词命中 WECHAT）。 */
    private fun wechatItem(uri: String, sizeBytes: Long = 1_000_000L, captureDate: Long = 1_000L) =
        OrganizeItem(
            uri = uri,
            isVideo = false,
            captureDate = captureDate,
            sizeBytes = sizeBytes,
            relativePath = "Android/data/com.tencent.mm/MicroMsg/attachment/",
            ocrText = null,
            pixelArea = 12_000_000L,
            labels = null,
            hasFace = false,
            aestheticScore = null,
            faceQualityScore = null,
        )

    /** 相机样例（DCIM 命中 CAMERA），用于验证来源过滤。 */
    private fun cameraItem(uri: String) =
        wechatItem(uri).copy(relativePath = "DCIM/Camera/")

    /** 截图样例（Screenshots 命中 SCREENSHOTS，优先于 DCIM/CAMERA 之外的他源）。 */
    private fun screenshotItem(uri: String) =
        wechatItem(uri).copy(relativePath = "Pictures/Screenshots/")

    /** 强模糊（HIGH 档中点）：裁定归 LOW_QUALITY_PHOTOS，阈值校准不过期。 */
    private val strongBlur: Float =
        OrganizeThresholds.BLUR_VARIANCE_LOW * OrganizeThresholds.STRONG_SIGNAL_FACTOR / 2

    /** 老照片拍摄时间：早于 now − OLD_PHOTO_YEARS 年（多一年余量，远离边界）。 */
    private fun oldCaptureDate(now: Long): Long =
        now - (OrganizeThresholds.OLD_PHOTO_YEARS + 1) * 365L * 24 * 3600 * 1000

    /** 微信低质照片：ValueGuard 保护判定的载体（captureDate 决定是否 OLD_PHOTO）。 */
    private fun wechatBlur(uri: String, captureDate: Long) =
        wechatItem(uri).copy(captureDate = captureDate, blurScore = strongBlur)

    private class FakeBackend : TrashBackend {
        override val isSupported: Boolean = true
        val trashed = mutableSetOf<String>()

        override fun buildTrashToken(uris: List<String>): Any = "trash-token"
        override fun buildRestoreToken(uris: List<String>): Any = "restore-token"

        // 已 trash 的不算残留（IS_TRASHED 语义，同 TrashSessionControllerTest.FakeBackend）
        override fun queryExisting(uris: List<String>): List<String> =
            uris.filter { uri -> uri !in trashed }

        override suspend fun trySilentTrash(uris: List<String>): List<String>? = null
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
        source: AppSource = AppSource.WECHAT,
    ): OrganizeSourceViewModel {
        // VM 内 outcomes collect 是无限订阅：挂独立 TestScope（非 runTest 子作用域），
        // 避免 runTest teardown 等待永活 collector 报 UncompletedCoroutinesError
        val vmScope = TestScope(scope.testScheduler)
        return OrganizeSourceViewModel(
            source = source,
            organizeRepository = repo.mock,
            trashBackend = backend,
            coroutineScope = vmScope,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun ready(vm: OrganizeSourceViewModel): OrganizeSourceUiState.Ready =
        vm.uiState.value as OrganizeSourceUiState.Ready

    @Test
    fun `init filters mixed library to target source only`() = runTest {
        // 多来源混杂（微信/相机/截图/未识别 OTHER）：只出目标 WECHAT；
        // w1/w2 无任何类目信号（CategoryArbiter 不定类）仍入网格——来源页是浏览视角
        val repo = FakeRepo().apply {
            items = listOf(
                wechatItem("w1"),
                wechatItem("w2").copy(isVideo = true),
                cameraItem("c1"),
                screenshotItem("s1"),
                wechatItem("o1").copy(relativePath = "Pictures/random/"),
            )
        }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = ready(vm)
        assertEquals(listOf("w1", "w2"), state.items.map { entry -> entry.item.uri })
        assertTrue(state.items.none { entry -> entry.isProtected })
    }

    @Test
    fun `wechat video items are included`() = runTest {
        val repo = FakeRepo().apply {
            items = listOf(wechatItem("v1").copy(isVideo = true))
        }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        assertEquals(listOf("v1"), ready(vm).items.map { entry -> entry.item.uri })
        assertTrue(ready(vm).items.first().item.isVideo)
    }

    @Test
    fun `selected starts empty without ai preselect`() = runTest {
        val repo = FakeRepo().apply { items = listOf(wechatItem("w1"), wechatItem("w2")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        assertTrue(ready(vm).selected.isEmpty())
    }

    @Test
    fun `duplicate uri rows are deduped to single item`() = runTest {
        // media_assets.uri 非唯一索引：重复行必须去重，否则网格同 key 硬崩 LazyVerticalGrid
        val repo = FakeRepo().apply { items = listOf(wechatItem("w1"), wechatItem("w1")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        assertEquals(listOf("w1"), ready(vm).items.map { entry -> entry.item.uri })
    }

    @Test
    fun `protected entry is not selectable via toggle`() = runTest {
        val now = System.currentTimeMillis()
        val repo = FakeRepo().apply {
            items = listOf(
                // 强模糊 + 老拍摄时间 → LOW_QUALITY_PHOTOS + OLD_PHOTO（ValueGuard 红线）
                wechatBlur("old", captureDate = oldCaptureDate(now)),
                wechatItem("plain"),
            )
        }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = ready(vm)
        val protected = state.items.first { entry -> entry.item.uri == "old" }
        assertTrue(protected.isProtected)
        assertEquals(setOf(ProtectReason.OLD_PHOTO), protected.protectReasons)

        vm.toggle("old")
        assertTrue(ready(vm).selected.isEmpty()) // 红线：保护项勾选被静默拒绝

        vm.toggle("plain")
        assertEquals(setOf("plain"), ready(vm).selected)
    }

    @Test
    fun `deleteSelected skips protected entries which cannot enter selection`() = runTest {
        val now = System.currentTimeMillis()
        val repo = FakeRepo().apply {
            items = listOf(
                wechatBlur("old", captureDate = oldCaptureDate(now)),
                wechatItem("plain"),
            )
        }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.toggle("old") // 拒绝
        vm.toggle("plain")
        vm.deleteSelected()
        advanceUntilIdle()
        val pending = vm.trashController.pendingRequest.value
        assertEquals(listOf("plain"), pending?.uris) // 保护项不进删除集
    }

    @Test
    fun `toSourceSections splits protected and recent-first date descending`() {
        val entries = listOf(
            SourceEntry(wechatItem("a", captureDate = 100L), emptySet()),
            SourceEntry(wechatItem("b", captureDate = 300L), emptySet()),
            SourceEntry(wechatItem("c", captureDate = 200L), emptySet()),
            SourceEntry(
                wechatItem("p1", captureDate = 500L),
                setOf(ProtectReason.SCARCE_PERSON),
            ),
            SourceEntry(
                wechatItem("p2", captureDate = 400L),
                setOf(ProtectReason.OLD_PHOTO),
            ),
        )
        val sections = entries.toSourceSections()
        // 主段日期降序（新→旧）
        assertEquals(listOf("b", "c", "a"), sections.recentFirst.map { entry -> entry.item.uri })
        // 保护段单独成段且同样日期降序
        assertEquals(listOf("p1", "p2"), sections.protectedItems.map { entry -> entry.item.uri })
        // 两段互斥且完备
        assertEquals(entries.size, sections.recentFirst.size + sections.protectedItems.size)
    }

    @Test
    fun `toggle flips uri selection for non-protected entries`() = runTest {
        val repo = FakeRepo().apply { items = listOf(wechatItem("w1"), wechatItem("w2")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.toggle("w1")
        assertEquals(setOf("w1"), ready(vm).selected)
        vm.toggle("w1")
        assertTrue(ready(vm).selected.isEmpty())
    }

    @Test
    fun `deleteSelected issues pending trash request with source tag`() = runTest {
        val repo = FakeRepo().apply { items = listOf(wechatItem("w1"), wechatItem("w2")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.toggle("w1")
        vm.deleteSelected()
        advanceUntilIdle()
        val pending = vm.trashController.pendingRequest.value
        assertEquals(listOf("w1"), pending?.uris)
        assertEquals(AppSource.WECHAT.name, pending?.tag)
        assertFalse(pending?.isRestore ?: true)
    }

    @Test
    fun `full trash success empties list into completion state and undo restores`() = runTest {
        val repo = FakeRepo().apply {
            items = listOf(wechatItem("a", sizeBytes = 100L), wechatItem("b", sizeBytes = 200L))
        }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.toggle("a")
        vm.toggle("b")
        vm.deleteSelected()
        advanceUntilIdle()
        backend.trashed.addAll(listOf("a", "b")) // 用户允许
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = ready(vm)
        assertTrue(state.trashed)
        assertTrue(state.items.isEmpty())
        assertEquals(2, state.trashedCount)
        assertEquals(300L, state.trashedBytes)
        assertEquals(listOf("a", "b"), state.lastTrashedUris)
        assertNull(vm.trashController.pendingRequest.value)

        // undo：恢复授权通过后重载回网格态（selected 仍空集——无 AI 预选语义）
        vm.undoLastTrash()
        advanceUntilIdle()
        assertTrue(vm.trashController.pendingRequest.value?.isRestore ?: false)
        backend.trashed.clear() // 用户允许恢复
        repo.items = listOf(wechatItem("a", sizeBytes = 100L), wechatItem("b", sizeBytes = 200L))
        vm.onRestoreResult(ok = true)
        advanceUntilIdle()
        val restored = ready(vm)
        assertFalse(restored.trashed)
        assertEquals(listOf("a", "b"), restored.items.map { entry -> entry.item.uri })
        assertTrue(restored.selected.isEmpty())
        assertEquals(0, restored.trashedCount)
    }

    @Test
    fun `partial trash removes only trashed items and stays in list state`() = runTest {
        val repo = FakeRepo().apply { items = listOf(wechatItem("a"), wechatItem("b")) }
        val backend = FakeBackend()
        val vm = viewModel(this, repo, backend)
        advanceUntilIdle()
        vm.toggle("a")
        vm.toggle("b")
        vm.deleteSelected()
        advanceUntilIdle()
        backend.trashed.add("a") // b 被系统保留
        vm.onTrashResult(ok = true)
        advanceUntilIdle()
        val state = ready(vm)
        assertFalse(state.trashed)
        assertEquals(listOf("b"), state.items.map { entry -> entry.item.uri })
        assertEquals(setOf("b"), state.selected) // 已删项从选中集剔除，剩余项保持选中
        assertTrue(vm.trashController.partialNotice.value)
    }

    @Test
    fun `user denial leaves list unchanged`() = runTest {
        val repo = FakeRepo().apply { items = listOf(wechatItem("a")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        vm.toggle("a")
        vm.deleteSelected()
        advanceUntilIdle()
        vm.onTrashResult(ok = false)
        advanceUntilIdle()
        val state = ready(vm)
        assertFalse(state.trashed)
        assertEquals(listOf("a"), state.items.map { entry -> entry.item.uri })
        assertEquals(setOf("a"), state.selected)
    }

    @Test
    fun `empty source loads ready with empty selection`() = runTest {
        val repo = FakeRepo().apply { items = listOf(cameraItem("c1")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = ready(vm)
        assertTrue(state.items.isEmpty())
        assertTrue(state.selected.isEmpty())
        assertFalse(state.trashed) // 空来源 ≠ 完成态
    }
}
