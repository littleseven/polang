package com.mamba.picme.agent.core.inference.remote.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudAgentToolCatalogTest {

    @Test
    fun `all three bridge tools are declared`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            assertNotNull(CloudAgentToolCatalog.specFor(name), "missing spec for $name")
        }
    }

    @Test
    fun `all bridge tools are LOCAL executor`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            assertEquals(ToolExecutor.LOCAL, CloudAgentToolCatalog.specFor(name)?.executor)
        }
    }

    @Test
    fun `spec names match contract constants exactly`() {
        assertEquals("get_gallery_summary", CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)
        assertEquals("search_media", CloudAgentToolContract.TOOL_SEARCH_MEDIA)
        assertEquals("refine_media_search", CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH)
    }

    @Test
    fun `retired CLOUD tools - constants pinned for history replay - no catalog spec`() {
        // render_ui(A2UI)/render_ui_openui/render_html 云端工具已退役（2026-11 GenUI 唯一化）：
        // 常量字面值必须稳定（server 存量 action 帧按工具名回放），目录不再声明——
        // [PRIVACY] 闸审计面收口 LOCAL 工具
        assertEquals("render_ui", CloudAgentToolContract.TOOL_RENDER_UI)
        assertEquals("render_ui_openui", CloudAgentToolContract.TOOL_RENDER_UI_OPENUI)
        assertEquals("render_html", CloudAgentToolContract.TOOL_RENDER_HTML)
        assertNull(CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_UI))
        assertNull(CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_UI_OPENUI))
        assertNull(CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_HTML))
    }

    @Test
    fun `write tools have no spec - unknown to cloud`() {
        assertNull(CloudAgentToolCatalog.specFor("delete_media"))
    }

    @Test
    fun `projection schemas promise no media references`() {
        listOf(
            CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        ).forEach { name ->
            val projection = CloudAgentToolCatalog.specFor(name)!!.projectionSchema
            assertTrue(projection.contains("不含"), "$name projection must state exclusion clause")
        }
    }

    @Test
    fun mediaSearchProjection_declaresHandleToken() {
        for (tool in listOf(
            CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
        )) {
            val spec = CloudAgentToolCatalog.specFor(tool)!!
            assertTrue(spec.projectionSchema.contains("handle:"), "$tool 未声明 handle token")
            assertTrue(spec.projectionSchema.contains("不含"), "$tool 缺排除子句")
        }
    }
}
