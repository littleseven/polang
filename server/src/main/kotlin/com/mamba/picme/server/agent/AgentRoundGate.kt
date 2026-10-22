package com.mamba.picme.server.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * 云端 Agent 跑轮 per-user 并发闸（M2.6 加固）：内存态，重启即清。
 *
 * 消除两类事故形态：
 * 1. 同会话并发跑轮——两轮事件按落库时间交错追加、投影互不可见，且任一轮抛错
 *    把整个会话置 ERROR 连坐另一轮（原 M1 已知边界）；
 * 2. 失控/恶意客户端并发开 N 条 SSE 长轮，把上游 LLM 并发与 2C2G 单机打满。
 *
 * 不变式：租约只在 messages 路由 try/finally 严格配对释放，**Gate 本身不做 TTL 清扫**——
 * 一轮理论最长驻留 20 迭代 ×（LLM 延迟 + 120s 工具挂起），Gate 层 TTL 可能在合法
 * 长轮上提前放行形成双轮；进程崩溃则内存表随之消失，无需兜底。
 * 端断线/卡死防护（M2.7）：路由层以 withTimeoutOrNull(ROUND_TIMEOUT_MS) 包裹 Koog loop，
 * 确保最坏情况下协程被取消、finally 必然执行——不依赖 Koog loop 的取消传递
 * （EventHandler 同步钩子 runBlocking 会阻断，2026-10-22 生产事故）。
 * 与 RateLimiter / BrowserConcurrencyRegistry 同约束：单实例语义，多实例需外置。
 */
class AgentRoundGate {

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 占用成功返回 true；该用户已有在跑轮返回 false（调用方回 409 round_in_flight）。 */
    fun tryAcquire(tokenHash: String): Boolean = inFlight.add(tokenHash)

    fun release(tokenHash: String) {
        inFlight.remove(tokenHash)
    }

    fun activeCount(): Int = inFlight.size
}
