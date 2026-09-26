package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.ClaudeAgentState
import com.mamba.picme.domain.chat.ClaudeDeliverUi
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.LlmPerformance
import com.mamba.picme.domain.chat.MediaResultsUi
import com.mamba.picme.domain.chat.OptimizeCandidateGroup

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.SearchResultSnapshot
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.agent.core.model.context.toReplyLanguage
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent
import com.mamba.picme.agent.core.intent.IntentGuard
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.inference.local.llm.LlmGenerationMetrics
import com.mamba.picme.agent.AndroidAgentComposition
import com.mamba.picme.agent.core.inference.local.llm.LlmModelNotFoundException
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.core.agenttools.AppTool
import com.mamba.picme.core.agenttools.AppToolExecutor
import com.mamba.picme.core.agenttools.RuntimeStateProvider
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.diag.CrashTraceStore
import com.mamba.picme.core.image.BitmapSampling
import com.mamba.picme.BuildConfig
import android.os.Build
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.ImageDescriptionStrategyResolver
import com.mamba.picme.domain.tag.TaggerModelSelector
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.search.MediaFeedbackUseCase
import com.mamba.picme.domain.usecase.StartTagScanResult
import com.mamba.picme.service.tag.TagGenerationService
import android.util.Log
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.domain.repository.AndroidMediaRepository
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
import com.mamba.picme.features.chat.capability.ChatMediaWriteCapability
import com.mamba.picme.features.chat.capability.ChatRunScriptCapability
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.chat.capability.ChatStartTagScanCapability
import com.mamba.picme.features.chat.capability.SearchOutcome
import com.mamba.picme.features.chat.engineer.EngineerTaskReducer
import com.mamba.picme.features.chat.engineer.EngineerTaskSid
import com.mamba.picme.features.chat.engineer.EngineerTaskSmokeSamples
import com.mamba.picme.features.chat.engineer.TaskCenterPartition
import com.mamba.picme.features.chat.js.CapabilityDispatchHandler
import com.mamba.picme.features.chat.js.loadChartBootstrapJs
import com.mamba.picme.features.chat.js.QuickJsEngine
import com.mamba.picme.features.chat.js.registerGalleryHandlers
import com.mamba.picme.domain.chat.ChatMessageType
import com.mamba.picme.domain.chat.streaming.StreamingPacingController
import com.mamba.picme.features.gallery.MediaViewModel
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val MAX_MESSAGES = 500
private const val GUEST_REGISTER_NUDGE_THRESHOLD = 20
private const val MAX_PREVIEW_LENGTH = 60
private const val MAX_CARDS = 20
private const val CHAT_IMAGE_MAX_PX = 1024

/** 网关 sid 格式：uuid4().hex[:12]（12 位小写 hex）；claude init 的 session_id 是带连字符 UUID，不匹配。 */
private val GATEWAY_SID_PATTERN = EngineerTaskSid.GATEWAY_PATTERN

/** 只读 JS 脚本 eval 超时。 */
private const val DEFAULT_EVAL_TIMEOUT_MS = 5_000L

/** 含 capability.dispatch 的脚本 eval 超时（挂起等用户确认 + 系统授权提示）。 */
private const val WRITE_EVAL_TIMEOUT_MS = 180_000L

/** tagGenerationScheduler 未注入（单测）时图像理解的兜底模型，与 AgentConfigurator 默认一致。 */
private const val FALLBACK_IMAGE_MODEL_KEY = "qwen3_vl_2b"

/** Chat 选图后的用户意图。EDIT 在 UI 层直接跳 PhotoEditor，不会进入 VM 的 sendImageWithIntent。 */
enum class ImageIntent { UNDERSTAND, FIND_SIMILAR, EDIT }

/**
 * chat 远程模型来源（chat 已移除本地 LLM、仅远程；用户配了自配 Key 时可「默认服务器/自配 Key」切换）。
 */
enum class RemoteModelSource(@StringRes val labelRes: Int) {
    DEFAULT(R.string.chat_model_official),
    USER_KEY(R.string.chat_model_user_key)
}

/**
 * 流式生成期间的占位文案资源。
 *
 * L2 协议下本地/远程输出恒为 JSON 指令（如 search_media / text_reply），不可直接展示原始 token；
 * 且远程推理为同步一次性返回（onToken 只回调一次），流式期间无可增量展示的文本。
 * 因此生成阶段统一展示该友好提示，待解析完成后再替换为最终文本/卡片消息。
 */
private const val STREAMING_THINKING_HINT_RES = R.string.chat_thinking

/**
 * Chat 首页 ViewModel — 管理聊天状态与数据流
 *
 * 职责：
 * - 维护消息列表（从 Room 加载）
 * - 处理用户发送消息，通过 LLM 推理获取真实回复
 * - 管理模型切换状态（本地/远程）
 * - 提供处理中状态（isProcessing）
 * - 管理会话列表和当前会话切换
 */
