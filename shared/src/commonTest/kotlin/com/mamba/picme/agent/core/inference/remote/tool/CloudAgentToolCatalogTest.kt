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
    fun renderUi_isCloudToolWithNoProjectionSurface() {
        val spec = CloudAgentToolCatalog.specFor(CloudAgentToolContract.TOOL_RENDER_UI)
        assertNotNull(spec)
        assertEquals(ToolExecutor.CLOUD, spec.executor)
        assertTrue(spec.projectionSchema.contains("不适用"), "CLOUD 工具 projectionSchema 须显式声明不适用")
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
