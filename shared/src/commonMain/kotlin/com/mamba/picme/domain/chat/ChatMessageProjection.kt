package com.mamba.picme.domain.chat

import com.mamba.picme.domain.chat.openui.OpenUiContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * parts → Room `chat_messages` 标量列投影（D1a parts-first 写路径唯一派生点，spec
 * 《chat-value-domain-ideal》§4.1 裁决 (a)：partsJson 权威，标量列降级为派生投影）。
 *
 * 投影规则（终态立法）：
 * - **type** = 首个 content/tool/data 类 part 的线格式鉴别值（有实义 kind 的三类 part
 *   均可主导行判型——data 类是抽卡/GenUI 行的唯一 kind，跨会话查询与 gacha 恢复依赖
 *   type 列命中 data_* 值）；reasoning / source / tombstone **永不作投影源**（伴随载荷
 *   不主导消息 kind——reasoning 是思考链、source 是引用、tombstone 是读侧合成物）；
 *   全消息无 content/tool/data part（纯 reasoning+source）→ `"text"`。
 * - **content** = 列表气泡可读性优先：Text.markdown / Image.ref / EditResult.description /
 *   Chart.svg / HtmlCard.html / BrowserLive 与 OpenUiCard 整颗 part JSON（沿 M1 content 列
 *   直通先例）；TaskCard 与 OptimizeCandidates → `""`（结构化负载由 metadata 承载）。
 *   user 图文（Image+Text 双 part）取 Text.markdown——正文列即用户文字，图 ref 走
 *   metadata.imageUri（与备份 v5 粗映射物化器的 user 分图分支互逆，见 androidApp BackupChatRestoreMapper）。
 * - **metadata** = 结构化查询/恢复负载，键名与 androidApp 读侧（parseEngineerTaskState/parseHtmlCardMeta/OptimizeCandidateGroup.fromJson）及备份粗映射物化器的解析键
 *   一致（它是现存读侧真相，投影即其逆）：HtmlCard → `{"html_card":{…}}`；TaskCard →
 *   `{"engineer_task":{…}}`；OptimizeCandidates → group JSON（顶层）；agent Image →
 *   `{"imageUri","saved"}`；EditResult → `{"imageUri","saved","suggestions"}`（缺省不写）；
 *   其余 → null。
 *
 * 纯函数、无平台依赖；androidApp Room 写接缝（`ChatMessageParts.kt`）是唯一消费方。
 */
data class ChatRowProjection(val type: String, val content: String, val metadata: String?)

/**
 * 判型锚 part（单点立法，值域终态中点审查 🟡2）：首个 content/tool/data 类 part；
 * reasoning / source / tombstone 永不作锚（伴随载荷不主导消息 kind，spec §3/§6.1——
 * reasoning 排序恒在 text 前也不改变判型）。写侧投影（[projectChatRow]）与双端读侧
 * 判型（androidApp `ChatViewModel.toUiModel` / iOS `ChatHistoryStoreCodec.uiTypeOf`，
 * iOS 经 SharedKit 消费本函数）唯一同源实现，消除「写侧投影 type=tool_chart、读侧按
 * parts 首元素判型落 TEXT」的失锚风险。
 */
fun primaryPartOf(parts: List<MessagePart>): MessagePart? =
    parts.firstOrNull { part ->
        // Tombstone 显式排除：其 category 归 DATA（Task 1 终态分类），但它是读侧合成占位
        //（未知/退役 part 的可读投影），不主导消息 kind
        part !is MessagePart.Tombstone &&
            (part.category == PartCategory.CONTENT ||
                part.category == PartCategory.TOOL ||
                part.category == PartCategory.DATA)
    }

fun projectChatRow(parts: List<MessagePart>, role: ModelInputRole): ChatRowProjection {
    val primary = primaryPartOf(parts)
    return when (primary) {
        null -> ChatRowProjection(TYPE_TEXT, "", null)
        is MessagePart.Text -> ChatRowProjection(TYPE_TEXT, primary.markdown, null)
        is MessagePart.Image -> projectImageRow(primary, parts, role)
        is MessagePart.Chart -> ChatRowProjection("tool_chart", primary.svg, null)
        is MessagePart.HtmlCard -> ChatRowProjection(
            type = "tool_html",
            content = primary.html,
            metadata = htmlCardMetadata(primary.meta),
        )
        is MessagePart.TaskCard -> ChatRowProjection(
            type = EngineerTaskState.ROOM_TYPE,
            content = "",
            metadata = engineerTaskMetadata(primary.task),
        )
        is MessagePart.EditResult -> ChatRowProjection(
            type = "tool_image_edit",
            content = primary.description,
            metadata = editResultMetadata(primary.ref, primary.saved, primary.suggestions),
        )
        is MessagePart.BrowserLive -> ChatRowProjection("tool_browser", MessagePartsCodec.encodePart(primary), null)
        is MessagePart.OptimizeCandidates -> ChatRowProjection(
            type = OptimizeCandidateGroup.MESSAGE_TYPE,
            content = "",
            metadata = metadataJson.encodeToString(OptimizeCandidateGroup.serializer(), primary.group),
        )
        is MessagePart.OpenUiCard -> ChatRowProjection(OpenUiContract.PART_TYPE, MessagePartsCodec.encodePart(primary), null)
        // reasoning/source/tombstone 已被 primaryPartOf（判型锚单点立法）过滤排除，分支
        // 不可达；保留满足 sealed 穷尽性
        is MessagePart.Reasoning,
        is MessagePart.SourceUrl,
        is MessagePart.SourceDocument,
        is MessagePart.Tombstone,
        -> ChatRowProjection(TYPE_TEXT, "", null)
    }
}

