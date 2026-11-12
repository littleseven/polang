package com.mamba.picme.domain.chat.openui

import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.data.IosLocalHandleStore
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

// ── Swift 渲染树镜像（iosMain）───────────────────────────────────────────────
//
// OpenUiValue 组件树经此投影为「数组承载一切集合」的镜像（Map → List<Prop>），
// 规避 Swift 侧 KotlinDictionary 人机工学；动作保留原始 OpenUiAction 引用供 dispatch 回传。

/** Swift 侧属性访问单元：name → value（value 为 [IosOpenUiVal] 镜像）。 */
class IosOpenUiPropVal(val name: String, val value: IosOpenUiVal)

/** OpenUiValue 的 Swift 渲染镜像（sealed → SKIE Swift enum / 类转型双可用）。 */
sealed interface IosOpenUiVal {
    class TextVal(val v: String) : IosOpenUiVal
    class NumVal(val v: Double) : IosOpenUiVal
    class BoolVal(val v: Boolean) : IosOpenUiVal
    data object NullVal : IosOpenUiVal

    /** 数组（对象数组/原始数组/组件 children 一律此形态；items 已镜像）。 */
    class ListVal(val items: List<IosOpenUiVal>) : IosOpenUiVal

    /** 对象（entries 键值对列表形态）。 */
    class ObjVal(val entries: List<IosOpenUiPropVal>) : IosOpenUiVal

    /** 动作表达式（裸动作或 Action([...]) 包装；保留原始动作供 dispatch 回传）。 */
    class ActionListVal(val actions: List<OpenUiAction>) : IosOpenUiVal

    /** $binding 引用（渲染期自 state 快照解析）。 */
    class BindingVal(val name: String) : IosOpenUiVal

    /** 嵌套组件实例。 */
    class NodeVal(val node: IosOpenUiNodeMirror) : IosOpenUiVal

    /** 未知组件占位节点（前向兼容可见降级，OpenUiValue.UnknownComponent 镜像）。 */
    class UnknownNodeVal(val type: String, val statementId: String) : IosOpenUiVal
}

/** 组件节点镜像：type + 已按库 schema 归位的命名属性。 */
class IosOpenUiNodeMirror(
    val type: String,
    val statementId: String,
    val props: List<IosOpenUiPropVal>,
)

/** state 快照镜像（$ 前缀键保留原样，Swift 侧按 "$name" 查）。 */
class IosOpenUiStateMirror(val entries: List<IosOpenUiPropVal>)

internal fun mirrorOf(value: OpenUiValue): IosOpenUiVal = when (value) {
    is OpenUiValue.Str -> IosOpenUiVal.TextVal(value.v)
    is OpenUiValue.Num -> IosOpenUiVal.NumVal(value.v)
    is OpenUiValue.Bool -> IosOpenUiVal.BoolVal(value.v)
    OpenUiValue.Null -> IosOpenUiVal.NullVal
    is OpenUiValue.Arr -> IosOpenUiVal.ListVal(value.v.map(::mirrorOf))
    is OpenUiValue.Obj -> IosOpenUiVal.ObjVal(value.v.map { (k, v) -> IosOpenUiPropVal(k, mirrorOf(v)) })
    is OpenUiValue.ActionVal -> IosOpenUiVal.ActionListVal(value.actions)
    is OpenUiValue.Binding -> IosOpenUiVal.BindingVal(value.name)
    is OpenUiValue.ComponentVal -> IosOpenUiVal.NodeVal(mirrorOf(value))
    is OpenUiValue.UnknownComponent -> IosOpenUiVal.UnknownNodeVal(value.type, value.statementId)
}

internal fun mirrorOf(node: OpenUiValue.ComponentVal): IosOpenUiNodeMirror = IosOpenUiNodeMirror(
    type = node.type,
    statementId = node.statementId,
    props = node.props.map { (k, v) -> IosOpenUiPropVal(k, mirrorOf(v)) },
)

