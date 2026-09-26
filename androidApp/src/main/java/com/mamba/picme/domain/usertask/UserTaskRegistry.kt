package com.mamba.picme.domain.usertask

import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * 用户任务混合注册表（spec §5）：Room 存身份（仅状态迁移落库）+ 内存走进度 + 合并流对 UI。
 * UI 只依赖本类的 tasks/activeCount，不感知背后体系（Agent First：显式优于隐式）。
 */
class UserTaskRegistry(
    private val dao: UserTaskDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _progress = MutableStateFlow<Map<String, TaskProgressSnapshot>>(emptyMap())
    private val adapters = mutableMapOf<UserTaskKind, UserTaskAdapter>()

    val tasks: StateFlow<List<UserTask>> =
        combine(dao.observeAll(), _progress) { rows, snapshots ->
            rows.map { row -> row.toUserTask(snapshots[row.id]) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeCount: StateFlow<Int> =
        tasks.map { list -> list.count { task -> UserTaskMapping.isActive(task.status) } }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    fun registerAdapter(adapter: UserTaskAdapter) {
        adapters[adapter.kind] = adapter
        adapter.start()
    }

    /** 统一动词分发：按任务 kind 路由到对应适配器；未知任务/未知 kind 静默返回（spec §9-1）。 */
    suspend fun perform(taskId: String, action: UserTaskAction) {
        val row = dao.getById(taskId) ?: return
        val kind = runCatching { UserTaskKind.valueOf(row.kind) }.getOrNull() ?: return
        adapters[kind]?.perform(taskId, action)
    }

    /** 状态迁移（落 Room；终态顺带修剪历史）。errorCode/errorDetail 仅在异常语义时传。 */
    suspend fun upsertStatus(
        id: String,
        kind: UserTaskKind,
        displayName: String?,
        status: UserTaskStatus,
        errorCode: UserTaskErrorCode? = null,
        errorDetail: String? = null,
    ) {
        val now = clock()
        dao.upsert(
            UserTaskEntity(
                id = id,
                kind = kind.name,
                displayName = displayName,
                status = status.name,
                errorCode = errorCode?.name,
                errorDetail = errorDetail,
                destination = UserTaskMapping.destinationFor(kind).name,
                updatedAt = now,
                completedAt = if (UserTaskMapping.isActive(status)) null else now,
            )
        )
        if (!UserTaskMapping.isActive(status)) dao.trimHistory(HISTORY_KEEP)
    }

    /** 高频进度（仅内存）；传 null 清除快照。 */
    fun updateProgress(taskId: String, snapshot: TaskProgressSnapshot?) {
        _progress.update { current ->
            if (snapshot == null) current - taskId else current + (taskId to snapshot)
        }
    }

    /** 适配器对账用：注册表中某 kind 的活动态任务 id。 */
    suspend fun activeIdsOfKind(kind: UserTaskKind): List<String> = dao.activeIdsOfKind(kind.name)

    /** 适配器对账用：注册表中某任务的当前状态（无行返回 null）。 */
    suspend fun currentStatus(taskId: String): UserTaskStatus? =
        dao.getById(taskId)?.let { row ->
            runCatching { UserTaskStatus.valueOf(row.status) }.getOrNull()
        }

    private fun UserTaskEntity.toUserTask(snapshot: TaskProgressSnapshot?): UserTask {
        val kind = UserTaskKind.valueOf(kind)
        val status = UserTaskStatus.valueOf(status)
        return UserTask(
            id = id,
            kind = kind,
            displayName = displayName,
            status = status,
            progress = snapshot?.progress,
            progressText = snapshot?.progressText,
            etaMs = snapshot?.etaMs,
            errorCode = errorCode?.let { code -> runCatching { UserTaskErrorCode.valueOf(code) }.getOrNull() },
            errorDetail = errorDetail,
            supportedActions = UserTaskMapping.actionsFor(status),
            destination = UserTaskMapping.destinationFor(kind),
            updatedAt = updatedAt,
        )
    }

    companion object {
        const val HISTORY_KEEP = 50
    }
}
