package com.mamba.picme.server.agent

/**
 * 回合级 LLM 用量累计（done 帧性能数据源）。
 *
 * Koog EventHandler `onLLMCallCompleted` 的 metaInfo 携带单次调用 input/output tokens，
 * 回合内多轮 LLM 调用（工具循环）逐次累加；elapsedMs 由 [AgentRunner] 在回合成功收尾时
 * 回填。旧端忽略 done 帧未知字段，协议向后兼容。
 */
class TurnUsage {
    var promptTokens: Long = 0
        private set
    var completionTokens: Long = 0
        private set
    var elapsedMs: Long = -1
        private set

    fun add(prompt: Long?, completion: Long?) {
        prompt?.let { promptTokens += it }
        completion?.let { completionTokens += it }
    }

    fun finish(elapsed: Long) {
        elapsedMs = elapsed
    }
}
