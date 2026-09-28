package com.mamba.picme.domain.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * part 三分类（spec §1.2）：内容物 / 工具产物 / 数据载荷。
 * 由子类型携带（[MessagePart.category]），代码面永不解析字符串前缀——
 * `tool_`/`data_` 前缀只是线格式设计约定 + 测试锁定。
 */
enum class PartCategory {
    CONTENT,
    TOOL,
    DATA,
    ;

    /** 分类的线格式名（content/tool/data），与 type 值前缀约定对应（content 类无前缀）。 */
    val wireName: String get() = name.lowercase()
}

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
 * 序列化：kotlinx JSON（[MessagePartsCodec]），`type` 鉴别字段值遵循 8 值分类法
 * （type taxonomy spec §1），`ignoreUnknownKeys` 保证前向兼容（新版本 part 字段旧版本可读）。
 */
@Serializable
sealed interface MessagePart {

    /**
     * 块级 id：消息内唯一。M4 定稿的**分轨命名空间**方案（spec §3/§10）：
     * - 瞬态轨（流式 turn，TurnPartsReducer 合成）：`txt-N`（文本块）/ `call-N`（工具块，
     *   占位原位填充 partId 不变）；
     * - 持久轨（Room partsJson，MessagePartsConverter 合成）：`p0`/`p1`… 消息内序号。
     * 两轨不交叉：流式消息不落 Room，落库消息不经 reducer——同一条消息任一时刻只属于一轨，
     * 故各生命周期内 LazyColumn key（`messageId:partId`）恒定。流式→落库边界 messageId
     * 必然变更（一 turn 拆多行的持久模型使然，spec §11 不做消息树），该边界 item 重建与
     * M2 前基线行为一致（整条流式气泡本就被新行替换），不属 key 跳变回归。
     */
    val partId: String

    /**
     * part 三分类（[PartCategory]，spec §1.2）。子类 getter-only 覆盖，无后备字段 →
     * kotlinx.serialization 不序列化本属性，线格式零变化。
     */
    val category: PartCategory

    /** 正文段（markdown）。一条回复可有多个 Text part（卡片间交错）。 */
    @Serializable
    @SerialName("text")
    data class Text(
        override val partId: String,
        val markdown: String,
        val state: PartState = PartState.DONE,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.CONTENT
    }

    /**
     * draw_chart 的渲染投影（端侧生成的 SVG 字符串）。
     * [state] = 工具状态机（spec §5.1）：M2 流式占位 INPUT_STREAMING/INPUT_AVAILABLE →
     * 产出原位填充 OUTPUT_AVAILABLE / 失败 OUTPUT_ERROR；持久化卡恒 OUTPUT_AVAILABLE
     * （默认值——M1 存量 partsJson 行无此字段，解码落默认值，线格式兼容）。
     */
    @Serializable
    @SerialName("tool_chart")
    data class Chart(
        override val partId: String,
        val svg: String,
        val state: ToolPartState = ToolPartState.OUTPUT_AVAILABLE,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.TOOL
    }

    /**
     * render_html 的渲染投影；display 声明/终判/测高随 [meta] 随迁（形态不跳变）。
     * [state] 语义同 [Chart.state]。
     */
    @Serializable
    @SerialName("tool_html")
    data class HtmlCard(
        override val partId: String,
        val html: String,
        val meta: HtmlCardMeta = HtmlCardMeta(),
        val state: ToolPartState = ToolPartState.OUTPUT_AVAILABLE,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.TOOL
    }

    /**
     * 工具调用状态机投影（工程师任务卡）。
     * [toolCallId] 关联 tool-call/tool-result 语义对；M1 legacy 迁移期等于 [EngineerTaskState.taskId]。
     */
    @Serializable
    @SerialName("tool_task")
    data class TaskCard(
        override val partId: String,
        val toolCallId: String,
        val state: ToolPartState,
        val task: EngineerTaskState,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.TOOL
    }

    /** 相册搜索结果 carousel（data part：默认不回灌 LLM）。 */
    @Serializable
    @SerialName("data_media_results")
    data class MediaResults(
        override val partId: String,
        val results: MediaResultsUi,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.DATA
    }

    /** 图片块（user_image / agent_image / user_image_text 的图）。媒体红线：原生 block，不经 LLM 排版。 */
    @Serializable
    @SerialName("image")
    data class Image(
        override val partId: String,
        val ref: String,
        /** agent 产物图是否已保存到相册（user 图恒 false）。 */
        val saved: Boolean = false,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.CONTENT
    }

    /** 对话式图片编辑结果（tool_image_edit）。 */
    @Serializable
    @SerialName("tool_image_edit")
    data class EditResult(
        override val partId: String,
        /** 编辑结果图 URI；metadata 缺失时为 null（UI 落 legacy 字段兜底）。 */
        val ref: String? = null,
        val description: String,
        val suggestions: List<String> = emptyList(),
        val saved: Boolean = false,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.TOOL
    }

    /** AI 优化抽卡候选条（data part：默认不回灌 LLM）。 */
    @Serializable
    @SerialName("data_optimize_candidates")
    data class OptimizeCandidates(
        override val partId: String,
        val group: OptimizeCandidateGroup,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.DATA
    }
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
