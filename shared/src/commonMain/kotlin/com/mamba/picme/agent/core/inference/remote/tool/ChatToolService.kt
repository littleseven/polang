package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.agent.core.inference.remote.log.TraceIdAware
import com.mamba.picme.agent.core.inference.remote.log.TraceIdHolder
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout

/**
 * chat 场景专用 ToolService（远程 ReAct，Koog 驱动）。
 *
 * 与 [RemoteControlToolService]（飞书远程控制 RPA：UI 操作 + 相机）区分：本类只暴露 **chat 场景可用**
 * 的能力命令（相册搜索/摘要、打标、AI 修图、反馈、设置、导航、JS 脚本），不含 UI 自动化与
 * 相机控制（相机 Capability 属 CAMERA 场景，chat 场景下 dispatch 不可用）。
 *
 * 每个 @Tool 是 [dispatchCommand] 的薄封装：命令统一进 [CapabilityRegistry]（scene=CHAT），
 * 复用既有 chat Capability（ChatSearchCapability/ChatGallerySummaryCapability/ChatRunScriptCapability 等）。
 *
 * 路由定位见 `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` §2.4（chat ReAct 入口）。与
 * [RemoteControlToolService] 同名但描述不同的工具（如 `run_gallery_script`）是按 agent 故意差异化，
 * 非漂移；逐字节相同的描述（如 `draw_chart`）抽到 [GalleryToolDocs] 共享。
 *
 * **Koog 工具表面**：用 Koog `@Tool(customName=...)`（保 LLM-facing 工具名，确定性）
 * + 方法级 `@LLMDescription`（工具描述）+ 每参数 `@LLMDescription`（参数描述）。组合根
 *（Android/JVM 侧）经 Koog 反射（`asToolsByClass()`，与 `reflect.ToolSet.asTools()` 同一
 * 扫描函数）直接派发 @Tool 方法——`reflect.ToolSet` 标记接口是 Koog 1.1.1 jvmCommonMain API，
 * commonMain 不可引用，故本类不实现它（KMP 抽取 Task 7）。**无需** langchain4j 期的
 * `callTool(toolName, argsJson)` 手写 when 分发。
 *
 * **并发模型（KMP 抽取 Task 7 suspend 化）**：@Tool 方法为 suspend（Koog 1.1.1 支持 suspend
 * 工具函数），dispatch 从 `future{}.get(5s)` 阻塞桥改写为 `withTimeout(5s)` 结构化等待，
 * 超时抛 `TimeoutCancellationException`（语义对齐旧 `java.util.concurrent.TimeoutException` 分支）；
 * `adjust_image` 的 `runBlocking` 桥同步删除（handler 本就 suspend）。
 *
 * **隐私不变式（决策1 / ADR-008）**：本 ToolService 运行在远程 ReAct 链路上，但其 @Tool 执行的
 * 媒体处理（`ai_optimize`/`edit_image`/`adjust_image`/打标/人脸）均在**端侧** renderer/本地模型完成，
 * **图片/视频字节绝不作为多模态输入上传给远程 LLM**；返回给模型的是纯文本 observation
 * （如「图片已优化，结果已展示在聊天中」）。受 `RemoteInferenceNoMediaUploadGuardTest` 守卫保护。
 *
 * **重要**：@Tool 参数**不用 Kotlin 默认值**——Koog 经 Kotlin 反射派发，默认值会生成 DefaultConstructorMarker
 * 合成方法；为保 R8/反射稳健（与 [RemoteControlToolService] 一致），所有参数必填，可选语义用空串由
 * 调用方传入（@LLMDescription 描述说明）。
 */
class ChatToolService private constructor() : TraceIdAware {

    companion object {
        /** dispatch 等待超时（毫秒），语义对齐旧 `future{}.get(5, SECONDS)`。 */
        private const val DISPATCH_TIMEOUT_MS = 5000L

        // KMP commonMain 无 synchronized，lazy 默认 SYNCHRONIZED 模式保证同款线程安全单例语义
        private val singleton: ChatToolService by lazy { ChatToolService() }
        fun getInstance(): ChatToolService = singleton
    }

    private val tag = "ChatToolService"

    /** UI 事件流：dispatchCommand 执行后的原始 AgentAction 发到此 flow，ChatViewModel collect 渲染卡片/跳转。 */
    val uiActions = MutableSharedFlow<AgentAction>(extraBufferCapacity = 16)

    /**
     * [uiActions] 的只读视图（SKIE spike S5）：SKIE 不转换 MutableSharedFlow 属性，
     * 只读 SharedFlow 才会生成 Swift `AsyncSequence` 包装（`for await` 直消费）。
     * Swift 侧订阅一律用此属性；`uiActions` 保留为 Kotlin 内部发射口。
     */
    val uiActionsReadOnly: SharedFlow<AgentAction> get() = uiActions

