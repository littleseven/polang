@file:Suppress("TooGenericExceptionCaught") // 粗映射全链 runCatching 行级兜底，不丢消息不崩恢复

package com.mamba.picme.domain.backup

import com.mamba.picme.data.local.PartsMessageRow
import com.mamba.picme.domain.backup.model.BackupChatMessage
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.HtmlCardMeta
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.ModelInputRole
import com.mamba.picme.domain.chat.OptimizeCandidateGroup
import com.mamba.picme.domain.chat.PartState
import com.mamba.picme.domain.chat.roleOf
import com.mamba.picme.domain.chat.toToolPartState
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 备份恢复 chat 段的一次性粗映射表（spec《chat-value-domain-ideal》§4 第 3 条，
 * 2026-11-08 备份 v6 落地时自 shared 已退役的标量→parts 转换器收编，原件随本任务整删）。
 *
 * **只活在备份模块，运行时零 legacy 知识**——恢复入口按容器版本分流：
 * - **v6+ 包**：[BackupChatMessage.partsJson] 直通（Room parts-first 列原值）；
 *   缺失/损坏行降级 Tombstone/Text 兜底，不丢消息；
 * - **v5 及以下旧包**：粗映射表自标量列物化 parts——
 *   终态 9 值 wire 直通；legacy 旧值映射为新值（文本类直通 text/image、卡类直通
 *   对应 tool_ 或 data_ 前缀新值）；已退役卡（data_ui/data_media_results/media_results）落
 *   Tombstone 占位（originalType=旧值，可读摘要提升）或纯文本行直通 Text；
 *   不可识别 type 整行跳过。
 *
 * 标量→parts 物化的 metadata 解析为 org.json 自包含实现（本文件即对 v5 备份格式的
 * 冻结快照，不随运行时演进）：engineer_task/html_card 键与 androidApp 读侧
 * `parseEngineerTaskState`/`parseHtmlCardMeta`（features/chat shim）、optimize group
 * 键与 `OptimizeCandidateGroup.fromJson` 逐键对齐。
 */
internal object BackupChatRestoreMapper {

    /** 终态投影线值集（Room type 列 9 值 + v5 窗口期旧鉴别值 data_ui_openui）。 */
    private val TERMINAL_TYPES: Set<String> = setOf(
        "text", "image",
        "tool_chart", "tool_html", "tool_task", "tool_image_edit", "tool_browser",
        "data_optimize_candidates", "data_genui_program",
    )

    /** legacy 旧值 → 终态值（口径 = Room v28 出清前 legacy 映射表的显式分支，git 历史可查）。 */
    private val LEGACY_ALIASES: Map<String, String> = mapOf(
        "user_text" to "text", "agent_text" to "text",
        "command" to "text", "plan_preview" to "text",
        "user_image" to "image", "user_image_text" to "image", "agent_image" to "image",
        "chart" to "tool_chart", "html_card" to "tool_html",
        "task_card" to "tool_task", "agent_edit_result" to "tool_image_edit",
        "optimize_candidates" to "data_optimize_candidates",
    )

    /** 已退役卡：恢复为可读 Tombstone 占位（机器 JSON 不裸渲染成文本气泡）。 */
    private val RETIRED_CARD_TYPES: Set<String> = setOf(
        "media_results", "data_media_results", "data_ui",
    )

    /** v5 窗口期鉴别值改名（data_ui_openui → data_genui_program，2026-11-08 值域终态）。 */
    private const val LEGACY_OPENUI_TYPE = "data_ui_openui"

    /** Tombstone 摘要提升的候选字段（优先级序，口径对齐 codec `SUMMARY_KEYS`）。 */
    private val SUMMARY_KEYS = listOf("fallbackSummary", "summary", "text", "markdown")

