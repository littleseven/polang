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
     * 跨会话获取所有任务卡消息（type = 'task_card'），按提交时间倒序。
     * 任务中心页数据源：type 为 TEXT 列，纯查询新增无 schema 变更。
     * LIMIT 200：任务卡只增不删，全表失效重发时限制回扫量；进行中/历史 50 条判据只关心最近窗口。
     */
    @Query("SELECT * FROM chat_messages WHERE type = 'task_card' ORDER BY timestamp DESC LIMIT 200")
    fun getTaskCardMessages(): Flow<List<ChatMessageEntity>>

    /**
     * 获取指定会话的最后一条消息
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForSession(sessionId: String): ChatMessageEntity?

    /**
     * 获取当前用户回合（最近一条 user_* 消息之后）最新的一张搜索结果卡片。
     * 用于 ReAct 多轮搜索 / 回退直搜时的卡片替换去重。
     */
    @Query("""
        SELECT * FROM chat_messages
        WHERE sessionId = :sessionId AND type = 'media_results'
          AND timestamp > (
              SELECT MAX(timestamp) FROM chat_messages
              WHERE sessionId = :sessionId AND type LIKE 'user\_%' ESCAPE '\'
          )
        ORDER BY timestamp DESC LIMIT 1
    """)
    suspend fun getLatestMediaResultsSinceLastUserMessage(sessionId: String): ChatMessageEntity?

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

