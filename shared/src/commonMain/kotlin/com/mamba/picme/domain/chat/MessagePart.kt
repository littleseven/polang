package com.mamba.picme.domain.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * part 五分类（值域终态，spec《chat-value-domain-ideal》§3）：内容物 / 思考链 /
 * 工具产物 / 数据载荷 / 引用来源。
 * 由子类型携带（[MessagePart.category]），代码面永不解析字符串前缀——
 * `tool_`/`data_`/`source_` 前缀只是线格式设计约定 + 测试锁定。
 */
enum class PartCategory {
    CONTENT,
    REASONING,
    TOOL,
    DATA,
    SOURCE,
    ;

    /** 分类的线格式名（content/reasoning/tool/data/source），与 type 值前缀约定对应（content/reasoning 类无前缀）。 */
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
 * 序列化：kotlinx JSON（[MessagePartsCodec]），`type` 鉴别字段值遵循「4+1 分类 13 值」
 * 终态（spec《chat-value-domain-ideal》§3）：content（text/image）+ reasoning +
 * tool_（tool_chart/tool_html/tool_task/tool_image_edit/tool_browser）+ data_
 * （data_optimize_candidates/data_genui_program）+ source_（source_url/source_document）
 * 五分类，另含 tombstone 兜底值（归类 DATA）——未知/退役鉴别值解码原位落 [Tombstone]
 * 占位（part 级前向兼容）；`ignoreUnknownKeys` 保证字段级前向兼容（新版本 part 字段旧版本可读）。
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
     * part 五分类（[PartCategory]，值域终态 spec §3）。子类 getter-only 覆盖，无后备字段 →
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
     * 思考链文本（一等 part，2026-11-08 值域终态新增——对齐 OpenAI Responses
     * `reasoning` item / Vercel `reasoning` part）。可折叠渲染；回灌剥离
     * （[ChatModelInput] 丢弃规则）；生产开关默认关（extended thinking 成本/延迟
     * 属产品决策，本值域只落类型与编解码）。[state] 沿 [Text] 两态流式口径；
     * 排序约定：reasoning 排在 text 前。partId 分轨约定同 [partId]。
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        override val partId: String,
        val text: String,
        val state: PartState = PartState.DONE,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.REASONING
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

    /**
     * OpenUI GenUI 卡（data part：默认不回灌 LLM，openui P1）。
     *
     * 鉴别值 `data_genui_program`——自 `data_ui_openui` 改名（2026-11-08 值域终态，
     * 命名规约第 2 条「禁嵌实现技术名」首次执行：OpenUI 是 GenUI 范式的技术实现，
     * kind 用范式名 `genui`；聊天表随 v28 重置，改名零迁移成本）。
     *
     * [program] 为 OpenUI Lang 1.0 程序原文（骨架 + Query 绑定，无数据），
     * 数据经 Query("local_handle") 端上补全（隐私闸对偶 D2/D6）。
     * [fallbackSummary] 为投影级降级摘要（云端可持有）。
     * [viewState] 为交互态（"selected=<label>"，同 part id 覆写持久化，对偶 US-7）。
     */
    @Serializable
    @SerialName("data_genui_program")
    data class OpenUiCard(
        override val partId: String,
        val program: String,
        val fallbackSummary: String,
        val viewState: String? = null,
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

    /**
     * 云端浏览器直播卡（tool_browser，spec: browser-vnc 直播卡 §4）。
     * 瞬态轨：browser_open 占位 → 会话期间经 BrowserLiveOverlay 原位覆写（帧/动作流水）；
     * 持久轨：会话结束落最终帧 + 结果摘要 + 动作计数（state 恒 OUTPUT_AVAILABLE /
     * 失败 OUTPUT_ERROR）。帧永不回灌 LLM（回灌投影见 ChatModelInput）。
     */
    @Serializable
    @SerialName("tool_browser")
    data class BrowserLive(
        override val partId: String,
        val sessionId: String,
        val state: ToolPartState = ToolPartState.INPUT_STREAMING,
        val currentUrl: String = "",
        val pageTitle: String = "",
        val frameJpegBase64: String? = null,
        /** 最近动作流水（至多 3 条，新在尾）。 */
        val actions: List<BrowserActionEntry> = emptyList(),
        val actionCount: Int = 0,
        val resultSummary: String? = null,
        val errorReason: String? = null,
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

    /**
     * web_search 引用来源（source part，2026-11-08 值域终态新增——对齐 Vercel
     * `source-url`）。引擎层自搜索代理结果抽取（≤5 条）挂当轮 assistant 消息；
     * 渲染为引用 chips 行（序号 + 域名 + 落地页）；回灌剥离。正文 markdown 图链
     * 行为不变（图=配图，chip=来源列表，职责不同）。
     */
    @Serializable
    @SerialName("source_url")
    data class SourceUrl(
        override val partId: String,
        val sourceId: String,
        val url: String,
        val title: String? = null,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.SOURCE
    }

    /**
     * 文档引用来源（source part，2026-11-08 值域终态预留——对齐 Vercel
     * `source-document`，暂无生产者；命名规约先行占位）。
     */
    @Serializable
    @SerialName("source_document")
    data class SourceDocument(
        override val partId: String,
        val sourceId: String,
        val mediaType: String,
        val title: String,
        val filename: String? = null,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.SOURCE
    }

    /**
     * 墓碑占位 part（2026-11-08 值域终态转正改名：前名 `Unsupported` / 鉴别值
     * `unsupported`）：**前向兼容 + 退役 part 的可读占位——读时投影，永不出现于
     * 生产写路径**。
     *
     * 两类场景解码原位落本 part：① 前向兼容——旧版本读到新版本写入的未知 part 类型
     * （或已知类型字段损坏），块序保住、其余 part 正常渲染、UI 落「此内容需新版本
     * App 查看」默认卡（ADR-016 降级口径的广义化：不丢消息、不崩、可感知）；② 退役
     * 占位——已出清值域的 legacy 鉴别值（如 A2UI `data_ui`）不再单值显式拦截，
     * 解码失败统一走本兜底。本 part 读时投影、改名零数据成本。
     *
     * [originalType] 为原始鉴别值（如未来的 `tool_video_edit` 或退役的 `data_ui`），
     * 仅作调试/日志；[summary] 在 payload 携带可读摘要字段（fallbackSummary/summary/
     * text/markdown）时提升。分类归 DATA：不回灌 LLM（与 data part 默认口径一致）。
     *
     * 注意：本 part 是读侧合成物，原始 payload 始终保留在 partsJson 原文中，
     * 升级新版本后同一行自动恢复为正式卡片（解码路径幂等）。
     */
    @Serializable
    @SerialName("tombstone")
    data class Tombstone(
        override val partId: String,
        val originalType: String,
        val summary: String? = null,
    ) : MessagePart {
        override val category: PartCategory get() = PartCategory.DATA
    }
}

/** 直播卡动作流水条目（description 已本地化——由 UI 侧组装，模型只搬运）。 */
@Serializable
data class BrowserActionEntry(
    val description: String,
    val ok: Boolean = true,
)

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
