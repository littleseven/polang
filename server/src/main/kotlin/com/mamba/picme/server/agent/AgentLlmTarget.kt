package com.mamba.picme.server.agent

import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig

/**
 * 一次云端 Agent 运行的 LLM 目标（ADR-017 双通道 × ADR-018 决策 4）。
 *
 * - GATEWAY：由生效渠道解析（[fromChannel]），apiKey 来自渠道配置（DB 托管）；
 * - BYOK：apiKey 来自请求头透传，**仅存活在内存**——本对象不序列化、不落库、不进日志。
 *
 * @param baseUrl OpenAI 兼容 API 源点（scheme://host[:port]，不带路径）；
 *   Koog 客户端按 `baseUrl + chatCompletionsPath` 组完整 URL
 * @param model 上游模型名（网关渠道已完成 modelMap 映射）
 */
data class AgentLlmTarget(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val authStyle: AuthStyle = AuthStyle.BEARER,
    val chatCompletionsPath: String = "v1/chat/completions",
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
        val (origin, path) = splitEndpoint(channel.baseUrl)
        return AgentLlmTarget(
            baseUrl = origin,
            apiKey = channel.apiToken,
            model = upstreamModel,
            authStyle = channel.authStyle,
            chatCompletionsPath = path,
        )
    }

    /** BYOK 透传：key 仅经此进入内存，调用方保证不落库（守卫测试钉死）。 */
    fun byok(apiKey: String, baseUrl: String, model: String): AgentLlmTarget {
        val (origin, path) = splitEndpoint(baseUrl)
        return AgentLlmTarget(
            baseUrl = origin,
            apiKey = apiKey,
            model = model,
            authStyle = AuthStyle.BEARER,
            chatCompletionsPath = path,
        )
    }

    /**
     * 渠道/用户配置存的可能是完整 endpoint（…/chat/completions）、带版本路径的根
     * （…/v1）或纯源点；Koog 需要「源点 + chatCompletionsPath」两段式（其默认 path
     * 自带 v1/ 前缀，直接给带路径的 baseUrl 会拼出 /v1/v1/chat/completions——
     * 2026-10-17 真机遛狗实踩，上游 404 空 body）。
     *
     * 返回 Pair(origin, chatCompletionsPath)：
     * - `https://api.deepseek.com/v1/chat/completions` → `https://api.deepseek.com` + `v1/chat/completions`
     * - `https://api.deepseek.com/v1` → 同上
     * - `https://api.deepseek.com` → 同上（默认 v1 前缀）
     * - `https://gateway.ai.cloudflare.com/v1/<id>/picme/compat/chat/completions` → 源点 + `v1/<id>/picme/compat/chat/completions`
     */
    internal fun splitEndpoint(baseUrl: String): Pair<String, String> {
        val trimmed = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        val schemeIdx = trimmed.indexOf("://")
        require(schemeIdx > 0) { "bad baseUrl: $baseUrl" }
        val pathStart = trimmed.indexOf('/', startIndex = schemeIdx + 3)
        if (pathStart == -1) return trimmed to "v1/chat/completions"
        val origin = trimmed.substring(0, pathStart)
        val path = trimmed.substring(pathStart + 1).trim('/')
        return origin to if (path.isEmpty()) "v1/chat/completions" else "$path/chat/completions"
    }
}
