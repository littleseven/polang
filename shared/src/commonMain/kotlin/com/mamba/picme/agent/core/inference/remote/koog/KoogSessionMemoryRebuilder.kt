package com.mamba.picme.agent.core.inference.remote.koog

import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import com.mamba.picme.domain.chat.ModelInputItem

/**
 * 短期记忆冷启动重建器（分层记忆 spec M4：主从制——Room 长期归档为 SSOT，
 * Koog ChatMemory 为可丢弃、可从长期轨重建的派生缓存）。
 *
 * 触发场景：备份恢复/换机/缓存被清后，Room 仍持有完整聊天历史而 `koog_memory_`
 * 键为空——此时若直接跑 agent loop，模型对屏幕上用户可见的历史完全失忆
 * （「继续刚才说的」断裂）。本器在会话发起推理前兜底：
 *
 * - 短期轨非空 → 不动（幂等，正常路径零开销一次 load）；
 * - 短期轨空且长期轨投影为空（全新会话）→ 不写；
 * - 短期轨空且长期轨有历史 → 经 [toKoogMemoryMessages] 投影后 save
 *  （预算/老化/条数裁剪由 store 的 assembleForPersistence 统一承担）。
 *
 * 调用方须 runCatching 包裹：记忆重建失败绝不阻断对话（spec 决策 9 同语义）。
 */
class KoogSessionMemoryRebuilder(
    private val store: ChatMemoryStore,
) {

    /**
     * 短期轨为空时从长期轨投影重建。返回是否发生了重建写入。
     * [projector] 由平台层提供（Android = Room chat_messages 经 toModelInput 投影），
     * 仅在本器确认短期轨为空后才调用。
     */
    suspend fun rebuildIfEmpty(
        sessionId: String,
        projector: suspend (sessionId: String) -> List<ModelInputItem>,
    ): Boolean {
        if (store.load(sessionId).isNotEmpty()) return false
        val items = projector(sessionId)
        if (items.isEmpty()) return false
        store.save(sessionId, items.toKoogMemoryMessages())
        Logger.i(TAG, "rebuilt session memory from archive: session=$sessionId, items=${items.size}")
        return true
    }

    private companion object {
        const val TAG = "PoLang:Chat"
    }
}
