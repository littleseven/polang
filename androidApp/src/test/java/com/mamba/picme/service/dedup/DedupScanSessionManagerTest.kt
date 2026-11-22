package com.mamba.picme.service.dedup

import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupMember
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanController
import com.mamba.picme.domain.dedup.DedupScanEvent
import com.mamba.picme.domain.dedup.DedupScanState
import com.mamba.picme.domain.dedup.DedupScanner
import com.mamba.picme.domain.dedup.VersionRole
import com.mamba.picme.features.gallery.dedup.DedupMediaSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DedupScanSessionManagerTest {

    private fun member(uri: String) = DedupMember(
        uri = uri,
        sizeBytes = 1_000L,
        mime = "image/jpeg",
        captureDate = 0L,
        modifiedAt = 0L,
        pixelArea = 100,
        aestheticScore = null,
        role = VersionRole.ORIGINAL,
        md5 = null,
        phash = null,
    )

    private fun group(id: String, level: DedupLevel = DedupLevel.EXACT): DedupGroup = DedupGroup(
        id = id,
        level = level,
        members = listOf(member("$id-a"), member("$id-b")),
        keepUri = "$id-a",
    )

    /** 假扫描器：按脚本流出事件，可选抛错（模拟扫描失败）/挂起（模拟扫描进行中）；记录调用计数。 */
    private class FakeScanner(
        private val events: List<DedupScanEvent>,
        private val hang: Boolean = false,
        private val failWith: Exception? = null,
    ) : DedupScanController {
        override var pauseRequested: Boolean = false
        var resumeCount: Int = 0
            private set
        var scanCount: Int = 0
            private set
        var lastConfig: DedupScanConfig? = null
            private set

        override fun resume() {
            pauseRequested = false
            resumeCount++
        }

        override fun scan(
            items: List<DedupScanner.ScanItem>,
            config: DedupScanConfig,
        ): Flow<DedupScanEvent> {
            scanCount++
            lastConfig = config
            return flow {
                for (event in events) emit(event)
                failWith?.let { failure -> throw failure }
                if (hang) awaitCancellation()
            }
        }
    }

    private class FakeMediaSource : DedupMediaSource {
        var callCount: Int = 0
            private set

        override suspend fun photoScanItems(): List<DedupScanner.ScanItem> {
            callCount++
            return emptyList()
        }
    }

    /** 排空协程：本环境 advanceUntilIdle 不驱动 backgroundScope，先推进 1ms（同 DedupViewModelTest 范式）。 */
    private fun TestScope.settle() {
        advanceTimeBy(1)
        advanceUntilIdle()
    }

    private fun TestScope.manager(
        scanner: FakeScanner,
        mediaSource: FakeMediaSource = FakeMediaSource(),
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = DedupScanSessionManager(
        scanner = scanner,
        mediaSource = mediaSource,
        scope = backgroundScope,
        ioDispatcher = ioDispatcher,
    )

    @Test
    fun `start emits running then completed with result groups`() = runTest {
        val g1 = group("g1")
        val g2 = group("g2", DedupLevel.VISUAL)
        val scanner = FakeScanner(
            events = listOf(
                DedupScanEvent.PhaseChanged(DedupLevel.EXACT, 1, 2),
                DedupScanEvent.Progress(DedupLevel.EXACT, 3, 10),
                DedupScanEvent.GroupFound(g1),
                DedupScanEvent.Done(listOf(g1, g2)),
            ),
        )
        val manager = manager(scanner)

        manager.startScan(DedupScanConfig())
        settle()

        val final = manager.progress.value
        assertEquals(DedupScanState.COMPLETED, final?.state)
        assertEquals(listOf(g1, g2), final?.resultGroups)
        // 阶段三元组与进度计数在事件流中传递
        assertEquals(DedupLevel.EXACT, final?.phase)
        assertEquals(3, final?.scanned)
        assertEquals(10, final?.total)
    }

    @Test
    fun `reentrant start while running is ignored`() = runTest {
        val scanner = FakeScanner(
            events = listOf(DedupScanEvent.Progress(DedupLevel.EXACT, 1, 10)),
            hang = true,
        )
        val mediaSource = FakeMediaSource()
        val manager = manager(scanner, mediaSource)

        manager.startScan(DedupScanConfig(visualThreshold = 5))
        settle()
        manager.startScan(DedupScanConfig(visualThreshold = 9))
        settle()

        assertEquals(DedupScanState.RUNNING, manager.progress.value?.state)
        assertEquals(1, scanner.scanCount)
        assertEquals(1, mediaSource.callCount)
        assertEquals(5, scanner.lastConfig?.visualThreshold)
    }

    @Test
    fun `reentrant start while paused is ignored`() = runTest {
        val scanner = FakeScanner(
            events = listOf(DedupScanEvent.Progress(DedupLevel.EXACT, 1, 10)),
            hang = true,
        )
        val manager = manager(scanner)

        manager.startScan(DedupScanConfig())
        settle()
        manager.pauseScan()
        manager.startScan(DedupScanConfig(visualThreshold = 9))
        settle()

        assertEquals(DedupScanState.PAUSED, manager.progress.value?.state)
        assertEquals(1, scanner.scanCount)
    }

    @Test
    fun `cancel maps to cancelled state and clears progress`() = runTest {
        val scanner = FakeScanner(
            events = listOf(DedupScanEvent.Progress(DedupLevel.EXACT, 5, 100)),
            hang = true,
        )
        val manager = manager(scanner)

        manager.startScan(DedupScanConfig())
        settle()
        assertTrue(manager.progress.value?.state == DedupScanState.RUNNING)

        manager.cancelScan()

        // 同步进入 CANCELLED 终态（观察者可即时判定），且解除了扫描器暂停标志
        assertEquals(DedupScanState.CANCELLED, manager.progress.value?.state)
        assertEquals(1, scanner.resumeCount)
        assertFalse(scanner.pauseRequested)

        // 终态滞留后清空：与「IDLE 无任务」语义对齐。backgroundScope 的 delay 不被
        // advanceUntilIdle 驱动（本环境已知怪癖），显式跨过滞留窗口再排空
        advanceTimeBy(1_000)
        runCurrent()
        advanceUntilIdle()
        assertNull(manager.progress.value)
    }

    @Test
    fun `pause then resume flips state and forwards to scanner`() = runTest {
        val scanner = FakeScanner(
            events = listOf(DedupScanEvent.Progress(DedupLevel.EXACT, 5, 100)),
            hang = true,
        )
        val manager = manager(scanner)

        manager.startScan(DedupScanConfig())
        settle()

        manager.pauseScan()
        assertEquals(DedupScanState.PAUSED, manager.progress.value?.state)
        assertTrue(scanner.pauseRequested)

        manager.resumeScan()
        assertEquals(DedupScanState.RUNNING, manager.progress.value?.state)
        assertFalse(scanner.pauseRequested)
        assertEquals(1, scanner.resumeCount)
    }

    @Test
    fun `group found prepends and phase changed updates triple`() = runTest {
        val g1 = group("g1")
        val g2 = group("g2")
        val scanner = FakeScanner(
            events = listOf(
                DedupScanEvent.GroupFound(g1),
                DedupScanEvent.GroupFound(g2),
                DedupScanEvent.PhaseChanged(DedupLevel.VISUAL, 2, 2),
            ),
            hang = true,
        )
        val manager = manager(scanner)

        manager.startScan(DedupScanConfig())
        settle()

        val progress = manager.progress.value
        assertEquals(DedupScanState.RUNNING, progress?.state)
        // 最新发现排前
        assertEquals(listOf(g2, g1), progress?.foundGroups)
        assertEquals(DedupLevel.VISUAL, progress?.phase)
        assertEquals(2, progress?.phaseIndex)
        assertEquals(2, progress?.phaseCount)
    }

    @Test
    fun `start after completion starts a fresh session`() = runTest {
        val scanner = FakeScanner(
            events = listOf(DedupScanEvent.Done(emptyList())),
        )
        val mediaSource = FakeMediaSource()
        val manager = manager(scanner, mediaSource)

        manager.startScan(DedupScanConfig())
        settle()
        assertEquals(DedupScanState.COMPLETED, manager.progress.value?.state)

        manager.startScan(DedupScanConfig())
        settle()

        // COMPLETED 不是重入守卫态：允许发起全新会话
        assertEquals(2, scanner.scanCount)
        assertEquals(2, mediaSource.callCount)
    }

    @Test
    fun `scan failure clears progress instead of sticking in running`() = runTest {
        // 失败兜底契约：取数/扫描异常 → progress 置 null（VM 侧 null → Scanning 退 Config；
        // 服务侧 null 触发释放唤醒锁 + 延时自灭——若停在 RUNNING，FGS 与唤醒锁将永久滞留）
        val scanner = FakeScanner(
            events = listOf(DedupScanEvent.Progress(DedupLevel.EXACT, 5, 100)),
            failWith = RuntimeException("mediastore boom"),
        )
        val manager = manager(scanner)

        manager.startScan(DedupScanConfig())
        settle()

        assertNull(manager.progress.value)

        // 失败后会话归 IDLE：可立即重新发起（非 RUNNING/PAUSED 守卫态）
        manager.startScan(DedupScanConfig())
        settle()
        assertEquals(2, scanner.scanCount)
    }
}
