package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

/**
 * render_ui——云端 Agent 的 A2UI 发卡工具（hybrid M2，spec §3.3 L1 卡）。
 *
 * 工具体是 CLOUD 侧空执行：A2UI 负载不经 tool-result 回环，而是经
 * AgentRunner.onToolCallStarting 落的 action 事件（tool+argsJson）随 SSE 下行，
 * 端侧 CloudAgentTurnRunner "action" 分支拦截渲染。工具只回确认文本。
 *
 * ⚠️ @LLMDescription 与 shared `A2uiCardContract` 的字面量（CATALOG_ID/版本/handle 约定）
 * 是字面量副本关系（server 不依赖 :shared），改动须双向同步核对。
 */
class RenderUiToolService {

    @Tool(customName = "render_ui")
    @LLMDescription(
        """向用户下发一张 A2UI 原生渲染卡（图表/统计可视化首选，优于 render_html）。
约束（必须全部满足，否则端上拒绝渲染或静默丢失数据）：
1. messagesJson 是 A2UI v0.9 信封 JSON 数组，每个信封含 "version": "v0.9" 与
   createSurface / updateComponents / updateDataModel 之一；
2. createSurface 的 catalogId 必须是 "https://polang.mamba.com/a2ui/catalog/v1"；
3. updateDataModel 一信封只写一个绑定：必须含 "path"（如 "/buckets"）与 "value" 两键，
   禁止使用 "data" 批量形式（端上解析器不识别该键，数据会被静默丢弃）；
4. 组件只用：Text / Row / Column / Card / Button / BarChart。
   Text 的 variant 仅 h1/h2/h3/h4/h5/caption/body；
   Button 必须给 childId（包一个 Text 组件作按钮文字，禁止用 "label" 属性）和 action，
   variant 仅 default/primary/borderless；
   BarChart（柱状图）：title=标题字符串，buckets=[{label,count}] 绑定数据模型，
   selected=选中桶 label 字符串（不是下标）；
5. 数据不进 JSON：凡涉及相册数据的值，updateDataModel 的字符串值写绑定
   "local://scan/<handleId>#histogram"，handleId 来自 search_media/refine_media_search
   结果尾部的「handle: scan/<id>」。禁止臆造 handleId，禁止把路径/uri/清单写进任何字段；
6. fallbackSummary 写投影级摘要（如「327 张，数据在原设备」），端上数据缺失时降级展示。"""
    )
    suspend fun renderUi(
        @LLMDescription("A2UI surface 唯一 id（与 createSurface.surfaceId 一致，建议 sf-<8位hex>）")
        surfaceId: String,
        @LLMDescription("A2UI v0.9 信封 JSON 数组原文")
        messagesJson: String,
        @LLMDescription("投影级降级摘要（数据缺失时端上展示的占位文案）")
        fallbackSummary: String,
    ): String = "UI 已下发端侧渲染（surfaceId=$surfaceId）"
}
