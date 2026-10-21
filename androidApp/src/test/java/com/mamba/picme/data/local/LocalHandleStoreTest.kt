package com.mamba.picme.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHandleStoreTest {

    private class FakeDao : LocalHandleDao {
        val rows = mutableMapOf<String, LocalHandleEntity>()
        override suspend fun upsert(handle: LocalHandleEntity) {
            rows[handle.handleId] = handle
        }
        override suspend fun byId(handleId: String): LocalHandleEntity? = rows[handleId]
        override suspend fun deleteOlderThan(beforeMs: Long): Int {
            val doomed = rows.values.filter { it.createdAt < beforeMs }.map { it.handleId }
            doomed.forEach { rows.remove(it) }
            return doomed.size
        }
    }

    @Test
    fun putThenPayloadOf_roundtrips() = runTest {
        val store = LocalHandleStore(FakeDao())
        val id = store.put("scan", "[{\"id\":1,\"captureDate\":1782000000000}]")
        assertEquals(12, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(
            "[{\"id\":1,\"captureDate\":1782000000000}]",
            store.payloadOf("scan", id),
        )
    }

    @Test
    fun payloadOf_wrongDomainOrUnknownId_returnsNull() = runTest {
        val store = LocalHandleStore(FakeDao())
        val id = store.put("scan", "[]")
        assertNull(store.payloadOf("faces", id))
        assertNull(store.payloadOf("scan", "ffffffffffff"))
    }
}
