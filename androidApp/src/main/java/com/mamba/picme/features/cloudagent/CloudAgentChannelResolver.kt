package com.mamba.picme.features.cloudagent

import com.mamba.picme.agent.core.remote.config.RemoteModelConfig

/** 云端会话通道选择（spec §6 表，建会话时一次定型，中途换供应商不影响进行中会话）。 */
sealed interface CloudAgentChannelChoice {
    /** 网关通道（M1 配额语义）。 */
    data object Gateway : CloudAgentChannelChoice

    /** BYOK 透传（model/baseUrl 建会话固化，apiKey 仅随 postMessage 头发送）。 */
    data class Byok(val model: String, val baseUrl: String, val apiKey: String) : CloudAgentChannelChoice

    /** BYOK 供应商但未授权 → 弹窗二选一（去授权 / 改用网关），不静默回退。 */
    data object NeedConsent : CloudAgentChannelChoice
}

object CloudAgentChannelResolver {
    /**
     * @param config 当前选中远程模型配置（null 或无 apiKey = 网关兜底，对齐 AiAgentUseCase 语义）
     * @param consent DataStore `cloud_agent_byok_consent`
     */
    fun resolve(config: RemoteModelConfig?, consent: Boolean): CloudAgentChannelChoice = when {
        config == null || config.apiKey.isBlank() -> CloudAgentChannelChoice.Gateway
        consent -> CloudAgentChannelChoice.Byok(config.modelId, config.baseUrl, config.apiKey)
        else -> CloudAgentChannelChoice.NeedConsent
    }
}
