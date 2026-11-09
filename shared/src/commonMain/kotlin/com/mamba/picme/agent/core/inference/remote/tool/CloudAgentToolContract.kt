package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 云端 Agent 端侧工具契约（ADR-018 M3 收口）——tool_request 帧的三端共享常量与纯函数。
 *
 * 三方消费：
 * - Android `DeviceToolExecutor`（androidApp `core/agenttools/`）；
 * - iOS `IosCloudAgentToolBridge`（shared iosMain）；
 * - server `DeviceToolService`（分层 runtime M1 起经 composite build 真引用本对象
 *   const——工具名与 DESC_* 描述；注解实参需编译期常量，const val 跨模块合法）。
 *
 * Swift 侧 `CloudAgentChatStore.errorPrefix` 同为字面量副本（SKIE 不消费本对象），同步约束同上。
 */
object CloudAgentToolContract {

    const val TOOL_GET_GALLERY_SUMMARY = "get_gallery_summary"
    const val TOOL_SEARCH_MEDIA = "search_media"
    const val TOOL_REFINE_MEDIA_SEARCH = "refine_media_search"
    const val TOOL_RUN_GALLERY_SCRIPT = "run_gallery_script"

    /**
     * ⚠️ 已退役（2026-11-05 A2UI 退役/GenUI 唯一化）——发射端（server）不再注册此工具，
     * 端侧解析分支已随 A2UI 渲染线删除。常量保留仅供步骤时间线（AgentStepTimeline）
     * 展示历史 action 帧；CLOUD 目录条目已随退役自 [CloudAgentToolCatalog] 删除。
     */
    const val TOOL_RENDER_UI = "render_ui"

    /**
     * ⚠️ 已退役（2026-11-01 turn-native only）——发射端（server）不再注册此工具。
     * 常量保留供步骤时间线（AgentStepTimeline）展示历史 action 帧，与
     * CloudAgentTurnRunner 历史帧解析（服务端回滚兼容：旧服务端仍可能下发 render_ui_openui）。
     */
    const val TOOL_RENDER_UI_OPENUI = "render_ui_openui"

    /**
     * ⚠️ 已退役（2026-11-05 GenUI 唯一化，云端 render_html 工具随 H5 线退役）——
     * 发射端（server）不再注册此工具。常量保留仅供 androidApp 步骤时间线
     * （AgentStepTimeline）展示历史 action 帧；CLOUD 目录条目已随退役自
     * [CloudAgentToolCatalog] 删除。
     */
    const val TOOL_RENDER_HTML = "render_html"

    /** 失败前缀（契约：结果 hasPrefix(ERROR_PREFIX) → ok=false；服务端/端侧/TurnRunner 同口径）。 */
    const val ERROR_PREFIX = "error:"

    /** 结果截断上限（字符，非字节——字节截断会切坏 UTF-8）。 */
    const val MAX_RESULT_CHARS = 32_000

    /** 截断后缀（契约逐字）。 */
    const val TRUNCATED_SUFFIX = "\n…[truncated]"

    /** 未知工具结果（写类工具不在云端映射表——云端会话里即未知工具）。 */
    fun unknownTool(tool: String): String = "$ERROR_PREFIX unknown_tool:$tool"

    /** 结果是否失败（postToolResult 的 ok 判定口径）。 */
    fun isError(result: String): Boolean = result.startsWith(ERROR_PREFIX)

