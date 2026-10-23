package com.mamba.picme.features.cloudagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.BuildConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.core.agenttools.CloudAgentGalleryTools
import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.domain.chat.MediaResultsUi
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
) : ViewModel() {

    /** 媒体卡消息经 [media] 携带渲染数据、H5 卡消息经 [htmlCard] 携带；其余 role 恒为 null。 */
    data class UiMessage(
        val id: Long,
        val role: Role,
        val text: String,
        val media: MediaResultsUi? = null,
        val htmlCard: HtmlCardUi? = null,
    )

    /** 云端 render_html 产物卡的 UI 数据（HTML 渲染前由 Screen 过 HtmlCardSanitizer）。 */
    data class HtmlCardUi(val html: String, val summary: String)

    enum class Role { USER, ASSISTANT, TOOL, ERROR, MEDIA, HTML_CARD }

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
                        is CloudTurnEvent.A2uiCard -> append(
                            UiMessage(msgSeq.incrementAndGet(), Role.TOOL, "render_ui(surface=${event.surfaceId})")
                        )
                        is CloudTurnEvent.Error -> {
                            append(UiMessage(msgSeq.incrementAndGet(), Role.ERROR, event.message))
                            setInFlight(false)
                        }
                        CloudTurnEvent.Done -> setInFlight(false)
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
            }
        }
    }

    /** 停止当前轮（取消穿透断流，ClaudeChatClient 先例）。 */
    fun stop() {
        turnJob?.cancel()
        setInFlight(false)
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
