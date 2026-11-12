package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO：聊天消息数据访问对象
 */
@Suppress("ComplexInterface") // Room DAO，方法数由表操作决定，不宜拆分
@Dao
interface ChatMessageDao {

    /**
     * 获取指定会话的所有消息，按时间升序排列
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<ChatMessageEntity>>

    /**
     * 获取指定会话的最近 N 条消息
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessageEntity>

    /**
     * 获取指定 ID 的消息
     */
    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getMessageById(id: String): ChatMessageEntity?

    /**
     * 跨会话获取所有任务卡消息（type = 'tool_task'），按提交时间倒序。
     * 任务中心页数据源：type 为 TEXT 列，纯查询新增无 schema 变更。
     * LIMIT 200：任务卡只增不删，全表失效重发时限制回扫量；进行中/历史 50 条判据只关心最近窗口。
     */
    @Query("SELECT * FROM chat_messages WHERE type = 'tool_task' ORDER BY timestamp DESC LIMIT 200")
    fun getTaskCardMessages(): Flow<List<ChatMessageEntity>>

    /**
     * recall_chat_history 工具取数（2026-11-12）：当前会话 `type='text'` 投影行 content
     * LIKE 关键词匹配（query 空串 = 不限），按时间倒序取最近 [limit] 条。
     * 只读投影列——投影列本就为此类消费而生（不变式 5 只禁绕过 parts 接缝**写**投影列）。
     * LIKE 通配符（%/_）随 query 原样透传，与 memory_facts 召回同口径。
     */
    @Query(
        """
        SELECT * FROM chat_messages
        WHERE sessionId = :sessionId
          AND type = 'text'
          AND (:query = '' OR content LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchTextMessages(sessionId: String, query: String, limit: Int): List<ChatMessageEntity>

    /**
     * 获取指定会话的最后一条消息
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForSession(sessionId: String): ChatMessageEntity?

    /**
     * 插入单条消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    /**
     * 批量插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    /**
     * 删除指定会话的最早 N 条消息（用于清理超限消息）
     */
    @Query("""
        DELETE FROM chat_messages 
        WHERE id IN (
            SELECT id FROM chat_messages 
            WHERE sessionId = :sessionId 
            ORDER BY timestamp ASC 
            LIMIT :count
        )
    """)
    suspend fun deleteOldestMessages(sessionId: String, count: Int)

    /**
     * 删除指定会话的所有消息
     */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteAllMessagesBySession(sessionId: String)

    /**
     * 获取指定会话的消息数量
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int

    /**
     * 获取所有 distinct sessionId，按最近消息时间倒序排列
     */
    @Query("SELECT DISTINCT sessionId FROM chat_messages ORDER BY timestamp DESC")
    fun getAllSessionIds(): Flow<List<String>>

    /** 快照导出：全量消息（备份用） */
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<ChatMessageEntity>
}

