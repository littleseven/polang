package com.mamba.picme.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * iOS 聊天历史文件编解码器（`chat_history_<sessionId>.json` 整包线格式，ADR-016 M5 B1）。
 *
 * 与 Android [MessagePartsCodec] 共用同一 parts 嵌套数组线格式（鉴别值 = 8 值分类法
 * `type` taxonomy spec §1），但落点不同：iOS 是整文件 JSON 而非 SQLite 行，无
 * `partsJson` 字符串列 / metadata blob 必要——采用**结构化平铺字段 + parts 数组双
 * payload**（平铺字段 = 旧 SwiftUI 渲染器的直读源，切换期等价性的锚；parts = M5
 * 拍平渲染的 SSOT）。
 *
 * - **encode**：`ChatMessageType` 11 值 UI 枚举 → wire 8 值分类法反向映射（UI 枚举
 *   含 role 前缀变体，role 已独立列承载）；平铺字段缺失时自 parts 补齐，保证文件
 *   内双 payload 一致；
 * - **decode**：parts-first 投影（对齐 androidApp toUiModel M4 口径）——primary part
 *   定 [ChatMessageType]、平铺字段以 parts 提取优先；parts 数组整体缺失时自结构化
 *   平铺字段重建（belt-and-braces，语义对齐 [MessagePartsConverter] 但数据源为结构
 *   化字段而非 metadata blob）；重建失败回原文 Text 兜底，**不允许丢消息**；
 * - **不持久化**：performance / claudeAgent / claudeDeliver / isStreaming / showCursor /
 *   isThinking（瞬态，对齐 Android Room 行不落库）；gachaInteractive（计算值，对齐
 *   Android `controller.hasPending(id)` 运行时查询语义，防 restart 后死交互条）。
 */
object ChatHistoryStoreCodec {

    private val json = Json {
        // 前向兼容：新版本字段旧版本解码时忽略
        ignoreUnknownKeys = true
        // 紧凑落盘：缺省值不写出（decode 回填默认值，round-trip 等值）
        encodeDefaults = false
    }

    private val listSerializer = ListSerializer(ChatMessageDto.serializer())

    /** 整包编码：消息列表 → JSON 文件字符串（写侧，Swift `ChatHistoryStore.persist` 消费）。 */
    fun encode(messages: List<ChatMessage>): String =
        json.encodeToString(listSerializer, messages.map { it.toDto() })

    /**
     * 整包解码：JSON 文件字符串 → 消息列表。null = 空文件/结构损坏，调用方按
     * legacy 格式兜底（Swift 侧 `LegacyChatMessage` 迁移），**不允许丢消息**。
     */
    fun decode(text: String?): List<ChatMessage>? {
        if (text.isNullOrBlank()) return null
        val dtos = runCatching { json.decodeFromString(listSerializer, text) }.getOrNull() ?: return null
        return dtos.map { it.toDomain() }
    }

    // ── wire DTO（ChatMessage 本体非 @Serializable——LlmPerformance 等瞬态载荷无
    //    线格式；持久化子集独立成 DTO，字段类型全部 @Serializable） ──

    @Serializable
    private data class ChatMessageDto(
        val id: String,
        /** wire 8 值分类法（type taxonomy spec §1），role 信息由 [role] 列承载。 */
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
        val mediaResults: MediaResultsUi? = null,
        val optimizeCandidates: OptimizeCandidateGroup? = null,
        val engineerTask: EngineerTaskState? = null,
        /** parts 嵌套数组（线格式同 [MessagePartsCodec]，鉴别值 = 8 值分类法）。 */
        val parts: List<MessagePart> = emptyList(),
    )

    // ── encode：domain → DTO ────────────────────────────────────

