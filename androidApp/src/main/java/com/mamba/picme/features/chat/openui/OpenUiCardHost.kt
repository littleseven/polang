package com.mamba.picme.features.chat.openui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.a2ui.A2uiSurfaceDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.mamba.picme.BuildConfig
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import com.mamba.picme.domain.chat.a2ui.GalleryHistogramBuilder
import com.mamba.picme.domain.chat.a2ui.ScanPhotoEntry
import com.mamba.picme.domain.chat.a2ui.ScanPhotoProjection
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolNotFoundException
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.chat.a2ui.A2uiCardDegraded
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * data_ui_openui 卡的渲染宿主（openui P2，与 A2uiCardHost 对偶）：一颗 session 一张卡。
 *
 * 三态：解析中 → LoadingIndicator；handle 失败/prepare Failed → A2uiCardDegraded(fallbackSummary)
 * （US-6 宁缺毋滥同口径）；Rendered → OpenUiNodeRenderer 递归渲染。
 *
 * Query("local_handle", ...) 经 [HandleLookup] 端侧补全数据（隐私闸对偶：GenUI 只生成骨架
 * 不生成数据）；任一 handle 缺失/不可解析 → 置 handleFailed 并抛异常（engine 回退 defaults），
 * prepare 后统判降级。viewState 键值对（`name=value;…`，2026-11-01 泛化，旧
 * `selected=<label>` 为子集）解码全部 Str 标量种初始 state（US-7 对偶）。
 *
 * 用户交互（@Set/@Reset）本地消费不出端（§3.4 不变式对偶），任意 Str state 变化经
 * [onViewStateChange] 持久化写回 part。@ToAssistant 上行（2026-10-31 起）：经
 * [onToAssistant] 以用户消息发进会话（CardHeader「查看全部」等卡内回话入口）。
 */