internal fun mirrorOf(state: Map<String, OpenUiValue>): IosOpenUiStateMirror =
    IosOpenUiStateMirror(state.map { (k, v) -> IosOpenUiPropVal(k, mirrorOf(v)) })

// ── 快照与副作用（Swift 消费面）─────────────────────────────────────────────

/** 卡片状态快照（prepare / dispatch 后的统一产物）。 */
sealed interface IosOpenUiCardSnapshot {
    /** 就绪：组件树根 + state 快照 + 持久化 viewState + 会话全量照片（翻页预览集）。 */
    class Ready(
        val root: IosOpenUiNodeMirror,
        val state: IosOpenUiStateMirror,
        val viewState: String,
        val photoUris: List<String>,
    ) : IosOpenUiCardSnapshot

    /** 降级：handle 缺失 / prepare Failed（宿主渲染 fallbackSummary 降级卡）。 */
    class Degraded(val errors: List<String>) : IosOpenUiCardSnapshot
}

/** 引擎外副作用（dispatch 顺序执行中收集，Swift 落地）。 */
sealed interface IosOpenUiSideEffect {
    class OpenUrl(val url: String) : IosOpenUiSideEffect
    class OpenPhoto(val uris: List<String>) : IosOpenUiSideEffect
    class ToAssistant(val text: String) : IosOpenUiSideEffect
    class Custom(val name: String, val args: List<IosOpenUiVal>) : IosOpenUiSideEffect
    /** @Run Mutation 失败（官方语义：中断链；宿主 toast 兜底）。 */
    class RunFailed : IosOpenUiSideEffect
}

/** dispatch 产物：新快照 + 副作用序列。 */
class IosOpenUiDispatchResult(
    val snapshot: IosOpenUiCardSnapshot,
    val sideEffects: List<IosOpenUiSideEffect>,
)

// ── 卡片控制器 ───────────────────────────────────────────────────────────────

/**
 * iOS OpenUI 卡宿主控制器（对位 androidApp OpenUiCardHost 的 Kotlin 侧职责）：
 * session 构建/prepare（local_handle 取数 + viewState 恢复）→ 快照镜像出 Swift；
 * 动作链串行 dispatch（@Set/@Reset/@Run 引擎内执行，@OpenUrl/@OpenPhoto/@ToAssistant/
 * 自定义动作收集为副作用交宿主）。信号 6 铁律：一切异常兜底为 Degraded 快照，不逃逸 Swift。
 */
