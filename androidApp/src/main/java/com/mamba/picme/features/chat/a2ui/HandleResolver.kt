package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.model.processor.A2uiJsonMessageParser
import androidx.a2ui.model.protocol.A2uiServerToClientMessage
import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import com.mamba.picme.domain.chat.a2ui.GalleryHistogramBuilder
import com.mamba.picme.domain.chat.a2ui.HistogramBucket
import com.mamba.picme.domain.chat.a2ui.ScanPhotoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** HandleResolver 输出：填槽完成 or 降级（US-6）。 */
sealed interface A2uiResolution {
    data class Ready(val messages: List<A2uiServerToClientMessage>) : A2uiResolution
    data class Degraded(val reason: String) : A2uiResolution
}

/**
 * local:// handle → 本地全量数据填槽（spec §3.2；GenUI 只生成骨架不生成数据）。
 *
 * 扫描 messagesJson 信封数组：updateDataModel 的字符串值若以 local:// 开头，解析 handle →
 * HandleLookup 取 payload → 按 domain 算数据（scan=月桶）→ 替换为结构化值重新构造信封。
 * 任一 handle 缺失/不可解析 → 整张卡 Degraded（宁缺毋滥，不混真假数据）。
 */
class HandleResolver(
    private val lookup: HandleLookup,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun resolve(surfaceId: String, messagesJson: String): A2uiResolution =
        withContext(Dispatchers.Default) {
            val parser = A2uiJsonMessageParser { input -> AndroidA2uiJsonReader(input) }
            val array = runCatching { JSONArray(messagesJson) }
                .getOrElse { return@withContext A2uiResolution.Degraded("messages_json_malformed") }
            val filled = mutableListOf<A2uiServerToClientMessage>()
            for (index in 0 until array.length()) {
                // 元素级防护：非对象元素（如 ["oops"]）不得逃逸 withContext 炸主线程（终审 M1）
                val envelope = array.optJSONObject(index)
                    ?: return@withContext A2uiResolution.Degraded("envelope_invalid")
                val dataModel = envelope.optJSONObject("updateDataModel")
                if (dataModel == null) {
                    val updateComponents = envelope.optJSONObject("updateComponents")
                    if (updateComponents != null) {
                        // BarChart selected 裸值归一化（2026-10-24 sf-012a0 事故，同 data 批量展开
                        // 的防御纵深）：裸字面量不订阅数据模型，点选高亮静默失效——改绑 /selected；
                        // 非空字面量（LLM 意图预选）在组件消息之后播种回数据模型，时序保证初值生效
                        val seed = normalizeBarChartSelectedBinding(updateComponents)
                        filled += parseEnvelope(parser, envelope)
                            ?: return@withContext A2uiResolution.Degraded("envelope_invalid")
                        if (seed != null) {
                            val componentSurfaceId =
                                updateComponents.optString("surfaceId", surfaceId)
                            filled += A2uiUpdateDataModelMessage(
                                surfaceId = componentSurfaceId,
                                path = A2uiCardContract.DATA_PATH_SELECTED,
                                value = seed,
                            )
                        }
                        continue
                    }
                    filled += parseEnvelope(parser, envelope)
                        ?: return@withContext A2uiResolution.Degraded("envelope_invalid")
                    continue
                }
                val modelSurfaceId = dataModel.optString("surfaceId", surfaceId)
                val dataBatch = dataModel.optJSONObject("data")
                if (dataBatch != null) {
                    // 防御纵深（fix-a2ui-blueprint-contract）：server 描述已禁止 data 批量形式，
                    // 端侧对存量/越约蓝图逐 key 展开为 path="/"+key 的单绑定消息再过 handle 解析
                    val keys = dataBatch.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val batchValue = dataBatch.opt(key)
                        if (batchValue is String && batchValue.startsWith(A2uiCardContract.HANDLE_SCHEME)) {
                            when (val resolution = resolveHandle(modelSurfaceId, "/$key", batchValue)) {
                                is A2uiResolution.Ready -> filled += resolution.messages
                                is A2uiResolution.Degraded -> return@withContext resolution
                            }
                        } else {
                            filled += A2uiUpdateDataModelMessage(
                                surfaceId = modelSurfaceId,
                                path = "/$key",
                                value = batchValue,
                            )
                        }
                    }
                    continue
                }
                val value = dataModel.opt("value")
                if (value !is String || !value.startsWith(A2uiCardContract.HANDLE_SCHEME)) {
                    filled += parseEnvelope(parser, envelope)
                        ?: return@withContext A2uiResolution.Degraded("envelope_invalid")
                    continue
                }
                when (val resolution = resolveHandle(modelSurfaceId, dataModel.optString("path", "/"), value)) {
                    is A2uiResolution.Ready -> filled += resolution.messages
                    is A2uiResolution.Degraded -> return@withContext resolution
                }
            }
            A2uiResolution.Ready(filled)
        }

    /** local:// handle → 填槽消息；任一环节失败 → Degraded（宁缺毋滥，不混真假数据）。 */
    private suspend fun resolveHandle(surfaceId: String, path: String, handle: String): A2uiResolution {
        val parsed = A2uiCardContract.parseLocalHandle(handle)
            ?: return A2uiResolution.Degraded("handle_malformed")
        val payload = lookup.payloadOf(parsed.domain, parsed.id)
            ?: return A2uiResolution.Degraded("handle_missing")
        val replacement: Any = when (parsed.domain) {
            A2uiCardContract.DOMAIN_SCAN -> {
                val entries = runCatching {
                    json.decodeFromString<List<ScanPhotoEntry>>(payload)
                }.getOrElse { return A2uiResolution.Degraded("payload_malformed") }
                GalleryHistogramBuilder.buildMonthly(entries, timeZone).toPlainList()
            }
            else -> return A2uiResolution.Degraded("domain_unknown")
        }
        return A2uiResolution.Ready(
            listOf(A2uiUpdateDataModelMessage(surfaceId = surfaceId, path = path, value = replacement)),
        )
    }

    private fun parseEnvelope(
        parser: A2uiJsonMessageParser,
        envelope: JSONObject,
    ): A2uiServerToClientMessage? =
        runCatching { parser.parse(envelope.toString()) }.getOrNull()

    private fun List<HistogramBucket>.toPlainList(): List<Map<String, Any>> =
        map { bucket -> mapOf("label" to bucket.label, "count" to bucket.count) }
}

