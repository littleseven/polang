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

    override fun start() {
        scope.launch {
            TagGenerationService.sessionProgress.collect { progress -> sync(progress) }
        }
    }

    @VisibleForTesting
    internal suspend fun sync(progress: TagScanSessionProgress?) {
        val status = progress?.let { value -> UserTaskMapping.fromTagScanState(value.state) }
        if (status == null) {
            // IDLE / 无会话：注册表残留活动态行 = 进程被杀于扫描中 → 对账置 CANCELLED
            val current = registry.currentStatus(TASK_ID)
            if (current != null && UserTaskMapping.isActive(current)) {
                registry.upsertStatus(
                    id = TASK_ID, kind = kind, displayName = null,
                    status = UserTaskStatus.CANCELLED,
                    errorCode = UserTaskErrorCode.PROCESS_TERMINATED,
                )
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
        registry.updateProgress(
            TASK_ID,
            TaskProgressSnapshot(
                progress = if (progress.total > 0) progress.processed / progress.total.toFloat() else null,
                progressText = "${progress.processed}/${progress.total}",
                etaMs = progress.estimatedRemainingMs,
            )
        )
    }

    override suspend fun perform(taskId: String, action: UserTaskAction) {
        when (action) {
            UserTaskAction.PAUSE -> control("pause")
            UserTaskAction.RESUME -> control("resume")
            UserTaskAction.CANCEL -> control("cancel")
            // 会话级无 FAILED 态，RETRY 只出现在进程被杀对账后 → 重起增量扫描
            UserTaskAction.RETRY -> control("start")
        }
    }

    companion object {
        const val TASK_ID = "tagscan:main"
    }
}
