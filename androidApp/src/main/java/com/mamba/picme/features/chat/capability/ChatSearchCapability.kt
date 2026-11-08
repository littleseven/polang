package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import java.lang.ref.WeakReference

/**
 * Chat 场景相册搜索 Capability。
 *
 * 职责：在 CHAT 场景把 search_media / refine_media_search 暴露为可用工具，
 * 并把命令回调给 [Delegate]（由 ChatViewModel 实现）执行。
 * 镜像 GalleryCapability 的 WeakReference Delegate 套路。
 *
 * 横滑卡退役（2026-11-08）：搜索/细化结果不再产出卡片 action，统一回 [AgentAction.TextReply]
 * 计数文本；展示由 LLM 经 render_html 图集卡完成。反馈三件套（record_feedback /
 * more_like_this / exclude_constraint）同步删除。
 */
class ChatSearchCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatSearchCapability? = null
        fun getInstance(): ChatSearchCapability =
            instance ?: synchronized(this) {
                instance ?: ChatSearchCapability().also { instance = it }
            }
    }

    private val tag = "ChatSearchCapability"

    override val name: String = "chat_gallery_search"
    override val description: String = "在聊天中搜索相册照片；支持多轮细化筛选"

    /**
     * 执行委托：由 ChatViewModel 实现并绑定。返回 [SearchOutcome]（全量命中 id）。
     */
    interface Delegate {
        suspend fun onSearchMedia(query: String, intent: SearchIntent? = null): SearchOutcome
        suspend fun onRefineMediaSearch(constraint: String, intent: SearchIntent? = null): SearchOutcome
    }

    private var delegateRef: WeakReference<Delegate>? = null

    fun bindDelegate(delegate: Delegate) {
        delegateRef = WeakReference(delegate)
        Logger.i(tag, "Delegate bound")
    }

    fun unbindDelegate() {
        delegateRef = null
        Logger.i(tag, "Delegate unbound")
    }

    override fun isAvailable(): Boolean = delegateRef?.get() != null

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(
        "search_media",
        "refine_media_search"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "search_media" -> "搜索相册照片，参数: query (自然语言，如'去年夏天'、'海边的')"
        "refine_media_search" -> "在上一轮相册搜索结果中细化筛选，参数: constraint (如'海边的'、'夜景')"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val d = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "相册搜索暂不可用（聊天页未激活）"
                )
            )
        return try {
            when (command) {
                is AgentCommand.SearchMedia -> d.onSearchMedia(command.query, command.intent)
                    .toTextReply(command.commandId)
                is AgentCommand.RefineMediaSearch -> d.onRefineMediaSearch(command.constraint, command.intent)
                    .toTextReply(command.commandId)
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatSearchCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Search failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "搜索失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }
}

/**
 * Delegate 执行搜索后的结果。mediaIds 为全量命中 id（供 ChatViewModel 持有做细化）。
 *
 * [errorMessage] 非空表示前置条件失败（如 refine 无搜索基数，spec §3.5-c），
 * 映射为 [AgentAction.Error] 让 LLM 拿到明确观测文本自行改道，而非空结果假成功。
 */
data class SearchOutcome(
    val query: String,
    val mediaIds: List<Long>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val errorMessage: String? = null
) {
    /**
     * 横滑卡退役后的产出形态：计数文本（LLM 观测与用户可见消息共用）。
     * 命中 id 清单由 ChatViewModel 搜索基数（lastResultAssets / 快照）承载，
     * LLM 出图集卡经 render_html + media://{id} 引用。
     */
    fun toTextReply(commandId: Int): Result<AgentAction> {
        if (errorMessage != null) {
            return Result.success(
                AgentAction.Error(
                    commandId = commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = errorMessage
                )
            )
        }
        val message = if (isRefinement) {
            "在上一轮结果内找到 $totalCount 张「$query」的照片"
        } else {
            "找到 $totalCount 张「$query」的照片"
        }
        return Result.success(AgentAction.TextReply(commandId = commandId, message = message))
    }
}