    /** 字符截断（String.take 即字符语义，不切半 UTF-8）。 */
    fun truncate(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text
        else text.take(MAX_RESULT_CHARS) + TRUNCATED_SUFFIX

    // ── 云端 LLM 可见描述 SSOT（2026-11-07 分层 runtime M1，自 server DeviceToolService
    // 逐字收口）── M2 起由 CloudAgentToolCatalog 条目字段真引用（server 工具桥已改
    // 目录驱动的编程式工具，@Tool/@LLMDescription 注解消费方退役）。投影承诺面与
    // CloudAgentToolCatalog.projectionSchema 同源；改描述只改本区，禁止任何一侧持有字面量副本。

    const val DESC_GET_GALLERY_SUMMARY =
        "获取用户手机相册摘要（照片/视频总数、人物聚类数、已打标数等）。工具在用户设备上执行，结果以文本摘要回传（投影：仅计数与文本元数据，不含文件路径与媒体清单）。"

    const val DESC_SEARCH_MEDIA =
        "在用户手机相册搜索照片/视频，结果以文本摘要回传（投影：仅数量+命中说明+尾部 opaque 数据句柄「handle: scan/<id>」（供 OpenUI 卡数据绑定（Query local_handle），禁止臆造 handleId），不含文件路径与媒体清单）。query 自然语言搜索词（留空=全相册所有媒体，统计全库时直接留空即可）；person 人物分组名或称谓（无则空串）；fromMs/toMs 毫秒时间串（query 含时间词时必须换算填入，空串=不限）；hasFace 传 'true'=只要含人脸照片（空串=不限）；mediaType 媒体类型：'photo'=只搜照片（默认）、'video'=只搜视频（用户提到视频/录像/vlog 时必传）、'all'=照片+视频。浏览照片的组织流程见 photo-browse 技能。"

    const val DESC_REFINE_MEDIA_SEARCH =
        "在上一轮 search_media 结果内细化过滤（如'只要夜景'），结果以文本摘要回传（投影：仅数量+尾部 opaque 数据句柄「handle: scan/<id>」（供 OpenUI 卡数据绑定（Query local_handle），禁止臆造 handleId），不含文件路径与媒体清单）。constraint 细化条件；fromMs/toMs 毫秒串（空串=不限）。上一轮无搜索基数时设备会返回错误，此时改用 search_media。"

    const val DESC_PARAM_QUERY = "自然语言搜索词"
    const val DESC_PARAM_PERSON = "人物分组名或称谓，无则空串"
    const val DESC_PARAM_FROM_MS = "时间起点毫秒串，空串=不限"
    const val DESC_PARAM_TO_MS = "时间终点毫秒串，空串=不限"
    const val DESC_PARAM_HAS_FACE = "'true'=只要含人脸；空串=不限"
    const val DESC_PARAM_MEDIA_TYPE = "'photo'=只搜照片（默认）；'video'=只搜视频；'all'=照片+视频"
    const val DESC_PARAM_CONSTRAINT = "细化条件"

    /**
     * 云端面 run_gallery_script（2026-11-08 补录）：只读取数面——月度趋势/标签分布等
     * 明细聚合此前云端无取数通路（2026-11-08 真机事故：LLM 被迫臆造月度数组画 ECharts
     * 趋势卡）。描述与端侧 chat 面（ChatToolService/RemoteControlToolService）按 agent
     * 故意差异化：云端不写 capability.dispatch（写通路不暴露给云端会话）。
     */
    const val DESC_RUN_GALLERY_SCRIPT =
        "在用户设备的端侧 JS 沙箱执行只读取数脚本做相册统计聚合，仅 return 的聚合结果回传（投影：仅聚合后小结果对象，不含文件路径/uri 与媒体清单——命中即被隐私闸拦截）。所有 handler 均为异步：必须用 await bridge.callAsync(name, args) 调用（bridge.call 已禁用）。可用 handler：gallery.summary({}) → 相册聚合统计（totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/labeledCount 等）；gallery.timeline({fromMs?,toMs?,bucketMs?}) → 按时间分桶计数 {桶起始时间戳:数量}（默认按月，bucketMs=2592000000=月/31536000000=年——月度/年度趋势的唯一真实取数通路）；gallery.query({label?,ocr?,location?,fromMs?,toMs?,hasFace?,limit?}) → 结构化过滤命中 {ids,total}；gallery.tags({}) → 标签分布 {标签:数量}（top 50）；gallery.stats_by_city({topN?}) → 城市分布；gallery.stats_by_tag({label?,hasFace?,fromMs?,toMs?}) → 条件过滤后标签分布。在 JS 内完成聚合计算（如月度计数、占比、环比），return 小结果对象（计数/分布/opaque id，非原始清单）。画 ECharts 图表前必须用本工具取真实数据，禁止臆造数值。画交互图表（ECharts）的唯一方式：脚本末尾 return Chart.echarts({title?, x:[类目标签], series:[{name,data,type?'bar'|'line',color?}], unit?, tip?, theme?:'fresh'(默认)/'ocean'/'sunset'/'violet'/'forest', style?:{palette/height等字段覆盖主题}})——固定模板自动渲染成图卡上屏（HTML 不回传给你，勿手写任何 HTML/ECharts 代码），你只收到 summary 文字用于撰写分析。注意：图卡已自动上屏，你的回复不得再包含 Html 组件或重复图卡，只写分析文字。完整 handler 参考见 gallery-script 技能。"

    const val DESC_PARAM_CODE =
        "JS 源码；用 await bridge.callAsync 取数，return 聚合后的小结果对象（计数/分布，非原始清单）"
}
