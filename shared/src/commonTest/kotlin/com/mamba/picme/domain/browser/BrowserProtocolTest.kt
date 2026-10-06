package com.mamba.picme.domain.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class BrowserProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `action result round-trip with frame`() {
        val r = BrowserActionResult(
            status = BrowserStatus.OK,
            sessionId = "s-1",
            currentUrl = "https://example.com",
            pageTitle = "Example",
            frameJpegBase64 = "anFk",
            actionMs = 123,
        )
        val decoded = json.decodeFromString<BrowserActionResult>(json.encodeToString(BrowserActionResult.serializer(), r))
        assertEquals(r, decoded)
    }

    @Test
    fun `error result keeps structured codes`() {
        val decoded = json.decodeFromString<BrowserActionResult>(
            """{"status":"pool_exhausted","errorCode":"user_concurrency","reason":"busy","unknownField":1}"""
        )
        assertEquals(BrowserStatus.POOL_EXHAUSTED, decoded.status)
        assertEquals("user_concurrency", decoded.errorCode)
        assertNull(decoded.frameJpegBase64)
    }

    @Test
    fun `frame result round-trip`() {
        val r = BrowserFrameResult(status = BrowserStatus.OK, sessionId = "s", currentUrl = "u", pageTitle = "t", frameJpegBase64 = "Zg==")
        val decoded = json.decodeFromString<BrowserFrameResult>(json.encodeToString(BrowserFrameResult.serializer(), r))
        assertEquals(r, decoded)
    }

    @Test
    fun `action request round-trip with element target`() {
        val req = BrowserActionRequest(
            sessionId = "s-1",
            action = BrowserAction.CLICK,
            targetIndex = 3,
            wantFrame = true,
        )
        val decoded = json.decodeFromString<BrowserActionRequest>(json.encodeToString(BrowserActionRequest.serializer(), req))
        assertEquals(req, decoded)
    }

    @Test
    fun `action result carries interactive elements`() {
        val decoded = json.decodeFromString<BrowserActionResult>(
            """{"status":"ok","sessionId":"s","elements":[{"index":0,"tag":"a","text":"Sign in","href":"https://example.com/login","type":null}]}"""
        )
        assertEquals(1, decoded.elements?.size)
        assertEquals("Sign in", decoded.elements?.first()?.text)
        assertEquals("https://example.com/login", decoded.elements?.first()?.href)
    }
}
