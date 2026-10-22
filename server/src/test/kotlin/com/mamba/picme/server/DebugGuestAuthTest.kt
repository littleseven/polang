package com.mamba.picme.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * debug 包访客身份合成（免登录双闸）：稳定 hash + 前缀可区分 + 与真实账号 token_hash 不撞。
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
    fun `hash carries debug guest prefix and is not raw account token hash`() {
        val hash = debugGuestTokenHash("dev-1")
        assertTrue(hash.startsWith("debug-guest:"))
        // 真实账号 token_hash = 64 hex（无冒号前缀），访客身份绝不与之同形
        assertEquals(64, hash.removePrefix("debug-guest:").length)
    }
}
