package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.MessagePartsCodec
import com.mamba.picme.domain.chat.PartState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    ) = ChatMessageEntity(
        id = id, sessionId = sessionId, type = type, role = role, content = "x", timestamp = timestamp,
        partsJson = MessagePartsCodec.encode(listOf(MessagePart.Text("p0", "x", PartState.DONE))),
    )

    @Test
    fun `getTaskCardMessages returns only tool_task rows newest first`() = runTest {
        messageDao.insertMessage(message("t1", type = "tool_task", timestamp = 100))
        messageDao.insertMessage(message("x1", type = "text", timestamp = 150))
        messageDao.insertMessage(message("t2", type = "tool_task", timestamp = 200))

        val cards = messageDao.getTaskCardMessages().first()
        assertEquals(listOf("t2", "t1"), cards.map { it.id })
    }
}
