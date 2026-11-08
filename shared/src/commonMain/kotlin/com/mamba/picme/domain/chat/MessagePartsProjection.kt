package com.mamba.picme.domain.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * parts → Room 标量列投影（持久化接缝反转后的写侧纯函数，ADR-016 收口）。
 *
 * 反转后 chat_messages.partsJson 是唯一内容权威；type 列降级为写时投影
 * （DAO 查询/索引面：任务中心 type='tool_task'、回灌窗口 role 子查询），
 * content 列退役。本对象是投影规则的唯一事实源：
 * - [roomTypeOf]：primary part → Room type 值（10 值分类法，[MessagePartsConverter] 的逆映射）；
 * - [contentPreviewOf]：UI content 字段/会话标题的文本投影（Text parts 拼接）；
 * - [slimMetadataJson]：metadata 白名单过滤——消息级住户保留，part 级键剔除（防双写回潮）。
 */
object MessagePartsProjection {

    /**
     * primary part（首个 part）→ Room type 列值。
     * [MessagePart.Unsupported] 为读侧合成物（生产写路径不出现），投影落 "text"
     * （已知边角：恢复原类型前 type 列暂不准，行重写时自愈；type 查询不针对未知类型）。
     */
    fun roomTypeOf(parts: List<MessagePart>): String = when (parts.firstOrNull()) {
        is MessagePart.Text -> "text"
        is MessagePart.Image -> "image"
        is MessagePart.Chart -> "tool_chart"
        is MessagePart.HtmlCard -> "tool_html"
        is MessagePart.TaskCard -> "tool_task"
        is MessagePart.EditResult -> "tool_image_edit"
        is MessagePart.BrowserLive -> "tool_browser"
        is MessagePart.OptimizeCandidates -> "data_optimize_candidates"
        is MessagePart.OpenUiCard -> "data_ui_openui"
        // 退役横滑卡（2026-11-08）：converter/codec 已不识 data_media_results，存量行
        // parts 落 Unsupported 兜底——投影仍归一退役正型，回放路径按 type 整行跳过
        // （RETIRED_MEDIA_RESULTS_TYPES 闭集）；若投影落 text 会把机器 JSON 渲染成可见气泡
        is MessagePart.Unsupported ->
            if ((parts.first() as MessagePart.Unsupported).originalType in
                LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES
            ) {
                "data_media_results"
            } else {
                "text"
            }
        null -> "text"
    }

    /** UI content 字段投影：Text parts 的 markdown 按 "\n\n" 拼接；无 Text part 为空串。 */
    fun contentPreviewOf(parts: List<MessagePart>): String =
        parts.filterIsInstance<MessagePart.Text>().joinToString("\n\n") { it.markdown }

    /** metadata 白名单住户（消息级，不属于任何 part；part 级键已迁入 parts）。 */
    private val SLIM_METADATA_KEYS = setOf(
        "claude_agent_state",
        "prompt_len", "decode_len", "prefill_time_ms", "decode_time_ms",
        "prefill_speed", "decode_speed", "used_sandbox",
        "savedAt",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * metadata 白名单过滤：part 级键（html_card/engineer_task/imageUri/saved/suggestions/
     * explanation/query/totalCount/isRefinement 及抽卡组整组根字段）一律剔除；
     * 白名单全空/整体损坏 → null（该消息无消息级元数据）。
     */
    fun slimMetadataJson(metadata: String?): String? {
        val obj = metadata
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
            ?: return null
        val kept = obj.filterKeys { key -> key in SLIM_METADATA_KEYS }
        if (kept.isEmpty()) return null
        return JsonObject(kept).toString()
    }
}
