package com.mamba.picme.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * iOS 聊天历史文件编解码器（`chat_history_<sessionId>.json` 整包线格式，ADR-016 M5 B1）。
 *
 * 与 Android [MessagePartsCodec] 共用同一 parts 嵌套数组线格式（鉴别值 = 13 值终态
 * 分类法，spec《chat-value-domain-ideal》§3），但落点不同：iOS 是整文件 JSON 而非
 * SQLite 行，无 `partsJson` 字符串列 / metadata blob 必要——采用**结构化平铺字段 +
 * parts 数组双 payload**（平铺字段 = 旧 SwiftUI 渲染器的直读源，切换期等价性的锚；
 * parts = M5 拍平渲染的 SSOT）。文件顶层带 **envelope 版本信封**
 * （`{"version":6,"messages":[...]}`，2026-11-08 值域终态）。
 *
 * - **encode**：`ChatMessageType` 9 值 kind 枚举 → wire 分类法反向映射（role 变体
 *   已归并——角色由 role 独立列承载，IMAGE_TEXT 归并 wire `image`）；平铺字段缺失时
 *   自 parts 补齐，保证文件内双 payload 一致；恒写当前 [FILE_VERSION]；
 * - **decode**：`version` 缺失（envelope 前的裸数组格式 / legacy Swift Codable 格式）
 *   或 < [FILE_VERSION] → **整体忽略**（返回 null，调用方落空列表——与 Android
 *   Room v28 聊天表重置语义对齐：老历史文件属已批准的可重置范围，无迁移路径）；
 *   当前版本内 parts-first 投影（对齐 androidApp toUiModel M4 口径）——primary part
 *   定 [ChatMessageType]、平铺字段以 parts 提取优先；parts 数组整体缺失时自结构化
 *   平铺字段重建（belt-and-braces，重建口径对齐 Android 备份 v5 粗映射物化器（BackupChatRestoreMapper）——含
 *   `tool_browser`/`data_genui_program` 整颗 part JSON 直通分支，数据源为结构化
 *   字段而非 metadata blob）；重建失败回原文 Text 兜底，**不允许丢消息**；
 * - **不持久化**：performance / claudeAgent / claudeDeliver / isStreaming / showCursor /
 *   isThinking（瞬态，对齐 Android Room 行不落库）；gachaInteractive（计算值，对齐
 *   Android `controller.hasPending(id)` 运行时查询语义，防 restart 后死交互条）。
 */
object ChatHistoryStoreCodec {

    /** 历史文件线格式版本（envelope 信封，2026-11-08 值域终态）：低于本版的文件整体忽略。 */
    const val FILE_VERSION = 6

    private val json = Json {
        // 前向兼容：新版本字段旧版本解码时忽略
        ignoreUnknownKeys = true
        // 紧凑落盘：缺省值不写出（decode 回填默认值，round-trip 等值）
        encodeDefaults = false
    }

    /** 整包编码：消息列表 → envelope v6 文件字符串（写侧，Swift `ChatHistoryStore.saveMessages` 消费）。 */
    fun encode(messages: List<ChatMessage>): String =
        json.encodeToString(
            ChatHistoryFileEnvelope.serializer(),
            ChatHistoryFileEnvelope(version = FILE_VERSION, messages = messages.map { it.toDto() }),
        )

    /**
     * 整包解码：envelope 文件字符串 → 消息列表。null = 忽略——空文件 / 结构损坏 /
     * `version` 缺失（envelope 前的裸数组格式、legacy Swift Codable 格式解析不出信封）
     * 或 < [FILE_VERSION]，调用方返回空列表（与 Android 聊天表重置语义对齐，不迁移）。
     * 当前版本内行级容错（未知 part 原位 Tombstone、损坏行降级 Text）保证不丢消息。
     */
    fun decode(text: String?): List<ChatMessage>? {
        if (text.isNullOrBlank()) return null
        val envelope = runCatching {
            json.decodeFromString(ChatHistoryFileEnvelope.serializer(), text)
        }.getOrNull() ?: return null
        if (envelope.version < FILE_VERSION) return null
        return envelope.messages.map { it.toDomain() }
    }

