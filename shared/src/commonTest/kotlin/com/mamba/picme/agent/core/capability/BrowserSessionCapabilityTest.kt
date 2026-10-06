package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.domain.browser.BrowserActionRequest
import com.mamba.picme.domain.browser.BrowserActionResult
import com.mamba.picme.domain.browser.BrowserFrameResult
import com.mamba.picme.domain.browser.BrowserStatus
import com.mamba.picme.domain.browser.BrowserUnavailableException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrowserSessionCapabilityTest {

    private class FakeTransport(var result: BrowserActionResult) : BrowserTransport {
        val calls = mutableListOf<String>()
        var lastRequest: BrowserActionRequest? = null
        var failWith: BrowserUnavailableException? = null
        override suspend fun open(url: String, wantFrame: Boolean): BrowserActionResult {
            calls += "open:$url:$wantFrame"
            failWith?.let { throw it }
            return result
        }
        override suspend fun action(request: BrowserActionRequest): BrowserActionResult {
            calls += "action:${request.sessionId}:${request.action}:${request.wantFrame}"
            lastRequest = request
            failWith?.let { throw it }
            return result
        }
        override suspend fun frame(sessionId: String): BrowserFrameResult {
            calls += "frame:$sessionId"
            failWith?.let { throw it }
            return BrowserFrameResult(status = BrowserStatus.OK, sessionId = sessionId)
        }
        override suspend fun close(sessionId: String): BrowserActionResult {
            calls += "close:$sessionId"
            failWith?.let { throw it }
            return result
        }
    }

    private class RecordingDelegate : BrowserSessionDelegate {
        val events = mutableListOf<String>()
        override fun onBrowserSessionStarted(sessionId: String, url: String, title: String, frameJpegBase64: String?) { events += "start:$sessionId" }
        override fun onBrowserSessionAction(sessionId: String, action: String, selector: String?, text: String?, url: String, title: String, frameJpegBase64: String?) { events += "act:$sessionId:$action" }
        override fun onBrowserSessionFrame(sessionId: String, url: String, title: String, frameJpegBase64: String) { events += "frame:$sessionId" }
        override fun onBrowserSessionFailed(sessionId: String, reason: String) { events += "fail:$sessionId:$reason" }
        override fun onBrowserSessionClosed(sessionId: String, finalFrameJpegBase64: String?, actionCount: Int) { events += "close:$sessionId:$actionCount" }
    }

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `open success emits started event and returns sessionId payload`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1", currentUrl = "https://a.com", pageTitle = "A", frameJpegBase64 = "Zg=="))
        val delegate = RecordingDelegate()
        val cap = BrowserSessionCapability(transport).also { it.setDelegate(delegate) }
        val result = cap.execute(AgentCommand.BrowserOpen(url = "https://a.com"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.contains("s-1"))
        assertEquals(listOf("open:https://a.com:true"), transport.calls)
        assertEquals(listOf("start:s-1"), delegate.events)
    }

    @Test
    fun `extract does not request frame`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1", textExtract = "hello"))
        val cap = BrowserSessionCapability(transport)
        cap.execute(AgentCommand.BrowserExtract(sessionId = "s-1"), context, null)
        assertEquals(listOf("action:s-1:extract:false"), transport.calls)
    }

    @Test
    fun `pool exhausted maps to degradation text`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.POOL_EXHAUSTED, reason = "busy"))
        val cap = BrowserSessionCapability(transport)
        val result = cap.execute(AgentCommand.BrowserOpen(url = "https://a.com"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.contains(BrowserStatus.POOL_EXHAUSTED))
    }

    @Test
    fun `transport exception maps to browser_unavailable degradation`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK)).also {
            it.failWith = BrowserUnavailableException("connect refused")
        }
        val cap = BrowserSessionCapability(transport)
        val result = cap.execute(AgentCommand.BrowserOpen(url = "https://a.com"), context, null)
        val action = assertIs<AgentAction.TextReply>(result.getOrThrow())
        assertTrue(action.message.contains(BrowserStatus.BROWSER_UNAVAILABLE))
    }

    @Test
    fun `close emits closed event with action count`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1", actionCount = 7))
        val delegate = RecordingDelegate()
        val cap = BrowserSessionCapability(transport).also { it.setDelegate(delegate) }
        cap.execute(AgentCommand.BrowserClose(sessionId = "s-1"), context, null)
        assertEquals(listOf("close:s-1:7"), delegate.events)
    }

    @Test
    fun `click normalizes sentinel fields to null and prefers index`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1"))
        val cap = BrowserSessionCapability(transport)
        cap.execute(AgentCommand.BrowserClick(sessionId = "s-1", targetIndex = 3), context, null)
        val req = transport.lastRequest!!
        assertEquals(3, req.targetIndex)
        assertEquals(null, req.targetText)
        assertEquals(null, req.selector)
    }

    @Test
    fun `click requests frame`() = runTest {
        val transport = FakeTransport(BrowserActionResult(status = BrowserStatus.OK, sessionId = "s-1"))
        val cap = BrowserSessionCapability(transport)
        cap.execute(AgentCommand.BrowserClick(sessionId = "s-1", selector = "button"), context, null)
        assertEquals(listOf("action:s-1:click:true"), transport.calls)
    }

    @Test
    fun `supported commands cover all seven browser tools`() {
        val cap = BrowserSessionCapability(FakeTransport(BrowserActionResult(status = BrowserStatus.OK)))
        assertEquals(
            listOf("browser_open", "browser_navigate", "browser_click", "browser_type", "browser_extract", "browser_screenshot", "browser_close"),
            cap.supportedCommands(),
        )
    }
}
