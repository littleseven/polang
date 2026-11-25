package com.mamba.picme.server.agent

import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.domain.chat.openui.OpenUiTurnNativeContract

/**
 * OpenUI 回合原生（turn-native）server 薄壳（U1-2 上提 shared 后真引用
 * [OpenUiTurnNativeContract]：SYSTEM_PROMPT 缺省参数组装、ensureValidProgram/
 * escapeOpenUiString 委托）——server 消费方与既有测试零改动，行为逐字节不变
 * （OpenUiTurnNativeTest 原位钉住）。
 */
object OpenUiTurnNative {

    /**
     * 对话形态黄金示例（同源 [OpenUiTurnNativeContract.GOLDEN_CHAT_PROGRAM]）。
     */
    const val GOLDEN_CHAT_PROGRAM = OpenUiTurnNativeContract.GOLDEN_CHAT_PROGRAM

    /**
     * 系统提示词（响应即程序契约）：缺省参数组装（云端身份行 + server_time 工具句），
     * 输出与现网逐字节一致（server OpenUiTurnNativeTest 钉住）。
     */
    val SYSTEM_PROMPT: String = OpenUiTurnNativeContract.buildSystemPrompt()

    /**
     * 终结消息兜底变换（AgentRunner 终结落流前无条件内联调用，spec§3.2 双保险）：
     * 合法 → normalize 后原样放行；非法（ProgramGuard 纠正用尽）→ 兜底包成
     * Markdown 程序，端上永远收到合法树。[language] 兜底文案随界面语言五语化。
     */
    fun ensureValidProgram(text: String, language: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE): String =
        OpenUiTurnNativeContract.ensureValidProgram(text, language)

    /** OpenUI 双引号字符串转义（委托 shared 契约，JSON 转义子集）。 */
    internal fun escapeOpenUiString(raw: String): String = OpenUiTurnNativeContract.escapeOpenUiString(raw)
}