    // ── wire DTO（ChatMessage 本体非 @Serializable——LlmPerformance 等瞬态载荷无
    //    线格式；持久化子集独立成 DTO，字段类型全部 @Serializable） ──

    /** 文件顶层信封：版本门禁 + 消息数组（v6 起取代裸数组落盘）。 */
    @Serializable
    private data class ChatHistoryFileEnvelope(
        val version: Int,
        val messages: List<ChatMessageDto>,
    )

    @Serializable
    private data class ChatMessageDto(
        val id: String,
        /** wire 9 值分类法（type taxonomy spec §1），role 信息由 [role] 列承载。 */
        val type: String,
        /** "user" / "agent"（[roleOf] 的反向值域）。 */
        val role: String,
        val content: String,
        val timestamp: Long,
        val modelUsed: String? = null,
        val imageUri: String? = null,
        val chartSvg: String? = null,
        val htmlContent: String? = null,
        val htmlCardMeta: HtmlCardMeta? = null,
        val imageSaved: Boolean = false,
        val optimizeCandidates: OptimizeCandidateGroup? = null,
        val engineerTask: EngineerTaskState? = null,
        /**
         * parts 嵌套数组（线格式同 [MessagePartsCodec]，鉴别值 = 13 值终态——12 正式值 +
         * Tombstone 兜底）。以 [JsonElement] 承载、逐元素
         * [MessagePartsCodec.decodePartOrTombstone] 解码（2026-11-08 Tombstone 兜底）：
         * 未知/退役 part 类型原位落 Tombstone 占位，不再整包 decode 失败丢历史。
         */
        val parts: List<JsonElement> = emptyList(),
    )

    // ── encode：domain → DTO ────────────────────────────────────

    private fun ChatMessage.toDto(): ChatMessageDto {
        // 判型锚单点立法：排除 reasoning/source/tombstone（与写侧 projectChatRow、
        // androidApp toUiModel 同源），平铺字段补齐同样不取伴随载荷 part
        val primary = primaryPartOf(parts)
        return ChatMessageDto(
            id = id,
            type = wireTypeOf(type),
            role = if (role == ModelInputRole.USER) "user" else "agent",
            content = content,
            timestamp = timestamp,
            modelUsed = modelUsed,
            // 平铺字段缺失时自 parts 补齐（写路径直构 parts、平铺字段漏填的等价性锚）
            imageUri = imageUri
                ?: (primary as? MessagePart.Image)?.ref
                ?: (primary as? MessagePart.EditResult)?.ref,
            chartSvg = chartSvg ?: (primary as? MessagePart.Chart)?.svg,
            htmlContent = htmlContent ?: (primary as? MessagePart.HtmlCard)?.html,
            htmlCardMeta = htmlCardMeta ?: (primary as? MessagePart.HtmlCard)?.meta,
            imageSaved = imageSaved || when (primary) {
                is MessagePart.Image -> primary.saved
                is MessagePart.EditResult -> primary.saved
                else -> false
            },
            optimizeCandidates = optimizeCandidates ?: (primary as? MessagePart.OptimizeCandidates)?.group,
            engineerTask = engineerTask ?: (primary as? MessagePart.TaskCard)?.task,
            parts = parts.map { json.encodeToJsonElement(MessagePart.serializer(), it) },
        )
    }

    /** ChatMessageType 9 值 kind 枚举 → wire 分类法（role 变体归并，role 列承载；IMAGE_TEXT 归并 `image`）。 */
    private fun wireTypeOf(type: ChatMessageType): String = when (type) {
        ChatMessageType.TEXT -> "text"
        ChatMessageType.IMAGE, ChatMessageType.IMAGE_TEXT -> "image"
        ChatMessageType.CHART -> "tool_chart"
        ChatMessageType.HTML_CARD -> "tool_html"
        ChatMessageType.TASK_CARD -> "tool_task"
        ChatMessageType.EDIT_RESULT -> "tool_image_edit"
        ChatMessageType.OPTIMIZE_CANDIDATES -> "data_optimize_candidates"
        ChatMessageType.OPENUI_CARD -> "data_genui_program"
    }

