package com.mamba.picme.agent.core.inference.remote.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ChatToolManifest 描述元数据的 KMP 护栏（commonTest：jvm + iosX64 双端跑）。
 *
 * jvmTest `ChatToolManifestConsistencyTest` 只能锁 JVM 反射路径；本测试直接锁
 * manifest 自身产出的 ToolDescriptor 关键字段——serializer schema 生成在 K/N
 * （SerializationClassJsonSchemaGenerator 经 SerialDescriptor 读 @SerialInfo 注解）
 * 与 JVM（ReflectionClassJsonSchemaGenerator）是两条代码路径，K/N 侧若丢参数描述
 * （@property:LLMDescription 未进 SerialDescriptor）这里即红。
 */
class ChatToolManifestDescriptorTest {

    private val descriptors = ChatToolManifest.buildDescriptors()

    @Test
    fun `exactly 8 tools with deterministic names`() {
        assertEquals(
            listOf(
                "get_gallery_summary", "search_media", "refine_media_search",
                "select_media", "favorite_media", "delete_media", "share_media", "ai_optimize",
            ),
            descriptors.map { it.name },
        )
    }

    @Test
    fun `param descriptions survive schema generation on this platform`() {
        val search = descriptors.single { it.name == "search_media" }
        assertEquals(
            listOf("query", "person", "fromMs", "toMs"),
            search.requiredParameters.map { it.name },
        )
        assertEquals("自然语言搜索词", search.requiredParameters[0].description)

        val refine = descriptors.single { it.name == "refine_media_search" }
        assertEquals(
            listOf("constraint", "fromMs", "toMs"),
            refine.requiredParameters.map { it.name },
        )
        assertEquals("细化条件", refine.requiredParameters[0].description)

        val favorite = descriptors.single { it.name == "favorite_media" }
        val favParam = favorite.requiredParameters.single { it.name == "favorite" }
        assertEquals("true 收藏 / false 取消", favParam.description)
        assertTrue(
            favParam.type.toString().contains("Boolean", ignoreCase = true),
            "favorite 参数类型应为 Boolean，实际：${favParam.type}",
        )
    }

    @Test
    fun `tool descriptions are non blank and match android byte source`() {
        val summary = descriptors.single { it.name == "get_gallery_summary" }
        assertEquals(
            "获取本地相册摘要：照片/视频/媒体总数、含人脸数、人物聚类数、已/未打标数、语义向量数、扫描建议。",
            summary.description,
        )

        // ai_optimize（2026-08-16 抽卡追齐）：description/参数描述逐字节对齐 ChatToolService.aiOptimize
        val optimize = descriptors.single { it.name == "ai_optimize" }
        assertEquals("AI 一键优化图片。imageUri 为图片 URI。", optimize.description)
        assertEquals("图片 URI", optimize.requiredParameters.single { it.name == "imageUri" }.description)
    }
}
