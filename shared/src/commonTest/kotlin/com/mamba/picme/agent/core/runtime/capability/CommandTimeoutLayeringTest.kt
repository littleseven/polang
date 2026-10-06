package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 命令超时层叠不变式守卫：registry 内层 [CommandExecutor] 超时必须 ≥ browser 外层 dispatch 超时。
 *
 * 回归背景（2026-10-06 review 发现的缺陷，431 用例全绿仍溜过）：外层
 * `ChatToolService.dispatchCommand` 为 browser_* 分级 25s，但内层 [CommandExecutor]
 * 默认 10s 仍是最紧约束——慢页面（服务端 navTimeout 15s 容忍区间内）被内层必杀，
 * 外层 25s 永不触发。数值断言只能钉常量，本测试读 registry **实例**实际生效值，
 * 防的是构造点回退（如 `CommandExecutor()` 裸默认）这类改法。
 */
class CommandTimeoutLayeringTest {

    @Test
    fun `registry inner executor timeout must cover browser outer dispatch timeout`() {
        val innerMs = CapabilityRegistry.create().commandExecutorTimeoutMs
        assertTrue(
            innerMs >= ChatToolService.BROWSER_DISPATCH_TIMEOUT_MS,
            "层叠不变式破坏：registry 内层 CommandExecutor ${innerMs}ms < " +
                "browser 外层 dispatch ${ChatToolService.BROWSER_DISPATCH_TIMEOUT_MS}ms——" +
                "慢页面会被内层必杀，外层超时永不生效"
        )
    }

    @Test
    fun `registry command timeout must sit inside the server budget window`() {
        // 绝对预算区间（防「常量整体调小、相对关系仍绿」）：
        // 下界 = 服务端 bridge navTimeoutMs=15000（慢页面 domcontentloaded 5~15s 的容忍区间）；
        // 上界 = 网关超时 30s（客户端必须更紧，否则网关先断、错误语义劣化）。
        assertTrue(
            CommandExecutor.REGISTRY_COMMAND_TIMEOUT_MS > 15_000L,
            "REGISTRY_COMMAND_TIMEOUT_MS=${CommandExecutor.REGISTRY_COMMAND_TIMEOUT_MS}ms 必须 > 服务端 navTimeout 15000ms"
        )
        assertTrue(
            CommandExecutor.REGISTRY_COMMAND_TIMEOUT_MS < 30_000L,
            "REGISTRY_COMMAND_TIMEOUT_MS=${CommandExecutor.REGISTRY_COMMAND_TIMEOUT_MS}ms 必须 < 网关超时 30000ms"
        )
    }
}
