package com.mamba.picme.agent.core.platform.storage

import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.inference.remote.koog.KoogMessageMemory
import com.mamba.picme.agent.core.platform.logging.Logger
import platform.Foundation.NSUserDefaults

/**
 * Koog 版对话历史持久化的 iOS actual（Phase 6.2 T1）：NSUserDefaults 承载。
 *
 * 与 Android `KoogMessageMemoryStore`（DataStore）逐语义对齐：
 * - 持久化键 `koog_memory_$sessionId`（同键前缀，跨端语义一致）；
 * - 三不变式复用 [KoogMessageMemory]（SystemMessage 不落盘 / tool_call 块原子裁剪 / 双向配对剔除）；
 * - 编解码复用 commonMain [encodeKoogMessages] / [decodeKoogMessages]（同模块 internal 可用）；
 * - NSUserDefaults 本身线程安全且同步落盘快，无需 DataStore 式串行 dispatcher；
 * - 所有异常路径降级为空列表/静默（对齐 Android 的容错语义，记忆丢失不阻断聊天）。
 */
class IosKoogMessageMemoryStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : ChatMemoryStore {

    private val tag = "IosKoogMessageMemoryStore"

    /** 加载：解码 → 剔除 System（不变式①）→ 双向配对 sanitize（不变式③）。 */
    override suspend fun load(sessionId: String): List<Message> = try {
        val raw = defaults.stringForKey(key(sessionId)) ?: return emptyList()
        KoogMessageMemory.sanitizeToolPairing(
            KoogMessageMemory.withoutSystemMessages(decodeKoogMessages(raw))
        )
    } catch (exception: Exception) {
        Logger.w(tag, "Failed to load history for session $sessionId", exception)
        emptyList()
    }

    /** 保存：M1 组装（剔 System① → 老化旧 tool result → token 预算裁剪 → 条数上限兜底②）→ 编码落盘。 */
    override suspend fun save(sessionId: String, messages: List<Message>) {
        try {
            val persisted = KoogMessageMemory.assembleForPersistence(messages)
            defaults.setObject(encodeKoogMessages(persisted), forKey = key(sessionId))
            Logger.d(tag, "Saved ${persisted.size} messages to session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to save history for session $sessionId", exception)
        }
    }

    /** 清空指定 session 的历史。 */
    override suspend fun clear(sessionId: String) {
        try {
            defaults.removeObjectForKey(key(sessionId))
            Logger.i(tag, "Cleared history for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to clear history for session $sessionId", exception)
        }
    }

    private fun key(sessionId: String) = "koog_memory_$sessionId"
}
