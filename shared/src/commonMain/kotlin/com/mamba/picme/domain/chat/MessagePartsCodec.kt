package com.mamba.picme.domain.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * [MessagePart] 列表的 kotlinx JSON 编解码（Room `partsJson` 列的线格式，ADR-016 D1）。
 *
 * 对齐 Vercel「UIMessage JSON 整包落库」实践：鉴别字段 `type`，值域 = **「4+1 分类
 * 13 值」终态**（spec《chat-value-domain-ideal》§3）：text/image（content）+ reasoning +
 * tool_chart/tool_html/tool_task/tool_image_edit/tool_browser（tool）+
 * data_optimize_candidates/data_genui_program（data）+ source_url/source_document
 * （source）+ tombstone 兜底值。已出清的 legacy 鉴别值（A2UI `data_ui` 等）无显式
 * 分支——解码失败统一落 [MessagePart.Tombstone] 占位（2026-11-08 墓碑拦截机器出清）；
 * `data_media_results`（横滑相册卡退役）存量行另由回放路径按
 * `LegacyChatTypeMigration.RETIRED_MEDIA_RESULTS_TYPES` 整行跳过。
 */
object MessagePartsCodec {

    private val json = Json {
        // 前向兼容：新版本 part 增加的字段，旧版本解码时忽略（不炸迁移后的老包）
        ignoreUnknownKeys = true
        // 紧凑落库：缺省值不写出（decode 回填默认值，round-trip 等值）
        encodeDefaults = false
    }

    private val listSerializer = ListSerializer(MessagePart.serializer())

    fun encode(parts: List<MessagePart>): String = json.encodeToString(listSerializer, parts)

    /**
     * 单 part 编码（配置同 [encode]，紧凑线格式）：投影 content 列「整颗 part JSON」
     * 直通先例（tool_browser / data_genui_program）的唯一编码出口。
     */
    fun encodePart(part: MessagePart): String = json.encodeToString(MessagePart.serializer(), part)

    /**
     * 解码 parts JSON；空串/整体结构损坏（非 JSON 数组）返回 null，调用方按 legacy
     * 列兜底（[MessagePartsConverter]），**不允许丢消息**。
     *
     * part 级兜底（Tombstone）：逐元素独立解码——未知 part 类型（新版本写入、旧版本
     * 不识，含已退役鉴别值）或已知类型字段损坏 → 原位合成 [MessagePart.Tombstone]
     * 占位，块序与其余 part 不受影响；原始 payload 保留在 partsJson 原文中，升级
     * 新版本后自动恢复为正式卡片。
     */
    fun decode(partsJson: String?): List<MessagePart>? {
        if (partsJson.isNullOrBlank()) return null
        val array = runCatching { json.parseToJsonElement(partsJson).jsonArray }.getOrNull() ?: return null
        return array.mapIndexed { index, element -> decodePartOrTombstone(element, index) }
    }

    /**
     * 单 part 解码（行级隔离，iOS `ChatHistoryStoreCodec` 复用同一口径）：正常解码 →
     * 失败（未知/退役/损坏）合成 [MessagePart.Tombstone]（不穿透、不拖垮同消息其它
     * part）。
     */
    internal fun decodePartOrTombstone(element: JsonElement, index: Int): MessagePart {
        val obj = element as? JsonObject
        // as? JsonPrimitive 安全形态：病态非 primitive type 值（数组/对象）不抛，落 Tombstone
        val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull
        return runCatching { json.decodeFromJsonElement(MessagePart.serializer(), element) }
            .getOrElse { tombstonePartOf(obj, type, index) }
    }

    /**
     * 未知/退役/损坏 part → [MessagePart.Tombstone] 合成（codec 与 converter 共用）：
     * partId 取 payload 原值（缺失按数组序号合成 `p<index>`）；payload 携带可读摘要
     * 字段（fallbackSummary/summary/text/markdown，对齐 legacy 墓碑先例）时提升为
     * [MessagePart.Tombstone.summary]，给用户可读的降级内容。
     */
    internal fun tombstonePartOf(obj: JsonObject?, type: String?, index: Int): MessagePart.Tombstone {
        val partId = (obj?.get("partId") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "p$index"
        val summary = SUMMARY_KEYS.firstNotNullOfOrNull { key ->
            (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return MessagePart.Tombstone(partId, originalType = type ?: "unknown", summary = summary)
    }

    /** Tombstone 摘要提升的候选字段（优先级序）。 */
    private val SUMMARY_KEYS = listOf("fallbackSummary", "summary", "text", "markdown")
}
