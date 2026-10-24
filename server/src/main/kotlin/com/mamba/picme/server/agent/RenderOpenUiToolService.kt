package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

/**
 * render_ui_openui——云端 Agent 的 OpenUI 发卡工具（openui P2，spec《openui-wire-format-toggle》§5）。
 *
 * 与 [RenderUiToolService]（A2UI 线）互斥注册：AgentRoutes 按请求头 X-GenUI-Format
 * 二选一（缺头/未知值默认 A2UI，向后兼容），LLM 一轮只见其中一套工具描述。
 *
 * 工具体是 CLOUD 侧空执行：OpenUI 程序不经 tool-result 回环，而是经
 * AgentRunner.onToolCallStarting 落的 action 事件（tool+argsJson）随 SSE 下行，
 * 端侧 CloudAgentTurnRunner "action" 分支拦截渲染。工具只回确认文本。
 *
 * ⚠️ @LLMDescription 与 shared `OpenUiContract`/`OpenUiLibrary` 的字面量
 * （data_ui_openui/local_handle/组件签名/$selected 约定）是字面量副本关系
 * （server 不依赖 :shared），改动须双向同步核对。
 */
class RenderOpenUiToolService {

    @Tool(customName = "render_ui_openui")
    @LLMDescription(
        """向用户下发一张 OpenUI 原生渲染卡（图表/统计可视化首选，优于 render_html）。
约束（必须全部满足，否则端上拒绝渲染或静默丢弃组件）：
1. program 是 OpenUI Lang 1.0 程序：每行一条语句，形如 `名 = 表达式`；组件调用
   大写开头、只用位置参数；字符串一律双引号；
2. 必须有 `root = ...` 语句（root 是整个组件树的入口）；
3. 可用组件签名（逐字遵守，禁发明属性）：
   - Text(text*, role?, colorRole?)：role∈HEADLINE|SUBHEAD|BODY|CAPTION（默认 BODY），
     colorRole∈PRIMARY|SURFACE|ERROR|ON_SURFACE（默认 ON_SURFACE）；
   - Row(children*, spacing?) / Column(children*, spacing?) / Card(children*, spacing?)：
     children 是组件数组（[组件1, 组件2]），spacing∈COMPACT|STANDARD|COMFORTABLE（默认 STANDARD）；
   - Button(label*, action?)：action 形如 @OpenUrl("https://...")；
   - BarChart(title?, buckets*, selected?)：buckets 是 [{label,count}] 数据引用；
4. 数据禁令：相册数据一律经查询绑定，写法逐字为
   `buckets = Query("local_handle", { handle: "local://scan/<id>#histogram" }, [])`
   （第三个参数 [] 是数据缺失时的降级默认值），其中 handleId 只许来自
   search_media/refine_media_search 结果尾部的「handle: scan/<id>」；
   禁止臆造 handleId，禁止把路径/uri/媒体清单写进 program 任何位置；
5. 交互约定：含 BarChart 的程序首行写 `${'$'}selected = ""`，BarChart 的 selected 参数传 `${'$'}selected`
   （端侧点选高亮回写该变量；裸字符串字面量不参与回写，点选高亮会静默失效）；
6. fallbackSummary 写投影级摘要（如「327 张，数据在原设备」），端上数据缺失时降级展示。"""
    )
    suspend fun renderUiOpenUi(
        @LLMDescription("OpenUI Lang 1.0 程序原文（骨架 + Query 绑定，无数据）")
        program: String,
        @LLMDescription("投影级降级摘要（数据缺失时端上展示的占位文案）")
        fallbackSummary: String,
    ): String {
        // 与发射点门控同源校验（review 🟡4 对偶）：非法回 error 文本作 Observation
        // 回灌，LLM 同轮修正重试（同 RenderUiToolService 闭环口径）
        RenderOpenUiValidator.validateToError(program)?.let { error -> return error }
        return "UI 已下发端侧渲染（OpenUI）"
    }
}
