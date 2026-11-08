package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.OptimizeCandidateGroup

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.ScoredCandidate
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.features.editor.EditRecipe

/**
 * chat 页 AI 优化抽卡编排器。
 * spec: 2026-08-06 chat-optimize-gacha 设计稿（已随交付清理，git 历史可查）
 *
 * 职责：
 * - 调 [AiOptimizeUseCase.optimizeWithGacha] 抽卡，候选缩略图经 [ChatImageStore] 落盘，
 *   构造 [OptimizeCandidateGroup] 消息负载（auto 落库已在 usecase 内完成）
 * - 维护 pending 组内存态（messageId → 候选 preset / 评分），支撑换一组 / 确认 / 废弃
 * - 确认：全尺寸渲染 + 写 [ChatEditStateHolder]（多轮 delta 续调基础）+ 落库 user
 * - 废弃：落库 dismiss（用户发新消息 / 切会话 / 清空对话时由 ViewModel 触发）
 *
 * 内存态为进程级：进程重建后 pending 丢失，对应卡条由 UI 降级只读（spec §4）。
 */
class ChatOptimizeGachaController(
    private val optimizeUseCase: AiOptimizeUseCase,
    private val chatImageRenderer: ChatImageRenderer,
    private val chatImageStore: ChatImageStore,
    private val feedbackLogger: OptimizeFeedbackLogger?,
    private val chatEditStateHolder: ChatEditStateHolder
) {

    /** 一组 pending 候选的内存态（候选 preset 不落消息，确认/重抽从这里取）。 */
    data class PendingGroup(
        val messageId: String,
        val sessionId: String,
        val sourceImageUri: String,
        val scene: Scene,
        val scored: List<ScoredCandidate>,
        val usedFingerprints: Set<String>,
        val drawIndex: Int
    )

    /** 抽卡结果 */
    sealed interface DrawOutcome {
        /** 候选卡组（Selected / KeepOriginal 均发卡组，区别在 recommendedIndex） */
        data class Candidates(
            val group: OptimizeCandidateGroup
        ) : DrawOutcome

        /** 抽卡不可用 / 缩略图全部落盘失败：退回现有单发结果（imageUri=null 时按错误文本处理） */
        data class Fallback(val imageUri: String?, val explanation: String) : DrawOutcome
    }

    /** 换一组结果 */
    sealed interface RerollOutcome {
        data class Rerolled(val group: OptimizeCandidateGroup) : RerollOutcome

        /** 内存态丢失（进程重建后）；UI 已降级只读时不会触发 */
        data object Expired : RerollOutcome

        /** 引擎不可用 / 落盘全失败：保留当前卡条，由 UI 提示 */
        data object Unavailable : RerollOutcome
    }

    /** 确认结果 */
    data class ConfirmResult(val imageUri: String, val recipe: EditRecipe)

    private val pendingGroups = mutableMapOf<String, PendingGroup>()

    fun hasPending(messageId: String): Boolean = pendingGroups.containsKey(messageId)

    /**
     * 抽卡（新消息）。
     *
     * @param messageId 调用方生成的消息 id，pending 内存态以它为键
     */
    suspend fun draw(messageId: String, imageUri: String, sessionId: String): DrawOutcome {
        val outcome = optimizeUseCase.optimizeWithGacha(imageUri)
        val (scored, recommendedIndex) = when (val r = outcome.result) {
            is GachaResult.Selected -> r.all to r.best.candidate.index
            is GachaResult.KeepOriginal -> r.all to -1
            GachaResult.Unavailable -> return fallback(imageUri, sessionId)
        }
        return persistAndBuild(
            messageId = messageId,
            imageUri = imageUri,
            sessionId = sessionId,
            scene = outcome.scene,
            usedFingerprints = outcome.usedFingerprints,
            scored = scored,
            recommendedIndex = recommendedIndex,
            drawIndex = 1,
        ) ?: fallback(imageUri, sessionId)
    }

    /** 换一组：以 pending 的 usedFingerprints 为 exclude 重抽并替换内存态。 */
    suspend fun reroll(messageId: String): RerollOutcome {
        val pending = pendingGroups[messageId] ?: return RerollOutcome.Expired
        val outcome = optimizeUseCase.optimizeWithGacha(
            imageUri = pending.sourceImageUri,
            exclude = pending.usedFingerprints
        )
        val (scored, recommendedIndex) = when (val r = outcome.result) {
            is GachaResult.Selected -> r.all to r.best.candidate.index
            is GachaResult.KeepOriginal -> r.all to -1
            GachaResult.Unavailable -> return RerollOutcome.Unavailable
        }
        val built = persistAndBuild(
            messageId = messageId,
            imageUri = pending.sourceImageUri,
            sessionId = pending.sessionId,
            scene = outcome.scene,
            usedFingerprints = outcome.usedFingerprints,
            scored = scored,
            recommendedIndex = recommendedIndex,
            drawIndex = pending.drawIndex + 1,
        ) ?: return RerollOutcome.Unavailable
        return RerollOutcome.Rerolled(built.group)
    }

    /**
     * 确认选中卡：全尺寸渲染 → 写 [ChatEditStateHolder] → 落库 user → 清内存态。
     *
     * @return null = 内存态丢失 / 卡不存在 / 卡被淘汰 / 渲染失败（调用方提示重试）
     */
    suspend fun confirm(messageId: String, candidateIndex: Int): ConfirmResult? {
        val pending = pendingGroups[messageId] ?: return null
        val scored = pending.scored.firstOrNull { it.candidate.index == candidateIndex } ?: return null
        if (scored.rejected) return null
        val recipe = OptimizeRecipeMapper.toEditRecipe(
            preset = scored.candidate.preset,
            sourceUri = pending.sourceImageUri,
            baseRecipe = EditRecipe(sourceUri = pending.sourceImageUri)
        )
        // 先摘除再渲染：渲染期间 discardPending 无法窃取该组，杜绝 dismiss/user 双落库
        pendingGroups.remove(messageId)
        val rendered = chatImageRenderer.renderRecipe(
            pending.sourceImageUri, recipe, pending.sessionId
        )
        if (rendered == null) {
            pendingGroups[messageId] = pending // 渲染失败保持可重试（spec §8）
            return null
        }
        chatEditStateHolder.update(pending.sessionId, recipe)
        runCatching {
            feedbackLogger?.log(
                pending.sourceImageUri, pending.scene, pending.scored,
                candidateIndex, OptimizeFeedbackLogger.SOURCE_USER
            )
        }
        return ConfirmResult(imageUri = rendered, recipe = recipe)
    }

    /**
     * 废弃会话内 pending 组（用户发新消息 / 切会话 / 清空对话 / 删会话时调用），落库 dismiss。
     *
     * @param exceptMessageId 需要保留的消息 id（一般不用；默认全部废弃）
     */
    suspend fun discardPending(sessionId: String, exceptMessageId: String? = null) {
        val discarded = pendingGroups.values.filter {
            it.sessionId == sessionId && it.messageId != exceptMessageId
        }
        discarded.forEach { p ->
            runCatching {
                feedbackLogger?.log(
                    p.sourceImageUri, p.scene, p.scored, -1,
                    OptimizeFeedbackLogger.SOURCE_DISMISS
                )
            }
            pendingGroups.remove(p.messageId)
        }
        if (discarded.isNotEmpty()) {
            Logger.d(TAG, "discarded ${discarded.size} pending gacha group(s) for session $sessionId")
        }
    }

    /** 候选缩略图落盘 + 构造消息负载 + 登记内存态；全部落盘失败返回 null（调用方走降级）。 */
    private suspend fun persistAndBuild(
        messageId: String,
        imageUri: String,
        sessionId: String,
        scene: Scene,
        usedFingerprints: Set<String>,
        scored: List<ScoredCandidate>,
        recommendedIndex: Int,
        drawIndex: Int,
    ): DrawOutcome.Candidates? {
        val uiCandidates = scored.map { sc ->
            val thumbPath = sc.thumbnail?.let { bmp ->
                runCatching { chatImageStore.writeResult(sessionId, bmp, "image/jpeg") }
                    .onFailure { Logger.w(TAG, "persist thumbnail failed: ${it.message}") }
                    .getOrNull()
            }.orEmpty()
            OptimizeCandidateGroup.Candidate(
                direction = sc.candidate.direction,
                thumbPath = thumbPath,
                nimaScore = sc.nimaScore,
                rejected = sc.rejected
            )
        }
        if (uiCandidates.all { it.thumbPath.isBlank() }) {
            Logger.w(TAG, "all candidate thumbnails failed to persist, gacha degraded")
            return null
        }
        val group = OptimizeCandidateGroup(
            sourceImageUri = imageUri,
            scene = scene.name,
            recommendedIndex = recommendedIndex,
            candidates = uiCandidates,
            usedFingerprints = usedFingerprints.toList(),
            drawIndex = drawIndex
        )
        pendingGroups[messageId] = PendingGroup(
            messageId = messageId,
            sessionId = sessionId,
            sourceImageUri = imageUri,
            scene = scene,
            // 剥离缩略图 Bitmap：落盘后 UI 走 Coil 读文件，内存态 Bitmap 无人消费（每组约 4MB）
            scored = scored.map { it.copy(thumbnail = null) },
            usedFingerprints = usedFingerprints,
            drawIndex = drawIndex
        )
        return DrawOutcome.Candidates(group = group)
    }

    /** 退回现有单发路径（与抽卡接入前行为完全一致）。 */
    private suspend fun fallback(imageUri: String, sessionId: String): DrawOutcome {
        val outcome = chatImageRenderer.aiOptimize(imageUri, sessionId)
        return DrawOutcome.Fallback(imageUri = outcome.imageUri, explanation = outcome.explanation)
    }

    companion object {
        private const val TAG = "PoLang:ChatGacha"
    }
}
