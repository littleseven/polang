package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsBridgeException
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.js.NativeHandler
import com.mamba.picme.agent.core.js.asyncHandler
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.CommandRisk
import com.mamba.picme.agent.core.model.context.AgentAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `capability.dispatch` async handler：JS → CapabilityRegistry 写通路。
 *
 * JS 契约：`await bridge.callAsync('capability.dispatch', {method, params})`
 * - `delete_media` `{ids:[id1,id2,...]}` —— DESTRUCTIVE，需用户确认
 * - `favorite_media` `{id:N, favorite:bool}` —— REVERSIBLE_WRITE，需用户确认
 * - `select_media` `{id:N, selected:bool}` —— REVERSIBLE_WRITE，需用户确认
 * - `get_gallery_summary` `{}` —— READ_ONLY，直接 dispatch 直通
 * - `remember_fact` / `forget_fact` / `recall_memory` —— 事实记忆（写需确认，读直通）
 * - `remember_person_relation` `{name, relation}` / `forget_person_relation` `{name}` —— REVERSIBLE_WRITE，需用户确认
 * - `query_person_relation` `{name?}` —— READ_ONLY，直接 dispatch 直通
 * 完整列表见 [SUPPORTED_METHODS]；其余 method 抛 [JsBridgeException]（Promise reject，JS 可 try/catch）。
 *
 * 写操作挂起等待 [requestConfirmation]（由 ChatViewModel 弹窗实现），
 * [confirmationTimeoutMs] 超时按拒绝处理；拒绝/超时/执行失败均抛异常让 JS catch。
 * 并发写确认（如 `Promise.all` 并发 callAsync）由 [confirmationMutex] 串行化：
 * 同一时刻只弹一个确认框，前一个确认完成后下一个才弹出（各自独享完整超时时长）。
 *
 * 本类只编排「解析 → 风险分级 → 确认 → dispatch → 结果转换」，
 * 确认交互与 dispatch 目标均由构造注入（显式注入，便于纯 JVM 单测）。
 */
