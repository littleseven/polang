package com.mamba.picme.agent.core.inference.remote.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudToolProjectionGateTest {

    @Test
    fun `clean projection passes through unchanged`() {
        val text = "找到 327 张「猫」的照片"
        assertEquals(text, CloudToolProjectionGate.gate("search_media", text))
    }

    @Test
    fun `error text without forbidden pattern passes through`() {
        val text = "error: 没有上一轮搜索结果可细化"
        assertEquals(text, CloudToolProjectionGate.gate("refine_media_search", text))
    }

    @Test
    fun `content uri is blocked`() {
        val out = CloudToolProjectionGate.gate("search_media", "找到 1 张 content://media/external/images/42")
        assertEquals("error: projection_blocked:search_media", out)
    }

    @Test
    fun `file uri is blocked`() {
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "file:///storage/emulated/0/DCIM/a.jpg"),
        )
    }

    @Test
    fun `local handle is blocked`() {
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "详见 local://scan/7f3a"),
        )
    }

    @Test
    fun `absolute storage path is blocked`() {
        assertEquals(
            "error: projection_blocked:get_gallery_summary",
            CloudToolProjectionGate.gate("get_gallery_summary", "最新: /storage/emulated/0/DCIM/x.jpg"),
        )
        assertEquals(
            "error: projection_blocked:get_gallery_summary",
            CloudToolProjectionGate.gate("get_gallery_summary", "/sdcard/DCIM/x.jpg"),
        )
    }

    @Test
    fun `app private path is blocked`() {
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "/data/user/0/com.mamba.picme/files/x"),
        )
        assertEquals(
            "error: projection_blocked:search_media",
            CloudToolProjectionGate.gate("search_media", "/data/media/0/x"),
        )
    }

    @Test
    fun `error message leaking path is also blocked`() {
        val out = CloudToolProjectionGate.gate("search_media", "error: /storage/emulated/0/db locked")
        assertEquals("error: projection_blocked:search_media", out)
    }

    @Test
    fun `blocked output itself passes the gate - idempotent safe`() {
        val blocked = CloudToolProjectionGate.gate("search_media", "content://x")
        assertEquals(blocked, CloudToolProjectionGate.gate("search_media", blocked))
        assertTrue(blocked.startsWith(CloudToolProjectionGate.BLOCKED_PREFIX))
    }

    @Test
    fun `empty string passes through`() {
        assertEquals("", CloudToolProjectionGate.gate("search_media", ""))
    }
}
