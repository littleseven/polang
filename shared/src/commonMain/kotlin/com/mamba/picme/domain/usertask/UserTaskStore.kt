package com.mamba.picme.domain.usertask

import kotlinx.coroutines.flow.Flow

/**
 * 用户任务持久化行（spec §5：元数据落库，进度走内存）。
 * 枚举以 name 字符串落线格式（与 Room user_task 表列口径一致）；非法值由注册表侧丢弃整行。
 * 静态文案不入库（I18N 红线）——标题由 UI 按 kind 取本地文案。
 */
data class UserTaskRow(
    val id: String,
    val kind: String,
    val displayName: String?,
    val status: String,
    val errorCode: String?,
    val errorDetail: String?,
    val destination: String,
    val updatedAt: Long,
    val completedAt: Long?,
)

/**
 * 用户任务存储抽象（UserTaskRegistry 的平台接缝）：Android = Room user_task 表适配实现；
 * iOS = Swift 文件/内存实现（经 SKIE 消费注册表，store 可内存实现起步）。
 */
interface UserTaskStore {
    /** 全量行流，按 updatedAt 倒序。 */
    fun observeAll(): Flow<List<UserTaskRow>>

    suspend fun getById(id: String): UserTaskRow?

    suspend fun upsert(row: UserTaskRow)

    /** 历史封顶：仅保留最近 [keep] 条终态任务（spec §5 历史语义）。 */
    suspend fun trimHistory(keep: Int)

    /** 某 kind 的活动态（PENDING/RUNNING/PAUSED）任务 id，适配器启动对账用。 */
    suspend fun activeIdsOfKind(kind: String): List<String>
}
