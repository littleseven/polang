package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var messageDao: ChatMessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        messageDao = db.chatMessageDao()
    }

    @After
    fun teardown() = db.close()

    private fun message(
        id: String,
        sessionId: String = "s1",
        type: String,
        role: String = "agent",
        timestamp: Long,
    ) = ChatMessageEntity(id = id, sessionId = sessionId, type = type, role = role, content = "x", timestamp = timestamp)

    @Test
    fun `returns latest card within current user turn`() = runTest {
        messageDao.insertMessage(message("u1", type = "text", role = "user", timestamp = 100))
        messageDao.insertMessage(message("c1", type = "data_media_results", timestamp = 200))
        messageDao.insertMessage(message("c2", type = "data_media_results", timestamp = 300))

        val found = messageDao.getLatestMediaResultsSinceLastUserMessage("s1")
        assertEquals("c2", found?.id)
    }

    @Test
    fun `returns null when card predates last user message`() = runTest {
        messageDao.insertMessage(message("c1", type = "data_media_results", timestamp = 100))
        messageDao.insertMessage(message("u1", type = "text", role = "user", timestamp = 200))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `user image messages also mark turn boundary`() = runTest {
        messageDao.insertMessage(message("c1", type = "data_media_results", timestamp = 100))
        messageDao.insertMessage(message("u1", type = "image", role = "user", timestamp = 200))
        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))

        messageDao.insertMessage(message("c2", type = "data_media_results", timestamp = 300))
        messageDao.insertMessage(message("u2", type = "image", role = "user", timestamp = 400))
        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `agent rows never mark turn boundary`() = runTest {
        // 回合边界只认 role = 'user'：agent 行不推进边界，此前的卡片仍可命中
        messageDao.insertMessage(message("u1", type = "text", role = "user", timestamp = 100))
        messageDao.insertMessage(message("c1", type = "data_media_results", timestamp = 200))
        messageDao.insertMessage(message("a1", type = "text", role = "agent", timestamp = 300))

        assertEquals("c1", messageDao.getLatestMediaResultsSinceLastUserMessage("s1")?.id)
    }

    @Test
    fun `ignores cards from other sessions`() = runTest {
        messageDao.insertMessage(message("u1", sessionId = "s1", type = "text", role = "user", timestamp = 100))
        messageDao.insertMessage(message("c1", sessionId = "s2", type = "data_media_results", timestamp = 200))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `card at same millisecond as user message is excluded`() = runTest {
        // 回合边界为 timestamp 严格大于：同毫秒卡片视为上一回合，不参与替换
        messageDao.insertMessage(message("u1", type = "text", role = "user", timestamp = 100))
        messageDao.insertMessage(message("c1", type = "data_media_results", timestamp = 100))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `returns null when no user message exists yet`() = runTest {
        messageDao.insertMessage(message("c1", type = "data_media_results", timestamp = 100))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `getTaskCardMessages returns only tool_task rows newest first`() = runTest {
        messageDao.insertMessage(message("t1", type = "tool_task", timestamp = 100))
        messageDao.insertMessage(message("x1", type = "text", timestamp = 150))
        messageDao.insertMessage(message("t2", type = "tool_task", timestamp = 200))

        val cards = messageDao.getTaskCardMessages().first()
        assertEquals(listOf("t2", "t1"), cards.map { it.id })
    }
}
