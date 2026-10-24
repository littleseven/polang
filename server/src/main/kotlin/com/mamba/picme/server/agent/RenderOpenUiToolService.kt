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

    companion object {
        // 字符串模板插值只许引用 const（编译期常量约束），$selected 的 $ 由此而来
        private const val D = "$"

        /**
         * 黄金示例（program 骨架 SSOT）：@LLMDescription 内嵌文本与
         * RenderOpenUiValidatorTest 同源引用（A2UI 线 RENDER_UI_EXAMPLE 先例），
         * 改示例测试必红——防描述与校验器脱节。handleId 用合法形态的样例值
         * （<id> 占位过不了 LOCAL_HANDLE_FORM 形态校验，示例必须自身合法）。
         */
        const val GOLDEN_STATS_PROGRAM =
            D + "selected = \"\"\n" +
                "buckets = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#histogram\" }, [])\n" +
                "root = Card([Text(\"相册按月分布\", \"HEADLINE\"), Text(\"按月统计\", \"CAPTION\"), " +
                "BarChart(\"每月照片数\", buckets, " + D + "selected)])"

        /** 图文混排黄金示例（搜索拿 handle 后，照片与文字同卡混排）。 */
        const val GOLDEN_MIXED_PROGRAM =
            "photos = Query(\"local_handle\", { handle: \"local://scan/f9d33db29ed6#photos\" }, [])\n" +
                "root = Card([Text(\"海边之旅\", \"HEADLINE\"), " +
                "Row([Image(photos[0].id), Image(photos[1].id), Image(photos[2].id)]), " +
                "Text(photos[0].date, \"CAPTION\")])"
    }

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
     ⚠️ SURFACE 是卡面背景色，文字上使用会隐形——文字颜色只用 PRIMARY/ON_SURFACE/ERROR；
   - Row(children*, spacing?) / Column(children*, spacing?) / Card(children*, spacing?)：
     children 是组件数组（[组件1, 组件2]），spacing∈COMPACT|STANDARD|COMFORTABLE（默认 STANDARD）；
     Row 的子组件等宽分栏（左右两栏排版写法 Row([左栏组件, 右栏组件])）；
   - Button(label*, action?)：action 形如 @OpenUrl("https://...")；
   - BarChart(title?, buckets*, selected?)：buckets 是 [{label,count}] 数据引用；
   - Image(photo*, aspect?)：相册照片（图文混排用）；photo 只许填 photos 数组元素的
     id（如 photos[0].id），禁止写字面量数字 id（防臆造）；aspect 是宽高比
     （宽/高，默认 1.33，横图可传 1.5~1.78，竖图 0.75）；
     Row([Image(...), Image(...)]) 可多张并排等宽；单卡图片≤12 张（内存上限）；
4. 数据禁令：相册数据一律经查询绑定，写法逐字为
   `buckets = Query("local_handle", { handle: "local://scan/<id>#histogram" }, [])`
   （第三个参数 [] 是数据缺失时的降级默认值），其中 handleId 只许来自
   search_media/refine_media_search 结果尾部的「handle: scan/<id>」；
   照片清单取数写法逐字为
   `photos = Query("local_handle", { handle: "local://scan/<id>#photos" }, [])`，
   得到 [{id, date}]（按搜索相关度排序，最多 60 张，date 是 "yyyy-MM-dd"
   拍摄日期可直接给 Text 用）；
   禁止臆造 handleId，禁止把路径/uri/媒体清单写进 program 任何位置；
   handle id 只在 program 里引用，回复正文禁止向用户展示 handle id；
5. Query 推荐独占整条语句右侧（先 `buckets = Query("local_handle", …, [])` 单独成行，
   组件参数里引用 buckets——见规则 7 黄金示例；端侧引擎已支持内联提升，独占写法最稳）；
6. 交互约定：含 BarChart 的程序首行写 `${'$'}selected = ""`，BarChart 的 selected 参数传 `${'$'}selected`
   （端侧点选高亮回写该变量；裸字符串字面量不参与回写，点选高亮会静默失效）；
7. fallbackSummary 写投影级摘要（如「327 张，数据在原设备」），端上数据缺失时降级展示；
8. 完整黄金示例（统计卡标准骨架——照此结构写：root = 语句必须有、独占一行、
   组件引用前面语句的变量名；program 不要包 markdown 代码围栏）：
""" + GOLDEN_STATS_PROGRAM + """
   图文混排黄金示例（搜索拿 handle 后，照片与文字同卡混排）：
""" + GOLDEN_MIXED_PROGRAM
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
