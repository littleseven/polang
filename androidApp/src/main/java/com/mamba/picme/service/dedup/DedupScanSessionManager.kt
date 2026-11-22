package com.mamba.picme.service.dedup

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanController
import com.mamba.picme.domain.dedup.DedupScanEvent
import com.mamba.picme.domain.dedup.DedupScanProgress
import com.mamba.picme.domain.dedup.DedupScanSession
import com.mamba.picme.domain.dedup.DedupScanState
import com.mamba.picme.features.gallery.dedup.DedupMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 去重扫描会话执行核心：取数（[DedupMediaSource]）→ [DedupScanController] 流式扫描 →
 * [DedupScanEvent] 到 [DedupScanProgress] 的状态映射。纯 Kotlin 无 Android 依赖，
 * 由 [DedupScanService] 持有并驱动；进度经 [progress] 供服务镜像到进程级单例流。
 *
 * 状态规则：
 * - startScan 活跃态（RUNNING/PAUSED）重入直接忽略，不重启新会话；
 * - pause/resume 只翻转状态并转发 scanner 控制标志；
 * - cancel 同步置 CANCELLED（解除 scanner 暂停防卡死）后经
 *   [TERMINAL_LINGER_MS] 滞留清空 progress——终态对观察者可见，随后与
 *   「IDLE 无任务」（null）语义对齐。
 */
class DedupScanSessionManager(
    private val scanner: DedupScanController,
    private val mediaSource: DedupMediaSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : DedupScanSession {

    private val _progress = MutableStateFlow<DedupScanProgress?>(null)
    override val progress: StateFlow<DedupScanProgress?> = _progress.asStateFlow()

    private var scanJob: Job? = null

    override fun startScan(config: DedupScanConfig) {
        val current = _progress.value
        if (current?.state == DedupScanState.RUNNING || current?.state == DedupScanState.PAUSED) return
        // scanner 为容器级单例：上轮会话（服务被杀重建场景）可能残留 PAUSED 标志，开新会话前清零
        scanner.pauseRequested = false
        val phases = config.levels.sorted()
        _progress.value = DedupScanProgress(
            state = DedupScanState.RUNNING,
            phase = phases.firstOrNull() ?: DedupLevel.EXACT,
            phaseIndex = 1,
            phaseCount = phases.size.coerceAtLeast(1),
            scanned = 0,
            total = 0,
            foundGroups = emptyList(),
        )
        scanJob = scope.launch {
            runCatching {
                val items = mediaSource.photoScanItems()
                scanner.scan(items, config)
                    .flowOn(ioDispatcher)
                    .collect { event -> onScanEvent(event) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                // 会话异常（取数/Room/未预期路径）：进度清空让观察方退出扫描态，不让 UI 卡死在 RUNNING
                Logger.w(TAG, "dedup scan session failed, clearing progress", error)
                _progress.value = null
            }
        }
    }

    override fun pauseScan() {
        val current = _progress.value ?: return
        if (current.state != DedupScanState.RUNNING) return
        scanner.pauseRequested = true
        _progress.value = current.copy(state = DedupScanState.PAUSED)
    }

    override fun resumeScan() {
        val current = _progress.value ?: return
        if (current.state != DedupScanState.PAUSED) return
        scanner.resume()
        _progress.value = current.copy(state = DedupScanState.RUNNING)
    }

    override fun cancelScan() {
        val current = _progress.value ?: return
        if (current.state == DedupScanState.COMPLETED || current.state == DedupScanState.CANCELLED) return
        scanner.resume() // 防卡死：暂停态取消时扫描循环需退出 awaitIfPaused
        scanJob?.cancel()
        scanJob = null
        if (current.state == DedupScanState.CANCELLED) return
        _progress.value = current.copy(state = DedupScanState.CANCELLED)
        scope.launch {
            delay(TERMINAL_LINGER_MS)
            if (_progress.value?.state == DedupScanState.CANCELLED) _progress.value = null
        }
    }

    private fun onScanEvent(event: DedupScanEvent) {
        val current = _progress.value ?: return
        when (event) {
            is DedupScanEvent.Progress -> _progress.value = current.copy(
                state = DedupScanState.RUNNING,
                phase = event.phase,
                scanned = event.scanned,
                total = event.total,
            )
            is DedupScanEvent.PhaseChanged -> _progress.value = current.copy(
                phase = event.phase,
                phaseIndex = event.phaseIndex,
                phaseCount = event.phaseCount,
            )
            is DedupScanEvent.GroupFound -> _progress.value = current.copy(
                foundGroups = listOf(event.group) + current.foundGroups, // 最新发现排前
            )
            is DedupScanEvent.Done -> _progress.value = current.copy(
                state = DedupScanState.COMPLETED,
                resultGroups = event.groups,
            )
            DedupScanEvent.Cancelled -> {
                _progress.value = current.copy(state = DedupScanState.CANCELLED)
                scanJob = null
                scope.launch {
                    delay(TERMINAL_LINGER_MS)
                    if (_progress.value?.state == DedupScanState.CANCELLED) _progress.value = null
                }
            }
        }
    }

    private companion object {
        const val TAG = "PoLang:DedupScan"

        /** CANCELLED 终态在进度流中的滞留时长：先让观察者（VM/通知）看到终态，再清空回 IDLE。 */
        const val TERMINAL_LINGER_MS = 300L
    }
}
