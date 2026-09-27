package com.mamba.picme.domain.agent.remote

import android.content.Context
import android.view.WindowManager
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.tool.RemoteControlToolService
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.local.insertMessageWithParts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * 远程命令调度器
 *
 * 接收飞书消息，统一通过 [AgentOrchestrator.processRemoteImInput] 处理，
 * 使用 ReAct 循环完成应用内 UI 自动化，并将结果通过 [FeishuChannelHandler] 回复给用户。
 *
 * **架构（2026-06-18，ADR-006 Phase 5）**：
 * - ReAct Agent 生命周期由 [AgentConfigurator] 管理（懒创建、缓存、清理）
 * - [AgentOrchestrator] 提供统一的 `processRemoteImInput()` 入口
 * - 本调度器仅负责：消息接收 → 调用 Orchestrator → 结果回复
 *
 * **聊天记录同步（2026-06-19）**：
 * - 飞书消息收发同步写入本地 Room 数据库（sessionId = "feishu"）
 * - 用户可在 App 内聊天页面查看与飞书的完整对话历史
 *
 * **ANR 防护**：
 * - 前一个任务未完成时收到新消息，自动取消旧任务
 * - 超时保护（120 秒），避免 LLM 推理长时间占用 CPU
 * - 相册直搜路径走 Dispatchers.IO；ReAct 路径由 `processRemoteImInput` 内部切
 *   orchestratorDispatcher，外层无双跳
 */
