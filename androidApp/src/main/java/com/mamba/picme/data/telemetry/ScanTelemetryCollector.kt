package com.mamba.picme.data.telemetry

import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 扫描会话遥测采集器：订阅 [TagGenerationService.sessionProgress]，按 sessionId 检测会话边界，
 * 在启动与终态（COMPLETED/CANCELLED）时上报 gallery_scan 事件（转化漏斗「扫描关」）。
 *
 * 「引擎零侵入」：与 TagScanTaskAdapter 同为进度流的旁路订阅者，扫描流水线不改一行代码。
 * 状态机只对会话边界敏感（RUNNING 首帧 = started，终态帧 = terminal），中间进度帧忽略——
 * 扫描期间进度变化频繁，逐帧上报无意义且浪费流量。
 */
class ScanTelemetryCollector(
    private val reporter: TelemetryReporter,
    private val scope: CoroutineScope,
    private val progressFlow: Flow<TagScanSessionProgress?> = TagGenerationService.sessionProgress,
) {

    /** 非幂等：与 TagScanTaskAdapter 同约定，由 AppContainer 单点调用一次 */
    fun start() {
        scope.launch {
            var activeSessionId: String? = null
            var sessionStartMs = 0L
            progressFlow.collect { progress ->
                when {
                    // 会话启动：新 sessionId 的 RUNNING 帧
                    progress?.state == ScanSessionState.RUNNING && progress.sessionId != activeSessionId -> {
                        activeSessionId = progress.sessionId
                        sessionStartMs = System.currentTimeMillis()
                        reporter.reportScanStarted(progress.total)
                    }
                    // 会话终结：COMPLETED / CANCELLED
                    progress != null && progress.sessionId == activeSessionId &&
                        (progress.state == ScanSessionState.COMPLETED || progress.state == ScanSessionState.CANCELLED) -> {
                        reporter.reportScanTerminal(
                            phase = if (progress.state == ScanSessionState.COMPLETED) "completed" else "cancelled",
                            processed = progress.processed,
                            total = progress.total,
                            failed = progress.failed,
                            durationMs = System.currentTimeMillis() - sessionStartMs,
                        )
                        activeSessionId = null
                    }
                }
            }
        }
    }
}
