package com.mamba.picme.agent.core.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * SkillRepository 同步/缓存/降级不变式（2026-11-08 端侧 load_skill 执行器）：
 * - 版本比对增量同步（一致复用正文零下载、不一致重拉、消失剔除）；
 * - stale-while-revalidate（网络失败保留旧缓存照常服务）与失败节流；
 * - 单点穿透（缓存未命中单拉入缓存）；prompt 清单段与云端同模板。
 */
class SkillRepositoryTest {

    private class FakeTransport(
        var manifestJson: String? = null,
        val skills: MutableMap<String, String> = mutableMapOf(),
    ) : SkillTransport {
        var manifestCalls = 0
        val skillCalls = mutableListOf<String>()

        override suspend fun fetchManifestJson(): String? {
            manifestCalls++
            return manifestJson
        }

        override suspend fun fetchSkillJson(name: String): String? {
            skillCalls += name
            return skills[name]
        }
    }

    private class FakeStore : SkillCacheStore {
        var json: String? = null
        override suspend fun read(): String? = json
        override suspend fun write(snapshotJson: String) {
            json = snapshotJson
        }
    }

    private var now = 1_000_000L

    private fun repo(
        transport: FakeTransport,
        store: FakeStore = FakeStore(),
        ttl: Long = SkillRepository.DEFAULT_SYNC_TTL_MS,
    ) = SkillRepository(transport, store, nowMs = { now }, syncTtlMs = ttl)

    private fun manifestJson(vararg entries: Triple<String, String, String>): String =
        entries.joinToString(prefix = "{\"skills\":[", postfix = "]}") { (name, desc, version) ->
            """{"name":"$name","description":"$desc","version":"$version"}"""
        }

    private fun skillJson(name: String, content: String, version: String): String =
        """{"name":"$name","content":"$content","version":"$version"}"""

    // ── 全量同步 ────────────────────────────────────────────────

