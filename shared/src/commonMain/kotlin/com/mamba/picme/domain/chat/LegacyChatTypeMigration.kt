package com.mamba.picme.domain.chat

/**
 * legacy 13 值 → 新 8 值分类法的唯一映射知识库（spec §4.2）。
 *
 * 只被两处调用：MIGRATION_25_26（Room 全量改写）与备份恢复（旧备份转正）。
 * 运行时不双吃——新写路径产出新值后永不经过这里。
 *
 * 注意：when 分支必须匹配 legacy 字面量（"task_card"/"optimize_candidates"），
 * 禁用 EngineerTaskState.ROOM_TYPE / OptimizeCandidateGroup.MESSAGE_TYPE 常量——
 * 常量在本重构后已是新值，而本映射的输入是 legacy 值。
 */
object LegacyChatTypeMigration {

    /** 新分类法 8 值全集（备份恢复据此判断行是新值还是 legacy）。 */
    val NEW_TYPES: Set<String> = setOf(
        "text", "image",
        "tool_chart", "tool_html", "tool_task", "tool_image_edit",
        "data_media_results", "data_optimize_candidates",
    )

    data class MigratedRow(val type: String, val role: String, val parts: List<MessagePart>)

    fun map(legacyType: String, content: String, metadata: String?): MigratedRow {
        val role = if (legacyType.startsWith("user_")) "user" else "agent"
        val newType = when (legacyType) {
            "user_text", "agent_text", "command", "plan_preview" -> "text"
            "user_image", "user_image_text", "agent_image" -> "image"
            "chart" -> "tool_chart"
            "html_card" -> "tool_html"
            "task_card" -> "tool_task" // legacy 字面量，禁用 ROOM_TYPE 常量
            "agent_edit_result" -> "tool_image_edit"
            "media_results" -> "data_media_results"
            "optimize_candidates" -> "data_optimize_candidates" // legacy 字面量
            else -> "text"
        }
        return MigratedRow(newType, role, MessagePartsConverter.toParts(newType, content, metadata, roleOf(role)))
    }
}
