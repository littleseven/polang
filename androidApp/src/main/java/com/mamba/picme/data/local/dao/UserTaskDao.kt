package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.UserTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: UserTaskEntity)

    @Query("SELECT * FROM user_task ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<UserTaskEntity>>

    @Query("SELECT * FROM user_task WHERE id = :id")
    suspend fun getById(id: String): UserTaskEntity?

    @Query("SELECT id FROM user_task WHERE kind = :kind AND status IN ('PENDING', 'RUNNING', 'PAUSED')")
    suspend fun activeIdsOfKind(kind: String): List<String>

    /** 历史封顶：仅保留最近 [keep] 条终态任务（spec §5 历史语义）。 */
    @Query(
        "DELETE FROM user_task WHERE id IN (" +
            "SELECT id FROM user_task WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
            "ORDER BY completedAt DESC LIMIT -1 OFFSET :keep)"
    )
    suspend fun trimHistory(keep: Int)
}
