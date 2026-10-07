package com.mamba.picme.server.db

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationsSettingsTest {

    @Test
    fun `seedSettings writes env defaults when rows absent`() = runBlocking {
        TestDb.init(ServerSettings)
        val config = AppConfig.load()
        Migrations.seedSettings(config)

        transaction(Db.instance) {
            val rows = ServerSettings.selectAll().associate { it[ServerSettings.key] to it[ServerSettings.value] }
            assertEquals(config.freeLlmQuota, rows[SettingsService.KEY_FREE])
            assertEquals(config.guestLlmQuota, rows[SettingsService.KEY_GUEST])
            assertEquals(config.browserDailyQuota, rows[SettingsService.KEY_BROWSER_DAILY])
        }
    }

    @Test
    fun `seedSettings is idempotent when rows present`() = runBlocking {
        TestDb.init(ServerSettings)
        val config = AppConfig.load()
        Migrations.seedSettings(config)
        Migrations.seedSettings(config)
        transaction(Db.instance) {
            val count = ServerSettings.selectAll().count()
            assertEquals(3L, count)
        }
    }
}
