package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountServiceTouchLastPlatformTest {

    private fun lastPlatformOf(email: String): String? = transaction(Db.instance) {
        Accounts.selectAll().where { Accounts.email eq email }.single()[Accounts.lastPlatform]
    }

    @Test
    fun `new account starts with null platform then touch sets it`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("p1@b.com", 100)
        assertNull(lastPlatformOf("p1@b.com"))

        AccountService.touchLastPlatform(AccountService.sha256(info.token), "android")
        assertEquals("android", lastPlatformOf("p1@b.com"))
    }

    @Test
    fun `touch updates when platform changes`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("p2@b.com", 100)
        val hash = AccountService.sha256(info.token)
        AccountService.touchLastPlatform(hash, "android")
        AccountService.touchLastPlatform(hash, "ios") // 换机/换端
        assertEquals("ios", lastPlatformOf("p2@b.com"))
    }

    @Test
    fun `touch on unknown hash is a no-op`() = runBlocking {
        TestDb.init(Accounts)
        AccountService.touchLastPlatform("deadbeef", "android") // 不抛、不建行
        assertEquals(0L, transaction(Db.instance) { Accounts.selectAll().count() })
    }
}
