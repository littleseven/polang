package com.mamba.picme.domain.usertask

import androidx.annotation.VisibleForTesting
import com.mamba.picme.data.download.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 模型下载控制收口（LlmModelDownloadManager 依赖 Context 无法在 JVM 单测构造）。
 * resume/retry 的冷流 collect 由实现侧在应用 scope 内启动，适配器只发动词。
 */
interface ModelDownloadControl {
    val downloadStates: StateFlow<Map<String, DownloadState>>
    fun pause(modelId: String)
    fun resume(modelId: String)
    fun cancel(modelId: String)
    fun retry(modelId: String)
}

/**
 * 模型下载适配器（spec §6）：每个 modelId = 1 个 UserTask（并发多任务）。
 * 附带收益：状态迁移落注册表 + 启动对账，下载体系首次获得进程重启可见性。
 */
class ModelDownloadTaskAdapter(
    private val registry: UserTaskRegistry,
    private val control: ModelDownloadControl,
    private val scope: CoroutineScope,
) : UserTaskAdapter {

    override val kind: UserTaskKind = UserTaskKind.MODEL_DOWNLOAD

    // 上一轮 sync 见过的活动任务 id——仅在 sync 内读写（单 collect 协程串行，无线程问题）
    private var lastActiveIds: Set<String> = emptySet()

    /** 非幂等：重复调用会起双订阅；当前唯一调用方 UserTaskRegistry.registerAdapter 保证单次。 */
    override fun start() {
        scope.launch { reconcile() }
        scope.launch {
            control.downloadStates.collect { states -> sync(states) }
        }
    }

    /**
     * 启动对账（spec §5）：Room 活动态行但无活体下载 → FAILED(PROCESS_TERMINATED)，可 RETRY。
     * 两个不变量：
     * 1) 正确性依赖 manager 不丢 id（除 deleteModel 用户删除）——活体 id 若被 manager 丢失会被误判进程死亡；
     * 2) 尾部 sync 重读活体状态自愈——即使对账误判，活体下载的下一帧 collect 也会覆盖回正确状态。
     */
    @VisibleForTesting
    internal suspend fun reconcile() {
        val liveIds = control.downloadStates.value.keys.map { modelId -> taskId(modelId) }.toSet()
        for (rowId in registry.activeIdsOfKind(kind)) {
            if (rowId !in liveIds) {
                registry.upsertStatus(
                    id = rowId, kind = kind,
                    displayName = modelId(rowId),
                    status = UserTaskStatus.FAILED,
                    errorCode = UserTaskErrorCode.PROCESS_TERMINATED,
                )
                registry.updateProgress(rowId, null)
            }
        }
        // 活体下载补登记（进程重启后 manager 重新收集到状态时也会经 sync 覆盖，此处先行对齐）
        sync(control.downloadStates.value)
    }

    @VisibleForTesting
    internal suspend fun sync(states: Map<String, DownloadState>) {
        val currentActiveIds = mutableSetOf<String>()
        for ((id, state) in states) {
            val status = UserTaskMapping.fromDownloadStatus(state.status)
            registry.upsertStatus(
                id = taskId(id),
                kind = kind,
                displayName = id,
                status = status,
            )
            // 终态不写进度快照（upsertStatus 内部已清）：与 TagScan 适配器审查结论一致，避免幽灵进度
            if (UserTaskMapping.isActive(status)) {
                currentActiveIds += taskId(id)
                registry.updateProgress(
                    taskId(id),
                    TaskProgressSnapshot(
                        progress = if (state.totalBytes > 0) {
                            // coerceIn 兜底：manager resumeDownload 有预存双计 bug 可致 downloadedBytes > totalBytes
                            (state.downloadedBytes / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                        // totalBytes==0 时文案同样置 null，避免误导性的 "0 B / 0 B"（对齐 TagScan "0/0" 规避先例）
                        progressText = if (state.totalBytes > 0) {
                            "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
                        } else {
                            null
                        },
                        etaMs = null,
                    )
                )
            } else {
                registry.updateProgress(taskId(id), null)
            }
        }
        // 本轮 map 中消失的活动任务 → CANCELLED 终态（无 errorCode：map 收缩唯一来源是 deleteModel
        // 用户删除，非进程死亡；CANCELLED 即终态不留 RETRY）。仍在本轮 map 中的终态行由上方循环处理。
        for (missingId in lastActiveIds - states.keys.map { id -> taskId(id) }.toSet()) {
            registry.upsertStatus(
                id = missingId,
                kind = kind,
                displayName = modelId(missingId),
                status = UserTaskStatus.CANCELLED,
            )
        }
        lastActiveIds = currentActiveIds
    }

    override suspend fun perform(taskId: String, action: UserTaskAction) {
        when (action) {
            UserTaskAction.PAUSE -> control.pause(modelId(taskId))
            UserTaskAction.RESUME -> control.resume(modelId(taskId))
            UserTaskAction.CANCEL -> control.cancel(modelId(taskId))
            UserTaskAction.RETRY -> control.retry(modelId(taskId))
        }
    }

    private fun taskId(modelId: String): String = taskIdFor(modelId)
    private fun modelId(taskId: String): String = taskId.removePrefix("download:")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= BYTES_PER_GB -> String.format(Locale.US, "%.1f GB", bytes / BYTES_PER_GB.toFloat())
        bytes >= BYTES_PER_MB -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB.toFloat())
        bytes >= BYTES_PER_KB -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB.toFloat())
        else -> "$bytes B"
    }

    companion object {
        private const val BYTES_PER_KB = 1L shl 10
        private const val BYTES_PER_MB = 1L shl 20
        private const val BYTES_PER_GB = 1L shl 30

        /** 任务 ID 方案唯一入口（组合根前置校验 FAILED 落库等外部写入方复用，禁止各处硬编码前缀）。 */
        fun taskIdFor(modelId: String): String = "download:$modelId"
    }
}
