package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DedupHashDao {
    @Query("SELECT * FROM dedup_hash WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<DedupHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DedupHashEntity>)

    @Query("DELETE FROM dedup_hash WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    /** 全部已有哈希（md5/phash 至少其一非空），整理中心 v2 重复组判定输入。 */
    @Query("SELECT uri, md5, phash FROM dedup_hash WHERE md5 IS NOT NULL OR phash IS NOT NULL")
    suspend fun getAllHashes(): List<DedupHashRow>
}

/** dedup_hash 轻量投影（整理中心 v2 重复组判定）。 */
data class DedupHashRow(
    val uri: String,
    val md5: String?,
    val phash: Long?,
)
