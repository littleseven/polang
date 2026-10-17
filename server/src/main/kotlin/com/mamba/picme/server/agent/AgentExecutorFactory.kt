package com.mamba.picme.server.agent

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.params.additionalPropertiesOf
import com.mamba.picme.server.llm.AuthStyle
import kotlinx.serialization.json.Json

/**
 * 云端 Agent 的 Koog 执行器工厂（:shared RemoteModelFactory.createKoogExecutor 的服务端对映）。
 *
 * - M1 仅 OpenAI 兼容协议（网关渠道与 BYOK 直连的 DeepSeek/Kimi/OpenAI 通用）；
 *   CLAUDE 原生协议为后续里程碑。
 * - capabilities 三件套（Completion/Tools/OpenAIEndpoint.Completions）：Koog capability gate
 *   逐个 require，缺一直接抛错（:shared 真机踩坑记录，勿减）。
 * - CF_AIG 渠道：Koog 标准路径会把 apiKey 塞进 Authorization 头，与 Cloudflare AIG 的
 *   cf-aig-authorization 约定不符——经 [CfAigKoogHttpClientFactory] 逐请求改写
 * （对齐 LlmProxy 的 CF_AIG 行为：只发 cf-aig-authorization，不发 Authorization）。
 * - temperature 钳制（kimi-k2.6 只收 1.0）与 thinking=disabled 注入（DeepSeek 系定制），
 *   规则与 :shared RemoteModelFactory 同源。
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
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = target.model,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Tools,
                LLMCapability.OpenAIEndpoint.Completions,
            ),
            maxOutputTokens = MAX_TOKENS.toLong(),
        )
        val factory = when (target.authStyle) {
            AuthStyle.BEARER -> httpClientFactory
            AuthStyle.CF_AIG -> CfAigKoogHttpClientFactory(httpClientFactory)
        }
        val client = OpenAILLMClient(
            target.apiKey,
            OpenAIClientSettings(baseUrl = target.baseUrl, chatCompletionsPath = target.chatCompletionsPath),
            factory,
        )
        val params = LLMParams(
            temperature = clampTemperature(target.model),
            maxTokens = MAX_TOKENS,
            additionalProperties = if (shouldInjectThinkingDisabled(target.baseUrl)) {
                additionalPropertiesOf("thinking" to mapOf("type" to "disabled"))
            } else {
                null
            },
        )
        return BuiltExecutor(MultiLLMPromptExecutor(client), model, params)
    }

    private fun clampTemperature(modelId: String): Double =
        if (modelId.contains("kimi-k2.6", ignoreCase = true)) 1.0 else 0.7

    /** DeepSeek 系定制参数只注入已知兼容供应商（OpenAI 官方收到未知参数 400）。 */
    private fun shouldInjectThinkingDisabled(baseUrl: String): Boolean =
        THINKING_DISABLED_HOSTS.any { baseUrl.contains(it) }

    private companion object {
        const val MAX_TOKENS: Int = 4096
        val THINKING_DISABLED_HOSTS = listOf("tokenhub", "moonshot", "deepseek")
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
