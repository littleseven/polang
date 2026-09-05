package com.mamba.picme.domain.trash

import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun newController(scope: TestScope, backend: FakeBackend): TrashSessionController =
        TrashSessionController(backend, scope, StandardTestDispatcher(scope.testScheduler))

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
        assertTrue(c.consumeErrorEvent())
        assertFalse(c.consumeErrorEvent()) // 一次性
    }

    @Test
    fun `user allowed with full success clears pending and reports trashed uris`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a", "b"))
        advanceUntilIdle()
        backend.simulateUserAllowed(listOf("a", "b"))
        val outcome = c.onTrashResult(ok = true)
        advanceUntilIdle()
        assertNull(c.pendingRequest.value)
        assertEquals(listOf("a", "b"), (outcome as? TrashOutcome.Trashed)?.trashedUris)
        assertFalse(c.partialNotice.value)
    }

    @Test
    fun `partial rejection sets notice and reports only actually trashed`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a", "b"))
        advanceUntilIdle()
        backend.simulateUserAllowed(listOf("a")) // b 被拒
        val outcome = c.onTrashResult(ok = true)
        advanceUntilIdle()
        assertEquals(listOf("a"), (outcome as? TrashOutcome.Trashed)?.trashedUris)
        assertTrue(c.partialNotice.value)
        c.consumePartialNotice()
        assertFalse(c.partialNotice.value)
    }

    @Test
    fun `user denied keeps nothing trashed and no notice`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestTrash(listOf("a"))
        advanceUntilIdle()
        val outcome = c.onTrashResult(ok = false)
        advanceUntilIdle()
        assertEquals(TrashOutcome.Cancelled, outcome)
        assertFalse(c.partialNotice.value)
    }

    @Test
    fun `restore flow trashes back to library`() = runTest {
        val backend = FakeBackend()
        val c = newController(this, backend)
        c.requestRestore(listOf("a"))
        advanceUntilIdle()
        assertEquals("restore-token", c.pendingRequest.value?.token)
        backend.trashed.remove("a")
        val outcome = c.onRestoreResult(ok = true)
        advanceUntilIdle()
        assertEquals(listOf("a"), (outcome as? TrashOutcome.Restored)?.restoredUris)
        assertNull(c.pendingRequest.value)
    }
}
