package com.mamba.picme.domain.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat 消息内容块（ADR-016 D1，对齐 Vercel AI SDK parts 模型）。
 *
 * 一条回复 = 一条消息 = 有序 parts 数组（**数组顺序即锚点**，禁用文内锚点占位符）。
 * 块级 [partId] 是流式三段式（start/delta/end）与 UI LazyColumn key（`messageId:partId`）的锚。
 *
 * 不可变性约定（spec §3）：全部子类型为纯 data class（val -only，List 构造点以 listOf 收口），
 * DONE 后的 part 不可变，原位更新仅允许 [TaskCard.state] 与 data part 的同 id 覆写。
 * `@Immutable` 注解不进 commonMain（ADR-013 纯度守卫禁 androidx.compose import），
 * Compose 稳定性注解属 M4 性能清单的 androidApp 侧收口。
 *
 * 序列化：kotlinx JSON（[MessagePartsCodec]），`type` 鉴别字段值与 legacy Room `type` 列对齐，
 * `ignoreUnknownKeys` 保证前向兼容（新版本 part 字段旧版本可读）。
 */
@Serializable
sealed interface MessagePart {

    /** 块级 id：消息内唯一（M1 legacy 迁移用 `p0`/`p1`… 序号；M2 流式起为真实 chunk id）。 */
    val partId: String

    /** 正文段（markdown）。一条回复可有多个 Text part（卡片间交错）。 */
    @Serializable
    @SerialName("text")
    data class Text(
        override val partId: String,
        val markdown: String,
        val state: PartState = PartState.DONE,
    ) : MessagePart

    /** draw_chart 的渲染投影（端侧生成的 SVG 字符串）。 */
    @Serializable
    @SerialName("chart")
    data class Chart(
        override val partId: String,
        val svg: String,
    ) : MessagePart

    /** render_html 的渲染投影；display 声明/终判/测高随 [meta] 随迁（形态不跳变）。 */
    @Serializable
    @SerialName("html_card")
    data class HtmlCard(
        override val partId: String,
        val html: String,
        val meta: HtmlCardMeta = HtmlCardMeta(),
    ) : MessagePart

    /**
     * 工具调用状态机投影（工程师任务卡）。
     * [toolCallId] 关联 tool-call/tool-result 语义对；M1 legacy 迁移期等于 [EngineerTaskState.taskId]。
     */
    @Serializable
    @SerialName("task_card")
    data class TaskCard(
        override val partId: String,
        val toolCallId: String,
        val state: ToolPartState,
        val task: EngineerTaskState,
    ) : MessagePart

    /** 相册搜索结果 carousel（data part：默认不回灌 LLM）。 */
    @Serializable
    @SerialName("media_results")
    data class MediaResults(
        override val partId: String,
        val results: MediaResultsUi,
    ) : MessagePart

    /** 图片块（user_image / agent_image / user_image_text 的图）。媒体红线：原生 block，不经 LLM 排版。 */
    @Serializable
    @SerialName("image")
    data class Image(
        override val partId: String,
        val ref: String,
        /** agent 产物图是否已保存到相册（user 图恒 false）。 */
        val saved: Boolean = false,
    ) : MessagePart

    /** 对话式图片编辑结果（agent_edit_result）。 */
    @Serializable
    @SerialName("edit_result")
    data class EditResult(
        override val partId: String,
        /** 编辑结果图 URI；metadata 缺失时为 null（UI 落 legacy 字段兜底）。 */
        val ref: String? = null,
        val description: String,
        val suggestions: List<String> = emptyList(),
        val saved: Boolean = false,
    ) : MessagePart

    /** AI 优化抽卡候选条（data part：默认不回灌 LLM）。 */
    @Serializable
    @SerialName("optimize_candidates")
    data class OptimizeCandidates(
        override val partId: String,
        val group: OptimizeCandidateGroup,
    ) : MessagePart
}

/** 正文段流式状态（spec §3）。 */
enum class PartState { STREAMING, DONE }

/**
 * 工具调用状态机（spec §5.1，对齐 Vercel v5/v6 四态 + 审批态）。
 * M1 仅作持久化枚举落地；流式迁移语义（INPUT_STREAMING 等中间态驱动）属 M2。
 */
enum class ToolPartState {
    INPUT_STREAMING,
    INPUT_AVAILABLE,
    OUTPUT_AVAILABLE,
    OUTPUT_ERROR,
    APPROVAL_REQUESTED,
    APPROVAL_RESPONDED,
    OUTPUT_DENIED,
}

/** 工程师任务卡五态 → 工具状态机渲染投影（spec §5.2 映射表）。 */
fun EngineerTaskStatus.toToolPartState(): ToolPartState = when (this) {
    EngineerTaskStatus.RUNNING -> ToolPartState.INPUT_AVAILABLE
    EngineerTaskStatus.AWAITING_CONTINUE,
    EngineerTaskStatus.AWAITING_DELIVER,
    -> ToolPartState.APPROVAL_REQUESTED
    EngineerTaskStatus.COMPLETED -> ToolPartState.OUTPUT_AVAILABLE
    EngineerTaskStatus.FAILED -> ToolPartState.OUTPUT_ERROR
}