    // ── decode：DTO → domain（parts-first 投影，对齐 androidApp toUiModel M4） ──

    private fun ChatMessageDto.toDomain(): ChatMessage {
        val role = roleOf(role)
        val parts = partsOrRebuild(role)
        // 判型锚单点立法（值域终态 🟡2）：uiTypeOf 取 primaryPartOf——[Reasoning, Chart]
        // 形态行判型落 CHART 而非 TEXT，与写侧投影零漂移
        val primary = primaryPartOf(parts)
        return ChatMessage(
            id = id,
            type = uiTypeOf(primary, role, parts),
            role = role,
            content = content,
            modelUsed = modelUsed,
            timestamp = timestamp,
            imageUri = (primary as? MessagePart.Image)?.ref
                ?: (primary as? MessagePart.EditResult)?.ref
                ?: imageUri,
            chartSvg = (primary as? MessagePart.Chart)?.svg ?: chartSvg,
            htmlContent = (primary as? MessagePart.HtmlCard)?.html ?: htmlContent,
            imageSaved = when (primary) {
                is MessagePart.Image -> primary.saved
                is MessagePart.EditResult -> primary.saved
                else -> imageSaved
            },
            htmlCardMeta = (primary as? MessagePart.HtmlCard)?.meta ?: htmlCardMeta,
            optimizeCandidates = (primary as? MessagePart.OptimizeCandidates)?.group ?: optimizeCandidates,
            engineerTask = (primary as? MessagePart.TaskCard)?.task ?: engineerTask,
            parts = parts,
        )
    }

    /** primary part → kind 枚举（对齐 androidApp toUiModel 的 parts-first 判型；role 仅用于 IMAGE_TEXT 分流）。 */
    private fun uiTypeOf(
        primary: MessagePart?,
        role: ModelInputRole,
        parts: List<MessagePart>,
    ): ChatMessageType = when (primary) {
        is MessagePart.Text -> ChatMessageType.TEXT
        is MessagePart.Image -> when {
            role == ModelInputRole.USER && parts.any { it is MessagePart.Text } -> ChatMessageType.IMAGE_TEXT
            else -> ChatMessageType.IMAGE
        }
        is MessagePart.Chart -> ChatMessageType.CHART
        is MessagePart.HtmlCard -> ChatMessageType.HTML_CARD
        is MessagePart.TaskCard -> ChatMessageType.TASK_CARD
        is MessagePart.EditResult -> ChatMessageType.EDIT_RESULT
        is MessagePart.OptimizeCandidates -> ChatMessageType.OPTIMIZE_CANDIDATES
        // OpenUI 卡 M1 Android-only（openui P1）：iOS 未落地，防御性落文本，正式映射随 iOS 跟随任务
        is MessagePart.OpenUiCard -> ChatMessageType.TEXT
        // iOS 浏览器直播卡未落地（M1 Android-only）：防御性落文本，正式映射随 iOS 跟随任务
        is MessagePart.BrowserLive -> ChatMessageType.TEXT
        // 值域终态三新 part（reasoning/source，2026-11-08）：均不主导消息 kind（reasoning
        // 折叠块 / source chips 由拍平 item 渲染），判型防御性落文本；iOS 渲染对等随后续
        // 任务落地（此间管线未产此类 part，分支不可达）
        is MessagePart.Reasoning,
        is MessagePart.SourceUrl,
        is MessagePart.SourceDocument,
        -> ChatMessageType.TEXT
        // 前向兼容/退役兜底（未知/损坏 part）：整消息判型防御性落文本；
        // 实际渲染走拍平 item（TYPE_TOMBSTONE 占位卡）
        is MessagePart.Tombstone -> ChatMessageType.TEXT
        null -> ChatMessageType.TEXT
    }

