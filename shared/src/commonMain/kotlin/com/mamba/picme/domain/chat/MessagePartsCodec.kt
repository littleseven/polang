package com.mamba.picme.domain.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * [MessagePart] 列表的 kotlinx JSON 编解码（Room `partsJson` 列的线格式，ADR-016 D1）。
 *
 * 对齐 Vercel「UIMessage JSON 整包落库」实践：鉴别字段 `type`，值遵循 9 值分类法
 * （type taxonomy spec §1）：text/image 为内容物，tool_chart/tool_html/tool_task/
 * tool_image_edit/tool_browser 为工具产物，data_optimize_candidates/
 * data_ui_openui 为数据载荷。legacy `data_ui`（A2UI 已退役）与 `data_media_results`
 * （横滑相册卡 2026-11-08 退役）不在鉴别值域内——前者作墓碑拦截值显式处理（见 [decode]），
 * 后者由回放路径按 `LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES` 整行跳过。
 */
object MessagePartsCodec {

    private val json = Json {
        // 前向兼容：新版本 part 增加的字段，旧版本解码时忽略（不炸迁移后的老包）
        ignoreUnknownKeys = true
        // 紧凑落库：缺省值不写出（decode 回填默认值，round-trip 等值）
        encodeDefaults = false
    }

    fun encode(parts: List<MessagePart>): String {
        // Unsupported 携带解码回填的 rawPayload 时原位回写原文（行更新重编码不销毁
        // 未知 payload）；其余 part 正常序列化（encodeDefaults=false 紧凑落库）
        val elements = parts.map { part ->
            val raw = (part as? MessagePart.Unsupported)?.rawPayload
            raw?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                ?: json.encodeToJsonElement(MessagePart.serializer(), part)
        }
        return JsonArray(elements).toString()
    }

    /**
     * 解码 parts JSON；空串/整体结构损坏（非 JSON 数组）返回 null，调用方按 legacy
     * 列兜底（[MessagePartsConverter]），**不允许丢消息**。
     *
     * part 级兜底（2026-11-08 Unsupported 兜底）：逐元素独立解码——未知 part 类型
     * （新版本写入、旧版本不识）或已知类型字段损坏 → 原位合成 [MessagePart.Unsupported]
     * 占位，块序与其余 part 不受影响；原始 payload 保留在 partsJson 原文中，升级
     * 新版本后自动恢复为正式卡片。
     *
     * 例外：legacy `data_ui`（A2UI 已退役，A2uiCard part 类已删除）——**先判 type
     * 再解码**：显式拦截提升 fallbackSummary 为可读 Text（ADR-016 降级口径，不丢消息）。
     */
    fun decode(partsJson: String?): List<MessagePart>? {
        if (partsJson.isNullOrBlank()) return null
        val array = runCatching { json.parseToJsonElement(partsJson).jsonArray }.getOrNull() ?: return null
        return array.mapIndexed { index, element -> decodePartOrUnsupported(element, index) }
    }

    /**
     * 单 part 解码（行级隔离，iOS `ChatHistoryStoreCodec` 复用同一口径）：legacy data_ui
     * 墓碑拦截 → 正常解码 → 失败合成 [MessagePart.Unsupported]（unknown/损坏不穿透、
     * 不拖垮同消息其它 part）。
     */
    internal fun decodePartOrUnsupported(element: JsonElement, index: Int): MessagePart {
        val obj = element as? JsonObject
        // as? JsonPrimitive 安全形态：病态非 primitive type 值（数组/对象）不抛，落 Unsupported
        val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull
        if (type == "data_ui") return MessagePartsConverter.legacyCardTombstone(obj)
        return runCatching { json.decodeFromJsonElement(MessagePart.serializer(), element) }
            .getOrElse { unsupportedPartOf(obj, type, index) }
    }

    /**
     * 未知/损坏 part → [MessagePart.Unsupported] 合成（codec 与 converter 共用）：
     * partId 取 payload 原值（缺失按数组序号合成 `p<index>`）；payload 携带可读摘要
     * 字段（fallbackSummary/summary/text/markdown，对齐 legacy 墓碑先例）时提升为
     * [MessagePart.Unsupported.summary]，给用户可读的降级内容。
     */
    internal fun unsupportedPartOf(obj: JsonObject?, type: String?, index: Int): MessagePart.Unsupported {
        val partId = (obj?.get("partId") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "p$index"
        val summary = SUMMARY_KEYS.firstNotNullOfOrNull { key ->
            (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return MessagePart.Unsupported(partId, originalType = type ?: "unknown", summary = summary)
            .apply { rawPayload = obj?.toString() }
    }

    /** Unsupported 摘要提升的候选字段（优先级序）。 */
    private val SUMMARY_KEYS = listOf("fallbackSummary", "summary", "text", "markdown")
}
