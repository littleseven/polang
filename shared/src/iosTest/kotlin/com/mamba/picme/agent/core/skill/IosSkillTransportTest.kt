package com.mamba.picme.agent.core.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * [IosSkillTransport] 适配器（Swift 桥回调 → suspend）的失败语义守卫
 * （2026-11-08 端侧 load_skill 接线，review 🟡-2 补测）：
 * 桥 null → null、桥异常 → null（不穿透 SkillRepository 的 stale-while-revalidate 链）、
 * 正常 body 原样透传 + 路径拼接（/v1/skills、/v1/skills/{name}）。
 */
class IosSkillTransportTest {

    private class ScriptedBridge(
        private val behavior: (String) -> String?,
    ) : IosSkillTransportBridge {
        val paths = mutableListOf<String>()
        override fun fetch(path: String, callback: (String?) -> Unit) {
            paths += path
            callback(behavior(path))
        }
    }

    @Test
    fun nullCallbackMapsToNull() = runTest {
        val bridge = ScriptedBridge { path -> null }
        val transport = IosSkillTransport(bridge)
        assertNull(transport.fetchManifestJson())
        assertNull(transport.fetchSkillJson("html-card"))
        assertEquals(listOf("/v1/skills", "/v1/skills/html-card"), bridge.paths)
    }

    @Test
    fun bridgeExceptionMapsToNull() = runTest {
        val transport = IosSkillTransport(
            object : IosSkillTransportBridge {
                override fun fetch(path: String, callback: (String?) -> Unit) {
                    throw IllegalStateException("bridge exploded")
                }
            },
        )
        assertNull(transport.fetchManifestJson())
    }

    @Test
    fun bodyPassesThroughUntouched() = runTest {
        val manifestJson = """{"skills":[{"name":"html-card","description":"HTML 卡","version":"abc"}]}"""
        val transport = IosSkillTransport(ScriptedBridge { path -> manifestJson })
        assertEquals(manifestJson, transport.fetchManifestJson())
        assertEquals(manifestJson, transport.fetchSkillJson("html-card"))
    }

    @Test
    fun cacheStoreDelegatesToBridge() = runTest {
        var written: String? = null
        val store = IosSkillCacheStore(
            object : IosSkillCacheBridge {
                override fun read(): String? = written
                override fun write(snapshotJson: String): Boolean {
                    written = snapshotJson
                    return true
                }
            },
        )
        assertNull(store.read())
        store.write("""{"skills":[],"syncedAtMs":1}""")
        assertTrue(written != null)
        assertEquals(written, store.read())
    }
}
