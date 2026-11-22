package com.mamba.picme.domain.usertask

import androidx.annotation.VisibleForTesting
import com.mamba.picme.domain.dedup.DedupScanProgress
import com.mamba.picme.service.dedup.DedupScanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 去重扫描控制动词收口（DedupScanService 依赖 Context 无法在 JVM 单测构造，故收为接口）。 */
fun interface DedupScanControl {
    /** action ∈ start / pause / resume / cancel（对齐 DedupScanService intent 协议）。 */
    suspend operator fun invoke(action: String)
}

/**
 * 去重扫描适配器（spec §6，仿 [TagScanTaskAdapter]）：整个扫描会话 = 1 个 UserTask
 * （id 固定 dedupscan:main）。订阅 Service 静态进度流；动词经 [DedupScanControl] 转发。
 * 引擎零侵入。
 *
 * 进度口径：`progress` = 任务级计数 scanned/total（去重无 ETA，etaMs 恒 null）；
 * 进度文案「128/500」数字中性形态（total==0 时同样置 null，避免误导性的 "0/0"）。
 * DedupScanProgress 无失败计数字段，COMPLETED 不挂 PARTIAL_FAILURES。
 */
class DedupScanTaskAdapter(
    private val registry: UserTaskRegistry,
    private val control: DedupScanControl,
    private val scope: CoroutineScope,
    private val progressFlow: Flow<DedupScanProgress?> = DedupScanService.sessionProgress,
) : UserTaskAdapter {

    override val kind: UserTaskKind = UserTaskKind.DEDUP_SCAN

    /** 非幂等：重复调用会起双订阅；当前唯一调用方 UserTaskRegistry.registerAdapter 保证单次。 */
    override fun start() {
        scope.launch {
            progressFlow.collect { progress ->
                sync(progress)
            }
        }
    }

    @VisibleForTesting
    internal suspend fun sync(progress: DedupScanProgress?) {
        val status = progress?.let { value -> UserTaskMapping.fromDedupScanState(value.state) }
        if (status == null) {
            // null 帧对账（任意帧，不限首帧）：null = 会话已不存在——进程重启后静态流必为 null 初值，
            // Manager 失败路径 stop-on-null 与终态滞留清流也会发出。注册表残留活动态行 = 会话死于
            // 半途而行未收口 → 置 FAILED(PROCESS_TERMINATED)；终态帧必先于死亡 null 到达，
            // isActive 守卫天然放过终态行——actionsFor(FAILED)=RETRY 使「已中断——点重试重新开始」
            // 文案有按钮可点（CANCELLED 动作集为空）。
            if (progress == null) {
                val current = registry.currentStatus(TASK_ID)
                if (current != null && UserTaskMapping.isActive(current)) {
                    registry.upsertStatus(
                        id = TASK_ID, kind = kind, displayName = null,
                        status = UserTaskStatus.FAILED,
                        errorCode = UserTaskErrorCode.PROCESS_TERMINATED,
                    )
                }
            }
            registry.updateProgress(TASK_ID, null)
            return
        }
        registry.upsertStatus(id = TASK_ID, kind = kind, displayName = null, status = status)
        // 终态不写进度快照（upsertStatus 内部已清）：避免重启前后进度文案不一致
        if (UserTaskMapping.isActive(status)) {
            registry.updateProgress(
                TASK_ID,
                TaskProgressSnapshot(
                    progress = if (progress.total > 0) {
                        progress.scanned / progress.total.toFloat()
                    } else {
                        null
                    },
                    progressText = if (progress.total > 0) "${progress.scanned}/${progress.total}" else null,
                    etaMs = null,
                ),
            )
        }
    }

    override suspend fun perform(taskId: String, action: UserTaskAction) {
        when (action) {
            UserTaskAction.PAUSE -> control("pause")
            UserTaskAction.RESUME -> control("resume")
            UserTaskAction.CANCEL -> control("cancel")
            // RETRY → start：进程死亡对账（FAILED）唯一出口，「点重试重新开始」链（spec §9-2）
            UserTaskAction.RETRY -> control("start")
        }
    }

    companion object {
        const val TASK_ID = "dedupscan:main"
    }
}
