package com.mamba.picme.core.agenttools

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 云端 Agent 工具桥的端侧执行器（spec §5）：tool_request 帧的 tool 名 → 注入的相册工具 handler。
 *
 * 三个 handler **无默认实现、必须显式注入**：生产接线（AppContainer）走
 * [CloudAgentGalleryTools] 无头直执（绕过场景注册表，对齐 iOS IosCloudAgentToolBridge）。
 * 历史上默认委托 ChatToolService 的 @Tool 方法（经 CapabilityRegistry scene=CHAT dispatch），
 * 因 chat 能力 Delegate 仅 ChatScreen 存活期绑定 + 云端 Agent 页非 CHAT 场景，命令被注册表
 * 入队并立即回「正在切换页面」占位文案，永不真正执行——defaults 即该故障的温床，已移除。
 *
 * M2 只读三件套；写类工具（delete/share/select）不在映射表——云端会话里它们即未知工具。
 * 结果截断按字符计（32_000，AppToolExecutor 32KB 语义的文本版——字节截断会切坏 UTF-8）。
 */
class DeviceToolExecutor(
    private val gallerySummary: suspend () -> String,
    private val searchMedia: suspend (String, String, String, String, String) -> String,
    private val refineSearch: suspend (String, String, String) -> String,
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
