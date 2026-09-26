package com.mamba.picme.domain.usertask

import androidx.annotation.VisibleForTesting
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** TAG 扫描控制动词收口（StartTagScanUseCase 依赖 Context 无法在 JVM 单测构造，故收为接口）。 */
fun interface TagScanControl {
    /** action ∈ start / pause / resume / cancel（对齐 StartTagScanUseCase 协议）。 */
    suspend operator fun invoke(action: String)
}

/**
 * TAG 扫描适配器（spec §6）：整个扫描会话 = 1 个 UserTask（id 固定 tagscan:main）。
 * 订阅 Service 静态进度流；动词经 [TagScanControl] 转发。引擎零侵入。
 */
class TagScanTaskAdapter(
    private val registry: UserTaskRegistry,
    private val control: TagScanControl,
    private val scope: CoroutineScope,
) : UserTaskAdapter {

    override val kind: UserTaskKind = UserTaskKind.TAG_SCAN

    /** 非幂等：重复调用会起双订阅；当前唯一调用方 UserTaskRegistry.registerAdapter 保证单次。 */
    override fun start() {
        scope.launch {
            var isFirstEmission = true
            TagGenerationService.sessionProgress.collect { progress ->
                sync(progress, isFirstEmission)
                isFirstEmission = false
            }
        }
    }

    @VisibleForTesting
    internal suspend fun sync(progress: TagScanSessionProgress?, isFirstEmission: Boolean = false) {
        val status = progress?.let { value -> UserTaskMapping.fromTagScanState(value.state) }
        if (status == null) {
            // 进程被杀对账仅限「start() 后首帧为 null」（进程重启后 Service 静态流必为 null 初值）：
            // 注册表残留活动态行 = 进程死于扫描中 → 置 CANCELLED(PROCESS_TERMINATED)。
            // 运行中出现的 IDLE 对象（orchestrator resume 竞态窗口会发）或后续 null 只清快照、不动状态。
            if (progress == null && isFirstEmission) {
                val current = registry.currentStatus(TASK_ID)
                if (current != null && UserTaskMapping.isActive(current)) {
                    registry.upsertStatus(
                        id = TASK_ID, kind = kind, displayName = null,
                        status = UserTaskStatus.CANCELLED,
                        errorCode = UserTaskErrorCode.PROCESS_TERMINATED,
                    )
                }
            }
            registry.updateProgress(TASK_ID, null)
            return
        }
        val partialFailures =
            status == UserTaskStatus.COMPLETED && progress.failed > 0
        registry.upsertStatus(
            id = TASK_ID,
            kind = kind,
            displayName = null,
            status = status,
            errorCode = if (partialFailures) UserTaskErrorCode.PARTIAL_FAILURES else null,
            errorDetail = if (partialFailures) progress.failed.toString() else null,
        )
        // 终态不写进度快照（upsertStatus 内部已清）：避免 PARTIAL_FAILURES 卡带幽灵 ETA、重启前后进度文案不一致
        if (UserTaskMapping.isActive(status)) {
            registry.updateProgress(
                TASK_ID,
                TaskProgressSnapshot(
                    progress = if (progress.total > 0) progress.processed / progress.total.toFloat() else null,
                    // total==0 时文案同样置 null，避免误导性的 "0/0"
                    progressText = if (progress.total > 0) "${progress.processed}/${progress.total}" else null,
                    etaMs = progress.estimatedRemainingMs,
                )
            )
        }
    }

    override suspend fun perform(taskId: String, action: UserTaskAction) {
        when (action) {
            UserTaskAction.PAUSE -> control("pause")
            UserTaskAction.RESUME -> control("resume")
            UserTaskAction.CANCEL -> control("cancel")
            // 防御性映射：当前状态机不可达（CANCELLED 无动作集、会话级无 FAILED 态），保留以对齐协议动词全集
            UserTaskAction.RETRY -> control("start")
        }
    }

    companion object {
        const val TASK_ID = "tagscan:main"
    }
}
