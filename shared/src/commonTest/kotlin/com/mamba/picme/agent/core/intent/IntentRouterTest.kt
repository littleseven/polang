package com.mamba.picme.agent.core.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * IntentRouter.route() 流程测试（无 executor 可达路径：门控/pattern/降级）。
 * LLM 调用路径（超时/重试/低置信降级）需 fake executor，暂以纯函数内核测试覆盖。
 */
class IntentRouterTest {

    private val state = CompactChatState(lastArtifact = null, hasSearchBase = false)

    @Test
    fun `gated query passes through without touching provider`() = runTest {
        var providerCalled = false
        val router = IntentRouter {
            providerCalled = true
            null
        }
        val result = router.route("今天天气怎么样", state, "2026-09-26", traceId = null)
        assertEquals(RoutePath.GATED_PASSTHROUGH, result.path)
        assertEquals(IntentId.OPEN_QA, result.output.deliverable)
        assertEquals(0, result.latencyMs)
        assertTrue(!providerCalled, "门控直通不得触发 executor provider")
    }

    @Test
    fun `pattern shortcut passes through without touching provider`() = runTest {
        var providerCalled = false
        val router = IntentRouter {
            providerCalled = true
            null
        }
        val result = router.route("看下我儿子的照片", state, "2026-09-26", traceId = null)
        assertEquals(RoutePath.PATTERN_SHORTCUT, result.path)
        assertEquals(IntentId.VIEW_PHOTOS, result.output.deliverable)
        assertTrue(!providerCalled, "pattern 捷径不得触发 executor provider")
    }

    @Test
    fun `null executor bundle degrades to OPEN_QA`() = runTest {
        val router = IntentRouter { null }
        val result = router.route("帮我分析一下去年照片的构图风格", state, "2026-09-26", traceId = null)
        assertEquals(RoutePath.DEGRADED_NO_EXECUTOR, result.path)
        assertEquals(IntentId.OPEN_QA, result.output.deliverable)
        assertEquals("executor unavailable", result.degradeReason)
    }
}