class IosOpenUiCardController(
    private val program: String,
    initialViewState: String?,
    private val handleStore: IosLocalHandleStore?,
    private val writeTools: OpenUiToolResolver?,
    private val mediaSeam: IosOpenUiMediaSeam,
) {

    private val dispatchMutex = Mutex()
    private var session: OpenUiSession? = null
    private var handleFailed = false

    /** 已发射 viewState 指纹（同值不重复持久化，对齐 Android lastEmittedViewState）。 */
    private var lastEmittedViewState: String =
        OpenUiContract.encodeViewState(OpenUiContract.decodeViewState(initialViewState))

    private val tools = OpenUiToolResolver { name, args ->
        if (name == OpenUiContract.TOOL_LOCAL_HANDLE) {
            when (val resolved = resolveLocalHandle(args)) {
                is HandleResolution.Ok -> resolved.value
                is HandleResolution.Err -> {
                    handleFailed = true
                    throw OpenUiHandleFailure(resolved.reason)
                }
            }
        } else {
            writeTools?.call(name, args) ?: throw OpenUiToolNotFoundException(name)
        }
    }

    /** 解析+取数+求值；Degraded 不缓存（handle 数据恢复后可自愈，对齐 Android）。 */
    suspend fun prepare(): IosOpenUiCardSnapshot = withContext(Dispatchers.Default) {
        val initialState = OpenUiContract.decodeViewState(viewStateSource())
            .map { (name, value) -> "\$$name" to OpenUiValue.Str(value) }
            .toMap()
        val fresh = OpenUiSession(program = program, tools = tools, initialState = initialState)
        val result = try {
            fresh.prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logErrors(listOf("${t.message ?: t::class.simpleName}"))
            return@withContext IosOpenUiCardSnapshot.Degraded(listOf("parse-exception"))
        }
        logErrors(errorTexts(result))
        val failedHandle = consumeHandleFailure()
        if (failedHandle || result !is OpenUiResult.Rendered) {
            session = null
            return@withContext IosOpenUiCardSnapshot.Degraded(errorTexts(result))
        }
        session = fresh
        readySnapshot(fresh)
    }

    /**
     * 动作链执行（宿主整链交入，串行 Mutex）；引擎动作（@Set/@Reset/@Run）即时执行，
     * 其余收集为副作用；@Run 失败中断后续链（官方语义）。空 session（未 prepare）no-op。
     */
    suspend fun dispatch(actions: List<OpenUiAction>): IosOpenUiDispatchResult = dispatchMutex.withLock {
        val current = session ?: return IosOpenUiDispatchResult(
            IosOpenUiCardSnapshot.Degraded(listOf("not-prepared")),
            emptyList()
        )
        val effects = mutableListOf<IosOpenUiSideEffect>()
        for (action in actions) {
            when (routeOf(action.name)) {
                Route.STATE_WRITE -> {
                    current.applyAction(action)
                    logErrors(current.lastErrors().map(::errorText))
                }
                Route.RUN -> {
                    val target = action.target
                    if (target == null) {
                        logErrors(listOf("@Run 缺目标语句名，忽略并继续链"))
                    } else {
                        val ok = current.runToolAction(target)
                        logErrors(current.lastErrors().map(::errorText))
                        if (!ok) {
                            effects += IosOpenUiSideEffect.RunFailed()
                            break
                        }
                    }
                }
                Route.OPEN_URL -> {
                    (action.args.firstOrNull() as? OpenUiValue.Str)?.v?.let { url ->
                        if (isOpenUrlAllowed(url)) {
                            effects += IosOpenUiSideEffect.OpenUrl(url)
                        } else {
                            logErrors(listOf("@OpenUrl 非 http(s) 地址被拦截: $url"))
                        }
                    }
                }
                Route.OPEN_PHOTO -> {
                    val clicked = (action.args.firstOrNull() as? OpenUiValue.Str)?.v
                    val all = sessionPhotoUris(current)
                    val uris = if (clicked != null && all.contains(clicked)) {
                        listOf(clicked) + (all - clicked)
                    } else {
                        action.args.mapNotNull { arg -> (arg as? OpenUiValue.Str)?.v }
                    }
                    if (uris.isNotEmpty()) effects += IosOpenUiSideEffect.OpenPhoto(uris)
                }
                Route.TO_ASSISTANT -> {
                    val text = (action.args.firstOrNull() as? OpenUiValue.Str)?.v.orEmpty()
                    if (text.isNotBlank()) effects += IosOpenUiSideEffect.ToAssistant(text)
                }
                Route.CUSTOM -> effects += IosOpenUiSideEffect.Custom(action.name, action.args.map(::mirrorOf))
                Route.UNKNOWN -> logErrors(listOf("未知动作: ${action.name}"))
            }
        }
        val snapshot = if (consumeHandleFailure()) {
            IosOpenUiCardSnapshot.Degraded(emptyList())
        } else {
            current.rootOrNull()?.let { readySnapshot(current) }
                ?: IosOpenUiCardSnapshot.Degraded(errorTexts(null))
        }
        IosOpenUiDispatchResult(snapshot, effects)
    }

    /** 当前持久化 viewState（宿主落 part，viewState 变更指纹去重）。 */
    fun currentViewState(): String = lastEmittedViewState

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun viewStateSource(): String? = lastEmittedViewState.takeIf { it.isNotEmpty() }

    private fun consumeHandleFailure(): Boolean {
        val failed = handleFailed
        handleFailed = false
        return failed
    }

    private fun errorText(error: OpenUiError): String = "${error.code}@${error.statementId}: ${error.message}"

    private fun errorTexts(result: OpenUiResult?): List<String> = when (result) {
        is OpenUiResult.Rendered -> result.errors.map(::errorText)
        is OpenUiResult.Failed -> result.errors.map(::errorText)
        null -> session?.lastErrors()?.map(::errorText) ?: emptyList()
    }

    /** Ready 快照（调用方保证 rootOrNull 非空）：viewState 指纹去重 + 全量照片浏览集。 */
    private suspend fun readySnapshot(session: OpenUiSession): IosOpenUiCardSnapshot {
        val root = session.rootOrNull()
            ?: return IosOpenUiCardSnapshot.Degraded(errorTexts(null))
        // viewState 指纹去重（全部 Str 标量持久化，对齐 Android strStateSnapshot）
        val encoded = OpenUiContract.encodeViewState(strStateSnapshot(session.state))
        if (encoded != lastEmittedViewState) lastEmittedViewState = encoded
        return IosOpenUiCardSnapshot.Ready(
            root = mirrorOf(root),
            state = mirrorOf(session.state),
            viewState = lastEmittedViewState,
            photoUris = sessionPhotoUris(session),
        )
    }

    /** 会话全量照片 URI（浏览完整性，对齐 Android allSessionPhotoUris）：state 投影 id + program 内 handle 全量 entries。 */
    private suspend fun sessionPhotoUris(session: OpenUiSession): List<String> {
        val projectedIds = session.state.values.flatMap { value ->
            (value as? OpenUiValue.Arr)?.v?.mapNotNull { item ->
                (item as? OpenUiValue.Obj)?.v?.get("id")?.let { id -> (id as? OpenUiValue.Num)?.v?.toLong() }
            } ?: emptyList()
        }.distinct()
        val handleIds = HANDLE_ID_REGEX.findAll(program).map { it.groupValues[1] }.toList()
        val fullIds = handleIds.flatMap { handleId ->
            val payload = handleStore?.payloadOf(OpenUiContract.DOMAIN_SCAN, handleId) ?: return@flatMap emptyList()
            decodeEntries(payload).map { it.id }
        }
        return mediaSeam.resolveUris((fullIds + projectedIds).distinct())
    }

    private fun decodeEntries(payload: String): List<ScanPhotoEntry> = runCatching {
        hostJson.decodeFromString(ListSerializer(ScanPhotoEntry.serializer()), payload)
    }.getOrElse { emptyList() }

    /** local_handle 工具实现：domain=scan 按 fragment 投影（histogram/photos/videos），对齐 Android OpenUiCardHost。 */
    private fun resolveLocalHandle(args: Map<String, OpenUiValue>): HandleResolution {
        val handle = (args["handle"] as? OpenUiValue.Str)?.v
            ?: return HandleResolution.Err("local_handle 缺 handle 参数")
        val parsed = OpenUiContract.parseLocalHandle(handle)
            ?: return HandleResolution.Err("handle 不可解析：$handle")
        val store = handleStore
            ?: return HandleResolution.Err("handle 存储未接线")
        val payload = store.payloadOf(parsed.domain, parsed.id)
            ?: return HandleResolution.Err("handle 缺失：$handle")
        if (parsed.domain != OpenUiContract.DOMAIN_SCAN) {
            return HandleResolution.Err("未知 domain：${parsed.domain}")
        }
        val entries = decodeEntries(payload)
        if (entries.isEmpty()) {
            // 空 entries 合法（空命中集理论上不注册句柄，此处防御；histogram 空桶同态）
            return when (parsed.fragment) {
                null, OpenUiContract.FRAGMENT_HISTOGRAM ->
                    HandleResolution.Ok(OpenUiValue.Arr(emptyList()))
                OpenUiContract.FRAGMENT_PHOTOS, OpenUiContract.FRAGMENT_VIDEOS ->
                    HandleResolution.Ok(OpenUiValue.Arr(emptyList()))
                else -> HandleResolution.Err("未知 fragment：${parsed.fragment}")
            }
        }
        return when (parsed.fragment) {
            null, OpenUiContract.FRAGMENT_HISTOGRAM -> HandleResolution.Ok(
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
            OpenUiContract.FRAGMENT_PHOTOS -> HandleResolution.Ok(photoRefsOf(ScanPhotoProjection.toPhotoRefs(entries)))
            OpenUiContract.FRAGMENT_VIDEOS -> HandleResolution.Ok(photoRefsOf(ScanPhotoProjection.toVideoRefs(entries)))
            else -> HandleResolution.Err("未知 fragment：${parsed.fragment}")
        }
    }

    private fun photoRefsOf(refs: List<PhotoRef>): OpenUiValue.Arr = OpenUiValue.Arr(
        refs.map { ref ->
            OpenUiValue.Obj(
                mapOf(
                    "id" to OpenUiValue.Num(ref.id.toDouble()),
                    "date" to OpenUiValue.Str(ref.date),
                )
            )
        }
    )

    private enum class Route { STATE_WRITE, OPEN_URL, OPEN_PHOTO, CUSTOM, TO_ASSISTANT, RUN, UNKNOWN }

    private fun routeOf(name: String): Route = when (name) {
        OpenUiContract.Actions.SET, OpenUiContract.Actions.RESET -> Route.STATE_WRITE
        OpenUiContract.Actions.OPEN_URL -> Route.OPEN_URL
        OpenUiContract.Actions.OPEN_PHOTO -> Route.OPEN_PHOTO
        OpenUiContract.Actions.TO_ASSISTANT -> Route.TO_ASSISTANT
        OpenUiContract.Actions.RUN -> Route.RUN
        in OpenUiLibrary.customActionNames -> Route.CUSTOM
        else -> Route.UNKNOWN
    }

    private fun logErrors(errors: List<String>) {
        if (errors.isNotEmpty()) Logger.w(TAG, "OpenUI 卡错误(${errors.size}): ${errors.joinToString("; ")}")
    }

    private sealed interface HandleResolution {
        class Ok(val value: OpenUiValue) : HandleResolution
        class Err(val reason: String) : HandleResolution
    }

    private class OpenUiHandleFailure(reason: String) : Exception(reason)

    private companion object {
        const val TAG = "OpenUI"
        val HANDLE_ID_REGEX = Regex("local://scan/([0-9a-f]+)#photos")
        val hostJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/** viewState 持久化快照：store 中全部 Str 标量（对齐 Android strStateSnapshot）。 */
private fun strStateSnapshot(state: Map<String, OpenUiValue>): Map<String, String> =
    state.mapNotNull { (key, value) -> (value as? OpenUiValue.Str)?.v?.let { text -> key to text } }.toMap()

/** @OpenUrl scheme 闸（对齐 Android isOpenUrlAllowed）：只放行 http/https。 */
internal fun isOpenUrlAllowed(url: String): Boolean {
    val scheme = url.trim().substringBefore("://").lowercase()
    return scheme == "https" || scheme == "http"
}

// ── 流式预览（openui P2，⑤）─────────────────────────────────────────────────

/**
 * 流式预览工厂：每帧前缀新建 session 做前缀解析（空 resolver 不取数），预算判定在
 * Swift 侧（24k 对齐 Android PREVIEW_PARSE_BUDGET_CHARS）；解析失败返 null（保留上一帧好预览）。
 */
object IosOpenUiPreviewFactory {

    suspend fun preparePreview(program: String): IosOpenUiCardSnapshot? = withContext(Dispatchers.Default) {
        runCatching {
            val session = OpenUiSession(
                program = program,
                tools = OpenUiToolResolver { _, _ -> OpenUiValue.Null },
            )
            (session.prepare() as? OpenUiResult.Rendered)?.let { rendered ->
                IosOpenUiCardSnapshot.Ready(
                    root = mirrorOf(rendered.root),
                    state = mirrorOf(session.state),
                    viewState = "",
                    photoUris = emptyList(),
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }
    }
}
