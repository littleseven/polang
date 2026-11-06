package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 云端 Agent 端侧工具契约（ADR-018 M3 收口）——tool_request 帧的三端共享常量与纯函数。
 *
 * 三方消费：
 * - Android `DeviceToolExecutor`（androidApp `core/agenttools/`）；
 * - iOS `IosCloudAgentToolBridge`（shared iosMain）；
 * - server `DeviceToolService`（Ktor，`@Tool(customName=…)` 注解需编译期常量且 server
 *   不依赖 :shared，持字面量副本——改动本文件任一常量必须同步核对 server 侧）。
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
}
