package com.mamba.picme.data.local.llmlog

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 意图路由判定审计实体（独立数据库 polang_llm_log 第四张表 routing_audit_log）。
 *
 * spec《意图路由契约与意图路由器》§3.7 前半：每回合路由判定（含门控直通/降级）
 * 落一条记录，使「护栏前路由正确率」「降级率」「pattern 命中率」可观测。
 *
 * 只含路由维度指标（path / deliverable / confidence / latency / degradeReason），
 * **不含用户 query 原文与槽位内容**（隐私红线——文本 query 也属用户输入，不落库）。
 * 保留最近 200 条。所有列均为普通列，无外键。
 */
@Entity(tableName = "routing_audit_log")
data class RoutingAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** 关联 ID：一条用户消息一个 traceId，可与 llm_call_log / tool_call_log 串联。 */
    val traceId: String? = null,
    /** 路由路径（RoutePath.name）：PATTERN_SHORTCUT / LLM_ROUTER / GATED_PASSTHROUGH / DEGRADED_*。 */
    val path: String,
    /** 判定产出物（IntentId.name）。 */
    val deliverable: String,
    /** 路由器自评置信度（pattern/门控/降级路径为 1.0 或 0.0 等约定值）。 */
    val confidence: Double?,
    /** 是否 refine 语义（路由器判定，策略层消费）。 */
    val isRefinement: Boolean,
    val latencyMs: Long,
    /** 降级原因（非降级路径为 null）。 */
    val degradeReason: String? = null
)
