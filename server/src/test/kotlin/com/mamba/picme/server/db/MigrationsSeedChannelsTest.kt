package com.mamba.picme.server.db

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.llm.ChannelInput
import com.mamba.picme.server.llm.ChannelRepository
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MigrationsSeedChannelsTest {

    private val config = AppConfig.load()

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
    }

    @Test
    fun `seedChannels creates 5 channels with one active`() = runBlocking {
        Migrations.seedChannels(config)
        val channels = ChannelRepository.list()
        assertEquals(5, channels.size)
        assertEquals(1, channels.count { it.isActive })
    }

    @Test
    fun `default active channel is Cloudflare when FORCE_PROVIDER unset`() = runBlocking {
        Migrations.seedChannels(config)
        val active = ChannelRepository.list().first { it.isActive }
        assertEquals("Cloudflare", active.name)
    }

    @Test
    fun `seedChannels is idempotent`() = runBlocking {
        Migrations.seedChannels(config)
        Migrations.seedChannels(config)
        assertEquals(5, ChannelRepository.list().size)
    }

    @Test
    fun `seedChannels seeds direct providers disabled`() = runBlocking {
        Migrations.seedChannels(config)
        val direct = ChannelRepository.list().filter { it.kind == "direct" }
        assertEquals(3, direct.size)
        assertTrue(direct.none { it.enabled })
    }

    @Test
    fun `seeded channels carry default_model`() = runBlocking {
        Migrations.seedChannels(config)
        val byName = ChannelRepository.list().associateBy { it.name }
        assertEquals("deepseek/deepseek-chat", byName["Cloudflare"]!!.defaultModel)
        assertEquals("deepseek-v4-flash-202605", byName["TokenHub"]!!.defaultModel)
        assertEquals("deepseek-v4-flash", byName["DeepSeek 直连"]!!.defaultModel)
        assertEquals("glm-5.4", byName["GLM 直连"]!!.defaultModel)
        assertEquals("kimi-k2.7-code", byName["Kimi 直连"]!!.defaultModel)
    }

    @Test
    fun `backfill populates blank default_model for known channels idempotently`() = runBlocking {
        // 模拟 prod 现状：老版本播种的渠道 default_model 为空
        ChannelRepository.create(
            ChannelInput("Cloudflare", "gateway", "https://x", "cf_aig", "", emptyMap(), true, ""),
        )
        Migrations.backfillDefaultModels()
        assertEquals(
            "deepseek/deepseek-chat",
            ChannelRepository.list().first { it.name == "Cloudflare" }.defaultModel,
        )
        // 再跑不变
        Migrations.backfillDefaultModels()
        assertEquals(
            "deepseek/deepseek-chat",
            ChannelRepository.list().first { it.name == "Cloudflare" }.defaultModel,
        )
    }
}