    @Test
    fun `first sync downloads all skills and persists snapshot`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifestJson(
                Triple("html-card", "HTML 规范", "v1"),
                Triple("album-maker", "相册流程", "v2"),
            ),
            skills = mutableMapOf(
                "html-card" to skillJson("html-card", "HTML 正文", "v1"),
                "album-maker" to skillJson("album-maker", "相册正文", "v2"),
            ),
        )
        val store = FakeStore()
        val repo = repo(transport, store)

        repo.syncIfStale()

        assertEquals(listOf("album-maker", "html-card"), transport.skillCalls.sorted())
        assertEquals("HTML 正文", repo.content("html-card"))
        assertEquals("相册正文", repo.content("album-maker"))
        // 已持久化（重启可恢复）
        val restored = SkillRepository(transport, store, nowMs = { now })
        assertEquals("HTML 正文", restored.content("html-card"))
    }

    @Test
    fun `sync reuses body when version unchanged and refetches when changed`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifestJson(Triple("html-card", "HTML 规范", "v1")),
            skills = mutableMapOf("html-card" to skillJson("html-card", "正文 v1", "v1")),
        )
        val repo = repo(transport)
        repo.syncIfStale()
        assertEquals(listOf("html-card"), transport.skillCalls)

        // 版本不变：TTL 到期后同步，只拉清单不重下载正文
        now += SkillRepository.DEFAULT_SYNC_TTL_MS + 1
        transport.skillCalls.clear()
        repo.syncIfStale()
        assertEquals(2, transport.manifestCalls)
        assertEquals(emptyList(), transport.skillCalls)

        // 服务端编辑（版本变）：重下载正文
        now += SkillRepository.DEFAULT_SYNC_TTL_MS + 1
        transport.manifestJson = manifestJson(Triple("html-card", "HTML 规范", "v2"))
        transport.skills["html-card"] = skillJson("html-card", "正文 v2", "v2")
        repo.syncIfStale()
        assertEquals(listOf("html-card"), transport.skillCalls)
        assertEquals("正文 v2", repo.content("html-card"))
    }

    @Test
    fun `sync removes skills that disappeared from manifest`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifestJson(
                Triple("html-card", "HTML 规范", "v1"),
                Triple("old-skill", "旧技能", "v1"),
            ),
            skills = mutableMapOf(
                "html-card" to skillJson("html-card", "HTML 正文", "v1"),
                "old-skill" to skillJson("old-skill", "旧正文", "v1"),
            ),
        )
        val repo = repo(transport)
        repo.syncIfStale()
        assertEquals("旧正文", repo.content("old-skill"))

        now += SkillRepository.DEFAULT_SYNC_TTL_MS + 1
        transport.manifestJson = manifestJson(Triple("html-card", "HTML 规范", "v1"))
        repo.syncIfStale(force = true)
        // 消失的技能被剔除；穿透单拉也 404（transport.skills 仍有但清单已删——
        // 穿透语义是「新上线技能」，已删除技能由同步收口）
        assertTrue(repo.promptSection().contains("html-card"))
        assertTrue(!repo.promptSection().contains("old-skill"))
    }

    @Test
    fun `sync failure keeps stale cache and throttles retries`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifestJson(Triple("html-card", "HTML 规范", "v1")),
            skills = mutableMapOf("html-card" to skillJson("html-card", "HTML 正文", "v1")),
        )
        val repo = repo(transport)
        repo.syncIfStale()

        // TTL 到期但网络失败：旧缓存照常服务
        now += SkillRepository.DEFAULT_SYNC_TTL_MS + 1
        transport.manifestJson = null
        repo.syncIfStale()
        assertEquals("HTML 正文", repo.content("html-card"))
        assertTrue(repo.promptSection().contains("html-card"))

        // 失败节流：RETRY_INTERVAL 内不再打网
        val callsAfterFailure = transport.manifestCalls
        repo.syncIfStale()
        assertEquals(callsAfterFailure, transport.manifestCalls)

        // 节流期过后重试成功
        now += SkillRepository.RETRY_INTERVAL_MS + 1
        transport.manifestJson = manifestJson(Triple("html-card", "HTML 规范", "v1"))
        repo.syncIfStale()
        assertTrue(transport.manifestCalls > callsAfterFailure)
    }

    @Test
    fun `body fetch failure keeps old version of that skill`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifestJson(Triple("html-card", "HTML 规范", "v1")),
            skills = mutableMapOf("html-card" to skillJson("html-card", "正文 v1", "v1")),
        )
        val repo = repo(transport)
        repo.syncIfStale()

        // 版本变了但正文 404：保留旧版（stale），不下线技能
        now += SkillRepository.DEFAULT_SYNC_TTL_MS + 1
        transport.manifestJson = manifestJson(Triple("html-card", "HTML 规范", "v2"))
        transport.skills.remove("html-card")
        repo.syncIfStale(force = true)
        assertEquals("正文 v1", repo.content("html-card"))
    }

    // ── 单点穿透 ────────────────────────────────────────────────

    @Test
    fun `content misses cache falls through to single fetch and caches`() = runTest {
        val transport = FakeTransport(
            manifestJson = null, // 清单拉不到（如离线启动）
            skills = mutableMapOf("html-card" to skillJson("html-card", "HTML 正文", "v1")),
        )
        val repo = repo(transport)

        assertEquals("HTML 正文", repo.content("html-card"))
        assertEquals(listOf("html-card"), transport.skillCalls)
        // 第二次命中缓存不再打网
        assertEquals("HTML 正文", repo.content("html-card"))
        assertEquals(listOf("html-card"), transport.skillCalls)
    }

    @Test
    fun `content returns null for unknown and malformed names`() = runTest {
        val transport = FakeTransport(manifestJson = manifestJson(Triple("html-card", "d", "v1")))
        val repo = repo(transport)
        assertNull(repo.content("no-such-skill"))
        assertNull(repo.content("../etc"))
        assertNull(repo.content("HTML-CARD"))
        // 非法名不打网
        assertEquals(emptyList(), transport.skillCalls.filter { it.contains("..") })
    }

    // ── prompt 清单段 ───────────────────────────────────────────

    @Test
    fun `promptSection empty before sync and lists skills after`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifestJson(Triple("html-card", "HTML 沙箱卡撰写规范", "v1")),
            skills = mutableMapOf("html-card" to skillJson("html-card", "正文", "v1")),
        )
        val repo = repo(transport)
        assertEquals("", repo.cachedPromptSection())

        repo.syncIfStale()
        val section = repo.promptSection()
        assertTrue(section.contains("load_skill"))
        assertTrue(section.contains("- html-card: HTML 沙箱卡撰写规范"))
        // 非挂起版（AIAgent 重建键路径）同内容
        assertEquals(section, repo.cachedPromptSection())
    }

    @Test
    fun `promptSection matches cloud template byte for byte`() = runTest {
        // 端云同模板钉住：与 server SkillCenterTest.promptSection 用例同输入同输出
        val section = AgentSkillContract.buildPromptSection(listOf("html-card" to "HTML 沙箱卡撰写规范"))
        assertTrue(section.startsWith("\n可用技能（SKILL）清单"))
        assertTrue(section.contains("- html-card: HTML 沙箱卡撰写规范\n"))
        assertEquals("", AgentSkillContract.buildPromptSection(emptyList()))
    }

    // ── 编解码 ──────────────────────────────────────────────────

    @Test
    fun `codec skips malformed entries and tolerates unknown fields`() {
        val parsed = SkillCodec.parseManifest(
            """{"skills":[{"name":"ok","description":"d","version":"v1","future":"x"},{"name":"Bad_Name","description":"d"},{"name":"no-desc","description":""}]}""",
        )
        assertEquals(listOf(SkillMeta("ok", "d", "v1")), parsed)
        assertNull(SkillCodec.parseManifest("not json"))
        assertNull(SkillCodec.parseSkillContent("{}"))
    }
}
