package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 云端 Agent 工具注册表（spec §3.1 Locality 静态声明）——每工具 executor + resultSchema +
 * projectionSchema 三声明，代码自有、非运行时判断。
 *
 * projectionSchema 是出端白名单的人类可读描述；机器可校验的禁出校验由
 * [CloudToolProjectionGate] 承载（同一文件族）。云端 LLM 可见描述只许承诺 projectionSchema
 * 这个面——server `DeviceToolService` 的 @LLMDescription 与之逐字对齐（字面量副本模式，
 * 改动须双向同步核对）。
 *
 * 完整结果（resultSchema 描述的结构，如 search_media 的 List<MediaAsset>）永不离开设备；
 * M2 起经 local:// handle 端上补全引用。本表是 [PRIVACY] 闸的审计入口。
 *
 * CLOUD 条目（如 render_html）语义相反：执行位在 server，不存在「出端」数据面，
 * projectionSchema 描述的是「回给 LLM 的面」（summary-only 回执）而非出端白名单，
 * 不经 [CloudToolProjectionGate]（该闸只管端→云方向）。
 */

/** 工具执行位。 */
enum class ToolExecutor { CLOUD, LOCAL }

/** 云端 Agent 工具注册条目。 */
data class CloudToolSpec(
    val name: String,
    val executor: ToolExecutor,
    /** 完整结果结构描述（端上保留，不上云）。 */
    val resultSchema: String,
    /** 出端白名单描述（摘要/计数/文本元数据）；必须含「不含」排除子句声明禁出项。 */
    val projectionSchema: String,
)

object CloudAgentToolCatalog {

    private val specs: Map<String, CloudToolSpec> = listOf(
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY,
            executor = ToolExecutor.LOCAL,
            resultSchema = "GallerySummary 结构（照片/视频总数、人物聚类数、已打标数等）",
            projectionSchema = "纯文本摘要：仅计数与文本元数据，不含文件路径/uri/媒体清单",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            executor = ToolExecutor.LOCAL,
            resultSchema = "List<MediaAsset> 完整资产列表（含 uri）+ 总数",
            projectionSchema = "计数文本「找到 N 张『query』的照片」+ 尾部 opaque 数据句柄「handle: scan/<id>」：仅数量+命中说明+句柄 id，不含文件路径/uri/媒体清单",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
            executor = ToolExecutor.LOCAL,
            resultSchema = "List<MediaAsset> 细化后完整资产列表（含 uri）+ 基数/命中数",
            projectionSchema = "计数文本「在上一轮结果内找到 N 张」+ 尾部 opaque 数据句柄「handle: scan/<id>」：仅数量+句柄 id，不含文件路径/uri/媒体清单",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_RENDER_UI,
            executor = ToolExecutor.CLOUD,
            resultSchema = "A2UI v0.9 信封 JSON 数组（骨架+绑定槽位），经 action 事件下行端侧渲染",
            projectionSchema = "不适用：CLOUD 工具无端侧数据出端；确认文本仅含 surfaceId，不含媒体数据",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_RENDER_UI_OPENUI,
            executor = ToolExecutor.CLOUD,
            resultSchema = "OpenUI Lang 1.0 程序（骨架+Query 绑定，无数据），经 action 事件下行端侧渲染",
            projectionSchema = "不适用：CLOUD 工具无端侧数据出端；确认文本为固定回执，不含媒体数据",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_RENDER_HTML,
            executor = ToolExecutor.CLOUD,
            resultSchema = "无（HTML 不入 LLM 上下文；卡本体经 AgentEvent HtmlCard 载荷下行端上）",
            projectionSchema = "LLM 仅见 summary 回执文本；HTML 为模型生成物下行端上渲染，方向为出云入端，不经 CloudToolProjectionGate（该闸只管端→云）",
        ),
    ).associateBy { spec -> spec.name }

    fun specFor(tool: String): CloudToolSpec? = specs[tool]
}
