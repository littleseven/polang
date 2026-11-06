package com.mamba.picme.domain.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * [MessagePart] 列表的 kotlinx JSON 编解码（Room `partsJson` 列的线格式，ADR-016 D1）。
 *
 * 对齐 Vercel「UIMessage JSON 整包落库」实践：鉴别字段 `type`，值遵循 10 值分类法
 * （type taxonomy spec §1）：text/image 为内容物，tool_chart/tool_html/tool_task/
 * tool_image_edit/tool_browser 为工具产物，data_media_results/data_optimize_candidates/
 * data_ui_openui 为数据载荷。legacy `data_ui`（A2UI 已退役）不在鉴别值域内，
 * 仅作墓碑拦截值显式处理（见 [decode]）。
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
     * 解码 parts JSON；空串/结构损坏/未知 part 类型返回 null，
     * 调用方按 legacy 列兜底（[MessagePartsConverter]），**不允许丢消息**。
     *
     * 例外：legacy `data_ui`（A2UI 已退役，A2uiCard part 类已删除）——
     * **先判 type 再解码**：data_ui 已非合法鉴别值，正常解码必失败；显式拦截提升
     * fallbackSummary 为可读 Text（ADR-016 降级口径，不丢消息）。
     */
    fun decode(partsJson: String?): List<MessagePart>? {
        if (partsJson.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(partsJson).jsonArray.map { element ->
                legacyDataUiTombstoneOrNull(element)
                    ?: json.decodeFromJsonElement(MessagePart.serializer(), element)
            }
        }.getOrNull()
    }

    /** legacy data_ui（A2UI 已退役）→ Text 墓碑：fallbackSummary 提升，不丢消息；非 data_ui 返回 null 走正常解码。 */
    private fun legacyDataUiTombstoneOrNull(element: JsonElement): MessagePart? {
        val obj = element as? JsonObject ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "data_ui") return null
        return MessagePartsConverter.legacyCardTombstone(obj)
    }
}
