package com.mamba.picme.domain.chat.a2ui

/**
 * A2UI 卡契约（D6 隔离层 SSOT）——线格式字面量与 local:// handle 格式的唯一出处。
 *
 * 三方消费：androidApp（渲染/落库/工具注册）、server `RenderUiToolService`（@LLMDescription
 * 字面量副本，改动须双向同步核对）、云端 LLM 行为约定（render_ui 工具描述内嵌）。
 *
 * handle 双形态（spec §3.2 + 投影闸约束）：
 * - 上行投影文本只带 opaque token（[formatHandleToken]，无 scheme，过 CloudToolProjectionGate）；
 * - 下行 A2UI JSON 的 updateDataModel 字符串值写完整 `local://` 绑定（下行不经闸）；
 * - 端上 HandleResolver 用 [parseLocalHandle] 解析填槽。
 */
object A2uiCardContract {

    /** ChatMessageEntity.type / converter 分支键。 */
    const val PART_TYPE = "data_ui"

    /** polang A2UI catalog 唯一 id（createSurface.catalogId 必须等于它）。 */
    const val CATALOG_ID = "https://polang.mamba.com/a2ui/catalog/v1"

    const val HANDLE_SCHEME = "local://"

    /** 相册扫描结果域（search_media / refine_media_search 命中集）。 */
    const val DOMAIN_SCAN = "scan"

    /** A2UI 协议版本（SUPPORTED_VERSIONS = ["v0.9", "v0.9.1"]，M2 固定 v0.9）。 */
    const val PROTOCOL_VERSION = "v0.9"

    /** 上行 opaque token：`handle: scan/<id>`（无 scheme，过投影闸）。 */
    fun formatHandleToken(domain: String, id: String): String = "handle: $domain/$id"

    /** 完整 handle 线格式：`local://<domain>/<id>[#<fragment>]`。 */
    fun formatLocalHandle(handle: LocalHandle): String = buildString {
        append(HANDLE_SCHEME).append(handle.domain).append('/').append(handle.id)
        handle.fragment?.let { fragment -> append('#').append(fragment) }
    }

    /**
     * 解析 `local://<domain>/<id>[#<fragment>]`；非 local:// 或缺 domain/id 返 null。
     * fragment 允许为空字符串以外的任意非空片段（交互态编码由消费方自定）。
     */
    fun parseLocalHandle(value: String): LocalHandle? {
        if (!value.startsWith(HANDLE_SCHEME)) return null
        val rest = value.removePrefix(HANDLE_SCHEME)
        val hashIndex = rest.indexOf('#')
        val pathPart = if (hashIndex >= 0) rest.substring(0, hashIndex) else rest
        val fragment = if (hashIndex >= 0) rest.substring(hashIndex + 1) else null
        val slashIndex = pathPart.indexOf('/')
        if (slashIndex <= 0 || slashIndex == pathPart.length - 1) return null
        return LocalHandle(
            domain = pathPart.substring(0, slashIndex),
            id = pathPart.substring(slashIndex + 1),
            fragment = fragment?.takeIf { frag -> frag.isNotEmpty() },
        )
    }
}

/** local:// handle 的结构化形态。 */
data class LocalHandle(
    val domain: String,
    val id: String,
    val fragment: String? = null,
)
