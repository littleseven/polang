package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
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
 * 工具名/错误前缀/截断契约收口 shared [CloudAgentToolContract]（M3，双端 + server 同源）。
 */
class DeviceToolExecutor(
    private val gallerySummary: suspend () -> String,
    private val searchMedia: suspend (String, String, String, String, String) -> String,
    private val refineSearch: suspend (String, String, String) -> String,
) {

    suspend fun execute(tool: String, args: JSONObject): String {
        val result = try {
            when (tool) {
                CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY -> gallerySummary()
                CloudAgentToolContract.TOOL_SEARCH_MEDIA -> searchMedia(
                    args.optString("query"),
                    args.optString("person"),
                    args.optString("fromMs"),
                    args.optString("toMs"),
                    args.optString("hasFace"),
                )
                CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH -> refineSearch(
                    args.optString("constraint"),
                    args.optString("fromMs"),
                    args.optString("toMs"),
                )
                else -> return CloudAgentToolContract.unknownTool(tool)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return "${CloudAgentToolContract.ERROR_PREFIX} ${e.message ?: e.javaClass.simpleName}"
        }
        return CloudAgentToolContract.truncate(result)
    }
}