    /**
     * 当轮 traceId 持有器：由组合根自 [com.mamba.picme.agent.core.inference.remote.koog.KoogChatAgent]
     *（chat 链路 Phase 4 起）的 traceIdHolder 接线写入，dispatchCommand 读取后注入 AgentContext，
     * 使 chat 远程 ReAct 路径下的 tool（含 JS 脚本）执行也带 traceId，与 LLM 调用关联。
     */
    @Volatile
    override var traceIdHolder: TraceIdHolder? = null

    /**
     * 指令驱动图片调整 handler（由 ChatViewModel 注入）。
     *
     * 参数：imageUri, brightness(-100~100), contrast(0~200, 默认50), saturation(0~200, 默认100), temperature(2000~8000, 默认5000)
     * 返回：结果描述（成功时含 file:// URI；失败时含错误信息）
     */
    var adjustImageHandler: (suspend (String, Float?, Float?, Float?, Float?) -> String)? = null

    // ── 相册 ──────────────────────────────────────────────────────

    @Tool(customName = "get_gallery_summary")
    @LLMDescription("获取本地相册摘要：照片/视频/媒体总数、含人脸数、人物聚类数、已/未打标数、语义向量数、扫描建议。")
    suspend fun getGallerySummary(): String =
        dispatchCommand(AgentCommand.GetGallerySummary(includeDetails = false))

    @Tool(customName = "search_media")
    @LLMDescription("搜索本地相册，**结果以横滑卡片直接展示给用户**（调用后无需再调其它工具展示，如实总结数量即可）。query 为自然语言搜索词，如'去年夏天海边的小孩'。人物精确查询用 person 传人物分组名或称谓（如'大宝''儿子'），并可与 fromMs/toMs 组合做「人物 ∩ 时间」精确交集——不要把人物名只拼进 query（会丢人物维度，误回他人照片）。")
    suspend fun searchMedia(
        @LLMDescription("自然语言搜索词") query: String,
        @LLMDescription("人物分组名或称谓（如'大宝''儿子'），无则空串") person: String,
        @LLMDescription("时间起点（毫秒，据当前日期算）；空串=不限") fromMs: String,
        @LLMDescription("时间终点（毫秒）；空串=不限") toMs: String
    ): String {
        val intent = structuredSearchIntent(query, person, fromMs, toMs)
        return dispatchCommand(AgentCommand.SearchMedia(query = query, intent = intent))
    }

    @Tool(customName = "refine_media_search")
    @LLMDescription("在上一轮搜索结果内细化过滤，**结果以横滑卡片更新展示**。如'只要夜景''找找4月的'。constraint 为细化条件；时间窄化务必传 fromMs/toMs（毫秒，据当前日期算）做精确交集，留空串=不限。上一轮无搜索基数时会返回明确错误，此时改用 search_media 重新全局搜索。")
    suspend fun refineMediaSearch(
        @LLMDescription("细化条件") constraint: String,
        @LLMDescription("时间起点（毫秒），如某月起始；空串=不限") fromMs: String,
        @LLMDescription("时间终点（毫秒），如某月末；空串=不限") toMs: String
    ): String {
        val intent = refineTimeIntent(fromMs, toMs)
        return dispatchCommand(AgentCommand.RefineMediaSearch(constraint = constraint, intent = intent))
    }

    // 写操作确认两层策略·Tier B：顶层 @Tool 直调写操作不经应用内确认（区别于 JS
    // capability.dispatch 的 Tier A——后者经 CapabilityDispatchHandler + WriteConfirmationController
    // 带预览确认）。删除由系统 MediaStore 授权框兜底、ReAct 循环对用户透明。分级 SSOT 见 CommandRisk。
    @Tool(customName = "delete_media")
    @LLMDescription("删除媒体。mediaIds 为 id 列表逗号分隔，无则空串。")
    suspend fun deleteMedia(
        @LLMDescription("媒体 id 列表逗号分隔，无则空串") mediaIds: String
    ): String {
        val ids = mediaIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return dispatchCommand(AgentCommand.DeleteMedia(mediaIds = ids))
    }

    @Tool(customName = "share_media")
    @LLMDescription("分享媒体。mediaIds 为 id 列表逗号分隔，无则空串。")
    suspend fun shareMedia(
        @LLMDescription("媒体 id 列表逗号分隔，无则空串") mediaIds: String
    ): String {
        val ids = mediaIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return dispatchCommand(AgentCommand.ShareMedia(mediaIds = ids))
    }

    @Tool(customName = "select_media")
    @LLMDescription("选择/取消选择媒体。selected 为 true 选中 / false 取消。")
    suspend fun selectMedia(
        @LLMDescription("媒体 id") mediaId: String,
        @LLMDescription("true 选中 / false 取消") selected: Boolean
    ): String = dispatchCommand(AgentCommand.SelectMedia(mediaId = mediaId, selected = selected))

