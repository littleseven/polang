package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 云端 Agent 工具桥的端侧执行器（spec §5）：tool_request 帧的 tool 名 → ChatToolService 只读 handler。
 * 依赖以函数注入（AppToolExecutor 范式），纯 JVM 可测；生产默认委托 ChatToolService 单例。
 *
 * M2 只读三件套；写类工具（delete/share/select）不在映射表——云端会话里它们即未知工具。
 * 结果截断按字符计（32_000，AppToolExecutor 32KB 语义的文本版——字节截断会切坏 UTF-8）。
 */
class DeviceToolExecutor(
    private val gallerySummary: suspend () -> String =
        { ChatToolService.getInstance().getGallerySummary() },
    private val searchMedia: suspend (String, String, String, String, String) -> String =
        { query, person, fromMs, toMs, hasFace ->
            ChatToolService.getInstance().searchMedia(query, person, fromMs, toMs, hasFace)
        },
    private val refineSearch: suspend (String, String, String) -> String =
        { constraint, fromMs, toMs ->
            ChatToolService.getInstance().refineMediaSearch(constraint, fromMs, toMs)
        },
) {

    suspend fun execute(tool: String, args: JSONObject): String {
        val result = try {
            when (tool) {
                "get_gallery_summary" -> gallerySummary()
                "search_media" -> searchMedia(
                    args.optString("query"),
                    args.optString("person"),
                    args.optString("fromMs"),
                    args.optString("toMs"),
                    args.optString("hasFace"),
                )
                "refine_media_search" -> refineSearch(
                    args.optString("constraint"),
                    args.optString("fromMs"),
                    args.optString("toMs"),
                )
                else -> return "error: unknown_tool:$tool"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return "error: ${e.message ?: e.javaClass.simpleName}"
        }
        return truncate(result)
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text
        else text.take(MAX_RESULT_CHARS) + "\n…[truncated]"

    companion object {
        const val MAX_RESULT_CHARS = 32_000
    }
}
