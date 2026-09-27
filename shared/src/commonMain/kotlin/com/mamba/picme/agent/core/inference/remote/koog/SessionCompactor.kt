package com.mamba.picme.agent.core.inference.remote.koog

import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import kotlin.time.Clock

/**
 * 摘要生成通道（函数式接口，便于测试注入假实现；生产经 [SummaryGeneratorViaExecutor] 接 Koog executor）。
 */
fun interface SummaryGenerator {
    /**
     * 发一次摘要请求，返回模型原始输出（JSON 或自由文本——解析/回退归 [CompactionSlots.parseSlots]）。
     * 抛异常 = 调用方跳过本轮压缩（决策 9）。
     */
    suspend fun generate(promptText: String): String
}

/**
 * M2 滚动压缩执行器（US-2.1/2.3/2.5，spec §4）。
 *
 * 触发判定 → 选候选 → 构造 prompt（含旧摘要增量合并）→ 经 [SummaryGenerator] 发请求 →
 * 解析/合并/版本递增 → 存 [ChatMemoryStore.saveSummary]。**不改写历史消息**——历史仍按 M1
 * `assembleForPersistence` 预算组装；摘要只增量叠加（compaction 是叠加式摘要，非替换式删消息）。
 *
 * 失败模式（spec §5 决策 9）：摘要请求失败/解析回退都**静默跳过本轮**，绝不因记忆系统阻断对话。
 */
class SessionCompactor(
    private val store: ChatMemoryStore,
    private val generator: SummaryGenerator,
    private val budgetTokens: Int = KoogMessageMemory.DEFAULT_BUDGET_TOKENS,
) {
    private val tag = "SessionCompactor"

    /**
     * 判定并执行压缩。无候选（未超预算/保护区外无可压轮）时零开销直接返回；
     * 压缩成功写摘要；失败静默（下次再试）。
     */
    suspend fun maybeCompact(sessionId: String) {
        val history = try {
            store.load(sessionId)
        } catch (exception: Exception) {
            Logger.w(tag, "load history failed, skip compaction: ${exception.message}")
            return
        }

        val candidates = KoogMessageMemory.selectCompactionCandidates(history, budgetTokens)
        if (candidates.isEmpty()) return

        val previous = try {
            store.loadSummary(sessionId)
        } catch (exception: Exception) {
            Logger.w(tag, "load summary failed, treat as fresh: ${exception.message}")
            null
        }

        val promptText = CompactionPrompt.build(previous, candidates)
        val raw = try {
            generator.generate(promptText)
        } catch (exception: Exception) {
            Logger.w(tag, "summary request failed, skip this round: ${exception.message}")
            return
        }

        val newSlots = CompactionSlots.parseSlots(raw)
        val merged = previous?.slots?.merge(newSlots) ?: newSlots
        val nextVersion = (previous?.version ?: 0) + 1
        val compaction = SessionCompaction(
            version = nextVersion,
            slots = merged,
            compactedUpToTurnId = countRealTurns(candidates),
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
        try {
            store.saveSummary(sessionId, compaction)
            Logger.i(tag, "compacted session=$sessionId v$nextVersion candidates=${candidates.size}")
        } catch (exception: Exception) {
            Logger.e(tag, "save summary failed: ${exception.message}")
        }
    }

    private fun countRealTurns(messages: List<ai.koog.prompt.message.Message>): Int =
        messages.count { message ->
            message is ai.koog.prompt.message.Message.User &&
                message.parts.none { part -> part is ai.koog.prompt.message.MessagePart.Tool.Result }
        }
}
