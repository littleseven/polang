package com.mamba.picme.agent.core.remote.config

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * LLM 线路窃听器（wire tap）：远程推理 HTTP 通道的旁路观测缝，不参与任何请求语义。
 *
 * 注入点 = [RemoteModelFactory.createKoogExecutor] 的 HttpClient 工厂路径：[WireTapHttpClientFactory]
 * （同包 `KoogHttpClientFactoryProvider.kt`）装饰 Koog HttpClient——
 * - `post`/`sse` 的出站请求体（Koog 各 LLM client 均以序列化后的 JSON **String** 作 body）
 *   → [onRequest]；
 * - `sse` 的逐帧原始 data 行 → [onSseDataLine]（包在 Koog 自己的 decode lambda 前同步调用，
 *   不包装 Flow、不引入协程/取消路径变化）。
 *
 * 用途（chat 可观测性缺口清偿，2026-11-12）：
 * - DEBUG 录制请求体（`LlmCallRecord.requestJson`，此前恒 `""`——Koog EventHandler 不暴露原始请求）；
 * - 生产采集 SSE usage 帧的 `cached_tokens`（纯指标，对标 server 侧 `llm_call_log.cached_tokens`；
 *   Koog 1.3.0 `createMetaInfo` 丢弃该字段）。OpenAI 兼容流式下 usage 只在部分供应商/配置的
 *   尾帧出现（DeepSeek/Moonshot 恒带，OpenAI 官方需 stream_options），未出现即 [latestCachedTokens]
 * 返回 null，属正常降级。
 *
 * 实现契约（🔴 必须遵守——窃听失败不得影响推理链路）：
 * - 线程安全（HttpClient 可能被多协程并发复用）；
 * - 非阻塞、O(帧长) 开销（生产硬红线：包装层开销必须可忽略）；
 * - 绝不抛异常（包装层已 runCatching 兜底，实现侧仍应自吞）。
 */
interface LlmWireTap {

    /** 出站请求体捕获（[bodyJson] = Koog 序列化后的完整请求 JSON 文本）。 */
    fun onRequest(url: String, bodyJson: String)

    /** SSE 原始 data 帧透传（每帧一次，先于 Koog 解码调用；实现侧自行扫描 usage）。 */
    fun onSseDataLine(line: String)

    /** 最近一次捕获的请求（含单调序号，供消费方检测陈旧）；未捕获 / 未开启保留时 null。 */
    fun latestRequest(): CapturedRequest?

    /**
     * 最近一次请求命中的 usage `cached_tokens`；本次请求未出现 usage 帧时 null
     * （每次 [onRequest] 清零——usage 只属于当次请求，防跨请求误归因）。
     */
    fun latestCachedTokens(): Int?
}

/** [LlmWireTap.latestRequest] 快照：[seq] 随捕获单调递增，[url]/[bodyJson] 为当次出站原文。 */
data class CapturedRequest(val seq: Long, val url: String, val bodyJson: String)

/**
 * 线程安全 latest 槽实现：单槽保存最近一次请求（`@Volatile` 引用写原子）+ [AtomicLong] 序号。
 *
 * - [captureRequests] = false（release 构建）：[onRequest] 即丢——请求体含消息全文，
 *   隐私红线决定不保留，[latestRequest] 恒 null；仅保留 SSE 指标扫描（cached_tokens 是
 *   纯指标，与 promptTokens 同级，release 允许采集）。
 * - [onSseDataLine]：子串命中 `"cached_tokens"` 才解析（无正则、O(帧长)、不缓存帧内容）。
 */
@OptIn(ExperimentalAtomicApi::class)
class LatestLlmWireTap(
    private val captureRequests: Boolean = true,
) : LlmWireTap {

    private val seq = AtomicLong(0L)

    @Volatile
    private var latest: CapturedRequest? = null

    @Volatile
    private var cachedTokens: Int? = null

    override fun onRequest(url: String, bodyJson: String) {
        cachedTokens = null
        if (captureRequests) {
            latest = CapturedRequest(seq.incrementAndFetch(), url, bodyJson)
        }
    }

    override fun onSseDataLine(line: String) {
        cachedTokens = extractCachedTokens(line) ?: return
    }

    override fun latestRequest(): CapturedRequest? = latest

    override fun latestCachedTokens(): Int? = cachedTokens
}

/**
 * 从单条 SSE data 帧文本提取 `usage.prompt_tokens_details.cached_tokens`（或任意层级同名键）。
 *
 * 子串定位 `"cached_tokens"` → 跳过冒号与空白 → 读取连续数字。命中即解析（无正则、
 * 无 JSON 全量反序列化、O(帧长)）；未命中返回 null。非数字值（null/false 等）返回 null。
 */
internal fun extractCachedTokens(line: String): Int? {
    val keyIndex = line.indexOf(CACHED_TOKENS_KEY)
    if (keyIndex < 0) return null
    var i = line.indexOf(':', keyIndex + CACHED_TOKENS_KEY.length)
    if (i < 0) return null
    i++
    while (i < line.length && line[i].isWhitespace()) i++
    val start = i
    while (i < line.length && line[i].isDigit()) i++
    if (i == start) return null
    return line.substring(start, i).toIntOrNull()
}

private const val CACHED_TOKENS_KEY = "\"cached_tokens\""
