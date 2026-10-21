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
    fun `render_html is CLOUD executor with summary-only receipt`() {
        val spec = CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_HTML)
        assertNotNull(spec, "missing spec for render_html")
        assertEquals("render_html", CloudAgentToolContract.TOOL_RENDER_HTML)
        assertEquals(ToolExecutor.CLOUD, spec.executor)
        assertTrue(
            spec.projectionSchema.contains("summary"),
            "render_html projection must declare summary-only receipt",
        )
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
}
