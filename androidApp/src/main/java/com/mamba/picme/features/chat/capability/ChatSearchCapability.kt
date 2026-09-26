package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
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
    override val description: String = "在聊天中搜索相册照片，结果以卡片展示；支持多轮细化筛选"

    /**
     * 执行委托：由 ChatViewModel 实现并绑定。返回 [SearchOutcome]（全量命中 id）。
     */
    interface Delegate {
        suspend fun onSearchMedia(query: String, intent: SearchIntent? = null): SearchOutcome
        suspend fun onRefineMediaSearch(constraint: String, intent: SearchIntent? = null): SearchOutcome
        suspend fun onRecordMediaFeedback(target: FeedbackTarget, action: FeedbackAction): Boolean
        suspend fun onMoreLikeThis(target: FeedbackTarget): SearchOutcome
        suspend fun onExcludeConstraint(constraint: String): Boolean
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
        "refine_media_search",
        "feedback",
        "more",
        "exclude"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "search_media" -> "搜索相册照片，参数: query (自然语言，如'去年夏天'、'海边的')"
        "refine_media_search" -> "在上一轮相册搜索结果中细化筛选，参数: constraint (如'海边的'、'夜景')"
        "feedback" -> "记录用户对某张图片的反馈，参数: target (last/ordinal:N/desc:xxx/mediaId:xxx), action (like/dislike)"
        "more" -> "基于指定图片推荐更多相似照片，参数: target (last/ordinal:N/desc:xxx/mediaId:xxx)"
        "exclude" -> "在后续搜索中排除某类约束，参数: constraint (如'夜景'、'室内')"
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
                    .toMediaResults(command.commandId)
                is AgentCommand.RefineMediaSearch -> d.onRefineMediaSearch(command.constraint, command.intent)
                    .toMediaResults(command.commandId)
                is AgentCommand.RecordMediaFeedback -> {
                    val success = d.onRecordMediaFeedback(command.target, command.action)
                    if (success) {
                        Result.success(AgentAction.Success(commandId = command.commandId, command = command))
                    } else {
                        Result.success(
                            AgentAction.Error(
                                commandId = command.commandId,
                                errorCode = AgentErrorCode.INVALID_PARAMS,
                                message = "feedback_resolve_failure"
                            )
                        )
                    }
                }
                is AgentCommand.MoreLikeThis -> d.onMoreLikeThis(command.target)
                    .copy(isRefinement = false)
                    .toMediaResults(command.commandId)
                is AgentCommand.ExcludeConstraint -> {
                    val success = d.onExcludeConstraint(command.constraint)
                    if (success) {
                        Result.success(AgentAction.Success(commandId = command.commandId, command = command))
                    } else {
                        Result.success(
                            AgentAction.Error(
                                commandId = command.commandId,
                                errorCode = AgentErrorCode.INVALID_PARAMS,
                                message = "feedback_resolve_failure"
                            )
                        )
                    }
                }
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
    fun toMediaResults(commandId: Int): Result<AgentAction> {
        if (errorMessage != null) {
            return Result.success(
                AgentAction.Error(
                    commandId = commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = errorMessage
                )
            )
        }
        return Result.success(
            AgentAction.MediaResults(
                commandId = commandId,
                query = query,
                mediaIds = mediaIds,
                totalCount = totalCount,
                isRefinement = isRefinement
            )
        )
    }
}
