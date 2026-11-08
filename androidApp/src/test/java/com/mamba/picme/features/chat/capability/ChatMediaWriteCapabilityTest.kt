package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatMediaWriteCapability] 命令映射单测（参照 [ChatRunScriptCapabilityTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMediaWriteCapabilityTest {

    private val context = AgentContext(scene = AgentScene.CHAT)
    private val capability = ChatMediaWriteCapability.getInstance()

    @After
    fun tearDown() {
        capability.unbindDelegate()
    }

    private class RecordingDelegate : ChatMediaWriteCapability.Delegate {
        val deleted = mutableListOf<List<String>>()
        val favorited = mutableListOf<Pair<String, Boolean>>()
        val selected = mutableListOf<Pair<String, Boolean>>()
        val shared = mutableListOf<List<String>>()

        override suspend fun onDeleteMedia(mediaIds: List<String>): String {
            deleted.add(mediaIds)
            return "已删除 ${mediaIds.size} 项"
        }

        override suspend fun onFavoriteMedia(mediaId: String, favorite: Boolean): String {
            favorited.add(mediaId to favorite)
            return "ok"
        }

        override suspend fun onSelectMedia(mediaId: String, selected: Boolean): String {
            this.selected.add(mediaId to selected)
            return "ok"
        }

        override suspend fun onShareMedia(mediaIds: List<String>): String {
            shared.add(mediaIds)
            return "ok"
        }
    }

    @Test
    fun `declares CHAT scene and four write commands`() {
        assertEquals(listOf(SceneManager.Scene.CHAT), capability.activeScenes())
        assertEquals(
            listOf("delete_media", "favorite_media", "select_media", "share_media"),
            capability.supportedCommands(),
        )
    }

    @Test
    fun `delete_media maps to delegate and returns TextReply`() = runBlocking {
        val delegate = RecordingDelegate()
        capability.bindDelegate(delegate)
        val result = capability.execute(
            AgentCommand.DeleteMedia(mediaIds = listOf("1", "2")),
            context,
            null,
        ).getOrNull()
        assertEquals(listOf(listOf("1", "2")), delegate.deleted)
        assertTrue("expected TextReply, got $result", result is AgentAction.TextReply)
        assertEquals("已删除 2 项", (result as AgentAction.TextReply).message)
    }

    @Test
    fun `delete_media with empty ids returns INVALID_PARAMS`() = runBlocking {
        capability.bindDelegate(RecordingDelegate())
        val result = capability.execute(
            AgentCommand.DeleteMedia(mediaIds = emptyList()),
            context,
            null,
        ).getOrNull()
        assertTrue(result is AgentAction.Error)
        assertEquals(AgentErrorCode.INVALID_PARAMS, (result as AgentAction.Error).errorCode)
    }

    @Test
    fun `favorite_media and select_media map to delegate`() = runBlocking {
        val delegate = RecordingDelegate()
        capability.bindDelegate(delegate)
        capability.execute(AgentCommand.FavoriteMedia(mediaId = "9", favorite = false), context, null)
        capability.execute(AgentCommand.SelectMedia(mediaId = "8", selected = true), context, null)
        assertEquals(listOf("9" to false), delegate.favorited)
        assertEquals(listOf("8" to true), delegate.selected)
    }

    @Test
    fun `execute reports unavailable when no delegate`() = runBlocking {
        capability.unbindDelegate()
        val result = capability.execute(
            AgentCommand.DeleteMedia(mediaIds = listOf("1")),
            context,
            null,
        ).getOrNull()
        assertTrue(result is AgentAction.Error)
        assertEquals(
            AgentErrorCode.CAPABILITY_UNAVAILABLE,
            (result as AgentAction.Error).errorCode,
        )
    }

    @Test
    fun `unsupported command reports method not found`() = runBlocking {
        capability.bindDelegate(RecordingDelegate())
        val result = capability.execute(
            AgentCommand.TextReply(message = "hi"),
            context,
            null,
        ).getOrNull()
        assertTrue(result is AgentAction.Error)
        assertEquals(
            AgentErrorCode.METHOD_NOT_FOUND,
            (result as AgentAction.Error).errorCode,
        )
    }
}
