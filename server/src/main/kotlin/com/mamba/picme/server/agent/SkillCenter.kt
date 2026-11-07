package com.mamba.picme.server.agent

import java.io.File

/**
 * Skill 中心（spike：2026-11-07，云端 Skill 热更新实验线）。
 *
 * 定位：skill = 按需加载的**指令包**（SKILL.md），回答「怎么做」；与 tool（回答
 * 「能做什么」）互补。Koog 1.3.0 无框架级 Skill 抽象（全部 artifact 零 skill 类），
 * 故加载通路走 Koog 官方扩展点——`load_skill` 注册为普通 Koog Tool（见 SkillTools.kt），
 * skill 正文以工具结果（Observation）进上下文，清单段注入 system prompt。
 *
 * 存储：`rootDir/<skill-name>/SKILL.md`，磁盘直读（不打包进 jar）——改 skill 资产
 * 无需重新部署，rsync 到 VPS 即热更新（spike 期每次调用重新扫盘，量小零缓存）。
 *
 * SKILL.md 格式（对齐 Anthropic Agent Skills / Claude Code 社区约定）：
 * ```
 * ---
 * name: html-card           # 必须与目录名一致
 * description: 一句话……     # LLM 判断相关性的唯一依据（清单注入）
 * ---
 * 正文（markdown 指令）
 * ```
 *
 * frontmatter 非法（缺 --- 块/缺 name/缺 description/name 与目录名不一致）的目录
 * 整体跳过，不进清单、不可加载——宁缺毋滥，坏资产不暴露给 LLM。
 */
class SkillCenter(
    private val rootDir: File,
) {

    data class SkillMeta(val name: String, val description: String)

    /** 当前可用技能清单（每次重新扫盘 = 热更新即时生效）。 */
    fun manifest(): List<SkillMeta> {
        val dirs = rootDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val file = File(dir, SKILL_FILE).takeIf { it.isFile } ?: return@mapNotNull null
            parseFrontmatter(file.readText(), expectedName = dir.name)
        }.sortedBy { it.name }
    }

    /**
     * 加载技能正文（frontmatter 之后的 markdown 指令部分）。
     * @return 正文（超 [MAX_CONTENT_CHARS] 截断）；技能不存在/非法返回 null
     */
    fun content(name: String): String? {
        if (!NAME_REGEX.matches(name)) return null
        val dir = File(rootDir, name)
        // 路径穿越防御：正则已限死字符集，canonical 校验双保险
        if (dir.canonicalFile.parentFile != rootDir.canonicalFile) return null
        val file = File(dir, SKILL_FILE).takeIf { it.isFile } ?: return null
        val raw = file.readText()
        val meta = parseFrontmatter(raw, expectedName = name) ?: return null
        val body = raw.substringAfter(FRONTMATTER_END, missingDelimiterValue = "").trim()
        if (body.isEmpty()) return null
        return if (body.length > MAX_CONTENT_CHARS) {
            body.take(MAX_CONTENT_CHARS) + "\n\n[truncated: skill ${meta.name} exceeds $MAX_CONTENT_CHARS chars]"
        } else {
            body
        }
    }

    /**
     * system prompt 技能清单段（空清单 = 空串，prompt 零变化）。
     * 只注入 name + description（渐进披露：正文按需经 load_skill 加载，不全文常驻）。
     */
    fun promptSection(): String {
        val skills = manifest()
        if (skills.isEmpty()) return ""
        return buildString {
            append("\n可用技能（SKILL）清单——技能是按需加载的指令包，回答「怎么做」：")
            append("当用户请求与某技能的描述匹配时，先调用 load_skill 工具获取完整指令，")
            append("然后严格按指令执行；不匹配时禁止加载（正文占上下文，按需取用）：\n")
            skills.forEach { append("- ${it.name}: ${it.description}\n") }
        }
    }

    private fun parseFrontmatter(raw: String, expectedName: String): SkillMeta? {
        if (!raw.startsWith(FRONTMATTER_START)) return null
        val end = raw.indexOf(FRONTMATTER_END, startIndex = FRONTMATTER_START.length)
        if (end < 0) return null
        val header = raw.substring(FRONTMATTER_START.length, end)
        val fields = header.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
        val name = fields["name"]?.takeIf { it == expectedName && NAME_REGEX.matches(it) } ?: return null
        val description = fields["description"]?.takeIf { it.isNotBlank() } ?: return null
        return SkillMeta(name, description)
    }

    companion object {
        const val SKILL_FILE = "SKILL.md"

        /** load_skill 结果上限（对齐 CloudAgentToolContract 32_000 字符截断口径）。 */
        const val MAX_CONTENT_CHARS = 32_000

        /** 技能名闭集：小写字母/数字/连字符（与目录名一一对应，防路径穿越第一道闸）。 */
        val NAME_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        private const val FRONTMATTER_START = "---\n"
        private const val FRONTMATTER_END = "\n---"

        /** 技能根目录：env POLANG_SKILLS_DIR，缺省工作目录下 `skills/`。 */
        fun fromEnv(): SkillCenter =
            SkillCenter(File(System.getenv("POLANG_SKILLS_DIR")?.takeIf { it.isNotBlank() } ?: "skills"))
    }
}
