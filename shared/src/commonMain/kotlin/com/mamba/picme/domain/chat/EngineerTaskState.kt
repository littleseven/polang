package com.mamba.picme.domain.chat

/**
 * 工程师模式任务卡状态（spec 2026-09-25-engineer-task-card-design D1/D2）。
 * 纯数据形状，双端 SSOT；状态迁移在 androidApp `EngineerTaskReducer`（消费 ClaudeEvent）。
 * org.json 序列化为平台边界，由 androidApp ChatModelCommonMainShim 提供（对齐 ClaudeAgentState 先例）。
 */
enum class EngineerTaskStatus { RUNNING, AWAITING_CONTINUE, AWAITING_DELIVER, COMPLETED, FAILED }

/** 审批动作回填（US-9）：已继续 / 已放弃 / 已交付 / 暂不交付。 */
enum class EngineerTaskResolution { CONTINUED, ABANDONED, DELIVERED, DELIVER_SKIPPED }

data class EngineerTaskState(
    val taskId: String,
    val sourceText: String,
    val sid: String? = null,
    val status: EngineerTaskStatus = EngineerTaskStatus.RUNNING,
    val stage: String? = null,
    val recentStages: List<String> = emptyList(),
    val turns: Int = 0,
    val costCents: Int? = null,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val fileChangeCount: Int = 0,
    val truncatedReason: String? = null,
    val errorSummary: String? = null,
    val resultSummary: String? = null,
    val resolution: EngineerTaskResolution? = null,
    val deliverBranch: String? = null,
) {
    companion object {
        /** Room chat_messages.type 列值（TEXT 列，新增类型无需迁移）。 */
        const val ROOM_TYPE = "task_card"
    }
}
