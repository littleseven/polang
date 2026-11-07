package com.mamba.picme.agent.core.skill

import com.mamba.picme.agent.core.platform.logging.Logger
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 端侧 skill 资产仓库（2026-11-08，端侧 load_skill 执行器的资产层）。
 *
 * 职责：自 picme-server `/v1/skills` 同步技能资产到本地缓存，向 chat runtime 提供
 * 清单披露段（[promptSection]）与正文加载（[content]）。与云端 `SkillCenter`（磁盘直读、
 * 零网络跳数）同契约不同落点——分层 runtime「同一资产源、runtime 落点不同」的 skill 层兑现。
 *
 * **版本与更新策略**（版本 = 服务端内容哈希，编辑即新版）：
 * - 全量同步 [syncIfStale]：TTL（默认 30min）到期才拉清单；逐技能按 version 比对，
 *   一致复用本地正文（零下载），不一致/缺失重拉正文，清单中消失的技能从缓存剔除；
 * - stale-while-revalidate：任何网络/解析失败保留旧缓存照常服务，同步失败节流
 *   （[RETRY_INTERVAL_MS]）防重复打爆；
 * - 单点穿透 [content]：缓存未命中（新上线技能/尚未同步）时单拉 `/v1/skills/{name}`
 *   并入缓存，LLM 在清单刷新前即可加载新技能。
 *
 * **并发**：全部可变状态经 [mutex] 串行化；同步与正文加载都是 suspend，
 * 调用方（RemoteChatEngine）以短超时包裹，慢网不阻塞 chat 主链路。
 *
 * **截断**：正文截断（32K）由服务端统一执行，端侧信任其口径不再二次截断
 * （避免截断后缀叠加）。
 */
