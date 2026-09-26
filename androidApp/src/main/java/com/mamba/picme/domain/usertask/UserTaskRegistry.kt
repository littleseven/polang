package com.mamba.picme.domain.usertask

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * 写库节流缓存（最近成功落库行）。ConcurrentHashMap：upsertStatus 可被多适配器协程并发调用，
     * 非原子 check-then-act 最坏只多写一次可接受。trimHistory 删行不清缓存是安全的——
     * 任何任务复跑必经活动态（与缓存的终态行不等）先行落库覆盖缓存。
     */
    private val lastWritten = ConcurrentHashMap<String, WrittenRow>()

    val tasks: StateFlow<List<UserTask>> =
        combine(dao.observeAll(), _progress) { rows, snapshots ->
            // 非法行整行丢弃（mapNotNull）——transform 抛出会杀死 Eagerly 共享协程且 stateIn 不重启
            rows.mapNotNull { row -> row.toUserTask(snapshots[row.id]) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeCount: StateFlow<Int> =
        tasks.map { list -> list.count { task -> UserTaskMapping.isActive(task.status) } }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    fun registerAdapter(adapter: UserTaskAdapter) {
        adapters[adapter.kind] = adapter
        adapter.start()
    }

    /**
     * 统一动词分发：按任务 kind 路由到对应适配器；未知任务/未知 kind/无适配器静默返回（spec §9-1）。
     * 全链路（Room 读 + 适配器分发，如 FGS 启动）异常兜底为日志降级，不穿透调用方——
     * 组合根 CEH 语义延伸：viewModelScope 直 launch 调用安全（CancellationException 照常传播）。
     */
    @Suppress("TooGenericExceptionCaught") // spec §9-1 动作失败静默语义：动词链路全包（CancellationException 已先行 rethrow）
    suspend fun perform(taskId: String, action: UserTaskAction) {
        try {
            val row = dao.getById(taskId) ?: return
            val kind = runCatching { UserTaskKind.valueOf(row.kind) }.getOrNull() ?: return
            adapters[kind]?.perform(taskId, action)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // spec §9-1：动作失败不穿透调用方——记日志降级，状态以体系流为准
            Logger.w(TAG, "perform failed, id=$taskId action=$action", exception)
        }
    }

    /** 状态迁移（落 Room；终态顺带修剪历史并清除残留进度快照）。errorCode/errorDetail 仅在异常语义时传。 */
    @Suppress("TooGenericExceptionCaught") // spec §9-5 有意兜住一切写库异常降级（CancellationException 已先行 rethrow）
    suspend fun upsertStatus(
        id: String,
        kind: UserTaskKind,
        displayName: String?,
        status: UserTaskStatus,
        errorCode: UserTaskErrorCode? = null,
        errorDetail: String? = null,
    ) {
        // 写库节流（spec §5 决策 #4「Room 只在状态迁移时写入」）：与最近成功落库行一致则跳过
        // dao.upsert——TAG 扫描每张一帧、下载 500ms 一帧的同态刷新不再击穿 Room。
        // updatedAt/completedAt 是每次刷新的派生字段，不入相等键（入键则节流失效）。
        val written = WrittenRow(kind, displayName, status, errorCode, errorDetail)
        if (lastWritten[id] == written) {
            // 终态进度快照在首次落库时已清，此处幂等再清一次兜底
            if (!UserTaskMapping.isActive(status)) _progress.update { current -> current - id }
            return
        }
        val now = clock()
        try {
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
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // spec §9-5：写库失败记 warning 降级不致命——不传播，避免打断适配器的体系订阅协程
            Logger.w(TAG, "upsertStatus failed, id=$id status=$status", exception)
            return
        }
        // 写成功才入缓存；写失败降级保持不缓存，下次同态调用仍会重试落库
        lastWritten[id] = written
        if (!UserTaskMapping.isActive(status)) {
            _progress.update { current -> current - id }
        }
    }

    /** 高频进度（仅内存）；传 null 清除快照。 */
    fun updateProgress(taskId: String, snapshot: TaskProgressSnapshot?) {
        _progress.update { current ->
            if (snapshot == null) current - taskId else current + (taskId to snapshot)
        }
    }

    /** 适配器对账用：注册表中某 kind 的活动态任务 id；读库失败返回 emptyList（降级语义 = 本次启动跳过对账）。 */
    @Suppress("TooGenericExceptionCaught") // 与 currentStatus 读路径降级对齐：读失败视同无活动行，不杀死适配器对账协程
    suspend fun activeIdsOfKind(kind: UserTaskKind): List<String> {
        return try {
            dao.activeIdsOfKind(kind.name)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Logger.w(TAG, "activeIdsOfKind failed, kind=$kind", exception)
            emptyList()
        }
    }

    /** 适配器对账用：注册表中某任务的当前状态（无行或读库失败返回 null）。 */
    @Suppress("TooGenericExceptionCaught") // 与 upsertStatus 写路径降级对齐：读失败视同无行，不杀死适配器 collect 协程
    suspend fun currentStatus(taskId: String): UserTaskStatus? {
        val row = try {
            dao.getById(taskId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Logger.w(TAG, "currentStatus failed, id=$taskId", exception)
            return null
        }
        return row?.let { entity ->
            runCatching { UserTaskStatus.valueOf(entity.status) }.getOrNull()
        }
    }

    private fun UserTaskEntity.toUserTask(snapshot: TaskProgressSnapshot?): UserTask? {
        val kind = runCatching { UserTaskKind.valueOf(kind) }.getOrElse { exception ->
            Logger.w(TAG, "drop row with illegal kind, id=$id kind=$kind", exception)
            return null
        }
        val status = runCatching { UserTaskStatus.valueOf(status) }.getOrElse { exception ->
            Logger.w(TAG, "drop row with illegal status, id=$id status=$status", exception)
            return null
        }
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

    /** 落库相等键：仅含内容字段；updatedAt/completedAt 为派生刷新字段不入键。 */
    private data class WrittenRow(
        val kind: UserTaskKind,
        val displayName: String?,
        val status: UserTaskStatus,
        val errorCode: UserTaskErrorCode?,
        val errorDetail: String?,
    )

    companion object {
        const val HISTORY_KEEP = 50
        private const val TAG = "UserTask"
    }
}
