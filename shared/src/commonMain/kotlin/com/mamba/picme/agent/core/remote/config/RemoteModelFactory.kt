package com.mamba.picme.agent.core.remote.config

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.params.additionalPropertiesOf
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecorder
import kotlin.concurrent.Volatile

/**
 * 远程模型工厂
 *
 * 统一管理远程推理参数（temperature、maxTokens 等）的创建和约束。
 * 所有远程推理路径共用此工厂，确保参数一致性，避免分散维护。
 *
 * ### 模型参数约束
 * Kimi K2.6 仅支持 temperature=1，其他值会导致 API 返回 400001 错误。
 * 此类约束在此集中管理，新增模型兼容逻辑只需修改此文件。
 */
object RemoteModelFactory {

    /**
     * 远程 LLM 调用记录接收端。由 :androidApp 在 Application 启动时注入（全构建注入）。
     * 为 null 时不录制。
     */
    @Volatile
    var recorder: LlmCallRecorder? = null

    /**
     * 是否记录消息全文（request messages / response text 等）。
     * DEBUG 构建置 true 记录全文；release 构建置 false 只落纯指标，
     * **绝不落消息内容**（隐私红线）。由 :androidApp 注入 recorder 时一并设置。
     */
    @Volatile
    var captureContent: Boolean = true

    /**
     * 根据模型 ID 获取合法 temperature 值。
     *
     * 某些模型对 temperature 有特殊约束（如 Kimi K2.6 仅接受 1.0），
     * 此方法将请求值钳制为模型支持的合法值。
     *
     * @param modelId 模型 ID（如 "kimi-k2.6"）
     * @param requested 请求的 temperature 值，null 时使用默认值 0.7
     * @return 钳制后的合法 temperature 值
     */
    fun clampTemperature(modelId: String, requested: Double? = null): Double {
        return if (modelId.contains("kimi-k2.6", ignoreCase = true)) 1.0 else (requested ?: 0.7)
    }

    // ── Koog（原 :agent-core 已删除；Koog 为唯一 Agent 执行路径）─────────────

    /**
     * Koog 执行器组装产物：Phase 4 chat 链路用它构建 [ai.koog.agents.core.agent.AIAgent]。
     *
     * - [executor]：单模型 PromptExecutor（按 config.protocol 分流：OPENAI 走 OpenAI 兼容
     *   客户端，CLAUDE 走 Anthropic 原生 Messages 客户端，均接自定义 baseUrl）。
     * - [model]：LLM 标识（provider 随协议：OpenAI / Anthropic，id=模型名），供 agent/executor 路由。
     * - [baseParams]：基础推理参数（temperature 钳制 + 已知兼容供应商的 thinking 禁用）。
     */
    public data class KoogExecutorBundle(
        public val executor: PromptExecutor,
        public val model: LLModel,
        public val baseParams: LLMParams,
    )

