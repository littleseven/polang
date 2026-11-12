@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃转结构化 Error，已记录日志
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.inference.remote.tool.ChatHistoryRecall
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao

/**
 * 聊天历史召回 Capability（CHAT 场景，recall_chat_history 工具执行体，2026-11-12）。
 *
 * Room 长期轨（chat_messages）主动召回通路——此前长期轨消费者只有工程师隧道 + 冷启动
 * 重建，本能力补上「会话内按需召回任意段」。检索范围 = 当前会话（session 经
 * AgentContext.memorySessionId 下传，ChatToolService.chatSessionId 由引擎每轮同步）；
 * 只读 `type='text'` 投影行，无 UI 卡产出。
 *
 * observation 组装收口 shared [ChatHistoryRecall]（纯函数，钳制/截断/格式与
 * commonTest 同源）；⚠️ 返回文本为硬编码中文模型侧 observation，绝不直达用户气泡（I18N）。
 */
class ChatHistoryRecallCapability(
    private val chatMessageDao: ChatMessageDao
) : BaseCapability() {

    private val tag = "PoLang:ChatHistoryRecall"

    override val name: String = "chat_history"
    override val description: String = "召回当前会话的历史聊天消息（关键词匹配或最近 N 条）"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf("recall_chat_history")

    override fun getCommandDescription(command: String): String =
        if (command == "recall_chat_history") {
            "召回会话历史，参数: query (关键词，空串=不限), limit (条数上限，默认 20，钳制 1..50)"
        } else {
            "未知命令"
        }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return try {
            if (command is AgentCommand.RecallChatHistory) {
                recall(command, context)
            } else {
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatHistoryRecallCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "execute failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "历史召回失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }

    private suspend fun recall(
        command: AgentCommand.RecallChatHistory,
        context: AgentContext
    ): Result<AgentAction> {
        val sessionId = context.memorySessionId
        val limit = ChatHistoryRecall.clampLimit(command.limit)
        val rows = chatMessageDao.searchTextMessages(sessionId, command.query.trim(), limit)
        // DAO 按 timestamp DESC 取最近 N 条 → 反转为时间正序（observation 语义）
        val lines = rows.asReversed().map { row ->
            ChatHistoryRecall.HistoryLine(
                role = row.role,
                content = row.content,
                timestamp = row.timestamp,
            )
        }
        Logger.i(
            tag,
            "recall: session=$sessionId query='${command.query}' limit=$limit hits=${rows.size}"
        )
        return Result.success(
            AgentAction.TextReply(
                commandId = command.commandId,
                message = ChatHistoryRecall.formatObservation(lines)
            )
        )
    }
}
