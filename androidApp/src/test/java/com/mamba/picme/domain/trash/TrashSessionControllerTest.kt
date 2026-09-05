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

    private class FakeBackend : TrashBackend {
        override val isSupported: Boolean = true
        var trashed = mutableSetOf<String>()
        var failNextBuild = false

        override fun buildTrashToken(uris: List<String>): Any {
            if (failNextBuild) throw RuntimeException("ipc fail")
            return "trash-token"
        }

        override fun buildRestoreToken(uris: List<String>): Any = "restore-token"

        override fun queryExisting(uris: List<String>): List<String> =
            uris.filter { uri -> uri !in trashed } // 已 trash 的不算残留（IS_TRASHED 语义）

        fun simulateUserAllowed(uris: List<String>) {
            trashed.addAll(uris)
        }
    }

    private class UnsupportedBackend : TrashBackend {
        override val isSupported: Boolean = false
        override fun buildTrashToken(uris: List<String>): Any = error("unreachable")
        override fun buildRestoreToken(uris: List<String>): Any = error("unreachable")
        override fun queryExisting(uris: List<String>): List<String> = emptyList()
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
    fun `unsupported backend sets error event without pending request`() = runTest {
        val c = newController(this, UnsupportedBackend())
        assertFalse(c.isSupported)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertTrue(c.errorEvent.value)
        c.consumeErrorEvent()
        assertFalse(c.errorEvent.value)
        // 恢复同理短路
        c.requestRestore(listOf("a"))
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertTrue(c.errorEvent.value)
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
}
