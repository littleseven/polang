package com.mamba.picme.domain.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [MessagePart] 列表的 kotlinx JSON 编解码（Room `partsJson` 列的线格式，ADR-016 D1）。
 *
 * 对齐 Vercel「UIMessage JSON 整包落库」实践：鉴别字段 `type`，值遵循 8 值分类法
 * （type taxonomy spec §1）：text/image 为内容物，tool_chart/tool_html/tool_task/
 * tool_image_edit 为工具产物，data_media_results/data_optimize_candidates 为数据载荷。
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
     */
    fun decode(partsJson: String?): List<MessagePart>? {
        if (partsJson.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(listSerializer, partsJson) }.getOrNull()
    }
}