    /**
     * 备份消息行 → parts-first 落库行。不可识别 type 返回 null（调用方跳过该行）。
     *
     * @param v6OrLater 容器版本 ≥ v6（partsJson 直通道）。
     */
    fun mapRow(msg: BackupChatMessage, v6OrLater: Boolean): PartsMessageRow? {
        val role = roleOf(msg.role ?: msg.type.roleFallback())
        val parts = if (v6OrLater) v6Parts(msg) else v5Parts(msg, role) ?: return null
        return PartsMessageRow(
            id = msg.id,
            sessionId = msg.sessionId,
            role = role,
            parts = parts,
            modelUsed = msg.modelUsed,
            timestamp = msg.timestamp,
            extraMetadataJson = msg.metadata,
        )
    }

    /** 旧备份无 role 列时按 legacy type 前缀推断（user_* → user，其余 agent）。 */
    private fun String.roleFallback(): String = if (startsWith("user_")) "user" else "agent"

    // ── v6+：partsJson 直通（缺失/损坏行降级兜底，不丢消息） ──────

    private fun v6Parts(msg: BackupChatMessage): List<MessagePart> =
        msg.partsJson?.let { MessagePartsCodec.decode(it) }.orEmpty()
            .ifEmpty { degradedParts(msg.type, msg.content) }

    /** v6 行降级兜底：content 为机器 JSON blob → Tombstone 占位；可读文本 → Text。 */
    private fun degradedParts(type: String, content: String): List<MessagePart> {
        val value = runCatching { JSONTokener(content).nextValue() }.getOrNull()
        return when (value) {
            is JSONObject ->
                listOf(MessagePart.Tombstone("p0", originalType = type, summary = value.promoteSummary()))
            is JSONArray -> listOf(MessagePart.Tombstone("p0", originalType = type))
            else -> textFallback(content)
        }
    }

    // ── v5-：粗映射表自标量列物化 ───────────────────────────────

    private fun v5Parts(msg: BackupChatMessage, role: ModelInputRole): List<MessagePart>? {
        val alias = LEGACY_ALIASES[msg.type]
        return when {
            msg.type == LEGACY_OPENUI_TYPE ->
                decodeSinglePart(rewriteDiscriminator(msg.content))
                    ?.let { single -> listOf(single) }
                    ?: retiredFallback(msg.type, msg.content)

            msg.type in TERMINAL_TYPES -> materializeTerminal(msg.type, msg.content, msg.metadata, role)

            alias != null -> materializeTerminal(alias, msg.content, msg.metadata, role)

            msg.type in RETIRED_CARD_TYPES -> retiredFallback(msg.type, msg.content)

            else -> null // 不可识别旧值：整行跳过
        }
    }

    /** 退役卡降级：机器 JSON → 可读 Tombstone（摘要提升）；纯文本行直通 Text。 */
    private fun retiredFallback(type: String, content: String): List<MessagePart> {
        val obj = runCatching { JSONObject(content) }.getOrNull()
            ?: return textFallback(content)
        return listOf(MessagePart.Tombstone("p0", originalType = type, summary = obj.promoteSummary()))
    }

