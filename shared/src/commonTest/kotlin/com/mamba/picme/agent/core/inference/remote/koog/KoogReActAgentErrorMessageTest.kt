package com.mamba.picme.agent.core.inference.remote.koog

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * [buildFriendlyErrorMessage] 单测（自旧 RemoteReActAgent 随迁逻辑的回归护栏，纯 JVM 无网络）。
 */
class KoogReActAgentErrorMessageTest {

    @Test
    fun `upstream_error anywhere in cause chain maps to service unavailable message`() {
        val direct = RuntimeException("upstream_error: model overloaded")
        assertEquals(
            "远程模型服务暂时不可用（upstream error），请稍后重试，或到设置切换其他模型供应商。",
            buildFriendlyErrorMessage(direct)
        )
        // 包装在 cause 链深处也要命中
        val wrapped = RuntimeException("http 502", IllegalStateException("UPSTREAM_ERROR from gateway"))
        assertEquals(
            "远程模型服务暂时不可用（upstream error），请稍后重试，或到设置切换其他模型供应商。",
            buildFriendlyErrorMessage(wrapped)
        )
    }

    @Test
    fun `tool_calls sequence error maps to session reset message`() {
        val e = RuntimeException("messages with role 'tool' must be a response to a preceeding message with 'tool_calls'")
        assertEquals(
            "对话历史中的工具调用消息顺序异常，已自动重置会话，请重新发送指令。",
            buildFriendlyErrorMessage(e)
        )
    }

    @Test
    fun `generic error falls through to message passthrough`() {
        assertEquals(
            "远程模型调用失败：connection reset",
            buildFriendlyErrorMessage(RuntimeException("connection reset"))
        )
        assertTrue(buildFriendlyErrorMessage(RuntimeException()).endsWith("未知错误"))
    }

    @Test
    fun `agent loop stuck maps to loop stopped message`() {
        val expected = "任务在工具调用中陷入循环（或超出步数上限），已自动停止。请换个说法，或把任务拆小一点重试。"
        assertEquals(expected, buildFriendlyErrorMessage(AgentLoopStuckException("tool loop guard tripped")))
        // 包装在 cause 链深处也要命中（agent 包装层可能再包一层）
        val wrapped = RuntimeException("run failed", AgentLoopStuckException("max iterations reached: 30"))
        assertEquals(expected, buildFriendlyErrorMessage(wrapped))
    }
}
