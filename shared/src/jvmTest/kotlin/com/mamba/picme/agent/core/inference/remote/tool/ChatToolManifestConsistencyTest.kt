package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.reflect.asToolsByClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ChatToolManifest 与 JVM 反射真源的一致性守卫（Phase 6.2 T2）。
 *
 * iOS 侧无反射，chat 工具表面由 commonMain `ChatToolManifest` 手工维护；本测试用
 * Koog JVM 反射（`asToolsByClass()`，与 Android 组合根同一扫描函数）展开
 * [ChatToolService]，对 9 个 iOS 工具（8 相册 + ai_optimize）逐项比对：name / description / 必需参数
 * （名称+描述+类型）/ 可选参数。任一侧改动（@LLMDescription 文案、参数增减）
 * 而另一侧未同步，本测试即红。
 *
 * 只能放 jvmTest：reflect 包 JVM-only（与 `ToolPromptDeterminismTest` 同因）。
 */
class ChatToolManifestConsistencyTest {

    private val reflectedByName: Map<String, ToolDescriptor> =
        ChatToolService.getInstance().asToolsByClass()
            .map { it.descriptor }
            .associateBy { it.name }

    @Test
    fun `manifest covers exactly the 8 ios chat tools`() {
        val expected = setOf(
            "get_gallery_summary", "search_media", "refine_media_search",
            "select_media", "favorite_media", "delete_media", "share_media", "ai_optimize",
        )
        assertEquals(expected, ChatToolManifest.buildDescriptors().map { it.name }.toSet())
    }

    @Test
    fun `every manifest tool matches reflection descriptor item by item`() {
        for (manifest in ChatToolManifest.buildDescriptors()) {
            val reflected = reflectedByName[manifest.name]
            assertNotNull("反射侧找不到工具 ${manifest.name}（ChatToolService 改名/删除了？）", reflected)
            reflected!!
            assertEquals("${manifest.name} description 漂移", reflected.description, manifest.description)
            assertEquals(
                "${manifest.name} requiredParameters 漂移（名称/描述/类型逐项）",
                reflected.requiredParameters.map { Triple(it.name, it.description, it.type.toString()) },
                manifest.requiredParameters.map { Triple(it.name, it.description, it.type.toString()) },
            )
            assertEquals(
                "${manifest.name} optionalParameters 漂移",
                reflected.optionalParameters.map { Triple(it.name, it.description, it.type.toString()) },
                manifest.optionalParameters.map { Triple(it.name, it.description, it.type.toString()) },
            )
        }
    }
}