@Suppress("TooManyFunctions", "LargeClass") // 待重构：UI 状态协调器，按职责拆分为多个 ViewModel/Delegate
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel(),
    ChatSearchCapability.Delegate,
    ChatGallerySummaryCapability.Delegate,
    ChatRunScriptCapability.Delegate,
    ChatStartTagScanCapability.Delegate,
    ChatMediaWriteCapability.Delegate {

    private val context = dependencies.context.applicationContext

    /**
     * 用户可见文案的解析 Context：按 App 语言设置取词，而非 applicationContext 的系统语言。
     *
     * 背景：MainActivity 只对 Activity context 做语言覆盖（attachBaseContext →
     * createConfigurationContext），applicationContext 的 Resources 始终跟随系统语言——
     * 「系统中文 + App 内选 English」时 ViewModel 里 context.getString 错出中文
     *（如流式「正在调用工具…」状态文案，2026-08-22 实测）。
     *
     * SYSTEM 档直接返回 applicationContext（与系统语言天然一致，也避开
     * MainActivity.updateLocale 对 Locale.setDefault 的污染问题）；
     * 非 SYSTEM 档按目标 locale 包 createConfigurationContext，并按语言缓存
     *（语言切换极少发生；@Volatile Pair 原子换引用足够，调用线程不固定）。
     */
    @Volatile
    private var cachedStringContext: Pair<AppLanguage, Context>? = null

    private fun stringContext(): Context {
        val language = userSettingsRepository.getAppLanguageBlocking()
        if (language == AppLanguage.SYSTEM) return context
        val cached = cachedStringContext
        if (cached != null && cached.first == language) return cached.second
        val locale = when (language) {
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
            AppLanguage.SPANISH -> Locale("es")
            AppLanguage.FRENCH -> Locale.FRENCH
            AppLanguage.SYSTEM -> Locale.getDefault() // 不可达（上方已 return），when 穷尽所需
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config).also { localized ->
            cachedStringContext = language to localized
        }
    }

    private val chatMessageDao = dependencies.chatMessageDao
    private val chatSessionDao = dependencies.chatSessionDao
    private val userSettingsRepository = dependencies.userSettingsRepository
    private val mediaSearchEngine = dependencies.mediaSearchEngine
    private val mediaFeedbackRepository = dependencies.mediaFeedbackRepository
    private val getGallerySummaryUseCase = dependencies.getGallerySummaryUseCase
    private val queryGalleryMediaUseCase = dependencies.queryGalleryMediaUseCase
    private val personDao = dependencies.personDao
    private val controlledVocab = dependencies.controlledVocab
    private val startTagScanUseCase = dependencies.startTagScanUseCase
    private val chatImageRenderer = dependencies.chatImageRenderer
    private val mediaRepository = dependencies.mediaRepository
    private val chatEditStateHolder = dependencies.chatEditStateHolder
    private val chatEditProcessor = dependencies.chatEditProcessor
    private val chatImageStore = dependencies.chatImageStore
    private val saveChatEditResultUseCase = dependencies.saveChatEditResultUseCase
    private val optimizeGachaController = dependencies.optimizeGachaController
    private val tagGenerationScheduler = dependencies.tagGenerationScheduler

    private val mediaFeedbackUseCase = MediaFeedbackUseCase(mediaFeedbackRepository)
    private val authClient = dependencies.picMeAuthClient

    /** 本条回复是否走了 JS 动态沙箱（onRunScript 被调过）；每次 sendMessage 重置。 */
    @Volatile
    private var replyUsedSandbox = false

    /** 持久化 JS Runtime（懒加载，复用避免 QuickJsEngine 重复创建开销）。 */
    @Volatile
    private var persistentJsRuntime: JsRuntime? = null

    /** JS eval 互斥锁（QuickJS 非线程安全，需串行化 eval）。 */
    private val jsEvalMutex = Mutex()

    /** media_results 卡片读-改-写（查上一张 + upsert）互斥锁，防并发双查 null 产生重复行 */
    private val mediaResultsMutex = Mutex()

    // ── capability.dispatch（JS → CapabilityRegistry 写通路）─────────────────

    /**
     * 写确认状态管理（纯 Kotlin，可单测）：维护「脚本已死，确认不再生效」不变式。
     * pending 弹窗 StateFlow 直接透传给 UI。
     */
    private val writeConfirmationController = WriteConfirmationController()
    val pendingWriteConfirmation: StateFlow<PendingWriteConfirmation?> =
        writeConfirmationController.pending

    /** 系统删除授权请求（复用 [MediaViewModel.DeleteAuthRequest] 与 ChatScreen 既有 launcher）。 */
    private val _deleteAuthRequest = MutableStateFlow<MediaViewModel.DeleteAuthRequest?>(null)
    val deleteAuthRequest: StateFlow<MediaViewModel.DeleteAuthRequest?> = _deleteAuthRequest.asStateFlow()

    /** chat 会话级收藏集合（App 尚无持久化收藏路径，与 Gallery favorite 先例一致）。 */
    private val _favoriteMediaIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteMediaIds: StateFlow<Set<String>> = _favoriteMediaIds.asStateFlow()

    /** chat 会话级选中集合。 */
    private val _selectedMediaIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedMediaIds: StateFlow<Set<String>> = _selectedMediaIds.asStateFlow()

    /** 卡条选中状态（messageId → 选中卡 index），「就用这张」可用性由 UI 依据该值判断。 */
    private val _gachaSelections = MutableStateFlow<Map<String, Int>>(emptyMap())
    val gachaSelections: StateFlow<Map<String, Int>> = _gachaSelections.asStateFlow()

    /** 正在换一组的卡条消息 id 集合（局部 loading + 防抖）。 */
    private val _gachaRerolling = MutableStateFlow<Set<String>>(emptySet())
    val gachaRerolling: StateFlow<Set<String>> = _gachaRerolling.asStateFlow()

    /** capability.dispatch handler：确认交互走 [WriteConfirmationController]，dispatch 走 CHAT 场景注册表。 */
    private val capabilityDispatchHandler = CapabilityDispatchHandler(
        dispatch = { command ->
            CapabilityRegistry.getInstance()
                .dispatch(command, AgentContext(scene = AgentScene.CHAT), null)
        },
        requestConfirmation = { method, risk, targetCount, previewIds ->
            writeConfirmationController.request(
                method = method,
                risk = risk,
                targetCount = targetCount,
                previewUris = resolvePreviewUris(previewIds),
            )
        },
    )

    /** UI 确认/拒绝入口（ChatScreen 确认框按钮回调）。 */
    fun resolveWriteConfirmation(confirmed: Boolean) =
        writeConfirmationController.resolve(confirmed)

    // ── claude-tunnel chat（spec §5/§6：AI 工程师 toggle → /v1/claude-chat SSE 流式）──

    private val claudeChatClient = dependencies.claudeChatClient

    /** app_tool_request 采集执行器（spec §3.1）；null = 未接线，收到请求直接忽略。 */
    private val appToolExecutor = dependencies.appToolExecutor

    /** claude-tunnel sid 持久化（Task 8）；null = 未接线（单测默认），退化为原内存态行为。 */
    private val claudeSidStore = dependencies.claudeSidStore

    /**
     * renderer 跨线程串行化：SSE onEvent 回调线程与 handleAppToolRequest 的 IO 协程会并发
     * renderer.apply。SSE 回调是非 suspend 主流，用 tryLock（失败则直接 apply，回到原竞态水平）；
     * tool result 合成事件持锁短暂，在 IO 协程里 withLock。
     */
    private val rendererMutex = Mutex()

    /** 任务卡落库串行化：SSE 事件串行到达 + 本 Mutex 保证 upsert 顺序与事件顺序一致（防乱序回写）。 */
    private val engineerTaskPersistMutex = Mutex()

    private val _claudeMode = MutableStateFlow(false)
    val claudeMode: StateFlow<Boolean> = _claudeMode.asStateFlow()

    /** AI 工程师模式当前账号是否有代码交付权限（ai_engineer_whitelist）。 */
    private val _canDeliverClaude = MutableStateFlow(false)
    val canDeliverClaude: StateFlow<Boolean> = _canDeliverClaude.asStateFlow()

    /** 网关 session id（多轮 --resume 用；网关 session 事件回填）。@Volatile：IO 线程回调写。 */
    @Volatile
    private var claudeSid: String? = null

    /** [claudeSid] 所属 chat 会话（跨会话守卫：他会话残留 sid 不得串用于本会话 resume/deliver）。 */
    @Volatile
    private var claudeSidOwner: String? = null

    /** msgId → 交付按钮状态（内存态；Room 消息经 loadMessages 重放时按 id 回填）。 */
    private val claudeDeliverOverrides = mutableMapOf<String, ClaudeDeliverUi>()

    /** 工程师任务卡内存态：taskId → 最新状态（Room 为持久层，此处为流式期间的 live 覆盖）。 */
    private val _engineerTasks = MutableStateFlow<Map<String, EngineerTaskState>>(emptyMap())
    val engineerTasks: StateFlow<Map<String, EngineerTaskState>> = _engineerTasks.asStateFlow()

    /** 任务卡动作（交付/继续/重试）在途的 taskId 集合（双击防护；UI 据此禁用审批按钮）。 */
    private val _engineerActionInFlight = MutableStateFlow<Set<String>>(emptySet())
    val engineerActionInFlight: StateFlow<Set<String>> = _engineerActionInFlight.asStateFlow()

    /** 跨会话活动任务计数（任务中心顶栏角标数据源；Room 驱动，进行中判据同 [TaskCenterPartition.isActive]）。 */
    val activeEngineerTaskCount: StateFlow<Int> =
        chatMessageDao.getTaskCardMessages()
            .map { messages ->
                messages.count { entity ->
                    parseEngineerTaskState(entity.metadata)
                        ?.let { task -> TaskCenterPartition.isActive(task) } == true
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 当前回合活动任务（sendClaudeMessage 提交时置位，回合结束清空）。 */
    @Volatile
    private var activeEngineerTaskId: String? = null

    /**
     * 进入 AI 工程师模式：有持久化上下文且所属 chat 会话仍在 → 切回该会话并恢复 sid
     * （transcript + agent 上下文双连续）；否则新建独立会话（claude-tunnel 上下文独立）。
     */
    fun enterClaudeMode() {
        if (_claudeMode.value) return
        _claudeMode.value = true
        _serverAuthToken.value.takeIf { it.isNotBlank() }?.let { refreshClaudeAvailability(it) }
        claudeDeliverOverrides.clear()
        val saved = claudeSidStore?.load()
        if (saved == null) {
            claudeSid = null
            claudeSidOwner = null
            newSession()
            return
        }
        val (chatSessionId, sid) = saved
        viewModelScope.launch {
            if (chatSessionDao.getSession(chatSessionId) != null) {
                claudeSid = sid
                claudeSidOwner = chatSessionId
                switchSession(chatSessionId)
            } else {
                // 所属会话已被删除：清残留记录，按全新会话处理
                claudeSidStore?.clear()
                claudeSid = null
                claudeSidOwner = null
                newSession()
            }
        }
    }

    fun exitClaudeMode() {
        _claudeMode.value = false
    }

    /**
     * claude 模式下的用户消息：走 [ClaudeChatClient.chat] SSE 流式（spec §6 事件）。
     * 事件经 [ClaudeAgentRenderer] 折叠成 agent 气泡（文本流式 + 步骤 + 文件改动）；
     * done 后落 Room（metadata 带 claude_agent_state，跨重载保留）；交付审批由 TASK_CARD 任务卡承载。
     * @param actionInFlightTaskId 任务卡动作（继续/重试）来源卡 id：回合结束（finally）释放其在途标记。
     * @param resumeSid 任务中心跨会话动作传入的卡片 sid（经网关 pattern 校验后才采纳）：
     *   VM 级 claudeSid 属于其他会话时的显式 resume 通道，防串会话续跑。
     */
    fun sendClaudeMessage(text: String, actionInFlightTaskId: String? = null, resumeSid: String? = null) {
        if (text.isBlank()) {
            // 携带在途标记的空调用：立即释放（正常路径由回合 finally 释放）
            actionInFlightTaskId?.let { id ->
                _engineerActionInFlight.update { inFlight -> inFlight - id }
            }
            return
        }
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            // 提升到 try 外：finally 做 compare-and-clear，防旧回合误清新回合的活动卡
            var taskId: String? = null
            try {
                ensureSessionExists(sessionId)
                chatMessageDao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        type = "user_text",
                        content = text,
                        modelUsed = null,
                    ),
                )
                chatSessionDao.touchSession(sessionId)

                val token = _serverAuthToken.value
                if (token.isBlank()) {
                    insertAgentMessage(sessionId, stringContext().getString(R.string.claude_login_required), "error")
                    _showRegistrationSheet.value = true
                    return@launch
                }

                _isProcessing.value = true
                // 提交即登记任务卡（spec：每次 claude-chat 提交 = 一张任务卡）
                val roundTaskId = "task_" + UUID.randomUUID().toString()
                taskId = roundTaskId
                val initialTask = EngineerTaskReducer.initial(roundTaskId, text, System.currentTimeMillis())
                activeEngineerTaskId = roundTaskId
                _engineerTasks.update { tasks -> tasks + (roundTaskId to initialTask) }
                engineerTaskPersistMutex.withLock { persistEngineerTask(sessionId, initialTask) }
                val renderer = ClaudeAgentRenderer()
                val streamingId = "claude_streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = "",
                    modelUsed = currentModelLabel(),
                    isStreaming = true,
                    isThinking = true,
                    claudeAgent = ClaudeAgentState(),
                )
                // sid 归属守卫：内存 sid 仅当属于本次发送会话才可用（切会话/任务中心跨会话动作后
                // 防串会话 --resume）；否则按 store 单槽恢复（仅当记录属于当前会话），
                // 再回落显式 resumeSid（任务中心跨会话动作传卡片 sid）。
                if (claudeSidOwner != sessionId) {
                    claudeSid = null
                    claudeSidOwner = null
                }
                if (claudeSid == null) {
                    claudeSid = claudeSidStore?.load()?.takeIf { it.first == sessionId }?.second
                    if (claudeSid != null) claudeSidOwner = sessionId
                }
                if (claudeSid == null && resumeSid != null && EngineerTaskSid.isGatewaySid(resumeSid)) {
                    // 网关有效 resume 不下发 session 事件（见下方 Session 分支注释），本地先登记归属
                    claudeSid = resumeSid
                    claudeSidOwner = sessionId
                    claudeSidStore?.save(sessionId, resumeSid)
                }
                // SSE 回调是非 suspend 主流：tryLock 与 IO 协程的合成 ToolResult 串行，
                // 拿不到锁则直接 apply（事件不能丢，竞态概率极低）
                fun applyToRenderer(ev: ClaudeEvent) {
                    val locked = rendererMutex.tryLock()
                    try {
                        renderer.apply(ev)
                        _streamingMessage.update { cur ->
                            cur?.copy(claudeAgent = renderer.state, isThinking = false)
                        }
                    } finally {
                        if (locked) rendererMutex.unlock()
                    }
                }
                val result = claudeChatClient.chat(token, text, claudeSid) { event ->
                    onClaudeEventForTask(sessionId, event, renderer.state.text)
                    when (event) {
                        is ClaudeEvent.Session -> Logger.i(TAG, "claude evt: Session sid=${event.sid}")
                        is ClaudeEvent.ToolUse -> Logger.i(
                            TAG,
                            "claude evt: ToolUse tool=${event.tool} detail=${ClaudeAgentRenderer.briefInput(event.tool, event.input)}",
                        )
                        is ClaudeEvent.FileChange -> Logger.i(TAG, "claude evt: FileChange ${event.action} ${event.path}")
                        is ClaudeEvent.ToolResult -> Logger.i(TAG, "claude evt: ToolResult ok=${event.ok}")
                        is ClaudeEvent.Error -> Logger.i(TAG, "claude evt: Error ${event.message}")
                        is ClaudeEvent.Done -> Logger.i(TAG, "claude evt: Done")
                        is ClaudeEvent.Cost -> Logger.i(TAG, "claude evt: Cost turns=${event.turns}")
                        is ClaudeEvent.AssistantText -> Unit
                        is ClaudeEvent.AppToolRequest -> Unit
                    }
                    when (event) {
                        // 网关 sid = 12 位 hex（workdir/deliver key，uuid4().hex[:12]）；
                        // claude stream-json init 也带一条 session（带连字符 UUID，仅网关内部 --resume 用），忽略。
                        // 有效 resume 时网关不下发 session 事件，该判断天然跳过；
                        // 网关侧轮换（workdir 被清后重新签发）时新 sid 直接覆盖并持久化，自愈失忆。
                        is ClaudeEvent.Session -> if (event.sid.matches(GATEWAY_SID_PATTERN)) {
                            claudeSid = event.sid
                            claudeSidOwner = sessionId
                            claudeSidStore?.save(sessionId, event.sid)
                        }
                        is ClaudeEvent.Done, is ClaudeEvent.Cost -> Unit
                        // spec §3.1：App 数据采集请求。合成 ToolUse 步骤（复用步骤气泡折叠），
                        // 后台执行采集 + postToolResult 回传，完成后合成 ToolResult 收尾。
                        is ClaudeEvent.AppToolRequest -> {
                            applyToRenderer(ClaudeEvent.ToolUse(event.tool, event.args))
                            handleAppToolRequest(event.requestId, event.tool, event.args, renderer)
                        }
                        else -> applyToRenderer(event)
                    }
                }
                _streamingMessage.value = null
                result.fold(
                    onSuccess = { persistClaudeBubble(sessionId, renderer.state) },
                    onFailure = { e ->
                        markActiveEngineerTaskFailed(sessionId, e.message)
                        insertAgentMessage(
                            sessionId,
                            stringContext().getString(R.string.chat_inference_error, e.message ?: stringContext().getString(R.string.chat_unknown_error)),
                            "error",
                        )
                    },
                )
            } catch (e: Exception) {
                Logger.e(TAG, "sendClaudeMessage failed", e)
                markActiveEngineerTaskFailed(sessionId, e.message)
                _streamingMessage.value = null
            } finally {
                _isProcessing.value = false
                if (activeEngineerTaskId == taskId) activeEngineerTaskId = null
                actionInFlightTaskId?.let { id ->
                    _engineerActionInFlight.update { inFlight -> inFlight - id }
                }
            }
        }
    }

    /** spec §3.1/§3.3：执行 App 数据采集并回传；过程经合成 ToolUse/ToolResult 事件入气泡。 */
    @VisibleForTesting
    internal fun handleAppToolRequest(
        requestId: String,
        tool: String,
        args: JSONObject,
        renderer: ClaudeAgentRenderer,
    ) {
        val executor = appToolExecutor ?: return
        val token = _serverAuthToken.value
        viewModelScope.launch(Dispatchers.IO) {
            var ok = true
            val summary = try {
                val appTool = AppTool.fromName(tool)
                    ?: throw IllegalArgumentException("unknown app tool: $tool")
                val payload = executor.execute(appTool, args)
                if (token.isNotBlank()) {
                    claudeChatClient.postToolResult(token, requestId, payload)
                }
                if (payload.optBoolean("empty")) {
                    stringContext().getString(R.string.chat_claude_tool_no_data, payload.optString("reason"))
                } else if (payload.optBoolean("truncated")) {
                    stringContext().getString(R.string.chat_claude_tool_uploaded_truncated, payload.toString().length)
                } else {
                    stringContext().getString(R.string.chat_claude_tool_uploaded, payload.toString().length)
                }
            } catch (e: Exception) {
                ok = false
                Logger.e(TAG, "handleAppToolRequest failed", e)
                if (token.isNotBlank()) {
                    runCatching {
                        claudeChatClient.postToolResult(
                            token, requestId, JSONObject().put("error", e.message ?: "collect failed"),
                        )
                    }
                }
                stringContext().getString(R.string.chat_claude_tool_collect_failed, e.message ?: stringContext().getString(R.string.chat_unknown_error))
            }
            // 与 SSE 回调线程串行（见 applyToRenderer）：合成 ToolResult 持锁短暂
            rendererMutex.withLock {
                renderer.apply(ClaudeEvent.ToolResult(ok = ok, summary = summary))
                _streamingMessage.update { cur -> cur?.copy(claudeAgent = renderer.state) }
            }
        }
    }

    /**
     * 把折叠后的 agent 气泡落 Room（type=agent_text + metadata.claude_agent_state）。
     * loadMessages 重放时由 [parseClaudeAgentState] 还原 [ChatMessageUi.claudeAgent]。
     */
    private suspend fun persistClaudeBubble(sessionId: String, state: ClaudeAgentState) {
        val sid = claudeSid
        Logger.i(
            TAG,
            "persistClaudeBubble: hasFileChange=${state.hasFileChange} claudeSid=$sid steps=${state.steps.size} stepTools=${state.steps.map { it.tool }}",
        )
        val msgId = UUID.randomUUID().toString()
        // 任务卡时代（2026-09-25 起）：交付审批收口到 TASK_CARD（US-2 审批唯一入口），
        // 新气泡不再登记 claudeDeliverOverrides；confirmClaudeDeliver 仅供 legacy 内存 override 使用。
        val metadata = JSONObject().put("claude_agent_state", state.toJson()).toString()
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = msgId,
                sessionId = sessionId,
                type = "agent_text",
                content = state.text,
                modelUsed = currentModelLabel(),
                metadata = metadata,
            ),
        )
        chatSessionDao.touchSession(sessionId)
    }

    /** 任务卡 upsert（REPLACE 同 id 重插，对齐 gacha metadata 覆写先例）；timestamp 恒为 startedAtMs，卡片锚定提交位置。 */
    private suspend fun persistEngineerTask(sessionId: String, state: EngineerTaskState) {
        // 展示层 overlay 的持久化失败不应中止推理（launch 内异常直接崩溃，故就地吞掉只记日志）
        runCatching {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    id = state.taskId,
                    sessionId = sessionId,
                    type = EngineerTaskState.ROOM_TYPE,
                    content = state.sourceText.take(50),
                    timestamp = state.startedAtMs,
                    metadata = JSONObject().put("engineer_task", state.toJson()).toString(),
                )
            )
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Logger.e(TAG, "persistEngineerTask failed taskId=${state.taskId}", e)
        }
    }

    /** SSE 事件 → 任务卡状态机；结构性事件才落库（文本 delta 不触发 Room churn）。 */
    private fun onClaudeEventForTask(sessionId: String, event: ClaudeEvent, finalText: String) {
        val taskId = activeEngineerTaskId ?: return
        val current = _engineerTasks.value[taskId] ?: return
        var next = EngineerTaskReducer.reduce(current, event, canDeliverClaude.value, System.currentTimeMillis())
        if (event is ClaudeEvent.Done && !event.truncated) {
            next = next.copy(resultSummary = EngineerTaskReducer.summarize(finalText))
        }
        if (next == current) return
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        if (EngineerTaskReducer.isStructural(event)) {
            viewModelScope.launch { engineerTaskPersistMutex.withLock { persistEngineerTask(sessionId, next) } }
        }
    }

    /** SSE 断连/失败：任务卡 terminal 化（P1 在场态 = FAILED；P3 回联时改「后台运行中」）。 */
    private fun markActiveEngineerTaskFailed(sessionId: String, message: String?) {
        val taskId = activeEngineerTaskId ?: return
        val current = _engineerTasks.value[taskId] ?: return
        // 断连语义只裁决「还在跑」的卡：COMPLETED/AWAITING_CONTINUE/FAILED 不被二次裁决
        // （否则 catch 路径会把 AWAITING_CONTINUE 打成 FAILED，吞掉「继续」affordance）
        if (current.status != EngineerTaskStatus.RUNNING) return
        val next = EngineerTaskReducer.reduce(
            current,
            ClaudeEvent.Error(message ?: "connection lost"),
            canDeliver = false,
            nowMs = System.currentTimeMillis(),
        )
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        viewModelScope.launch { engineerTaskPersistMutex.withLock { persistEngineerTask(sessionId, next) } }
    }

    /**
     * 截断后「继续」：用当前 session 的 sid 发"继续"（[sendClaudeMessage] 复用 --resume）。
     * 注：继续的是本会话最新 sid，与具体气泡无关（一会话一 sid）。
     */
    fun continueClaude() {
        sendClaudeMessage(stringContext().getString(R.string.chat_claude_continue))
    }

    /** 审批动作回填 + 落库（US-9：同一决策点只审批一次，reducer 内幂等）。sessionId 由调用方在动作入口捕获，防异步回包时用户已切会话。 */
    private fun resolveEngineerTask(
        sessionId: String,
        taskId: String,
        resolution: EngineerTaskResolution,
        deliverBranch: String? = null,
    ) {
        val current = _engineerTasks.value[taskId] ?: return
        val next = EngineerTaskReducer.resolved(current, resolution, System.currentTimeMillis(), deliverBranch)
        if (next == current) return
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        viewModelScope.launch {
            engineerTaskPersistMutex.withLock { persistEngineerTask(sessionId, next) }
        }
    }

    /** 任务卡「继续」：旧卡回填已继续 + 同 sid 续跑（新回合新卡，语义同气泡按钮）。resumeSid 见 [sendClaudeMessage]。 */
    fun continueEngineerTask(taskId: String, resumeSid: String? = null) {
        if (_isProcessing.value) return
        // 双击防护：在途标记跨帧存活（sendClaudeMessage 的 launch 异步置位 _isProcessing，
        // 同帧 try/finally 移除会留空窗），由新回合 finally 释放
        if (taskId in _engineerActionInFlight.value) return
        resolveEngineerTask(_currentSessionId.value, taskId, EngineerTaskResolution.CONTINUED)
        _engineerActionInFlight.update { inFlight -> inFlight + taskId }
        sendClaudeMessage(
            stringContext().getString(R.string.chat_claude_continue),
            actionInFlightTaskId = taskId,
            resumeSid = resumeSid,
        )
    }

    /** 任务卡「到此为止」。sessionId 显式传入时按该会话落库（任务中心跨会话动作），缺省取当前会话。 */
    fun abandonEngineerTask(taskId: String, sessionId: String? = null) {
        if (_isProcessing.value) return
        resolveEngineerTask(sessionId ?: _currentSessionId.value, taskId, EngineerTaskResolution.ABANDONED)
    }

    /** 任务卡「暂不」交付。sessionId 语义同 [abandonEngineerTask]。 */
    fun skipEngineerDeliver(taskId: String, sessionId: String? = null) {
        if (_isProcessing.value) return
        resolveEngineerTask(sessionId ?: _currentSessionId.value, taskId, EngineerTaskResolution.DELIVER_SKIPPED)
    }

    /** 任务卡「重试」：重发原消息（新任务卡，不覆盖旧卡，US-11）。resumeSid 见 [sendClaudeMessage]。 */
    fun retryEngineerTask(taskId: String, resumeSid: String? = null) {
        if (_isProcessing.value) return
        val source = _engineerTasks.value[taskId]?.sourceText?.takeIf { text -> text.isNotBlank() } ?: return
        // 双击防护同 continueEngineerTask：在途标记由新回合 finally 释放
        if (taskId in _engineerActionInFlight.value) return
        _engineerActionInFlight.update { inFlight -> inFlight + taskId }
        sendClaudeMessage(source, actionInFlightTaskId = taskId, resumeSid = resumeSid)
    }

    /** 任务卡「交付 push」：复用 ClaudeChatClient.deliver；失败保持 AWAITING_DELIVER 可重试。sessionId 语义同 [abandonEngineerTask]。 */
    fun deliverEngineerTask(taskId: String, sessionId: String? = null) {
        if (_isProcessing.value) return
        val targetSessionId = sessionId ?: _currentSessionId.value
        // 状态门控：仅 AWAITING_DELIVER 可交付，防 COMPLETED 卡被代码层重复交付
        val task = _engineerTasks.value[taskId]
            ?.takeIf { candidate -> candidate.status == EngineerTaskStatus.AWAITING_DELIVER }
            ?: return
        // 消费端校验+归属守卫（reducer 冻结面不动）：claude init 的 UUID session 事件每回合都会
        // 经 reducer last-wins 覆盖 task.sid，而网关 /deliver 只认 12-hex sid；
        // 回落 VM 级 claudeSid 仅当其归属目标会话（跨会话残留 sid 不得串用）。
        val sid = EngineerTaskSid.chooseActionSid(
            taskSid = task.sid,
            vmSid = claudeSid,
            vmSidOwner = claudeSidOwner,
            targetSessionId = targetSessionId,
        ) ?: run {
            // 无法定位 sid（跨会话且卡片 sid 被 UUID 覆盖）：错误摘要上卡、保持待审批，
            // 引导回原会话交付（防静默失败，按钮失灵零反馈）
            markEngineerTaskDeliverError(
                targetSessionId,
                taskId,
                stringContext().getString(R.string.chat_task_deliver_sid_unavailable),
            )
            return
        }
        // 双击防护：taskId 级 in-flight，回包/早退必移除（finally 覆盖 token 缺失路径）
        if (taskId in _engineerActionInFlight.value) return
        _engineerActionInFlight.update { inFlight -> inFlight + taskId }
        viewModelScope.launch {
            try {
                val token = _serverAuthToken.value
                if (token.isBlank()) return@launch
                claudeChatClient.deliver(token, sid, "push").fold(
                    onSuccess = { json ->
                        val branch = json.optString("branch")
                        if (json.optBoolean("ok", false) && branch.isNotBlank()) {
                            resolveEngineerTask(targetSessionId, taskId, EngineerTaskResolution.DELIVERED, branch)
                        } else {
                            markEngineerTaskDeliverError(targetSessionId, taskId, json.optString("error"))
                        }
                    },
                    onFailure = { error -> markEngineerTaskDeliverError(targetSessionId, taskId, error.message) },
                )
            } finally {
                _engineerActionInFlight.update { inFlight -> inFlight - taskId }
            }
        }
    }

    /** 交付失败：保持待审批态，错误摘要上卡（可重试，对齐 confirmClaudeDeliver pending 恢复语义）。 */
    private fun markEngineerTaskDeliverError(sessionId: String, taskId: String, message: String?) {
        val current = _engineerTasks.value[taskId] ?: return
        // 晚到的失败回包不写已裁决卡（与 resolved 幂等对称）
        if (current.resolution != null) return
        val next = current.copy(
            errorSummary = stringContext().getString(R.string.claude_deliver_failed, message ?: ""),
            updatedAtMs = System.currentTimeMillis(),
        )
        _engineerTasks.update { tasks -> tasks + (taskId to next) }
        viewModelScope.launch {
            engineerTaskPersistMutex.withLock { persistEngineerTask(sessionId, next) }
        }
    }

    /**
     * 任务中心跨会话动作前置：内存态无该卡时从 Room 补种（suspend，调用方须 await 后再动作），
     * 防跨会话操作时 resolve/retry 因 map 未加载而空转。已存在（含 live 覆盖）时不触碰。
     */
    private suspend fun primeEngineerTask(taskId: String) {
        if (_engineerTasks.value.containsKey(taskId)) return
        val entity = chatMessageDao.getMessageById(taskId) ?: return
        val task = parseEngineerTaskState(entity.metadata) ?: return
        _engineerTasks.update { tasks ->
            if (tasks.containsKey(taskId)) tasks else tasks + (taskId to task)
        }
    }

    /** 任务中心「交付 push」：跨会话安全（prime 补种 + 显式 sessionId 落库），页内完成不跳转。 */
    fun deliverEngineerTaskFromCenter(sessionId: String, taskId: String) {
        viewModelScope.launch {
            primeEngineerTask(taskId)
            deliverEngineerTask(taskId, sessionId)
        }
    }

    /** 任务中心「暂不」交付：跨会话安全，页内完成不跳转。 */
    fun skipEngineerDeliverFromCenter(sessionId: String, taskId: String) {
        viewModelScope.launch {
            primeEngineerTask(taskId)
            skipEngineerDeliver(taskId, sessionId)
        }
    }

    /** 任务中心「到此为止」：跨会话安全，页内完成不跳转。 */
    fun abandonEngineerTaskFromCenter(sessionId: String, taskId: String) {
        viewModelScope.launch {
            primeEngineerTask(taskId)
            abandonEngineerTask(taskId, sessionId)
        }
    }

    /**
     * 任务中心「继续」：切到任务所属会话后同 sid 续跑（新回合新卡）。
     * 发送依赖当前会话上下文，故跨会话时先 switchSession；卡片自带合规 sid 经 resumeSid 显式续跑
     * （VM 级 claudeSid 属于其他会话时不得串用）；UI 层随后应返回 chat 观察新回合。
     */
    fun continueEngineerTaskFromCenter(sessionId: String, taskId: String) {
        viewModelScope.launch {
            primeEngineerTask(taskId)
            val cardSid = _engineerTasks.value[taskId]?.sid
            if (_currentSessionId.value != sessionId) switchSession(sessionId)
            continueEngineerTask(taskId, resumeSid = cardSid)
        }
    }

    /** 任务中心「重试」：切到任务所属会话后重发原消息（新卡，不覆盖旧卡，US-11）；resumeSid 语义同 [continueEngineerTaskFromCenter]；UI 层随后应返回 chat。 */
    fun retryEngineerTaskFromCenter(sessionId: String, taskId: String) {
        viewModelScope.launch {
            primeEngineerTask(taskId)
            val cardSid = _engineerTasks.value[taskId]?.sid
            if (_currentSessionId.value != sessionId) switchSession(sessionId)
            retryEngineerTask(taskId, resumeSid = cardSid)
        }
    }

    /**
     * 交付当前气泡对应 session 的改动（spec §8）：POST /v1/claude-deliver → 网关 push claude-chat/<sid>。
     * 结果回填气泡；gateway MVP 仅 push（pr/auto 二期）。
     */
    fun confirmClaudeDeliver(messageId: String, mode: String = "push") {
        val ov = claudeDeliverOverrides[messageId]
        Logger.i(TAG, "confirmClaudeDeliver: msgId=$messageId mode=$mode ov=${ov?.sid}/${ov?.pending}")
        if (ov == null) return
        val sid = ov.sid
        claudeDeliverOverrides[messageId] = ov.copy(pending = false)
        _messages.update { msgs ->
            msgs.map { m -> if (m.id == messageId) m.copy(claudeDeliver = ov.copy(pending = false)) else m }
        }
        viewModelScope.launch {
            val token = _serverAuthToken.value
            if (token.isBlank()) {
                Logger.w(TAG, "confirmClaudeDeliver: token blank, abort")
                return@launch
            }
            val t0 = System.currentTimeMillis()
            Logger.i(TAG, "confirmClaudeDeliver: calling deliver sid=$sid ...")
            val result = claudeChatClient.deliver(token, sid, mode)
            Logger.i(TAG, "confirmClaudeDeliver: deliver returned in ${System.currentTimeMillis() - t0}ms isSuccess=${result.isSuccess}")
            val extra = result.fold(
                onSuccess = { json ->
                    Logger.i(TAG, "confirmClaudeDeliver: response=$json")
                    val branch = json.optString("branch")
                    if (json.optBoolean("ok", false) && branch.isNotBlank()) {
                        stringContext().getString(R.string.claude_deliver_done, branch)
                    } else {
                        stringContext().getString(R.string.claude_deliver_failed, json.optString("error"))
                    }
                },
                onFailure = { e ->
                    Logger.w(TAG, "confirmClaudeDeliver: failure ${e.javaClass.simpleName}: ${e.message}")
                    stringContext().getString(R.string.claude_deliver_failed, e.message ?: "")
                },
            )
            // 成功（ok+branch）才隐藏交付按钮；失败则恢复 pending=true，允许重试
            // （之前点一次失败按钮就永久消失，无法重试）。
            val delivered = result.isSuccess &&
                result.getOrNull()?.optBoolean("ok", false) == true &&
                !result.getOrNull()?.optString("branch").isNullOrBlank()
            claudeDeliverOverrides[messageId] = ov.copy(pending = !delivered)
            _messages.update { msgs ->
                val updated = msgs.map { m ->
                    if (m.id == messageId) {
                        val st = m.claudeAgent
                        val merged = if (st == null) ClaudeAgentState(text = extra) else st.copy(text = st.text + "\n" + extra)
                        m.copy(claudeAgent = merged, claudeDeliver = ov.copy(pending = !delivered))
                    } else {
                        m
                    }
                }
                Logger.i(TAG, "confirmClaudeDeliver: delivered=$delivered pending=${!delivered} extra='$extra'")
                updated
            }
        }
    }

    fun consumeDeleteAuthRequest() {
        _deleteAuthRequest.value = null
    }

    /**
     * 把确认预览的媒体 id 解析为缩略图 URI（供确认框网格展示）。
     * 解析失败（id 失效/媒体已删）返回空列表，确认框退化为纯文本。
     */
    private suspend fun resolvePreviewUris(previewIds: List<String>): List<String> {
        val idSet = previewIds.mapNotNull { it.toLongOrNull() }.toSet()
        if (idSet.isEmpty()) return emptyList()
        return runCatching {
            mediaRepository.allMedia.first()
                .filter { it.id in idSet }
                .map { it.uri }
        }.getOrDefault(emptyList())
    }

    /** session -> 上一轮搜索全量命中（供 in-set 细化）。 */
    private val lastResultAssets = mutableMapOf<String, List<MediaAsset>>()

    /**
     * session -> 脚本路径（run_gallery_script return 含 ids）产出的媒体 id 集合与总数，
     * 待回合收尾护栏消费（spec §3.5-a/c）：水合成 MediaAsset 后收口 refine 基数
     *（写 [lastResultAssets]），且本回合未出横滑卡片时端侧补卡。
     */
    private val pendingScriptMediaIds = mutableMapOf<String, Pair<List<Long>, Int>>()

    /** session -> 最近搜索快照（多轮对话指代用）。 */
    private val sessionSearchSnapshots = mutableMapOf<String, MutableList<SearchResultSnapshot>>()

    /** session -> 当前生效的排除约束（内存实现，跟随当前搜索结果）。 */
    private val sessionExcludes = mutableMapOf<String, MutableSet<String>>()

    /** 防止用户快速重复点击同一反馈按钮。 */
    private val pendingFeedbackActions = mutableSetOf<String>()

    /** 当前会话最近一条用户图片消息的持久化 URI，供 ai_optimize 指代「这张照片】。 */
    private val _lastUserImageUri = MutableStateFlow<String?>(null)

    /**
     * 时间专属词集合。当 [SearchIntent.timeRange] 已经表达了时间范围时，
     * 这些词不应再作为内容关键词去匹配标签/OCR/文件名，否则会导致时间候选集与空标签候选集交集为空。
     */
    private val timeOnlyKeywords = setOf(
        "去年", "今年", "明年", "前年", "后年",
        "春天", "夏天", "秋天", "冬天", "春季", "夏季", "秋季", "冬季",
        "上半年", "下半年", "近半年", "最近半年", "半年",
        "近一年", "最近一年", "一年", "近几年", "最近几年",
        "最近", "近三个月", "近3个月",
        "今天", "昨天", "前天", "明天", "后天",
        "上周", "本周", "下周",
        "上星期", "这星期", "下星期", "上个星期", "这个星期", "下个星期",
        "上个月", "这个月", "下个月", "上月", "今月", "下月"
    )

    /** 匹配“3月”“12月”“五月”等月份表达。 */
    private val monthKeywordRegex = Regex("""^(\d{1,2}月|[一二三四五六七八九十]{1,3}月)$""")

    private val orchestrator = AgentOrchestrator.getInstance()

    private val _currentSessionId = MutableStateFlow("default")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    /**
     * 当前正在流式生成的 AI 消息（未落库），用于实时展示 token。
     */
    private val _streamingMessage = MutableStateFlow<ChatMessageUi?>(null)
    val streamingMessage: StateFlow<ChatMessageUi?> = _streamingMessage.asStateFlow()

    private val pacingController = StreamingPacingController(
        scope = viewModelScope,
        onPaced = { text, cursor ->
            _streamingMessage.update { current ->
                current?.copy(content = text, showCursor = cursor)
            }
        }
    )

    /**
     * AI 优化命令触发后需要导航到编辑器的目标 URI。
     */
    private val _pendingAiOptimizeNavigation = MutableStateFlow<String?>(null)
    val pendingAiOptimizeNavigation: StateFlow<String?> = _pendingAiOptimizeNavigation.asStateFlow()

    fun consumeAiOptimizeNavigation() {
        _pendingAiOptimizeNavigation.value = null
    }

    /**
     * UI 实际展示的消息列表：已持久化消息 + 流式临时消息；TASK_CARD 叠加工程师任务 live 态。
     */
    val displayMessages: StateFlow<List<ChatMessageUi>> =
        combine(_messages, _streamingMessage, _engineerTasks) { messages, streaming, tasks ->
            val base = if (streaming != null) messages + streaming else messages
            if (tasks.isEmpty()) {
                base
            } else {
                base.map { msg ->
                    val live = msg.engineerTask?.let { task -> tasks[task.taskId] }
                    if (live != null && live != msg.engineerTask) msg.copy(engineerTask = live) else msg
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentModel = MutableStateFlow<ChatModelOption>(ChatModelOption.Remote)
    val currentModel: StateFlow<ChatModelOption> = _currentModel.asStateFlow()

    /** chat 可选远程模型项（官方 / 用户自配）。 */
    data class ChatRemoteModel(val id: String, val displayName: String, val remoteConfig: RemoteModelConfig)

    private val officialModel = ChatRemoteModel(
        "official",
        stringContext().getString(R.string.chat_model_official),
        RemoteModelConfig.PICME_SERVER_DEFAULT
    )

    /** 可选模型列表：官方 + 用户自配（已配置 apiKey 的）。 */
    private val _availableModels = MutableStateFlow<List<ChatRemoteModel>>(listOf(officialModel))
    val availableModels: StateFlow<List<ChatRemoteModel>> = _availableModels.asStateFlow()

    /** 当前选中模型 id（默认官方）。 */
    private val _selectedModelId = MutableStateFlow(officialModel.id)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    /** 当前选中模型。 */
    val selectedModel: ChatRemoteModel
        get() = _availableModels.value.find { it.id == _selectedModelId.value } ?: officialModel

    /**
     * 官方模型注入账户 token（gatewayToken → X-App-Token，走 PoLang Server 账户额度）；
     * 用户自配模型用其 apiKey 直连，无需注入。
     */
    private fun effectiveRemoteConfig(model: ChatRemoteModel): RemoteModelConfig =
        if (model.id == officialModel.id) {
            model.remoteConfig.copy(gatewayToken = _serverAuthToken.value)
        } else {
            model.remoteConfig
        }

    /** 用户是否配了自配 Key（决定是否显示模型切换胶囊）。从设置中心 flow 实时更新。 */
    private val _hasUserKey = MutableStateFlow(false)
    val hasUserKey: StateFlow<Boolean> = _hasUserKey.asStateFlow()

    // ── 访客模式与注册引导 ──────────────────────────────────
    private val _serverAuthToken = MutableStateFlow("")

    /** 访客消息累计数（DataStore 持久，跨会话），驱动渐进式注册引导。 */
    private val _guestMessageCount = MutableStateFlow(0)
    val guestMessageCount: StateFlow<Int> = _guestMessageCount.asStateFlow()

    /** 常驻引导 banner 的会话内关闭标记（不持久化，重启 App 复现以保留提醒）。 */
    private val _guestBannerDismissed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            userSettingsRepository.serverAuthTokenFlow.collect { token ->
                _serverAuthToken.value = token
                if (token.isBlank()) {
                    _canDeliverClaude.value = false
                } else if (_claudeMode.value) {
                    refreshClaudeAvailability(token)
                }
            }
        }
        viewModelScope.launch {
            userSettingsRepository.guestChatMessageCountFlow.collect { count ->
                _guestMessageCount.value = count
            }
        }
    }

    private fun refreshClaudeAvailability(token: String) {
        viewModelScope.launch {
            claudeChatClient.engineerAvailability(token)
                .onSuccess { _canDeliverClaude.value = it }
                .onFailure { _canDeliverClaude.value = false }
        }
    }

    // ── 问题上报 ──────────────────────────────────
    private val issueReportClient = dependencies.issueReportClient

    private val _issueReportState = MutableStateFlow<IssueReportState>(IssueReportState.Idle)
    val issueReportState: StateFlow<IssueReportState> = _issueReportState.asStateFlow()

    fun submitIssueReport(category: String, title: String, description: String) {
        val token = _serverAuthToken.value
        if (token.isBlank()) {
            _issueReportState.value = IssueReportState.Error(stringContext().getString(R.string.report_issue_guest_not_allowed))
            return
        }
        if (title.isBlank()) {
            _issueReportState.value = IssueReportState.Error(stringContext().getString(R.string.chat_report_title_required))
            return
        }
        _issueReportState.value = IssueReportState.Submitting
        viewModelScope.launch {
            val result = issueReportClient.submit(token, category, title, description)
            _issueReportState.value = result.fold(
                onSuccess = { IssueReportState.Success(it) },
                onFailure = { IssueReportState.Error(it.message ?: stringContext().getString(R.string.chat_report_failed)) }
            )
        }
    }

    fun resetIssueReportState() {
        _issueReportState.value = IssueReportState.Idle
    }

    /** 远程模式且未注册（无 server token）→ 访客试用，由服务端设备级额度放行。 */
    val isGuestMode: StateFlow<Boolean> = combine(_currentModel, _serverAuthToken) { model, token ->
        model is ChatModelOption.Remote && token.isBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 累计达阈值后常驻的可关闭引导 banner（仅 guest 模式）。 */
    val showGuestBanner: StateFlow<Boolean> =
        combine(isGuestMode, _guestMessageCount, _guestBannerDismissed) { guest, count, dismissed ->
            guest && count >= GUEST_REGISTER_NUDGE_THRESHOLD && !dismissed
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissGuestBanner() {
        _guestBannerDismissed.value = true
    }

    private val _showRegistrationSheet = MutableStateFlow(false)
    val showRegistrationSheet: StateFlow<Boolean> = _showRegistrationSheet.asStateFlow()

    fun openRegistrationSheet() {
        _showRegistrationSheet.value = true
    }

    fun dismissRegistrationSheet() {
        _showRegistrationSheet.value = false
    }

    fun sendVerificationCode(email: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { authClient.sendVerificationCode(email).also(onResult) }
    }

    fun verifyCode(email: String, code: String, onResult: (Result<*>) -> Unit) {
        viewModelScope.launch {
            val result = authClient.verifyCode(email, code)
            result.onSuccess { auth ->
                userSettingsRepository.updateServerAuth(auth.token, email)
                _showRegistrationSheet.value = false
            }
            onResult(result)
        }
    }

    private val _threads = MutableStateFlow<List<ChatThreadUi>>(emptyList())
    val threads: StateFlow<List<ChatThreadUi>> = _threads.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 过滤后的线程列表
     */
    val filteredThreads: StateFlow<List<ChatThreadUi>> = combine(
        _threads,
        _searchQuery
    ) { threads, query ->
        if (query.isBlank()) threads
        else threads.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastMessagePreview.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 冷启对账：修复缺失/孤儿文件、prune 终态行、重新约束 LRU 容量
        viewModelScope.launch { runCatching { chatImageStore.reconcileColdStart() } }
        // chat ReAct tool 双通道：collect ChatToolService.uiActions → 渲染搜索卡片/编辑跳转
        viewModelScope.launch {
            ChatToolService.getInstance().uiActions.collect { action ->
                when (action) {
                    is AgentAction.MediaResults -> {
                        val sid = _currentSessionId.value
                        val assets = lastResultAssets[sid].orEmpty()
                            .filter { it.id in action.mediaIds }
                            .take(MAX_CARDS)
                        if (assets.isNotEmpty()) {
                            // 同一用户回合多轮搜索由 insertMediaResultsMessage 内部 upsert 去重，仅保留最新卡片
                            insertMediaResultsMessage(
                                sid,
                                MediaResultsUi(
                                    query = action.query,
                                    assets = assets,
                                    totalCount = action.totalCount,
                                    isRefinement = action.isRefinement
                                )
                            )
                        }
                    }
                    is AgentAction.Success -> {
                        when (action.command) {
                            is AgentCommand.AiOptimize -> {
                                handleAgentAction(action, _currentSessionId.value, currentModelLabel())
                            }
                            is AgentCommand.EditImage -> {
                                handleAgentAction(action, _currentSessionId.value, currentModelLabel())
                            }
                            else -> {}
                        }
                    }
                    else -> {}
                }
            }
        }
        // adjust_image handler：ChatToolService → ChatImageRenderer.adjustImage → chat 内渲染
        ChatToolService.getInstance().adjustImageHandler = { uri, brightness, contrast, saturation, temperature ->
            val renderer = chatImageRenderer
            if (renderer == null) {
                stringContext().getString(R.string.chat_optimize_unavailable)
            } else {
                val sid = _currentSessionId.value
                val outcome = renderer.adjustImage(uri, brightness, contrast, saturation, temperature, sid)
                Logger.i(TAG, "adjustImage outcome: imageUri=${outcome.imageUri}, explanation=${outcome.explanation}")
                if (outcome.imageUri != null) {
                    insertAgentImageMessage(
                        sessionId = sid,
                        imageUri = outcome.imageUri,
                        content = outcome.explanation,
                        modelUsed = currentModelLabel()
                    )
                    outcome.explanation
                } else {
                    outcome.explanation
                }
            }
        }
        // chat 页仅远程：模型选择固定为 Remote（端侧文本 LLM 已移除）
        _currentModel.value = ChatModelOption.Remote
        // 实时监听用户自配 Key：决定是否显示「默认服务器/自配 Key」切换（配 key 后即时刷新）
        viewModelScope.launch {
            // 首次加载时跟随设置中心的选中模型：否则 chat 恒默认官方源，
            // 用户在设置里选了自配 Key 也不会生效（chat 选择是页内独立状态）。
            var restoredFromSettings = false
            try {
                userSettingsRepository.aiAgentRemoteModelConfigsFlow.collect { json ->
                    val userConfigs = RemoteModelConfigs.fromJson(json).configs.filter { cfg -> cfg.isConfigured }
                    val userModels = userConfigs.map { cfg -> ChatRemoteModel(cfg.uniqueKey, cfg.modelId, cfg) }
                    _availableModels.value = listOf(officialModel) + userModels
                    _hasUserKey.value = userModels.isNotEmpty()
                    if (!restoredFromSettings) {
                        restoredFromSettings = true
                        val settingsSelected = userSettingsRepository.aiAgentSelectedRemoteModelFlow.first()
                        if (userModels.any { it.id == settingsSelected }) {
                            _selectedModelId.value = settingsSelected
                            Logger.i(TAG, "chat model restored from settings: $settingsSelected")
                        }
                    }
                    Logger.i(
                        TAG,
                        "availableModels: official + ${userModels.size} user = ${userModels.map { it.displayName }}"
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to observe remote model configs", e)
            }
        }
        // 从 DataStore 恢复上次选中的会话 ID；校验该会话是否仍存在，不存在则回退 default
        restoreLastSessionId()
        loadMessages()
        loadThreads()
    }

    private fun restoreLastSessionId() {
        viewModelScope.launch {
            try {
                val savedId = userSettingsRepository.chatCurrentSessionIdFlow.first()
                if (savedId.isNotBlank() && savedId != "default") {
                    // 校验会话是否仍存在于数据库（可能已被删除）
                    val exists = chatSessionDao.getSession(savedId) != null
                    val target = if (exists) savedId else "default"
                    if (!exists) {
                        // 会话已被删除，同步修正 DataStore
                        userSettingsRepository.updateChatCurrentSessionId("default")
                    }
                    _currentSessionId.value = target
                    Logger.i(TAG, "Restored last session: $target (saved=$savedId, exists=$exists)")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to restore last session id", e)
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            try {
                _currentSessionId
                    .flatMapLatest { sessionId ->
                        chatMessageDao.getMessagesBySession(sessionId)
                    }
                    .collect { entities ->
                        val uiMessages = entities.map { e ->
                            val ui = e.toUiModel()
                            val deliver = claudeDeliverOverrides[ui.id]
                            ui
                                .let { if (deliver != null) it.copy(claudeDeliver = deliver) else it }
                        }
                        _messages.value = uiMessages
                        // 合并语义（非整体重置）：Room 表级 invalidation 重发不能冲掉活动任务的
                        // live entry——同 taskId 取 updatedAtMs 较大者，内存独有 entry 保留。
                        val loadedTasks = uiMessages
                            .mapNotNull { msg -> msg.engineerTask?.let { task -> task.taskId to task } }
                            .toMap()
                        _engineerTasks.update { current ->
                            val merged = loadedTasks.toMutableMap()
                            current.forEach { (taskId, live) ->
                                val fromRoom = merged[taskId]
                                if (fromRoom == null || live.updatedAtMs >= fromRoom.updatedAtMs) {
                                    merged[taskId] = live
                                }
                            }
                            merged
                        }
                        // 回填仍处 pending 的卡条选中态（controller 内存态存活于 ViewModel 重建，选中态不存活）
                        val restored = entities
                            .filter { it.type == OptimizeCandidateGroup.MESSAGE_TYPE }
                            .filter { optimizeGachaController?.hasPending(it.id) == true }
                            .mapNotNull { entity ->
                                OptimizeCandidateGroup.fromJson(entity.metadata)?.let { entity.id to it.recommendedIndex }
                            }
                            .toMap()
                        if (restored.isNotEmpty()) {
                            _gachaSelections.value = _gachaSelections.value + restored
                        }
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load messages", e)
            }
        }
    }

    private fun loadThreads() {
        viewModelScope.launch {
            try {
                chatSessionDao.getAllSessions()
                    .collect { sessions ->
                        val threads = sessions.map { session ->
                            val lastMessage = chatMessageDao.getLastMessageForSession(session.sessionId)
                            ChatThreadUi(
                                sessionId = session.sessionId,
                                title = resolveThreadTitle(session),
                                lastMessagePreview = lastMessage?.content?.take(MAX_PREVIEW_LENGTH) ?: "",
                                updatedAt = session.updatedAt,
                                isSelected = session.sessionId == _currentSessionId.value
                            )
                        }
                        _threads.value = threads
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load threads", e)
            }
        }
    }

    private fun resolveThreadTitle(session: ChatSessionEntity): String {
        return when {
            session.sessionId == "default" && session.title == "default" -> stringContext().getString(R.string.new_chat)
            session.sessionId == "feishu" -> stringContext().getString(R.string.chat_thread_feishu)
            session.title.isBlank() -> stringContext().getString(R.string.new_chat)
            else -> session.title
        }
    }

    /**
     * 切换当前会话
     */
    fun switchSession(sessionId: String) {
        // 先按旧会话废弃 pending 卡条：launch 体在 _currentSessionId 更新后才可能执行，
        // 必须先把旧 id 捕获下来，否则会误废弃新会话的卡条
        val previousSessionId = _currentSessionId.value
        viewModelScope.launch { discardPendingOptimizeGacha(previousSessionId) }
        _currentSessionId.value = sessionId
        Logger.i(TAG, "Switched to session: $sessionId")
        viewModelScope.launch {
            userSettingsRepository.updateChatCurrentSessionId(sessionId)
        }
    }

    /**
     * 创建新会话并切换过去
     */
    fun newSession() {
        val sessionId = UUID.randomUUID().toString()
        viewModelScope.launch {
            discardPendingOptimizeGacha()
            try {
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        sessionId = sessionId,
                        title = "New Chat"
                    )
                )
                _currentSessionId.value = sessionId
                Logger.i(TAG, "Created new session: $sessionId")
                userSettingsRepository.updateChatCurrentSessionId(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create session", e)
            }
        }
    }

    /**
     * 重命名会话
     */
    fun renameSession(sessionId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            try {
                val trimmed = newTitle.trim()
                chatSessionDao.updateTitle(sessionId, trimmed)
                Logger.i(TAG, "Renamed session $sessionId to: $trimmed")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to rename session", e)
            }
        }
    }

    /**
     * 删除会话及其消息；如果删除的是当前会话，切回 default
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                optimizeGachaController?.discardPending(sessionId)
                chatImageStore.evictForSession(sessionId)
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.deleteSession(sessionId)
                // 会话级内存缓存同步清理，避免已删会话的搜索结果/快照/排除集残留
                lastResultAssets.remove(sessionId)
                pendingScriptMediaIds.remove(sessionId)
                sessionSearchSnapshots.remove(sessionId)
                sessionExcludes.remove(sessionId)
                // 选中态是纯 UI 内存态，会话删除后整体清理，回退到推荐卡高亮即可
                _gachaSelections.value = emptyMap()
                // 删除的是工程师上下文所属会话 → 清掉持久化记录，避免 prefs 残留
                if (claudeSidStore?.load()?.first == sessionId) claudeSidStore?.clear()
                if (claudeSidOwner == sessionId) {
                    claudeSid = null
                    claudeSidOwner = null
                }
                if (_currentSessionId.value == sessionId) {
                    _currentSessionId.value = "default"
                    userSettingsRepository.updateChatCurrentSessionId("default")
                }
                Logger.i(TAG, "Deleted session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete session", e)
            }
        }
    }

    /**
     * 把指定编辑/优化结果消息保存进相册。成功后消息 imageUri 重指向 content://，UI 经 Flow 自动刷新。
     * @param onResult 成功/失败回调，供 UI 切换按钮状态 / 提示。
     */
    fun saveEditResult(messageId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = saveChatEditResultUseCase.execute(messageId)
            if (res.isFailure) Logger.w(TAG, "saveEditResult failed: ${res.exceptionOrNull()}")
            onResult(res.isSuccess)
        }
    }

    /** 打开编辑结果预览时刷新 LRU recency（仅对私有 file:// 路径有意义）。 */
    fun touchEditImage(imageUri: String?) {
        if (imageUri == null || !imageUri.startsWith("file://")) return
        val path = imageUri.removePrefix("file://")
        viewModelScope.launch { runCatching { chatImageStore.touch(path) } }
    }

    /**
     * 更新搜索关键字（在内存中过滤线程列表）
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * 发送用户消息，通过 Agent 编排器执行命令或返回闲聊回复
     *
     * 流程：
     * 1. 保存用户消息到 Room
     * 2. 触发处理状态
     * 3. 创建流式占位消息，实时展示 token
     * 4. 获取相册摘要
     * 5. 构建 Agent 上下文
     * 6. 调用 [AgentOrchestrator.streamChat] 流式推理
     * 7. 推理完成后保存完整结果到 Room
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // 待重构：sendMessage 按阶段拆分为 send/parse/persist
    fun sendMessage(text: String, imageUri: String? = null) {
        if (text.isBlank()) return

        // [DEV_ONLY] 调试指令：/html 注入 HTML 卡片冒烟测试集（样本清单见 HtmlCardSmokeSamples
        // KDoc，恶意用例故意绕过清洗器直插以专测 WebView 锁死层），不走 LLM
        if (BuildConfig.DEBUG && text.trim() == "/html") {
            viewModelScope.launch {
                ensureSessionExists(_currentSessionId.value)
                HtmlCardSmokeSamples.all.forEach { sample -> emitHtmlCardMessage(sample) }
            }
            return
        }

        // [DEV_ONLY] 调试指令：/task 注入任务卡五态冒烟集，不走 LLM
        if (BuildConfig.DEBUG && text.trim() == "/task") {
            viewModelScope.launch {
                val sessionId = _currentSessionId.value
                ensureSessionExists(sessionId)
                EngineerTaskSmokeSamples.all(System.currentTimeMillis()).forEach { sample ->
                    _engineerTasks.update { tasks -> tasks + (sample.taskId to sample) }
                    engineerTaskPersistMutex.withLock { persistEngineerTask(sessionId, sample) }
                }
            }
            return
        }

        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            replyUsedSandbox = false
            // 新回合开始：清掉上一回合可能残留的脚本待补卡 ids（防跨回合泄漏）
            pendingScriptMediaIds.remove(sessionId)
            // 用户发新消息即放弃未确认的抽卡（落库 dismiss）
            discardPendingOptimizeGacha()
            try {
                // 0. 确保会话元数据存在
                ensureSessionExists(sessionId)

                // 携带图片时，把图片 uri 作为上下文并写入 metadata，供 UI 图文混排展示
                if (imageUri != null) {
                    _lastUserImageUri.value = imageUri
                }

                // 1. 保存用户消息
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = if (imageUri != null) "user_image_text" else "user_text",
                    content = text,
                    modelUsed = null,
                    metadata = imageUri?.let { """{"imageUri":"$it"}""" }
                )
                chatMessageDao.insertMessage(userMessage)
                chatSessionDao.touchSession(sessionId)

                // 1.2 访客渐进引导：仅未注册时计数；恰好跨阈值当次插入提示消息并弹出双选项引导（>阈值不再弹）
                if (isGuestMode.value) {
                    val guestCount = userSettingsRepository.incrementGuestChatMessageCount()
                    _guestMessageCount.value = guestCount
                    if (guestCount == GUEST_REGISTER_NUDGE_THRESHOLD) {
                        insertAgentMessage(
                            sessionId = sessionId,
                            content = stringContext().getString(
                                R.string.chat_guest_threshold_message,
                                GUEST_REGISTER_NUDGE_THRESHOLD
                            ),
                            modelUsed = currentModelLabel(),
                        )
                        _showRegistrationSheet.value = true
                    }
                }

                // 1.5 自动命名：根据用户的第一条消息生成会话标题
                val messageCount = chatMessageDao.getMessageCount(sessionId)
                if (messageCount == 1) {
                    updateSessionTitleIfDefault(sessionId, generateAutoTitle(userMessage))
                }

                // 2. 触发处理状态
                _isProcessing.value = true

                // 3. 创建流式占位消息（立即展示「思考中」提示，避免空气泡）
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = stringContext().getString(STREAMING_THINKING_HINT_RES),
                    modelUsed = currentModelLabel(),
                    isStreaming = true,
                    isThinking = true
                )
                pacingController.start()

                // 3.5 获取相册摘要并注入上下文
                val gallerySummary = getGallerySummaryUseCase(includeDetails = false)

                // 4. 构建 Agent 上下文（性格/回复语言每次发消息时读取，设置变更下条消息即生效）
                val assistantPersona = userSettingsRepository.assistantPersonaFlow.first()
                val replyLanguage = userSettingsRepository.appLanguageFlow.first()
                    .toReplyLanguage(Locale.getDefault().toLanguageTag())
                val agentContext = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    recentSearchResults = sessionSearchSnapshots[sessionId].orEmpty(),
                    lastUserImageUri = _lastUserImageUri.value,
                    gallerySummary = gallerySummary,
                    traceId = java.util.UUID.randomUUID().toString(),
                    persona = assistantPersona,
                    replyLanguage = replyLanguage
                )

                // 5. 调用流式推理
                //
                // 流式期间占位消息内容实时更新（只走 _streamingMessage 内存轨，不落 Room）：
                // - TextSnapshot：模型本轮累计全文快照，直接整体替换气泡内容
                //   （AGENT_TEXT 经 MarkdownText 渲染，天然支持增量 Markdown）。
                // - ToolCallStarted：进入工具调用轮，气泡切换为"正在调用工具"状态文案；
                //   新一轮首个 delta 到达时快照从空重新累计，自动覆盖状态文案。
                // chat 推理前同步配置 remoteConfig：确保用当前 _remoteSource 对应的远程源，
                // 避免其他场景（AiAgentUseCase/PoLangApplication）注入的 userRemoteConfig 残留导致走错服务器。
                orchestrator.updateRemoteRuntimeConfig(
                    remoteConfig = effectiveRemoteConfig(selectedModel),
                    privacyLevel = AiAgentPrivacyLevel.STRICT
                )
                Logger.i(
                    TAG,
                    "chat inference: model=${selectedModel.displayName}, baseUrl=${selectedModel.remoteConfig.baseUrl}"
                )
                // 用户选了图片时，把 URI 注入 input 让 ReAct LLM 知道（ai_optimize 需 image_uri）
                val effectiveInput = if (imageUri != null) {
                    "[用户选择了图片：$imageUri，请基于这张图片处理] $text"
                } else {
                    text
                }
                val result = orchestrator.remoteChatEngine.streamChat(
                    input = effectiveInput,
                    agentContext = agentContext,
                    onEvent = { event ->
                        when (event) {
                            is ChatStreamEvent.TextSnapshot -> {
                                pacingController.onTextSnapshot(event.text)
                                if (_streamingMessage.value?.isThinking == true) {
                                    _streamingMessage.value = _streamingMessage.value?.copy(isThinking = false)
                                }
                            }
                            ChatStreamEvent.ToolCallStarted -> {
                                pacingController.reset()
                                _streamingMessage.value = _streamingMessage.value?.copy(
                                    content = stringContext().getString(R.string.chat_calling_tool),
                                    showCursor = false,
                                    isThinking = false
                                )
                            }
                        }
                    }
                )

                // 流式已结束（streamChat 返回 = onCompleteResponse 已触发）：节奏器追平收尾
                pacingController.finish()

                // 6. 处理结果
                result.fold(
                    onSuccess = { streamResult ->
                        // 清除流式占位
                        _streamingMessage.value = null

                        // 回合终态护栏 a/c（spec §3.5，意图路由 M1）：脚本路径 return 含 ids
                        // 而本回合未出横滑卡片 → 端侧水合 ids 直接补卡 + 收口 refine 搜索基数。
                        // 须在误拒回退检测之前执行：补上的卡片让后续 getLatestMediaResultsSinceLastUserMessage
                        // 判定一致（避免重复卡）。
                        settleScriptMediaIds(sessionId, text)

                        // 性能数据统一在此计算并透传给所有回复路径（文本/命令），
                        // 让 remote(DeepSeek) 响应气泡也展示 prompt/decode tokens、延迟、速度。
                        // 此前仅纯文本路径填 performance，命令路径（remote ReAct 常走）传 null。
                        val performance = streamResult.metrics?.let { metrics ->
                            LlmPerformance(
                                promptLen = metrics.promptTokens ?: 0,
                                decodeLen = metrics.completionTokens ?: 0,
                                prefillTimeMs = 0,
                                decodeTimeMs = metrics.latencyMs,
                                prefillSpeed = 0f,
                                decodeSpeed = if (metrics.latencyMs > 0 && (metrics.completionTokens ?: 0) > 0)
                                    (metrics.completionTokens!!.toFloat() / metrics.latencyMs * 1000) else 0f,
                                usedSandbox = replyUsedSandbox
                            )
                        }

                        // 检测 LLM 安全对齐误触发：用户想搜相册但 LLM 拒绝了
                        val replyText = (streamResult.commands.firstOrNull() as? AgentCommand.TextReply)?.message
                            ?: streamResult.fullResponse
                        val directReply = streamResult.directReply
                        if (directReply != null) {
                            // 意图路由直执回合：用户气泡由平台层本地化渲染（模型侧 observation
                            // 是硬编码中文模板，不可直达用户——I18N 红线）；卡片已经 uiActions 渲染
                            insertAgentMessage(
                                sessionId,
                                if (directReply.totalCount > 0) {
                                    stringContext().getString(R.string.chat_direct_results_shown)
                                } else {
                                    stringContext().getString(R.string.gallery_search_no_results)
                                },
                                currentModelLabel(),
                                performance
                            )
                        } else if (IntentGuard.isRefusedSearchRequest(text, replyText) &&
                            chatMessageDao.getLatestMediaResultsSinceLastUserMessage(sessionId) == null
                        ) {
                            Logger.w(TAG, "LLM refused search request, falling back to direct gallery search")
                            val outcome = onSearchMedia(text)
                            val assets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
                            if (assets.isNotEmpty()) {
                                insertMediaResultsMessage(
                                    sessionId,
                                    MediaResultsUi(outcome.query, assets, outcome.totalCount, isRefinement = false)
                                )
                            } else {
                                insertAgentMessage(
                                    sessionId,
                                    stringContext().getString(R.string.gallery_search_no_results),
                                    currentModelLabel()
                                )
                            }
                        } else if (streamResult.commands.isNotEmpty()) {
                            // 有命令需要执行：通过 CapabilityRegistry 分发
                            Logger.i(TAG, "Executing ${streamResult.commands.size} commands from streaming response")
                            // 聊天页拦截模糊跳转：只有明确说"去相机/去相册/去设置/返回"等口令时才放行
                            val commands = IntentGuard.sanitizeNavigationCommands(
                                streamResult.commands,
                                text,
                                blockedMessage = stringContext().getString(R.string.chat_nav_blocked_in_chat)
                            )
                            val finalCommand = if (commands.size > 1) {
                                AgentCommand.BatchExecute(commands = commands)
                            } else {
                                commands.first()
                            }
                            val action = orchestrator.getCapabilityRegistry()
                                .dispatch(finalCommand, agentContext)
                            val actionValue = action.getOrNull()
                            if (actionValue is AgentAction.Error) {
                                // 聊天页命令分发失败时，优先展示模型原始回复，避免把"暂不支持此操作"抛给用户
                                Logger.w(TAG, "Capability dispatch failed in chat, falling back to full response. error=${actionValue.message}, detail=${actionValue.detail}")
                                insertAgentMessage(sessionId, streamResult.fullResponse.ifBlank { actionValue.message }, currentModelLabel(), performance)
                            } else {
                                handleAgentAction(actionValue, sessionId, currentModelLabel(), performance)
                            }
                        } else {
                            // 纯文本回复：保存到 Room（REMOTE 场景或 LOCAL 的 text_reply）
                            insertAgentMessage(
                                sessionId = sessionId,
                                content = streamResult.fullResponse,
                                modelUsed = currentModelLabel(),
                                performance = performance
                            )
                        }
                    },
                    onFailure = { error ->
                        // 清除流式占位
                        _streamingMessage.value = null
                        // langchain4j 异常 message = HTTP 响应体（OkHttpClient→HttpException→AuthenticationException 全程透传，状态码不进 message）。
                        // guest 配额耗尽时 server 返回 403 body={"error":"quota_exceeded",...}（见 LlmRoute），据此识别。
                        val errorBody = error.message.orEmpty()
                        val isGuestQuota = isGuestMode.value &&
                            errorBody.contains("quota_exceeded", ignoreCase = true)
                        if (isGuestQuota) {
                            // 访客试用额度用完 → 友好提示 + 打开注册引导（软引导，非硬阻断）
                            insertAgentMessage(
                                sessionId = sessionId,
                                content = stringContext().getString(R.string.chat_guest_quota_used_up),
                                modelUsed = currentModelLabel(),
                            )
                            _showRegistrationSheet.value = true
                        } else {
                            insertAgentMessage(
                                sessionId = sessionId,
                                content = stringContext().getString(
                                    R.string.chat_inference_error,
                                    error.message ?: stringContext().getString(R.string.chat_unknown_error),
                                ),
                                modelUsed = "error",
                            )
                        }
                    }
                )

                // 7. 清理超限消息
                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send message", e)
                _streamingMessage.value = null
                // 保存错误提示
                val errorMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "agent_text",
                    content = stringContext().getString(
                        R.string.chat_inference_error,
                        e.message ?: stringContext().getString(R.string.chat_unknown_error),
                    ),
                    modelUsed = "error"
                )
                chatMessageDao.insertMessage(errorMessage)
                chatSessionDao.touchSession(sessionId)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun currentModelLabel(): String {
        return when (_currentModel.value) {
            is ChatModelOption.Remote -> "remote_deepseek"
        }
    }

    /**
     * 将 AgentAction 渲染为聊天消息
     */
    @Suppress("LongMethod", "NestedBlockDepth") // 待重构：handleAgentAction 按 Action 类型分发
    private suspend fun handleAgentAction(
        action: AgentAction?,
        sessionId: String,
        modelLabel: String,
        performance: LlmPerformance? = null
    ) {
        when (action) {
            is AgentAction.TextReply -> {
                insertAgentMessage(sessionId, action.message, modelLabel, performance)
            }
            is AgentAction.MediaResults -> {
                val assets = lastResultAssets[sessionId].orEmpty()
                    .filter { it.id in action.mediaIds }
                    .take(MAX_CARDS)
                insertMediaResultsMessage(
                    sessionId,
                    MediaResultsUi(
                        query = action.query,
                        assets = assets,
                        totalCount = action.totalCount,
                        isRefinement = action.isRefinement
                    )
                )
            }
            is AgentAction.Success -> {
                when (val cmd = action.command) {
                    is AgentCommand.AiOptimize -> {
                        val targetUri = cmd.imageUri.takeIf { it.isNotBlank() }
                            ?: _lastUserImageUri.value
                        if (targetUri.isNullOrBlank()) {
                            insertAgentMessage(
                                sessionId,
                                stringContext().getString(R.string.chat_ai_optimize_need_image),
                                currentModelLabel(),
                                performance
                            )
                        } else {
                            handleAiOptimize(sessionId, targetUri, cmd.explanation, currentModelLabel(), performance)
                        }
                    }
                    is AgentCommand.EditImage -> {
                        val outputUri = cmd.imageUri
                        val explanation = cmd.explanation
                            ?: stringContext().getString(R.string.chat_edit_result_default)
                        insertEditResultMessage(sessionId, outputUri, explanation, currentModelLabel(), performance)
                    }
                    else -> {
                        insertAgentMessage(sessionId, describeCommandResult(cmd), "command", performance)
                    }
                }
            }
            is AgentAction.Error -> {
                val message = if (action.message == "feedback_resolve_failure") {
                    stringContext().getString(R.string.feedback_resolve_failure)
                } else {
                    action.message
                }
                insertAgentMessage(sessionId, "❌ $message", "error", performance)
            }
            is AgentAction.BatchResult -> {
                // 取词 context 提到循环外复用：逐命令重读语言设置会在主线程累积 runBlocking 开销
                val strings = stringContext()
                val summary = action.results.joinToString("\n") { subAction ->
                    when (subAction) {
                        is AgentAction.Success -> describeCommandResult(subAction.command, strings)
                        is AgentAction.Error -> "❌ ${subAction.message}"
                        is AgentAction.TextReply -> subAction.message
                        else -> ""
                    }
                }
                insertAgentMessage(sessionId, summary.ifBlank { strings.getString(R.string.chat_batch_done) }, "command", performance)
            }
            null -> {
                insertAgentMessage(sessionId, stringContext().getString(R.string.chat_no_execution_result), "error", performance)
            }
        }
    }

    /**
     * AI 优化：抽卡闭环（候选卡组消息）；控制器未注入时退回旧单发路径。
     * spec: 2026-08-06 chat-optimize-gacha 设计稿（已随交付清理，git 历史可查）
     */
    private suspend fun handleAiOptimize(
        sessionId: String,
        targetUri: String,
        explanationOverride: String?,
        modelUsed: String,
        performance: LlmPerformance?
    ) {
        val controller = optimizeGachaController
        if (controller == null) {
            legacyAiOptimize(sessionId, targetUri, explanationOverride, modelUsed, performance)
            return
        }
        val messageId = UUID.randomUUID().toString()
        when (val outcome = controller.draw(messageId, targetUri, sessionId)) {
            is ChatOptimizeGachaController.DrawOutcome.Candidates -> {
                insertOptimizeCandidatesMessage(
                    sessionId = sessionId,
                    messageId = messageId,
                    group = outcome.group,
                    content = explanationOverride ?: outcome.explanation,
                    modelUsed = modelUsed
                )
            }
            is ChatOptimizeGachaController.DrawOutcome.Fallback -> {
                if (outcome.imageUri != null) {
                    insertAgentImageMessage(
                        sessionId = sessionId,
                        imageUri = outcome.imageUri,
                        content = explanationOverride ?: outcome.explanation,
                        modelUsed = modelUsed,
                        performance = performance
                    )
                } else {
                    insertAgentMessage(sessionId, outcome.explanation, modelUsed, performance)
                }
            }
        }
    }

    /** 抽卡控制器未注入时的旧单发路径（与抽卡接入前行为一致）。 */
    private suspend fun legacyAiOptimize(
        sessionId: String,
        targetUri: String,
        explanationOverride: String?,
        modelUsed: String,
        performance: LlmPerformance?
    ) {
        val renderer = chatImageRenderer
        if (renderer == null) {
            insertAgentMessage(sessionId, stringContext().getString(R.string.chat_optimize_unavailable), modelUsed, performance)
            return
        }
        val outcome = renderer.aiOptimize(targetUri, sessionId)
        Logger.i(TAG, "AiOptimize outcome (legacy): imageUri=${outcome.imageUri}, explanation=${outcome.explanation}")
        if (outcome.imageUri != null) {
            insertAgentImageMessage(sessionId, outcome.imageUri, explanationOverride ?: outcome.explanation, modelUsed, performance)
        } else {
            insertAgentMessage(sessionId, outcome.explanation, modelUsed, performance)
        }
    }

    /**
     * 把命令执行结果转成用户友好的自然语言。
     *
     * [strings] 取词 context（默认按当前 App 语言解析）；批量场景（BatchResult 逐命令汇总）
     * 由调用方提到循环外复用，避免每条命令重复 runBlocking 读语言设置。
     */
    private fun describeCommandResult(command: AgentCommand, strings: Context = stringContext()): String {
        return when (command) {
            is AgentCommand.NavigateTo -> strings.getString(R.string.chat_result_navigated_to, command.destination)
            is AgentCommand.GoBack -> strings.getString(R.string.chat_result_went_back)
            is AgentCommand.LaunchApp -> {
                val target = command.appName ?: command.packageName
                    ?: strings.getString(R.string.chat_result_app_fallback)
                strings.getString(R.string.chat_result_opened_app, target)
            }
            is AgentCommand.OpenSystemSettings -> strings.getString(R.string.chat_result_opened_settings, command.setting)
            is AgentCommand.AiOptimize -> command.explanation?.let { explanation -> "✅ $explanation" }
                ?: strings.getString(R.string.chat_result_ai_optimize_done)
            is AgentCommand.StartTagScan -> strings.getString(R.string.chat_result_tag_scan_done)
            is AgentCommand.BatchExecute -> strings.getString(R.string.chat_result_batch_executed)
            is AgentCommand.RecordMediaFeedback -> when (command.action) {
                FeedbackAction.LIKE -> "✅ ${strings.getString(R.string.feedback_confirmed_like)}"
                FeedbackAction.DISLIKE -> "✅ ${strings.getString(R.string.feedback_confirmed_dislike)}"
                else -> strings.getString(R.string.chat_result_feedback_recorded)
            }
            is AgentCommand.ExcludeConstraint -> "✅ ${strings.getString(R.string.feedback_excluded, command.constraint)}"
            else -> strings.getString(R.string.chat_result_executed, AgentCommand.getMethodName(command))
        }
    }

    /**
     * 用户点击搜索结果卡片上的反馈按钮。
     */
    fun onMediaFeedback(mediaId: String, query: String, action: FeedbackAction) {
        val key = "$mediaId-$query-${action.name}"
        if (pendingFeedbackActions.contains(key)) return
        pendingFeedbackActions.add(key)

        viewModelScope.launch {
            try {
                when (action) {
                    FeedbackAction.LIKE, FeedbackAction.DISLIKE -> {
                        mediaFeedbackUseCase.record(
                            mediaId = mediaId,
                            queryText = query,
                            sessionId = _currentSessionId.value,
                            action = action
                        )
                        updateCurrentResultsFeedback(mediaId, action, query)
                    }
                    FeedbackAction.MORE_LIKE_THIS -> {
                        triggerMoreLikeThis(mediaId, query)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to record media feedback", e)
            } finally {
                pendingFeedbackActions.remove(key)
            }
        }
    }

    private suspend fun updateCurrentResultsFeedback(mediaId: String, action: FeedbackAction, query: String) {
        val currentMessages = _messages.value
        val updatedMessages = currentMessages.map { message ->
            val mr = message.mediaResults
            if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null && mr.query == query) {
                val updatedState = mr.feedbackState.toMutableMap().apply {
                    when (action) {
                        FeedbackAction.LIKE -> put(mediaId, FeedbackAction.LIKE)
                        FeedbackAction.DISLIKE -> put(mediaId, FeedbackAction.DISLIKE)
                        else -> { /* no-op */ }
                    }
                }
                val reorderedAssets = reorderAssetsByFeedback(mr.assets, updatedState, query)
                message.copy(
                    mediaResults = mr.copy(
                        assets = reorderedAssets,
                        feedbackState = updatedState
                    )
                )
            } else {
                message
            }
        }
        _messages.value = updatedMessages
    }

    private suspend fun reorderAssetsByFeedback(
        assets: List<MediaAsset>,
        feedbackState: Map<String, FeedbackAction>,
        query: String
    ): List<MediaAsset> {
        val scores = mediaFeedbackUseCase.getScoresForQuery(query)
        return assets.sortedByDescending { asset ->
            val score = scores[asset.id.toString()]
            val delta = mediaFeedbackUseCase.calculateScoreDelta(score)
            val baseIndex = assets.indexOf(asset)
            val baseScore = (assets.size - baseIndex).toFloat()
            baseScore + delta * 100f
        }
    }

    private suspend fun triggerMoreLikeThis(mediaId: String, query: String) {
        val sessionId = _currentSessionId.value
        val asset = lastResultAssets[sessionId]?.find { it.id.toString() == mediaId }
            ?: return
        val tags = asset.labels?.let { parseLabels(it) }?.take(3) ?: emptyList()
        val constraint = if (tags.isNotEmpty()) {
            stringContext().getString(R.string.chat_more_like_this_with_tags, tags.joinToString("、"))
        } else {
            stringContext().getString(R.string.chat_more_like_this)
        }
        val outcome = onRefineMediaSearch(constraint)
        if (outcome.mediaIds.isNotEmpty()) {
            val refinedAssets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
            insertMediaResultsMessage(
                sessionId,
                MediaResultsUi(
                    query = constraint,
                    assets = refinedAssets,
                    totalCount = outcome.totalCount,
                    isRefinement = true
                )
            )
        } else {
            insertAgentMessage(
                sessionId,
                stringContext().getString(R.string.feedback_no_more_results),
                "gallery_search"
            )
        }
    }

    private fun parseLabels(labelsJson: String): List<String> {
        return try {
            val json = org.json.JSONObject(labelsJson)
            val tags = json.optJSONArray("tags")
            (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @VisibleForTesting
    internal fun resolveTarget(target: FeedbackTarget, sessionId: String? = null): MediaAsset? {
        val sid = sessionId ?: _currentSessionId.value
        val assets = lastResultAssets[sid].orEmpty()
        if (assets.isEmpty()) return null
        return when (target) {
            is FeedbackTarget.LastShown -> assets.firstOrNull()
            is FeedbackTarget.Ordinal -> assets.getOrNull((target.index - 1).coerceAtLeast(0))
            is FeedbackTarget.MediaId -> assets.find { it.id.toString() == target.id }
            is FeedbackTarget.Description -> assets.find { matchesTags(it, target.text) }
        }
    }

    private fun matchesTags(asset: MediaAsset, description: String): Boolean {
        val labels = asset.labels?.let { parseLabels(it) } ?: emptyList()
        val terms = description.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return false
        return terms.any { term ->
            labels.any { label -> label.contains(term, ignoreCase = true) } ||
                asset.fileName.contains(term, ignoreCase = true)
        }
    }

    // ── ChatSearchCapability.Delegate：相册搜索执行 ────────────────

    override suspend fun onSearchMedia(query: String, intent: SearchIntent?): SearchOutcome {
        val sessionId = _currentSessionId.value
        val start = System.currentTimeMillis()
        val result = runCatching {
            if (intent != null) {
                val filter = searchIntentToStructuredFilter(intent)
                mediaSearchEngine.search(filter = filter)
            } else {
                mediaSearchEngine.search(query)
            }
        }.getOrElse {
            Logger.w(TAG, "onSearchMedia failed for '$query'", it)
            return SearchOutcome(query, emptyList(), 0, isRefinement = false)
        }
        Logger.i(TAG, "onSearchMedia query='$query' intent=$intent total=${result.media.size} time=${System.currentTimeMillis() - start}ms")
        val photos = result.media.filter { it.type == MediaType.PHOTO }
        lastResultAssets[sessionId] = photos
        recordSearchSnapshot(sessionId, query, photos.size, isRefinement = false)
        return SearchOutcome(query, photos.map { it.id }, photos.size, isRefinement = false)
    }

    override suspend fun onRefineMediaSearch(constraint: String, intent: SearchIntent?): SearchOutcome {
        val sessionId = _currentSessionId.value
        val prior = lastResultAssets[sessionId].orEmpty()
        // 回合护栏 c（spec §3.5-c）：无搜索基数时返回明确错误引导模型改用 search_media，
        // 不再静默退化为全局重搜（那会用与既有约束无关的新结果覆盖会话搜索状态，
        // 且观测文本与真实行为脱节，诱发幻觉链）。
        if (prior.isEmpty()) {
            Logger.w(TAG, "onRefineMediaSearch without base results, asking model to search fresh")
            return SearchOutcome(
                constraint, emptyList(), 0, isRefinement = true,
                errorMessage = "没有上一轮搜索结果可细化：请改用 search_media 重新全局搜索" +
                    "（人物/时间用 person/fromMs/toMs 结构化参数）"
            )
        }

        val start = System.currentTimeMillis()
        val priorIds = prior.map { asset -> asset.id }.toSet()
        val result = runCatching {
            if (intent != null) {
                // LLM 已给出标准化意图：直接在 prior 内执行结构化过滤
                val filter = searchIntentToStructuredFilter(intent)
                mediaSearchEngine.search(filter = filter, limitToIds = priorIds).media
            } else {
                // 兜底：字符串解析 + in-set 过滤
                val cleaned = ChatGallerySearch.cleanConstraint(constraint)
                val searchHits = mediaSearchEngine.search(cleaned, limitToIds = priorIds).media
                ChatGallerySearch.resolveRefine(prior, searchHits, cleaned)
            }
        }.getOrElse {
            Logger.w(TAG, "onRefineMediaSearch failed for '$constraint'", it)
            return SearchOutcome(constraint, emptyList(), 0, isRefinement = true)
        }

        val refined = result
        val faceInPrior = prior.count { a -> a.hasFace }
        Logger.i(
            TAG,
            "onRefineMediaSearch prior=${prior.size} hasFaceInPrior=$faceInPrior " +
                "constraint='$constraint' intent=$intent refined=${refined.size} " +
                "time=${System.currentTimeMillis() - start}ms"
        )
        // in-set 空 → 保留上一轮结果集不变，返回空细化结果。不再全局重搜 constraint：
        // 那会用与既有条件无关的新结果覆盖状态，破坏多轮收敛（用户会看到无关照片）。
        if (refined.isEmpty()) {
            return SearchOutcome(constraint, emptyList(), 0, isRefinement = true)
        }
        lastResultAssets[sessionId] = refined
        recordSearchSnapshot(sessionId, constraint, refined.size, isRefinement = true)
        return SearchOutcome(constraint, refined.map { it.id }, refined.size, isRefinement = true)
    }

    /**
     * 将 :shared 的 [SearchIntent] 转换为 app 层的 [StructuredFilter]。
     *
     * 转换前先做时间词清洗：只要 [SearchIntent.timeRange] 已给出，就把“夏天”“去年”等
     * 时间专属词从 keywords / ocrKeywords / locationKeywords 中剔除，避免引擎把
     * 时间约束与空内容候选集取交集导致 0 结果。
     */
    private fun searchIntentToStructuredFilter(intent: SearchIntent): StructuredFilter {
        val sanitized = sanitizeTimeKeywords(intent)
        return StructuredFilter(
            timeRange = sanitized.timeRange?.let {
                com.mamba.picme.domain.model.TimeRange(startMs = it.startMs, endMs = it.endMs)
            },
            keywords = sanitized.keywords,
            ocrKeywords = sanitized.ocrKeywords,
            locationKeywords = sanitized.locationKeywords,
            personName = sanitized.personName,
            hasFaces = sanitized.hasFaces,
            needsLlm = false
        )
    }

    /**
     * 当意图中同时存在 [timeRange] 和时间专属词时，剔除这些时间专属词。
     * 这是 Prompt 约束之外的第二层保险，防止小模型/远程模型仍把“夏天”当成内容关键词。
     */
    private fun sanitizeTimeKeywords(intent: SearchIntent): SearchIntent {
        if (intent.timeRange == null) return intent
        fun isTimeOnly(word: String): Boolean = word in timeOnlyKeywords || monthKeywordRegex.matches(word)
        return intent.copy(
            keywords = intent.keywords.filterNot(::isTimeOnly),
            ocrKeywords = intent.ocrKeywords.filterNot(::isTimeOnly),
            locationKeywords = intent.locationKeywords.filterNot(::isTimeOnly)
        )
    }

    private fun recordSearchSnapshot(
        sessionId: String,
        query: String,
        totalCount: Int,
        isRefinement: Boolean
    ) {
        val assets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
        if (assets.isEmpty()) return
        val snapshot = SearchSnapshotBuilder.build(assets, query, totalCount, isRefinement)
        val list = sessionSearchSnapshots.getOrPut(sessionId) { mutableListOf() }
        list.add(snapshot)
        if (list.size > SearchSnapshotBuilder.MAX_ROUNDS) {
            list.removeAt(0)
        }
    }

    override suspend fun onRecordMediaFeedback(target: FeedbackTarget, action: FeedbackAction): Boolean {
        val sessionId = _currentSessionId.value
        val asset = resolveTarget(target, sessionId) ?: return false
        val mediaId = asset.id.toString()
        val query = sessionSearchSnapshots[sessionId]?.lastOrNull()?.query ?: ""
        mediaFeedbackUseCase.record(
            mediaId = mediaId,
            queryText = query,
            sessionId = sessionId,
            action = action
        )
        updateCurrentResultsFeedback(mediaId, action, query)
        return true
    }

    override suspend fun onMoreLikeThis(target: FeedbackTarget): SearchOutcome {
        val sessionId = _currentSessionId.value
        val asset = resolveTarget(target, sessionId)
            ?: return SearchOutcome("", emptyList(), 0, isRefinement = false)
        val tags = asset.labels?.let { parseLabels(it) }?.take(3) ?: emptyList()
        val constraint = if (tags.isNotEmpty()) {
            stringContext().getString(R.string.chat_more_like_this_with_tags, tags.joinToString("、"))
        } else {
            stringContext().getString(R.string.chat_more_like_this)
        }
        return onRefineMediaSearch(constraint)
    }

    override suspend fun onExcludeConstraint(constraint: String): Boolean {
        if (constraint.isBlank()) return false
        val sessionId = _currentSessionId.value
        if (lastResultAssets[sessionId].isNullOrEmpty()) return false
        sessionExcludes.getOrPut(sessionId) { mutableSetOf() }.add(constraint)
        reapplyFiltersToCurrentResults(sessionId)
        mediaFeedbackUseCase.recordExclude(constraint, sessionId)
        return true
    }

    // ── ChatGallerySummaryCapability.Delegate：相册摘要 ─────────────

    override suspend fun onGetGallerySummary(includeDetails: Boolean): GallerySummary? {
        return getGallerySummaryUseCase(includeDetails)
    }

    // ── ChatRunScriptCapability.Delegate：执行 JS 脚本（端侧沙箱）─────────────

    override suspend fun onRunScript(code: String, traceId: String?): String {
        replyUsedSandbox = true
        return withContext(Dispatchers.Default) {
            val rt = getOrCreateJsRuntime()
            jsEvalMutex.withLock {
                // 含 capability.dispatch 的脚本会挂起等用户确认（最长 120s），放宽 eval 超时
                val evalTimeoutMs =
                    if (code.contains("capability.dispatch")) WRITE_EVAL_TIMEOUT_MS else DEFAULT_EVAL_TIMEOUT_MS
                // evalAsync 按「async 函数体」语义执行：顶层 return/await 合法；
                // 返回的 Promise 由引擎两段式 eval 解包（dokar3 不会自动解包顶层 Promise），
                // resolved value 作为结果，rejected 则抛出真实 JS 错误回传 LLM。
                writeConfirmationController.onScriptStarted()
                val result = try {
                    rt.evalAsync(code, evalTimeoutMs, traceId)
                } finally {
                    // 脚本结束（正常/超时/取消）：在途写确认一律拒绝——
                    // 「脚本已死，确认不再生效」，防孤儿确认在 SCRIPT_TIMEOUT 后仍执行写操作
                    writeConfirmationController.onScriptEnded()
                }
                // 产物拦截：脚本 return Chart.x({...}) → {chart:<svg>, summary:<text>}；
                // return {html:<自包含HTML>, summary:<text>} → HTML 组件卡片。
                // 渲染产物直接渲染成卡片（不喂回 LLM），summary 回传 LLM 做文字总结（省 token）。
                val obj = result as? JsValue.Obj
                val chart = obj?.entries?.get("chart") as? JsValue.Str
                val htmlCard = obj?.entries?.get("html") as? JsValue.Str
                when {
                    chart != null -> {
                        emitChartMessage(chart.value)
                        (obj.entries["summary"] as? JsValue.Str)?.value
                            ?: stringContext().getString(R.string.chat_chart_generated)
                    }
                    htmlCard != null -> {
                        when (val sanitized = HtmlCardSanitizer.sanitize(htmlCard.value)) {
                            is HtmlCardSanitizer.Result.Ok -> {
                                emitHtmlCardMessage(sanitized.html)
                                (obj.entries["summary"] as? JsValue.Str)?.value
                                    ?: stringContext().getString(R.string.chat_html_card_generated)
                            }
                            is HtmlCardSanitizer.Result.Rejected -> sanitized.reason
                        }
                    }
                    else -> {
                        // 回合护栏数据源（spec §3.5-a/c）：脚本 return 对象含 ids 数组
                        //（gallery.query/intersect 命中集合）→ 登记待回合收尾补卡/收口 refine 基数
                        //（M1 阶段无条件触发，不依赖意图判定；纯统计脚本不 return ids 即可规避）。
                        captureScriptMediaIds(result)
                        result.toJson()
                    }
                }
            }
        }
    }

    /**
     * 把端侧 JS 生成的图表 SVG 作为一条 [ChatMessageType.CHART] 消息**落库**。
     *
     * 聊天消息列表由 DB Flow 驱动，每次写入都会整体重载；若图卡只在内存，会被后续消息
     * 的重载冲掉（表现为“图先出现又消失”）。落库后图卡随会话持久，跨重载/重启均保留。
     */
    private suspend fun emitChartMessage(svg: String) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = "chart_" + System.currentTimeMillis(),
                sessionId = _currentSessionId.value,
                type = "chart",
                content = svg,
                timestamp = System.currentTimeMillis(),
                modelUsed = "chart"
            )
        )
    }

    /**
     * 把清洗后的自包含 HTML 作为一条 [ChatMessageType.HTML_CARD] 消息**落库**。
     * 与 [emitChartMessage] 同理：消息列表由 DB Flow 驱动，卡片必须落库才能跨重载持久。
     */
    private suspend fun emitHtmlCardMessage(html: String) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = "html_" + System.currentTimeMillis(),
                sessionId = _currentSessionId.value,
                type = "html_card",
                content = html,
                timestamp = System.currentTimeMillis(),
                modelUsed = "html_card"
            )
        )
    }

    /**
     * draw_chart 工具落点：用端侧 Chart 生成器把 [labels]/[values] 画成 [type] 图，
     * 渲染结果插入聊天；返回 summary（回传 LLM 做文字总结）。
     */
    override suspend fun onDrawChart(
        type: String,
        title: String,
        labels: List<String>,
        values: List<Double>,
        unit: String?,
        traceId: String?
    ): String = withContext(Dispatchers.Default) {
        val rt = getOrCreateJsRuntime()
        jsEvalMutex.withLock {
            val fn = when (type.lowercase().trim()) {
                "line" -> "line"
                "pie" -> "pie"
                else -> "bar"
            }
            val args = JSONObject()
                .put("title", title)
                .put("labels", JSONArray(labels))
                .put("values", JSONArray(values))
                .apply { if (!unit.isNullOrBlank()) put("unit", unit) }
                .toString()
            val result = rt.eval("Chart." + fn + "(" + args + ")", traceId)
            val obj = result as? JsValue.Obj
            val chart = obj?.entries?.get("chart") as? JsValue.Str
            if (chart != null) emitChartMessage(chart.value)
            (obj?.entries?.get("summary") as? JsValue.Str)?.value
                ?: stringContext().getString(R.string.chat_chart_generated)
        }
    }

    /**
     * render_html 工具落点：[html] 清洗（[HtmlCardSanitizer]）后作为 HTML_CARD 消息落库，
     * 由 HtmlCard 离线 WebView 渲染；返回 summary（回传 LLM 做文字总结）。
     * 清洗拒绝（超限等）时不落库，原因直接回传 LLM 引导重新生成。
     */
    override suspend fun onRenderHtml(
        html: String,
        summary: String?,
        traceId: String?
    ): String = withContext(Dispatchers.Default) {
        when (val result = HtmlCardSanitizer.sanitize(html)) {
            is HtmlCardSanitizer.Result.Ok -> {
                emitHtmlCardMessage(result.html)
                summary?.takeIf { it.isNotBlank() }
                    ?: stringContext().getString(R.string.chat_html_card_generated)
            }
            is HtmlCardSanitizer.Result.Rejected -> {
                Logger.w(TAG, "render_html rejected: ${result.reason}")
                result.reason
            }
        }
    }

    /**
     * 获取或创建持久化 JsRuntime，注册全部 gallery/media handler（只注册一次）。
     */
    private fun getOrCreateJsRuntime(): JsRuntime {
        persistentJsRuntime?.let { return it }
        return synchronized(this) {
            persistentJsRuntime?.let { return it }
            val rt = JsRuntime(
                engine = QuickJsEngine(
                    onLog = { msg -> Log.i("PoLang:Js", msg) },
                    evalTimeoutMs = 5_000,
                ),
                scope = viewModelScope,
                source = "chat",
            )
            // 注入 Chart 图表生成器（bar/line/pie → SVG）。失败仅告警，不阻断脚本能力。
            runCatching { rt.eval(loadChartBootstrapJs(context)) }
                .onFailure { Logger.w(TAG, "Chart bootstrap failed", it) }
            // gallery.*/media.* 只读 handler（唯一注册点，与 Debug 演示共用）
            registerGalleryHandlers(
                rt, getGallerySummaryUseCase, queryGalleryMediaUseCase, personDao, controlledVocab,
                scanProgressProvider = { TagGenerationService.sessionProgress.value },
            )
            // capability.dispatch：JS → CapabilityRegistry 写通路（写操作经用户确认；仅 chat 链路注册）
            rt.register(capabilityDispatchHandler.asNativeHandler())
            Log.i(TAG, "Persistent JsRuntime created with ${rt.handlerNames()} handlers")
            persistentJsRuntime = rt
            rt
        }
    }

    override fun onCleared() {
        super.onCleared()
        persistentJsRuntime?.close()
        persistentJsRuntime = null
        // adjustImageHandler 闭包捕获 this：ViewModel 销毁后必须摘除，否则进程级单例 ChatToolService 长期持有
        ChatToolService.getInstance().adjustImageHandler = null
    }

    // ── ChatStartTagScanCapability.Delegate：TAG 扫描控制 ─────────────

    override suspend fun onStartTagScan(
        action: String,
        taskType: String?,
        mode: String?
    ): StartTagScanResult {
        return startTagScanUseCase(action = action, taskType = taskType, mode = mode)
    }

    // ── ChatMediaWriteCapability.Delegate：媒体写操作（删除/收藏/选中）─────────

    /**
     * 删除：复用 [AndroidMediaRepository] 删除路径；API 29/30+ 需系统授权时，
     * 通过 [deleteAuthRequest] 交给 ChatScreen 既有 launcher 弹系统授权框。
     */
    override suspend fun onDeleteMedia(mediaIds: List<String>): String {
        val ids = mediaIds.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return stringContext().getString(R.string.chat_write_no_valid_ids)
        mediaRepository.deleteMediaByIds(ids)

        mediaRepository.getPendingRecoverableIntentSender()?.let { sender ->
            _deleteAuthRequest.value = MediaViewModel.DeleteAuthRequest.Api29(sender)
            return stringContext().getString(R.string.chat_delete_started_pending, ids.size)
        }
        val pendingUris = mediaRepository.getPendingDeleteUris().map { uriString -> Uri.parse(uriString) }
        if (pendingUris.isNotEmpty()) {
            _deleteAuthRequest.value = MediaViewModel.DeleteAuthRequest.Api30(pendingUris)
            return stringContext().getString(R.string.chat_delete_started_pending, ids.size)
        }
        return stringContext().getString(R.string.chat_deleted_n, ids.size)
    }

    override suspend fun onFavoriteMedia(mediaId: String, favorite: Boolean): String {
        _favoriteMediaIds.value =
            if (favorite) _favoriteMediaIds.value + mediaId else _favoriteMediaIds.value - mediaId
        Logger.d(TAG, "Favorite media $mediaId = $favorite (session level)")
        return if (favorite) {
            stringContext().getString(R.string.chat_favorited_one)
        } else {
            stringContext().getString(R.string.chat_unfavorited_one)
        }
    }

    override suspend fun onSelectMedia(mediaId: String, selected: Boolean): String {
        _selectedMediaIds.value =
            if (selected) _selectedMediaIds.value + mediaId else _selectedMediaIds.value - mediaId
        Logger.d(TAG, "Select media $mediaId = $selected (session level)")
        return if (selected) {
            stringContext().getString(R.string.chat_selected_one)
        } else {
            stringContext().getString(R.string.chat_unselected_one)
        }
    }

    private fun reapplyFiltersToCurrentResults(sessionId: String) {
        val current = lastResultAssets[sessionId] ?: return
        val excludes = sessionExcludes[sessionId] ?: return
        if (excludes.isEmpty()) return
        val filtered = current.filter { asset ->
            val labels = asset.labels?.let { parseLabels(it) } ?: emptyList()
            val text = (labels + asset.fileName).joinToString(" ")
            excludes.none { constraint -> text.contains(constraint, ignoreCase = true) }
        }
        lastResultAssets[sessionId] = filtered
        recordSearchSnapshot(
            sessionId = sessionId,
            query = sessionSearchSnapshots[sessionId]?.lastOrNull()?.query ?: "",
            totalCount = filtered.size,
            isRefinement = true
        )
    }

    /**
     * 回退直连：不经 Agent，直接把文本喂 MediaSearchEngine（LLM 不可用时可用）。单轮。
     */
    fun searchGalleryDirectly(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                _isProcessing.value = true
                chatMessageDao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        type = "user_text",
                        content = text,
                        modelUsed = null
                    )
                )
                chatSessionDao.touchSession(sessionId)
                val outcome = onSearchMedia(text)
                val assets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
                insertMediaResultsMessage(
                    sessionId,
                    MediaResultsUi(outcome.query, assets, outcome.totalCount, isRefinement = false)
                )
                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Direct gallery search failed", e)
                insertAgentMessage(
                    sessionId,
                    stringContext().getString(
                        R.string.chat_search_failed,
                        e.message ?: stringContext().getString(R.string.chat_unknown_error),
                    ),
                    "error"
                )
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * 回合护栏数据采集（spec §3.5-a/c）：脚本 return 对象含 `ids` 数组时登记到
     * [pendingScriptMediaIds]，等回合收尾统一水合/补卡（onRunScript 内不做 IO，
     * 避免拉长 JS eval 临界区）。
     */
    private fun captureScriptMediaIds(result: JsValue) {
        val obj = result as? JsValue.Obj ?: return
        val arr = obj.entries["ids"] as? JsValue.Arr ?: return
        val ids = arr.items.mapNotNull { item -> (item as? JsValue.Num)?.value?.toLong() }
        if (ids.isEmpty()) return
        val total = (obj.entries["total"] as? JsValue.Num)?.value?.toInt() ?: ids.size
        pendingScriptMediaIds[_currentSessionId.value] = ids to total
    }

    /**
     * 回合终态护栏 a/c（spec §3.5，意图路由 M1）：
     * - c（refine 基数收口）：脚本路径产出的媒体 id 集合水合后写入 [lastResultAssets]，
     *   后续 refine_media_search 不再因基数缺失而空转；
     * - a（补卡）：本回合未产出 media_results 卡片时，端侧用水合结果直接补渲染横滑卡片，
     *   消除「脚本拿到 ids 却谎称已展示」的幻觉土壤。
     *
     * 水合走 [MediaRepository.allMedia] 快照过滤（与 resolvePreviewUris 同一既有范式），
     * 与搜索路径同一资产源。
     */
    private suspend fun settleScriptMediaIds(sessionId: String, userText: String) {
        val pending = pendingScriptMediaIds.remove(sessionId) ?: return
        val (ids, total) = pending
        val idSet = ids.toSet()
        val assets = runCatching {
            mediaRepository.allMedia.first().filter { asset -> asset.id in idSet }
        }.getOrElse {
            Logger.w(TAG, "settleScriptMediaIds hydrate failed (${ids.size} ids)", it)
            return
        }
        if (assets.isEmpty()) return
        lastResultAssets[sessionId] = assets
        // 搜索基数快照（spec §3.2 紧凑状态源）：脚本路径同样让 hasSearchBase 为真，
        // 使意图路由器/细化链路能识别「上一轮有结果可细化」。
        recordSearchSnapshot(sessionId, userText, total, isRefinement = false)
        if (chatMessageDao.getLatestMediaResultsSinceLastUserMessage(sessionId) == null) {
            Logger.i(TAG, "settleScriptMediaIds: 补卡 ${assets.size} 张（脚本路径未出卡）")
            insertMediaResultsMessage(
                sessionId,
                MediaResultsUi(
                    query = userText,
                    assets = assets.take(MAX_CARDS),
                    totalCount = total,
                    isRefinement = false
                )
            )
        }
    }

    /**
     * 插入搜索结果卡片。
     * 同一用户回合内可能多次产出 MediaResults（ReAct 多轮 search_media/refine_media_search、
     * 拒绝措辞回退直搜等）：[replacePreviousInTurn] 为 true 时复用本回合上一张卡片行的 id
     * 做 REPLACE upsert，保证一个用户回合至多一张横滑卡片；以图搜图等自成新回合的入口传
     * false，追加新卡而非覆盖上一回合的结果。
     */
    private suspend fun insertMediaResultsMessage(
        sessionId: String,
        ui: MediaResultsUi,
        replacePreviousInTurn: Boolean = true
    ) {
        mediaResultsMutex.withLock {
            val previousCard = if (replacePreviousInTurn) {
                chatMessageDao.getLatestMediaResultsSinceLastUserMessage(sessionId)
            } else {
                null
            }
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    id = previousCard?.id ?: UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "media_results",
                    content = ChatGallerySearch.serializeContent(ui.assets),
                    timestamp = previousCard?.timestamp ?: System.currentTimeMillis(),
                    modelUsed = "gallery_search",
                    metadata = ChatGallerySearch.serializeMetadata(ui.query, ui.totalCount, ui.isRefinement)
                )
            )
            chatSessionDao.touchSession(sessionId)
        }
    }

    private suspend fun ensureSessionExists(sessionId: String) {
        val existing = chatSessionDao.getSession(sessionId)
        if (existing == null) {
            chatSessionDao.insertSession(
                ChatSessionEntity(
                    sessionId = sessionId,
                    title = if (sessionId == "default") "New Chat" else "Chat"
                )
            )
        }
    }

    /**
     * 根据用户的第一条消息自动生成会话标题。
     *
     * - 文本消息：取内容前 [ChatTitleGenerator.MAX_AUTO_TITLE_LENGTH] 个字符，去除首尾标点，合并换行/连续空白。
     * - 图片消息：统一显示为图片对话标题。
     */
    private fun generateAutoTitle(firstUserMessage: ChatMessageEntity): String {
        return ChatTitleGenerator.generateTitle(
            firstUserMessageType = firstUserMessage.type,
            textContent = firstUserMessage.content,
            imageTitle = stringContext().getString(R.string.chat_title_image_first),
            fallbackTitle = stringContext().getString(R.string.new_chat)
        )
    }

    /**
     * 如果当前标题仍是系统默认值，则将其更新为自动生成的标题。
     *
     * 保护用户手动重命名的标题不被覆盖。
     */
    private suspend fun updateSessionTitleIfDefault(
        sessionId: String,
        candidateTitle: String
    ) {
        val session = chatSessionDao.getSession(sessionId) ?: return
        if (!isDefaultTitle(session.title)) return
        chatSessionDao.updateTitle(sessionId, candidateTitle)
        Logger.i(TAG, "Auto-updated session title to: $candidateTitle")
    }

    /**
     * 判断标题是否为系统默认标题。
     */
    private fun isDefaultTitle(title: String): Boolean {
        if (title.isBlank()) return true
        if (title == "New Chat" || title == "Chat") return true
        if (title == stringContext().getString(R.string.new_chat)) return true
        return false
    }

    /**
     * 插入 AI 回复/命令结果到 Room
     */
    private suspend fun insertAgentMessage(
        sessionId: String,
        content: String,
        modelUsed: String,
        performance: LlmPerformance? = null
    ) {
        val metadata = performance?.let {
            JSONObject().apply {
                put("prompt_len", it.promptLen)
                put("decode_len", it.decodeLen)
                put("prefill_time_ms", it.prefillTimeMs)
                put("decode_time_ms", it.decodeTimeMs)
                put("prefill_speed", it.prefillSpeed.toDouble())
                put("decode_speed", it.decodeSpeed.toDouble())
                put("used_sandbox", it.usedSandbox)
            }.toString()
        }
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "agent_text",
                content = content,
                modelUsed = modelUsed,
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }

    /**
     * 插入一条带结果图的 AI 消息（type=agent_image）。用于 chat 内执行图像编辑后直接返回结果。
     */
    private suspend fun insertAgentImageMessage(
        sessionId: String,
        imageUri: String,
        content: String,
        modelUsed: String,
        performance: LlmPerformance? = null
    ) {
        val metadata = JSONObject().apply {
            put("imageUri", imageUri)
            put("saved", false)
            performance?.let { p ->
                put("prompt_len", p.promptLen)
                put("decode_len", p.decodeLen)
                put("prefill_time_ms", p.prefillTimeMs)
                put("decode_time_ms", p.decodeTimeMs)
                put("prefill_speed", p.prefillSpeed.toDouble())
                put("decode_speed", p.decodeSpeed.toDouble())
                put("used_sandbox", p.usedSandbox)
            }
        }.toString()
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "agent_image",
                content = content,
                modelUsed = modelUsed,
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }

    /**
     * 插入对话式图片编辑结果消息。
     *
     * - content：给用户的说明文本
     * - metadata.imageUri：编辑后的结果图 URI
     * - metadata.suggestions：可继续执行的推荐话术
     */
    @VisibleForTesting
    internal suspend fun insertEditResultMessage(
        sessionId: String,
        imageUri: String,
        explanation: String,
        modelUsed: String,
        performance: LlmPerformance? = null
    ) {
        val metadata = JSONObject().apply {
            put("imageUri", imageUri)
            put("saved", false)
            put("explanation", explanation)
            put("suggestions", JSONArray(listOf(
                stringContext().getString(R.string.chat_edit_suggestion_brighter),
                stringContext().getString(R.string.chat_edit_suggestion_fine_tune)
            )))
            performance?.let {
                put("prompt_len", it.promptLen)
                put("decode_len", it.decodeLen)
                put("prefill_time_ms", it.prefillTimeMs)
                put("decode_time_ms", it.decodeTimeMs)
                put("prefill_speed", it.prefillSpeed.toDouble())
                put("decode_speed", it.decodeSpeed.toDouble())
            }
        }.toString()
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "agent_edit_result",
                content = explanation,
                modelUsed = modelUsed,
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }

    /** 插入候选卡组消息（type=optimize_candidates），并按推荐卡初始化选中态。 */
    @VisibleForTesting
    internal suspend fun insertOptimizeCandidatesMessage(
        sessionId: String,
        messageId: String,
        group: OptimizeCandidateGroup,
        content: String,
        modelUsed: String
    ) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = messageId,
                sessionId = sessionId,
                type = OptimizeCandidateGroup.MESSAGE_TYPE,
                content = content,
                modelUsed = modelUsed,
                metadata = group.toJson()
            )
        )
        chatSessionDao.touchSession(sessionId)
        _gachaSelections.value = _gachaSelections.value + (messageId to group.recommendedIndex)
    }

    /** 点选候选卡（同时触发全屏预览，由 UI 侧处理）。 */
    fun onOptimizeGachaSelection(messageId: String, index: Int) {
        _gachaSelections.value = _gachaSelections.value + (messageId to index)
    }

    /**
     * 换一组：重抽并覆写该条消息。
     *
     * @param onResult true=成功；false=不可用（UI toast，卡条保持）
     */
    fun onOptimizeGachaReroll(messageId: String, onResult: (Boolean) -> Unit) {
        val controller = optimizeGachaController ?: return
        if (messageId in _gachaRerolling.value) return // 防抖：换一组期间忽略重复点击
        _gachaRerolling.value = _gachaRerolling.value + messageId
        viewModelScope.launch {
            try {
                when (val outcome = controller.reroll(messageId)) {
                    is ChatOptimizeGachaController.RerollOutcome.Rerolled -> {
                        chatMessageDao.getMessageById(messageId)?.let { entity ->
                            chatMessageDao.insertMessage(
                                entity.copy(content = outcome.explanation, metadata = outcome.group.toJson())
                            )
                        }
                        _gachaSelections.value = _gachaSelections.value + (messageId to outcome.group.recommendedIndex)
                        onResult(true)
                    }
                    ChatOptimizeGachaController.RerollOutcome.Expired,
                    ChatOptimizeGachaController.RerollOutcome.Unavailable -> onResult(false)
                }
            } finally {
                _gachaRerolling.value = _gachaRerolling.value - messageId
            }
        }
    }

    /**
     * 就用这张：全尺寸渲染 → 该条消息改写为 agent_image 结果消息（复用 insert-replace 模式）。
     *
     * @param onResult true=成功；false=失败（UI toast，卡条保持可重试）
     */
    fun onOptimizeGachaConfirm(messageId: String, candidateIndex: Int, onResult: (Boolean) -> Unit) {
        val controller = optimizeGachaController ?: return
        viewModelScope.launch {
            val result = controller.confirm(messageId, candidateIndex)
            if (result == null) {
                onResult(false)
                return@launch
            }
            chatMessageDao.getMessageById(messageId)?.let { entity ->
                val metadata = JSONObject().apply {
                    put("imageUri", result.imageUri)
                    put("saved", false)
                }.toString()
                chatMessageDao.insertMessage(
                    entity.copy(type = "agent_image", metadata = metadata)
                )
            }
            _gachaSelections.value = _gachaSelections.value - messageId
            onResult(true)
        }
    }

    /** 废弃会话的 pending 卡条（落库 dismiss）；在用户发新消息/切会话等打断点调用。 */
    private suspend fun discardPendingOptimizeGacha(sessionId: String = _currentSessionId.value) {
        optimizeGachaController?.discardPending(sessionId)
    }

    /**
     * 仅暂存图片：复制到内部存储 + 设 [_lastUserImageUri]，**不**插入消息、**不**触发推理。
     * 返回持久化后的路径字符串；失败返回 null。供 Chat 输入框「缩略图预览」用。
     */
    fun stageImage(uri: Uri): String? {
        val persisted = persistImage(uri) ?: return null
        _lastUserImageUri.value = persisted
        return persisted
    }

    /**
     * 按 [intent] 发送「图 + 意图/文字」。`uri` 为已通过 [stageImage] 持久化的内部存储路径。
     * - 文字非空：作为 Agent 指令，图片经 [_lastUserImageUri] 作为上下文（[sendMessage]）。
     * - [ImageIntent.FIND_SIMILAR]：以图搜图，命中则插 media-results 轮播，否则提示无结果。
     * - [ImageIntent.UNDERSTAND]（默认）：复用 [sendImageMessage] 的图像理解链路。
     * 注意：[ImageIntent.EDIT] 由 UI 直接跳 PhotoEditor，不应进入本方法。
     */
    fun sendImageWithIntent(uri: String, intent: ImageIntent, text: String?) {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                when {
                    !text.isNullOrBlank() -> sendMessage(text, uri)
                    intent == ImageIntent.FIND_SIMILAR -> {
                        _isProcessing.value = true
                        val bitmap = BitmapSampling.decodeFile(uri, CHAT_IMAGE_MAX_PX)
                        val assets = if (bitmap != null) {
                            mediaSearchEngine.searchByImage(bitmap)
                        } else {
                            emptyList()
                        }
                        _isProcessing.value = false
                        if (assets.isNotEmpty()) {
                            // 以图搜图不插 user 消息、自成新回合：追加新卡，不覆盖上一回合的搜索结果
                            insertMediaResultsMessage(
                                sessionId,
                                MediaResultsUi(
                                    query = stringContext().getString(R.string.chat_intent_find_similar),
                                    assets = assets.take(MAX_CARDS),
                                    totalCount = assets.size,
                                    isRefinement = false
                                ),
                                replacePreviousInTurn = false
                            )
                        } else {
                            insertAgentMessage(
                                sessionId,
                                stringContext().getString(R.string.gallery_search_no_results),
                                "gallery_search"
                            )
                        }
                    }
                    else -> sendImageMessage(Uri.fromFile(java.io.File(uri)))
                }
                chatSessionDao.touchSession(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "sendImageWithIntent failed", e)
                _isProcessing.value = false
            }
        }
    }

    /**
     * 发送图片消息，通过端侧 VLM 进行图像理解。
     *
     * 引擎跟随设置页「打标模型」选择（与打标同源，见 [TaggerModelSelector]）：
     * Florence-2 走 ONNX caption 管线，qwen3_vl_2b 走 MNN imageInference。
     */
    fun sendImageMessage(imageUri: Uri) {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                _isProcessing.value = true

                // 0. 将图片复制到内部存储（content:// URI 权限在进程重启后失效）
                val persistedUri = persistImage(imageUri)
                if (persistedUri == null) {
                    insertAgentMessage(sessionId, stringContext().getString(R.string.chat_image_save_failed), "error")
                    return@launch
                }

                // 1. 保存用户图片消息到 Room（使用内部存储路径）
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "user_image",
                    content = persistedUri,
                    modelUsed = null
                )
                chatMessageDao.insertMessage(userMessage)
                _lastUserImageUri.value = persistedUri
                chatSessionDao.touchSession(sessionId)

                // 1.5 自动命名：根据用户的第一条消息生成会话标题
                val messageCount = chatMessageDao.getMessageCount(sessionId)
                if (messageCount == 1) {
                    updateSessionTitleIfDefault(sessionId, generateAutoTitle(userMessage))
                }

                // 1.6 按设置页打标模型解析图像理解引擎（与打标同源，AUTO 由 TaggerModelSelector 兜底）
                val scheduler = tagGenerationScheduler
                val modelKey = withContext(Dispatchers.IO) {
                    scheduler?.currentTaggerModelKey()
                } ?: FALLBACK_IMAGE_MODEL_KEY

                // 2. 创建流式占位
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = stringContext().getString(R.string.chat_analyzing_image),
                    modelUsed = modelKey,
                    isStreaming = true,
                    isThinking = true
                )

                // 3. 加载 Bitmap
                val bitmap = BitmapSampling.decodeStream(
                    { context.contentResolver.openInputStream(imageUri) },
                    CHAT_IMAGE_MAX_PX
                )
                if (bitmap == null) {
                    _streamingMessage.value = null
                    insertAgentMessage(sessionId, stringContext().getString(R.string.chat_image_load_failed), "error")
                    return@launch
                }

                // 4. 按解析出的模型执行图像理解：Florence-2 走 ONNX caption 管线，其余走 MNN imageInference
                if (modelKey == TaggerModelSelector.defaultKey) {
                    // Florence-2（ONNX，不走 MNN）：复用 scheduler 的 caption + en→zh 翻译管线
                    val description = scheduler?.describeImage(bitmap)
                    _streamingMessage.value = null
                    if (description.isNullOrBlank()) {
                        insertAgentMessage(
                            sessionId,
                            stringContext().getString(R.string.chat_model_not_loaded_guide, modelKey),
                            "error"
                        )
                        return@launch
                    }
                    insertAgentMessage(sessionId = sessionId, content = description, modelUsed = modelKey)
                    // 将图片分析结果保存到 MemoryManager，使后续文本消息能引用图片上下文
                    orchestrator.appendConversation(
                        sessionId = sessionId,
                        userInput = "请描述这张图片",
                        assistantResponse = description
                    )
                    cleanupIfNeeded(sessionId)
                    return@launch
                }

                // MNN VLM（qwen3_vl_2b）：显式传 modelId 跟随设置，提示词按 UI 语言直出
                if (!orchestrator.localModelService.isModelLoaded) {
                    _streamingMessage.value = ChatMessageUi(
                        id = streamingId,
                        type = ChatMessageType.AGENT_TEXT,
                        content = stringContext().getString(R.string.chat_loading_model),
                        modelUsed = modelKey
                    )
                }
                val strategy = withContext(Dispatchers.IO) {
                    ImageDescriptionStrategyResolver.resolve(modelKey, userSettingsRepository.getAppLanguageBlocking())
                }
                val inferenceResult = orchestrator.localModelService.withModelLoaded(
                    modelId = modelKey,
                    caller = "ChatViewModel:imageInference"
                ) { engine ->
                    // 接口视图只有 ByteArray 入参；Bitmap 便捷重载是 LocalLlmEngine 的
                    // Android 专有 API，组合根保证实际类型（同一单例）。
                    (engine as LocalLlmEngine).imageInference(
                        systemPrompt = strategy.systemPrompt,
                        userPrompt = strategy.userPrompt,
                        bitmap = bitmap,
                        maxTokens = 256
                    )
                }

                if (inferenceResult.isFailure) {
                    _streamingMessage.value = null
                    val error = inferenceResult.exceptionOrNull()
                    val unknown = stringContext().getString(R.string.chat_unknown_error)
                    val message = if (error is LlmModelNotFoundException || error?.message?.contains("模型") == true) {
                        stringContext().getString(R.string.chat_model_not_loaded, error?.message ?: unknown)
                    } else {
                        stringContext().getString(R.string.chat_image_process_error, error?.message ?: unknown)
                    }
                    insertAgentMessage(sessionId, message, "error")
                    return@launch
                }
                val response = inferenceResult.getOrThrow()

                // 清除流式占位
                _streamingMessage.value = null

                if (response.isBlank()) {
                    insertAgentMessage(sessionId, stringContext().getString(R.string.chat_model_empty_response), "error")
                } else {
                    insertAgentMessage(
                        sessionId = sessionId,
                        content = response,
                        modelUsed = modelKey,
                        // LlmGenerationMetrics 是 Android actual 类型（shared androidMain），
                        // commonMain 的 LocalModelService 不再透出；经组合根取同一引擎实例读取。
                        performance = AndroidAgentComposition.localLlmEngine.lastGenerationMetrics?.toLlmPerformance()
                    )
                    // 将图片分析结果保存到 MemoryManager，使后续文本消息能引用图片上下文
                    orchestrator.appendConversation(
                        sessionId = sessionId,
                        userInput = strategy.userPrompt,
                        assistantResponse = response
                    )
                }

                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send image message", e)
                _streamingMessage.value = null
                insertAgentMessage(
                    sessionId,
                    stringContext().getString(
                        R.string.chat_image_process_error,
                        e.message ?: stringContext().getString(R.string.chat_unknown_error),
                    ),
                    "error"
                )
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * 切换当前模型（chat 页仅远程：Remote 为唯一选项，同步远程配置到 AgentOrchestrator）。
     */
    fun switchModel(model: ChatModelOption) {
        _currentModel.value = model
        viewModelScope.launch {
            try {
                // 同步到 AgentOrchestrator（复用已有的远程配置）
                val existingRemoteConfig = orchestrator.getUserRemoteConfig()
                orchestrator.updateRemoteRuntimeConfig(
                    remoteConfig = existingRemoteConfig,
                    privacyLevel = AiAgentPrivacyLevel.STRICT
                )
                Logger.i(TAG, "Model switched to: ${model.labelRes}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to sync model switch", e)
            }
        }
    }

    /**
     * 切换 chat 远程模型（官方 / 用户自配某项）。chat 仅远程：配置 orchestrator 用对应 RemoteModelConfig。
     */
    fun switchModel(modelId: String) {
        val model = _availableModels.value.find { it.id == modelId } ?: return
        _selectedModelId.value = modelId
        viewModelScope.launch {
            try {
                orchestrator.updateRemoteRuntimeConfig(
                    remoteConfig = effectiveRemoteConfig(model),
                    privacyLevel = AiAgentPrivacyLevel.STRICT
                )
                Logger.i(TAG, "chat model switched: ${model.displayName} (${model.remoteConfig.baseUrl})")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to switch chat model", e)
            }
        }
    }

    /**
     * 当图片从媒体库中被删除后，同步把对应 media_results 消息里的该图片移除。
     * 如果某条 media_results 消息的所有图片都被删光，则整条消息一起删掉。
     */
    fun removeMediaResultAsset(mediaId: Long) {
        viewModelScope.launch {
            try {
                val currentMessages = _messages.value
                val updatedMessages = currentMessages.mapNotNull { message ->
                    val mr = message.mediaResults
                    if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null &&
                        mr.assets.any { it.id == mediaId }
                    ) {
                        val newAssets = mr.assets.filter { it.id != mediaId }
                        if (newAssets.isEmpty()) {
                            val deletedText = stringContext().getString(R.string.chat_results_photo_deleted)
                            chatMessageDao.getMessageById(message.id)?.let { entity ->
                                chatMessageDao.insertMessage(
                                    entity.copy(
                                        type = "agent_text",
                                        content = deletedText,
                                        metadata = null
                                    )
                                )
                            }
                            return@mapNotNull message.copy(
                                type = ChatMessageType.AGENT_TEXT,
                                content = deletedText,
                                mediaResults = null
                            )
                        }
                        val newTotal = (mr.totalCount - 1).coerceAtLeast(newAssets.size)
                        chatMessageDao.getMessageById(message.id)?.let { entity ->
                            chatMessageDao.insertMessage(
                                entity.copy(
                                    content = ChatGallerySearch.serializeContent(newAssets),
                                    metadata = ChatGallerySearch.serializeMetadata(
                                        mr.query,
                                        newTotal,
                                        mr.isRefinement
                                    )
                                )
                            )
                        }
                        message.copy(
                            mediaResults = mr.copy(
                                assets = newAssets,
                                totalCount = newTotal
                            )
                        )
                    } else {
                        message
                    }
                }
                _messages.value = updatedMessages
                Logger.i(TAG, "Removed media result asset $mediaId from chat UI")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to remove media result asset $mediaId", e)
            }
        }
    }

    /**
     * 清空当前会话
     */
    fun clearChat() {
        viewModelScope.launch {
            try {
                val sessionId = _currentSessionId.value
                optimizeGachaController?.discardPending(sessionId)
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.updateTitle(sessionId, "New Chat")
                _messages.value = emptyList()
                // 选中态是纯 UI 内存态，消息删光后整体清理，回退到推荐卡高亮即可
                _gachaSelections.value = emptyMap()
                // 搜索/路由状态同步清理：消息已删，旧搜索基数不应再驱动 refine 直执（路由层
                // hasSearchBase 派生自 recentSearchResults）与卡片水合
                lastResultAssets.remove(sessionId)
                sessionSearchSnapshots.remove(sessionId)
                pendingScriptMediaIds.remove(sessionId)
                Logger.i(TAG, "Chat cleared for session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to clear chat", e)
            }
        }
    }

    /**
     * 如果消息数超过上限，删除最早的消息
     */
    private suspend fun cleanupIfNeeded(sessionId: String) {
        try {
            val count = chatMessageDao.getMessageCount(sessionId)
            if (count > MAX_MESSAGES) {
                val excess = count - MAX_MESSAGES
                chatMessageDao.deleteOldestMessages(sessionId, excess)
                Logger.i(TAG, "Cleaned up $excess old messages for session $sessionId")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to cleanup messages", e)
        }
    }

    private fun parseImageUri(metadata: String): String? = try {
        org.json.JSONObject(metadata).optString("imageUri").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    @Suppress("CyclomaticComplexMethod") // 待重构：toUiModel 按消息类型映射抽表
    private fun ChatMessageEntity.toUiModel(): ChatMessageUi {
        val isMediaResults = type == "media_results"
        val performance = if (isMediaResults) null else metadata?.let { parsePerformanceMetadata(it) }
        val mediaResults = if (isMediaResults) ChatGallerySearch.deserialize(content, metadata) else null
        return ChatMessageUi(
            id = id,
            type = when (type) {
                "user_text" -> ChatMessageType.USER_TEXT
                "agent_text" -> ChatMessageType.AGENT_TEXT
                "user_image" -> ChatMessageType.USER_IMAGE
                "user_image_text" -> ChatMessageType.USER_IMAGE_TEXT
                "agent_image" -> ChatMessageType.AGENT_IMAGE
                "command" -> ChatMessageType.COMMAND
                "plan_preview" -> ChatMessageType.PLAN_PREVIEW
                "media_results" -> ChatMessageType.MEDIA_RESULTS
                "chart" -> ChatMessageType.CHART
                "html_card" -> ChatMessageType.HTML_CARD
                "agent_edit_result" -> ChatMessageType.AGENT_EDIT_RESULT
                OptimizeCandidateGroup.MESSAGE_TYPE -> ChatMessageType.OPTIMIZE_CANDIDATES
                EngineerTaskState.ROOM_TYPE -> ChatMessageType.TASK_CARD
                else -> ChatMessageType.AGENT_TEXT
            },
            content = content,
            chartSvg = if (type == "chart") content else null,
            htmlContent = if (type == "html_card") content else null,
            imageUri = if (type == "user_image_text" || type == "agent_image" || type == "agent_edit_result") metadata?.let { m -> parseImageUri(m) } else null,
            imageSaved = (type == "agent_image" || type == "agent_edit_result") &&
                (metadata?.let { runCatching { org.json.JSONObject(it).optBoolean("saved", false) }.getOrDefault(false) } ?: false),
            modelUsed = modelUsed,
            timestamp = timestamp,
            performance = performance,
            mediaResults = mediaResults,
            claudeAgent = parseClaudeAgentState(metadata),
            optimizeCandidates = if (type == OptimizeCandidateGroup.MESSAGE_TYPE) {
                OptimizeCandidateGroup.fromJson(metadata)
            } else {
                null
            },
            gachaInteractive = type == OptimizeCandidateGroup.MESSAGE_TYPE &&
                optimizeGachaController?.hasPending(id) == true,
            engineerTask = if (type == EngineerTaskState.ROOM_TYPE) parseEngineerTaskState(metadata) else null,
        )
    }

    /** 从 metadata.claude_agent_state 还原 agent 气泡（跨重载/重启保留）。 */
    private fun parseClaudeAgentState(metadata: String?): ClaudeAgentState? {
        if (metadata.isNullOrBlank()) return null
        return runCatching {
            JSONObject(metadata).optJSONObject("claude_agent_state")?.let { ClaudeAgentState.fromJson(it) }
        }.getOrNull()
    }

    /**
     * 从 metadata JSON 解析本地 LLM 性能指标
     */
    private fun parsePerformanceMetadata(metadata: String): LlmPerformance? {
        return try {
            val json = JSONObject(metadata)
            // metadata 不含任何性能字段（典型：AI 工程师 claude 气泡只写 claude_agent_state，
            // 网关 SSE 不下发 input/output tokens）时，视为"无性能数据"返回 null，而非用
            // optLong 默认值拼出一个全 0 的 LlmPerformance——否则 UI 会因 performance 非 null
            // 而在气泡底部渲染一堆无意义的 0。本地/REMOTE chat 落库时必带这些字段，正常解析不受影响。
            if (!json.has("prompt_len") && !json.has("decode_len") &&
                !json.has("decode_time_ms") && !json.has("prefill_time_ms")
            ) {
                return null
            }
            LlmPerformance(
                promptLen = json.optLong("prompt_len", 0),
                decodeLen = json.optLong("decode_len", 0),
                prefillTimeMs = json.optLong("prefill_time_ms", 0),
                decodeTimeMs = json.optLong("decode_time_ms", 0),
                prefillSpeed = json.optDouble("prefill_speed", 0.0).toFloat(),
                decodeSpeed = json.optDouble("decode_speed", 0.0).toFloat(),
                usedSandbox = json.optBoolean("used_sandbox", false)
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse performance metadata", e)
            null
        }
    }

    private fun LlmGenerationMetrics.toLlmPerformance(): LlmPerformance {
        return LlmPerformance(
            promptLen = promptLen,
            decodeLen = decodeLen,
            prefillTimeMs = prefillTime / 1000,
            decodeTimeMs = decodeTime / 1000,
            prefillSpeed = prefillSpeed,
            decodeSpeed = decodeSpeed
        )
    }

    /**
     * 将 content:// URI 图片复制到内部存储，返回持久化路径
     * 解决 content picker 临时权限在进程重启后失效导致图片不显示的问题
     */
    private fun persistImage(sourceUri: Uri): String? {
        return try {
            val imagesDir = java.io.File(context.filesDir, "picme_images")
            if (!imagesDir.exists()) imagesDir.mkdirs()

            val destFile = java.io.File(imagesDir, "img_${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to persist image", e)
            null
        }
    }

}

/** Chat 问题上报 UI 状态。 */
sealed interface IssueReportState {
    data object Idle : IssueReportState
    data object Submitting : IssueReportState
    data class Success(val issueId: Int) : IssueReportState
    data class Error(val message: String) : IssueReportState
}

/**
 * 生产接线工厂（spec §3.1）：把 Android 数据源接进 [AppToolExecutor]。
 *
 * - 日志来自 [Logger] 内存环缓冲（最近 500 条）；崩溃栈来自 [CrashTraceStore] 落盘文件。
 * - 运行时状态只放元数据与 Boolean（绝不放 token 本体 / 用户 Key）。
 * - 相册摘要是纯统计数字（[PRIVACY]：绝不含路径 / 图片）。
 * - runtimeState/gallerySummary 是同步 lambda 但数据源是 suspend Flow：
 *   此处已在 IO 调度器上执行（[ChatViewModel.handleAppToolRequest] launch(Dispatchers.IO)），
 *   工厂内 runBlocking 取值可接受。
 */
internal fun buildAppToolExecutor(deps: ChatViewModelDependencies): AppToolExecutor = AppToolExecutor(
    logProvider = {
        Logger.logs.value.joinToString("\n") { e -> "${e.timestamp} ${e.level} PoLang:${e.tag}: ${e.message}" }
    },
    crashTraceReader = { CrashTraceStore.read(deps.context.filesDir) },
    chatHistoryLoader = { sessionId, limit ->
        // schema 缺省语义是「当前会话」：字面量 "default" 会漂移（工程师会话恒为 UUID），
        // 改从设置库读当前会话 id（switchSession/newSession 均经 updateChatCurrentSessionId 写入）
        val effectiveSessionId = sessionId
            ?: deps.userSettingsRepository.chatCurrentSessionIdFlow.first()
        deps.chatMessageDao.getRecentMessages(effectiveSessionId, limit)
            .map { it.type to it.content }
    },
    runtimeStateProvider = RuntimeStateProvider {
        runBlocking {
            val settings = deps.userSettingsRepository
            val userConfigs = RemoteModelConfigs.fromJson(settings.aiAgentRemoteModelConfigsFlow.first())
            JSONObject()
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("gitSha", BuildConfig.GIT_SHA)
                .put("deviceModel", Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("selectedModelId", settings.aiAgentSelectedRemoteModelFlow.first())
                .put("hasUserKey", userConfigs.configs.any { it.isConfigured })
                .put("agentMode", settings.aiAgentModeFlow.first().name)
                .put("hasServerAuthToken", settings.serverAuthTokenFlow.first().isNotBlank())
        }
    },
    gallerySummaryLoader = {
        runBlocking {
            val s = deps.getGallerySummaryUseCase()
            if (s == null) {
                JSONObject().put("empty", true).put("reason", "summary_unavailable")
            } else {
                JSONObject()
                    .put("totalPhotos", s.totalPhotos)
                    .put("totalVideos", s.totalVideos)
                    .put("totalMedia", s.totalMedia)
                    .put("hasFaceCount", s.hasFaceCount)
                    .put("personClusterCount", s.personClusterCount)
                    .put("namedPersonCount", s.namedPersonCount)
                    .put("labeledCount", s.labeledCount)
                    .put("unlabeledCount", s.unlabeledCount)
                    .put("semanticEncodedCount", s.semanticEncodedCount)
                    .put("isScanning", s.isScanning)
                    .put("recommendation", s.recommendation.name)
            }
        }
    },
)
