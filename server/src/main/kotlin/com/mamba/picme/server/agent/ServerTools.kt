package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.asToolsByClass
import java.time.Instant

/**
 * 云端 Agent 的服务端工具（ADR-018 决策 2：服务端工具本地执行域）。
 *
 * M1 只有只读的 server_time（验证工具闭环链路）；云端浏览器等接入为后续里程碑。
 * 端侧工具（相册/媒体）走工具桥，不在此注册。
 *
 * 决策 1a 可逆性元数据：[REVERSIBLE_TOOLS]——server_time 只读无副作用，
 * 不属于可逆语义管辖（可逆性针对有副作用工具），故集合为空。
 */
class ServerToolService(private val clock: () -> Long = { System.currentTimeMillis() }) {

    @Tool(customName = "server_time")
    @LLMDescription("获取服务器当前时间，返回毫秒时间戳与 ISO-8601 字符串")
    suspend fun serverTime(): String {
        val ms = clock()
        return "server_time: epochMs=$ms iso=${Instant.ofEpochMilli(ms)}"
    }

    companion object {
        /** 有副作用且声明了逆操作的工具名集合（决策 1a）。M1 无为空集。 */
        val REVERSIBLE_TOOLS: Set<String> = emptySet()

        /** M1 默认 Koog ToolRegistry（reflect 展开，JVM-only API——server 即 JVM）。 */
        fun defaultRegistry(clock: () -> Long = { System.currentTimeMillis() }): ToolRegistry =
            ToolRegistry { tools(ServerToolService(clock).asToolsByClass()) }
    }
}

/**
 * 服务端工具直派器：pending action 重放（崩溃恢复）时按工具名执行。
 * 与 Koog ToolRegistry 同源注册，避免双份实现漂移。
 */
class ServerToolDispatcher {
    private val handlers = linkedMapOf<String, suspend (String) -> String>()

    fun register(name: String, handler: suspend (String) -> String): ServerToolDispatcher {
        handlers[name] = handler
        return this
    }

    fun contains(name: String): Boolean = handlers.containsKey(name)

    /** @throws IllegalArgumentException 未知工具 */
    suspend fun dispatch(name: String, argsJson: String): String =
        handlers[name]?.invoke(argsJson)
            ?: throw IllegalArgumentException("unknown server tool: $name")

    companion object {
        /** 与 [ServerToolService.defaultRegistry] 同源：同一 clock、同一实现。 */
        fun default(clock: () -> Long = { System.currentTimeMillis() }): ServerToolDispatcher {
            val service = ServerToolService(clock)
            return ServerToolDispatcher().register("server_time") { service.serverTime() }
        }
    }
}
