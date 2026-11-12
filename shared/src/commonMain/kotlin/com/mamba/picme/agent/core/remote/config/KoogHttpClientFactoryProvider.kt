package com.mamba.picme.agent.core.remote.config

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * 创建 Koog HTTP 客户端工厂。双端均显式构造（绕开 Koog 1.1.1 ServiceLoader 缺陷：
 * `HttpClientFactoryResolver.resolve()` 经 `java.util.ServiceLoader` 找
 * `KoogHttpClient.Factory` provider，而 Koog 1.1.1 的 http-client-ktor-android 变体未发布
 * `META-INF/services/ai.koog.http.client.KoogHttpClient$Factory`（KMP android 发布缺陷），
 * Android runtime 下 ServiceLoader 永远空 → "No KoogHttpClient.Factory provider found"
 *（真机实测 2026-08-07 复现）。显式构造也更利于 R8：无需为 ServiceLoader provider 加 keep）。
 *
 * 时效（2026-09-25 核实）：1.3.0 的 http-client-ktor-android aar 内 classes.jar **仍无**
 * `META-INF/services` 条目——缺陷未修，显式构造在 1.3.0 下继续必要，勿因升级误删。
 *
 * @param extraHeaders 注入到每个请求的附加头（如网关鉴权头 `X-App-Token` / `X-Device-Id`）。
 * 空 map 时直接返回默认 Ktor 工厂，零额外开销（语义对齐 runtime-core `RemoteModelFactory` 现状）。
 */
expect fun createKoogHttpClientFactory(extraHeaders: Map<String, String> = emptyMap()): KoogHttpClient.Factory

/**
 * 给 Koog HttpClient 工厂注入额外请求 header（picme-server 网关鉴权 `X-App-Token` / `X-Device-Id`）。
 *
 * 自 runtime-core `RemoteModelFactory.kt` 私有实现原样提升（纯 Koog API + kotlinx-serialization，
 * 无平台依赖）。`OpenAIClientSettings` 无 header 参数；`OpenAILLMClient(apiKey, settings, factory)`
 * 的 apiKey 只派生 `Authorization`。网关要求的自定义 header 经此装饰器合并进
 * `KoogHttpClient.Factory.create` 的 `headers` 形参（位置参数透传，authHeaderValue 等 7 个其余
 * 参数原样转交委托工厂——auth 仍走 apiKey 标准路径，不在此重写）。
 *
 * `headers + extraHeaders`：委托工厂（默认 Ktor）传入的 headers 全保留，extraHeaders 同名键覆盖
 * （本场景 extraHeaders 仅含网关鉴权键，不与默认 headers 冲突）。
 */
internal class HeaderInjectingHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val extraHeaders: Map<String, String>,
) : KoogHttpClient.Factory {
    override fun create(
        baseURL: String,
        authHeaderValue: String,
        headers: Map<String, String>,
        queryParams: Map<String, String>,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
        requestTimeoutMs: Long,
        json: Json,
    ): KoogHttpClient = delegate.create(
        baseURL,
        authHeaderValue,
        headers + extraHeaders,
        queryParams,
        connectTimeoutMs,
        socketTimeoutMs,
        requestTimeoutMs,
        json,
    )
}

/**
 * [LlmWireTap] 注入的工厂装饰器（[RemoteModelFactory.createKoogExecutor] 默认路径按需包装）：
 * 把委托工厂产物再包一层 [WireTapKoogHttpClient] 旁路观测。tap 未注入（null）时工厂层
 * 零包装零开销，语义与装饰前逐字节一致。
 */
internal class WireTapHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val tap: LlmWireTap,
) : KoogHttpClient.Factory {
    override fun create(
        baseURL: String,
        authHeaderValue: String,
        headers: Map<String, String>,
        queryParams: Map<String, String>,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
        requestTimeoutMs: Long,
        json: Json,
    ): KoogHttpClient = WireTapKoogHttpClient(
        delegate.create(
            baseURL,
            authHeaderValue,
            headers,
            queryParams,
            connectTimeoutMs,
            socketTimeoutMs,
            requestTimeoutMs,
            json,
        ),
        tap,
    )
}

/**
 * [LlmWireTap] 的客户端装饰器：`post`/`sse` 出站请求体（Koog JSON 请求体恒为 String，
 * 非文本体跳过）旁路给 [LlmWireTap.onRequest]；`sse` 的逐帧原始 data 行在 Koog 自己的
 * decode lambda **前**同步旁路给 [LlmWireTap.onSseDataLine]。
 *
 * 设计约束（生产路径硬红线）：
 * - **不包装 Flow、不引入协程/取消/异常透明性变化**——只在 decode lambda 外再套一层
 *   同步函数（tap 调用 runCatching 包裹，异常绝不上抛），Koog 消费侧时序语义不变；
 * - 每帧开销 = 一次子串扫描（O(帧长)，远小于 Koog 随后的 JSON 反序列化）；
 * - `lines`（非 LLM 推理路径）不包装。
 */
internal class WireTapKoogHttpClient(
    private val delegate: KoogHttpClient,
    private val tap: LlmWireTap,
) : KoogHttpClient by delegate {

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        captureRequest(path, requestBody)
        return delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> {
        captureRequest(path, requestBody)
        return delegate.sse(
            path,
            requestBody,
            requestBodyType,
            dataFilter,
            { line ->
                runCatching { tap.onSseDataLine(line) }
                decodeStreamingResponse(line)
            },
            processStreamingChunk,
            parameters,
            headers,
        )
    }

    private fun captureRequest(path: String, body: Any?) {
        val bodyJson = body as? String ?: return
        runCatching { tap.onRequest(path, bodyJson) }
    }
}
