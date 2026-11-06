package com.mamba.picme.domain.chat.openui

/**
 * OpenUI 线格式契约（字面量 SSOT）。
 *
 * 三方消费：androidApp（渲染/落库/工具注册）、server `OpenUiPromptAssets`
 * （prompt 资产/描述文本字面量副本，改动须双向同步核对）、云端 LLM 行为约定。
 *
 * local:// handle 契约唯一出处（2026-11-05 A2UI 退役后自 A2uiCardContract 并入）：
 * - 上行投影文本只带 opaque token（[formatHandleToken]，无 scheme，过 CloudToolProjectionGate）；
 * - 下行 OpenUI Query("local_handle") 绑定写完整 `local://` 句柄（下行不经闸）；
 * - 端上宿主用 [parseLocalHandle] 解析填槽。
 */
object OpenUiContract {

    /** ChatMessageEntity.type / converter 分支键 / MessagePart serialName。 */
    const val PART_TYPE = "data_ui_openui"

    const val HANDLE_SCHEME = "local://"

    /** 相册扫描结果域（search_media / refine_media_search 命中集）。 */
    const val DOMAIN_SCAN = "scan"

    /** 端上宿主工具名（Query("local_handle", ...) → HandleLookup 端侧补全，隐私闸对偶）。 */
    const val TOOL_LOCAL_HANDLE = "local_handle"

    /** BarChart 选中桶的绑定 state 变量名。 */
    const val STATE_SELECTED = "\$selected"

    /**
     * 动作名（§6.3）SSOT：eval 产出（[ENGINE]）与宿主合成（[OPEN_PHOTO]）共用词表。
     * parser 内置名表 / eval / engine `applyAction` / Android 宿主 dispatch 统一引用；
     * server `RenderOpenUiValidator` 持字面量副本，改动须双向同步核对（同 iconNames 惯例）。
     *
     * 交互去向对齐官方 actions 语义（2026-11-01 动作体系官方对齐，不新增语法）：
     * 1. **本地操作**：[SET]/[RESET] 卡内状态写、[OPEN_URL] 外部链接、[OPEN_PHOTO] 大图预览，
     *    App 内页面跳转 = library 自定义动作（`OpenUiLibrary.customActions`，官方 custom
     *    action 机制——library 注册、渲染层不执行、事件抛宿主路由）；
     * 2. **访问 API**：[RUN] 执行 `Mutation`（宿主写工具，toolProvider 同 Query 一条接缝；
     *    mutation.status 可绑定回 UI；失败中断后续动作链）或重取 Query；
     * 3. **访问 Agent**：[TO_ASSISTANT] 以用户消息上行（第二参 context 官方语义：任意值
     *    原样透传 host）。
     * 组合经 `Action([...])` 顺序执行（渲染层整链交宿主，@Run 失败即中断）。
     */
    object Actions {
        const val SET = "Set"
        const val RESET = "Reset"
        const val RUN = "Run"
        const val TO_ASSISTANT = "ToAssistant"
        const val OPEN_URL = "OpenUrl"

        /** 宿主合成（渲染层 Image 点击看大图，program 不可达，不出端不回落 program）。 */
        const val OPEN_PHOTO = "OpenPhoto"

        /** Action([...]) 包装调用名（语法关键字，非可 dispatch 动作）。 */
        const val WRAPPER = "Action"
    }

    /** eval 层可产出的内置动作名全集（宿主 dispatch 必须穷尽覆盖，androidApp 侧清单测试钉住；
     *  library 自定义动作不在此列——经 CUSTOM 路由按注册名分发）。 */
    val ENGINE_ACTIONS: Set<String> = setOf(
        Actions.SET,
        Actions.RESET,
        Actions.RUN,
        Actions.TO_ASSISTANT,
        Actions.OPEN_URL,
    )

    /**
     * scan 句柄的 #photos fragment（spec《openui-image-component-design》D2）：
     * `local://scan/<id>#photos` → 端侧投影 [{id, date}]（ScanPhotoProjection，cap 60）。
     * server `OpenUiPromptAssets` 描述文本持字面量副本，改动须双向同步核对。
     */
    const val FRAGMENT_PHOTOS = "photos"

    /**
     * scan 句柄的 #videos fragment（2026-11-04 Video 组件）：
     * `local://scan/<id>#videos` → 端侧投影 [{id, date, durationSec}]（cap 60）。
     * server `OpenUiPromptAssets` 描述文本持字面量副本，改动须双向同步核对。
     */
    const val FRAGMENT_VIDEOS = "videos"

    /**
     * viewState 线格式（2026-11-01 泛化，review Y4）：`name=value;name=value` 键值对序列——
     * name 为 state 变量名（不含 `$`，恢复时映射回 `$name` → Str），`%`/`=`/`;` 三字符
     * percent-编码。历史单值形态 `selected=<label>` 是其一阶子集，旧数据可读。
     * 持久化范围 = 当前 store 中全部 Str 标量（选择态与表单文本同径恢复；Bool/Num 不持久化，
     * 重建回声明默认值）。
     */
    fun encodeViewState(entries: Map<String, String>): String =
        entries.entries.joinToString(";") { (name, value) ->
            "${escapeViewStateToken(name.removePrefix("$"))}=${escapeViewStateToken(value)}"
        }

    /**
     * [encodeViewState] 逆操作；容忍畸形段（无 `=` 的段丢弃，与旧解析的容错口径一致）。
     * 已知缝隙：旧格式值未转义，历史数据 value 含 `;` 时（如 "selected=a;b"）新解码截断
     * 为 "a"（旧解析保留全值）——一次性低概率损失，仅影响该类历史卡的选择态恢复。
     */
    fun decodeViewState(viewState: String?): Map<String, String> {
        if (viewState.isNullOrEmpty()) return emptyMap()
        return viewState.split(";").mapNotNull { segment ->
            val eq = segment.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = unescapeViewStateToken(segment.substring(0, eq))
            if (name.isEmpty()) return@mapNotNull null
            name to unescapeViewStateToken(segment.substring(eq + 1))
        }.toMap()
    }

    private fun escapeViewStateToken(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '%' -> append("%25")
                '=' -> append("%3D")
                ';' -> append("%3B")
                else -> append(ch)
            }
        }
    }

    private fun unescapeViewStateToken(encoded: String): String = encoded
        .replace("%3B", ";")
        .replace("%3D", "=")
        .replace("%25", "%")

    /** 上行 opaque token：`handle: scan/<id>`（无 scheme，过投影闸）。 */
    fun formatHandleToken(domain: String, id: String): String = "handle: $domain/$id"

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

    /** 历史单值便捷方法：encodeViewState 的 selected 单键形式（label 转义同轨）。 */
    fun viewStateOfSelected(label: String): String = encodeViewState(mapOf("selected" to label))

    /** 历史单值读取：等价于 decodeViewState(viewState)["selected"] 取非空。 */
    fun selectedOfViewState(viewState: String?): String? =
        decodeViewState(viewState)["selected"]?.takeIf { it.isNotEmpty() }
}

/** local:// handle 的结构化形态。 */
data class LocalHandle(
    val domain: String,
    val id: String,
    val fragment: String? = null,
)
