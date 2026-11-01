package com.mamba.picme.server.agent

/**
 * 回合原生（turn-native）content 流 → 响应 program 前缀的节流发射器（spec§3.2）。
 *
 * 语义对齐 [OpenUiToolCallStreamTracker]（≥[minEmitChars] 增量且距上次 ≥[minEmitIntervalMs]
 * 才发射；前缀未推进不重复发），但跟踪对象从「工具 args JSON 增量」换成「content
 * TextDelta 纯文本累计」——响应即程序，无需 JSON 转义剥离。每轮 LLM 流式开始
 * （EventHandler onLLMStreamingStarting）调 [onRoundStart] 重置：回合内多轮 LLM 输出
 * （中间迷你程序 → 终结整树）各自从空累计，端上覆盖式替换预览。
 */
internal class OpenUiContentStreamTracker(
    private val minEmitChars: Int = 48,
    private val minEmitIntervalMs: Long = 80,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val buffer = StringBuilder()
    private var lastEmittedLen = 0
    private var lastEmitAt = 0L
    private var hasEmitted = false

    /** 新一轮 LLM 流式开始：本轮前缀从空重新累计（端上预览覆盖式替换）。 */
    fun onRoundStart() {
        buffer.setLength(0)
        lastEmittedLen = 0
        lastEmitAt = 0L
        hasEmitted = false
    }

    /** @return 应发射的 program 前缀；本帧不触发发射 → null。 */
    fun onTextDelta(text: String): String? {
        buffer.append(text)
        val prefix = buffer.toString()
        return if (shouldEmit(prefix.length)) emit(prefix) else null
    }

    private fun shouldEmit(prefixLen: Int): Boolean =
        prefixLen - lastEmittedLen >= minEmitChars &&
            (!hasEmitted || now() - lastEmitAt >= minEmitIntervalMs)

    private fun emit(prefix: String): String {
        lastEmittedLen = prefix.length
        lastEmitAt = now()
        hasEmitted = true
        return prefix
    }
}