    private fun ChatMessage.toDto(): ChatMessageDto {
        val primary = parts.firstOrNull()
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
            mediaResults = mediaResults ?: (primary as? MessagePart.MediaResults)?.results,
            optimizeCandidates = optimizeCandidates ?: (primary as? MessagePart.OptimizeCandidates)?.group,
            engineerTask = engineerTask ?: (primary as? MessagePart.TaskCard)?.task,
            parts = parts,
        )
    }

    /** ChatMessageType 11 值 UI 枚举 → wire 8 值分类法（role 变体归并，role 列承载）。 */
    private fun wireTypeOf(type: ChatMessageType): String = when (type) {
        ChatMessageType.USER_TEXT, ChatMessageType.AGENT_TEXT -> "text"
        ChatMessageType.USER_IMAGE, ChatMessageType.USER_IMAGE_TEXT, ChatMessageType.AGENT_IMAGE -> "image"
        ChatMessageType.CHART -> "tool_chart"
        ChatMessageType.HTML_CARD -> "tool_html"
        ChatMessageType.TASK_CARD -> "tool_task"
        ChatMessageType.AGENT_EDIT_RESULT -> "tool_image_edit"
        ChatMessageType.MEDIA_RESULTS -> "data_media_results"
        ChatMessageType.OPTIMIZE_CANDIDATES -> "data_optimize_candidates"
    }

    // ── decode：DTO → domain（parts-first 投影，对齐 androidApp toUiModel M4） ──

    private fun ChatMessageDto.toDomain(): ChatMessage {
        val role = roleOf(role)
        val parts = partsOrRebuild(role)
        val primary = parts.firstOrNull()
        return ChatMessage(
            id = id,
            type = uiTypeOf(primary, role, parts),
            role = role,
            content = content,
            modelUsed = modelUsed,
            timestamp = timestamp,
            mediaResults = (primary as? MessagePart.MediaResults)?.results ?: mediaResults,
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

    /** primary part → UI 枚举（对齐 androidApp toUiModel 的 parts-first 判型）。 */
    private fun uiTypeOf(
        primary: MessagePart?,
        role: ModelInputRole,
        parts: List<MessagePart>,
    ): ChatMessageType = when (primary) {
        is MessagePart.Text ->
            if (role == ModelInputRole.USER) ChatMessageType.USER_TEXT else ChatMessageType.AGENT_TEXT
        is MessagePart.Image -> when {
            role == ModelInputRole.USER && parts.any { it is MessagePart.Text } -> ChatMessageType.USER_IMAGE_TEXT
            role == ModelInputRole.USER -> ChatMessageType.USER_IMAGE
            else -> ChatMessageType.AGENT_IMAGE
        }
        is MessagePart.Chart -> ChatMessageType.CHART
        is MessagePart.HtmlCard -> ChatMessageType.HTML_CARD
        is MessagePart.TaskCard -> ChatMessageType.TASK_CARD
        is MessagePart.EditResult -> ChatMessageType.AGENT_EDIT_RESULT
        is MessagePart.MediaResults -> ChatMessageType.MEDIA_RESULTS
        is MessagePart.OptimizeCandidates -> ChatMessageType.OPTIMIZE_CANDIDATES
        null -> if (role == ModelInputRole.USER) ChatMessageType.USER_TEXT else ChatMessageType.AGENT_TEXT
    }

    /**
     * parts 数组在场直用；缺失时自结构化平铺字段重建（belt-and-braces）；重建失败
     * 回原文 Text 兜底（行级语义对齐 [MessagePartsConverter.toParts]，不丢消息）。
     */
    private fun ChatMessageDto.partsOrRebuild(role: ModelInputRole): List<MessagePart> {
        if (parts.isNotEmpty()) return parts
        return runCatching { rebuildParts(role) }
            .getOrElse { fallbackText(content) }
            .ifEmpty { fallbackText(content) }
    }

    private fun fallbackText(content: String): List<MessagePart> =
        listOf(MessagePart.Text(partId = "p0", markdown = content, state = PartState.DONE))

    @Suppress("CyclomaticComplexMethod") // 8 wire 值全枚举映射表，单点收口
    private fun ChatMessageDto.rebuildParts(role: ModelInputRole): List<MessagePart> = when (type) {
        "text" -> listOf(MessagePart.Text("p0", content, PartState.DONE))

        // image 按角色分流（语义对齐 MessagePartsConverter，数据源为结构化字段）
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

        "data_media_results" -> listOf(
            MessagePart.MediaResults(
                "p0",
                results = mediaResults
                    ?: throw IllegalArgumentException("data_media_results rebuild missing mediaResults"),
            ),
        )

        "data_optimize_candidates" -> listOf(
            MessagePart.OptimizeCandidates(
                "p0",
                group = optimizeCandidates
                    ?: throw IllegalArgumentException("data_optimize_candidates rebuild missing group"),
            ),
        )

        else -> fallbackText(content)
    }
}
