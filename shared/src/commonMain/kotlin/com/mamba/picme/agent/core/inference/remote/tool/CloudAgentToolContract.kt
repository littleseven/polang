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
        "在用户手机相册搜索照片/视频，结果以文本摘要回传（投影：仅数量+命中说明+尾部 opaque 数据句柄「handle: scan/<id>」（供 OpenUI 卡数据绑定（Query local_handle），禁止臆造 handleId），不含文件路径与媒体清单）。query 自然语言搜索词（留空=全相册所有媒体，统计全库时直接留空即可）；person 人物分组名或称谓（无则空串）；fromMs/toMs 毫秒时间串（query 含时间词时必须换算填入，空串=不限）；hasFace 传 'true'=只要含人脸照片（空串=不限）；mediaType 媒体类型：'photo'=只搜照片（默认）、'video'=只搜视频（用户提到视频/录像/vlog 时必传）、'all'=照片+视频。【display 判定——浏览/看照片（「看看」「给我看看」「最近拍了什么」「找些…的照片」等）一律 'false' 取数 + OpenUI PhotoGrid 卡展示（照片网格 + 统计 + 分组头 = 更好的浏览体验）；仅纯计数/取数（统计、为出卡取数）也传 'false'。'true'（横滑图集卡）仅限快速单次查询（如精确找某一张、快速核对存在性），不是默认展示方式。】"

    const val DESC_REFINE_MEDIA_SEARCH =
        "在上一轮 search_media 结果内细化过滤（如'只要夜景'），结果以文本摘要回传（投影：仅数量+尾部 opaque 数据句柄「handle: scan/<id>」（供 OpenUI 卡数据绑定（Query local_handle），禁止臆造 handleId），不含文件路径与媒体清单）。constraint 细化条件；fromMs/toMs 毫秒串（空串=不限）。上一轮无搜索基数时设备会返回错误，此时改用 search_media。"

    const val DESC_PARAM_QUERY = "自然语言搜索词"
    const val DESC_PARAM_PERSON = "人物分组名或称谓，无则空串"
    const val DESC_PARAM_FROM_MS = "时间起点毫秒串，空串=不限"
    const val DESC_PARAM_TO_MS = "时间终点毫秒串，空串=不限"
    const val DESC_PARAM_HAS_FACE = "'true'=只要含人脸；空串=不限"
    const val DESC_PARAM_DISPLAY = "浏览照片='false'+OpenUI PhotoGrid 卡；仅快速单查='true'。默认 'false'"
    const val DESC_PARAM_MEDIA_TYPE = "'photo'=只搜照片（默认）；'video'=只搜视频；'all'=照片+视频"
    const val DESC_PARAM_CONSTRAINT = "细化条件"
}
