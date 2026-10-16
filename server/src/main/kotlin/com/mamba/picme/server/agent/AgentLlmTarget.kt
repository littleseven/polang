package com.mamba.picme.server.agent

import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig

/**
 * 一次云端 Agent 运行的 LLM 目标（ADR-017 双通道 × ADR-018 决策 4）。
 *
 * - GATEWAY：由生效渠道解析（[fromChannel]），apiKey 来自渠道配置（DB 托管）；
 * - BYOK：apiKey 来自请求头透传，**仅存活在内存**——本对象不序列化、不落库、不进日志。
 *
 * @param baseUrl OpenAI 兼容 API 根（Koog 客户端自行追加 chat/completions）
 * @param model 上游模型名（网关渠道已完成 modelMap 映射）
 */
data class AgentLlmTarget(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val authStyle: AuthStyle = AuthStyle.BEARER,
)

object AgentTargetResolver {

    /**
     * 网关渠道 → 运行目标。null = 渠道不可用（token 缺失 / 模型不支持且无兜底），
     * 路由层据此回 503/400（语义对齐 LlmProxy）。
     */
    fun fromChannel(channel: ChannelConfig, requestedModel: String): AgentLlmTarget? {
        if (channel.apiToken.isBlank()) return null
        val upstreamModel = channel.modelMap[requestedModel]
            ?: channel.defaultModel.takeIf { it.isNotBlank() }
            ?: return null
        return AgentLlmTarget(
            baseUrl = apiRootOf(channel.baseUrl),
            apiKey = channel.apiToken,
            model = upstreamModel,
            authStyle = channel.authStyle,
        )
    }

    /** BYOK 透传：key 仅经此进入内存，调用方保证不落库（守卫测试钉死）。 */
    fun byok(apiKey: String, baseUrl: String, model: String): AgentLlmTarget =
        AgentLlmTarget(baseUrl = apiRootOf(baseUrl), apiKey = apiKey, model = model, authStyle = AuthStyle.BEARER)

    /**
     * 渠道/用户配置存的是完整 endpoint（…/chat/completions），Koog 客户端要 API 根。
     * 幂等：不带该后缀的原样返回。
     */
    internal fun apiRootOf(baseUrl: String): String =
        baseUrl.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("chat/completions").trimEnd('/') + "/"
}