/**
 * 图片行投影按角色分流（对齐 converter 读侧 image 分支）：
 * - user 图文（Image + Text 双 part）：content = 用户文字，metadata.imageUri = 图 ref
 *   （converter 逆投影后还原 [Image, Text] 双 part）；
 * - user 纯图：content = ref，metadata = null（converter 逆投影 ref 取 content）；
 * - agent 图：content = ref，metadata = {imageUri, saved}（converter 逆投影 ref 取
 *   metadata.imageUri ?: content、saved 取 metadata.saved）。
 */
private fun projectImageRow(
    primary: MessagePart.Image,
    parts: List<MessagePart>,
    role: ModelInputRole,
): ChatRowProjection {
    val userText = if (role == ModelInputRole.USER) {
        parts.filterIsInstance<MessagePart.Text>().firstOrNull()
    } else {
        null
    }
    return when {
        userText != null -> ChatRowProjection(
            type = TYPE_IMAGE,
            content = userText.markdown,
            metadata = buildJsonObject { put("imageUri", primary.ref) }.toString(),
        )
        role == ModelInputRole.USER -> ChatRowProjection(TYPE_IMAGE, primary.ref, null)
        else -> ChatRowProjection(
            type = TYPE_IMAGE,
            content = primary.ref,
            metadata = buildJsonObject {
                put("imageUri", primary.ref)
                put("saved", primary.saved)
            }.toString(),
        )
    }
}

/** EditResult 投影 metadata：imageUri / saved / suggestions（缺省不写，converter 解析键对齐）。 */
private fun editResultMetadata(ref: String?, saved: Boolean, suggestions: List<String>): String? {
    if (ref == null && !saved && suggestions.isEmpty()) return null
    return buildJsonObject {
        ref?.let { put("imageUri", it) }
        if (saved) put("saved", true)
        if (suggestions.isNotEmpty()) {
            putJsonArray("suggestions") { suggestions.forEach(::add) }
        }
    }.toString()
}

/**
 * html_card 投影 metadata：`{"html_card":{…}}`，键 display/displayMode(name)/
 * measuredHeightPx/summary（缺省不写）——与读侧 `parseHtmlCardMeta` /
 * androidApp 读侧 parseHtmlCardMeta 的解析键对齐（备份粗映射物化器同口径），形态对齐旧 org.json 写出
 * （nullable 字段不写显式 null：org.json 读侧 `has()`+`getInt` 对显式 null 会抛）。
 */
private fun htmlCardMetadata(meta: HtmlCardMeta): String? {
    val card = buildJsonObject {
        meta.display?.let { put("display", it) }
        meta.displayMode?.let { put("displayMode", it.name) }
        meta.measuredHeightPx?.let { put("measuredHeightPx", it) }
        meta.summary?.let { put("summary", it) }
    }
    if (card.size == 0) return null
    return buildJsonObject { put("html_card", card) }.toString()
}

/**
 * engineer_task 投影 metadata：`{"engineer_task":{…}}`——非空默认标量（status/turns/
 * fileChangeCount）恒写出（读侧对缺失 status 回退 FAILED，省略会把 RUNNING 卡还原成
 * FAILED）；nullable 字段不写显式 null（org.json 读侧 `has()`+`getInt/getLong` 对显式
 * null 抛异常）。形态对齐旧 org.json `EngineerTaskState.toJson()`。
 */
private fun engineerTaskMetadata(task: EngineerTaskState): String = buildJsonObject {
    put("engineer_task", buildJsonObject {
        put("taskId", task.taskId)
        put("sourceText", task.sourceText)
        task.sid?.let { put("sid", it) }
        put("status", task.status.name)
        task.stage?.let { put("stage", it) }
        putJsonArray("recentStages") { task.recentStages.forEach(::add) }
        put("turns", task.turns)
        task.costCents?.let { put("costCents", it) }
        put("startedAtMs", task.startedAtMs)
        put("updatedAtMs", task.updatedAtMs)
        put("fileChangeCount", task.fileChangeCount)
        task.truncatedReason?.let { put("truncatedReason", it) }
        task.errorSummary?.let { put("errorSummary", it) }
        task.resultSummary?.let { put("resultSummary", it) }
        task.resolution?.let { put("resolution", it.name) }
        task.deliverBranch?.let { put("deliverBranch", it) }
    })
}.toString()

/**
 * 投影 metadata 负载序列化（group 等纯非空负载）：encodeDefaults = false 保持紧凑
 * （缺省字段读侧均有默认值兜底），无 nullable 顶层字段故不产生显式 null。
 */
private val metadataJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}

private const val TYPE_TEXT = "text"
private const val TYPE_IMAGE = "image"