class CapabilityDispatchHandler(
    private val dispatch: suspend (AgentCommand) -> Result<AgentAction>,
    private val requestConfirmation: suspend (method: String, risk: CommandRisk, targetCount: Int, previewIds: List<String>) -> Boolean,
    private val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
) {

    companion object {
        const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 120_000L

        /** 确认框缩略图预览条数上限。 */
        const val MAX_PREVIEW_IDS = 6

        /** 支持的 method 列表（写操作 + 只读直通示例），用于错误提示与 LLM 指引对齐。 */
        val SUPPORTED_METHODS = listOf(
            "delete_media", "favorite_media", "select_media", "get_gallery_summary",
            "remember_fact", "forget_fact", "recall_memory",
            "remember_person_relation", "forget_person_relation", "query_person_relation",
        )
    }

    /** 写确认互斥锁：Promise.all 并发 callAsync 时串行弹确认框，避免 StateFlow 单槽互相覆盖。 */
    private val confirmationMutex = Mutex()

    /** 注册到 JsRuntime 的 NativeHandler 形态。 */
    fun asNativeHandler(): NativeHandler.Async = asyncHandler("capability.dispatch") { args -> invoke(args) }

    @Suppress("ThrowsCount") // 多个参数校验 throw 是必要的前置检查
    suspend fun invoke(args: JsValue): JsValue {
        val obj = args as? JsValue.Obj
            ?: throw JsBridgeException(
                JsBridgeException.HANDLER_ERROR,
                "capability.dispatch requires object args {method, params}",
            )
        val method = (obj.entries["method"] as? JsValue.Str)?.value
            ?: throw JsBridgeException(
                JsBridgeException.HANDLER_ERROR,
                "capability.dispatch requires 'method' string",
            )
        val params = obj.entries["params"] as? JsValue.Obj
        val command = buildCommand(method, params)

        if (CommandRisk.ofMethod(method) != CommandRisk.READ_ONLY) {
            val confirmed = confirmationMutex.withLock {
                withTimeoutOrNull(confirmationTimeoutMs) {
                    requestConfirmation(
                        method,
                        CommandRisk.ofMethod(method),
                        targetCountOf(command),
                        previewIdsOf(command),
                    )
                } ?: false
            }
            if (!confirmed) {
                throw JsBridgeException(
                    JsBridgeException.HANDLER_ERROR,
                    "operation rejected or confirmation timed out: $method",
                )
            }
        }

        val action = dispatch(command).getOrElse { cause ->
            throw JsBridgeException(
                JsBridgeException.HANDLER_ERROR,
                "dispatch failed: ${cause.message ?: "unknown error"}",
            )
        }
        return actionToJsValue(action)
    }

    /** method + params → AgentCommand；不支持的 method 抛错（不 crash、不进 dispatch）。 */
    private fun buildCommand(method: String, params: JsValue.Obj?): AgentCommand = when (method) {
        "delete_media" -> {
            val ids = (params?.entries?.get("ids") as? JsValue.Arr)?.items
                ?.mapNotNull { (it as? JsValue.Num)?.value?.toLong() }
                ?: emptyList()
            if (ids.isEmpty()) {
                throw JsBridgeException(
                    JsBridgeException.HANDLER_ERROR,
                    "delete_media requires params.ids (non-empty number array)",
                )
            }
            AgentCommand.DeleteMedia(mediaIds = ids.map { it.toString() })
        }
        "favorite_media" -> AgentCommand.FavoriteMedia(
            mediaId = requireIdParam(method, params),
            favorite = boolParam(params, "favorite", default = true),
        )
        "select_media" -> AgentCommand.SelectMedia(
            mediaId = requireIdParam(method, params),
            selected = boolParam(params, "selected", default = true),
        )
        "get_gallery_summary" -> AgentCommand.GetGallerySummary(includeDetails = true)
        "remember_fact" -> {
            val content = strParam(params, "content")
                ?: throw JsBridgeException(
                    JsBridgeException.HANDLER_ERROR,
                    "remember_fact requires params.content (string)",
                )
            AgentCommand.RememberFact(
                content = content,
                category = strParam(params, "category"),
                source = "JS_DISPATCH",
            )
        }
        "forget_fact" -> {
            val factId = (params?.entries?.get("fact_id") as? JsValue.Num)?.value?.toLong()
            val query = strParam(params, "query")
            if (factId == null && query == null) {
                throw JsBridgeException(
                    JsBridgeException.HANDLER_ERROR,
                    "forget_fact requires params.fact_id (number) or params.query (string)",
                )
            }
            AgentCommand.ForgetFact(factId = factId, query = query)
        }
        "recall_memory" -> AgentCommand.RecallMemory(
            query = strParam(params, "query").orEmpty(),
        )
        "remember_person_relation" -> {
            val name = strParam(params, "name")
                ?: throw JsBridgeException(
                    JsBridgeException.HANDLER_ERROR,
                    "remember_person_relation requires params.name (string)",
                )
            val relation = strParam(params, "relation")
                ?: throw JsBridgeException(
                    JsBridgeException.HANDLER_ERROR,
                    "remember_person_relation requires params.relation (string)",
                )
            AgentCommand.RememberPersonRelation(name = name, relation = relation)
        }
        "forget_person_relation" -> {
            val name = strParam(params, "name")
                ?: throw JsBridgeException(
                    JsBridgeException.HANDLER_ERROR,
                    "forget_person_relation requires params.name (string)",
                )
            AgentCommand.ForgetPersonRelation(name = name)
        }
        "query_person_relation" -> AgentCommand.QueryPersonRelation(
            name = strParam(params, "name"),
        )
        else -> throw JsBridgeException(
            JsBridgeException.HANDLER_ERROR,
            "unsupported method '$method' (supported: ${SUPPORTED_METHODS.joinToString()})",
        )
    }

    private fun requireIdParam(method: String, params: JsValue.Obj?): String {
        val id = (params?.entries?.get("id") as? JsValue.Num)?.value?.toLong()
            ?: throw JsBridgeException(
                JsBridgeException.HANDLER_ERROR,
                "$method requires params.id (number)",
            )
        return id.toString()
    }

    private fun boolParam(params: JsValue.Obj?, name: String, default: Boolean): Boolean =
        (params?.entries?.get(name) as? JsValue.Bool)?.value ?: default

    private fun strParam(params: JsValue.Obj?, name: String): String? =
        (params?.entries?.get(name) as? JsValue.Str)?.value?.ifBlank { null }

    private fun targetCountOf(command: AgentCommand): Int =
        if (command is AgentCommand.DeleteMedia) command.mediaIds.size else 1

    /** 确认框预览用的媒体 id（前 [MAX_PREVIEW_IDS] 个；由调用方解析为缩略图）。 */
    private fun previewIdsOf(command: AgentCommand): List<String> = when (command) {
        is AgentCommand.DeleteMedia -> command.mediaIds.take(MAX_PREVIEW_IDS)
        is AgentCommand.FavoriteMedia -> listOf(command.mediaId)
        is AgentCommand.SelectMedia -> listOf(command.mediaId)
        else -> emptyList()
    }

    /** AgentAction → JsValue；Error action 抛异常（Promise reject）。 */
    private fun actionToJsValue(action: AgentAction): JsValue = when (action) {
        is AgentAction.Error -> throw JsBridgeException(JsBridgeException.HANDLER_ERROR, action.message)
        is AgentAction.Success -> JsValue.Obj(
            linkedMapOf(
                "ok" to JsValue.Bool(true),
                "method" to JsValue.Str(AgentCommand.getMethodName(action.command)),
            )
        )
        is AgentAction.TextReply -> JsValue.Obj(
            linkedMapOf(
                "ok" to JsValue.Bool(true),
                "message" to JsValue.Str(action.message),
            )
        )
        is AgentAction.BatchResult -> JsValue.Obj(
            linkedMapOf(
                "ok" to JsValue.Bool(action.results.all { it.isSuccess }),
                "resultCount" to JsValue.Num(action.results.size.toDouble()),
            )
        )
    }
}
