package com.mamba.picme.service.dedup

import android.content.Context
import androidx.core.content.ContextCompat
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanProgress
import com.mamba.picme.domain.dedup.DedupScanSession
import kotlinx.coroutines.flow.StateFlow

/**
 * [DedupScanSession] 的 intent 控制面实现（仿 StartTagScanUseCase 范式）：
 * 控制命令经 Intent 下发给 [DedupScanService]，观察直读伴生 [DedupScanService.sessionProgress]，
 * 与服务生命周期解耦——VM 只持本适配器即可驱动并观察服务端会话。
 */
class ServiceBackedDedupScanSession(
    private val context: Context,
) : DedupScanSession {

    override val progress: StateFlow<DedupScanProgress?> = DedupScanService.sessionProgress

    /** 启动扫描：必须 startForegroundService（dataSync FGS，5s 内 startForeground）。 */
    override fun startScan(config: DedupScanConfig) {
        ContextCompat.startForegroundService(context, DedupScanService.intentStart(context, config))
    }

    /** 控制命令走普通 startService：FGS 存活时 app 处前台态可自由 startService；服务已死则这些命令本就是 no-op。 */
    override fun pauseScan() {
        context.startService(DedupScanService.intentPause(context))
    }

    override fun resumeScan() {
        context.startService(DedupScanService.intentResume(context))
    }

    override fun cancelScan() {
        context.startService(DedupScanService.intentCancel(context))
    }
}
