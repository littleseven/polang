package com.mamba.picme.domain.usertask

import androidx.annotation.VisibleForTesting
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** TAG 扫描控制动词收口（StartTagScanUseCase 依赖 Context 无法在 JVM 单测构造，故收为接口）。 */
fun interface TagScanControl {
    /** action ∈ start / pause / resume / cancel（对齐 StartTagScanUseCase 协议）。 */
    suspend operator fun invoke(action: String)
}

/**
 * TAG 扫描适配器（spec §6）：整个扫描会话 = 1 个 UserTask（id 固定 tagscan:main）。
 * 订阅 Service 静态进度流；动词经 [TagScanControl] 转发。引擎零侵入。
 *
 * 口径立法（2026-10-05 修订）：`progress` 喂**任务域加权进度**
 * （[TagScanSessionProgress.weightedFraction]，公式见 shared ScanProgressCalculator）——
 * 自动 sweep 跨链式批次连续、中途新增照片不回退；不再混拼库域完成度
 * （库域 = LibraryCompletion，只服务圆环/统计，两个作用域各自独立）。
 * 任务级 processed/total 仅以 progressText「128/500」数字中性形态辅助呈现。
 */
class TagScanTaskAdapter(
    private val registry: UserTaskRegistry,
    private val control: TagScanControl,
    private val scope: CoroutineScope,
    private val progressFlow: Flow<TagScanSessionProgress?> = TagGenerationService.sessionProgress,
) : UserTaskAdapter {

    override val kind: UserTaskKind = UserTaskKind.TAG_SCAN

    /** 非幂等：重复调用会起双订阅；当前唯一调用方 UserTaskRegistry.registerAdapter 保证单次。 */
    override fun start() {
        scope.launch {
            var isFirstEmission = true
            progressFlow.collect { progress ->
                sync(progress, isFirstEmission)
                isFirstEmission = false
            }
        }
    }

    @VisibleForTesting
    internal suspend fun sync(
        progress: TagScanSessionProgress?,
        isFirstEmission: Boolean = false,
    ) {
        val status = progress?.let { value -> UserTaskMapping.fromTagScanState(value.state) }
        if (status == null) {
            // 进程被杀对账仅限「start() 后首帧为 null」（进程重启后 Service 静态流必为 null 初值）：
            // 注册表残留活动态行 = 进程死于扫描中 → 置 FAILED(PROCESS_TERMINATED)——
            // actionsFor(FAILED)=RETRY 使「已中断——点重试重新开始」文案有按钮可点（CANCELLED 动作集为空）。
            // 运行中出现的 IDLE 对象（orchestrator resume 竞态窗口会发）或后续 null 只清快照、不动状态。
            if (progress == null && isFirstEmission) {
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
                    // 任务域加权进度优先；老版本帧（无 weightedFraction）回退任务级计数
                    progress = progress.weightedFraction
                        ?: if (progress.total > 0) progress.processed / progress.total.toFloat() else null,
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
            // RETRY → start：进程死亡对账（FAILED）唯一出口，「点重试重新开始」链（spec §9-2）
            UserTaskAction.RETRY -> control("start")
        }
    }

    companion object {
        const val TASK_ID = "tagscan:main"
    }
}
