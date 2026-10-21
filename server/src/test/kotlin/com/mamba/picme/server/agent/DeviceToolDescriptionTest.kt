package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.reflect.asToolsByClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 可见描述只许承诺 projectionSchema 面（混合架构 M1，spec §3.1③）：
 * 三设备工具描述必须显式声明「不含文件路径/媒体清单」，防止描述漂移暗示全量数据可得。
 */
class DeviceToolDescriptionTest {

    private val descriptors = DeviceToolService("s", AgentBridgeRegistry()) { _ -> }
        .asToolsByClass()
        .associateBy { tool -> tool.descriptor.name }

    @Test
    fun `three device tools present`() {
        assertEquals(
            setOf("get_gallery_summary", "search_media", "refine_media_search"),
            descriptors.keys,
        )
    }

    @Test
    fun `every description promises projection-only surface`() {
        descriptors.forEach { (name, tool) ->
            val description = tool.descriptor.description
            assertTrue("$name description must state projection", description.contains("投影"))
            assertTrue("$name description must state exclusion", description.contains("不含文件路径"))
        }
    }

    @Test
    fun `media search descriptions declare handle token`() {
        // 与 shared CloudAgentToolCatalog.projectionSchema 的 handle 子句双向同步（混合架构 M2）
        listOf("search_media", "refine_media_search").forEach { name ->
            val description = descriptors.getValue(name).descriptor.description
            assertTrue("$name description must declare handle", description.contains("handle: scan/"))
        }
    }
}