    /**
     * 创建 Koog 执行器包。
     *
     * - 协议分流：config.protocol == OPENAI → [OpenAILLMClient]（[OpenAIClientSettings] 仅传
     *   baseUrl，DeepSeek/Kimi/网关/OpenAI 官方通用）；CLAUDE → [AnthropicLLMClient]
     *   （Anthropic 原生 Messages 协议，[AnthropicClientSettings] 传 baseUrl +
     *   modelVersionsMap 把自建 LLModel 映射为模型 id 字符串——Koog 默认版本表只认其预定义
     *   LLModel 实例，自定义模型 id 不映射会抛 "Unsupported model"）。
     * - 网关鉴权 header：[extraHeaders]（如 `X-App-Token` / `X-Device-Id`）经同包的
     *   [createKoogHttpClientFactory] 构造工厂（非空时内部包 HeaderInjectingHttpClientFactory）——
     *   auth 仍由 apiKey 经 client 标准路径注入，extraHeaders 合并进 factory.create 的
     *   headers map。空 map 时直接用默认工厂，零额外开销。
     * - `thinking.type=disabled`：DeepSeek 系定制参数，经 `additionalProperties` 平铺到请求体
     *   顶层。仅对已知兼容供应商（tokenhub/kimi/deepseek）与无 providerId 的旧配置注入；
     *   OpenAI 官方与 Anthropic 收到未知参数会 400，CLAUDE 协议更不适用，均不注入。
     * - `clampTemperature`：kimi-k2.6 钳到 1.0，其余 0.7（与旧链路一致）。
     */
    public fun createKoogExecutor(
        config: RemoteModelConfig,
        extraHeaders: Map<String, String> = emptyMap(),
        /**
         * 自定义 Koog HTTP 工厂（分层 runtime M1：server 传 CF_AIG 鉴权改写包装或裸
         * Ktor 工厂）。null = 默认路径 `createKoogHttpClientFactory(extraHeaders)`。
         */
        httpClientFactory: KoogHttpClient.Factory? = null,
    ): KoogExecutorBundle {
        val effectiveApiKey = config.apiKey.ifEmpty { "gateway-auth" }
        // ⚠️ 不能用 HttpClientFactoryResolver.resolve()——它经 java.util.ServiceLoader 找
        // KoogHttpClient.Factory provider，而 Koog 1.1.1 的 http-client-ktor-android 变体**未发布**
        // META-INF/services/ai.koog.http.client.KoogHttpClient$Factory（KMP android 发布缺陷），
        // Android runtime 下 ServiceLoader 永远空 → "No KoogHttpClient.Factory provider found"
        //（真机实测 2026-08-07 复现；Ktor 自身的 HttpClientEngineContainer provider 正常在 APK 内）。
        // createKoogHttpClientFactory（同包）内部显式构造 KtorKoogHttpClient.Factory()
        // 绕过（显式构造也更利于 R8：无需为 ServiceLoader provider 加 keep），并按需包
        // HeaderInjectingHttpClientFactory 注入网关 header。Anthropic client 同理必须显式传工厂。
        val factory = httpClientFactory ?: createKoogHttpClientFactory(extraHeaders)
        val model: LLModel
        val client = when (config.protocol) {
            RemoteProtocol.CLAUDE -> {
                model = LLModel(
                    provider = LLMProvider.Anthropic,
                    id = config.modelId,
                    // Anthropic client 入口 gate：execute 无条件 require Completion + Tools；
                    // executeStreaming require Completion。Thinking 仅在发送 reasoning 内容时
                    // 检查（本链路不开 extended thinking，不声明）。
                    capabilities = listOf(
                        LLMCapability.Completion,
                        LLMCapability.Tools,
                        LLMCapability.Temperature,
                    ),
                    maxOutputTokens = MAX_TOKENS.toLong(),
                )
                AnthropicLLMClient(
                    effectiveApiKey,
                    AnthropicClientSettings(
                        baseUrl = config.baseUrl,
                        modelVersionsMap = mapOf(model to config.modelId),
                    ),
                    factory,
                )
            }
            RemoteProtocol.OPENAI -> {
                model = LLModel(
                    provider = LLMProvider.OpenAI,
                    id = config.modelId,
                    // 声明能力，否则 Koog 1.1.1 的 capability gate 链会逐个抛错（真机逐个撞出）：
                    // 1. OpenAILLMClient.determineParams：capabilities 为空走兜底抛
                    //    "Cannot determine proper LLM params"（需 OpenAIEndpoint.Completions）。
                    // 2. AbstractOpenAILLMClient.executeStreaming / getResponse 开头
                    //    requireCapability(Completion) → "does not support completion"。
                    // 3. getResponse 内 tools 非空时 requireCapability(Tools) →
                    //    "does not support tools"；消息转换序列化 tool 历史也查 Tools。
                    // 🔴 不加 Responses（切 Responses API）、Thinking（与 thinking.type=disabled
                    //    冲突）；无需 MultipleChoices/Vision/Audio/Document（chat 只文本+工具）。
                    capabilities = listOf(
                        LLMCapability.Completion,
                        LLMCapability.Tools,
                        LLMCapability.OpenAIEndpoint.Completions,
                    ),
                    maxOutputTokens = MAX_TOKENS.toLong(),
                )
                val settings = if (config.chatCompletionsPath.isBlank()) {
                    OpenAIClientSettings(baseUrl = config.baseUrl)
                } else {
                    OpenAIClientSettings(
                        baseUrl = config.baseUrl,
                        chatCompletionsPath = config.chatCompletionsPath,
                    )
                }
                OpenAILLMClient(
                    effectiveApiKey,
                    settings,
                    factory,
                )
            }
        }
        val executor: PromptExecutor = MultiLLMPromptExecutor(client)
        val params = LLMParams(
            temperature = clampTemperature(config.modelId),
            maxTokens = MAX_TOKENS,
            additionalProperties = if (shouldInjectThinkingDisabled(config)) {
                additionalPropertiesOf("thinking" to mapOf("type" to "disabled"))
            } else {
                null
            },
        )
        return KoogExecutorBundle(executor, model, params)
    }

    /**
     * 是否注入 DeepSeek 系定制参数 `thinking.type=disabled`。
     *
     * 并集规则（分层 runtime M1）：providerId 白名单 ‖ 空 providerId 旧配置历史行为 ‖
     * baseUrl host 标记。OpenAI 官方（收到未知参数 400）与 Anthropic 原生协议
     *（无此参数语义）不注入。云端 Agent 的 providerId 恒非空（"server-cloud-agent"），
     * 注入判定由 host 标记精确接管；端侧存量路径不受影响。
     */
    private fun shouldInjectThinkingDisabled(config: RemoteModelConfig): Boolean {
        if (config.protocol != RemoteProtocol.OPENAI) return false
        return config.providerId.isBlank() ||
            config.providerId in THINKING_DISABLED_PROVIDERS ||
            THINKING_DISABLED_HOST_MARKERS.any { config.baseUrl.contains(it, ignoreCase = true) }
    }

    private val THINKING_DISABLED_PROVIDERS = setOf(
        "tencent-tokenhub",
        "kimi-official",
        "deepseek-official",
    )

    /**
     * DeepSeek 系上游的 baseUrl host 标记（分层 runtime M1 自 server
     * AgentExecutorFactory.THINKING_DISABLED_HOSTS 收口，两表口径一致）。
     */
    private val THINKING_DISABLED_HOST_MARKERS = listOf("tokenhub", "moonshot", "deepseek")

    // object 内可直接声明 const val（companion 仅在 class 内需要；standalone object 内
    // 嵌套 companion 非法——曾踩此编译错）。createKoogExecutor 引用此常量。
    private const val MAX_TOKENS: Int = 4096
}

// 注：私有 `HeaderInjectingHttpClientFactory` 与本类同模块同包（KoogHttpClientFactoryProvider.kt，
// internal，由 createKoogHttpClientFactory 按需包装）——本文件已随 KMP 抽取迁 :shared commonMain。
