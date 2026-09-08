package com.mamba.picme.features.gallery.organize

import com.mamba.picme.domain.organize.ClassifiedItem
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.organize.OrganizeConfidence
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
class OrganizeCategoryViewModelTest {

    /** 截图类目样例：relativePath 命中 Screenshots 即归 SCREEN_CONTENT（路径强信号，恒 HIGH）。 */
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

    /** 强模糊：HIGH 档（blur < BLUR_VARIANCE_LOW × STRONG_SIGNAL_FACTOR）内取中点，阈值校准不过期。 */
    private val strongBlur: Float =
        OrganizeThresholds.BLUR_VARIANCE_LOW * OrganizeThresholds.STRONG_SIGNAL_FACTOR / 2

    /** 边界模糊：MEDIUM 档（0.5×阈值 ~ 1×阈值）内取中点。 */
    private val borderlineBlur: Float =
        OrganizeThresholds.BLUR_VARIANCE_LOW * (1 + OrganizeThresholds.STRONG_SIGNAL_FACTOR) / 2

    /** 老照片拍摄时间：早于 now − OLD_PHOTO_YEARS 年（多一年余量，远离边界）。 */
    private fun oldCaptureDate(now: Long): Long =
        now - (OrganizeThresholds.OLD_PHOTO_YEARS + 1) * 365L * 24 * 3600 * 1000

    /**
     * 低质照片样例：blurScore < BLUR_VARIANCE_LOW 裁定归 LOW_QUALITY_PHOTOS；
     * blur < BLUR_VARIANCE_LOW × STRONG_SIGNAL_FACTOR = HIGH，其间 = MEDIUM。captureDate 显式传入
     * ——ValueGuard 保守偏置会把 1970 老时间戳判 OLD_PHOTO 保护，非保护用例必须给近期时间。
     */
    private fun blurPhoto(uri: String, captureDate: Long, blurScore: Float) = photo(uri).copy(
        captureDate = captureDate,
        blurScore = blurScore,
    )

    /** 精确重复组成员：exactDupGroupSize ≥ 2 裁定归 DUPLICATES 且恒 HIGH 非保护。 */
    private fun dupPhoto(uri: String, groupKey: String, groupSize: Int, captureDate: Long = 1_000L) =
        photo(uri).copy(
            captureDate = captureDate,
            exactDupGroupSize = groupSize,
            exactDupGroupKey = groupKey,
        )