/**
 * BarChart selected 裸值归一化（纯函数，测试锚点；防御纵深同 data 批量展开）。
 *
 * 契约要求 selected 写 {"path":"/selected"} 数据绑定；LLM 偶发写裸字符串字面量
 * （如 "selected": ""）或干脆缺键——两者都不订阅数据模型，点选高亮静默失效。
 * 对 updateComponents 载荷内每个 BarChart 组件：selected 非 JSONObject（绑定形态）
 * 一律改写/补写为 {"path":"/selected"}。
 *
 * @return 需播种回数据模型的初始选中值（非空裸字面量，LLM 意图预选）；否则 null。
 *         播种消息由调用方置于组件消息之后（时序保证初值覆盖默认态）。
 */
internal fun normalizeBarChartSelectedBinding(updateComponents: JSONObject): String? {
    val components = updateComponents.optJSONArray("components") ?: return null
    var seed: String? = null
    for (index in 0 until components.length()) {
        val component = components.optJSONObject(index) ?: continue
        if (component.optString("component") != PolangBarChartComponent.NAME) continue
        val raw = component.opt("selected")
        if (raw is JSONObject) continue // 已是绑定形态（含 {"path":...}），不动
        component.put("selected", JSONObject(mapOf("path" to A2uiCardContract.DATA_PATH_SELECTED)))
        (raw as? String)?.takeIf { it.isNotEmpty() }?.let { literal -> seed = literal }
    }
    return seed
}
