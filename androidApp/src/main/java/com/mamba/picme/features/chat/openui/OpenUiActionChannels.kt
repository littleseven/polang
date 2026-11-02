package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiValue

/**
 * OpenUI 卡动作宿主侧裁定层（2026-11-01 动作三通道官方对齐批次）。
 *
 * 交互去向对齐官方 actions 语义（https://www.openui.com/docs/openui-lang/actions）：
 * - 本地操作 = 官方 @Set/@Reset（状态写）、@OpenUrl、自定义动作 @Navigate（页面跳转）
 * - 访问 API = `Mutation("工具名", {args})` + @Run(ref)——engine 执行宿主注入的
 *   `OpenUiToolResolver`（写工具白名单见 [OpenUiWriteTools]），结果 {status,data,error}
 *   可绑定 UI，失败中断动作链
 * - 访问 Agent = 官方 @ToAssistant(message, context?)
 * 三者非互斥：`Action([...])` 组合顺序执行，整链交宿主分发（@Run 失败即中断）。
 *
 * program 侧只写线名（目的地名 / 工具名），业务映射全部收口本文件——
 * 遵循「枚举优于条件」：LLM 可写的动作空间是闭集，宿主不做自由文本路由。
 */

/**
 * @Navigate 目的地闭集（线名 → 业务页面）。
 * wireName 即 program 写法（`@Navigate("task_center")`）；增删须同步
 * server `RenderOpenUiToolService` 工具描述（字面量副本口径同组件清单）。
 * @param takesParam 是否消费第二参（如 gallery_search 的搜索词、organize 的 tab 名）。
 */
enum class OpenUiNavigateTarget(val wireName: String, val takesParam: Boolean) {
    /** 相册主页（Pager 页 0）。 */
    GALLERY("gallery", takesParam = false),

    /** 相册搜索（param = 自然语言搜索词，走 gallerySearchRequest 先例通道）。 */
    GALLERY_SEARCH("gallery_search", takesParam = true),

    /** 整理+扫描合并页（param 可选："scan" 预选扫描 Tab，缺省/其他 = 整理 Tab）。 */
    ORGANIZE("organize", takesParam = true),

    /** 人物聚类页（Pager 页 3）。 */
    PEOPLE("people", takesParam = false),

    /** 回忆页（Pager 页 4）。 */
    MEMORY("memory", takesParam = false),

    /** 任务中心（NavHost 路由，任务卡「查看全部」同款出口）。 */
    TASK_CENTER("task_center", takesParam = false),

    /** 设置主菜单（NavHost 路由）。 */
    SETTINGS("settings", takesParam = false),
    ;

    companion object {
        /** 线名 → 目的地；未知名返回 null（宿主告警降级，不穿透不崩溃）。 */
        fun ofWireName(name: String): OpenUiNavigateTarget? =
            entries.firstOrNull { target -> target.wireName == name }
    }
}

/**
 * @Navigate 自定义动作事件 → 目的地闭集裁定（官方 custom action 宿主路由助手）：
 * args[0] = destination 线名（必填 Str），args[1] = param（可选 Str）；
 * 不可解析返回 null（宿主告警降级，不穿透不崩溃）。
 */
fun resolveOpenUiNavigate(args: List<OpenUiValue>): Pair<OpenUiNavigateTarget, String?>? {
    val wireName = (args.firstOrNull() as? OpenUiValue.Str)?.v ?: return null
    val target = OpenUiNavigateTarget.ofWireName(wireName) ?: return null
    val param = (args.getOrNull(1) as? OpenUiValue.Str)?.v
    return target to param
}

/**
 * Mutation 写工具白名单（M1 先行的媒体写两项）——program 写
 * `fav = Mutation("favorite_media", { id: photos[0].id })` 后 `@Run(fav)` 执行。
 * 工具名 SSOT：server 工具描述持字面量副本，增删须双向同步；
 * 高风险操作（删除等需系统授权的）不入本白名单——卡内点击无确认链路，只收低风险直写。
 */
object OpenUiWriteTools {
    /** 收藏照片：args = { id: 照片 id }（chat 会话级，同 ChatMediaWriteCapability favorite 口径）。 */
    const val FAVORITE_MEDIA = "favorite_media"

    /** 取消收藏：args = { id: 照片 id }。 */
    const val UNFAVORITE_MEDIA = "unfavorite_media"

    val ALL: Set<String> = setOf(FAVORITE_MEDIA, UNFAVORITE_MEDIA)
}