    /** ClassifiedItem 夹具：category 不参与 toSections 分组，固定取值即可。 */
    private fun classified(
        uri: String,
        confidence: OrganizeConfidence,
        protectReasons: Set<ProtectReason> = emptySet(),
    ) = ClassifiedItem(
        item = photo(uri),
        category = OrganizeCategory.SCREEN_CONTENT,
        confidence = confidence,
        protectReasons = protectReasons,
    )

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
        category: OrganizeCategory = OrganizeCategory.SCREEN_CONTENT,
    ): OrganizeCategoryViewModel {
        // VM 内 outcomes collect 是无限订阅：挂独立 TestScope（非 runTest 子作用域），
        // 避免 runTest teardown 等待永活 collector 报 UncompletedCoroutinesError
        val vmScope = TestScope(scope.testScheduler)
        return OrganizeCategoryViewModel(
            category = category,
            organizeRepository = repo.mock,
            trashBackend = backend,
            coroutineScope = vmScope,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun ready(vm: OrganizeCategoryViewModel): OrganizeCategoryUiState.Ready =
        vm.uiState.value as OrganizeCategoryUiState.Ready

    @Test
    fun `init filters category items and ai-preselects high confidence non-protected`() = runTest {
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("b"), photo("c")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = ready(vm)
        assertEquals(listOf("a", "b"), state.items.map { entry -> entry.item.uri })
        // SCREEN_CONTENT 路径判定恒 HIGH 且该类目不设保护 → 全部预选
        assertEquals(setOf("a", "b"), state.selected)
        assertFalse(state.trashed)
    }

    @Test
    fun `duplicate uri rows are deduped to single item`() = runTest {
        // media_assets.uri 非唯一索引：重复行必须去重，否则网格同 key 硬崩 LazyVerticalGrid
        val repo = FakeRepo().apply { items = listOf(shot("a"), shot("a"), shot("b")) }
        val vm = viewModel(this, repo, FakeBackend())
        advanceUntilIdle()
        val state = ready(vm)
        assertEquals(listOf("a", "b"), state.items.map { entry -> entry.item.uri })
        assertEquals(setOf("a", "b"), state.selected)
    }

    @Test
    fun `preselect only picks HIGH confidence non-protected items`() = runTest {
        val now = System.currentTimeMillis()
        val repo = FakeRepo().apply {
            items = listOf(
                // HIGH：强模糊（HIGH 档中点），近期拍摄非保护
                blurPhoto("high", captureDate = now, blurScore = strongBlur),
                // MEDIUM：边界模糊（MEDIUM 档中点）
                blurPhoto("medium", captureDate = now, blurScore = borderlineBlur),
                // HIGH（强模糊）但 protected：老照片 → OLD_PHOTO
                blurPhoto("old", captureDate = oldCaptureDate(now), blurScore = strongBlur),
            )
        }
        val vm = viewModel(this, repo, FakeBackend(), OrganizeCategory.LOW_QUALITY_PHOTOS)
        advanceUntilIdle()
        val state = ready(vm)
        assertEquals(listOf("high", "medium", "old"), state.items.map { entry -> entry.item.uri })
        assertEquals(setOf("high"), state.selected)
    }

    @Test
    fun `toSections splits suggested review protected disjoint and complete`() {
        val entries = listOf(
            classified("a", OrganizeConfidence.HIGH),
            classified("b", OrganizeConfidence.MEDIUM),
            classified("c", OrganizeConfidence.LOW),
            classified("d", OrganizeConfidence.HIGH, protectReasons = setOf(ProtectReason.OLD_PHOTO)),
            // MEDIUM + protected：保护优先于 review 段
            classified("e", OrganizeConfidence.MEDIUM, protectReasons = setOf(ProtectReason.USER_ENGAGED)),
        )
        val sections = entries.toSections()
        assertEquals(listOf("a"), sections.suggested.map { entry -> entry.item.uri })
        assertEquals(listOf("b", "c"), sections.review.map { entry -> entry.item.uri })
        assertEquals(listOf("d", "e"), sections.protectedItems.map { entry -> entry.item.uri })
        assertEquals(entries.size, sections.suggested.size + sections.review.size + sections.protectedItems.size)
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
        val now = System.currentTimeMillis()
        val repo = FakeRepo().apply {
            items = listOf(
                blurPhoto("a", captureDate = now, blurScore = strongBlur),
                blurPhoto("b", captureDate = now, blurScore = strongBlur),
                // protected（OLD_PHOTO）：selectAll 不得勾穿保护段（spec §6.3 永不预选）
                blurPhoto("p", captureDate = oldCaptureDate(now), blurScore = strongBlur),
            )
        }
        val vm = viewModel(this, repo, FakeBackend(), OrganizeCategory.LOW_QUALITY_PHOTOS)
        advanceUntilIdle()
        vm.deselectAll()
        assertTrue(ready(vm).selected.isEmpty())
        vm.selectAll()
        assertEquals(setOf("a", "b"), ready(vm).selected)
    }

    @Test
    fun `duplicates preselect keeps one keeper per exact group - latest captureDate`() = runTest {
        // 3 张同组精确重复：预选只含 2 张，keeper = 组内 captureDate 最大者（d3）
        val repo = FakeRepo().apply {
            items = listOf(
                dupPhoto("d1", "md5a", 3, captureDate = 100L),
                dupPhoto("d2", "md5a", 3, captureDate = 200L),
                dupPhoto("d3", "md5a", 3, captureDate = 300L),
            )
        }
        val vm = viewModel(this, repo, FakeBackend(), OrganizeCategory.DUPLICATES)
        advanceUntilIdle()
        val state = ready(vm)
        assertEquals(3, state.items.size)
        assertEquals(setOf("d1", "d2"), state.selected)
    }

    @Test
    fun `duplicates selectAll keeps one keeper per group, keyless similar-only member unaffected`() = runTest {
        // selectAll 同样每组留 1 张（keeper=d3）；无组标识的 similar-only 成员（MEDIUM 请确认段）
        // 不参与 keeper 排除，仍随全选被圈选
        val repo = FakeRepo().apply {
            items = listOf(
                dupPhoto("d1", "md5a", 3, captureDate = 100L),
                dupPhoto("d2", "md5a", 3, captureDate = 200L),
                dupPhoto("d3", "md5a", 3, captureDate = 300L),
                photo("s1").copy(similarDupGroupSize = 3),
            )
        }
        val vm = viewModel(this, repo, FakeBackend(), OrganizeCategory.DUPLICATES)
        advanceUntilIdle()
        vm.deselectAll()
        vm.selectAll()
        assertEquals(setOf("d1", "d2", "s1"), ready(vm).selected)
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
        assertEquals(OrganizeCategory.SCREEN_CONTENT.name, pending?.tag)
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
        assertEquals(listOf("b"), state.items.map { entry -> entry.item.uri })
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
        assertEquals(listOf("a", "b"), state.items.map { entry -> entry.item.uri })
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
        assertEquals(listOf("a", "b"), state.items.map { entry -> entry.item.uri })
        assertEquals(setOf("a", "b"), state.selected)
        assertEquals(0, state.trashedCount)
        assertEquals(0L, state.trashedBytes)
        assertTrue(state.lastTrashedUris.isEmpty())
    }

    @Test
    fun `ai preselect off loads without selection, toggling on selects high non-protected`() = runTest {
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
