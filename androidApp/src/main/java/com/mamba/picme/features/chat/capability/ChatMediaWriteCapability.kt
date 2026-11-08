@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import java.lang.ref.WeakReference

/**
 * Chat 场景「媒体写操作」Capability（delete_media / favorite_media / select_media / share_media）。
 *
 * 存在意义：JS 经 `capability.dispatch` 在 CHAT 场景下发写命令时，若注册表只找得到
 * GalleryCapability（GALLERY 场景），命令会进跨页队列（等用户打开相册页才执行）——
 * 对 JS 同步语义是错误的。本 Capability 声明 activeScenes=[CHAT] 并注册到全局
 * CapabilityRegistry（PoLangApplication 启动期），使 CHAT 场景 dispatch 命中本类，立即执行。
 *
 * 写操作落点由 [Delegate]（ChatViewModel）实现：删除复用 MediaRepository 的
 * MediaStore 授权流；收藏/选中为 chat 会话级状态（App 尚无持久化收藏路径，
 * 与 GalleryCapability 的 favorite 先例一致）；分享直接拉系统分享面板
 *（shareMediaAssets，无需相册页在场）。
 *
 * share_media 于 2026-11-08 补入本类：此前 CHAT 场景 dispatch 只能命中 GalleryCapability
 *（场景不匹配 → 跨页队列 + 「正在切换页面」话术，实际不跳转），是与 view_media 同类的
 * 工具陷阱；iOS 侧本就有执行体（IosChatGalleryCapability.handleShare → Swift 消费
 * uiActions 弹分享面板），本补全对齐 iOS 并消除 Android 陷阱（[PARITY]）。
 *
 * **写操作确认两层策略的执行汇聚点**：本 Capability 是 CHAT 场景媒体写的唯一执行体，两条触发
 * 链路都汇此——(A) JS `capability.dispatch` 经 [com.mamba.picme.features.chat.js.CapabilityDispatchHandler]
 * 在 dispatch 前完成应用内确认；(B) 顶层 `ChatToolService.delete_media` 等 @Tool 直调，依赖系统
 * MediaStore 授权框。差异由 [com.mamba.picme.agent.core.model.command.CommandRisk] 统一分级、
 * 是刻意设计（见 CommandRisk 与 AGENT_ARCHITECTURE.md）。本类只管执行，不重复确认。
 */
class ChatMediaWriteCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatMediaWriteCapability? = null

        fun getInstance(): ChatMediaWriteCapability =
            instance ?: synchronized(this) {
                instance ?: ChatMediaWriteCapability().also { instance = it }
            }
    }

    private val tag = "ChatMediaWriteCapability"

    override val name: String = "chat_media_write"
    override val description: String =
        "Chat 场景媒体写操作：删除（走系统授权流）/收藏/选中/分享（系统分享面板），删除为高风险不可恢复操作"

    interface Delegate {
        /** 删除媒体（内部走 MediaStore 授权流，可能弹系统授权框）；返回结果描述。 */
        suspend fun onDeleteMedia(mediaIds: List<String>): String

        /** 收藏/取消收藏（chat 会话级）；返回结果描述。 */
        suspend fun onFavoriteMedia(mediaId: String, favorite: Boolean): String

        /** 选中/取消选中（chat 会话级）；返回结果描述。 */
        suspend fun onSelectMedia(mediaId: String, selected: Boolean): String

        /** 分享媒体（拉系统分享面板）；返回结果描述。 */
        suspend fun onShareMedia(mediaIds: List<String>): String
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

    override fun supportedCommands(): List<String> =
        listOf("delete_media", "favorite_media", "select_media", "share_media")

    override fun getCommandDescription(command: String): String = when (command) {
        "delete_media" -> "删除媒体（不可恢复，需用户确认 + 系统授权）。参数: media_ids (id 列表)"
        "favorite_media" -> "收藏/取消收藏。参数: media_id, favorite (true/false)"
        "select_media" -> "选中/取消选中。参数: media_id, selected (true/false)"
        "share_media" -> "分享媒体（拉系统分享面板）。参数: media_ids (id 列表)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val delegate = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "媒体写操作暂不可用（聊天页未激活）"
                )
            )
        return try {
            when (command) {
                is AgentCommand.DeleteMedia -> {
                    if (command.mediaIds.isEmpty()) {
                        errorResult(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要删除的媒体")
                    } else {
                        textResult(command.commandId, delegate.onDeleteMedia(command.mediaIds))
                    }
                }
                is AgentCommand.FavoriteMedia ->
                    textResult(command.commandId, delegate.onFavoriteMedia(command.mediaId, command.favorite))
                is AgentCommand.SelectMedia ->
                    textResult(command.commandId, delegate.onSelectMedia(command.mediaId, command.selected))
                is AgentCommand.ShareMedia -> {
                    if (command.mediaIds.isEmpty()) {
                        errorResult(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要分享的媒体")
                    } else {
                        textResult(command.commandId, delegate.onShareMedia(command.mediaIds))
                    }
                }
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatMediaWriteCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Media write failed", e)
            errorResult(command.commandId, AgentErrorCode.INTERNAL_ERROR, "媒体写操作失败：${e.message ?: "未知错误"}")
        }
    }

    private fun textResult(commandId: Int, message: String): Result<AgentAction> =
        Result.success(AgentAction.TextReply(commandId = commandId, message = message))

    private fun errorResult(commandId: Int, errorCode: Int, message: String): Result<AgentAction> =
        Result.success(
            AgentAction.Error(
                commandId = commandId,
                errorCode = errorCode,
                message = message
            )
        )
}