    @Tool(customName = "favorite_media")
    @LLMDescription("收藏/取消收藏媒体。favorite 为 true 收藏 / false 取消。")
    suspend fun favoriteMedia(
        @LLMDescription("媒体 id") mediaId: String,
        @LLMDescription("true 收藏 / false 取消") favorite: Boolean
    ): String = dispatchCommand(AgentCommand.FavoriteMedia(mediaId = mediaId, favorite = favorite))

    @Tool(customName = "switch_view_mode")
    @LLMDescription("切换相册视图。mode: grid(网格)/list(列表)。")
    suspend fun switchViewMode(
        @LLMDescription("视图模式：grid/list") mode: String
    ): String = dispatchCommand(AgentCommand.SwitchViewMode(mode = mode))

    // ── 反馈 ──────────────────────────────────────────────────────

    @Tool(customName = "record_feedback")
    @LLMDescription("记录用户对搜索结果的反馈。action: like/dislike。target: last(上次结果)/ordinal:N(第N张)/desc:描述/mediaId:id。")
    suspend fun recordFeedback(
        @LLMDescription("反馈目标：last / ordinal:N / desc:文本 / mediaId:id") target: String,
        @LLMDescription("like 或 dislike") action: String
    ): String = dispatchCommand(
        AgentCommand.RecordMediaFeedback(
            target = parseFeedbackTarget(target),
            action = parseFeedbackAction(action)
        )
    )

    @Tool(customName = "more_like_this")
    @LLMDescription("基于指定图片推荐更多相似照片。target 同 record_feedback。")
    suspend fun moreLikeThis(
        @LLMDescription("目标：last / ordinal:N / desc:文本 / mediaId:id") target: String
    ): String = dispatchCommand(AgentCommand.MoreLikeThis(target = parseFeedbackTarget(target)))

    @Tool(customName = "exclude_constraint")
    @LLMDescription("在后续搜索中排除某类约束，如'不要夜景'。constraint 为排除条件。")
    suspend fun excludeConstraint(
        @LLMDescription("排除条件") constraint: String
    ): String = dispatchCommand(AgentCommand.ExcludeConstraint(constraint = constraint))

    // ── 打标 / 修图 ───────────────────────────────────────────────

    @Tool(customName = "start_tag_scan")
    @LLMDescription("查询 TAG 扫描状态（人脸/标签/语义索引进度）。无需参数。")
    suspend fun startTagScan(): String =
        dispatchCommand(AgentCommand.StartTagScan(action = "query", taskType = null, mode = null))

    @Tool(customName = "ai_optimize")
    @LLMDescription("AI 一键优化图片。imageUri 为图片 URI。")
    suspend fun aiOptimize(
        @LLMDescription("图片 URI") imageUri: String
    ): String = dispatchCommand(AgentCommand.AiOptimize(imageUri = imageUri))

    @Tool(customName = "adjust_image")
    @LLMDescription("按显式参数调整图片亮度/对比度/饱和度/色温，返回调整后的图片。用户说「调亮」「增加对比度」「提高饱和度」等指令时使用。brightness: -100(暗)~100(亮)，0=不变。contrast: 0~200，50=默认。saturation: 0~200，100=默认。temperature: 2000(冷蓝)~8000(暖黄)，5000=默认。未指定的参数留空串表示不调整。")
    suspend fun adjustImage(
        @LLMDescription("图片 URI") imageUri: String,
        @LLMDescription("亮度 -100~100，0=不变，留空=不调") brightness: String,
        @LLMDescription("对比度 0~200，50=默认，留空=不调") contrast: String,
        @LLMDescription("饱和度 0~200，100=默认，留空=不调") saturation: String,
        @LLMDescription("色温 2000(冷)~8000(暖)，5000=默认，留空=不调") temperature: String
    ): String {
        val handler = adjustImageHandler ?: return "Error: 图片调整暂不可用"
        val b = brightness.toFloatOrNull()
        val c = contrast.toFloatOrNull()
        val s = saturation.toFloatOrNull()
        val t = temperature.toFloatOrNull()
        // suspend 化（Task 7）：handler 本就 suspend，旧 runBlocking 桥随删。
        return handler.invoke(imageUri, b, c, s, t)
    }

