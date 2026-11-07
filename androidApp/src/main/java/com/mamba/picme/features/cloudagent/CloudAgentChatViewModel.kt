package com.mamba.picme.features.cloudagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.BuildConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.core.agenttools.CloudAgentGalleryTools
import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.domain.chat.AgentStepUi
import com.mamba.picme.domain.chat.MediaResultsUi
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 云端 Agent 最小聊天页状态机（spec §5，调试入口实验页；内存态消息列表，不落 Room——镜像属 M3）。
 *
 * 生命周期：prepare()（通道解析 + 建会话，幂等）→ Chat 态 send()/stop() 循环。
 * 回合内核（SSE 收帧/工具桥上行）委托 [CloudAgentTurnRunner]——Chat 主页云端模式共用同一内核。
 * BYOK key 仅存本类私有字段（内存态），随 postMessage 头发送；绝不进日志（Logger 调用不含 key）。
 * onEvent 回调抵达于 Dispatchers.IO（网络线程），Chat 态写经 StateFlow.update CAS 安全；消息 id 由 AtomicLong 保证唯一单调。
 */
class CloudAgentChatViewModel(
    private val api: CloudAgentApi,
    private val deviceToolExecutor: DeviceToolExecutor,
    private val settingsRepository: UserSettingsRepository,
    private val cloudAgentGalleryTools: CloudAgentGalleryTools,
    /** 409 round_in_flight 上屏提示文案（生产注入走资源文件，VM 不持 Context 保持纯 JVM 可测）。 */
    private val roundInFlightHint: () -> String = { "Previous round still running, please wait" },
    /** 步骤时间线文案解析（resId → 本地化文案；必填——生产注入 context.getString，测试注入桩，省略即编译错误以防英文兜底漂移）。 */
    private val stepLabel: (Int) -> String,
) : ViewModel() {

    /** 媒体卡消息经 [media] 携带、H5 卡经 [htmlCard] 携带、OpenUI 预览卡经 [openUiCard] 携带；其余 role 恒为 null。 */
    data class UiMessage(
        val id: Long,
        val role: Role,
        val text: String,
        val media: MediaResultsUi? = null,
        val htmlCard: HtmlCardUi? = null,
        val openUiCard: OpenUiCardUi? = null,
    )

    /** 云端 render_html 产物卡的 UI 数据（HTML 渲染前由 Screen 过 HtmlCardSanitizer）。 */
    data class HtmlCardUi(val html: String, val summary: String)

    /** OpenUI 调试预览卡（扫码/会话加载/画廊注入；label = 来源标注，如会话短 id·model 或组件名）。 */
    data class OpenUiCardUi(val program: String, val label: String)

    enum class Role { USER, ASSISTANT, TOOL, ERROR, MEDIA, HTML_CARD, OPENUI_CARD }

    sealed interface UiState {
        data object Preparing : UiState
        data object NeedByokConsent : UiState
        data class Failed(val reason: String) : UiState
        data class Chat(
            val sessionId: String,
            val channel: String, // "gateway" | "byok"
            val messages: List<UiMessage>,
            val inFlight: Boolean,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Preparing)
    val state: StateFlow<UiState> = _state

    /**
     * 云端回合步骤时间线（瞬态，不落 Room）：reducer 消费 StepStarted/StepFinished，
     * [steps] 为快照流，回合开始/结束（含 stop() 取消路径）清空。
     * 单线程契约：onEvent 由 SSE reader 串行回调，单回合内无并发。
     */
    private val stepTimeline = AgentStepTimeline()
    private val _steps = MutableStateFlow<List<AgentStepUi>>(emptyList())
    val steps: StateFlow<List<AgentStepUi>> = _steps

    private var appToken: String = ""
    private var byokApiKey: String = ""
    private var turnJob: Job? = null
    private val msgSeq = AtomicLong(0L)
    private var prepareStarted = false
    private val turnRunner = CloudAgentTurnRunner(api, deviceToolExecutor, viewModelScope)

    /** 入口：通道解析（spec §6 表）→ 建会话。幂等（同步守卫防快速双击竞态 + 非 Preparing 直接返回）。 */
    fun prepare() {
        if (prepareStarted) return
        prepareStarted = true
        if (_state.value !is UiState.Preparing) return
        viewModelScope.launch {
            val token = settingsRepository.serverAuthTokenFlow.first()
            // 未登录：release 维持登录闸；debug 放宽——空 token 继续，服务端 DEBUG_QUOTA_BYPASS
            // 双闸（env + X-App-Channel: debug + X-Device-Id）合成访客身份兜底，调试页免注册遛狗。
            if (token.isBlank() && !BuildConfig.DEBUG) {
                _state.value = UiState.Failed("not_logged_in")
                return@launch
            }
            appToken = token
            val configsJson = settingsRepository.aiAgentRemoteModelConfigsFlow.first()
            val selectedKey = settingsRepository.aiAgentSelectedRemoteModelFlow.first()
            val config = if (configsJson.isBlank()) {
                null
            } else {
                val configs = RemoteModelConfigs.fromJson(configsJson)
                configs.configs.find { c -> c.uniqueKey == selectedKey }
                    ?: configs.getConfigByModelId(selectedKey)
            }
            val consent = settingsRepository.cloudAgentByokConsentFlow.first()
            when (val choice = CloudAgentChannelResolver.resolve(config, consent)) {
                CloudAgentChannelChoice.Gateway -> createAndEnter("gateway", model = "", byokBaseUrl = "", key = "")
                is CloudAgentChannelChoice.Byok ->
                    createAndEnter("byok", choice.model, choice.baseUrl, choice.apiKey)
                CloudAgentChannelChoice.NeedConsent -> _state.value = UiState.NeedByokConsent
            }
        }
    }

    /** 授权弹窗 [使用网关]：放弃 BYOK 改走网关通道。 */
    fun useGatewayInstead() {
        if (_state.value !is UiState.NeedByokConsent) return
        viewModelScope.launch { createAndEnter("gateway", model = "", byokBaseUrl = "", key = "") }
    }

    private suspend fun createAndEnter(channel: String, model: String, byokBaseUrl: String, key: String) {
        api.createSession(appToken, channel, model, byokBaseUrl)
            .onSuccess { json ->
                val sessionId = json.optString("session_id")
                if (sessionId.isBlank()) {
                    _state.value = UiState.Failed("bad_session_response")
                    return@onSuccess
                }
                byokApiKey = key
                _state.value = UiState.Chat(
                    sessionId = sessionId,
                    channel = channel,
                    messages = emptyList(),
                    inFlight = false,
                )
            }
            .onFailure { e ->
                _state.value = UiState.Failed(e.message ?: "create_session_failed")
            }
    }

    fun send(text: String) {
        val chat = _state.value as? UiState.Chat ?: return
        if (chat.inFlight || text.isBlank()) return
        turnJob = viewModelScope.launch {
            append(UiMessage(msgSeq.incrementAndGet(), Role.USER, text))
            setInFlight(true)
            // 回合开始清空步骤时间线（上回合残留不跨回合）
            stepTimeline.clear()
            _steps.value = emptyList()
            // 图集卡透出：工具命中时把资产列表插为媒体卡消息（数据不出设备，回传服务器仍是
            // 纯文本计数）；回合结束 finally 摘除，避免跨回合残留（stop() 取消路径也走 finally）。
            cloudAgentGalleryTools.mediaResultsListener = { query, assets, totalCount, isRefinement ->
                append(
                    UiMessage(
                        msgSeq.incrementAndGet(),
                        Role.MEDIA,
                        text = query,
                        media = MediaResultsUi(
                            query = query,
                            assets = assets.take(MAX_MEDIA_CARDS),
                            totalCount = totalCount,
                            isRefinement = isRefinement,
                        ),
                    )
                )
            }
            try {
                val result = turnRunner.runTurn(
                    appToken,
                    chat.sessionId,
                    text,
                    byokApiKey,
                ) { event ->
                    when (event) {
                        is CloudTurnEvent.AssistantText ->
                            append(UiMessage(msgSeq.incrementAndGet(), Role.ASSISTANT, event.text))
                        is CloudTurnEvent.ToolCall ->
                            append(UiMessage(msgSeq.incrementAndGet(), Role.TOOL, event.tool))
                        is CloudTurnEvent.HtmlCard ->
                            append(
                                UiMessage(
                                    msgSeq.incrementAndGet(),
                                    Role.HTML_CARD,
                                    text = event.summary,
                                    htmlCard = HtmlCardUi(event.html, event.summary),
                                ),
                            )
                        is CloudTurnEvent.OpenUiCard -> append(
                            UiMessage(
                                msgSeq.incrementAndGet(),
                                Role.TOOL,
                                "render_ui_openui(len=${event.program.length})"
                            )
                        )
                        is CloudTurnEvent.OpenUiCardDelta -> Unit // 流式预览主页专属（openui P2），调试页忽略
                        is CloudTurnEvent.StepStarted -> {
                            val labelRes = agentStepLabelRes(event.tool)
                            if (labelRes == null) {
                                // server_time 契约不上时间线；未知工具跳过并告警一记（不污染进度表达）
                                if (event.tool != "server_time") {
                                    Logger.w(TAG, "cloud step: unknown tool ${event.tool}, skipped")
                                }
                            } else {
                                stepTimeline.start(event.stepId, stepLabel(labelRes))
                                _steps.value = stepTimeline.steps
                            }
                        }
                        is CloudTurnEvent.StepFinished -> {
                            // 未知 id（孤儿 observation / 上回合 pending 重放）被 reducer 忽略返回 null，刷快照无害
                            stepTimeline.finish(event.stepId, event.ok)?.let { step ->
                                Logger.d(TAG, "cloud step: ${step.label} -> ${step.status}")
                            }
                            _steps.value = stepTimeline.steps
                        }
                        is CloudTurnEvent.Error -> {
                            append(UiMessage(msgSeq.incrementAndGet(), Role.ERROR, event.message))
                            setInFlight(false)
                        }
                        is CloudTurnEvent.Done -> setInFlight(false)
                    }
                }
                // 流干净结束但无 Done 帧的兜底（Done 已清则为幂等 no-op）
                result.onSuccess { setInFlight(false) }
                result.onFailure { e ->
                    val msg = if (isRoundInFlight(e)) roundInFlightHint() else e.message ?: "network error"
                    append(UiMessage(msgSeq.incrementAndGet(), Role.ERROR, msg))
                    setInFlight(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // CloudAgentClient 非 2xx 直接抛异常（非 Result.failure），此处兜底上屏
                val msg = if (isRoundInFlight(e)) roundInFlightHint() else e.message ?: "network error"
                append(UiMessage(msgSeq.incrementAndGet(), Role.ERROR, msg))
                setInFlight(false)
            } finally {
                cloudAgentGalleryTools.mediaResultsListener = null
                // 回合结束（含 stop() 取消穿透路径）瞬态步骤清空
                stepTimeline.clear()
                _steps.value = emptyList()
            }
        }
    }

    /** 停止当前轮（取消穿透断流，ClaudeChatClient 先例）。 */
    fun stop() {
        turnJob?.cancel()
        setInFlight(false)
    }

    /**
     * OpenUI 调试注入（扫码/会话加载/画廊点选的最终程序上屏预览）：插一条 OPENUI_CARD
     * 内存消息，纯本地、不经网络、与云端回合无关；非 Chat 态 no-op。
     */
    fun injectOpenUiPreview(label: String, program: String) {
        if (program.isBlank()) return
        append(
            UiMessage(
                msgSeq.incrementAndGet(),
                Role.OPENUI_CARD,
                text = label,
                openUiCard = OpenUiCardUi(program = program, label = label),
            ),
        )
    }

    override fun onCleared() {
        finishSessionBestEffort()
    }

    /**
     * 终结云端会话（2026-11-07 会话卫生）：尽力而为通知服务端 close，
     * 防 agent_session 表 running 僵尸堆积。viewModelScope 已随 onCleared 取消，
     * finish 走一次性独立协程（一个往返即终，完成即回收）；失败仅记日志，
     * 服务端 stalled 语义兜底。internal 供单测直调（onCleared 是 protected）。
     */
    internal fun finishSessionBestEffort() {
        val chat = _state.value as? UiState.Chat ?: return
        val token = appToken
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { api.finish(token, chat.sessionId) }
                .onFailure { error -> Logger.w(TAG, "finish cloud session failed: ${error.message}") }
        }
    }

    /**
     * 判定失败是否为 409 round_in_flight（服务端 per-user 跑轮互斥闸）。
     * CloudAgentClient 非 2xx 抛 RuntimeException("HTTP 409: {json}")，匹配双特征防误判。
     */
    private fun isRoundInFlight(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("HTTP 409") && msg.contains("round_in_flight")
    }

    private fun append(message: UiMessage) {
        // CAS：onEvent 在网络线程，读-改-写须原子化；非 Chat 态（stop/失败后的迟到帧）为 no-op
        _state.update { current ->
            if (current is UiState.Chat) current.copy(messages = current.messages + message) else current
        }
    }

    private fun setInFlight(inFlight: Boolean) {
        _state.update { current ->
            if (current is UiState.Chat) current.copy(inFlight = inFlight) else current
        }
    }
}

/** 媒体卡横滑展示上限（与主聊天页 ChatViewModel.MAX_CARDS 同约定）。 */
private const val MAX_MEDIA_CARDS = 20

private const val TAG = "PoLang:CloudAgent"