@Composable
fun OpenUiCardHost(
    part: MessagePart.OpenUiCard,
    handleLookup: HandleLookup,
    photoResolver: OpenUiPhotoResolver,
    onViewStateChange: (viewState: String) -> Unit,
    modifier: Modifier = Modifier,
    onPhotoClick: (uris: List<String>) -> Unit = {},
    onToAssistant: (text: String) -> Unit = {},
) {
    // 键粒度收窄为 partId+program（2026-11-01 review Y4 修复）：viewState 回流只变
    // viewState 字段，不再重建 session——此前以整颗 part 为键，卡内任意 $selected 写入
    // 都会触发 Room 回流→session 重建→其他交互 state（表单文本/开关）全丢。
    // viewState 仅在首次构建时解码为 initialState 填槽（恢复语义不变）。
    val resolution by produceState<OpenUiResolution?>(initialValue = null, part.partId, part.program) {
        var handleFailed = false
        val tools = OpenUiToolResolver { name, args ->
            if (name != OpenUiContract.TOOL_LOCAL_HANDLE) throw OpenUiToolNotFoundException(name)
            val resolved = resolveLocalHandle(handleLookup, args)
            if (resolved is HandleResolution.Ok) {
                resolved.value
            } else {
                handleFailed = true
                throw OpenUiHandleException((resolved as HandleResolution.Err).reason)
            }
        }
        // viewState 泛化（同 Y4）：全部键值对映射回 $name → Str 填槽（旧格式
        // "selected=<label>" 是其一阶子集，旧数据恢复行为不变）
        val initialState = OpenUiContract.decodeViewState(part.viewState)
            .map { (name, value) -> "\$$name" to OpenUiValue.Str(value) }
            .toMap()
        val session = OpenUiSession(
            program = part.program,
            tools = tools,
            initialState = initialState,
        )
        val result = session.prepare()
        // 可观测性：Rendered 也可能携带非致命错误（如 inline-reserved 导致组件静默丢弃），
        // 必须打出来——2026-10-24 真机柱图消失事故即因缺此日志难以定位
        val errors = when (result) {
            is OpenUiResult.Rendered -> result.errors
            is OpenUiResult.Failed -> result.errors
        }
        if (errors.isNotEmpty()) {
            Logger.w(
                "Chat",
                "OpenUI 卡 prepare 错误(${errors.size}): " + errors.joinToString("; ") { error ->
                    "${error.code}@${error.statementId}: ${error.message}"
                },
            )
        }
        value = when {
            handleFailed -> OpenUiResolution.Degraded
            result is OpenUiResult.Rendered -> OpenUiResolution.Ready(session)
            else -> OpenUiResolution.Degraded
        }
    }
    when (val current = resolution) {
        null -> A2uiSurfaceDefaults.LoadingIndicator()
        OpenUiResolution.Degraded -> A2uiCardDegraded(part.fallbackSummary, modifier)
        is OpenUiResolution.Ready -> key(part.partId) {
            if (BuildConfig.DEBUG) {
                // debug 徽标 overlay（与 A2uiCardHost 对偶）：显性标注此卡由 OpenUI 协议驱动，
                // 点击开程序原文查看器
                Box(modifier = modifier) {
                    OpenUiReadyContent(
                        session = current.session,
                        photoResolver = photoResolver,
                        onViewStateChange = onViewStateChange,
                        onPhotoClick = onPhotoClick,
                        onToAssistant = onToAssistant,
                        initialViewState = part.viewState,
                    )
                    OpenUiDebugBadge(
                        partId = part.partId,
                        program = part.program,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            } else {
                OpenUiReadyContent(
                    session = current.session,
                    photoResolver = photoResolver,
                    onViewStateChange = onViewStateChange,
                    modifier = modifier,
                    onPhotoClick = onPhotoClick,
                    onToAssistant = onToAssistant,
                    initialViewState = part.viewState,
                )
            }
        }
    }
}

private sealed interface OpenUiResolution {
    data object Degraded : OpenUiResolution
    data class Ready(val session: OpenUiSession) : OpenUiResolution
}

/** local_handle 取数结果（密封结果替代多 throw，detekt ThrowsCount）。 */
private sealed interface HandleResolution {
    data class Ok(val value: OpenUiValue) : HandleResolution
    data class Err(val reason: String) : HandleResolution
}

/** handle 任一环节失败信号：engine 捕获记 tool-error 回退 defaults，宿主统判降级。 */
private class OpenUiHandleException(reason: String) : Exception(reason)

/**
 * local_handle 工具实现：handle → HandleLookup payload → 按 domain + fragment 投影分流
 * （scan：空/histogram = 月桶统计卡；#photos = 照片 id 清单投影，图文混排卡，
 * spec《openui-image-component-design》D2）。
 * 任一环节失败返回 [HandleResolution.Err]（宿主侧置 handleFailed 并抛
 * [OpenUiHandleException]，engine 回退 defaults 后统判降级）。
 */
private suspend fun resolveLocalHandle(
    handleLookup: HandleLookup,
    args: Map<String, OpenUiValue>,
): HandleResolution {
    val handle = (args["handle"] as? OpenUiValue.Str)?.v
        ?: return HandleResolution.Err("local_handle 缺 handle 参数")
    val parsed = A2uiCardContract.parseLocalHandle(handle)
        ?: return HandleResolution.Err("handle 不可解析：$handle")
    val payload = handleLookup.payloadOf(parsed.domain, parsed.id)
        ?: return HandleResolution.Err("handle 缺失：$handle")
    if (parsed.domain != A2uiCardContract.DOMAIN_SCAN) {
        return HandleResolution.Err("未知 domain：${parsed.domain}")
    }
    val entries = runCatching { hostJson.decodeFromString<List<ScanPhotoEntry>>(payload) }
        .getOrElse { error ->
            if (error is CancellationException) throw error // 协程取消穿透
            return HandleResolution.Err("payload 不可解析：${error.message}")
        }
    return when (val fragment = parsed.fragment) {
        // 现状默认：月桶直方图（BarChart 统计卡）。
        // parseLocalHandle 已把空 fragment 归一为 null，"" 分支不存在
        null, "histogram" -> HandleResolution.Ok(
            OpenUiValue.Arr(
                GalleryHistogramBuilder.buildMonthly(entries).map { bucket ->
                    OpenUiValue.Obj(
                        mapOf(
                            "label" to OpenUiValue.Str(bucket.label),
                            "count" to OpenUiValue.Num(bucket.count.toDouble()),
                        )
                    )
                }
            )
        )
        // 图文混排：照片 id 清单投影（[{id, date}]，保持搜索相关度原序，cap 60）
        OpenUiContract.FRAGMENT_PHOTOS -> HandleResolution.Ok(
            OpenUiValue.Arr(
                ScanPhotoProjection.toPhotoRefs(entries).map { ref ->
                    OpenUiValue.Obj(
                        mapOf(
                            "id" to OpenUiValue.Num(ref.id.toDouble()),
                            "date" to OpenUiValue.Str(ref.date),
                        )
                    )
                }
            )
        )
        else -> HandleResolution.Err("未知 fragment：$fragment")
    }
}

private val hostJson = Json { ignoreUnknownKeys = true }

@Composable
private fun OpenUiReadyContent(
    session: OpenUiSession,
    photoResolver: OpenUiPhotoResolver,
    onViewStateChange: (viewState: String) -> Unit,
    modifier: Modifier = Modifier,
    onPhotoClick: (uris: List<String>) -> Unit = {},
    onToAssistant: (text: String) -> Unit = {},
    initialViewState: String?,
) {
    // P1 响应式状态（spec《2026-10-26-openui-compose-alignment-design》§3，对齐 androidx
    // bind 粒度订阅语义）：session.state 非 Compose 可观测（ADR-013 禁 compose 入 commonMain，
    // 边界转换只能宿主侧做），SnapshotStateMap 逐键增量同步换 per-key 读追踪——action 后
    // 只有订阅了变更键的节点重组，不再整表替换全量失效。
    val stateMap = remember(session) {
        mutableStateMapOf<String, OpenUiValue>().apply { putAll(session.state) }
    }
    var root by remember(session) { mutableStateOf(session.rootOrNull()) }
    // 会话全量照片 URI（2026-11-01 浏览完整性）：session.state 中任何含 id 字段的
    // 对象数组（photos 投影形态）全量提取——PhotoGrid 只展示 12 张但 handle 可能
    // 返回 60 张；点击任一照片 → 多页翻页预览展示全量（Google Photos 语义）
    val allSessionPhotoUris by produceState<List<String>>(emptyList(), session) {
        val ids = session.state.values.flatMap { value ->
            (value as? OpenUiValue.Arr)?.v?.mapNotNull { item ->
                (item as? OpenUiValue.Obj)?.v?.get("id")?.let { id ->
                    (id as? OpenUiValue.Num)?.v?.toLong()
                }
            } ?: emptyList()
        }.distinct()
        value = ids.mapNotNull { photoId -> photoResolver.resolve(photoId)?.uri }
    }
    // 已发射 viewState 指纹（归一化后比对，同值不重复持久化；初值取 part 携带态的归一化，
    // 避免冷启动首轮 Set 同值时多写一次 Room）
    var lastEmittedViewState by remember(session) {
        mutableStateOf(OpenUiContract.encodeViewState(OpenUiContract.decodeViewState(initialViewState)))
    }
    // prepare 期错误已在装载日志打过；此计数器支撑 action 期增量观测（Y3 配套，见 dispatch）
    var seenErrorCount by remember(session) { mutableIntStateOf(session.lastErrors().size) }
    val uriHandler = LocalUriHandler.current
    val dispatch: (OpenUiAction) -> Unit = { action ->
        when (openUiActionRouteOf(action.name)) {
            OpenUiActionRoute.STATE_WRITE -> {
                session.applyAction(action)
                // action 期错误唯一观测出口（Y3 配套）：reevaluateSafely 崩溃兜底或重求期
                // 错误并入 lastErrors，增量打一条（prepare 期错误已在装载时打过）
                val latestErrors = session.lastErrors()
                if (latestErrors.size > seenErrorCount) {
                    latestErrors.drop(seenErrorCount).forEach { error ->
                        Logger.w("Chat", "OpenUI 卡 action 重求期错误: ${error.code}@${error.statementId}: ${error.message}")
                    }
                    seenErrorCount = latestErrors.size
                }
                stateMap.syncWith(session.state)
                // root 相等门控：绑定全保留为 Binding 的树在纯交互 action 后结构不变（data class
                // 结构化相等），不换引用即零树级重组（ComponentVal props 含 Map 非 stable 类型，
                // 不比等必全树重组）；烘焙引用场景树必变，照常更新
                val newRoot = session.rootOrNull()
                if (newRoot != root) root = newRoot
                // viewState 泛化（2026-11-01 review Y4）：持久化全部 Str 标量 state 而不只
                // $selected——BarChart M4.2 自定义绑定变量（$month 等）的点选跨重建也能恢复；
                // produceState 键已收窄为 partId+program，回流不重建 session，无回流循环
                val encoded = OpenUiContract.encodeViewState(strStateSnapshot(session.state))
                if (encoded != lastEmittedViewState) {
                    lastEmittedViewState = encoded
                    onViewStateChange(encoded)
                }
            }
            OpenUiActionRoute.OPEN_URL -> (action.args.firstOrNull() as? OpenUiValue.Str)?.v?.let { url ->
                // scheme 闸（2026-11-01 review Y5）：与 Image src 的 https 校验同族防御
                if (isOpenUrlAllowed(url)) {
                    runCatching { uriHandler.openUri(url) }
                } else {
                    Logger.w("Chat", "OpenUI @OpenUrl 非 http(s) 地址被拦截: $url")
                }
            }
            // Image 点击看大图（宿主动作，program 不可达——渲染层 resolved uri 直发，
            // 不出端不回落 program；ChatScreen 落 chat 内预览浮层）
            OpenUiActionRoute.OPEN_PHOTO -> {
                // 2026-11-01 浏览完整性：优先用会话全量照片（handle 返回的 60 张），
                // 点击位移至首位——300 张结果也能翻完 handle 给出的全部，不再限于网格 12 张
                val clickedUri = (action.args.firstOrNull() as? OpenUiValue.Str)?.v
                val uris = when {
                    clickedUri != null && allSessionPhotoUris.contains(clickedUri) ->
                        listOf(clickedUri) + (allSessionPhotoUris - clickedUri)
                    else -> action.args.mapNotNull { arg -> (arg as? OpenUiValue.Str)?.v }
                }
                if (uris.isNotEmpty()) onPhotoClick(uris)
            }
            // @ToAssistant 上行（2026-10-31 真机回归修复，原为 M1 只记日志不路由）：
            // 卡内回话入口（CardHeader「查看全部」缺省动作等）以用户消息发进会话。
            // M1 只取首参（review B1）：eval 收第二参（官方 payload 语义）但上行通道未定义，丢弃
            OpenUiActionRoute.TO_ASSISTANT -> {
                val text = (action.args.firstOrNull() as? OpenUiValue.Str)?.v.orEmpty()
                Logger.d("Chat", "OpenUI @ToAssistant 上行: $text")
                if (text.isNotBlank()) onToAssistant(text)
            }
            // @Run（Mutation 执行）M1 未实现：显式识别并告警，不落「未知动作」静默吞（review Y2）
            OpenUiActionRoute.RUN_UNSUPPORTED ->
                Logger.w("Chat", "OpenUI @Run(${action.target.orEmpty()}) M1 未实现，忽略")
            OpenUiActionRoute.UNKNOWN -> Logger.w("Chat", "OpenUI 未知动作: ${action.name}")
        }
    }
    root?.let { component ->
        OpenUiNodeRenderer(
            node = component,
            state = stateMap,
            photoResolver = photoResolver,
            onAction = dispatch,
            modifier = modifier,
        )
    }
}

/** dispatch 路由分类（纯函数，清单测试钉住与 [OpenUiContract.ENGINE_ACTIONS] 的覆盖关系）。 */
internal enum class OpenUiActionRoute { STATE_WRITE, OPEN_URL, OPEN_PHOTO, TO_ASSISTANT, RUN_UNSUPPORTED, UNKNOWN }

internal fun openUiActionRouteOf(name: String): OpenUiActionRoute = when (name) {
    OpenUiContract.Actions.SET, OpenUiContract.Actions.RESET -> OpenUiActionRoute.STATE_WRITE
    OpenUiContract.Actions.OPEN_URL -> OpenUiActionRoute.OPEN_URL
    OpenUiContract.Actions.OPEN_PHOTO -> OpenUiActionRoute.OPEN_PHOTO
    OpenUiContract.Actions.TO_ASSISTANT -> OpenUiActionRoute.TO_ASSISTANT
    OpenUiContract.Actions.RUN -> OpenUiActionRoute.RUN_UNSUPPORTED
    else -> OpenUiActionRoute.UNKNOWN
}

/** @OpenUrl scheme 闸（2026-11-01 review Y5）：只放行 http/https，拦截 file://、intent://、market:// 等。 */
internal fun isOpenUrlAllowed(url: String): Boolean {
    // scheme 大小写不敏感（URI 规范）；容忍首参带空白
    val scheme = url.trim().substringBefore("://").lowercase()
    return scheme == "https" || scheme == "http"
}

/** viewState 持久化快照：store 中全部 Str 标量（选择态与表单文本同径；Bool/Num 不持久化）。 */
private fun strStateSnapshot(state: Map<String, OpenUiValue>): Map<String, String> =
    state.mapNotNull { (key, value) -> (value as? OpenUiValue.Str)?.v?.let { text -> key to text } }
        .toMap()

/**
 * 逐键增量同步（P1，spec §3.1）：删消失键、写变更键、不动未变键——配合 SnapshotStateMap
 * 的 per-key 读追踪，只有订阅变更键的组合节点失效。整表替换（putAll 前 clear）会让所有
 * 订阅者全量失效，退化回旧的快照拷贝语义，禁那样写。
 */
internal fun MutableMap<String, OpenUiValue>.syncWith(latest: Map<String, OpenUiValue>) {
    (keys - latest.keys).forEach(::remove)
    latest.forEach { (key, value) ->
        if (this[key] != value) this[key] = value
    }
}
