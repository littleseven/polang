package com.mamba.picme.server.agent

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具桥挂起表（ADR-018 决策 2，spec §3）：requestId → 等待端侧结果的 Deferred。
 * 内存单进程语义——服务重启即清空（spec §3.4 服务重启行：残留 RUNNING 会话由
 * 下轮 pending 重放兜底）。条目随 deferred 完成自动摘除（invokeOnCompletion），防泄漏。
 */
class AgentBridgeRegistry {
    private data class Entry(val sessionId: String, val deferred: CompletableDeferred<String>)

    private val pending = ConcurrentHashMap<String, Entry>()

    fun register(sessionId: String, requestId: String): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        pending[requestId] = Entry(sessionId, deferred)
        deferred.invokeOnCompletion { pending.remove(requestId) }
        return deferred
    }

    /** @return false = requestId 未知或已完成（路由层据此回 409 tool_request_not_pending）。 */
    fun complete(requestId: String, result: String): Boolean =
        pending[requestId]?.deferred?.complete(result) ?: false

    /** SSE 连接关闭时清理该会话全部挂起项；等待方以 error 文本闭环（Observation 照常落流）。 */
    fun failAll(sessionId: String, reason: String) {
        pending.values.filter { entry -> entry.sessionId == sessionId }.forEach { entry ->
            entry.deferred.complete("error: $reason")
        }
    }

    /** 测试探针。 */
    internal fun pendingCount(): Int = pending.size
}
