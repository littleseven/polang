package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.mamba.picme.agent.core.skill.AgentSkillContract
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * iOS chat 工具手工清单（Phase 6.2 T2）：K/N 无反射，`asToolsByClass()` 不可用，
 * 用 [SimpleTool] 子类逐字面对齐 [ChatToolService] 的 7 个相册工具 + ai_optimize
 * （2026-08-16 抽卡追齐新增；2026-09-26 意图路由 M1：`view_media` 移出 chat 工具面；
 * `search_media` 增补 person/fromMs/toMs 结构化参数透传；2026-09-29 再增补 hasFace；
 * 2026-11-08 横滑相册卡退役：search/refine 描述去「自动出卡」，改为取数 + render_html
 * 出图集卡；同日终审补登 `render_html` 本体——描述指向的工具必须在工具面物理存在）。
 * 2026-11-08 端侧 load_skill 执行器 iOS 接线：+`load_skill`（技能是跨意图元能力，
 * 名字/描述/参数描述直接引用 [AgentSkillContract] const——端云 LLM 可见面 SSOT，
 * 天然与 @Tool 真源逐字节一致，无双写漂移面）。
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
        @property:LLMDescription("时间起点（毫秒，据当前日期算）；query 含时间词（如'去年夏天''3月'）时**必须**换算填入；空串=不限") val fromMs: String,
        @property:LLMDescription("时间终点（毫秒）；query 含时间词时**必须**换算填入；空串=不限") val toMs: String,
        @property:LLMDescription("true=只要含人脸的照片；空串=不限") val hasFace: String,
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

    @Serializable
    class RenderHtmlArgs(
        @property:LLMDescription("HTML 源码：CSS/JS 内联；可用远程 <img>/<video>/<audio>/CSS 与 <a> 外链；禁远程 script/iframe/fetch/XHR") val html: String,
        @property:LLMDescription("对卡片内容的一句话文字总结") val summary: String,
        @property:LLMDescription("展示形态：inline（默认，聊天内完全撑开直接交互，内容高度建议 ≤2/3 屏）或 fullpage（长报告/多屏图文/为全屏设计的交互页，聊天内显示固定高预览、点击进全屏）；空串 = inline") val display: String,
    )

    @Serializable
    class LoadSkillArgs(
        @property:LLMDescription(AgentSkillContract.DESC_PARAM_SKILL_NAME) val name: String,
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
        description = "搜索本地相册，返回命中总数与前 24 个媒体 id（命中 ≤24 时全量返回；需要更多 id 用 run_gallery_script 的 gallery.query 获取）。结果不再自动出卡——需要展示给用户时随后调 render_html，用 media://{id} 引用出图集卡。query 为自然语言搜索词，如'去年夏天海边的小孩'。人物精确查询用 person 传人物分组名或称谓（如'大宝''儿子'），并可与 fromMs/toMs 组合做「人物 ∩ 时间」精确交集——不要把人物名只拼进 query（会丢人物维度，误回他人照片）。只要含人脸/人像的照片（'有人脸''有人的''人像''自拍/合照'类诉求）必须传 hasFace='true'——只把'人脸'写进 query 会丢人脸维度。",
    ) {
        override suspend fun execute(args: SearchMediaArgs): String =
            ChatToolService.getInstance().searchMedia(args.query, args.person, args.fromMs, args.toMs, args.hasFace)
    }

    private class RefineMediaSearchTool : SimpleTool<RefineMediaSearchArgs>(
        argsType = typeToken<RefineMediaSearchArgs>(),
        name = "refine_media_search",
        description = "在上一轮搜索结果内细化过滤，返回命中总数与前 24 个媒体 id（并更新搜索基数；展示用 render_html 重出图集卡）。如'只要夜景''找找4月的'。constraint 为细化条件；时间窄化务必传 fromMs/toMs（毫秒，据当前日期算）做精确交集，留空串=不限。上一轮无搜索基数时会返回明确错误，此时改用 search_media 重新全局搜索。",
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

    /**
     * render_html（2026-11-08 横滑卡退役追齐，终审 🔴2 修复）：浏览展示的出卡工具——
     * 退役后 search/refine 描述指引模型「展示用 render_html 出图集卡」，iOS 工具面缺它
     * 就是教模型调不存在的工具（幻觉诱导）。执行走 ChatToolService.renderHtml →
     * dispatchCommand → IosRenderHtmlCapability（组合根已注册，bridge=null 时能力自报
     * 不可用回 Error）。描述/参数逐字节对齐 ChatToolService.renderHtml（含 draw_chart /
     * run_gallery_script 提及——二者不在 iOS chat 工具面，但一致性测试要求零漂移；
     * 模型误调会被未知工具拒绝并自恢复，IosChatPrompt 收敛规则只引 render_html）。
     */
    private class RenderHtmlTool : SimpleTool<RenderHtmlArgs>(
        argsType = typeToken<RenderHtmlArgs>(),
        name = "render_html",
        description = "渲染一个 HTML 组件卡片插入聊天（端侧 WebView 渲染，支持内联 CSS/JS 动画与交互）。" +
            "仅当用户明确要求更丰富的展示或交互组件（如可交互图表、动画演示、自定义布局卡片）时才调用；" +
            "柱/折/饼统计图一律仍用 draw_chart，不要用本工具替代。" +
            "html 的 CSS/JS 一律内联；远程 script、iframe/object/embed/form、meta refresh 会被清洗剔除，" +
            "fetch/XHR 也不可用（离线沙箱）。" +
            "鼓励引用远程富媒体提升表现力：<img> 网络图片、<video>/<audio> 远程媒体、远程 CSS 样式、" +
            "<a> 外链均可使用（a 外链用户点击后在全屏落地页打开）；" +
            "URL 务必用确定可访问的真实地址（优先官方/权威站点素材），不要编造域名。" +
            "引用用户相册本地图片/视频：图片用 <img src=\"media://媒体id\">、视频用 <video src=\"media://媒体id\">（id 只能来自 run_gallery_script 取数" +
            "（gallery.query/media.meta 等）或 search_media 的结果，禁止虚构 id；" +
            "禁止 file://、content:// 路径——端侧会把 media:// 重写为白名单通道注入）。" +
            "取数脚本 return 只回统计摘要——ids 只用于拼接 media:// 引用内联进 html。" +
            "宽度自适应容器（width:100%，不写死超过卡片宽度的固定 px）；总大小不超过 100KB。" +
            "卡片有两种展示形态（display 参数）：inline（默认）在聊天流内完全撑开、卡内直接交互，" +
            "内容高度建议不超过【HTML 卡片渲染环境】段的建议值；" +
            "fullpage 在聊天流内只显示固定高上半部预览（底部渐隐），用户点击后进全屏查看器交互——" +
            "长报告/多屏图文/为全屏设计的交互页必须传 display=\"fullpage\"，" +
            "并把标题/导航/关键交互设计在内容顶部（用户先看到的是上半部预览）；" +
            "未声明 fullpage 但内容超过一屏的卡会被端侧强制转为预览形态。" +
            "风格要求：简洁专业（白/浅灰底 + 中性深灰文字 + 单一强调色，系统字体栈，留白充足，" +
            "圆角 8~12px 配细边框/浅阴影，图表细线少网格，不用 emoji 当图标、不用花哨渐变）。" +
            "summary 填你对卡片内容的一句话文字总结（会回传给你组织回复，并用作全屏查看器标题）。",
    ) {
        override suspend fun execute(args: RenderHtmlArgs): String =
            ChatToolService.getInstance().renderHtml(args.html, args.summary, args.display)
    }

    /**
     * load_skill（2026-11-08 端侧执行器 iOS 接线）：name/description/参数描述引用
     * [AgentSkillContract] const（与 @Tool 真源同源，零漂移面）；执行端 =
     * [LoadSkillCapability]（组合根注册），正文来自 SkillRepository 缓存 + 单点穿透。
     */
    private class LoadSkillTool : SimpleTool<LoadSkillArgs>(
        argsType = typeToken<LoadSkillArgs>(),
        name = AgentSkillContract.TOOL_LOAD_SKILL,
        description = AgentSkillContract.DESC_LOAD_SKILL,
    ) {
        override suspend fun execute(args: LoadSkillArgs): String =
            ChatToolService.getInstance().loadSkill(args.name)
    }

    /** 10 个工具实例（stateless，共享一份即可；registry 与 descriptors 同源派生）。 */
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
            RenderHtmlTool(),
            LoadSkillTool(),
        )
    }

    /** system prompt 清单段用的描述元数据（与 [tools] 同源，零漂移）。 */
    fun buildDescriptors(): List<ToolDescriptor> = tools.map { it.descriptor }
}
