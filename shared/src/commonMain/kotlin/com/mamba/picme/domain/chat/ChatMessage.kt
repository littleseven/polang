package com.mamba.picme.domain.chat

import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.context.MediaAsset
import kotlinx.serialization.Serializable

/**
 * 聊天消息 UI 数据类（SSOT，1:1 对齐 Android `ChatMessageUi`）。
 *
 * 本文件是 Chat 消息模型的 commonMain 权威定义，双端共享。org.json 序列化
 * （[ClaudeAgentState] / [OptimizeCandidateGroup] 的 toJson/fromJson）不在此——
 * 它们是平台关注点，由 androidApp 扩展函数提供（Room metadata 边界）。
 *
 * iOS 经 SharedKit 消费本类型（Swift `typealias ChatMessage = SharedKit.ChatMessage`，
 * M5 起渲染源 = parts 拍平列表；Swift 侧仅保留流式瞬态包装）。
 *
 * ADR-016（M1）：新增 [parts] 有序内容块数组（Vercel parts 模型），与 legacy 平铺字段
 * 双写共存；UI 渲染仍读 legacy 字段（M1 不动 UI），parts 供持久化（Room partsJson）与
 * LLM 回灌（[toModelInput]）消费。流式瞬态字段（isStreaming/showCursor/isThinking）不进入
 * parts（streaming 消息不落库，parts 恒空）。
 */
data class ChatMessage(
    val id: String,
    val type: ChatMessageType,
    val content: String,
    /**
     * 消息角色（spec §2：role 升格为独立消息级字段，不再自 type 前缀派生）。
     * 无默认值——调用点必须显式声明；持久化行经 [roleOf] 自 Room role 列（"user"/"agent"）
     * 解析，内存构造按消息来源直接给值。
     */
    val role: ModelInputRole,
    val modelUsed: String? = null,
    val timestamp: Long = nowEpochMillis(),
    val performance: LlmPerformance? = null,
    val mediaResults: MediaResultsUi? = null,
    /** 图文混排（USER_IMAGE_TEXT）时携带的图片 uri；其余类型为 null。 */
    val imageUri: String? = null,
    /** CHART 类型：端侧生成的 SVG 字符串。 */
    val chartSvg: String? = null,
    /** HTML_CARD 类型：自包含 HTML 字符串（已清洗，离线 WebView 渲染）。 */
    val htmlContent: String? = null,
    /** agent_image / agent_edit_result 是否已保存到相册。 */
    val imageSaved: Boolean = false,
    /** 流式输出中的瞬态消息（不落库）。 */
    val isStreaming: Boolean = false,
    /** 流式打字光标是否可见（节奏器驱动）。 */
    val showCursor: Boolean = false,
    /** 思考中（首 token 到达前）：UI 显示三点 typing indicator。 */
    val isThinking: Boolean = false,
    /** claude-tunnel agent 气泡状态（文本流式 + 步骤列表 + 文件改动）。 */
    val claudeAgent: ClaudeAgentState? = null,
    /** claude agent 气泡的交付动作；非空且 pending=true 时渲染「交付」按钮。 */
    val claudeDeliver: ClaudeDeliverUi? = null,
    /** 抽卡候选卡组负载（OPTIMIZE_CANDIDATES 消息）。 */
    val optimizeCandidates: OptimizeCandidateGroup? = null,
    /** TASK_CARD 载荷（工程师任务卡；状态存 Room metadata）。 */
    val engineerTask: EngineerTaskState? = null,
    /** 卡条是否可交互（controller 内存态仍有 pending；进程重建后降级只读）。 */
    val gachaInteractive: Boolean = false,
    /** HTML_CARD 双形态元数据（display 声明 / 端侧终判 displayMode / 测高 / summary；Room metadata `html_card` key）。 */
    val htmlCardMeta: HtmlCardMeta? = null,
    /**
     * 有序内容块数组（ADR-016 parts 模型，M1）。数组顺序即锚点。
     * 填充口径：持久化（Room partsJson 双写）与 LLM 回灌（实体侧 `toModelInputItems`）恒消费；
     * UI 模型侧——M2 起流式占位消息由 turn 装配器实时填充（瞬态内存轨），Room 读侧仅
     * 任务卡消息恢复填充（live 态 overlay 挂载点），其余类型仍留空（无消费方，省 decode 开销）；
     * M4 切渲染源时全量恢复。直接构造的瞬态消息默认空表。
     */
    val parts: List<MessagePart> = emptyList(),
)