    /**
     * parts 数组在场直用（逐元素 tolerant 解码：未知/损坏/退役 part → Tombstone 占位，
     * 对齐 [MessagePartsCodec] 口径）；缺失时自结构化平铺字段重建（belt-and-braces）；
     * 重建失败回原文 Text 兜底（行级语义对齐 Android 侧行级兜底，不丢消息）。
     */
    private fun ChatMessageDto.partsOrRebuild(role: ModelInputRole): List<MessagePart> {
        if (parts.isNotEmpty()) {
            return parts.mapIndexed { index, element -> MessagePartsCodec.decodePartOrTombstone(element, index) }
        }
        return runCatching { rebuildParts(role) }
            .getOrElse { fallbackText(content) }
            .ifEmpty { fallbackText(content) }
    }

    private fun fallbackText(content: String): List<MessagePart> =
        listOf(MessagePart.Text(partId = "p0", markdown = content, state = PartState.DONE))

    @Suppress("CyclomaticComplexMethod") // wire 值全枚举映射表，单点收口
    private fun ChatMessageDto.rebuildParts(role: ModelInputRole): List<MessagePart> = when (type) {
        "text" -> listOf(MessagePart.Text("p0", content, PartState.DONE))

        // image 按角色分流（语义对齐投影规则，数据源为结构化字段）
        "image" -> when {
            role == ModelInputRole.USER && imageUri != null ->
                listOf(MessagePart.Image("p0", ref = imageUri!!), MessagePart.Text("p1", content, PartState.DONE))
            role == ModelInputRole.USER -> listOf(MessagePart.Image("p0", ref = content))
            else -> listOf(MessagePart.Image("p0", ref = imageUri ?: content, saved = imageSaved))
        }

        "tool_chart" -> listOf(MessagePart.Chart("p0", svg = chartSvg ?: content))

        "tool_html" -> listOf(
            MessagePart.HtmlCard("p0", html = htmlContent ?: content, meta = htmlCardMeta ?: HtmlCardMeta()),
        )

        // browser 直播卡 / OpenUI GenUI 卡：content 承载整颗 part JSON（口径对齐
        // Android 备份粗映射物化器——同两分支直通解码）；损坏行抛异常 →
        // partsOrRebuild runCatching 落原文 Text 兜底
        "tool_browser" ->
            listOf(json.decodeFromString(MessagePart.BrowserLive.serializer(), content))

        "data_genui_program" ->
            listOf(json.decodeFromString(MessagePart.OpenUiCard.serializer(), content))

        "tool_task" -> listOf(
            MessagePart.TaskCard(
                partId = "p0",
                toolCallId = engineerTask?.taskId
                    ?: throw IllegalArgumentException("tool_task rebuild missing engineerTask"),
                state = engineerTask!!.status.toToolPartState(),
                task = engineerTask!!,
            ),
        )

        "tool_image_edit" -> listOf(
            MessagePart.EditResult(
                "p0",
                ref = imageUri,
                description = content,
                suggestions = emptyList(), // suggestions 仅随 parts 数组持久化，重建路径不回填
                saved = imageSaved,
            ),
        )

        "data_optimize_candidates" -> listOf(
            MessagePart.OptimizeCandidates(
                "p0",
                group = optimizeCandidates
                    ?: throw IllegalArgumentException("data_optimize_candidates rebuild missing group"),
            ),
        )

        // 未知 wire type（前向兼容兜底，语义对齐 Android 备份粗映射的降级分支）：
        // content 为 JSON blob → Unsupported 占位卡，防 JSON 原文裸渲染；纯文本 → 保留 Text
        else -> listOf(unknownTypeFallback(type, content))
    }

    private fun unknownTypeFallback(type: String, content: String): MessagePart {
        val element = runCatching { json.parseToJsonElement(content) }.getOrNull()
        return when (element) {
            is JsonObject -> MessagePartsCodec.tombstonePartOf(
                element,
                (element["type"] as? JsonPrimitive)?.contentOrNull ?: type,
                index = 0,
            )
            is JsonArray -> MessagePart.Tombstone("p0", originalType = type)
            else -> MessagePart.Text("p0", content, PartState.DONE)
        }
    }
}