    @Tool(customName = "edit_image")
    @LLMDescription("对话式图片编辑（美颜/滤镜/调色），后台完成并把结果图发到聊天中，**绝不跳转到编辑页**。用户说「磨皮 30」「瘦脸」「美白一点」「换胶片风」「再亮一点」等编辑意图时使用（含 adjust_image 覆盖不了的场景）。edits 为 JSON 字符串，字段（均可选，只传要改的）：smoothing/whitening/big_eyes/lip_color/blush（美颜 0~100）、slim_face（-50~50）、brightness/exposure（-100~100）、contrast/saturation（0~200，100 为原图）、temperature（色温开尔文 2000~8000，5000 为原图，越大越暖）、tint（-100~100）、filter_name（滤镜枚举，如 FILM_GOLD/COOL）、filter_intensity（0~100）、style_name（风格枚举）。**模糊/相对调整（「美白一点」「再亮一点」）必须用 *_delta 字段且幅度要小**：美颜 ±10 以内、亮度/曝光 ±15 以内、对比度/饱和度 ±15 以内、色温 ±500 以内、tint ±15 以内、slim_face ±5 以内（超出会被截断）；如 {\"brightness_delta\":10} 表示再亮一点。只有用户明确给出数值（「磨皮 50」）才用绝对值字段。不支持的编辑（消除物体/局部美颜）不要编造参数，在 explanation 返回 [unsupported:erase] 或 [unsupported:local_beauty]。")
    suspend fun editImage(
        @LLMDescription("目标图片 URI，留空串表示用最近发送的图片") imageUri: String,
        @LLMDescription("编辑参数 JSON 字符串，如 {\"smoothing\":30,\"filter_name\":\"FILM_GOLD\",\"filter_intensity\":70}") edits: String,
        @LLMDescription("给用户的一句话说明；未支持请求填 [unsupported:erase] 或 [unsupported:local_beauty]，无则留空串") explanation: String
    ): String {
        val params = try {
            EditParams.fromJson(edits.ifBlank { "{}" })
        } catch (e: Exception) {
            return "Error: edits JSON 解析失败: ${e.message}"
        }
        return dispatchCommand(
            AgentCommand.EditImage(
                params = params,
                imageUri = imageUri,
                explanation = explanation.ifBlank { null }
            )
        )
    }

    @Tool(customName = "run_gallery_script")
    @LLMDescription("在端侧沙箱执行 JavaScript 做相册盘点/统计分析（取数类 handler 只读、数据不出端；删除/收藏等写操作走 capability.dispatch，会弹窗经用户确认）。所有 handler 均为异步，**必须用 await bridge.callAsync(name, args) 调用**（bridge.call 已禁用，调用会报错）。可用 handler： gallery.summary → 相册聚合统计（totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation）； gallery.query({label?,ocr?,location?,fromMs?,toMs?,hasFace?,person?,limit?}) → 结构化过滤命中，返回 {ids:[...], total:N}（多维 AND，全可选；ids 已截断到 limit，total 为未截断真实数）； gallery.tags → 实际打标标签分布 {标签:照片数}（按计数降序 top 50）； gallery.timeline({fromMs?,toMs?,bucketMs?}) → 按时间分桶统计 {\"桶起始时间戳\":照片数}（默认按月，bucketMs=2592000000=月/31536000000=年）； gallery.intersect({idsA:[...],idsB:[...],op:\"intersect|union|diff\"}) → 集合交并差，返回 {ids:[...],total:N}（用于多次 query 结果交叉，如旅行+人脸）； media.meta(id) → 单张元数据 {id,type,captureMs,fileName,labels:[...],locationName,city,hasFace,faceId,aestheticScore,faceQualityScore}（不含路径/GPS/OCR/向量）； media.batch_meta([id1,id2,...]) → 批量元数据 [{...},...]（上限 50，避免循环调 media.meta）； gallery.stats_by_tag({label?,hasFace?,fromMs?,toMs?}) → 条件过滤后的标签分布（如人像照片内的场景标签）； face.cluster({topN?}) → 人脸聚类盘点 {clusterCount,namedCount,totalEmbeddings,unassignedEmbeddings,topPersons:[{personId,name,faceCount,coverMediaId}]}（topN 默认 10 上限 50，不含 embedding 原始数据）； tag.audit({topN?}) → 打标覆盖审计 {totalMedia,unlabeledCount,neverScannedCount,lastScanAt,outOfVocabTags:{标签:照片数}}（词表外标签 topN 默认 10 上限 50）； gallery.stats_by_city({topN?}) → 按城市分组的媒体计数分布 {城市:照片数}（topN 默认 10 上限 50）； tag.scan_status({}) → TAG 扫描会话状态快照 {active,state,sessionId,currentPass,processed,total,pending,failed,estimatedRemainingMs}（只读查询，绝不触发扫描；无会话时仅回 {active:false,state:null}）。 可并发取数：var r=await Promise.all([bridge.callAsync('gallery.summary',{}),bridge.callAsync('gallery.tags',{})]); var s=r[0],t=r[1]; 在 JS 内组合计算（如某标签占比 = query.total / summary.totalMedia；环比 = 本月/上月-1），return 结果对象回传给你做总结。 示例：var s=await bridge.callAsync('gallery.summary',{}); var t=await bridge.callAsync('gallery.tags',{}); return {total:s.totalMedia, topTags:t}; 写操作（删除/收藏/选中）：用 await bridge.callAsync('capability.dispatch',{method,params})，写操作会在端侧弹窗等用户确认（拒绝或超时 Promise 会 reject，必须 try/catch）。支持的 method： delete_media {ids:[数字id,...]}（删除，不可恢复）、favorite_media {id:数字id, favorite:true/false}、select_media {id:数字id, selected:true/false}、remember_fact {content:文本, category?:文本}、forget_fact {fact_id?:数字id, query?:文本}、remember_person_relation {name:人物名, relation:关系称谓}、forget_person_relation {name:人物名}、get_gallery_summary {}、recall_memory {query:文本}、query_person_relation {name?:人物名}（后三者只读直通）；其余 method 会报错。 完整示例（找出截图标签照片并批量删除）：var q=await bridge.callAsync('gallery.query',{label:'截图',limit:200}); if(q.ids.length===0){return {deleted:0};} try{var r=await bridge.callAsync('capability.dispatch',{method:'delete_media',params:{ids:q.ids}}); return {deleted:q.total, result:r};}catch(e){return {deleted:0, cancelled:true, reason:String(e)};} UI 效果：本工具自身只回传数据/文本给你总结，不直接产生界面；但 return 的对象若含 ids 数组（gallery.query/intersect 的命中），端侧会自动把这些照片补展示为横滑卡片——纯统计/盘点脚本不要 return ids，需要给用户看图时优先用 search_media（直接出卡片）。")
    suspend fun runGalleryScript(
        @LLMDescription("JS 源码；用 await bridge.callAsync 取数据（gallery.summary/tags/timeline/query/stats_by_tag/stats_by_city/intersect, media.meta/batch_meta, face.cluster, tag.audit, tag.scan_status）；写操作（删除/收藏/选中/记忆）用 await bridge.callAsync('capability.dispatch',{method,params})（会弹窗等用户确认，需 try/catch 处理拒绝），return 结果对象") code: String
    ): String = dispatchCommand(AgentCommand.ExecuteScript(code = code))

