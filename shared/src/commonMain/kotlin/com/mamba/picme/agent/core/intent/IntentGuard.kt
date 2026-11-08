package com.mamba.picme.agent.core.intent

import com.mamba.picme.agent.core.model.command.AgentCommand

/**
 * 意图守卫层（Intent Guard）：LLM tool_calls 路由之外的确定性护栏。
 *
 * 当前框架的意图理解由远程 LLM 一步完成（tool_calls 协议），不设前置意图分类器。
 * 本对象只承载「补偿 LLM 路由失误」的确定性规则，原为 ChatViewModel 内的私有
 * 防御性逻辑，收口到 shared commonMain 以便复用（含 iOS）与单测覆盖：
 *
 * - [isExplicitNavigationRequest] / [sanitizeNavigationCommands]：chat 页拦截 LLM
 *   误判的模糊跳转命令（navigate_to / go_back），非明确口令时替换为文本提示。
 *
 * （isRefusedSearchRequest 误拒回退判定已随 2026-11-08 直执路由退役删除——
 *  直执路径整体下线后无生产调用点。）
 *
 * 红线：本层只做保守的误伤修正，不替代 LLM 的开放语义理解；新增规则必须配单测。
 */
object IntentGuard {

    /**
     * 判断用户输入是否包含明确的页面跳转口令。
     *
     * 仅当匹配以下模式时才应放行 chat 页的 navigate_to / go_back：
     * - "去/回/打开 + 相机/相册/设置/调试/模型中心"
     * - "返回/后退/上一页/回去"
     *
     * 不含上述口令的表述（如"我想看看相册""想去拍照"）视为模糊，应被拦截。
     */
    fun isExplicitNavigationRequest(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        return EXPLICIT_NAVIGATION_PATTERNS.any { pattern -> pattern.containsMatchIn(trimmed) }
    }

    /**
     * 拦截模糊跳转命令：命令含 navigate_to / go_back 但用户输入不匹配明确口令时，
     * 将导航命令替换为 [blockedMessage] 文本回复，避免聊天中因 LLM 误判突然跳页。
     *
     * [blockedMessage] 由调用方按平台 i18n 注入（Android 传 R.string 资源文案），
     * 本层不持有字符串资源。
     */
    fun sanitizeNavigationCommands(
        commands: List<AgentCommand>,
        userInput: String,
        blockedMessage: String
    ): List<AgentCommand> {
        if (commands.isEmpty()) return commands
        val hasNavigation = commands.any { command ->
            command is AgentCommand.NavigateTo || command is AgentCommand.GoBack
        }
        if (!hasNavigation) return commands
        // 明确跳转口令：放行
        if (isExplicitNavigationRequest(userInput)) return commands
        // 否则把所有导航命令替换为提示文本
        return commands.map { command ->
            when (command) {
                is AgentCommand.NavigateTo, is AgentCommand.GoBack ->
                    AgentCommand.TextReply(message = blockedMessage)
                else -> command
            }
        }
    }

    private val EXPLICIT_NAVIGATION_PATTERNS = listOf(
        Regex("""(去|回|打开)\s*(相机|相册|设置|调试|模型中心|model_center)"""),
        Regex("""(返回|后退|上一页|回去)""")
    )
}
