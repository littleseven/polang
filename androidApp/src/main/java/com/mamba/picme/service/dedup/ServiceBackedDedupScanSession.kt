package com.mamba.picme.service.dedup

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mamba.picme.core.common.Logger
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

    /**
     * 控制命令尽力而为：FGS 存活时 app 计入前台态、startService 合法；但服务已死或 app
     * 退后台时 startService 会抛 IllegalStateException（API 26+）——本就无会话可控制，
     * 吞异常记警告即可，绝不让控制面命令 crash 调用方（VM/任务卡/通知动作等远端触发点）。
     */
    override fun pauseScan() {
        control(DedupScanService.intentPause(context), "pause")
    }

    override fun resumeScan() {
        control(DedupScanService.intentResume(context), "resume")
    }

    override fun cancelScan() {
        control(DedupScanService.intentCancel(context), "cancel")
    }

    private fun control(intent: Intent, action: String) {
        runCatching { context.startService(intent) }
            .onFailure { error -> Logger.w(TAG, "dedup scan $action command dropped (service dead or app backgrounded)", error) }
    }

    private companion object {
        const val TAG = "PoLang:DedupScan"
    }
}
