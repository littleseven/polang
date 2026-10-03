package com.mamba.picme.domain.usertask

import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Swift（Documents/user_tasks.json 文件持久化）→ Kotlin 的桥协议。由 iosApp
 * `UserTaskStoreBridge`（Swift/NSObject）实现；语义对齐 Android `RoomUserTaskStore`。
 *
 * SharedBridge 铁律（同 [com.mamba.picme.data.IosMediaRepositoryBridge]）：Swift 实现侧绝不抛异常
 * 跨边界——读写失败一律用空集合 / false 表达；loadAll 失败降级为空态，persistAll 失败经
 * [UserTaskPersistException] 上抛，由 registry 降级路径接管（内存态保留、不写节流缓存、下帧重试）。
 */
interface IosUserTaskStoreBridge {
    /** 启动全量加载（重启后行原样呈现，终态历史保留；进程死亡对账在适配器层——模型下载已实装，TAG 扫描降级见台账）。 */
    fun loadAll(): List<UserTaskRow>

    /** 全量覆盖落盘（行数封顶 HISTORY_KEEP + 活动行，量级小，整写原子替换）。返回是否成功。 */
    fun persistAll(rows: List<UserTaskRow>): Boolean
}

/**
 * `UserTaskStore` 的 iOS 实现：内存 [MutableStateFlow] 为真源，每次变更整写 Swift 桥落盘。
 * 排序/封顶口径与 Android `UserTaskDao` SQL 一致：observeAll 按 updatedAt 倒序；
 * trimHistory 仅保留最近 [keep] 条终态行（completedAt 倒序，null 视同 epoch 0 排尾）。
 */
class IosUserTaskStore(
    private val bridge: IosUserTaskStoreBridge,
) : UserTaskStore {

    private val mutex = Mutex()
    private val rowsFlow = MutableStateFlow(bridge.loadAll().sortedByDescending { it.updatedAt })

    override fun observeAll(): Flow<List<UserTaskRow>> = rowsFlow

    override suspend fun getById(id: String): UserTaskRow? = rowsFlow.value.firstOrNull { it.id == id }

    override suspend fun upsert(row: UserTaskRow) {
        mutate { current -> current.filterNot { it.id == row.id } + row }
    }

    override suspend fun trimHistory(keep: Int) {
        mutate { current ->
            val dropIds = current
                .filter { it.status in TERMINAL_STATUSES }
                .sortedByDescending { it.completedAt ?: 0L }
                .drop(keep)
                .mapTo(HashSet()) { it.id }
            if (dropIds.isEmpty()) current else current.filterNot { it.id in dropIds }
        }
    }

    override suspend fun activeIdsOfKind(kind: String): List<String> =
        rowsFlow.value.filter { it.kind == kind && it.status in ACTIVE_STATUSES }.map { it.id }

    private suspend fun mutate(transform: (List<UserTaskRow>) -> List<UserTaskRow>) {
        mutex.withLock {
            val updated = transform(rowsFlow.value).sortedByDescending { it.updatedAt }
            rowsFlow.value = updated
            // 落盘在锁内与内存更新同序提交（锁外落盘并发下序可与内存倒挂）；失败上抛——
            // 内存态已更新，由 registry 既有 catch-降级-不缓存路径接管，同态帧下帧重试
            //（对齐 Android Room 抛异常语义，替代原仅记 warning 正常返回）
            if (!bridge.persistAll(updated)) {
                throw UserTaskPersistException()
            }
        }
    }

    companion object {
        private val ACTIVE_STATUSES = setOf("PENDING", "RUNNING", "PAUSED")
        private val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED", "CANCELLED")
    }
}

/** 桥落盘失败的信号异常（Swift 桥以 false 表达失败、不跨边界抛）；registry 捕获后降级不缓存。 */
class UserTaskPersistException : Exception("persistAll failed; in-memory state kept, retry on next mutation")

/**
 * iOS 工厂：封装 [UserTaskRegistry] 构造（对齐 StreamingPacingControllerFactory 先例——
 * Swift 不能直接构造 Kotlin [CoroutineScope]，由 iosMain 注入）。
 *
 * scope 口径对齐 Android AppContainer.userTaskScope：SupervisorJob + Default + CEH 记日志不穿透；
 * 进程级单例（AppContainer 持有），不随页面销毁。
 */
fun createUserTaskRegistry(bridge: IosUserTaskStoreBridge): UserTaskRegistry =
    UserTaskRegistry(
        store = IosUserTaskStore(bridge),
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, throwable ->
                    Logger.e("UserTask", "uncaught exception in ios userTaskScope", throwable)
                },
        ),
    )
