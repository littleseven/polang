package com.mamba.picme.features.cloudagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 云端 Agent 最小聊天页状态机（spec §5，调试入口实验页；内存态消息列表，不落 Room——镜像属 M3）。
 *
 * 生命周期：prepare()（通道解析 + 建会话，幂等）→ Chat 态 send()/stop() 循环。
 * 一轮只开一条 SSE：tool_request 帧到达即 launch 端执行 + 上行（不阻塞收帧循环）。
 * BYOK key 仅存本类私有字段（内存态），随 postMessage 头发送；绝不进日志（Logger 调用不含 key）。
 */
class CloudAgentChatViewModel(
    private val api: CloudAgentApi,
    private val deviceToolExecutor: DeviceToolExecutor,
    private val settingsRepository: UserSettingsRepository,
) : ViewModel() {

    data class UiMessage(val id: Long, val role: Role, val text: String)
    enum class Role { USER, ASSISTANT, TOOL, ERROR }

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
    private var msgSeq = 0L

    /** 入口：通道解析（spec §6 表）→ 建会话。幂等（非 Preparing 直接返回）。 */
    fun prepare() {
        if (_state.value !is UiState.Preparing) return
        viewModelScope.launch {
            val token = settingsRepository.serverAuthTokenFlow.first()
            if (token.isBlank()) {
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
                byokApiKey = key
                _state.value = UiState.Chat(
                    sessionId = json.optString("session_id"),
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
            append(UiMessage(++msgSeq, Role.USER, text))
            setInFlight(true)
            val result = api.postMessage(
                appToken,
                chat.sessionId,
                text,
                byokApiKey.takeIf { it.isNotBlank() },
            ) { frame -> onFrame(frame) }
            result.onFailure { e ->
                append(UiMessage(++msgSeq, Role.ERROR, e.message ?: "network error"))
                setInFlight(false)
            }
        }
    }

    /** 停止当前轮（取消穿透断流，ClaudeChatClient 先例）。 */
    fun stop() {
        turnJob?.cancel()
        setInFlight(false)
    }

    private fun onFrame(frame: CloudFrame) {
        val chat = _state.value as? UiState.Chat ?: return
        when (frame) {
            is CloudFrame.Event -> handleEvent(frame.event)
            is CloudFrame.ToolRequest -> {
                append(UiMessage(++msgSeq, Role.TOOL, frame.tool))
                // 端执行不阻塞收帧循环
                viewModelScope.launch {
                    val result = deviceToolExecutor.execute(frame.tool, frame.args)
                    api.postToolResult(
                        appToken,
                        chat.sessionId,
                        frame.requestId,
                        ok = !result.startsWith("error:"),
                        result = result,
                    ).onFailure { e -> Logger.w(TAG, "postToolResult failed: ${e.message}") }
                }
            }
            is CloudFrame.Done -> setInFlight(false)
            is CloudFrame.Error -> {
                append(UiMessage(++msgSeq, Role.ERROR, frame.message))
                setInFlight(false)
            }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: return
        when (event.optString("type")) {
            // 助手答复（user 回声跳过——本地已先上屏）
            "message" -> if (event.optString("role") == "assistant") {
                append(UiMessage(++msgSeq, Role.ASSISTANT, payload.optString("content")))
            }
            else -> Unit // action/observation/finish 不上屏（TOOL 行由 tool_request 帧驱动）
        }
    }

    private fun append(message: UiMessage) {
        val chat = _state.value as? UiState.Chat ?: return
        _state.value = chat.copy(messages = chat.messages + message)
    }

    private fun setInFlight(inFlight: Boolean) {
        val chat = _state.value as? UiState.Chat ?: return
        _state.value = chat.copy(inFlight = inFlight)
    }

    companion object {
        private const val TAG = "CloudAgent"
    }
}
