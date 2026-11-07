package com.mamba.picme.agent.core.skill

/**
 * skill 机制契约（端云 LLM 可见面 SSOT，2026-11-08 端侧 load_skill 执行器落地）。
 *
 * skill = 按需加载的**指令包**（SKILL.md），回答「怎么做」；与 tool（回答「能做什么」）
 * 互补。Koog 1.3.0 无框架级 Skill 抽象，加载通路走 Koog 官方 Tool 扩展点——`load_skill`
 * 是普通工具，skill 正文以工具结果（Observation）进上下文，策略层零改动。
 *
 * 本对象是端云两个 runtime 的同一份字面量源（分层 runtime M1 收口范式的延伸）：
 * - 云端：server `LoadSkillToolService`（@Tool 注解实参引用本对象 const）+ `SkillCenter`
 *   的 prompt 清单段（经 [buildPromptSection] 生成）；
 * - 端侧：shared `ChatToolService.loadSkill`（同注解引用）+ `SkillRepository.promptSection`
 *   （同 [buildPromptSection]）。
 * Kotlin const 跨模块可作注解实参，两端工具名/描述/清单段逐字节一致，无双写漂移面。
 *
 * 版本语义：skill 的版本 = 服务端对 SKILL.md 原文的内容哈希（内容寻址，编辑即新版，
 * 无需手工维护版本号）；端侧按版本串比对决定重下载（见 SkillRepository）。
 */
object AgentSkillContract {

    /** 工具名（端云一致；与 CloudAgentToolCatalog 无涉——load_skill 不进端云桥目录）。 */
    const val TOOL_LOAD_SKILL = "load_skill"

    /** LLM 可见工具描述（端云逐字节一致）。 */
    const val DESC_LOAD_SKILL =
        "按名称加载技能（SKILL）的完整指令。可用技能清单在系统提示词中；" +
            "当用户请求与某技能描述匹配时先调用本工具获取指令，再严格按指令执行"

    /** name 参数描述（端云逐字节一致）。 */
    const val DESC_PARAM_SKILL_NAME = "技能名称，逐字取自系统提示词的可用技能清单，如 html-card"

    /** load_skill 正文上限（与 server SkillCenter.MAX_CONTENT_CHARS 同口径）。 */
    const val MAX_CONTENT_CHARS = 32_000

    /** 技能名闭集（与 server SkillCenter.NAME_REGEX 逐字同源——同一正则文本，防路径穿越第一道闸）。 */
    val NAME_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

    /**
     * load_skill 错误前缀（注意带尾空格——server LoadSkillToolService 的历史线上口径，
     * 与 CloudAgentToolContract.ERROR_PREFIX（"error:" 无空格）是两个字面量，勿混用）。
     */
    const val ERROR_PREFIX = "error: "

    /** unknown_skill 错误文本（端云同口径）。 */
    fun unknownSkill(name: String): String = "${ERROR_PREFIX}unknown_skill: $name"

    /**
     * system prompt 技能清单段（渐进披露：只注入 name + description，正文按需经 load_skill
     * 加载）。空清单 = 空串（prompt 零变化）。端云同一模板，server `SkillCenter.promptSection`
     * 与端侧 `SkillRepository.promptSection` 均委托本函数。
     */
    fun buildPromptSection(skills: List<Pair<String, String>>): String {
        if (skills.isEmpty()) return ""
        return buildString {
            append("\n可用技能（SKILL）清单——技能是按需加载的指令包，回答「怎么做」：")
            append("当用户请求与某技能的描述匹配时，先调用 load_skill 工具获取完整指令，")
            append("然后严格按指令执行；不匹配时禁止加载（正文占上下文，按需取用）：\n")
            skills.forEach { (name, description) -> append("- $name: $description\n") }
        }
    }
}
