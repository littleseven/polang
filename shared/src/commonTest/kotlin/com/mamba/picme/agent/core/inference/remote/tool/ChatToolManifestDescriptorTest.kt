package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.skill.AgentSkillContract
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
    fun `exactly 10 tools with deterministic names`() {
        assertEquals(
            listOf(
                "get_gallery_summary", "search_media", "refine_media_search",
                "select_media", "favorite_media", "delete_media", "share_media", "ai_optimize",
                "render_html", // 2026-11-08 横滑卡退役追齐（终审 🔴2）：浏览展示出卡工具
                "load_skill",
            ),
            descriptors.map { it.name },
        )
    }

    @Test
    fun `param descriptions survive schema generation on this platform`() {
        val search = descriptors.single { it.name == "search_media" }
        assertEquals(
            listOf("query", "person", "fromMs", "toMs", "hasFace", "mediaType"),
            search.requiredParameters.map { it.name },
        )
        assertEquals("自然语言搜索词", search.requiredParameters[0].description)
        // mediaType（U2-7 2026-11-26）：media id → handle 双轨的第 6 参，空串=photo
        assertEquals(
            "媒体类型：'photo'=只搜照片（默认）；'video'=只搜视频（用户提到视频/录像/vlog 时必传）；'all'=照片+视频；空串=photo",
            search.requiredParameters.single { it.name == "mediaType" }.description,
        )

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
            "获取本地相册摘要：照片/视频/媒体总数、含人脸数、人物聚类数、已/未打标数、语义向量数、扫描建议。结果尾部附数据句柄 handle: scan/<id>，OpenUI 程序绑定照片/视频展示时使用（句柄为全库媒体集，月度分布/相册概况卡绑定它）。",
            summary.description,
        )

        // ai_optimize（2026-08-16 抽卡追齐）：description/参数描述逐字节对齐 ChatToolService.aiOptimize
        val optimize = descriptors.single { it.name == "ai_optimize" }
        assertEquals("AI 一键优化图片。imageUri 为图片 URI。", optimize.description)
        assertEquals("图片 URI", optimize.requiredParameters.single { it.name == "imageUri" }.description)

        // load_skill（2026-11-08 端侧执行器 iOS 接线）：manifest 与 @Tool 真源同引
        // AgentSkillContract const，description/参数描述与契约 SSOT 逐字节一致
        val loadSkill = descriptors.single { it.name == AgentSkillContract.TOOL_LOAD_SKILL }
        assertEquals(AgentSkillContract.DESC_LOAD_SKILL, loadSkill.description)
        assertEquals(
            AgentSkillContract.DESC_PARAM_SKILL_NAME,
            loadSkill.requiredParameters.single { it.name == "name" }.description,
        )
    }
}
