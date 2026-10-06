package com.mamba.picme.server.config

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.ServerSettings
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsServiceTest {

    @Test
    fun `load reads seeded rows into snapshot`() = runBlocking {
        TestDb.init(ServerSettings)
        transaction(Db.instance) {
            ServerSettings.insert {
                it[key] = SettingsService.KEY_FREE; it[value] = 555; it[updatedAt] = 1L
            }
            ServerSettings.insert {
                it[key] = SettingsService.KEY_GUEST; it[value] = 77; it[updatedAt] = 1L
            }
        }
        SettingsService.load()
        val snap = SettingsService.snapshot()
        assertEquals(555, snap.freeLlmQuota)
        assertEquals(77, snap.guestLlmQuota)
    }

    @Test
    fun `update writes both fields and refreshes snapshot`() = runBlocking {
        TestDb.init(ServerSettings)
        SettingsService.load() // 空 → 默认值
        val snap = SettingsService.update(free = 300, guest = 50, browserDailyQuota = 33)
        assertEquals(300, snap.freeLlmQuota)
        assertEquals(50, snap.guestLlmQuota)
        assertEquals(33, snap.browserDailyQuota)
        assertEquals(300, SettingsService.snapshot().freeLlmQuota) // 缓存已刷新

        transaction(Db.instance) {
            val rows = ServerSettings.selectAll().associate { it[ServerSettings.key] to it[ServerSettings.value] }
            assertEquals(300, rows[SettingsService.KEY_FREE])
            assertEquals(50, rows[SettingsService.KEY_GUEST])
            assertEquals(33, rows[SettingsService.KEY_BROWSER_DAILY])
        }
    }

    @Test
    fun `update with null leaves the other field untouched`() = runBlocking {
        TestDb.init(ServerSettings)
        SettingsService.update(free = 200, guest = 40, browserDailyQuota = 7)
        SettingsService.update(free = null, guest = 9)
        assertEquals(200, SettingsService.snapshot().freeLlmQuota)
        assertEquals(9, SettingsService.snapshot().guestLlmQuota)
        assertEquals(7, SettingsService.snapshot().browserDailyQuota)
    }
}