    @Tool(customName = "draw_chart")
    @LLMDescription(GalleryToolDocs.DRAW_CHART)
    suspend fun drawChart(
        @LLMDescription("图表类型：bar(柱状)/line(折线)/pie(饼图)") type: String,
        @LLMDescription("图表标题") title: String,
        @LLMDescription("分类/x 轴标签，英文逗号分隔，如 '1月,2月,3月' 或 '人像,风景,美食'") labels: String,
        @LLMDescription("每个标签对应的数值，英文逗号分隔，与 labels 等长，如 '12,8,21'") values: String,
        @LLMDescription("数值单位，如 '张'；无则空串") unit: String
    ): String {
        val labelList = labels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val valueList = values.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        return dispatchCommand(
            AgentCommand.DrawChart(
                type = type,
                title = title,
                labels = labelList,
                values = valueList,
                unit = unit.ifBlank { null }
            )
        )
    }

    @Tool(customName = "render_html")
    @LLMDescription(
        "渲染一个 HTML 组件卡片插入聊天（端侧 WebView 渲染，支持内联 CSS/JS 动画与交互）。" +
            "仅当用户明确要求更丰富的展示或交互组件（如可交互图表、动画演示、自定义布局卡片）时才调用；" +
            "柱/折/饼统计图一律仍用 draw_chart，不要用本工具替代。" +
            "html 的 CSS/JS 一律内联；远程 script、iframe/object/embed/form、meta refresh 会被清洗剔除，" +
            "fetch/XHR 也不可用（离线沙箱）。" +
            "鼓励引用远程富媒体提升表现力：<img> 网络图片、<video>/<audio> 远程媒体、远程 CSS 样式、" +
            "<a> 外链均可使用（a 外链用户点击后在全屏落地页打开）；" +
            "URL 务必用确定可访问的真实地址（优先官方/权威站点素材），不要编造域名。" +
            "宽度自适应容器（width:100%，不写死超过卡片宽度的固定 px）；总大小不超过 100KB。" +
            "卡片有两种展示形态（display 参数）：inline（默认）在聊天流内完全撑开、卡内直接交互，" +
            "内容高度建议不超过【HTML 卡片渲染环境】段的建议值；" +
            "fullpage 在聊天流内只显示固定高上半部预览（底部渐隐），用户点击后进全屏查看器交互——" +
            "长报告/多屏图文/为全屏设计的交互页必须传 display=\"fullpage\"，" +
            "并把标题/导航/关键交互设计在内容顶部（用户先看到的是上半部预览）；" +
            "未声明 fullpage 但内容超过一屏的卡会被端侧强制转为预览形态。" +
            "风格要求：简洁专业（白/浅灰底 + 中性深灰文字 + 单一强调色，系统字体栈，留白充足，" +
            "圆角 8~12px 配细边框/浅阴影，图表细线少网格，不用 emoji 当图标、不用花哨渐变）。" +
            "summary 填你对卡片内容的一句话文字总结（会回传给你组织回复，并用作全屏查看器标题）。"
    )
    suspend fun renderHtml(
        @LLMDescription("HTML 源码：CSS/JS 内联；可用远程 <img>/<video>/<audio>/CSS 与 <a> 外链；禁远程 script/iframe/fetch/XHR") html: String,
        @LLMDescription("对卡片内容的一句话文字总结") summary: String,
        @LLMDescription("展示形态：inline（默认，聊天内完全撑开直接交互，内容高度建议 ≤2/3 屏）或 fullpage（长报告/多屏图文/为全屏设计的交互页，聊天内显示固定高预览、点击进全屏）；空串 = inline") display: String
    ): String = dispatchCommand(
        AgentCommand.RenderHtml(
            html = html,
            summary = summary.ifBlank { null },
            display = display.trim().lowercase().ifBlank { null }
        )
    )

