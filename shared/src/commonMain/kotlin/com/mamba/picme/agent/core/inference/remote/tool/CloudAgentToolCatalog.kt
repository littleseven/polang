package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType

/**
 * 云端 Agent 工具注册表（spec §3.1 Locality 静态声明）——每工具 description + params +
 * executor + resultSchema + projectionSchema 一处声明，代码自有、非运行时判断。
 *
 * 分层 runtime M2 起本表是**工具声明的唯一源头**：
 * - server `DeviceToolService` 按 [localTools] 循环生成编程式 Koog 工具
 *   （[descriptorFor] 产出 LLM 可见 schema），不再持 @Tool 反射方法；
 * - 双端 executor（androidApp `DeviceToolExecutor` / shared iosMain `IosCloudAgentToolBridge`）
 *   经 [paramNames]/[defaultFor] 读参数名与兼容缺省，不再持魔数字符串。
 *
 * projectionSchema 是出端白名单的人类可读描述；机器可校验的禁出校验由
 * [CloudToolProjectionGate] 承载（同一文件族）。云端 LLM 可见描述只许承诺 projectionSchema
 * 这个面——条目 [CloudToolSpec.description] 真引用
 * [CloudAgentToolContract].DESC_*（分层 runtime M1 收口，M2 起迁为本表字段）。
 *
 * 完整结果（resultSchema 描述的结构，如 search_media 的 List<MediaAsset>）永不离开设备；
 * M2 起经 local:// handle 端上补全引用。本表是 [PRIVACY] 闸的审计入口。
 *
 * CLOUD 条目（render_ui/render_ui_openui/render_html）已随 2026-11 GenUI 唯一化全退役
 * 删除（工具名常量保留于 [CloudAgentToolContract]，仅供历史回放展示）；本表现仅声明
 * LOCAL 工具。CLOUD 工具的 projectionSchema 语义与 LOCAL 相反（描述「回给 LLM 的面」
 * 而非出端白名单，不经 [CloudToolProjectionGate]，该闸只管端→云方向）——后续若新增
 * CLOUD 条目须沿用此口径。
 */

/** 工具执行位。 */
enum class ToolExecutor { CLOUD, LOCAL }

/**
 * 工具参数声明。
 *
 * @property required LLM 可见 schema 的 required/optional 分流。桥工具现行全必填
 *   （Koog 反射期即全必填，端侧以空串表达「不限」），声明仅供未来演进。
 * @property default **端侧 executor 兼容缺省**（旧服务端不下发该键时的兜底，如 display
 *   "false"/mediaType "photo"）——不进 LLM schema、不改变 required 语义。
 */
data class CloudToolParam(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val default: String = "",
)

/** 云端 Agent 工具注册条目。 */
data class CloudToolSpec(
    val name: String,
    val executor: ToolExecutor,
    /** LLM 可见工具描述（真引用 [CloudAgentToolContract].DESC_*）。 */
    val description: String,
    /** 参数声明（桥帧键序；空 = 无参工具）。 */
    val params: List<CloudToolParam> = emptyList(),
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
            description = CloudAgentToolContract.DESC_GET_GALLERY_SUMMARY,
            resultSchema = "GallerySummary 结构（照片/视频总数、人物聚类数、已打标数等）",
            projectionSchema = "纯文本摘要：仅计数与文本元数据，不含文件路径/uri/媒体清单",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_SEARCH_MEDIA,
            executor = ToolExecutor.LOCAL,
            description = CloudAgentToolContract.DESC_SEARCH_MEDIA,
            params = listOf(
                CloudToolParam("query", CloudAgentToolContract.DESC_PARAM_QUERY),
                CloudToolParam("person", CloudAgentToolContract.DESC_PARAM_PERSON),
                CloudToolParam("fromMs", CloudAgentToolContract.DESC_PARAM_FROM_MS),
                CloudToolParam("toMs", CloudAgentToolContract.DESC_PARAM_TO_MS),
                CloudToolParam("hasFace", CloudAgentToolContract.DESC_PARAM_HAS_FACE),
                CloudToolParam("display", CloudAgentToolContract.DESC_PARAM_DISPLAY, default = "false"),
                CloudToolParam("mediaType", CloudAgentToolContract.DESC_PARAM_MEDIA_TYPE, default = "photo"),
            ),
            resultSchema = "List<MediaAsset> 完整资产列表（含 uri）+ 总数",
            projectionSchema = "计数文本「找到 N 张『query』的照片」+ 尾部 opaque 数据句柄「handle: scan/<id>」：仅数量+命中说明+句柄 id，不含文件路径/uri/媒体清单",
        ),
        CloudToolSpec(
            name = CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH,
            executor = ToolExecutor.LOCAL,
            description = CloudAgentToolContract.DESC_REFINE_MEDIA_SEARCH,
            params = listOf(
                CloudToolParam("constraint", CloudAgentToolContract.DESC_PARAM_CONSTRAINT),
                CloudToolParam("fromMs", CloudAgentToolContract.DESC_PARAM_FROM_MS),
                CloudToolParam("toMs", CloudAgentToolContract.DESC_PARAM_TO_MS),
            ),
            resultSchema = "List<MediaAsset> 细化后完整资产列表（含 uri）+ 基数/命中数",
            projectionSchema = "计数文本「在上一轮结果内找到 N 张」+ 尾部 opaque 数据句柄「handle: scan/<id>」：仅数量+句柄 id，不含文件路径/uri/媒体清单",
        ),
    ).associateBy { spec -> spec.name }

    fun specFor(tool: String): CloudToolSpec? = specs[tool]

    /** 全部 LOCAL 条目（server 工具桥循环生成编程式 Koog 工具的输入）。 */
    fun localTools(): List<CloudToolSpec> = specs.values.filter { it.executor == ToolExecutor.LOCAL }

    /** 参数名列表（声明序 = 桥帧键序）；未知工具空表。 */
    fun paramNames(tool: String): List<String> = specs[tool]?.params?.map { it.name } ?: emptyList()

    /** 端侧 executor 兼容缺省（未声明 = 空串）。 */
    fun defaultFor(tool: String, param: String): String =
        specs[tool]?.params?.firstOrNull { it.name == param }?.default ?: ""

    /**
     * LLM 可见 schema（Koog [ToolDescriptor]）：name/description/required/optional 全部
     * 自声明派生——编程式 `Tool`（显式 descriptor 构造）下 LLM 所见与反射期逐项等价。
     */
    fun descriptorFor(tool: String): ToolDescriptor {
        val spec = requireNotNull(specs[tool]) { "unknown cloud agent tool: $tool" }
        fun CloudToolParam.toDescriptor() = ToolParameterDescriptor(name, description, ToolParameterType.String)
        return ToolDescriptor(
            name = spec.name,
            description = spec.description,
            requiredParameters = spec.params.filter { it.required }.map { it.toDescriptor() },
            optionalParameters = spec.params.filterNot { it.required }.map { it.toDescriptor() },
        )
    }
}
