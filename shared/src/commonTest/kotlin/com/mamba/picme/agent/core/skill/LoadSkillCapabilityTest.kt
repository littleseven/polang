package com.mamba.picme.agent.core.skill

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * LoadSkillCapability 降级不变式（与 WebSearchCapabilityTest 同构）：
 * 命中返回正文、未命中/异常映射 `error: unknown_skill` 文本交 LLM 降级，不穿透 ReAct 链。
 */
class LoadSkillCapabilityTest {

    private class FakeTransport(
        val skills: Map<String, String> = emptyMap(),
    ) : SkillTransport {
        override suspend fun fetchManifestJson(): String? = null
        override suspend fun fetchSkillJson(name: String): String? = skills[name]
    }

    private class FakeStore : SkillCacheStore {
        override suspend fun read(): String? = null
        override suspend fun write(snapshotJson: String) = Unit
    }

    private val context = AgentContext(scene = AgentScene.CHAT)

    private fun capability(skills: Map<String, String>): LoadSkillCapability {
        val transport = FakeTransport(skills)
        return LoadSkillCapability(SkillRepository(transport, FakeStore(), nowMs = { 0L }))
    }

    private fun skillJson(name: String, content: String) =
        """{"name":"$name","content":"$content","version":"v1"}"""

    @Test
    fun `known skill returns body as TextReply`() = runTest {
        val cap = capability(mapOf("html-card" to skillJson("html-card", "HTML 撰写规范正文")))
        val result = cap.execute(AgentCommand.LoadSkill(name = "html-card"), context, null)
        val action = result.getOrThrow()
        assertIs<AgentAction.TextReply>(action)
        assertEquals("HTML 撰写规范正文", action.message)
    }

    @Test
    fun `unknown skill maps to error text without throwing`() = runTest {
        val cap = capability(emptyMap())
        val result = cap.execute(AgentCommand.LoadSkill(name = "no-such"), context, null)
        val action = result.getOrThrow()
        assertIs<AgentAction.TextReply>(action)
        assertEquals("error: unknown_skill: no-such", action.message)
    }

    @Test
    fun `unsupported command type maps to METHOD_NOT_FOUND`() = runTest {
        val cap = capability(emptyMap())
        val result = cap.execute(AgentCommand.GetGallerySummary(), context, null)
        assertIs<AgentAction.Error>(result.getOrThrow())
    }

    @Test
    fun `capability advertises load_skill command`() {
        assertEquals(listOf("load_skill"), capability(emptyMap()).supportedCommands())
        assertEquals("load_skill", capability(emptyMap()).name)
    }
}
