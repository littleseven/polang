package com.mamba.picme.server.agent

/**
 * Koog 流式帧 → render_ui_openui program 增量预览的跟踪器（openui P2，spec
 * 《2026-10-26-openui-compose-alignment-design》§4 P2）。
 *
 * OpenAI 兼容协议下工具调用增量首帧才带 name、后续帧只有 arguments 增量，多工具并行
 * 调用按 id（缺省 index）归组。本类只跟踪 [toolName] 命中的流，其余工具调用忽略。
 *
 * 节流：距上次发射累积 ≥ [minEmitChars] 且间隔 ≥ [minEmitIntervalMs] 才发射；
 * [onComplete] 补尾无视节流（正式 action 帧紧随其后，尾帧保证预览与完整程序衔接）。
 * 发射去重：前缀未推进不重复发。
 */
internal class OpenUiToolCallStreamTracker(
    private val toolName: String = RenderOpenUiValidator.TOOL_NAME,
    private val minEmitChars: Int = 48,
    private val minEmitIntervalMs: Long = 80,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private class Stream(
        var name: String?,
        val index: Int?,
        val extractor: OpenUiProgramStreamExtractor,
    )

    private val streams = mutableMapOf<String, Stream>()
    private var lastEmittedLen = 0
    private var lastEmitAt = 0L
    private var hasEmitted = false

    /**
     * @return 应发射的 program 前缀；本帧不触发发射 → null。
     */
    fun onDelta(id: String?, name: String?, content: String?, index: Int?): String? {
        val stream = streamFor(id, name, index) ?: return null
        if (!name.isNullOrBlank()) stream.name = name
        if (stream.name != toolName) return null
        val prefix = stream.extractor.feed(content.orEmpty()) ?: return null
        return if (shouldEmit(prefix.length)) emit(prefix) else null
    }

    /** 工具调用流收尾：补尾发射（前缀有推进才发）。 */
    fun onComplete(id: String?, index: Int?): String? {
        val stream = resolve(id, index) ?: return null
        if (stream.name != toolName) return null
        // extractor 无增量可喂，直接重提取终态（终帧 content 已在最后的 onDelta 喂入）
        val prefix = stream.extractor.feed("") ?: return null
        return if (prefix.length > lastEmittedLen) emit(prefix) else null
    }

    private fun streamFor(id: String?, name: String?, index: Int?): Stream? {
        // 首帧（带 name）必登记；后续帧按 key 归组。name 显式非本工具 → 登记后短路忽略，
        // 防同 key 后续帧误归属（id 缺失时 index 归组，name 以首帧为准）
        streams[keyOf(id, index)]?.let { return it }
        if (name == null) {
            // OpenAI 协议只在首帧带 id/name，后续增量帧 id 缺失（2026-10-26 真机日志实证：
            // DeepSeek 后续 chunk 无 id，key 错位导致整条流被丢弃）→ 按 index 别名回溯首帧
            // 登记的流；index 也缺省时单流场景归属唯一流，多流无法安全归属则丢弃
            val resolved = resolve(id, index)
            if (resolved != null) return resolved
            if (streams.isEmpty()) {
                return Stream(null, index, OpenUiProgramStreamExtractor()).also { streams[keyOf(id, index)] = it }
            }
            return null
        }
        return Stream(name, index, OpenUiProgramStreamExtractor()).also { streams[keyOf(id, index)] = it }
    }

    private fun resolve(id: String?, index: Int?): Stream? {
        streams[keyOf(id, index)]?.let { return it }
        if (index != null) {
            streams.values.singleOrNull { it.index == index }?.let { return it }
        }
        // id/index 全缺：只有一个已登记流时归属它（多流并行无法安全归属，宁可丢帧不串流）
        return streams.values.singleOrNull()
    }

    private fun keyOf(id: String?, index: Int?): String =
        id?.takeIf { it.isNotBlank() } ?: "index:${index ?: 0}"

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
