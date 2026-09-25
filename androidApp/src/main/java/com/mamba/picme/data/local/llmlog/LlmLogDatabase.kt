package com.mamba.picme.data.local.llmlog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 远程 LLM 调用日志 + tool 执行指标**独立数据库**。
 *
 * - 与主库 [com.mamba.picme.data.local.AppDatabase] 完全独立：单独 DB 文件
 *   (polang_llm_log.db)、单独 version / migration，零外键、零共享 schema。
 * - 含 llm_call_log（推理层：LLM 调用）、tool_call_log（行动层：tool 执行指标）、
 *   js_run_log（端侧执行层：JS 沙盒运行事件）、routing_audit_log（路由层：意图路由
 *   判定审计，spec《意图路由契约与意图路由器》§3.7）四张表。
 * - 全构建写入；release 构建仅落纯指标，不落消息内容（隐私红线）。
 * - 采用 fallbackToDestructiveMigration（诊断数据可丢）。
 */
@Database(
    entities = [LlmCallLogEntity::class, ToolCallLogEntity::class, JsRunLogEntity::class, RoutingAuditLogEntity::class],
    version = 5,
    exportSchema = false
)
abstract class LlmLogDatabase : RoomDatabase() {

    abstract fun llmCallLogDao(): LlmCallLogDao
    abstract fun toolCallLogDao(): ToolCallLogDao
    abstract fun jsRunLogDao(): JsRunLogDao
    abstract fun routingAuditLogDao(): RoutingAuditLogDao

    companion object {
        @Volatile
        private var INSTANCE: LlmLogDatabase? = null

        fun getDatabase(context: Context): LlmLogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LlmLogDatabase::class.java,
                    "polang_llm_log.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