class SkillRepository(
    private val transport: SkillTransport,
    private val store: SkillCacheStore,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val syncTtlMs: Long = DEFAULT_SYNC_TTL_MS,
) {

    private val tag = "SkillRepository"
    private val mutex = Mutex()

    /** 内存缓存（null = 尚未自 store 加载）；全部读写经 [mutex]。 */
    private var memoryCache: SkillCacheSnapshot? = null

    /** 最近一次同步尝试时刻（成功或失败都记，失败节流防打爆）。 */
    private var lastSyncAttemptMs: Long = 0L

    // ── 披露层（prompt 注入） ─────────────────────────────────────

    /**
     * system prompt 技能清单段（挂起版，自 store 加载后生成）。
     * 引擎在每轮跑 agent 前调用（syncIfStale 之后）；空清单/未同步 = 空串（prompt 零变化）。
     */
    suspend fun promptSection(): String = mutex.withLock {
        ensureLoadedLocked()
        buildSectionLocked()
    }

    /**
     * system prompt 技能清单段（非挂起版，仅读内存缓存）。
     * 供 KoogChatAgent 的 AIAgent 重建键同步求值——该路径无法 suspend；
     * 语义 = 「上次同步完成时的清单」，首次同步完成前恒空串（技能段随重建键变化自然补入）。
     */
    fun cachedPromptSection(): String {
        val snapshot = memoryCache ?: return ""
        return AgentSkillContract.buildPromptSection(
            snapshot.skills.map { it.meta.name to it.meta.description }.sortedBy { it.first },
        )
    }

    // ── 正文加载（load_skill 执行） ──────────────────────────────

    /**
     * 加载技能正文（缓存优先，未命中单点穿透网络并写缓存）。
     * @return 正文；技能不存在/拉取失败返回 null（调用方映射 `error: unknown_skill`）
     */
    suspend fun content(name: String): String? {
        val trimmed = name.trim()
        if (!AgentSkillContract.NAME_REGEX.matches(trimmed)) return null
        mutex.withLock {
            ensureLoadedLocked()
            memoryCache?.skills?.firstOrNull { it.meta.name == trimmed }?.let { return it.body }
        }
        // 缓存未命中：先尝试一次全量同步（TTL 内/失败节流期会立即返回），再单点穿透
        syncIfStale()
        mutex.withLock {
            memoryCache?.skills?.firstOrNull { it.meta.name == trimmed }?.let { return it.body }
        }
        val fetched = transport.fetchSkillJson(trimmed)?.let { SkillCodec.parseSkillContent(it) } ?: return null
        val (meta, body) = fetched
        mutex.withLock {
            val current = memoryCache ?: SkillCacheSnapshot()
            // 清单里已有的描述信息优先保留（单拉响应不含 description）
            val description = current.skills.firstOrNull { it.meta.name == trimmed }?.meta?.description ?: meta.description
            val entry = CachedSkill(SkillMeta(trimmed, description, meta.version), body)
            val updated = current.copy(
                skills = current.skills.filter { it.meta.name != trimmed } + entry,
            )
            memoryCache = updated
            persistLocked(updated)
        }
        return body
    }

    // ── 同步 ────────────────────────────────────────────────────

    /**
     * TTL 到期的增量全量同步（[force] 跳过 TTL 与失败节流）。失败保留旧缓存（stale）。
     * 调用方建议以短超时（秒级）包裹——慢网下放弃本次同步，下轮再试。
     */
    suspend fun syncIfStale(force: Boolean = false) {
        val now = nowMs()
        mutex.withLock {
            ensureLoadedLocked()
            val snapshot = memoryCache ?: SkillCacheSnapshot()
            if (!force) {
                // 失败/尝试节流：距上次尝试不足 RETRY_INTERVAL 不再打网
                if (lastSyncAttemptMs != 0L && now - lastSyncAttemptMs < RETRY_INTERVAL_MS) return
                // 新鲜度：距上次成功同步不足 TTL 直接跳过
                if (snapshot.syncedAtMs != 0L && now - snapshot.syncedAtMs < syncTtlMs) return
            }
            lastSyncAttemptMs = now
        }
        val remote = transport.fetchManifestJson()?.let { SkillCodec.parseManifest(it) } ?: run {
            Logger.d(tag, "skill manifest fetch/parse failed, keeping stale cache")
            return
        }
        mutex.withLock {
            val current = memoryCache ?: SkillCacheSnapshot()
            val currentByName = current.skills.associateBy { it.meta.name }
            val merged = remote.mapNotNull { meta ->
                val cached = currentByName[meta.name]
                // 版本一致 = 原文逐字节相同（内容哈希），直接复用本地正文
                if (cached != null && meta.version.isNotEmpty() && cached.meta.version == meta.version) {
                    return@mapNotNull cached.copy(meta = meta)
                }
                val body = transport.fetchSkillJson(meta.name)
                    ?.let { SkillCodec.parseSkillContent(it) }?.second
                when {
                    body != null -> CachedSkill(meta, body)
                    cached != null -> cached // 正文拉取失败：保留旧版（stale），不下线技能
                    else -> null // 新技能拉取失败：本轮跳过，下次同步重试
                }
            }
            // 清单中消失的技能自然被剔除（merged 只含 remote 清单项）
            val updated = SkillCacheSnapshot(skills = merged, syncedAtMs = nowMs())
            memoryCache = updated
            persistLocked(updated)
            Logger.i(tag, "skill sync: ${merged.size} skills (${merged.count { it.meta.version.isNotEmpty() }} versioned)")
        }
    }

    // ── 内部 ────────────────────────────────────────────────────

    /** 内存缓存未加载时自 store 读入（调用方须持 [mutex]）。 */
    private suspend fun ensureLoadedLocked() {
        if (memoryCache != null) return
        memoryCache = runCatching { store.read() }.getOrNull()
            ?.let { SkillCodec.decodeSnapshot(it) } ?: SkillCacheSnapshot()
    }

    private fun buildSectionLocked(): String {
        val snapshot = memoryCache ?: return ""
        return AgentSkillContract.buildPromptSection(
            snapshot.skills.map { it.meta.name to it.meta.description }.sortedBy { it.first },
        )
    }

    private suspend fun persistLocked(snapshot: SkillCacheSnapshot) {
        runCatching { store.write(SkillCodec.encodeSnapshot(snapshot)) }
            .onFailure { Logger.w(tag, "skill cache persist failed: ${it.message}") }
    }

    companion object {
        /** 全量同步 TTL（30 分钟）：skill 是低频变更资产，chat 轮次按此新鲜度复用缓存。 */
        const val DEFAULT_SYNC_TTL_MS = 30 * 60 * 1000L

        /** 同步尝试节流（60 秒）：失败后退避，防 chat 高频轮次重复打网。 */
        const val RETRY_INTERVAL_MS = 60 * 1000L
    }
}
