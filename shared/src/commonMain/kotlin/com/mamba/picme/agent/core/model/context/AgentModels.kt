package com.mamba.picme.agent.core.model.context

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import kotlin.time.Clock

/**
 * Agent 运行上下文
 *
 * 描述当前用户所处的场景和设备状态，用于构建 system prompt 和路由命令。
 *
 * @property scene 当前场景
 * @property beautySettings 当前美颜设置
 * @property filterType 当前滤镜
 * @property styleFilter 当前风格
 * @property zoomRatio 当前变焦
 * @property exposureCompensation 当前曝光
 * @property captureMode 当前拍摄模式
 * @property isRecording 是否正在录制
 * @property memorySessionId 记忆会话 ID（用于隔离不同场景的对话历史）
 */
data class AgentContext(
    val scene: AgentScene,
    val beautySettings: BeautySettings = BeautySettings(),
    val filterType: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val zoomRatio: Float = 1f,
    val exposureCompensation: Int = 0,
    val captureMode: MediaType = MediaType.PHOTO,
    val isRecording: Boolean = false,
    val memorySessionId: String = scene.name.lowercase(),
    val recentSearchResults: List<SearchResultSnapshot> = emptyList(),
    /** 最近一条用户图片消息的持久化路径，供模型指代「这张照片」 */
    val lastUserImageUri: String? = null,
    /** 当前相册摘要，供 LLM 回答「有多少照片/人脸/是否需扫描」等问题 */
    val gallerySummary: GallerySummary? = null,
    /** 一次用户消息的关联 ID，贯穿该轮 LLM/tool/JS 三层日志；非 chat 来源为 null。 */
    val traceId: String? = null,
    /** Chat 助手性格预设；非 chat 调用方用默认值（不注入性格段，行为不变）。 */
    val persona: AssistantPersona = AssistantPersona.DEFAULT,
    /** Chat 回复语言（已解析）；非 chat 调用方用默认值。 */
    val replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE
)

/**
 * 最近搜索结果快照，用于 LLM 解析跨轮指代。
 *
 * @property query 该轮搜索的自然语言查询
 * @property results 展示给用户的媒体项（含 tags，用于自然语言指代）
 * @property totalCount 命中总数
 * @property isRefinement 是否为上轮结果的细化
 * @property timestamp 快照时间戳
 */
data class SearchResultSnapshot(
    val query: String,
    val results: List<ResultItem>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val timestamp: Long
)

/**
 * 搜索结果项快照。
 *
 * @property mediaId 媒体唯一标识
 * @property tags 用于指代的标签（如"海","日落"）
 */
data class ResultItem(
    val mediaId: String,
    val tags: List<String>
)

/**
 * 场景标识（等同于 Camera/Gallery/PhotoEdit/Settings）
 *
 * 注意：AgentScene 与 MnnResourceManager.Scene 语义不同：
 * - AgentScene 描述用户所在页面（用于 Agent 能力路由）
 * - MnnResourceManager.Scene 描述 MNN 模型生命周期策略
 */
enum class AgentScene {
    CHAT,
    CAMERA,
    GALLERY,
    PHOTO_EDIT,
    SETTINGS
}

/**
 * [P1-4] 模型使用模式
 *
 * 不同模型的生命周期策略根本不同，此枚举定义了模型的"使用模式"，
 * 供 MnnResourceManager 决策卸载策略。
 *
 * - CROSS_PAGE_PERSISTENT: 跨页面常驻，页面切换不触发卸载（如 LLM）
 * - PAGE_SCOPED: 页面级绑定，离开页面即触发卸载（如 Face）
 * - SESSION_SCOPED: 会话级绑定，关闭时延迟释放（如 ASR）
 */
enum class ModelUsagePattern {
    /** 跨页面常驻：页面切换不触发卸载，仅响应内存压力 */
    CROSS_PAGE_PERSISTENT,
    /** 页面级绑定：离开页面即触发卸载 */
    PAGE_SCOPED,
    /** 会话级绑定：关闭时延迟释放（冷却时间防止频繁切换） */
    SESSION_SCOPED
}

// 注：原 `object AgentIdGenerator`（AtomicInteger 实现）已迁至 :shared commonMain 的
// expect AgentIdGenerator（同包同 FQN，本文件及全部调用点零变更）。

/**
 * 结构化错误码定义
 *
 * 建立结构化的错误分类体系，借鉴标准 JSON-RPC 错误码范围。
 */
object AgentErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val SCENE_MISMATCH = -32000
    const val CAPABILITY_UNAVAILABLE = -32001
    const val EXECUTION_TIMEOUT = -32002
    const val QUEUE_FULL = -32003
    const val QUEUE_EXPIRED = -32004
    const val COMMAND_QUEUED = -32006
}

/**
 * Agent 执行结果（Sealed Class）
 *
 * 每个结果携带 commandId 用于请求-响应关联。
 */
sealed class AgentAction {

    /**
     * 命令执行成功
     *
     * @property commandId 关联的请求命令 ID（32位自增整型）
     * @property command 被执行的命令（用于追踪）
     */
    data class Success(
        val commandId: Int,
        val command: AgentCommand
    ) : AgentAction()

    /**
     * 文本回复（聊天模式）
     *
     * @property commandId 关联的请求命令 ID（32位自增整型）
     * @property message 回复文本
     */
    data class TextReply(
        val commandId: Int,
        val message: String
    ) : AgentAction()

    /**
     * 执行失败
     *
     * @property commandId 关联的请求命令 ID（32位自增整型）
     * @property errorCode 结构化错误码
     * @property message 错误描述
     * @property detail 错误详情（可选）
     */
    data class Error(
        val commandId: Int,
        val errorCode: Int,
        val message: String,
        val detail: String? = null
    ) : AgentAction()

    /**
     * 批量执行结果
     *
     * @property commandId 关联的 BatchExecute 命令 ID（32位自增整型）
     * @property results 各子命令的执行结果（每个都带独立 commandId）
     */
    data class BatchResult(
        val commandId: Int,
        val results: List<AgentAction>
    ) : AgentAction()

    /**
     * 是否执行成功
     */
    val isSuccess: Boolean
        get() = when (this) {
            is Success -> true
            is TextReply -> true
            is Error -> false
            is BatchResult -> results.all { it.isSuccess }
        }

    companion object {
        /**
         * 获取 AgentAction 的 commandId
         */
        fun getCommandId(action: AgentAction): Int = when (action) {
            is Success -> action.commandId
            is TextReply -> action.commandId
            is Error -> action.commandId
            is BatchResult -> action.commandId
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 结构化可观测性事件（Agent First 原则：Structured Observability）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Agent 命令执行链路事件
 *
 * 用于全链路追踪：解析 → 路由 → 分发 → 执行 → 回调
 * 可被 AI 直接消费，实现自我诊断。
 */
data class AgentCommandEvent(
    val stage: CommandStage,
    val commandType: String,
    val commandAction: String? = null,
    val scene: String? = null,
    val success: Boolean,
    val message: String? = null,
    val errorType: String? = null,
    val durationMs: Long? = null,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
) {
    enum class CommandStage {
        PARSE,      // 命令解析
        ROUTE,      // 路由决策
        DISPATCH,   //  capability 分发
        EXECUTE,    //  capability 执行
        CALLBACK,   // 回调触发
        FALLBACK    // 降级处理
    }

    companion object {
        fun parseSuccess(commandType: String, action: String? = null): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.PARSE,
                commandType = commandType,
                commandAction = action,
                success = true
            )

        fun parseFailure(rawInput: String, reason: String): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.PARSE,
                commandType = "unknown",
                success = false,
                message = rawInput,
                errorType = reason
            )

        fun dispatchSuccess(
            commandType: String,
            capability: String,
            scene: String
        ): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.DISPATCH,
                commandType = commandType,
                scene = scene,
                success = true,
                message = "dispatched to $capability"
            )

        fun dispatchFailure(
            commandType: String,
            scene: String,
            reason: String
        ): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.DISPATCH,
                commandType = commandType,
                scene = scene,
                success = false,
                errorType = reason
            )

        fun executeSuccess(commandType: String, capability: String): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.EXECUTE,
                commandType = commandType,
                success = true,
                message = "executed by $capability"
            )

        fun executeFailure(
            commandType: String,
            capability: String,
            reason: String
        ): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.EXECUTE,
                commandType = commandType,
                success = false,
                message = "failed in $capability",
                errorType = reason
            )

        fun callbackMissing(commandType: String, callbackName: String): AgentCommandEvent =
            AgentCommandEvent(
                stage = CommandStage.CALLBACK,
                commandType = commandType,
                success = false,
                errorType = "CALLBACK_NOT_SET",
                message = "$callbackName callback is null"
            )
    }
}
