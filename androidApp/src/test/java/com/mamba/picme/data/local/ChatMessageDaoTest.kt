package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

    private fun message(id: String, sessionId: String = "s1", type: String, timestamp: Long) =
        ChatMessageEntity(id = id, sessionId = sessionId, type = type, content = "x", timestamp = timestamp)

    @Test
    fun `returns latest card within current user turn`() = runTest {
        messageDao.insertMessage(message("u1", type = "user_text", timestamp = 100))
        messageDao.insertMessage(message("c1", type = "media_results", timestamp = 200))
        messageDao.insertMessage(message("c2", type = "media_results", timestamp = 300))

        val found = messageDao.getLatestMediaResultsSinceLastUserMessage("s1")
        assertEquals("c2", found?.id)
    }

    @Test
    fun `returns null when card predates last user message`() = runTest {
        messageDao.insertMessage(message("c1", type = "media_results", timestamp = 100))
        messageDao.insertMessage(message("u1", type = "user_text", timestamp = 200))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `user image messages also mark turn boundary`() = runTest {
        messageDao.insertMessage(message("c1", type = "media_results", timestamp = 100))
        messageDao.insertMessage(message("u1", type = "user_image", timestamp = 200))
        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))

        messageDao.insertMessage(message("c2", type = "media_results", timestamp = 300))
        messageDao.insertMessage(message("u2", type = "user_image_text", timestamp = 400))
        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `ignores cards from other sessions`() = runTest {
        messageDao.insertMessage(message("u1", sessionId = "s1", type = "user_text", timestamp = 100))
        messageDao.insertMessage(message("c1", sessionId = "s2", type = "media_results", timestamp = 200))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `card at same millisecond as user message is excluded`() = runTest {
        // 回合边界为 timestamp 严格大于：同毫秒卡片视为上一回合，不参与替换
        messageDao.insertMessage(message("u1", type = "user_text", timestamp = 100))
        messageDao.insertMessage(message("c1", type = "media_results", timestamp = 100))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }

    @Test
    fun `returns null when no user message exists yet`() = runTest {
        messageDao.insertMessage(message("c1", type = "media_results", timestamp = 100))

        assertNull(messageDao.getLatestMediaResultsSinceLastUserMessage("s1"))
    }
}
