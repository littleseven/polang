package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * iOS chat 工具手工清单（Phase 6.2 T2）：K/N 无反射，`asToolsByClass()` 不可用，
 * 用 [SimpleTool] 子类逐字面对齐 [ChatToolService] 的 7 个相册工具 + ai_optimize
 * （2026-08-16 抽卡追齐新增；2026-09-26 意图路由 M1：`view_media` 移出 chat 工具面
 *（chat 内看图 = 点横滑卡片，消除 chat 必败工具陷阱），`search_media` 增补
 * person/fromMs/toMs 结构化参数透传）。
 *
 * 一致性纪律：name/description/参数名/参数描述**逐字节**照抄 @Tool(customName) 与
 * @LLMDescription 原文（改任何一侧都会触发 jvmTest `ChatToolManifestConsistencyTest`
 * 红——该测试用 JVM 反射展开结果与本清单逐项比对）。
 *
 * 工具实现直接委托 [ChatToolService] 的 suspend 方法（同一 dispatchCommand →
 * CapabilityRegistry(scene=CHAT) + uiActions 通路，Android/iOS 行为同源）。
 *
 * 未纳入的 @Tool（JS/修图/记忆/设置/导航等）见 plan §1「不进第一版」——iOS prompt
 * （IosChatPrompt）不引用这些能力，LLM 无从幻觉调用。
 */
@OptIn(ExperimentalSerializationApi::class)
object ChatToolManifest {

    // ── 参数包（@Serializable，属性名= JVM 反射参数名，@LLMDescription= 参数描述）────

    @Serializable
    class EmptyArgs

    @Serializable
    class SearchMediaArgs(
        @property:LLMDescription("自然语言搜索词") val query: String,
        @property:LLMDescription("人物分组名或称谓（如'大宝''儿子'），无则空串") val person: String,
        @property:LLMDescription("时间起点（毫秒，据当前日期算）；空串=不限") val fromMs: String,
        @property:LLMDescription("时间终点（毫秒）；空串=不限") val toMs: String,
    )

    @Serializable
    class RefineMediaSearchArgs(
        @property:LLMDescription("细化条件") val constraint: String,
        @property:LLMDescription("时间起点（毫秒），如某月起始；空串=不限") val fromMs: String,
        @property:LLMDescription("时间终点（毫秒），如某月末；空串=不限") val toMs: String,
    )

    @Serializable
    class SelectMediaArgs(
        @property:LLMDescription("媒体 id") val mediaId: String,
        @property:LLMDescription("true 选中 / false 取消") val selected: Boolean,
    )

    @Serializable
    class FavoriteMediaArgs(
        @property:LLMDescription("媒体 id") val mediaId: String,
        @property:LLMDescription("true 收藏 / false 取消") val favorite: Boolean,
    )

    @Serializable
    class MediaIdsArgs(
        @property:LLMDescription("媒体 id 列表逗号分隔，无则空串") val mediaIds: String,
    )

    @Serializable
    class AiOptimizeArgs(
        @property:LLMDescription("图片 URI") val imageUri: String,
    )

    // ── 工具实现（委托 ChatToolService 同名 suspend 方法）─────────────────────────

    private class GetGallerySummaryTool : SimpleTool<EmptyArgs>(
        argsType = typeToken<EmptyArgs>(),
        name = "get_gallery_summary",
        description = "获取本地相册摘要：照片/视频/媒体总数、含人脸数、人物聚类数、已/未打标数、语义向量数、扫描建议。",
    ) {
        override suspend fun execute(args: EmptyArgs): String =
            ChatToolService.getInstance().getGallerySummary()
    }

