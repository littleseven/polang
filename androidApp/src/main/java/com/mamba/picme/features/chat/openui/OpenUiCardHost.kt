package com.mamba.picme.features.chat.openui

import androidx.compose.material3.a2ui.A2uiSurfaceDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import com.mamba.picme.domain.chat.a2ui.GalleryHistogramBuilder
import com.mamba.picme.domain.chat.a2ui.ScanPhotoEntry
import com.mamba.picme.domain.chat.openui.OpenUiAction
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolNotFoundException
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.features.chat.a2ui.A2uiCardDegraded
import kotlinx.serialization.json.Json

/**
 * data_ui_openui 卡的渲染宿主（openui P2，与 A2uiCardHost 对偶）：一颗 session 一张卡。
 *
 * 三态：解析中 → LoadingIndicator；handle 失败/prepare Failed → A2uiCardDegraded(fallbackSummary)
 * （US-6 宁缺毋滥同口径）；Rendered → OpenUiNodeRenderer 递归渲染。
 *
 * Query("local_handle", ...) 经 [HandleLookup] 端侧补全数据（隐私闸对偶：GenUI 只生成骨架
 * 不生成数据）；任一 handle 缺失/不可解析 → 置 handleFailed 并抛异常（engine 回退 defaults），
 * prepare 后统判降级。viewState `selected=<label>` 种 `$selected` 初始 state（US-7 对偶）。
 *
 * 用户交互（@Set/@Reset）本地消费不出端（§3.4 不变式对偶），$selected 变化经
 * [onViewStateChange] 写本地交互态。
 */
@Composable
fun OpenUiCardHost(
    part: MessagePart.OpenUiCard,
    handleLookup: HandleLookup,
    onViewStateChange: (viewState: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 键粒度收到整颗 part（data class 可比）：viewState 回流产生新 part 即重建 session、
    // 按最新 viewState 重种 $selected（同 A2uiCardHost 恢复语义）
    val resolution by produceState<OpenUiResolution?>(initialValue = null, part) {
        var handleFailed = false
        val tools = OpenUiToolResolver { name, args ->
            if (name != OpenUiContract.TOOL_LOCAL_HANDLE) throw OpenUiToolNotFoundException(name)
            try {
                resolveLocalHandle(handleLookup, args)
            } catch (e: Exception) {
                handleFailed = true
                throw e
            }
        }
        val selected = OpenUiContract.selectedOfViewState(part.viewState)
        val session = OpenUiSession(
            program = part.program,
            tools = tools,
            initialState = if (selected != null) {
                mapOf(OpenUiContract.STATE_SELECTED to OpenUiValue.Str(selected))
            } else {
                emptyMap()
            },
        )
        val result = session.prepare()
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
            OpenUiReadyContent(
                session = current.session,
                onViewStateChange = onViewStateChange,
                modifier = modifier,
            )
        }
    }
}

private sealed interface OpenUiResolution {
    data object Degraded : OpenUiResolution
    data class Ready(val session: OpenUiSession) : OpenUiResolution
}

/**
 * local_handle 工具实现：handle → HandleLookup payload → 按 domain 算数据（scan=月桶）。
 * 任一环节失败抛异常（宿主侧置 handleFailed，engine 回退 defaults 后统判降级）。
 */
private suspend fun resolveLocalHandle(
    handleLookup: HandleLookup,
    args: Map<String, OpenUiValue>,
): OpenUiValue {
    val handle = (args["handle"] as? OpenUiValue.Str)?.v
        ?: throw IllegalArgumentException("local_handle 缺 handle 参数")
    val parsed = A2uiCardContract.parseLocalHandle(handle)
        ?: throw IllegalArgumentException("handle 不可解析：$handle")
    val payload = handleLookup.payloadOf(parsed.domain, parsed.id)
        ?: throw NoSuchElementException("handle 缺失：$handle")
    return when (parsed.domain) {
        A2uiCardContract.DOMAIN_SCAN -> {
            val entries = hostJson.decodeFromString<List<ScanPhotoEntry>>(payload)
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
        }
        else -> throw IllegalArgumentException("未知 domain：${parsed.domain}")
    }
}

private val hostJson = Json { ignoreUnknownKeys = true }

@Composable
private fun OpenUiReadyContent(
    session: OpenUiSession,
    onViewStateChange: (viewState: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // applyAction 后快照刷新触发重组（session 内部状态非 Compose 可观测）
    var stateSnapshot by remember(session) { mutableStateOf(session.state) }
    var root by remember(session) { mutableStateOf(session.rootOrNull()) }
    val uriHandler = LocalUriHandler.current
    val dispatch: (OpenUiAction) -> Unit = { action ->
        when (action.name) {
            "Set", "Reset" -> {
                val before = (session.state[OpenUiContract.STATE_SELECTED] as? OpenUiValue.Str)?.v
                session.applyAction(action)
                stateSnapshot = session.state
                root = session.rootOrNull()
                val after = (session.state[OpenUiContract.STATE_SELECTED] as? OpenUiValue.Str)?.v
                if (after != before) {
                    onViewStateChange(after?.let(OpenUiContract::viewStateOfSelected) ?: "")
                }
            }
            "OpenUrl" -> (action.args.firstOrNull() as? OpenUiValue.Str)?.v?.let { url ->
                runCatching { uriHandler.openUri(url) }
            }
            // M1 不上行（§3.4 不变式对偶）：只留结构化日志线索
            "ToAssistant" -> Logger.d("Chat", "OpenUI @ToAssistant 本地消费（不上行）: ${action.args}")
            else -> Logger.d("Chat", "OpenUI 未知动作: ${action.name}")
        }
    }
    root?.let { component ->
        OpenUiNodeRenderer(
            node = component,
            state = stateSnapshot,
            onAction = dispatch,
            modifier = modifier,
        )
    }
}