    // ── 记忆（人物关系 + 事实） ─────────────────────────────────────

    @Tool(customName = "remember_person_relation")
    @LLMDescription("记住人物与「我」的关系，如用户说「小宝是我女儿」「大宝是我发小」。name 为已命名人物名（须已在相册人物分组命名，未命名会返回引导提示），relation 为关系谓词（spouse/partner/son/daughter/child/father/mother/parent/elder_brother/elder_sister/younger_brother/younger_sister/sibling/grandfather/grandmother/grandparent/other_family/friend/classmate/colleague/other）、中文称谓（女儿/老公/对象等）或任意自定义称呼原话（发小/闺蜜等，按原话记住）。重复声明自动覆盖旧关系。")
    suspend fun rememberPersonRelation(
        @LLMDescription("已命名人物名") name: String,
        @LLMDescription("关系谓词或中文称谓") relation: String
    ): String = dispatchCommand(AgentCommand.RememberPersonRelation(name = name, relation = relation))

    @Tool(customName = "forget_person_relation")
    @LLMDescription("忘记与某人物的全部关系，如用户说「忘掉小宝的关系」。name 为人物名。")
    suspend fun forgetPersonRelation(
        @LLMDescription("人物名") name: String
    ): String = dispatchCommand(AgentCommand.ForgetPersonRelation(name = name))

    @Tool(customName = "list_person_relations")
    @LLMDescription("查询已记住的人物关系。用户问「看一下我的人物关系」「我女儿是谁」「小宝和我什么关系」「我记住了哪些关系」「谁是我的家人」时调用本工具，不要凭印象回答。name 留空返回全部指向「我」的关系；指定人物名只查该人物。返回关系列表（含自定义称呼）。")
    suspend fun listPersonRelations(
        @LLMDescription("人物名，指定则只查该人物与「我」的关系；留空查全部") name: String
    ): String = dispatchCommand(AgentCommand.QueryPersonRelation(name = name.ifBlank { null }))

    @Tool(customName = "remember_fact")
    @LLMDescription("记住一条事实，如用户说「帮我记住小宝对花粉过敏」「记住我喜欢低饱和度滤镜」。content 为原子化事实内容（一条一个事实），category 为可选分类（如 健康/偏好），无则空串。")
    suspend fun rememberFact(
        @LLMDescription("事实内容") content: String,
        @LLMDescription("可选分类，无则空串") category: String
    ): String = dispatchCommand(
        AgentCommand.RememberFact(
            content = content,
            category = category.ifBlank { null },
            source = "CHAT_TOOL"
        )
    )

    @Tool(customName = "forget_fact")
    @LLMDescription("忘记一条事实，如用户说「忘掉花粉过敏那条」。factId 优先（先用 recall_memory 拿到），无则空串；query 为内容模糊匹配（恰好一条才删，多条返回候选）。")
    suspend fun forgetFact(
        @LLMDescription("事实 id，无则空串") factId: String,
        @LLMDescription("内容模糊匹配，无则空串") query: String
    ): String = dispatchCommand(
        AgentCommand.ForgetFact(
            factId = factId.toLongOrNull(),
            query = query.ifBlank { null }
        )
    )

    @Tool(customName = "recall_memory")
    @LLMDescription("system prompt【关于用户】段已含常见记忆，优先直接引用；本工具仅在该段未列全（被截断）或需拿 factId 删除时调用。query 为模糊匹配关键词，空串返回全部。返回列表含 factId，供 forget_fact 精确删除。")
    suspend fun recallMemory(
        @LLMDescription("模糊匹配关键词，空串返回全部") query: String
    ): String = dispatchCommand(AgentCommand.RecallMemory(query = query))

    // ── 设置 ──────────────────────────────────────────────────────

    @Tool(customName = "change_theme")
    @LLMDescription("切换主题。theme: system/light/dark。")
    suspend fun changeTheme(
        @LLMDescription("system/light/dark") theme: String
    ): String = dispatchCommand(AgentCommand.ChangeTheme(theme = theme))