    private class SearchMediaTool : SimpleTool<SearchMediaArgs>(
        argsType = typeToken<SearchMediaArgs>(),
        name = "search_media",
        description = "搜索本地相册，**结果以横滑卡片直接展示给用户**（调用后无需再调其它工具展示，如实总结数量即可）。query 为自然语言搜索词，如'去年夏天海边的小孩'。人物精确查询用 person 传人物分组名或称谓（如'大宝''儿子'），并可与 fromMs/toMs 组合做「人物 ∩ 时间」精确交集——不要把人物名只拼进 query（会丢人物维度，误回他人照片）。",
    ) {
        override suspend fun execute(args: SearchMediaArgs): String =
            ChatToolService.getInstance().searchMedia(args.query, args.person, args.fromMs, args.toMs)
    }

    private class RefineMediaSearchTool : SimpleTool<RefineMediaSearchArgs>(
        argsType = typeToken<RefineMediaSearchArgs>(),
        name = "refine_media_search",
        description = "在上一轮搜索结果内细化过滤，**结果以横滑卡片更新展示**。如'只要夜景''找找4月的'。constraint 为细化条件；时间窄化务必传 fromMs/toMs（毫秒，据当前日期算）做精确交集，留空串=不限。上一轮无搜索基数时会返回明确错误，此时改用 search_media 重新全局搜索。",
    ) {
        override suspend fun execute(args: RefineMediaSearchArgs): String =
            ChatToolService.getInstance().refineMediaSearch(args.constraint, args.fromMs, args.toMs)
    }

    private class SelectMediaTool : SimpleTool<SelectMediaArgs>(
        argsType = typeToken<SelectMediaArgs>(),
        name = "select_media",
        description = "选择/取消选择媒体。selected 为 true 选中 / false 取消。",
    ) {
        override suspend fun execute(args: SelectMediaArgs): String =
            ChatToolService.getInstance().selectMedia(args.mediaId, args.selected)
    }

    private class FavoriteMediaTool : SimpleTool<FavoriteMediaArgs>(
        argsType = typeToken<FavoriteMediaArgs>(),
        name = "favorite_media",
        description = "收藏/取消收藏媒体。favorite 为 true 收藏 / false 取消。",
    ) {
        override suspend fun execute(args: FavoriteMediaArgs): String =
            ChatToolService.getInstance().favoriteMedia(args.mediaId, args.favorite)
    }

    private class DeleteMediaTool : SimpleTool<MediaIdsArgs>(
        argsType = typeToken<MediaIdsArgs>(),
        name = "delete_media",
        description = "删除媒体。mediaIds 为 id 列表逗号分隔，无则空串。",
    ) {
        override suspend fun execute(args: MediaIdsArgs): String =
            ChatToolService.getInstance().deleteMedia(args.mediaIds)
    }

    private class ShareMediaTool : SimpleTool<MediaIdsArgs>(
        argsType = typeToken<MediaIdsArgs>(),
        name = "share_media",
        description = "分享媒体。mediaIds 为 id 列表逗号分隔，无则空串。",
    ) {
        override suspend fun execute(args: MediaIdsArgs): String =
            ChatToolService.getInstance().shareMedia(args.mediaIds)
    }

    /** ai_optimize（2026-08-16 抽卡追齐）：逐字节对齐 ChatToolService.aiOptimize。 */
    private class AiOptimizeTool : SimpleTool<AiOptimizeArgs>(
        argsType = typeToken<AiOptimizeArgs>(),
        name = "ai_optimize",
        description = "AI 一键优化图片。imageUri 为图片 URI。",
    ) {
        override suspend fun execute(args: AiOptimizeArgs): String =
            ChatToolService.getInstance().aiOptimize(args.imageUri)
    }

    /** 8 个工具实例（stateless，共享一份即可；registry 与 descriptors 同源派生）。 */
    val tools: List<ToolBase<*, *>> by lazy {
        listOf(
            GetGallerySummaryTool(),
            SearchMediaTool(),
            RefineMediaSearchTool(),
            SelectMediaTool(),
            FavoriteMediaTool(),
            DeleteMediaTool(),
            ShareMediaTool(),
            AiOptimizeTool(),
        )
    }

    /** system prompt 清单段用的描述元数据（与 [tools] 同源，零漂移）。 */
    fun buildDescriptors(): List<ToolDescriptor> = tools.map { it.descriptor }
}
