package com.mamba.picme.data.local.llmlog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 意图路由判定审计 DAO。
 */
@Dao
interface RoutingAuditLogDao {

    @Insert
    suspend fun insert(entity: RoutingAuditLogEntity): Long

    /** 最近 [limit] 条（新→旧）。 */
    @Query("SELECT * FROM routing_audit_log ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RoutingAuditLogEntity>

    @Query("SELECT COUNT(*) FROM routing_audit_log")
    suspend fun count(): Int

    /** 仅保留最新的 [keep] 条，删除其余；返回删除行数。 */
    @Query(
        "DELETE FROM routing_audit_log WHERE id NOT IN " +
            "(SELECT id FROM routing_audit_log ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun prune(keep: Int): Int

    @Query("DELETE FROM routing_audit_log")
    suspend fun clearAll(): Int

    /** 同一 traceId 的全部记录（旧→新），供详情页 turn pager 装配。 */
    @Query("SELECT * FROM routing_audit_log WHERE traceId = :traceId ORDER BY createdAt ASC")
    suspend fun getByTraceId(traceId: String): List<RoutingAuditLogEntity>
}
