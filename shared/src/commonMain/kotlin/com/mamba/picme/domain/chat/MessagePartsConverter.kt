package com.mamba.picme.domain.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * (type, content, metadata, role) → parts 的运行时转换器（M1 双写接缝的写侧纯函数）。
 *
 * 纯净化口径（spec §4.2）：只认新 8 值分类法；legacy 13→8 映射知识在
 * [LegacyChatTypeMigration]，本转换器不双吃。行级兜底不变：任何异常/空结果 → 原文 Text part。
 */
object MessagePartsConverter {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 转换单条 Room 消息行为 parts 列表（有序）。
     * partId 用消息内序号 `p0`/`p1`…（M1 迁移期约定；M2 流式起为真实 chunk id）。
     */
    fun toParts(type: String, content: String, metadata: String?, role: ModelInputRole): List<MessagePart> =
        runCatching { convert(type, content, metadata, role) }
            .getOrElse { fallbackText(content) }
            .ifEmpty { fallbackText(content) }

    /** 行级兜底：保留原文（content），UI 与回灌均有可读内容，消息不丢。 */
    private fun fallbackText(content: String): List<MessagePart> =
        listOf(MessagePart.Text(partId = "p0", markdown = content, state = PartState.DONE))

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 8 类型全枚举映射表，单点收口
    private fun convert(type: String, content: String, metadata: String?, role: ModelInputRole): List<MessagePart> {
        val meta = parseMetadata(metadata)
        return when (type) {
            "text" -> listOf(MessagePart.Text("p0", content, PartState.DONE))

            // image 按角色分流（spec §4.2）：user 图文在场拆双 part（图上文下），
            // 缺 uri 时 content 即路径；agent 图 ref 取 metadata.imageUri ?: content
            "image" -> when {
                role == ModelInputRole.USER && meta?.str("imageUri") != null ->
                    listOf(
                        MessagePart.Image("p0", ref = meta.str("imageUri")!!),
                        MessagePart.Text("p1", content, PartState.DONE),
                    )

                role == ModelInputRole.USER -> listOf(MessagePart.Image("p0", ref = content))

                else -> listOf(
                    MessagePart.Image(
                        "p0",
                        ref = meta?.str("imageUri") ?: content,
                        saved = meta?.bool("saved") ?: false,
                    ),
                )
            }

            "tool_chart" -> listOf(MessagePart.Chart("p0", svg = content))

            "tool_html" -> listOf(MessagePart.HtmlCard("p0", html = content, meta = parseHtmlCardMeta(meta)))

            // browser 直播卡：content 列存 BrowserLive 整颗 JSON（meta 列留空）；
            // 损坏行抛异常 → 行级 Text 兜底（对齐 toParts 的 runCatching 收口）
            "tool_browser" ->
                listOf(json.decodeFromString(MessagePart.BrowserLive.serializer(), content))

            EngineerTaskState.ROOM_TYPE ->
                listOf(parseTaskCard(meta))

            "tool_image_edit" ->
                listOf(
                    MessagePart.EditResult(
                        "p0",
                        ref = meta?.str("imageUri"),
                        description = content,
                        suggestions = meta?.strList("suggestions") ?: emptyList(),
                        saved = meta?.bool("saved") ?: false,
                    ),
                )

            "data_media_results" ->
                listOf(MessagePart.MediaResults("p0", parseMediaResults(content, meta)))

            OptimizeCandidateGroup.MESSAGE_TYPE ->
                listOf(MessagePart.OptimizeCandidates("p0", parseOptimizeGroup(metadata)))

            else -> fallbackText(content)
        }
    }

    // ── metadata 解析（kotlinx JsonElement，宽松口径对齐 org.json optXxx） ──

