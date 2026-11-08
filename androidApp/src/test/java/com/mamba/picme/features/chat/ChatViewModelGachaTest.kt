package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.OptimizeCandidateGroup

import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.chatMessageEntity
import com.mamba.picme.data.local.decodeParts
import com.mamba.picme.domain.chat.MessagePart
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelGachaTest : ChatViewModelTestBase() {

    private val gachaController = mockk<ChatOptimizeGachaController>(relaxUnitFun = true)

    override fun newViewModel(): ChatViewModel = super.newViewModelWithGacha(gachaController)

    private fun group() = OptimizeCandidateGroup(
        sourceImageUri = "content://media/1",
        scene = "GENERAL",
        recommendedIndex = 1,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate("base", "file:///a.jpg", 6.0f, false),
            OptimizeCandidateGroup.Candidate("warm", "file:///b.jpg", 6.5f, false)
        ),
        usedFingerprints = listOf("fp1"),
        drawIndex = 1
    )

    private fun candidatesMessage(group: OptimizeCandidateGroup = group()) = chatMessageEntity(
        id = "msg1", sessionId = "default", role = "agent",
        parts = listOf(MessagePart.OptimizeCandidates("p0", group)),
    )

    @Test
    fun `loadMessages restores gacha selection for pending candidates message`() = runTest {
        every { gachaController.hasPending("msg1") } returns true
        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(
            listOf(candidatesMessage())
        )

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(1, vm.gachaSelections.value["msg1"])
    }

    @Test
    fun `insertOptimizeCandidatesMessage persists optimize_candidates and seeds selection`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.insertOptimizeCandidatesMessage("default", "msg1", group(), "remote_deepseek")
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals(OptimizeCandidateGroup.MESSAGE_TYPE, slot.captured.type)
        assertEquals("msg1", slot.captured.id)
        val persisted = slot.captured.decodeParts()
            .filterIsInstance<MessagePart.OptimizeCandidates>().single()
        assertEquals(group(), persisted.group)
        assertEquals(1, vm.gachaSelections.value["msg1"])
    }

    @Test
    fun `onOptimizeGachaConfirm rewrites message to agent image`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        coEvery { gachaController.confirm("msg1", 1) } returns
            ChatOptimizeGachaController.ConfirmResult("file:///full.jpg", mockk())
        coEvery { chatMessageDao.getMessageById("msg1") } returns candidatesMessage()

        vm.onOptimizeGachaConfirm("msg1", 1) { }
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals("image", slot.captured.type)
        assertEquals("agent", slot.captured.role)
        assertEquals("msg1", slot.captured.id)
        val image = slot.captured.decodeParts().filterIsInstance<MessagePart.Image>().single()
        assertEquals("file:///full.jpg", image.ref)
        assertFalse(image.saved)
        assertNull(slot.captured.metadata)
    }

    @Test
    fun `onOptimizeGachaConfirm failure keeps message and reports error`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        coEvery { gachaController.confirm("msg1", 1) } returns null

        var failed = false
        vm.onOptimizeGachaConfirm("msg1", 1) { ok -> failed = !ok }
        advanceUntilIdle()

        assertTrue(failed)
        coVerify(exactly = 0) { chatMessageDao.insertMessage(any()) }
    }

    @Test
    fun `onOptimizeGachaReroll rewrites message parts and resets selection`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val newGroup = group().copy(drawIndex = 2, recommendedIndex = 0)
        coEvery { gachaController.reroll("msg1") } returns
            ChatOptimizeGachaController.RerollOutcome.Rerolled(newGroup)
        coEvery { chatMessageDao.getMessageById("msg1") } returns candidatesMessage()

        vm.onOptimizeGachaReroll("msg1") { }
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        val persisted = slot.captured.decodeParts()
            .filterIsInstance<MessagePart.OptimizeCandidates>().single()
        assertEquals(newGroup, persisted.group)
        assertEquals(0, vm.gachaSelections.value["msg1"])
    }

    @Test
    fun `onOptimizeGachaReroll ignores concurrent tap while rerolling`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<ChatOptimizeGachaController.RerollOutcome>()
        coEvery { gachaController.reroll("msg1") } coAnswers { gate.await() }

        var results = 0
        vm.onOptimizeGachaReroll("msg1") { results++ }
        vm.onOptimizeGachaReroll("msg1") { results++ } // 应被防抖忽略
        gate.complete(
            ChatOptimizeGachaController.RerollOutcome.Rerolled(group().copy(drawIndex = 2))
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { gachaController.reroll("msg1") }
        assertEquals(1, results)
        assertTrue(vm.gachaRerolling.value.isEmpty())
    }

    @Test
    fun `clearChat discards pending gacha groups`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.clearChat()
        advanceUntilIdle()

        coVerify { gachaController.discardPending("default", any()) }
    }

    @Test
    fun `clearChat clears gacha selections`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.insertOptimizeCandidatesMessage("default", "msg1", group(), "remote_deepseek")
        advanceUntilIdle()
        assertEquals(1, vm.gachaSelections.value["msg1"])

        vm.clearChat()
        advanceUntilIdle()

        assertTrue(vm.gachaSelections.value.isEmpty())
    }
}
