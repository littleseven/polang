package com.mamba.picme.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * debug 包访客身份合成（免登录双闸）：稳定 hash + 64 hex 同形 + 输入域隔离零碰撞。
 */
class DebugGuestAuthTest {

    @Test
    fun `same device yields same hash`() {
        assertEquals(debugGuestTokenHash("dev-1"), debugGuestTokenHash("dev-1"))
    }

    @Test
    fun `different devices yield different hashes`() {
        assertNotEquals(debugGuestTokenHash("dev-1"), debugGuestTokenHash("dev-2"))
    }

    @Test
    fun `hash is 64 hex fitting agent session token hash column`() {
        val hash = debugGuestTokenHash("dev-1")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
