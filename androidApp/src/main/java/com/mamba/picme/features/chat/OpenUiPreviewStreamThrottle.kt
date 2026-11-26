package com.mamba.picme.features.chat

/**
 * 本地回合 OpenUI 程序预览流的写入节流器（Task 13，U2 跟进）。
 *
 * 镜像 server `OpenUiContentStreamTracker` 的发射粒度（≥[MIN_EMIT_CHARS] 字符增量且
 * 距上次发射 ≥[MIN_EMIT_INTERVAL_MS]），压低 TextSnapshot 帧直写 `_openUiStreamProgram`
 * 的频率：低端机（256MB heap）上每帧累计快照全量重解析 + Compose 重组叠加 TAG 扫描
 * 堆压力曾致 OOM（device-evidence/crash-stacks.txt，4 次崩溃）。
 *
 * 与 server tracker 的语义差异（输入是**累计全文快照**而非增量 delta）：
 * - reset 后首个快照直通（预览即时出现，改道决策当场可观测——T8 latch 语义依赖）；
 * - 快照与当前累计不连续（回退/新轮防御）时重置发射基准并直通（跨回合残留天然无害）；
 * - [flushTail] 收尾强制发射最新累计：收尾判定/清空动作针对最新完整文本，
 *   不因节流滞后丢尾帧（保尾帧契约）。
 *
 * 线程模型与 [StreamingPacingController] 同款：事件流有序、单线程消费
 * （Koog 事件回调串行），无锁。
 */
internal class OpenUiPreviewStreamThrottle(
    private val minEmitChars: Int = MIN_EMIT_CHARS,
    private val minEmitIntervalMs: Long = MIN_EMIT_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var currentText: String = ""
    private var lastEmittedLen: Int = 0
    private var lastEmitAt: Long = 0L
    private var hasEmitted: Boolean = false

    /** 新一轮程序流（RoundStarted / ToolCallStarted 打断 / 回合收尾后）：累计与发射基准归零。 */
    fun reset() {
        currentText = ""
        lastEmittedLen = 0
        lastEmitAt = 0L
        hasEmitted = false
    }

    /**
     * 累计快照进入节流判定。
     * @return 应写入预览轨的最新文本；本帧不发射 → null（调用方不写）。
     */
    fun onTextSnapshot(text: String): String? {
        if (text == currentText) return null
        val isContinuousGrowth = text.length > currentText.length && text.startsWith(currentText)
        currentText = text
        if (!isContinuousGrowth) return emit() // 回退/新轮：重置基准直通
        if (!hasEmitted) return emit() // 首帧直通（预览即时出现）
        return if (shouldEmit()) emit() else null
    }

    /**
     * 收尾保尾帧：强制发射最新累计（回合收尾前调用——keepPartial 判定/清空动作
     * 针对最新完整文本，节流滞后不得丢尾）。
     * @return 累计为空或与上次发射一致（无尾帧可补）→ null。
     */
    fun flushTail(): String? =
        if (currentText.isNotEmpty() && currentText.length != lastEmittedLen) emit() else null

    private fun shouldEmit(): Boolean =
        currentText.length - lastEmittedLen >= minEmitChars &&
            now() - lastEmitAt >= minEmitIntervalMs

    private fun emit(): String {
        lastEmittedLen = currentText.length
        lastEmitAt = now()
        hasEmitted = true
        return currentText
    }

    companion object {
        /** 发射最小字符增量（镜像 server OpenUiContentStreamTracker 同粒度）。 */
        const val MIN_EMIT_CHARS = 48

        /** 发射最小间隔毫秒（镜像 server OpenUiContentStreamTracker 同粒度）。 */
        const val MIN_EMIT_INTERVAL_MS = 80L
    }
}
