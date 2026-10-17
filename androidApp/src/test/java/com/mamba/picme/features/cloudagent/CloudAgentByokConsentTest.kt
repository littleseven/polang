package com.mamba.picme.features.cloudagent

import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentByokConsentTest {

    private val byokConfig = RemoteModelConfig(
        modelId = "kimi-k2.6",
        providerId = "kimi-official",
        apiKey = "sk-user-key",
        baseUrl = "https://api.moonshot.cn/v1/",
    )

    @Test
    fun `no user config selects gateway regardless of consent`() {
        assertEquals(CloudAgentChannelChoice.Gateway, CloudAgentChannelResolver.resolve(null, consent = false))
        assertEquals(CloudAgentChannelChoice.Gateway, CloudAgentChannelResolver.resolve(null, consent = true))
    }

    @Test
    fun `config without apiKey selects gateway`() {
        val noKey = byokConfig.copy(apiKey = "")
        assertEquals(CloudAgentChannelChoice.Gateway, CloudAgentChannelResolver.resolve(noKey, consent = false))
    }

    @Test
    fun `byok config with consent selects byok`() {
        val choice = CloudAgentChannelResolver.resolve(byokConfig, consent = true)
        assertTrue(choice is CloudAgentChannelChoice.Byok)
        val byok = choice as CloudAgentChannelChoice.Byok
        assertEquals("kimi-k2.6", byok.model)
        assertEquals("https://api.moonshot.cn/v1/", byok.baseUrl)
        assertEquals("sk-user-key", byok.apiKey)
    }

    @Test
    fun `byok config without consent requires consent dialog`() {
        assertEquals(CloudAgentChannelChoice.NeedConsent, CloudAgentChannelResolver.resolve(byokConfig, consent = false))
    }
}
