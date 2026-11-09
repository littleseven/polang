package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: ChatSessionDao
    private lateinit var messageDao: ChatMessageDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
        sessionDao = db.chatSessionDao()
        messageDao = db.chatMessageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndRetrieveSession() = runBlocking {
        val session = ChatSessionEntity(sessionId = "s1", title = "Test Chat")
        sessionDao.insertSession(session)

        val loaded = sessionDao.getSession("s1")
        assertEquals("Test Chat", loaded?.title)
    }

    @Test
    fun updateTitleReflectedInFlow() = runBlocking {
        sessionDao.insertSession(ChatSessionEntity(sessionId = "s1", title = "Old"))
        sessionDao.updateTitle("s1", "New")

        val sessions = sessionDao.getAllSessions().first()
        assertEquals("New", sessions.first().title)
    }

    @Test
    fun deleteSessionRemovesOnlyTargetSession() = runBlocking {
        sessionDao.insertSession(ChatSessionEntity(sessionId = "s1", title = "A"))
        sessionDao.insertSession(ChatSessionEntity(sessionId = "s2", title = "B"))
        messageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = "s1",
                type = "user_text",
                content = "hello",
                partsJson = MessagePartsCodec.encode(listOf(MessagePart.Text("p0", "hello")))
            )
        )

        messageDao.deleteAllMessagesBySession("s1")
        sessionDao.deleteSession("s1")

        assertNull(sessionDao.getSession("s1"))
        assertEquals("B", sessionDao.getSession("s2")?.title)
    }

    @Test
    fun getLastMessageForSessionReturnsMostRecent() = runBlocking {
        val sessionId = "s1"
        messageDao.insertMessage(
            ChatMessageEntity(
                id = "m1",
                sessionId = sessionId,
                type = "user_text",
                content = "first",
                timestamp = 1000,
                partsJson = MessagePartsCodec.encode(listOf(MessagePart.Text("p0", "first")))
            )
        )
        messageDao.insertMessage(
            ChatMessageEntity(
                id = "m2",
                sessionId = sessionId,
                type = "agent_text",
                content = "last",
                timestamp = 2000,
                partsJson = MessagePartsCodec.encode(listOf(MessagePart.Text("p0", "last")))
            )
        )

        val last = messageDao.getLastMessageForSession(sessionId)
        assertEquals("last", last?.content)
    }
}
