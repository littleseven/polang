package com.mamba.picme.agent.core.skill

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 技能清单条目（端侧缓存 + prompt 披露层）。
 *
 * @property version 内容版本 = 服务端对 SKILL.md 原文的内容哈希（内容寻址，编辑即新版）。
 *   端侧只比对不计算；空串 = 旧服务端未下发版本（每次同步恒重下载，退化但不错）。
 */
@Serializable
data class SkillMeta(
    val name: String,
    val description: String,
    val version: String = "",
)

/** 单技能缓存条目：清单元信息 + 正文（frontmatter 之后的指令部分，已截断）。 */
@Serializable
data class CachedSkill(
    val meta: SkillMeta,
    val body: String,
)

/** 本地缓存快照（SkillCacheStore 持久化单元，JSON 线格式）。 */
@Serializable
data class SkillCacheSnapshot(
    val skills: List<CachedSkill> = emptyList(),
    /** 最近一次成功全量同步的时刻（epoch ms；0 = 从未成功同步）。 */
    val syncedAtMs: Long = 0L,
)

@Serializable
private class ManifestDto(val skills: List<SkillMeta> = emptyList())

@Serializable
private class SkillContentDto(val name: String = "", val content: String = "", val version: String = "")

/** skill 线格式编解码（服务端 JSON → 模型；快照 → 持久化 JSON）。解析失败一律 null，调用方回落缓存。 */
object SkillCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 `GET /v1/skills` 响应（{"skills":[{name,description,version}]}）。 */
    fun parseManifest(jsonText: String): List<SkillMeta>? =
        runCatching { json.decodeFromString<ManifestDto>(jsonText).skills }
            .getOrNull()
            ?.filter { AgentSkillContract.NAME_REGEX.matches(it.name) && it.description.isNotBlank() }

    /** 解析 `GET /v1/skills/{name}` 响应（{"name","content","version"}）。 */
    fun parseSkillContent(jsonText: String): Pair<SkillMeta, String>? =
        runCatching { json.decodeFromString<SkillContentDto>(jsonText) }
            .getOrNull()
            ?.takeIf { AgentSkillContract.NAME_REGEX.matches(it.name) && it.content.isNotBlank() }
            ?.let { SkillMeta(it.name, "", it.version) to it.content }

    fun encodeSnapshot(snapshot: SkillCacheSnapshot): String = json.encodeToString(snapshot)

    fun decodeSnapshot(jsonText: String): SkillCacheSnapshot? =
        runCatching { json.decodeFromString<SkillCacheSnapshot>(jsonText) }.getOrNull()
}