/**
 * 相册搜索结果 carousel 的 UI 数据。
 * assets 已截到展示上限；totalCount 为全量命中数。
 */
@Serializable
data class MediaResultsUi(
    val query: String,
    val assets: List<MediaAsset>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val feedbackState: Map<String, FeedbackAction> = emptyMap()
)

/** HTML 卡展示形态终判（端侧；spec §4 分流判定结果，随消息 metadata 持久化）。 */
enum class HtmlCardDisplayMode { INLINE, FULLPAGE }

/**
 * HTML_CARD 消息的双形态元数据（Room metadata `html_card` key；org.json serde 在 androidApp 扩展）。
 *
 * 纯数据，不含平台依赖；判定逻辑见 androidApp `HtmlCardDisplay`。
 */
@Serializable
data class HtmlCardMeta(
    /** LLM 经 render_html `display` 参数的声明原值（"inline"/"fullpage"）；null = 未声明（按 inline 处理）。 */
    val display: String? = null,
    /** 端侧终判形态；null = 尚未判定（待测高）。 */
    val displayMode: HtmlCardDisplayMode? = null,
    /** 终判时的内容测高（CSS px ≈ dp）；声明 fullpage 直判时可为 null（未测高）。 */
    val measuredHeightPx: Int? = null,
    /** LLM 给的一句话摘要：全屏查看器标题 / 渲染失败兜底封面文案。 */
    val summary: String? = null,
)

/** 本地/远程 LLM 性能指标（展示用）。 */
data class LlmPerformance(
    val promptLen: Long,
    val decodeLen: Long,
    val prefillTimeMs: Long,
    val decodeTimeMs: Long,
    val prefillSpeed: Float,
    val decodeSpeed: Float,
    val usedSandbox: Boolean = false
)

/**
 * claude-tunnel agent 气泡的可变状态（事件折叠产物）。
 * 纯数据；toJson/fromJson（org.json）在 androidApp 扩展。
 */
data class ClaudeAgentState(
    val text: String = "",
    val steps: List<ClaudeStepUi> = emptyList(),
    val hasFileChange: Boolean = false,
    val truncatedReason: String? = null,
) {
    companion object
}

/** agent 气泡里的一步（工具调用 / 文件改动）的状态。 */
data class ClaudeStepUi(
    val tool: String,
    val status: ClaudeStepStatus,
    val detail: String,
)

enum class ClaudeStepStatus { RUNNING, SUCCESS, FAILED }

/** claude 交付按钮状态。pending=true 显示按钮；交付完成后置 false。 */
data class ClaudeDeliverUi(val sid: String, val pending: Boolean)

/**
 * chat 抽卡候选卡组消息负载（OPTIMIZE_CANDIDATES 消息的 metadata）。
 * 纯数据；toJson/fromJson（org.json）在 androidApp 扩展。
 * `@Serializable` 供 parts_json（kotlinx）落库；与 org.json 线格式互不干扰。
 */
@Serializable
data class OptimizeCandidateGroup(
    val sourceImageUri: String,
    val scene: String,
    /** NIMA 最优卡 index；-1 = KeepOriginal 不预选。 */
    val recommendedIndex: Int,
    val candidates: List<Candidate>,
    /** 「换一组」回传 exclude 的去重指纹。 */
    val usedFingerprints: List<String>,
    /** 第几组（换一组 +1）。 */
    val drawIndex: Int
) {
    /**
     * 单张候选卡的展示数据。
     * - [thumbPath] 候选图路径；空串 = 落盘失败（UI 占位）。
     * - [nimaScore] NIMA 美学分；null = 未评分。
     */
    @Serializable
    data class Candidate(
        val direction: String,
        val thumbPath: String,
        // 默认值供 kotlinx 解码兜底：org.json 线格式在 nimaScore 为 null 时缺省该键
        val nimaScore: Float? = null,
        val rejected: Boolean = false,
    )

    companion object {
        const val MESSAGE_TYPE = "data_optimize_candidates"
    }
}
