package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * opaque handle（`handle: <domain>/<id>`）的本地数据锚点（D2：完整结果引用式，不内联不上云）。
 * payloadJson 结构由 domain 决定：scan = List<ScanPhotoEntry>（shared a2ui 包）。
 */
@Entity(tableName = "local_handle")
data class LocalHandleEntity(
    @PrimaryKey val handleId: String,
    val domain: String,
    val payloadJson: String,
    val createdAt: Long,
)

@Dao
interface LocalHandleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(handle: LocalHandleEntity)

    @Query("SELECT * FROM local_handle WHERE handleId = :handleId")
    suspend fun byId(handleId: String): LocalHandleEntity?

    @Query("DELETE FROM local_handle WHERE createdAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
