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
            val array = runCatching { JSONArray(messagesJson) }
                .getOrElse { return@withContext A2uiResolution.Degraded("messages_json_malformed") }
            val filled = mutableListOf<A2uiServerToClientMessage>()
            for (index in 0 until array.length()) {
                val envelope = array.getJSONObject(index)
                val dataModel = envelope.optJSONObject("updateDataModel")
                if (dataModel == null) {
                    filled += parseEnvelope(envelope)
                        ?: return@withContext A2uiResolution.Degraded("envelope_invalid")
                    continue
                }
                val value = dataModel.opt("value")
                if (value !is String || !value.startsWith(A2uiCardContract.HANDLE_SCHEME)) {
                    filled += parseEnvelope(envelope)
                        ?: return@withContext A2uiResolution.Degraded("envelope_invalid")
                    continue
                }
                val handle = A2uiCardContract.parseLocalHandle(value)
                    ?: return@withContext A2uiResolution.Degraded("handle_malformed")
                val payload = lookup.payloadOf(handle.domain, handle.id)
                    ?: return@withContext A2uiResolution.Degraded("handle_missing")
                val replacement: Any = when (handle.domain) {
                    A2uiCardContract.DOMAIN_SCAN -> {
                        val entries = runCatching {
                            json.decodeFromString<List<ScanPhotoEntry>>(payload)
                        }.getOrElse { return@withContext A2uiResolution.Degraded("payload_malformed") }
                        GalleryHistogramBuilder.buildMonthly(entries, timeZone).toPlainList()
                    }
                    else -> return@withContext A2uiResolution.Degraded("domain_unknown")
                }
                filled += A2uiUpdateDataModelMessage(
                    surfaceId = dataModel.optString("surfaceId", surfaceId),
                    path = dataModel.optString("path", "/"),
                    value = replacement,
                )
            }
            A2uiResolution.Ready(filled)
        }

    private fun parseEnvelope(envelope: JSONObject): A2uiServerToClientMessage? =
        runCatching {
            A2uiJsonMessageParser { input -> AndroidA2uiJsonReader(input) }
                .parse(envelope.toString())
        }.getOrNull()

    private fun List<HistogramBucket>.toPlainList(): List<Map<String, Any>> =
        map { bucket -> mapOf("label" to bucket.label, "count" to bucket.count) }
}