    @Tool(customName = "change_language")
    @LLMDescription("切换语言。language: zh/zh-tw/en/es/fr/system。")
    suspend fun changeLanguage(
        @LLMDescription("zh/zh-tw/en/es/fr/system") language: String
    ): String = dispatchCommand(AgentCommand.ChangeLanguage(language = language))

    @Tool(customName = "toggle_setting")
    @LLMDescription("切换开关型设置。key 为设置键，enabled 为开/关。")
    suspend fun toggleSetting(
        @LLMDescription("设置键") key: String,
        @LLMDescription("true/false") enabled: Boolean
    ): String = dispatchCommand(AgentCommand.ToggleSetting(settingKey = key, enabled = enabled))

    @Tool(customName = "download_model")
    @LLMDescription("下载模型。modelId 为模型标识。")
    suspend fun downloadModel(
        @LLMDescription("模型 id") modelId: String
    ): String = dispatchCommand(AgentCommand.DownloadModel(modelId = modelId))

    @Tool(customName = "switch_face_engine")
    @LLMDescription("切换人脸检测引擎。engine: mediapipe/mnn/mlkit。")
    suspend fun switchFaceEngine(
        @LLMDescription("引擎名") engine: String
    ): String = dispatchCommand(AgentCommand.SwitchFaceEngine(engine = engine))

    // ── 导航 / 系统 ───────────────────────────────────────────────

    @Tool(customName = "navigate_to")
    @LLMDescription("导航到页面。destination: camera/gallery/settings/debug。")
    suspend fun navigateTo(
        @LLMDescription("camera/gallery/settings/debug") destination: String
    ): String = dispatchCommand(AgentCommand.NavigateTo(destination = destination))

    @Tool(customName = "go_back")
    @LLMDescription("返回上一页。")
    suspend fun goBack(): String = dispatchCommand(AgentCommand.GoBack())

    @Tool(customName = "launch_app")
    @LLMDescription("打开外部应用。packageName 或 appName 至少给一个，另一个空串。")
    suspend fun launchApp(
        @LLMDescription("包名，无则空串") packageName: String,
        @LLMDescription("应用名，无则空串") appName: String
    ): String = dispatchCommand(
        AgentCommand.LaunchApp(
            packageName = packageName.ifBlank { null },
            appName = appName.ifBlank { null },
            activityClass = null
        )
    )

    @Tool(customName = "open_system_settings")
    @LLMDescription("打开系统设置页。setting: wifi/bluetooth/location 等。")
    suspend fun openSystemSettings(
        @LLMDescription("设置项") setting: String
    ): String = dispatchCommand(AgentCommand.OpenSystemSettings(setting = setting))

    // ── 通用 ──────────────────────────────────────────────────────

    @Tool(customName = "delay")
    @LLMDescription("等待指定毫秒。delayMs 1~300000。")
    suspend fun delay(
        @LLMDescription("延迟毫秒") delayMs: Long
    ): String = dispatchCommand(
        AgentCommand.Delay(delayMs = delayMs.coerceIn(1, 300000))
    )

    @Tool(customName = "finish")
    @LLMDescription("任务完成时调用，提供完成摘要给用户。")
    fun finish(
        @LLMDescription("给用户的完成摘要") summary: String
    ): String = summary

    // ── 内部：命令分发（复用 RemoteControlToolService.dispatchCommand 范式，scene=CHAT）────

    private suspend fun dispatchCommand(command: AgentCommand): String =
        dispatchCommandWithTrace(command, traceIdHolder?.value)

    /**
     * 带显式 traceId 的命令分发入口（internal）：供意图路由器直执路径（RemoteChatEngine）
     * 复用同一条「dispatch → uiActions 发射 → observation 文本 → 5s 超时」链路，
     * 保证路由直执与 LLM tool_calls 的执行语义完全一致（含审计 traceId 串联）。
     */
    internal suspend fun dispatchCommandWithTrace(command: AgentCommand, traceId: String?): String =
        dispatchCommandDetailed(command, traceId).observation

    /** 直执路径的结构化分发结果：[action] 为 null 表示 dispatch 失败/超时（observation 为 Error 文本）。 */
    internal data class DetailedDispatch(val observation: String, val action: AgentAction?)

