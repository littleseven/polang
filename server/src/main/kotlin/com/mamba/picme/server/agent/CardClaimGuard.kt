package com.mamba.picme.server.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假发卡守卫（2026-10-24 真机穷举实测：5/13 个布局类用例 LLM 不调 render_ui 就用文字
 * 声称「卡片已下发 ✅」——负载校验器只管「调了但写错」，管不到「根本没调」）。
 *
 * 策略层收尾检测（[cloudAgentStrategy] 的 correctFakeCardClaim 节点）：终结消息命中
 * 发卡声明模式 + 当轮无卡工具 Action 过门控 → 纠正一次（追加 user 纠正消息就地重发
 * LLM 请求），让 LLM 要么真实调用工具、要么改写回复删除发卡表述。
 *
 * 每轮一个新实例（[AgentRunner.runTurn] 内创建），无跨轮状态。
 */
internal class CardClaimGuard {

    private val cardSent = AtomicBoolean(false)
    private val corrections = AtomicInteger(0)

    /** 卡工具 Action 过发射点门控时调用（过门控 = 卡真实落流下行端侧）。 */
    fun markCardSent() {
        cardSent.set(true)
    }

    /** 终结消息是否需要纠正回环：声称发卡 + 当轮未真实发卡 + 纠正次数未达上限。 */
    fun needsCorrection(assistantText: String): Boolean =
        !cardSent.get() && corrections.get() < MAX_CORRECTIONS && isCardClaim(assistantText)

    fun markCorrected() {
        corrections.incrementAndGet()
    }

    companion object {
        /** 单轮纠正上限：防 LLM 坚持己见死循环（第二次放手让终结消息照常落流）。 */
        const val MAX_CORRECTIONS = 1

        /** 发卡工具集：render_ui（A2UI）/ render_ui_openui（OpenUI 线）/ render_html（H5 卡）。 */
        val CARD_TOOLS = setOf(RenderUiValidator.TOOL_NAME, RenderOpenUiValidator.TOOL_NAME, "render_html")

        internal const val CORRECTION_PROMPT =
            "系统校验：你刚才的回复声称卡片已下发，但本轮并未成功调用发卡工具，" +
                "用户实际没有收到任何卡片。若确需发卡，立即调用当前会话可用的发卡工具" +
                "（render_ui / render_ui_openui / render_html）真实下发；" +
                "若不需要发卡，直接给出改写后的回复（删除「已下发/已生成」类表述），不要提及本提示。"

        private val CLAIM_PATTERN = Regex(
            "卡片?[^。\\n]{0,8}(已下发|已生成|已发送|已推送|已展示|已渲染)" +
                "|(已下发|已生成|已发送|已推送|已展示|已渲染)[^。\\n]{0,8}卡片?" +
                "|已下发[^。\\n]{0,4}[✅✔]" +
                "|\\bcards?\\b[^.\\n]{0,24}(has been |was )?(sent|delivered|generated|rendered|created)",
            RegexOption.IGNORE_CASE,
        )

        fun isCardClaim(text: String): Boolean = CLAIM_PATTERN.containsMatchIn(text)
    }
}