class RemoteCommandDispatcher(
    private val channel: RemoteChannel,
    context: Context,
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao
) {

    private val tag = "RemoteDispatcher"
    private val appContext = context.applicationContext
    private val orchestrator = AgentOrchestrator.getInstance()

    /** 当前会话 ID = 激活通道 id（feishu / telegram），每次 dispatch 动态读取。 */
    private fun sessionId(): String = channel.channelId.ifBlank { "remote" }

    /** 当前正在执行的 Job，用于新消息到达时取消旧任务 */
    @Volatile
    private var currentJob: Job? = null

    /**
     * 当前正在处理的消息 ID（飞书 messageId / Telegram chatId）。
     * agent 执行 capture 工具时由 [AgentOrchestrator.remoteImToolCallListener] 读取，
     * 精准标记远程拍照回传——替代入口关键词猜测（"连拍三张照片"匹配不到会漏标）。
     */
    @Volatile
    private var activeMessageId: String? = null

    init {
        orchestrator.remoteImToolCallListener = { toolName ->
            if (toolName == "capture") {
                activeMessageId?.let {
                    RemotePhotoTracker.startCapture(it)
                    Logger.i(tag, "capture 工具触发，远程拍照追踪已启动: messageId=$it")
                }
            }
        }
    }

    /** ReAct 循环超时（毫秒）— 多轮交互需要更长 timeout */
    private val TIMEOUT_MS = 120_000L

    /**
     * 接收飞书消息并启动 ReAct Agent 处理
     *
     * 统一通过 [AgentOrchestrator.processRemoteImInput] 执行 ReAct 循环，
     * 当 Agent 不可用时回退到原有 [AgentOrchestrator.processUserInput] 路径。
     * 所有收发消息同步写入本地聊天记录。
     *
     * **飞书拍照追踪**：agent 实际执行 capture 工具时（经 remoteImToolCallListener）
     * 设置 [RemotePhotoTracker] 状态，照片保存完成后自动发送到飞书。
     */
    suspend fun dispatch(text: String, messageId: String) {
        Logger.i(tag, "远程命令: text='$text', messageId=$messageId")
        currentJob?.cancel()
        activeMessageId = messageId

        // 确保飞书会话元数据存在
        ensureFeishuSession()

        // 持久化收到的飞书用户消息
        saveUserMessage(text)

        // ── 快速通道：相册搜索 + 预览 ──
        // 对于明确的“搜索照片”指令（可能附带“预览第 N 张”），直接走工具调用，避免依赖 LLM 是否遵循 prompt。
        val directSearchQuery = extractSearchQuery(text)
        if (directSearchQuery != null) {
            withContext(Dispatchers.IO) {
                channel.sendMessage("⏳ 正在搜索照片...", messageId)
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val previewIndex = extractPreviewIndex(text)
                val result = if (wm != null) {
                    executeDirectGallerySearch(directSearchQuery, previewIndex, wm)
                } else {
                    "❌ WindowManager 不可用"
                }
                saveAgentMessage(result)
                channel.sendMessage(result, messageId)
            }
            return
        }

        // ReAct 路径不再外层切 Dispatchers.IO：processRemoteImInput 内部已切
        // orchestratorDispatcher（双跳无意义）；channel.sendMessage 为非挂起 fire-and-forget、
        // Room DAO 挂起函数自切内部 executor，均不依赖外层上下文。
        channel.sendMessage("⏳ 正在处理您的请求...", messageId)

        // WindowManager 可用性门禁保留（组合根的飞书 RPA 工具集按需自取 WindowManager，
        // 不可用时走回退路径而非让 agent 构建期崩溃）
        val wmAvailable = appContext.getSystemService(Context.WINDOW_SERVICE) != null
        if (wmAvailable) {
            // ── ReAct Agent 路径（统一走 AgentOrchestrator）──
            try {
                val result = withTimeout(TIMEOUT_MS) {
                    orchestrator.processRemoteImInput(text, TIMEOUT_MS)
                }
                val reply = result.fold(
                    onSuccess = { it },
                    onFailure = { error -> "❌ ${error.message ?: "未知错误"}" }
                )
                Logger.i(tag, "远程命令执行完毕，回复：$reply")

                // [飞书拍照] 如果包含拍照命令，Agent 回复改为"处理中"提示
                // 实际拍照成功/失败由 observeFeishuPhotoCapture 通知
                val isPhotoCommand = text.contains("拍照") || text.contains("拍张") || text.contains("拍照片")
                val finalReply = if (isPhotoCommand) {
                    // 如果 Agent 已经说了类似"拍好了"的话，保持不变
                    // 否则替换为处理中提示
                    if (reply.contains("拍") && (reply.contains("好") || reply.contains("成功") || reply.contains("完成"))) {
                        "📸 正在拍照，请稍候..."
                    } else {
                        reply
                    }
                } else {
                    reply
                }

                // 持久化 Agent 回复
                saveAgentMessage(finalReply)
                channel.sendMessage(finalReply, messageId)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val timeoutMsg = "⏰ 处理超时（${TIMEOUT_MS / 1000}秒），请稍后重试"
                saveAgentMessage(timeoutMsg)
                channel.sendMessage(timeoutMsg, messageId)
            }
        } else {
            // ── 回退路径 ──
            Logger.i(tag, "WindowManager 不可用，回退到原有路径")
            fallbackProcess(text, messageId)
        }
    }

    /**
     * WindowManager 不可用时的回退：端侧文本 LLM 已移除，本地兜底链路不存在，
     * 直接告知用户当前环境不可用。
     */
    private suspend fun fallbackProcess(text: String, messageId: String) {
        Logger.w(tag, "fallbackProcess: WindowManager 不可用，无法处理 '$text'")
        val msg = "❌ 当前无法执行远程控制：请先在手机上打开应用后重试（WindowManager 不可用）"
        saveAgentMessage(msg)
        channel.sendMessage(msg, messageId)
    }

    // ── 聊天记录持久化 ─────────────────────────────────────────────

    private suspend fun ensureFeishuSession() {
        try {
            val existing = chatSessionDao.getSession(sessionId())
            if (existing == null) {
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        sessionId = sessionId(),
                        title = if (sessionId() == "telegram") "Telegram 远程控制" else "飞书远程控制"
                    )
                )
                Logger.i(tag, "Created feishu chat session")
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to ensure feishu session", e)
        }
    }

    private suspend fun saveUserMessage(content: String) {
        try {
            chatMessageDao.insertMessageWithParts(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId(),
                    type = "user_text",
                    content = content,
                    modelUsed = null
                )
            )
            chatSessionDao.touchSession(sessionId())
        } catch (e: Exception) {
            Logger.w(tag, "Failed to save user message", e)
        }
    }

    private suspend fun saveAgentMessage(content: String) {
        try {
            chatMessageDao.insertMessageWithParts(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId(),
                    type = "agent_text",
                    content = content,
                    modelUsed = "feishu_remote"
                )
            )
            chatSessionDao.touchSession(sessionId())
        } catch (e: Exception) {
            Logger.w(tag, "Failed to save agent message", e)
        }
    }

    /**
     * 从用户输入中提取相册搜索关键词。
     * 支持的句式：
     * - 搜索去年夏天小孩的照片
     * - 进入相册，搜索“去年夏天小孩”
     * - 查找上海的照片
     * - 打开相册，搜索7月的美女，预览第四张
     * - 打开相册，预览7月1日的第四张美女图片
     *
     * 遇到“预览/查看/打开/点击/第 N 张”等后续动作词时停止，避免把预览指令也当成搜索词。
     */
    private fun extractSearchQuery(text: String): String? {
        val cleaned = text.replace("[\"“”]".toRegex(), "")
        val stopWords = "，?(?:预览|查看|打开|点击|第[一二三四五六七八九十0-9]+张)"
        val patterns = listOf(
            // 显式搜索动词
            "搜索[:：]?(.+?)(?:的照片|(?=$stopWords)|\$)".toRegex(),
            "查找[:：]?(.+?)(?:的照片|(?=$stopWords)|\$)".toRegex(),
            "找(.+?)(?:的照片|(?=$stopWords)|\$)".toRegex(),
            // 省略搜索动词、直接“预览/查看/打开 X 的第 N 张”
            "预览(.+?)第[一二三四五六七八九十0-9]+张".toRegex(),
            "查看(.+?)第[一二三四五六七八九十0-9]+张".toRegex(),
            "打开(.+?)第[一二三四五六七八九十0-9]+张".toRegex()
        )
        for (pattern in patterns) {
            pattern.find(cleaned)?.groupValues?.get(1)?.trim()?.let {
                // 过滤掉纯“相册”这种无意义 query
                if (it.isNotBlank() && it != "相册") return it
            }
        }
        return null
    }

    /**
     * 从用户输入中提取“预览第 N 张”的序号。
     * 支持中文数字（第四张）和阿拉伯数字（第4张）。
     */
    private fun extractPreviewIndex(text: String): Int? {
        val matchResult = "第([一二三四五六七八九十0-9]+)张".toRegex().find(text) ?: return null
        return chineseNumberToInt(matchResult.groupValues[1])
    }

    /**
     * 中文/阿拉伯数字转 Int。
     */
    private fun chineseNumberToInt(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val chars = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        var result = 0
        var temp = 0
        for (c in text) {
            when {
                c in chars -> temp = chars[c]!!
                c == '十' -> {
                    result += if (temp == 0) 10 else temp * 10
                    temp = 0
                }
                c == '百' -> {
                    result += if (temp == 0) 100 else temp * 100
                    temp = 0
                }
                else -> return null
            }
        }
        result += temp
        return if (result > 0) result else null
    }

    /**
     * 直接执行相册搜索（并可选预览第 N 张），不经过 LLM ReAct 循环。
     */
    private suspend fun executeDirectGallerySearch(query: String, previewIndex: Int?, wm: WindowManager): String {
        return try {
            val toolService = RemoteControlToolService(wm)
            val navigateResult = toolService.navigateTo("gallery")
            if (navigateResult.startsWith("Error:")) {
                return "❌ 进入相册失败：$navigateResult"
            }
            val searchResult = toolService.searchPhotos(query)
            if (searchResult.startsWith("Error:")) {
                return "❌ 搜索失败：$searchResult"
            }
            if (previewIndex != null) {
                val clickResult = toolService.clickGalleryItem(previewIndex)
                return if (clickResult.startsWith("Error:")) {
                    "✅ 已搜索“$query”，但预览第 ${previewIndex} 张失败：$clickResult"
                } else {
                    "✅ 已搜索“$query”并预览第 ${previewIndex} 张照片"
                }
            }
            "✅ 已完成相册搜索：$searchResult"
        } catch (e: Exception) {
            Logger.e(tag, "Direct gallery search failed", e)
            "❌ 搜索执行异常：${e.message ?: "未知错误"}"
        }
    }
}
