package com.mamba.picme.domain.chat

import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.context.MediaAsset

/**
 * 聊天消息 UI 数据类（SSOT，1:1 对齐 Android `ChatMessageUi`）。
 *
 * 本文件是 Chat 消息模型的 commonMain 权威定义，双端共享。org.json 序列化
 * （[ClaudeAgentState] / [OptimizeCandidateGroup] 的 toJson/fromJson）不在此——
 * 它们是平台关注点，由 androidApp 扩展函数提供（Room metadata 边界）。
 *
 * iOS 当前用 Swift 原生 `ChatMessage`（未消费本类型）；本下沉为后续 KMP 整理铺路。
 */
data class ChatMessage(
    val id: String,
    val type: ChatMessageType,
    val content: String,
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
)

/**
 * 相册搜索结果 carousel 的 UI 数据。
 * assets 已截到展示上限；totalCount 为全量命中数。
 */
data class MediaResultsUi(
    val query: String,
    val assets: List<MediaAsset>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val feedbackState: Map<String, FeedbackAction> = emptyMap()
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
 */
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
    data class Candidate(
        val direction: String,
        val thumbPath: String,
        val nimaScore: Float?,
        val rejected: Boolean
    )

    companion object {
        const val MESSAGE_TYPE = "optimize_candidates"
    }
}
