package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

/**
 * skill 加载工具（spike：2026-11-07）——Koog 无框架级 Skill 抽象，skill 加载走
 * Koog 官方 Tool 扩展点（与 server_time 同一 annotation 范式：@Tool + @LLMDescription
 * + asToolsByClass reflect 展开）。skill 正文作为工具结果（Observation）进上下文，
 * 走标准 ReAct 循环，AgentRunner/策略层零改动。
 *
 * 云端执行语义：server 即 skill 资产的宿主，直读 [SkillCenter] 磁盘，无网络跳数。
 * 端侧 Agent 的 load_skill（HTTP 拉取 /v1/skills/{name}）为后续里程碑，与本工具
 * 同名同契约（工具名/`error:` 前缀），端云 LLM 可见面一致。
 */
class LoadSkillToolService(
    private val center: SkillCenter,
) {

    @Tool(customName = "load_skill")
    @LLMDescription(
        "按名称加载技能（SKILL）的完整指令。可用技能清单在系统提示词中；" +
            "当用户请求与某技能描述匹配时先调用本工具获取指令，再严格按指令执行",
    )
    suspend fun loadSkill(
        @LLMDescription("技能名称，逐字取自系统提示词的可用技能清单，如 html-card")
        name: String,
    ): String {
        val content = center.content(name.trim())
            ?: return ERROR_PREFIX + "unknown_skill: $name"
        return content
    }

    companion object {
        /** 与 CloudAgentToolContract.ERROR_PREFIX 同口径（"error: "）。 */
        const val ERROR_PREFIX = "error: "
    }
}