    /**
     * 终态值物化（收编自已退役转换器的 convert 核心，行为等价）：
     * 行级 runCatching 兜底原文 Text，任何异常/空结果不丢消息。
     */
    private fun materializeTerminal(
        type: String,
        content: String,
        metadata: String?,
        role: ModelInputRole,
    ): List<MessagePart> = runCatching { convertTerminal(type, content, metadata, role) }
        .getOrElse { textFallback(content) }
        .ifEmpty { textFallback(content) }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // v5 备份格式冻结快照，单点收口
    private fun convertTerminal(
        type: String,
        content: String,
        metadata: String?,
        role: ModelInputRole,
    ): List<MessagePart> {
        val meta = parseMetadata(metadata)
        return when (type) {
            "text" -> listOf(MessagePart.Text("p0", content, PartState.DONE))

            // image 按角色分流：user 图文在场拆双 part（图上文下），缺 uri 时 content 即路径；
            // agent 图 ref 取 metadata.imageUri ?: content
            "image" -> when {
                role == ModelInputRole.USER && meta?.optStringOrNull("imageUri") != null ->
                    listOf(
                        MessagePart.Image("p0", ref = meta.optStringOrNull("imageUri")!!),
                        MessagePart.Text("p1", content, PartState.DONE),
                    )

                role == ModelInputRole.USER -> listOf(MessagePart.Image("p0", ref = content))

                else -> listOf(
                    MessagePart.Image(
                        "p0",
                        ref = meta?.optStringOrNull("imageUri") ?: content,
                        saved = meta?.optBooleanOrNull("saved") ?: false,
                    ),
                )
            }

            "tool_chart" -> listOf(MessagePart.Chart("p0", svg = content))

            "tool_html" -> listOf(MessagePart.HtmlCard("p0", html = content, meta = parseHtmlCardMeta(metadata)))

            // browser 直播卡 / OpenUI GenUI 卡：content 列存整颗 part JSON（meta 列留空）
            "tool_browser", "data_genui_program" ->
                decodeSinglePart(content)?.let { single -> listOf(single) } ?: retiredFallback(type, content)

            EngineerTaskState.ROOM_TYPE -> listOf(parseTaskCard(metadata))

            "tool_image_edit" -> listOf(
                MessagePart.EditResult(
                    "p0",
                    ref = meta?.optStringOrNull("imageUri"),
                    description = content,
                    suggestions = meta?.optStringList("suggestions") ?: emptyList(),
                    saved = meta?.optBooleanOrNull("saved") ?: false,
                ),
            )

            OptimizeCandidateGroup.MESSAGE_TYPE ->
                listOf(MessagePart.OptimizeCandidates("p0", parseOptimizeGroup(metadata)))

            else -> error("terminal type not covered: $type") // 防御：物化只服务已归一化的终态值
        }
    }

    /** 行级兜底：保留原文（content），UI 与回灌均有可读内容。 */
    private fun textFallback(content: String): List<MessagePart> =
        listOf(MessagePart.Text(partId = "p0", markdown = content, state = PartState.DONE))

    // ── 单 part JSON 直通解码（复用 codec 公开 API + 逐 part Tombstone 兜底） ──

    /**
     * content 列整颗 part JSON → [MessagePart]。包裹为数组走 [MessagePartsCodec.decode]
     * （part 级未知值/损坏原位落 Tombstone）；整体非 JSON 返回 null（调用方行级兜底）。
     */
    private fun decodeSinglePart(content: String): MessagePart? =
        MessagePartsCodec.decode("[$content]")?.singleOrNull()

    /** 改名窗口期鉴别值重写：把 payload 的 `type` 字段改写为终态值再直通解码。 */
    private fun rewriteDiscriminator(content: String): String =
        runCatching { JSONObject(content).put("type", "data_genui_program").toString() }.getOrDefault(content)

    // ── metadata 解析（org.json 宽松口径，nullable 语义显式化） ──

    private fun parseMetadata(metadata: String?): JSONObject? =
        metadata
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun JSONObject.optStringOrNull(key: String): String? =
        runCatching { optString(key) }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        runCatching { if (has(key) && !isNull(key)) optBoolean(key) else null }.getOrNull()