    private fun parseMetadata(metadata: String?): JsonObject? =
        metadata
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strList(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    // ── 各类型 payload 解析 ─────────────────────────────────────

    /** media_results：content = 展示资产 JSON 数组（id/uri/type/captureDate/fileName/faceFocusY）。 */
    private fun parseMediaResults(content: String, meta: JsonObject?): MediaResultsUi {
        val arr = json.parseToJsonElement(content) as? JsonArray
            ?: throw IllegalArgumentException("media_results content is not a JSON array")
        val assets = arr.map { element ->
            val obj = element as? JsonObject
                ?: throw IllegalArgumentException("media_results asset is not a JSON object")
            MediaAsset(
                id = obj["id"]?.jsonPrimitive?.long
                    ?: throw IllegalArgumentException("media_results asset missing id"),
                uri = obj["uri"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("media_results asset missing uri"),
                type = obj["type"]?.jsonPrimitive?.contentOrNull
                    ?.let { name -> runCatching { MediaType.valueOf(name) }.getOrDefault(MediaType.PHOTO) }
                    ?: MediaType.PHOTO,
                captureDate = obj["captureDate"]?.jsonPrimitive?.longOrNull ?: 0L,
                fileName = obj["fileName"]?.jsonPrimitive?.contentOrNull ?: "",
                faceFocusY = obj["faceFocusY"]?.jsonPrimitive?.doubleOrNull?.toFloat(),
            )
        }
        return MediaResultsUi(
            query = meta?.str("query") ?: "",
            assets = assets,
            totalCount = meta?.int("totalCount") ?: assets.size,
            isRefinement = meta?.bool("isRefinement") ?: false,
        )
    }

    /** html_card 双形态元数据（metadata `html_card` 子对象；缺失 = 未声明 inline 待测高）。 */
    private fun parseHtmlCardMeta(meta: JsonObject?): HtmlCardMeta {
        val sub = meta?.get("html_card") as? JsonObject ?: return HtmlCardMeta()
        return HtmlCardMeta(
            display = sub.str("display"),
            // 未知 displayMode 枚举值静默回退 null（待重新判定），对齐 androidApp parseHtmlCardMeta
            displayMode = sub.str("displayMode")
                ?.let { name -> runCatching { HtmlCardDisplayMode.valueOf(name) }.getOrNull() },
            measuredHeightPx = sub.int("measuredHeightPx"),
            summary = sub.str("summary"),
        )
    }

    /**
     * task_card：metadata `engineer_task` 子对象 → [EngineerTaskState] + [ToolPartState] 投影。
     * 缺 taskId / 整体结构损坏抛异常 → 行级 Text 兜底；其余字段缺失静默回退默认值（不丢卡），
     * 未知 status 回退 FAILED 安全终态（对齐 androidApp parseEngineerTaskState）。
     */
    private fun parseTaskCard(meta: JsonObject?): MessagePart.TaskCard {
        val root = meta?.get("engineer_task") as? JsonObject
            ?: throw IllegalArgumentException("task_card metadata missing engineer_task")
        val task = EngineerTaskState(
            taskId = root["taskId"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("task_card metadata missing taskId"),
            sourceText = root["sourceText"]?.jsonPrimitive?.contentOrNull ?: "",
            sid = root.str("sid"),
            status = root["status"]?.jsonPrimitive?.contentOrNull
                ?.let { name -> runCatching { EngineerTaskStatus.valueOf(name) }.getOrDefault(EngineerTaskStatus.FAILED) }
                ?: EngineerTaskStatus.FAILED,
            stage = root.str("stage"),
            recentStages = root.strList("recentStages") ?: emptyList(),
            turns = root.int("turns") ?: 0,
            costCents = root.int("costCents"),
            startedAtMs = root["startedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            updatedAtMs = root["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            fileChangeCount = root.int("fileChangeCount") ?: 0,
            truncatedReason = root.str("truncatedReason"),
            errorSummary = root.str("errorSummary"),
            resultSummary = root.str("resultSummary"),
            resolution = root.str("resolution")
                ?.let { name -> runCatching { EngineerTaskResolution.valueOf(name) }.getOrNull() },
            deliverBranch = root.str("deliverBranch"),
        )
        return MessagePart.TaskCard(
            partId = "p0",
            toolCallId = task.taskId,
            state = task.status.toToolPartState(),
            task = task,
        )
    }

    /**
     * optimize_candidates：metadata 整体即 [OptimizeCandidateGroup] JSON
     * （字段名与 org.json toJson 写出一致；必需字段缺失抛异常 → 行级 Text 兜底）。
     */
    private fun parseOptimizeGroup(metadata: String?): OptimizeCandidateGroup =
        json.decodeFromString(
            OptimizeCandidateGroup.serializer(),
            metadata ?: throw IllegalArgumentException("optimize_candidates metadata missing"),
        )
}
