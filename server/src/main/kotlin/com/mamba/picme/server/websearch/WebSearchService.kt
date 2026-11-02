package com.mamba.picme.server.websearch

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * DeepSeek 网页搜索（/v1/tools/search，2026-10 开放，按次计费 5 元/千次成功请求）的服务端封装。
 *
 * 定位：主聊天链路 `web_search` 工具的上游——客户端工具经 `/v1/web-search` 打到本服务，
 * 本服务持 DeepSeek key 调 Search API（`need_content=true`），在服务端完成
 * 「正文 Markdown → 配图 URL 提取 + 摘要截断」的瘦身，回给端侧紧凑 JSON。
 * key 不落库、不走渠道体系（渠道 token 是网关/TokenHub 的，对 api.deepseek.com 无效），
 * 由 env `DEEPSEEK_SEARCH_API_KEY` 注入；未配置时路由 503 降级，端侧工具回不可用文案。
 *
 * 计费注意：仅 HTTP 200 且 results 非空的请求计费，失败/空结果免费（官方口径）。
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val siteName: String?,
    val date: String?,
    val images: List<String>,
    val contentExcerpt: String,
)

sealed interface SearchOutcome {
    data class Success(val results: List<WebSearchResult>) : SearchOutcome

    /** 上游不可用（余额不足 402 / 限流 429 / 网络异常等）——客户端据此降级纯文本回答。 */
    data class Unavailable(val reason: String) : SearchOutcome
}

/** 路由层依赖抽象：测试注入 fake，生产为 [WebSearchService]。 */
fun interface SearchExecutor {
    suspend fun search(query: String, count: Int): SearchOutcome
}

class WebSearchService(
    private val http: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : SearchExecutor {

    override suspend fun search(query: String, count: Int): SearchOutcome = try {
        val resp = http.post("$baseUrl/v1/tools/search") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            // 手写序列化（TextContent）：共享 httpClient 未装 ContentNegotiation，直接塞 JsonObject 会炸
            setBody(
                io.ktor.http.content.TextContent(
                    buildJsonObject {
                        put("query", query)
                        put("count", count.coerceIn(1, MAX_UPSTREAM_COUNT))
                        put("need_content", true)
                    }.toString(),
                    ContentType.Application.Json,
                ),
            )
        }
        when (resp.status.value) {
            200 -> SearchOutcome.Success(parseResults(resp.bodyAsText()))
            402 -> SearchOutcome.Unavailable("insufficient_balance")
            429 -> SearchOutcome.Unavailable("rate_limited")
            else -> SearchOutcome.Unavailable("upstream_${resp.status.value}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SearchOutcome.Unavailable(e.message ?: "network_error")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /** 上行 count 上限（官方 1~50；服务侧收敛，省 token 也控出图噪音）。 */
        const val MAX_UPSTREAM_COUNT = 10

        /** 回给端侧的结果条数上限。 */
        internal const val MAX_RESULTS = 5

        /** 每条结果配图 URL 上限（https 门控后）。 */
        internal const val MAX_IMAGES_PER_RESULT = 3

        /** 每条结果正文摘录上限（字符），防整页正文灌爆 LLM 上下文。 */
        internal const val MAX_CONTENT_EXCERPT_CHARS = 400

        private val json = Json { ignoreUnknownKeys = true }

        /** Markdown 图片语法 `![alt](url)` / `![alt](<url>)`，只收 https（与 render_ui Image 口径一致）。 */
        private val imageMarkdownRegex = Regex("""!\[[^\]]*\]\(\s*<?(https://[^\s)>"']+)""")

        /** 完整图片语法（含可选 title 与闭括号），excerpt 摘除用。 */
        private val fullImageSyntaxRegex = Regex("""!\[[^\]]*\]\([^)]*\)""")

        /** 从网页正文 Markdown 提取配图 URL（去重、https 门控、限量）。 */
        internal fun extractImageUrls(markdown: String, limit: Int = MAX_IMAGES_PER_RESULT): List<String> =
            imageMarkdownRegex.findAll(markdown)
                .map { it.groupValues[1] }
                .distinct()
                .take(limit)
                .toList()

        /** 正文 → 摘录：去图片语法、折叠空白、截断。 */
        internal fun excerpt(content: String): String =
            content
                .replace(fullImageSyntaxRegex, "")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(MAX_CONTENT_EXCERPT_CHARS)

        /** 上游 Search 响应 JSON → 瘦身后的结果集（解析失败/无 results 数组 = 空集，不炸）。 */
        internal fun parseResults(body: String): List<WebSearchResult> {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return emptyList()
            val results = root["results"] ?: return emptyList()
            return runCatching {
                results.jsonArray.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val url = (obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (title.isBlank() || url.isBlank()) return@mapNotNull null
                    val content = (obj["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    WebSearchResult(
                        title = title,
                        url = url,
                        snippet = (obj["snippet"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        siteName = (obj["site_name"] as? JsonPrimitive)?.contentOrNull,
                        date = (obj["date"] as? JsonPrimitive)?.contentOrNull,
                        images = extractImageUrls(content),
                        contentExcerpt = excerpt(content),
                    )
                }.take(MAX_RESULTS)
            }.getOrDefault(emptyList())
        }

        /** 端侧响应 JSON（紧凑结构，LLM 直接消费 images 字段嵌 Markdown 图片）。 */
        internal fun toClientJson(results: List<WebSearchResult>): JsonObject = buildJsonObject {
            put("status", "ok")
            putJsonArray("results") {
                results.forEach { r ->
                    add(
                        buildJsonObject {
                            put("title", r.title)
                            put("url", r.url)
                            put("snippet", r.snippet)
                            r.siteName?.let { put("site_name", it) }
                            r.date?.let { put("date", it) }
                            putJsonArray("images") { r.images.forEach { img -> add(JsonPrimitive(img)) } }
                            if (r.contentExcerpt.isNotBlank()) put("content_excerpt", r.contentExcerpt)
                        },
                    )
                }
            }
        }
    }
}