    private fun JSONObject.optStringList(key: String): List<String>? =
        runCatching {
            optJSONArray(key)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } } }
        }.getOrNull()

    private fun JSONObject.promoteSummary(): String? =
        SUMMARY_KEYS.firstNotNullOfOrNull { key -> optStringOrNull(key) }

    // ── 各卡类 payload 解析（键与 androidApp 读侧 shim 逐键对齐，冻结于 v5 格式） ──

    /** html_card 双形态元数据（metadata `html_card` 子对象；缺失 = 未声明 inline 待测高）。 */
    private fun parseHtmlCardMeta(metadata: String?): HtmlCardMeta {
        val sub = parseMetadata(metadata)?.optJSONObject("html_card") ?: return HtmlCardMeta()
        return HtmlCardMeta(
            display = sub.optStringOrNull("display"),
            // 未知 displayMode 枚举值静默回退 null（待重新判定），对齐 androidApp parseHtmlCardMeta
            displayMode = sub.optStringOrNull("displayMode")
                ?.let { name -> runCatching { HtmlCardDisplayMode.valueOf(name) }.getOrNull() },
            measuredHeightPx = sub.optIntOrNull("measuredHeightPx"),
            summary = sub.optStringOrNull("summary"),
        )
    }

    /**
     * task_card：metadata `engineer_task` 子对象 → [EngineerTaskState]。缺 taskId /
     * 整体结构损坏抛异常 → 行级 Text 兜底；其余字段缺失静默回退默认值（不丢卡），
     * 未知 status 回退 FAILED 安全终态（对齐 androidApp parseEngineerTaskState）。
     */
    private fun parseTaskCard(metadata: String?): MessagePart.TaskCard {
        val root = parseMetadata(metadata)?.optJSONObject("engineer_task")
            ?: throw IllegalArgumentException("task_card metadata missing engineer_task")
        val task = EngineerTaskState(
            taskId = root.optStringOrNull("taskId")
                ?: throw IllegalArgumentException("task_card metadata missing taskId"),
            sourceText = root.optStringOrNull("sourceText") ?: "",
            sid = root.optStringOrNull("sid"),
            status = root.optStringOrNull("status")
                ?.let { name -> runCatching { EngineerTaskStatus.valueOf(name) }.getOrDefault(EngineerTaskStatus.FAILED) }
                ?: EngineerTaskStatus.FAILED,
            stage = root.optStringOrNull("stage"),
            recentStages = root.optStringList("recentStages") ?: emptyList(),
            turns = root.optIntOrNull("turns") ?: 0,
            costCents = root.optIntOrNull("costCents"),
            startedAtMs = root.optLongOrNull("startedAtMs") ?: 0L,
            updatedAtMs = root.optLongOrNull("updatedAtMs") ?: 0L,
            fileChangeCount = root.optIntOrNull("fileChangeCount") ?: 0,
            truncatedReason = root.optStringOrNull("truncatedReason"),
            errorSummary = root.optStringOrNull("errorSummary"),
            resultSummary = root.optStringOrNull("resultSummary"),
            resolution = root.optStringOrNull("resolution")
                ?.let { name -> runCatching { EngineerTaskResolution.valueOf(name) }.getOrNull() },
            deliverBranch = root.optStringOrNull("deliverBranch"),
        )
        return MessagePart.TaskCard(
            partId = "p0",
            toolCallId = task.taskId,
            state = task.status.toToolPartState(),
            task = task,
        )
    }

    /**
     * optimize_candidates：metadata 整体即 [OptimizeCandidateGroup] JSON（必需字段
     * 缺失/损坏抛异常 → 行级 Text 兜底，键与 `OptimizeCandidateGroup.fromJson` 对齐）。
     */
    private fun parseOptimizeGroup(metadata: String?): OptimizeCandidateGroup {
        if (metadata.isNullOrBlank()) throw IllegalArgumentException("optimize_candidates metadata missing")
        val obj = JSONObject(metadata) // 必需字段缺失由下方严格取值抛出
        val arr = obj.getJSONArray("candidates")
        return OptimizeCandidateGroup(
            sourceImageUri = obj.getString("sourceImageUri"),
            scene = obj.optString("scene"),
            recommendedIndex = obj.optInt("recommendedIndex", -1),
            candidates = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                OptimizeCandidateGroup.Candidate(
                    direction = c.optString("direction"),
                    thumbPath = c.optString("thumbPath"),
                    nimaScore = if (c.has("nimaScore") && !c.isNull("nimaScore")) c.getDouble("nimaScore").toFloat() else null,
                    rejected = c.optBoolean("rejected", false),
                )
            },
            usedFingerprints = obj.optJSONArray("usedFingerprints")
                ?.let { fps -> (0 until fps.length()).map { fps.optString(it) } }
                ?: emptyList(),
            drawIndex = obj.optInt("drawIndex", 1),
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        runCatching { if (has(key) && !isNull(key)) getInt(key) else null }.getOrNull()

    private fun JSONObject.optLongOrNull(key: String): Long? =
        runCatching { if (has(key) && !isNull(key)) getLong(key) else null }.getOrNull()
}
