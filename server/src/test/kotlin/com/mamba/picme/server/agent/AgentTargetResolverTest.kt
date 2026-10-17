package com.mamba.picme.server.agent

import com.mamba.picme.server.llm.AuthStyle
import com.mamba.picme.server.llm.ChannelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AgentTargetResolver.splitEndpoint 映射钉死（2026-10-17 真机遛狗实踩回归防线）：
 * 渠道/用户配置里的完整 endpoint 必须拆成「源点 + chatCompletionsPath」两段，
 * 否则 Koog 默认 path 自带 v1/ 前缀会拼出 /v1/v1/chat/completions（上游 404 空 body）。
 */
class AgentTargetResolverTest {

    @Test
    fun `splitEndpoint maps full endpoint to origin plus default path`() {
        assertEquals(
            "https://api.deepseek.com" to "v1/chat/completions",
            AgentTargetResolver.splitEndpoint("https://api.deepseek.com/v1/chat/completions"),
        )
    }

    @Test
    fun `splitEndpoint maps version root to origin plus default path`() {
        assertEquals(
            "https://api.deepseek.com" to "v1/chat/completions",
            AgentTargetResolver.splitEndpoint("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com" to "v1/chat/completions",
            AgentTargetResolver.splitEndpoint("https://api.deepseek.com/v1/"),
        )
    }

    @Test
    fun `splitEndpoint maps bare origin to default path`() {
        assertEquals(
            "https://api.deepseek.com" to "v1/chat/completions",
            AgentTargetResolver.splitEndpoint("https://api.deepseek.com"),
        )
    }

    @Test
    fun `splitEndpoint keeps custom gateway prefix path`() {
        assertEquals(
            "https://gateway.ai.cloudflare.com" to "v1/abc123/picme/compat/chat/completions",
            AgentTargetResolver.splitEndpoint("https://gateway.ai.cloudflare.com/v1/abc123/picme/compat/chat/completions"),
        )
        assertEquals(
            "https://open.bigmodel.cn" to "api/paas/v4/chat/completions",
            AgentTargetResolver.splitEndpoint("https://open.bigmodel.cn/api/paas/v4/chat/completions"),
        )
    }

    @Test
    fun `fromChannel produces Koog-ready origin and path`() {
        val channel = ChannelConfig(
            3, "DeepSeek 直连", "direct", "https://api.deepseek.com/v1/chat/completions",
            AuthStyle.BEARER, "tok-abc",
            emptyMap(), "deepseek-v4-flash",
        )
        val target = AgentTargetResolver.fromChannel(channel, "any-model")!!
        assertEquals("https://api.deepseek.com", target.baseUrl)
        assertEquals("v1/chat/completions", target.chatCompletionsPath)
        assertEquals("deepseek-v4-flash", target.model)
    }

    @Test
    fun `byok produces Koog-ready origin and path`() {
        val target = AgentTargetResolver.byok("user-key", "https://api.moonshot.cn/v1", "kimi-k2.7")
        assertEquals("https://api.moonshot.cn", target.baseUrl)
        assertEquals("v1/chat/completions", target.chatCompletionsPath)
    }
}
