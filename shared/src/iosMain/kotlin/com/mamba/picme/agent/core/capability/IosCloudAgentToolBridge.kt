package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.data.IosChatSearchBridge
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException

/**
 * 云端 Agent 工具桥（ADR-018 M2，iOS 对等端）—— tool_request 帧的端侧执行器。
 *
 * 对位 Android `core/agenttools/DeviceToolExecutor`（ChatToolService 只读三件套），
 * 但**不经 CapabilityRegistry 场景门**：Android 侧 registry 要求 CHAT 场景，云端调试页
 * 在 UNKNOWN 场景调用会被排队返回占位文本（真机实证，场景解耦挂账 M3）——故本桥持有
 * 自己的 [IosChatGalleryCapability] 实例直接 `execute`（业务语义相同：相册摘要/搜索/细化），
 * intent 组装同源复用 [ChatToolService] 的 internal 构建器，零复制。
 *
 * 结果契约（与 Android DeviceToolExecutor 语义对齐；MediaResults 摘要文案按云端无卡场景裁剪——
 * Android 带「，已展示在卡片中」尾巴，本桥无卡片故不含）：
 * - 文本结果；失败以 `error: <message>` 前缀判定 ok=false（[AgentAction.Error] → error 前缀）；
 * - 超 [MAX_RESULT_CHARS] **字符**截断 + `\n…[truncated]`（字符非字节——不切半 UTF-8）；
 * - 未知工具 → `error: unknown_tool:<tool>`；
 * - 写类工具（delete/share/select 等）不在映射表——云端会话里即未知工具（M2 只读三件套）。
 *
 * 信号 6 铁律：一切异常（除 [CancellationException] 透传）在 Kotlin 侧兜底为 error 前缀字符串，
 * 绝不逃逸到 Swift。
 */
class IosCloudAgentToolBridge(
    repository: MediaRepository,
    bridge: IosMediaRepositoryBridge,
    searchBridge: IosChatSearchBridge? = null
) {

    /**
     * 自有能力实例（独立于组合根注册到 CapabilityRegistry 的那颗——搜索轮次快照互不染指；
     * 云端会话的 refine 基数由本实例的内存态承载）。
     */
    private val capability = IosChatGalleryCapability(repository, bridge, searchBridge)

    /** 工具分发入口（Swift 侧逐帧调用）。args 全 String 空容忍（对齐 SKIE 契约形态）。 */
    suspend fun execute(tool: String, args: Map<String, String>): String {
        val result = try {
            when (tool) {
                TOOL_GET_GALLERY_SUMMARY -> getGallerySummary()
                TOOL_SEARCH_MEDIA -> searchMedia(
                    query = args["query"].orEmpty(),
                    person = args["person"].orEmpty(),
                    fromMs = args["fromMs"].orEmpty(),
                    toMs = args["toMs"].orEmpty(),
                    hasFace = args["hasFace"].orEmpty(),
                )
                TOOL_REFINE_MEDIA_SEARCH -> refineMediaSearch(
                    constraint = args["constraint"].orEmpty(),
                    fromMs = args["fromMs"].orEmpty(),
                    toMs = args["toMs"].orEmpty(),
                )
                else -> return "$ERROR_PREFIX unknown_tool:$tool"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return "$ERROR_PREFIX ${e.message ?: "unknown"}"
        }
        return truncate(result)
    }

    /** @Tool get_gallery_summary 对等：相册摘要。 */
    suspend fun getGallerySummary(): String =
        executeCommand(AgentCommand.GetGallerySummary(includeDetails = false))

    /** @Tool search_media 对等：intent 组装同源复用 [ChatToolService.structuredSearchIntent]。 */
    suspend fun searchMedia(
        query: String,
        person: String,
        fromMs: String,
        toMs: String,
        hasFace: String
    ): String {
        val intent = ChatToolService.getInstance()
            .structuredSearchIntent(query, person, fromMs, toMs, hasFace)
        return executeCommand(AgentCommand.SearchMedia(query = query, intent = intent))
    }

    /** @Tool refine_media_search 对等：intent 组装同源复用 [ChatToolService.refineTimeIntent]。 */
    suspend fun refineMediaSearch(
        constraint: String,
        fromMs: String,
        toMs: String
    ): String {
        val intent = ChatToolService.getInstance().refineTimeIntent(fromMs, toMs)
        return executeCommand(AgentCommand.RefineMediaSearch(constraint = constraint, intent = intent))
    }

    /**
     * 直调能力执行（绕过 CapabilityRegistry 场景门，见类头注释）并把 [AgentAction] 映射为
     * 工具结果文本：TextReply→原文；MediaResults→计数摘要；Error→`error: ` 前缀。
     */
    private suspend fun executeCommand(command: AgentCommand): String {
        val result = capability.execute(
            command,
            AgentContext(scene = AgentScene.CHAT, memorySessionId = "cloud_agent"),
            null
        )
        return result.fold(
            onSuccess = { action -> actionToText(action) },
            onFailure = { "$ERROR_PREFIX ${it.message ?: "dispatch failed"}" },
        )
    }

    private fun actionToText(action: AgentAction): String = when (action) {
        is AgentAction.TextReply -> action.message
        is AgentAction.MediaResults -> "找到 ${action.totalCount} 张「${action.query}」的照片"
        is AgentAction.Error -> "$ERROR_PREFIX ${action.message}"
        else -> "ok"
    }

    companion object {
        const val TOOL_GET_GALLERY_SUMMARY = "get_gallery_summary"
        const val TOOL_SEARCH_MEDIA = "search_media"
        const val TOOL_REFINE_MEDIA_SEARCH = "refine_media_search"

        /** 失败前缀（契约：hasPrefix("error:") → ok=false）。 */
        const val ERROR_PREFIX = "error:"

        /** 结果截断上限（字符，非字节；与 Android DeviceToolExecutor.MAX_RESULT_CHARS 同源）。 */
        const val MAX_RESULT_CHARS = 32_000

        /** 截断后缀（契约逐字）。 */
        const val TRUNCATED_SUFFIX = "\n…[truncated]"

        /** 字符截断（契约 §2：字符非字节——UTF-8 不切半字；String.take 即字符语义）。 */
        internal fun truncate(text: String): String =
            if (text.length <= MAX_RESULT_CHARS) text
            else text.take(MAX_RESULT_CHARS) + TRUNCATED_SUFFIX
    }
}
