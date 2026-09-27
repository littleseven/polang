package com.mamba.picme.agent.core.platform.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.inference.remote.koog.KoogMessageMemory
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider
import com.mamba.picme.agent.core.platform.thread.SharedDispatcherProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// 同一 chat_memory DataStore 文件（与 langchain4j 期 DataStoreChatMemoryStore 共享进程级单例），
// 但用独立键前缀 koog_memory_ 隔离：Koog Message 是 kotlinx-serializable 的多态结构，
// 与 langchain4j 手写 JSON 不兼容，混用同一键会在迁移重叠期互相覆盖/解析失败。
private val Context.koogChatMemoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_memory")

/**
 * Koog 版对话历史持久化（原 :agent-core 已删除，Koog 接管）。
 *
 * 把 Koog [Message] 列表序列化到 Android DataStore，复用 [KoogMessageMemory] 的三不变式
 * （SystemMessage 不落盘 / tool_call 块原子裁剪 / 双向配对剔除悬空）。
 *
 * - **持久化键**：`koog_memory_$sessionId`（与旧 langchain4j 路径的 `memory_$sessionId` 同文件不同键，
 *   迁移重叠期互不干扰；切到 Koog 后历史从空开始，符合一次性迁移预期）。
 * - **线程模型**：与 [MemoryManager] 一致，经 [DispatcherProvider.dataStoreDispatcher] 串行执行，
 *   与本地推理/网络请求隔离。
 * - **序列化**：Koog [Message] 是 `@Serializable` 的密封接口，直接用 kotlinx-serialization 多态
 *   编解码；编解码逻辑抽到独立的 [encodeKoogMessages] / [decodeKoogMessages] 顶层函数，便于纯 JVM 单测。
 *
 * 本类在 Phase 3 仅作为记忆层组件落地（旧 langchain4j 路径仍活、不被调用）；
 * 由 chat 链路（Phase 4）按 run 模型接 load/save。
 */
public class KoogMessageMemoryStore(
    private val context: Context,
    dispatcherProvider: DispatcherProvider = SharedDispatcherProvider.instance,
) : ChatMemoryStore {

    private val tag = "KoogMessageMemoryStore"
    private val dataStore = context.koogChatMemoryDataStore
    private val dataStoreDispatcher = dispatcherProvider.dataStoreDispatcher

    /** 加载指定 session 的历史：解码 → 剔除 System（不变式①）→ 双向配对 sanitize（不变式③）。 */
    override suspend fun load(sessionId: String): List<Message> = withContext(dataStoreDispatcher) {
        return@withContext try {
            val key = stringPreferencesKey("koog_memory_$sessionId")
            val raw = withTimeout(TIMEOUT_MS) {
                dataStore.data.map { prefs -> prefs[key] }.first()
            } ?: return@withContext emptyList()

            KoogMessageMemory.sanitizeToolPairing(
                KoogMessageMemory.withoutSystemMessages(decodeKoogMessages(raw))
            )
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout loading history for session $sessionId")
            emptyList()
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to load history for session $sessionId", exception)
            emptyList()
        }
    }

    /** 保存指定 session 的历史：M1 组装（剔 System① → 老化旧 tool result → token 预算裁剪 → 条数上限兜底②）→ 编码落盘。 */
    override suspend fun save(sessionId: String, messages: List<Message>) = withContext(dataStoreDispatcher) {
        try {
            val key = stringPreferencesKey("koog_memory_$sessionId")
            val persisted = KoogMessageMemory.assembleForPersistence(messages)
            val raw = encodeKoogMessages(persisted)
            withTimeout(TIMEOUT_MS) {
                dataStore.edit { prefs -> prefs[key] = raw }
            }
            Logger.d(tag, "Saved ${persisted.size} messages to session $sessionId")
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout saving history for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to save history for session $sessionId", exception)
        }
    }

    /** 清空指定 session 的历史。 */
    override suspend fun clear(sessionId: String) = withContext(dataStoreDispatcher) {
        try {
            val key = stringPreferencesKey("koog_memory_$sessionId")
            withTimeout(TIMEOUT_MS) {
                dataStore.edit { prefs -> prefs.remove(key) }
            }
            Logger.i(tag, "Cleared history for session $sessionId")
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout clearing history for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to clear history for session $sessionId", exception)
        }
    }

    private companion object {
        private const val TIMEOUT_MS: Long = 5000L
    }
}
