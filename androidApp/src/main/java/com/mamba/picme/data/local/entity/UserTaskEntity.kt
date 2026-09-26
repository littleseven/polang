package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户任务身份行（spec §5：元数据落 Room，进度走内存）。
 * 静态文案不入库（I18N 红线）——标题由 UI 按 kind 取 string resource。
 */
@Entity(tableName = "user_task")
data class UserTaskEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val displayName: String?,
    val status: String,
    val errorCode: String?,
    val errorDetail: String?,
    val destination: String,
    val updatedAt: Long,
    val completedAt: Long?,
)
