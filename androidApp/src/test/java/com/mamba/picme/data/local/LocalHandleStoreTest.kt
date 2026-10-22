package com.mamba.picme.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHandleStoreTest {

    @Test
    fun putThenPayloadOf_roundtrips() = runTest {
        val store = LocalHandleStore(FakeLocalHandleDao())
        val id = store.put("scan", "[{\"id\":1,\"captureDate\":1782000000000}]")
        assertEquals(12, id.length)
        assertTrue(id.all { ch -> ch in '0'..'9' || ch in 'a'..'f' })
        assertEquals(
            "[{\"id\":1,\"captureDate\":1782000000000}]",
            store.payloadOf("scan", id),
        )
    }

    @Test
    fun payloadOf_wrongDomainOrUnknownId_returnsNull() = runTest {
        val store = LocalHandleStore(FakeLocalHandleDao())
        val id = store.put("scan", "[]")
        assertNull(store.payloadOf("faces", id))
        // 保证不存在的 id：由 put 返回值翻转首字符（保持 12-hex 形态），避免硬编码理论碰撞
        val unknownId = (if (id.first() == '0') '1' else '0') + id.substring(1)
        assertNull(store.payloadOf("scan", unknownId))
    }
}
