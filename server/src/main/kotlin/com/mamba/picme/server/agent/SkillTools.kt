package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import com.mamba.picme.agent.core.skill.AgentSkillContract

/**
 * skill 加载工具（spike：2026-11-07）——Koog 无框架级 Skill 抽象，skill 加载走
 * Koog 官方 Tool 扩展点（与 server_time 同一 annotation 范式：@Tool + @LLMDescription
 * + asToolsByClass reflect 展开）。skill 正文作为工具结果（Observation）进上下文，
 * 走标准 ReAct 循环，AgentRunner/策略层零改动。
 *
 * 云端执行语义：server 即 skill 资产的宿主，直读 [SkillCenter] 磁盘，无网络跳数。
 * 端侧 load_skill 已落地（2026-11-08）：shared `SkillRepository` 经 HTTP 拉取
 * /v1/skills/{name}，与本工具同名同契约（工具名/描述/error 前缀均收口
 * [AgentSkillContract]），端云 LLM 可见面一致。
 */
class LoadSkillToolService(
    private val center: SkillCenter,
) {

    @Tool(customName = AgentSkillContract.TOOL_LOAD_SKILL)
    @LLMDescription(AgentSkillContract.DESC_LOAD_SKILL)
    suspend fun loadSkill(
        @LLMDescription(AgentSkillContract.DESC_PARAM_SKILL_NAME)
        name: String,
    ): String {
        val content = center.content(name.trim())
            ?: return AgentSkillContract.unknownSkill(name)
        return content
    }
}
