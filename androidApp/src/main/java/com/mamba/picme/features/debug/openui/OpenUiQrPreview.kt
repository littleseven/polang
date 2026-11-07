package com.mamba.picme.features.debug.openui

import android.content.Context
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.features.chat.openui.OpenUiPhotoResolver
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenUI 扫码/会话预览的共享辅助层（debug 专用）：
 * 扫码载荷/手输 → 会话 ID → `GET /agent/sessions/{id}/final-program` 拉最终程序 →
 * 端上真实渲染器预览（Skill 调试产物与 Agent 直接输出同口径）。
 *
 * 2026-11-07 起 UI 宿主自 Debug 页 Tab 迁入云端 Agent 调试页（OpenUiDebugOverlay，
 * features/cloudagent/），本文件只留数据/解析/stub 供 Overlay 与渲染点共用。
 * 渲染依赖用 stub：调试会话的相册投影句柄跨设备无意义（handle 缺失走既有降级路径）。
 * 每次成功加载留底（SharedPreferences JSON，上限 20 条）。
 */

/** 预览记录（留底条目）。 */
data class QrPreviewEntry(
    val sessionId: String,
    val model: String,
    val program: String,
    val loadedAt: Long,
) {
    val summary: String get() = program.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: program.take(60)
}

/** SharedPreferences JSON 留底（prepend，上限 20 条）。 */
internal class QrPreviewHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<QrPreviewEntry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            QrPreviewEntry(
                sessionId = o.getString("session_id"),
                model = o.optString("model"),
                program = o.getString("program"),
                loadedAt = o.optLong("loaded_at"),
            )
        }
    }.getOrDefault(emptyList())

    fun record(entry: QrPreviewEntry) {
        val rest = list().filter { it.sessionId != entry.sessionId }
        val arr = JSONArray()
        (listOf(entry) + rest).take(MAX_ENTRIES).forEach { e ->
            arr.put(
                JSONObject()
                    .put("session_id", e.sessionId)
                    .put("model", e.model)
                    .put("program", e.program)
                    .put("loaded_at", e.loadedAt),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val PREFS_NAME = "debug_openui_qr_history"
        const val KEY = "entries"
        const val MAX_ENTRIES = 20
    }
}

/** 扫码载荷/手输 → 会话 ID：polang://openui-preview?session=xxx 或裸 UUID/短 id。 */
internal fun parsePreviewSessionId(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val fromLink = Regex("""[?&]session=([A-Za-z0-9-]+)""").find(text)?.groupValues?.get(1)
    val candidate = fromLink ?: text
    return candidate.takeIf { it.matches(Regex("""[A-Za-z0-9-]{8,64}""")) }
}

/** 句柄/照片 stub：调试会话投影跨设备无意义，恒 null 走既有降级（画廊同口径）。 */
internal val previewHandleLookup = object : HandleLookup {
    override suspend fun payloadOf(domain: String, handleId: String): String? = null
}
internal val previewPhotoResolver = OpenUiPhotoResolver { null }

internal fun formatQrPreviewTs(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
