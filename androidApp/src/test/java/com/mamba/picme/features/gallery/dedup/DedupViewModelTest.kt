package com.mamba.picme.features.gallery.dedup

import android.content.IntentSender
import com.mamba.picme.domain.repository.BackfillBatchResult
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupMember
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanProgress
import com.mamba.picme.domain.dedup.DedupScanSession
import com.mamba.picme.domain.dedup.DedupScanState
import com.mamba.picme.domain.dedup.DedupTrashManager
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.VersionRole
import com.mamba.picme.domain.organize.AppSource
import com.mamba.picme.domain.organize.OrganizeItem
import com.mamba.picme.domain.organize.StorageStory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DedupViewModelTest {

    private fun member(
        uri: String,
        sizeBytes: Long = 1_000L,
        captureDate: Long = 0L,
        modifiedAt: Long = 0L,
        pixelArea: Int = 100,
        contentType: DedupContentType = DedupContentType.GENERAL,
    ) = DedupMember(
        uri = uri,
        sizeBytes = sizeBytes,
        mime = "image/jpeg",
        captureDate = captureDate,
        modifiedAt = modifiedAt,
        pixelArea = pixelArea,
        aestheticScore = null,
        role = VersionRole.ORIGINAL,
        md5 = null,
        phash = null,
        contentType = contentType,
    )

    private fun group(
        id: String,
        level: DedupLevel,
        members: List<DedupMember>,
        // 与生产建组口径一致：SCENE 默认不预选（autoPreselectedFor）
        autoPreselected: Boolean = level != DedupLevel.SCENE,
    ) = DedupGroup(
        id = id,
        level = level,
        members = members,
        keepUri = members.first().uri,
        autoPreselected = autoPreselected,
    )

    /** 假扫描会话：MutableStateFlow 进度 + 调用计数（JVM 接缝，服务端会话替身）。 */
    private class FakeSession : DedupScanSession {
        override val progress = MutableStateFlow<DedupScanProgress?>(null)
        var startCount: Int = 0
            private set
        var pauseCount: Int = 0
            private set
        var resumeCount: Int = 0
            private set
        var cancelCount: Int = 0
            private set
        var lastConfig: DedupScanConfig? = null
            private set

        override fun startScan(config: DedupScanConfig) {
            startCount++
            lastConfig = config
        }

        override fun pauseScan() {
            pauseCount++
        }

        override fun resumeScan() {
            resumeCount++
        }

        override fun cancelScan() {
            cancelCount++
        }
    }

    private fun activeProgress(
        state: DedupScanState,
        foundGroups: List<DedupGroup> = emptyList(),
        scanned: Int = 0,
        total: Int = 0,
    ) = DedupScanProgress(
        state = state,
        phase = DedupLevel.EXACT,
        phaseIndex = 1,
        phaseCount = 1,
        scanned = scanned,
        total = total,
        foundGroups = foundGroups,
    )

    private fun completedProgress(resultGroups: List<DedupGroup>) = DedupScanProgress(
        state = DedupScanState.COMPLETED,
        phase = DedupLevel.EXACT,
        phaseIndex = 1,
        phaseCount = 1,
        scanned = 0,
        total = 0,
        foundGroups = emptyList(),
        resultGroups = resultGroups,
    )

    private fun fakeTrashManager(supported: Boolean = true): DedupTrashManager = mockk {
        every { isSupported } returns supported
        every { buildTrashIntent(any()) } returns mockk<IntentSender>()
        every { buildRestoreIntent(any()) } returns mockk<IntentSender>()
        every { queryExisting(any()) } returns emptyList()
    }

    /** 排空协程：本环境 advanceUntilIdle 不驱动 backgroundScope，先推进 1ms（同 ChatPhotoPickerViewModelTest 范式）。 */
    private fun TestScope.settle() {
        advanceTimeBy(1)
        advanceUntilIdle()
    }

    /** 整理中心统计源替身：JVM 单测无 Room/MediaStore，恒空流（WhileSubscribed 未订阅时不上游）。 */
    private fun fakeOrganizeRepository(): OrganizeRepository = mockk {
        every { observeItems() } returns flowOf(emptyList())
        // init 后台补算桩：恒空批次立即退出循环，消除既有测试静默走 onFailure 失败分支
        coEvery { backfillQualitySignals(any(), any()) } returns BackfillBatchResult(attempted = 0, written = 0)
    }

    /** 预分析落点替身：hub 新鲜度行数据源，恒 0（从未分析）。 */
    private fun fakeSettingsRepository(): UserSettingsRepository = mockk {
        every { organizePreAnalysisRanAtMsFlow } returns flowOf(0L)
    }

    private fun viewModel(
        session: DedupScanSession,
        trashManager: DedupTrashManager = fakeTrashManager(),
        organizeRepository: OrganizeRepository = fakeOrganizeRepository(),
        settingsRepository: UserSettingsRepository = fakeSettingsRepository(),
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ) = DedupViewModel(
        session = session,
        trashManager = trashManager,
        organizeRepository = organizeRepository,
        settingsRepository = settingsRepository,
        coroutineScope = scope,
        ioDispatcher = ioDispatcher,
    )

    @Test
    fun `backfill loop exits when batch returns zero`() = runTest {
        val organizeRepository = mockk<OrganizeRepository> {
            every { observeItems() } returns flowOf(emptyList())
            coEvery { backfillQualitySignals(any(), any()) } returnsMany listOf(
                BackfillBatchResult(attempted = 200, written = 200),
                BackfillBatchResult(attempted = 0, written = 0),
            )
        }
        viewModel(
            FakeSession(),
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            organizeRepository = organizeRepository,
        )
        settle()

        // 首批满批 200 → 次批 0 → 循环退出，共 2 次调用
        coVerify(exactly = 2) { organizeRepository.backfillQualitySignals(any(), any()) }
    }

    @Test
    fun `backfill permanently failed rows are excluded so later rows proceed`() = runTest {
        val organizeRepository = mockk<OrganizeRepository> {
            every { observeItems() } returns flowOf(emptyList())
            coEvery { backfillQualitySignals(any(), any()) } returnsMany listOf(
                // 批 1：bad1 解码失败（attempted=2, written=1）——旧逻辑 written>0 续批但坏行仍在头部
                BackfillBatchResult(attempted = 2, written = 1, failedUris = listOf("bad1")),
                // 批 2：排除 bad1 后后续行正常补算
                BackfillBatchResult(attempted = 1, written = 1),
                // 批 3：无残留（attempted=0）→ 退出
                BackfillBatchResult(attempted = 0, written = 0),
            )
        }
        viewModel(
            FakeSession(),
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            organizeRepository = organizeRepository,
        )
        settle()

        coVerify(exactly = 3) { organizeRepository.backfillQualitySignals(any(), any()) }
        // 第 2/3 批必须携带排除集（bad1 不再占批次头部）
        coVerify { organizeRepository.backfillQualitySignals(any(), match { excluded -> "bad1" in excluded }) }
    }

    @Test
    fun `backfill all-failed batch does not stop the loop early`() = runTest {
        val organizeRepository = mockk<OrganizeRepository> {
            every { observeItems() } returns flowOf(emptyList())
            coEvery { backfillQualitySignals(any(), any()) } returnsMany listOf(
                // 批 1 全败（written=0）：旧逻辑在此退出，后续行永远补不上
                BackfillBatchResult(attempted = 2, written = 0, failedUris = listOf("bad1", "bad2")),
                // 批 2：排除后新行补算成功
                BackfillBatchResult(attempted = 1, written = 1),
                BackfillBatchResult(attempted = 0, written = 0),
            )
        }
        viewModel(
            FakeSession(),
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            organizeRepository = organizeRepository,
        )
        settle()

        coVerify(exactly = 3) { organizeRepository.backfillQualitySignals(any(), any()) }
        coVerify {
            organizeRepository.backfillQualitySignals(any(), match { excluded -> excluded.containsAll(listOf("bad1", "bad2")) })
        }
    }

    @Test
    fun `session running progress drives Scanning state progressively`() = runTest {
        val g1 = group("g1", DedupLevel.EXACT, listOf(member("a"), member("b")))
        val g2 = group("g2", DedupLevel.EXACT, listOf(member("c"), member("d")))
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = activeProgress(
            DedupScanState.RUNNING,
            foundGroups = listOf(g1),
            scanned = 10,
            total = 100,
        ).copy(phaseIndex = 1, phaseCount = 2)
        settle()

        var state = vm.uiState.value as DedupUiState.Scanning
        assertEquals(DedupLevel.EXACT, state.phase)
        assertEquals(1, state.phaseIndex)
        assertEquals(2, state.phaseCount)
        assertEquals(10, state.scanned)
        assertEquals(100, state.total)
        assertEquals(listOf(g1), state.foundGroups)
        assertFalse(state.paused)

        session.progress.value = activeProgress(
            DedupScanState.RUNNING,
            foundGroups = listOf(g2, g1),
            scanned = 60,
            total = 100,
        ).copy(phase = DedupLevel.VISUAL, phaseIndex = 2, phaseCount = 2)
        settle()

        state = vm.uiState.value as DedupUiState.Scanning
        assertEquals(DedupLevel.VISUAL, state.phase)
        assertEquals(2, state.phaseIndex)
        assertEquals(60, state.scanned)
        // 最新发现排前（由会话产出，VM 整体消费）
        assertEquals(listOf(g2, g1), state.foundGroups)
    }

    @Test
    fun `setKeep marks userOverride and changes deleteUris`() = runTest {
        // a 文件更大：完成按 BEST_QUALITY 重算后仍保留 a（排序恒等）
        val g = group("g1", DedupLevel.EXACT, listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)))
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()
        val results = vm.uiState.value as DedupUiState.Results
        assertEquals("a", results.groups.single().keepUri)
        assertEquals(listOf("b"), results.groups.single().deleteUris)

        vm.setKeep("g1", "b")

        val updated = (vm.uiState.value as DedupUiState.Results).groups.single()
        assertEquals("b", updated.keepUri)
        assertTrue(updated.userOverride)
        assertEquals(listOf("a"), updated.deleteUris)
        assertEquals(3_000L, updated.reclaimBytes)
    }

    @Test
    fun `applyPolicy skips userOverride groups`() = runTest {
        val g1 = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", modifiedAt = 100L), member("b", modifiedAt = 200L)),
        )
        val g2 = group(
            "g2", DedupLevel.VISUAL,
            listOf(member("c", modifiedAt = 300L), member("d", modifiedAt = 400L)),
        )
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g1, g2))
        settle()
        vm.setKeep("g1", "b") // 用户手动保留 b

        vm.applyPolicy(KeepPolicy.LATEST)

        val state = vm.uiState.value as DedupUiState.Results
        assertEquals(KeepPolicy.LATEST, state.policy)
        val updatedG1 = state.groups.first { grp -> grp.id == "g1" }
        // userOverride 组不动
        assertEquals("b", updatedG1.keepUri)
        assertTrue(updatedG1.userOverride)
        assertEquals(listOf("a", "b"), updatedG1.members.map { m -> m.uri })
        val updatedG2 = state.groups.first { grp -> grp.id == "g2" }
        // 未覆盖组按 LATEST 重排：modifiedAt 最新的 d 排第一并成为 keep
        assertEquals("d", updatedG2.keepUri)
        assertEquals(listOf("d", "c"), updatedG2.members.map { m -> m.uri })
    }

    @Test
    fun `smartSelectAll does not touch SCENE groups`() = runTest {
        val exact = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", captureDate = 1L), member("b", captureDate = 2L)),
        )
        val scene = group(
            "g2", DedupLevel.SCENE,
            listOf(member("c", captureDate = 3L), member("d", captureDate = 4L)),
        )
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(exact, scene))
        settle()
        vm.setKeep("g1", "a")
        vm.setKeep("g2", "d")

        vm.smartSelectAll()

        val state = vm.uiState.value as DedupUiState.Results
        val exactGroup = state.groups.first { grp -> grp.id == "g1" }
        // EXACT 组清 override 并按当前 policy（BEST_QUALITY：captureDate 最新在前）重算
        assertFalse(exactGroup.userOverride)
        assertEquals("b", exactGroup.keepUri)
        val sceneGroup = state.groups.first { grp -> grp.id == "g2" }
        // SCENE 组保留用户手动选择
        assertTrue(sceneGroup.userOverride)
        assertEquals("d", sceneGroup.keepUri)
    }

    @Test
    fun `pause forwards to session and paused progress maps to paused Scanning`() = runTest {
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = activeProgress(DedupScanState.RUNNING, scanned = 5, total = 100)
        settle()

        vm.pauseScan()
        assertEquals(1, session.pauseCount)
        session.progress.value = activeProgress(DedupScanState.PAUSED, scanned = 5, total = 100)
        settle()
        val paused = vm.uiState.value as DedupUiState.Scanning
        assertTrue(paused.paused)

        vm.resumeScan()
        assertEquals(1, session.resumeCount)
        session.progress.value = activeProgress(DedupScanState.RUNNING, scanned = 5, total = 100)
        settle()
        val resumed = vm.uiState.value as DedupUiState.Scanning
        assertFalse(resumed.paused)
    }

    @Test
    fun `cancelScan forwards to session and cancelled progress returns to Config`() = runTest {
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = activeProgress(DedupScanState.RUNNING)
        settle()
        assertTrue(vm.uiState.value is DedupUiState.Scanning)

        vm.cancelScan()
        assertEquals(1, session.cancelCount)
        // 状态迁移等会话流回推（转发本身不改 uiState）
        assertTrue(vm.uiState.value is DedupUiState.Scanning)

        session.progress.value = activeProgress(DedupScanState.CANCELLED)
        settle()
        assertTrue(vm.uiState.value is DedupUiState.Config)

        // 会话清空（IDLE）对非扫描态是 no-op
        session.progress.value = null
        settle()
        assertTrue(vm.uiState.value is DedupUiState.Config)
    }

    @Test
    fun `deleteSelected on API30 path emits PendingTrash and refusal returns to Results with groups intact`() =
        runTest {
            // a 文件更大：完成按 BEST_QUALITY 重算后排序恒等，deleteUris 仍为 [b]
            val g = group("g1", DedupLevel.EXACT, listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)))
            val session = FakeSession()
            val trash = fakeTrashManager(supported = true)
            val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

            vm.startScan(DedupScanConfig())
            session.progress.value = completedProgress(listOf(g))
            settle()

            vm.deleteSelected()
            settle() // buildTrashIntent 已移入 ioDispatcher 异步构建
            val pending = vm.pendingTrash.value
            assertNotNull(pending)
            assertEquals(listOf("b"), pending?.uris)
            // 授权在途时状态仍是 Results
            assertTrue(vm.uiState.value is DedupUiState.Results)

            vm.onTrashResult(ok = false)

            assertNull(vm.pendingTrash.value)
            val state = vm.uiState.value as DedupUiState.Results
            assertEquals(listOf(g), state.groups)
        }

    @Test
    fun `deleteSelected confirmed moves to Cleaned with reclaimed bytes`() = runTest {
        // a 文件更大：完成按 BEST_QUALITY 重算后仍保留 a，b 进回收站
        val g = group("g1", DedupLevel.EXACT, listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)))
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()
        vm.deleteSelected()
        settle()
        vm.onTrashResult(ok = true)
        settle()

        val cleaned = vm.uiState.value as DedupUiState.Cleaned
        assertEquals(1, cleaned.deletedCount)
        assertEquals(2_000L, cleaned.reclaimedBytes)
        assertEquals(listOf("b"), cleaned.trashedUris)
        // 唯一的组已删干净：无剩余组，Cleaned 只提供「完成」
        assertTrue(cleaned.remainingGroups.isEmpty())
        assertNull(vm.pendingTrash.value)
    }

    @Test
    fun `deleteSelected with no deletable uris is a no-op`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a")))
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()
        vm.deleteSelected()

        assertNull(vm.pendingTrash.value)
        assertTrue(vm.uiState.value is DedupUiState.Results)
    }

    @Test
    fun `startScan is not re-entrant while Scanning`() = runTest {
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig(visualThreshold = 5))
        session.progress.value = activeProgress(DedupScanState.RUNNING)
        settle()
        vm.startScan(DedupScanConfig(visualThreshold = 9))
        settle()

        // 第二次启动被忽略：会话只见第一份配置
        assertEquals(1, session.startCount)
        assertEquals(5, session.lastConfig?.visualThreshold)
    }

    @Test
    fun `setKeep during Scanning is ignored`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a"), member("b")))
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = activeProgress(DedupScanState.RUNNING, foundGroups = listOf(g))
        settle()
        vm.setKeep("g1", "b")

        val state = vm.uiState.value as DedupUiState.Scanning
        // 扫描屏只展示不改选：keep 与 override 均不变
        assertEquals("a", state.foundGroups.single().keepUri)
        assertFalse(state.foundGroups.single().userOverride)
    }

    @Test
    fun `partial trash refusal keeps Results with groups intact and raises notice`() = runTest {
        // a 文件更大：完成按 BEST_QUALITY 重算后排序恒等
        val g = group("g1", DedupLevel.EXACT, listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)))
        val session = FakeSession()
        val trash = mockk<DedupTrashManager> {
            every { isSupported } returns true
            every { buildTrashIntent(any()) } returns mockk<IntentSender>()
            // 部分拒绝：uri 仍存在
            every { queryExisting(any()) } answers { firstArg() }
        }
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()
        vm.deleteSelected()
        settle()
        vm.onTrashResult(ok = true)
        settle()

        assertNull(vm.pendingTrash.value)
        val state = vm.uiState.value as DedupUiState.Results
        assertEquals(listOf(g), state.groups)
        // 一次性 UI 提示置位，消费后复位
        assertTrue(vm.partialTrashNotice.value)
        vm.consumePartialTrashNotice()
        assertFalse(vm.partialTrashNotice.value)
    }

    @Test
    fun `batch delete excludes SCENE groups and deleteSelected only acts on current tab`() = runTest {
        // 每组首张文件更大：完成按 BEST_QUALITY 重算后排序恒等，deleteUris 为各组第二张
        val exact = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)),
        )
        val visual = group(
            "g2", DedupLevel.VISUAL,
            listOf(member("c", sizeBytes = 4_000L), member("d", sizeBytes = 3_000L)),
        )
        val scene = group(
            "g3", DedupLevel.SCENE,
            listOf(member("e", sizeBytes = 6_000L), member("f", sizeBytes = 5_000L)),
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(exact, visual, scene))
        settle()
        val state = vm.uiState.value as DedupUiState.Results
        val groups = state.groups

        // 派生值：仅 EXACT/VISUAL 组的 deleteUris（各 1 张非保留项）
        val batchUris = vm.batchDeleteUris(groups)
        assertEquals(listOf("b", "d"), batchUris.sorted())
        assertEquals(2_000L + 3_000L, vm.batchReclaimBytes(groups, batchUris))

        // 按类型细分：默认落在 EXACT Tab，tab 域口径只含 g1 的 b
        assertEquals(DedupLevel.EXACT, state.selectedTab)
        assertEquals(listOf("b"), vm.tabBatchUris(state))

        vm.deleteSelected()
        settle()

        // 删除流只带当前 Tab（EXACT）的 uris，VISUAL/SCENE 组不参与
        assertEquals(listOf("b"), vm.pendingTrash.value?.uris)
        assertTrue(vm.uiState.value is DedupUiState.Results)
    }

    @Test
    fun `deleteSelected confirmed on VISUAL tab keeps other groups for continueWithRemaining`() = runTest {
        val exact = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)),
        )
        val visual = group(
            "g2", DedupLevel.VISUAL,
            listOf(member("c", sizeBytes = 4_000L), member("d", sizeBytes = 3_000L)),
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(exact, visual))
        settle()
        vm.selectTab(DedupLevel.VISUAL)
        vm.deleteSelected()
        settle()

        // VISUAL Tab 只删 g2 的非保留项 d
        assertEquals(listOf("d"), vm.pendingTrash.value?.uris)

        vm.onTrashResult(ok = true)
        settle()

        val cleaned = vm.uiState.value as DedupUiState.Cleaned
        assertEquals(1, cleaned.deletedCount)
        assertEquals(3_000L, cleaned.reclaimedBytes)
        // EXACT 组未被动过，留给「继续整理」
        assertEquals(listOf("g1"), cleaned.remainingGroups.map { group -> group.id })

        vm.continueWithRemaining()

        val results = vm.uiState.value as DedupUiState.Results
        assertEquals(listOf("g1"), results.groups.map { group -> group.id })
        // 切到还有组的第一个 Tab
        assertEquals(DedupLevel.EXACT, results.selectedTab)
    }

    @Test
    fun `deleteSelected abandons authorization when tab switched during IPC window`() = runTest {
        val exact = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)),
        )
        val visual = group(
            "g2", DedupLevel.VISUAL,
            listOf(member("c", sizeBytes = 4_000L), member("d", sizeBytes = 3_000L)),
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(exact, visual))
        settle()
        vm.deleteSelected()
        // buildTrashIntent 的 IPC 窗口内切 Tab：当前 Tab 选择集与发起时已不一致
        vm.selectTab(DedupLevel.VISUAL)
        settle()

        // 安全放弃：不发授权、不动任何照片，停留 Results
        assertNull(vm.pendingTrash.value)
        assertTrue(vm.uiState.value is DedupUiState.Results)
    }

    @Test
    fun `remainingGroups drop trashed members and recompute keep when keepUri was trashed`() = runTest {
        // 跨级重叠：b 同时属于 EXACT 组（非保留项，会被删）与 VISUAL 组（按画质是保留项）
        val exact = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)),
        )
        val visual = group(
            "g2", DedupLevel.VISUAL,
            listOf(
                member("b", sizeBytes = 2_000L),
                member("c", sizeBytes = 1_500L),
                member("d", sizeBytes = 1_200L),
            ),
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(exact, visual))
        settle()
        // EXACT Tab 删除：b 入回收站
        vm.deleteSelected()
        settle()
        vm.onTrashResult(ok = true)
        settle()

        val cleaned = vm.uiState.value as DedupUiState.Cleaned
        assertEquals(listOf("b"), cleaned.trashedUris)
        // g1 删干净移出；g2 剔除幽灵成员 b 后存活，keepUri 被删 → 按 policy 重算为 c
        val survived = cleaned.remainingGroups.single()
        assertEquals("g2", survived.id)
        assertEquals(listOf("c", "d"), survived.members.map { member -> member.uri })
        assertEquals("c", survived.keepUri)
        assertFalse(survived.userOverride)

        // 继续整理回 Results：g2 的 deleteUris 只剩 d，b 不会被渲染/二次删除
        vm.continueWithRemaining()
        val results = vm.uiState.value as DedupUiState.Results
        assertEquals(DedupLevel.VISUAL, results.selectedTab)
        assertEquals(listOf("d"), results.groups.single().deleteUris)
    }

    @Test
    fun `deleteGroup confirmed removes the group and stays in Results`() = runTest {
        // 每组首张文件更大：完成按 BEST_QUALITY 重算后排序恒等，deleteUris 为各组第二张
        val g1 = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)),
        )
        val g2 = group(
            "g2", DedupLevel.EXACT,
            listOf(member("c", sizeBytes = 4_000L), member("d", sizeBytes = 3_000L)),
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g1, g2))
        settle()

        vm.deleteGroup("g1")
        settle()

        val pending = vm.pendingTrash.value
        assertNotNull(pending)
        // groupId 标记逐组删除，uris 只含本组待删项
        assertEquals("g1", pending?.groupId)
        assertEquals(listOf("b"), pending?.uris)
        assertTrue(vm.uiState.value is DedupUiState.Results)

        vm.onTrashResult(ok = true)
        settle()

        // 逐组删除确认后留在 Results 原地刷新：g1 消失，g2 不动，不进 Cleaned 完成页
        val state = vm.uiState.value as DedupUiState.Results
        assertEquals(listOf("g2"), state.groups.map { group -> group.id })
        assertEquals(DedupLevel.EXACT, state.selectedTab)
        assertNull(vm.pendingTrash.value)
    }

    @Test
    fun `deleteGroup refusal keeps Results with groups intact`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)))
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()
        vm.deleteGroup("g1")
        settle()
        vm.onTrashResult(ok = false)

        assertNull(vm.pendingTrash.value)
        val state = vm.uiState.value as DedupUiState.Results
        assertEquals(listOf(g), state.groups)
    }

    @Test
    fun `deleteGroup on non-preselected group without override is a no-op`() = runTest {
        // 未预选截图组（未改选）deleteUris 为空：按钮仅「确认本组选择」，不发起删除
        val screenshotVisual = group(
            "g1", DedupLevel.VISUAL,
            listOf(
                member("s1", sizeBytes = 3_000L, contentType = DedupContentType.SCREENSHOT),
                member("s2", sizeBytes = 2_000L, contentType = DedupContentType.SCREENSHOT),
            ),
            autoPreselected = false,
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(screenshotVisual))
        settle()
        vm.deleteGroup("g1")
        settle()

        assertNull(vm.pendingTrash.value)
        assertTrue(vm.uiState.value is DedupUiState.Results)
    }

    @Test
    fun `deleteGroup is the per-group delete path for SCENE groups after user picks keep`() = runTest {
        // spec §4：SCENE 不参与批量删除，组详情逐组确认（改选）后由 deleteGroup 删除
        val scene = group(
            "g1", DedupLevel.SCENE,
            listOf(member("e", sizeBytes = 6_000L), member("f", sizeBytes = 5_000L)),
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(scene))
        settle()
        vm.setKeep("g1", "e") // 详情页改选 = 逐组确认
        vm.deleteGroup("g1")
        settle()

        assertEquals(listOf("f"), vm.pendingTrash.value?.uris)

        vm.onTrashResult(ok = true)
        settle()

        // 组存活成员不足 2：整组移出，Results 为空
        val state = vm.uiState.value as DedupUiState.Results
        assertTrue(state.groups.isEmpty())
    }

    @Test
    fun `deleteGroup abandons authorization when group selection changed during IPC window`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a", sizeBytes = 3_000L), member("b", sizeBytes = 2_000L)))
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()
        vm.deleteGroup("g1")
        // buildTrashIntent 的 IPC 窗口内改选保留项：该组选择集与发起时不一致
        vm.setKeep("g1", "b")
        settle()

        // 安全放弃：不发授权、不动任何照片，停留 Results
        assertNull(vm.pendingTrash.value)
        assertTrue(vm.uiState.value is DedupUiState.Results)
    }

    @Test
    fun `smartSelectAll only applies to selected tab`() = runTest {
        val exact = group(
            "g1", DedupLevel.EXACT,
            listOf(member("a", captureDate = 1L), member("b", captureDate = 2L)),
        )
        val visual = group(
            "g2", DedupLevel.VISUAL,
            listOf(member("c", captureDate = 3L), member("d", captureDate = 4L)),
        )
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(exact, visual))
        settle()
        // 两组都手动改选过（BEST_QUALITY 默认 keep 为 captureDate 最新者 b/d）
        vm.setKeep("g1", "a")
        vm.setKeep("g2", "c")

        // 当前 Tab = EXACT：只有 g1 被清 override 重算
        vm.smartSelectAll()
        var state = vm.uiState.value as DedupUiState.Results
        val exactGroup = state.groups.first { grp -> grp.id == "g1" }
        assertFalse(exactGroup.userOverride)
        assertEquals("b", exactGroup.keepUri)
        val visualGroup = state.groups.first { grp -> grp.id == "g2" }
        assertTrue(visualGroup.userOverride)
        assertEquals("c", visualGroup.keepUri)

        // 切到 VISUAL Tab 再全选：g2 被重算，g1 不再被回滚
        vm.selectTab(DedupLevel.VISUAL)
        vm.smartSelectAll()
        state = vm.uiState.value as DedupUiState.Results
        val visualGroup2 = state.groups.first { grp -> grp.id == "g2" }
        assertFalse(visualGroup2.userOverride)
        assertEquals("d", visualGroup2.keepUri)
        assertEquals("b", state.groups.first { grp -> grp.id == "g1" }.keepUri)
    }

    @Test
    fun `session completion recomputes default keep by current policy`() = runTest {
        // 扫描器按 BEST_QUALITY 建组（pixelArea 大者在前），Config 已选「保留最新」
        val g = group(
            "g1", DedupLevel.EXACT,
            listOf(
                member("old-big", modifiedAt = 100L, pixelArea = 2_000),
                member("new-small", modifiedAt = 200L, pixelArea = 1_000),
            ),
        )
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.applyPolicy(KeepPolicy.LATEST) // Config 态改策略
        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()

        val state = vm.uiState.value as DedupUiState.Results
        assertEquals(KeepPolicy.LATEST, state.policy)
        val result = state.groups.single()
        // 完成分支按当前 policy 重算：keepUri 为 modifiedAt 最新者
        assertEquals("new-small", result.keepUri)
        assertEquals(listOf("new-small", "old-big"), result.members.map { m -> m.uri })
    }

    @Test
    fun `buildTrashIntent failure keeps Results and emits no PendingTrash`() = runTest {
        val g = group("g1", DedupLevel.EXACT, listOf(member("a"), member("b")))
        val session = FakeSession()
        val trash = mockk<DedupTrashManager> {
            every { isSupported } returns true
            every { buildTrashIntent(any()) } throws RuntimeException("ipc boom")
        }
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(g))
        settle()
        vm.deleteSelected()
        settle()

        assertNull(vm.pendingTrash.value)
        assertTrue(vm.uiState.value is DedupUiState.Results)
    }

    @Test
    fun `non-preselected screenshot VISUAL group stays out of batch until user picks keep`() = runTest {
        // spec AC-6：截图 VISUAL 组默认不预选、不进批量 CTA；详情改选（userOverride）后参与删除
        val screenshotVisual = group(
            "g1", DedupLevel.VISUAL,
            listOf(
                member("s1", sizeBytes = 3_000L, contentType = DedupContentType.SCREENSHOT),
                member("s2", sizeBytes = 2_000L, contentType = DedupContentType.SCREENSHOT),
            ),
            autoPreselected = false,
        )
        val exact = group(
            "g2", DedupLevel.EXACT,
            listOf(member("a", sizeBytes = 5_000L), member("b", sizeBytes = 4_000L)),
        )
        val session = FakeSession()
        val trash = fakeTrashManager(supported = true)
        val vm = viewModel(session, trash, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(screenshotVisual, exact))
        settle()
        var groups = (vm.uiState.value as DedupUiState.Results).groups

        // 未预选组 deleteUris 为空，批量口径只含 EXACT 组的 b
        assertTrue(groups.first { grp -> grp.id == "g1" }.deleteUris.isEmpty())
        assertEquals(listOf("b"), vm.batchDeleteUris(groups))
        assertEquals(4_000L, vm.batchReclaimBytes(groups, vm.batchDeleteUris(groups)))

        // 组详情改选保留项 = 逐组确认：deleteUris 正常派生并进批量
        vm.setKeep("g1", "s1")
        groups = (vm.uiState.value as DedupUiState.Results).groups
        val confirmed = groups.first { grp -> grp.id == "g1" }
        assertTrue(confirmed.userOverride)
        assertEquals(listOf("s2"), confirmed.deleteUris)
        assertEquals(listOf("b", "s2"), vm.batchDeleteUris(groups).sorted())
        assertEquals(2_000L + 4_000L, vm.batchReclaimBytes(groups, vm.batchDeleteUris(groups)))
    }

    @Test
    fun `smartSelectAll skips non-preselected groups`() = runTest {
        // spec §10.3：智能全选不勾选未预选组（截图/文档 VISUAL、SCENE），连 override 也不动
        val screenshotVisual = group(
            "g1", DedupLevel.VISUAL,
            listOf(
                member("s1", captureDate = 1L, contentType = DedupContentType.SCREENSHOT),
                member("s2", captureDate = 2L, contentType = DedupContentType.SCREENSHOT),
            ),
            autoPreselected = false,
        )
        val exact = group(
            "g2", DedupLevel.EXACT,
            listOf(member("a", captureDate = 3L), member("b", captureDate = 4L)),
        )
        val session = FakeSession()
        val vm = viewModel(session, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        vm.startScan(DedupScanConfig())
        session.progress.value = completedProgress(listOf(screenshotVisual, exact))
        settle()
        vm.setKeep("g1", "s2") // 用户在详情逐组确认过
        vm.smartSelectAll()

        val state = vm.uiState.value as DedupUiState.Results
        val untouched = state.groups.first { grp -> grp.id == "g1" }
        // 未预选组原样保留：用户确认不被智能全选覆盖，也不被重算勾选
        assertTrue(untouched.userOverride)
        assertEquals("s2", untouched.keepUri)
        val resorted = state.groups.first { grp -> grp.id == "g2" }
        assertFalse(resorted.userOverride)
        assertEquals("b", resorted.keepUri)
    }

    @Test
    fun `storage story and board derive from one items emission`() = runTest {
        val now = System.currentTimeMillis()
        val staleScreenshot = item(
            uri = "content://media/2",
            isVideo = false,
            captureDate = now - 400L * 24 * 3600 * 1000,
            sizeBytes = 50L,
            relativePath = "Pictures/Screenshots/old.png",
        )
        val items = listOf(
            item(
                uri = "content://media/1",
                isVideo = false,
                captureDate = now,
                sizeBytes = 100L,
                relativePath = "DCIM/Camera/a.jpg",
            ),
            staleScreenshot,
            item(
                uri = "content://media/3",
                isVideo = true,
                captureDate = now,
                sizeBytes = 900L,
                relativePath = "Movies/vid.mp4",
            ),
        )
        val organizeRepository = mockk<OrganizeRepository> {
            every { observeItems() } returns flowOf(items)
            coEvery { backfillQualitySignals(any(), any()) } returns BackfillBatchResult(attempted = 0, written = 0)
        }
        val vm = viewModel(
            session = FakeSession(),
            organizeRepository = organizeRepository,
            settingsRepository = mockk {
                every { organizePreAnalysisRanAtMsFlow } returns flowOf(123L)
            },
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        var story: StorageStory? = null
        var boardCategoryCount = -1
        var ranAt = -1L
        val jobs = listOf(
            launch { vm.storageStory.collect { value -> story = value } },
            launch { vm.organizeBoard.collect { value -> boardCategoryCount = value.categories.size } },
            launch { vm.preAnalysisRanAt.collect { value -> ranAt = value } },
        )
        settle()
        jobs.forEach { job -> job.cancel() }

        val emitted = story!!
        assertEquals(1050L, emitted.totalBytes)
        assertEquals(150L, emitted.photoBytes)
        assertEquals(900L, emitted.videoBytes)
        assertEquals(50L, emitted.screenshotBytes)
        assertEquals(1, emitted.staleScreenshotCount)
        assertEquals(50L, emitted.staleScreenshotBytes)
        assertTrue(emitted.sourceLedgers.any { ledger ->
            ledger.source == AppSource.CAMERA && ledger.bytes == 100L
        })
        // board 恒 6 卡口径不变（合并派生不改变看板语义）
        assertEquals(6, boardCategoryCount)
        // 上次分析落点直通（DataStore 流 → hub 新鲜度行）
        assertEquals(123L, ranAt)
    }

    @Test
    fun `storage story stays null before first emission`() = runTest {
        val vm = viewModel(
            session = FakeSession(),
            settingsRepository = mockk {
                every { organizePreAnalysisRanAtMsFlow } returns flowOf(0L)
            },
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val job = launch { vm.storageStory.collect { } }
        settle()
        // 空媒体库：items 空列表也是一次发射，story 非空但全零口径（0 张库的诚实叙事）
        assertNotNull(vm.storageStory.value)
        assertEquals(0L, vm.storageStory.value?.totalBytes)
        assertEquals(0, vm.storageStory.value?.staleScreenshotCount)
        assertEquals(0L, vm.preAnalysisRanAt.value)
        job.cancel()
    }

    private fun item(
        uri: String,
        isVideo: Boolean,
        captureDate: Long,
        sizeBytes: Long,
        relativePath: String?,
    ) = OrganizeItem(
        uri = uri,
        isVideo = isVideo,
        captureDate = captureDate,
        sizeBytes = sizeBytes,
        relativePath = relativePath,
        ocrText = null,
        pixelArea = null,
        labels = null,
        hasFace = false,
        aestheticScore = null,
        faceQualityScore = null,
    )
}
