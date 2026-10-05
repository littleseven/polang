package com.mamba.picme.data.local

import com.mamba.picme.data.local.dao.UserTaskDao
import com.mamba.picme.data.local.entity.UserTaskEntity
import com.mamba.picme.domain.usertask.UserTaskRow
import com.mamba.picme.domain.usertask.UserTaskStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room user_task 表 → shared [UserTaskStore] 的适配实现（entity↔row 枚举名线格式不变）。
 * 组合根注入：UserTaskRegistry(store = RoomUserTaskStore(database.userTaskDao()), …)。
 */
class RoomUserTaskStore(private val dao: UserTaskDao) : UserTaskStore {

    override fun observeAll(): Flow<List<UserTaskRow>> =
        dao.observeAll().map { entities -> entities.map { entity -> entity.toRow() } }

    override suspend fun getById(id: String): UserTaskRow? = dao.getById(id)?.toRow()

    override suspend fun upsert(row: UserTaskRow) = dao.upsert(row.toEntity())

    override suspend fun trimHistory(keep: Int) = dao.trimHistory(keep)

    override suspend fun activeIdsOfKind(kind: String): List<String> = dao.activeIdsOfKind(kind)

    private fun UserTaskEntity.toRow(): UserTaskRow = UserTaskRow(
        id = id,
        kind = kind,
        displayName = displayName,
        status = status,
        errorCode = errorCode,
        errorDetail = errorDetail,
        destination = destination,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    private fun UserTaskRow.toEntity(): UserTaskEntity = UserTaskEntity(
        id = id,
        kind = kind,
        displayName = displayName,
        status = status,
        errorCode = errorCode,
        errorDetail = errorDetail,
        destination = destination,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
}