    /**
     * [dispatchCommandWithTrace] 的结构化变体：除 observation 外返回真实 [AgentAction]，
     * 供路由直执路径判定成功/失败（失败回落完整 agent loop）并提取结果基数（totalCount）。
     */
    internal suspend fun dispatchCommandDetailed(command: AgentCommand, traceId: String?): DetailedDispatch {
        return try {
            // 结构化等待（替代 future{}.get(5s) 阻塞桥）：超时经协程取消级联终止底层 dispatch。
            val result = withTimeout(DISPATCH_TIMEOUT_MS) {
                CapabilityRegistry.getInstance()
                    .dispatch(command, AgentContext(scene = AgentScene.CHAT, traceId = traceId), null)
            }
            result.fold(
                onSuccess = { action ->
                    // UI 通道：把原始 AgentAction 发给 ChatViewModel 渲染（卡片/跳转等）
                    uiActions.tryEmit(action)
                    // LLM observation：基于真实执行结果生成（而非 "OK"）。
                    // ⚠️ 该文本是模型侧观测（硬编码中文模板），直执路径下绝不可直达用户气泡（I18N）。
                    DetailedDispatch(
                        observation = when (action) {
                            is AgentAction.MediaResults ->
                                "找到 ${action.totalCount} 张「${action.query}」的照片，已展示在卡片中"
                            is AgentAction.TextReply -> action.message
                            is AgentAction.Success -> when (action.command) {
                                is AgentCommand.AiOptimize -> "图片已优化，结果已展示在聊天中"
                                is AgentCommand.EditImage -> "图片已编辑完成，结果图已发到聊天中"
                                else -> "OK"
                            }
                            is AgentAction.Error -> "Error: ${action.message}"
                            else -> "OK: ${action::class.simpleName}"
                        },
                        action = action,
                    )
                },
                onFailure = { DetailedDispatch("Error: ${it.message}", null) },
            )
        } catch (e: TimeoutCancellationException) {
            // 等待 dispatch 5s 超时（语义对齐旧 java.util.concurrent.TimeoutException 分支）：
            // withTimeout 已级联取消底层 dispatch 协程，无协程裸跑。
            // 记调用方视角的等待超时，二者可经 traceId 关联。
            Logger.w(tag, "dispatchCommand wait timed out: ${command::class.simpleName}")
            CommandExecutor.recordDispatchEvent(
                capability = "(chat_tool)",
                commandType = AgentCommand.getMethodName(command),
                success = false,
                errorCode = CommandExecutor.ERROR_CODE_TIMEOUT,
                errorMessage = "dispatch wait timed out after 5s",
                traceId = traceId
            )
            DetailedDispatch("Error: ${e.message}", null)
        } catch (e: CancellationException) {
            // 外部取消（agent cancel）：结构化并发要求透传，不吞为错误字符串。
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "dispatchCommand failed: ${command::class.simpleName}: ${e.message}")
            DetailedDispatch("Error: ${e.message}", null)
        }
    }

    private fun parseFeedbackTarget(target: String): FeedbackTarget = when {
        target == "last" -> FeedbackTarget.LastShown
        target.startsWith("ordinal:") ->
            runCatching { FeedbackTarget.Ordinal(target.removePrefix("ordinal:").toInt()) }
                .getOrDefault(FeedbackTarget.LastShown)
        target.startsWith("desc:") -> FeedbackTarget.Description(target.removePrefix("desc:"))
        target.startsWith("mediaId:") -> FeedbackTarget.MediaId(target.removePrefix("mediaId:"))
        else -> FeedbackTarget.Description(target)
    }

    private fun parseFeedbackAction(action: String): FeedbackAction =
        runCatching { FeedbackAction.valueOf(action.trim().uppercase()) }
            .getOrDefault(FeedbackAction.LIKE)

    /**
     * 把 search_media 的 person/fromMs/toMs 组装成 [SearchIntent]（M1 透传，spec §3.4-1）。
     * 三者全空 → null（走 onSearchMedia 的字符串路径）；任一非空 → 结构化过滤精确执行。
     * person 槽位原样透传（称谓→人物名的消歧由引擎层 PersonQueryResolver / 调用方先做）。
     */
    private fun structuredSearchIntent(
        query: String,
        person: String,
        fromMs: String,
        toMs: String
    ): SearchIntent? {
        val start = fromMs.trim().toLongOrNull()
        val end = toMs.trim().toLongOrNull()
        val personName = person.trim().ifBlank { null }
        if (personName == null && start == null && end == null) return null
        return SearchIntent(
            query = query,
            timeRange = if (start != null || end != null) {
                TimeRange(startMs = start ?: 0L, endMs = end ?: Long.MAX_VALUE)
            } else {
                null
            },
            personName = personName,
        )
    }

    /**
     * 把 refine_media_search 的 fromMs/toMs 解析成 [SearchIntent]（timeRange）。
     * 二者都空 → null（走 onRefineMediaSearch 的字符串路径）；任一非空 → 结构化时间，走精确交集。
     */
    private fun refineTimeIntent(fromMs: String, toMs: String): SearchIntent? {
        val start = fromMs.trim().toLongOrNull()
        val end = toMs.trim().toLongOrNull()
        if (start == null && end == null) return null
        return SearchIntent(
            query = "",
            timeRange = TimeRange(startMs = start ?: 0L, endMs = end ?: Long.MAX_VALUE),
        )
    }
}
