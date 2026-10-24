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

    /**
     * polang A2UI catalog 唯一 id（createSurface.catalogId 必须等于它）。
     * 可解引用 URL：GET 返回 catalog JSON schema（官网静态文件 docs-site/a2ui/catalog/v1.json，
     * 由 androidApp `A2uiCatalogDescriptorSyncTest` 生成并对账，部署走 deploy-docs-site.sh）；
     * 端侧仍仅做等值匹配、从不发起网络请求。
     */
    const val CATALOG_ID = "https://polang.net/a2ui/catalog/v1.json"

    const val HANDLE_SCHEME = "local://"

    /** 相册扫描结果域（search_media / refine_media_search 命中集）。 */
    const val DOMAIN_SCAN = "scan"

    /** A2UI 协议版本（SUPPORTED_VERSIONS = ["v0.9", "v0.9.1"]，M2 固定 v0.9）。 */
    const val PROTOCOL_VERSION = "v0.9"

    /** BarChart 数据模型路径：月桶列表（updateDataModel 写 [{label,count}]）。 */
    const val DATA_PATH_BUCKETS = "/buckets"

    /**
     * BarChart 数据模型路径：选中桶 label。蓝图侧 selected 属性必须写 {"path":"/selected"}
     * 绑定（裸字符串字面量不订阅数据模型，点选高亮静默失效——2026-10-24 sf-012a0 事故）；
     * 端侧点选回写与 viewState 恢复也走此路径。
     */
    const val DATA_PATH_SELECTED = "/selected"

    // ── 语义样式令牌线格式键（A2uiStyleTokens 配套） ──────────────────────

    /** Text 组件语义层级键（value = HEADLINE|SUBHEAD|BODY|CAPTION）。 */
    const val ROLE_KEY = "role"

    /** 颜色语义角色键（value = PRIMARY|SURFACE|ERROR|ON_SURFACE）。 */
    const val COLOR_ROLE_KEY = "colorRole"

    /** 间距语义角色键（value = COMPACT|STANDARD|COMFORTABLE）。 */
    const val SPACING_KEY = "spacing"

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
