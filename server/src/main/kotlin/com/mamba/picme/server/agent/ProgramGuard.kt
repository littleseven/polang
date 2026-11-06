package com.mamba.picme.server.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * 终结程序守卫（turn-native，spec§3.5——原假发卡守卫的回合原生泛化；
 * 2026-11-06 GenUI 唯一化后无条件启用，每轮一个新实例）。
 *
 * /agent 路由面恒走 OpenUI 回合原生：LLM 的整个终结回复必须是一颗合法 OpenUI 程序。
 * 策略层 onTextMessage 出边经 [invalidProgramError] 门控：非法 → correctInvalidProgram
 * 节点携带具体错误清单回环重发（上限 [MAX_CORRECTIONS] 次防死循环）；上限用尽
 * 放行，由 AgentRunner 终结落流的 [OpenUiTurnNative.ensureValidProgram] 兜底
 * Markdown 包装——「端上永远收到合法树」的双保险。
 */
internal class ProgramGuard {

    private val corrections = AtomicInteger(0)

    /** 终结文本是否需要纠正回环：非合法程序 + 纠正次数未达上限。null = 放行。 */
    fun invalidProgramError(assistantText: String): String? {
        if (corrections.get() >= MAX_CORRECTIONS) return null
        return RenderOpenUiValidator.validateProgramToError(assistantText)
    }

    fun markCorrected() {
        corrections.incrementAndGet()
    }

    companion object {
        /** 单轮纠正上限（防 LLM 坚持己见死循环）。 */
        const val MAX_CORRECTIONS = 1

        fun correctionPrompt(error: String): String =
            "系统校验：你的整个回复就是用户看到的界面，必须是一颗合法的 OpenUI Lang 1.0 程序" +
                "（不允许程序之外的任何文字，也不要包 markdown 代码围栏；纯文本回复写 root = Markdown(\"…\")）。" +
                "你上次的输出未通过校验，逐条修正后重新输出完整程序：\n$error"
    }
}
