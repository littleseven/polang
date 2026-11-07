package com.mamba.picme.server.agent

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.agent.core.remote.config.RemoteProtocol
import com.mamba.picme.server.llm.AuthStyle
import kotlinx.serialization.json.Json

/**
 * 云端 Agent 的 Koog 执行器工厂（分层 runtime M1 起为 shared `RemoteModelFactory` 的
 * 适配器：capabilities 三件套/temperature 钳制/thinking=disabled 注入/maxTokens
 * 全部端云同源，本类只剩两个云端关切）。
 *
 * - CF_AIG 渠道：Koog 标准路径会把 apiKey 塞进 Authorization 头，与 Cloudflare AIG 的
 *   cf-aig-authorization 约定不符——经 [CfAigKoogHttpClientFactory] 逐请求改写
 * （对齐 LlmProxy 的 CF_AIG 行为：只发 cf-aig-authorization，不发 Authorization）。
 * - 目标映射：`AgentLlmTarget`（渠道托管密钥/BYOK 透传）→ shared `RemoteModelConfig`。
 *   providerId 恒非空：shared 对空 providerId 保留「旧配置注入 thinking=disabled」的
 *   历史行为，云端判定改由 baseUrl host 标记规则精确接管（与原
 *   THINKING_DISABLED_HOSTS 口径一致）。
 * - M1 仅 OpenAI 兼容协议（网关渠道与 BYOK 直连的 DeepSeek/Kimi/OpenAI 通用）；
 *   CLAUDE 原生协议为后续里程碑（shared 工厂已支持，接入即得）。
 */
class AgentExecutorFactory(
    private val httpClientFactory: KoogHttpClient.Factory = KtorKoogHttpClient.Factory(),
) {

    data class BuiltExecutor(
        val executor: PromptExecutor,
        val model: LLModel,
        val params: LLMParams,
    )

    fun create(target: AgentLlmTarget): BuiltExecutor {
        val factory = when (target.authStyle) {
            AuthStyle.BEARER -> httpClientFactory
            AuthStyle.CF_AIG -> CfAigKoogHttpClientFactory(httpClientFactory)
        }
        val bundle = RemoteModelFactory.createKoogExecutor(
            RemoteModelConfig(
                modelId = target.model,
                providerId = PROVIDER_ID,
                protocol = RemoteProtocol.OPENAI,
                apiKey = target.apiKey,
                baseUrl = target.baseUrl,
                chatCompletionsPath = target.chatCompletionsPath,
            ),
            httpClientFactory = factory,
        )
        return BuiltExecutor(bundle.executor, bundle.model, bundle.baseParams)
    }

    private companion object {
        const val PROVIDER_ID = "server-cloud-agent"
    }
}

/**
 * Cloudflare AIG 鉴权包装（对齐 LlmProxy 的 CF_AIG 行为：只发 cf-aig-authorization）。
 *
 * 注入点实证（Koog 1.3.0 源码核实）：AbstractOpenAILLMClient 把
 * `Authorization: Bearer <apiKey>` 放进 **create 期** headers map，KtorKoogHttpClient
 * 将其落入 Ktor defaultRequest（每请求生效）。故改写也在 create 期完成——
 * 剥离 Authorization，替换为 cf-aig-authorization。
 */
private class CfAigKoogHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
) : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient {
        val token = headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value?.removePrefix("Bearer ")?.trim().orEmpty()
        val rewritten = headers.filterKeys { !it.equals("Authorization", ignoreCase = true) } +
            ("cf-aig-authorization" to "Bearer $token")
        return delegate.create(
            clientName, baseUrl, rewritten, queryParameters,
            requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json,
        )
    }
}
