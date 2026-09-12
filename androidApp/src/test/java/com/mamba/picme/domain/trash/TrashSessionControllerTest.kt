package com.mamba.picme.domain.trash

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashSessionControllerTest {

    private class FakeIpcException(message: String) : Exception(message)

    private class FakeBackend : TrashBackend {
        override val isSupported: Boolean = true
        var trashed = mutableSetOf<String>()
        var failNextBuild = false
        var buildTrashCalls = 0
        /** 静默快路径结果；null = 快路径不可用（开关关/无权限），回落系统授权框 */
        var silentTrashResult: List<String>? = null

        override fun buildTrashToken(uris: List<String>): Any {
            buildTrashCalls++
            if (failNextBuild) throw FakeIpcException("ipc fail")
            return "trash-token"
        }

        override fun buildRestoreToken(uris: List<String>): Any = "restore-token"

        override fun queryExisting(uris: List<String>): List<String> =
            uris.filter { uri -> uri !in trashed } // 已 trash 的不算残留（IS_TRASHED 语义）

        override suspend fun trySilentTrash(uris: List<String>): List<String>? = silentTrashResult

        fun simulateUserAllowed(uris: List<String>) {
            trashed.addAll(uris)
        }
    }

    private class UnsupportedBackend : TrashBackend {
        override val isSupported: Boolean = false
        override fun buildTrashToken(uris: List<String>): Any = error("unreachable")
        override fun buildRestoreToken(uris: List<String>): Any = error("unreachable")
        override fun queryExisting(uris: List<String>): List<String> = emptyList()
        override suspend fun trySilentTrash(uris: List<String>): List<String>? = null
    }

    private fun newController(scope: TestScope, backend: TrashBackend): TrashSessionController =
        TrashSessionController(backend, scope, StandardTestDispatcher(scope.testScheduler))

    /** 收集 outcomes 流；测试尾必须 cancel（runTest 会等待子协程）。 */
    private fun TestScope.collectOutcomes(
        controller: TrashSessionController,
        sink: MutableList<TrashOutcome>,
    ): Job = launch { controller.outcomes.toList(sink) }

    @Test
    fun `requestTrash exposes pending request with uris and token`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a", "b"), tag = "cat:screenshots")
        advanceUntilIdle()
        val pending = c.pendingRequest.value
        assertEquals(listOf("a", "b"), pending?.uris)
        assertEquals("trash-token", pending?.token)
        assertEquals("cat:screenshots", pending?.tag)
    }

    @Test
    fun `requestTrash while pending is ignored`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        c.requestTrash(listOf("b"))
        advanceUntilIdle()
        assertEquals(listOf("a"), c.pendingRequest.value?.uris)
    }

    @Test
    fun `build failure keeps state clean and emits error event`() = runTest {
        val backend = FakeBackend().apply { failNextBuild = true }
        val c = newController(this, backend)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertTrue(c.errorEvent.value)
        c.consumeErrorEvent()
        assertFalse(c.errorEvent.value) // 一次性
    }

    @Test
    fun `unsupported backend sets error event and emits unsupported outcome`() = runTest {
        val c = newController(this, UnsupportedBackend())
        assertFalse(c.isSupported)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        advanceUntilIdle() // 先激活订阅：SharedFlow replay=0，无订阅者时 tryEmit 即丢
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertTrue(c.errorEvent.value)
        // Unsupported outcome 供 VM 编排层回滚在途提交状态（F2 死锁修复）
        assertEquals(listOf(TrashOutcome.Unsupported), received)
        c.consumeErrorEvent()
        assertFalse(c.errorEvent.value)
        // 恢复同理短路
        c.requestRestore(listOf("a"))
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertTrue(c.errorEvent.value)
        assertEquals(
            listOf(TrashOutcome.Unsupported, TrashOutcome.Unsupported),
            received,
        )
        job.cancel()
    }

    @Test
    fun `user allowed with full success clears pending and emits trashed outcome`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestTrash(listOf("a", "b"))
        advanceUntilIdle()
        backend.simulateUserAllowed(listOf("a", "b"))
        c.onTrashResult(ok = true)
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        val outcome = received.single() as TrashOutcome.Trashed
        assertEquals(listOf("a", "b"), outcome.trashedUris)
        assertNull(outcome.tag) // 未传 tag 透传 null
        assertFalse(c.partialNotice.value)
        job.cancel()
    }

    @Test
    fun `partial rejection sets notice and emits only actually trashed`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestTrash(listOf("a", "b"), tag = "cat:x")
        advanceUntilIdle()
        backend.simulateUserAllowed(listOf("a")) // b 被拒
        c.onTrashResult(ok = true)
        advanceUntilIdle()
        val outcome = received.single() as TrashOutcome.Trashed
        assertEquals(listOf("a"), outcome.trashedUris)
        assertEquals("cat:x", outcome.tag)
        assertTrue(c.partialNotice.value)
        c.consumePartialNotice()
        assertFalse(c.partialNotice.value)
        job.cancel()
    }

    @Test
    fun `user denied emits cancelled and no notice`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        c.onTrashResult(ok = false)
        advanceUntilIdle()
        assertEquals(listOf(TrashOutcome.Cancelled), received)
        assertFalse(c.partialNotice.value)
        job.cancel()
    }

    @Test
    fun `restore flow emits restored uris`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestRestore(listOf("a"))
        advanceUntilIdle()
        assertEquals("restore-token", c.pendingRequest.value?.token)
        backend.trashed.remove("a")
        c.onRestoreResult(ok = true)
        advanceUntilIdle()
        assertEquals(listOf("a"), (received.single() as TrashOutcome.Restored).restoredUris)
        assertNull(c.pendingRequest.value)
        job.cancel()
    }

    @Test
    fun `restore denied emits cancelled`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestRestore(listOf("a"))
        advanceUntilIdle()
        c.onRestoreResult(ok = false)
        advanceUntilIdle()
        assertEquals(listOf(TrashOutcome.Cancelled), received)
        job.cancel()
    }

    @Test
    fun `second request during token build is ignored (in-flight guard)`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a"))
        // token 构建协程未调度前 in-flight 已同步置位：第二个 request 被拒绝，不覆盖第一个 pending
        c.requestTrash(listOf("b"))
        advanceUntilIdle()
        assertEquals(listOf("a"), c.pendingRequest.value?.uris)
    }

    @Test
    fun `cancelPendingRequest clears matching pending and emits cancelled`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        advanceUntilIdle() // 先激活订阅
        c.requestTrash(listOf("a"), tag = "preview_swipe_chat")
        advanceUntilIdle()
        // 非匹配 tag 不动 pending
        c.cancelPendingRequest("preview_swipe_gallery")
        assertEquals(listOf("a"), c.pendingRequest.value?.uris)
        // 匹配 tag：清 pending + Cancelled 入流，单槽释放后可再请求
        c.cancelPendingRequest("preview_swipe_chat")
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertEquals(listOf(TrashOutcome.Cancelled), received)
        c.requestTrash(listOf("b"), tag = "preview_swipe_chat")
        advanceUntilIdle()
        assertEquals(listOf("b"), c.pendingRequest.value?.uris)
        job.cancel()
    }

    @Test
    fun `cancelPendingRequest during token build prevents pending and frees the slot`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        advanceUntilIdle() // 先激活订阅
        c.requestTrash(listOf("a"), tag = "preview_swipe_memory")
        // 宿主在 token 构建完成前解绑：取消在途请求
        c.cancelPendingRequest("preview_swipe_memory")
        advanceUntilIdle()
        // 不落 pending、按 Cancelled 结算、单槽已释放
        assertNull(c.pendingRequest.value)
        assertEquals(listOf(TrashOutcome.Cancelled), received)
        c.requestTrash(listOf("b"), tag = "preview_swipe_memory")
        advanceUntilIdle()
        assertEquals(listOf("b"), c.pendingRequest.value?.uris)
        job.cancel()
    }

    // ── 静默快路径（MANAGE_MEDIA + 「删除不再询问」开关）──

    @Test
    fun `silent trash full success bypasses pending and emits trashed outcome`() = runTest {
        val backend = FakeBackend().apply { silentTrashResult = listOf("a", "b") }
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestTrash(listOf("a", "b"), tag = "cat:x")
        advanceUntilIdle()
        // 零弹框：不产生 pendingRequest、不构建 token
        assertNull(c.pendingRequest.value)
        assertEquals(0, backend.buildTrashCalls)
        val outcome = received.single() as TrashOutcome.Trashed
        assertEquals(listOf("a", "b"), outcome.trashedUris)
        assertEquals("cat:x", outcome.tag)
        assertFalse(c.partialNotice.value)
        job.cancel()
    }

    @Test
    fun `silent trash partial success emits succeeded and falls back failed subset to token path`() = runTest {
        val backend = FakeBackend().apply { silentTrashResult = listOf("a") } // b 写失败
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestTrash(listOf("a", "b"), tag = "cat:x")
        advanceUntilIdle()
        // 成功集立即入流；失败子集回落 token 通路（持权时同样免弹框），不再置 partialNotice
        val outcome = received.single() as TrashOutcome.Trashed
        assertEquals(listOf("a"), outcome.trashedUris)
        assertEquals("cat:x", outcome.tag)
        assertEquals(1, backend.buildTrashCalls)
        val pending = c.pendingRequest.value
        assertEquals(listOf("b"), pending?.uris)
        assertEquals("trash-token", pending?.token)
        assertEquals("cat:x", pending?.tag)
        assertFalse(c.partialNotice.value)
        job.cancel()
    }

    @Test
    fun `silent trash all failed falls back whole batch to token path`() = runTest {
        val backend = FakeBackend().apply { silentTrashResult = emptyList() } // 空集 ≠ null：快路径已生效但全失败
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        // 全失败：无 Trashed 入流、无 partialNotice，整批回落 token 通路
        assertTrue(received.isEmpty())
        assertEquals(1, backend.buildTrashCalls)
        val pending = c.pendingRequest.value
        assertEquals(listOf("a"), pending?.uris)
        assertEquals("trash-token", pending?.token)
        assertFalse(c.partialNotice.value)
        job.cancel()
    }

    @Test
    fun `silent trash unavailable falls back to system pending path`() = runTest {
        val backend = FakeBackend() // silentTrashResult = null：开关关/无权限
        val c = newController(this, backend)
        val received = mutableListOf<TrashOutcome>()
        val job = collectOutcomes(c, received)
        c.requestTrash(listOf("a", "b"), tag = "cat:x")
        advanceUntilIdle()
        // 回落既有系统授权框通路
        assertEquals(1, backend.buildTrashCalls)
        val pending = c.pendingRequest.value
        assertEquals(listOf("a", "b"), pending?.uris)
        assertEquals("trash-token", pending?.token)
        assertTrue(received.isEmpty())
        job.cancel()
    }

    @Test
    fun `restore never uses silent path`() = runTest {
        val backend = FakeBackend().apply { silentTrashResult = listOf("a") }
        val c = newController(this, backend)
        c.requestRestore(listOf("a"))
        advanceUntilIdle()
        // 恢复仍走系统授权 token（静默快路径仅覆盖 trash）
        assertEquals("restore-token", c.pendingRequest.value?.token)
    }
}
