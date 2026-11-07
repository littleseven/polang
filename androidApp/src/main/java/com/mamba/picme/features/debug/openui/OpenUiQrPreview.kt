package com.mamba.picme.features.debug.openui

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.data.remote.picme.CloudAgentClient
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.features.chat.openui.OpenUiCardHost
import com.mamba.picme.features.chat.openui.OpenUiPhotoResolver
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenUI 扫码预览 Tab（debug 专用，0.9.35 配套端侧）：
 * 扫管理后台结果页/回放页二维码（载荷 `polang://openui-preview?session={id}`）或手输
 * 会话 ID → `GET /agent/sessions/{id}/final-program` 拉最终程序 → 端上真实渲染器
 * （[OpenUiCardHost] 与 Chat 同管线）预览。Skill 调试产物与 Agent 直接输出同口径。
 *
 * 渲染依赖用 stub：调试会话的相册投影句柄跨设备无意义（handle 缺失走既有降级路径）。
 * 每次成功加载留底（SharedPreferences JSON，上限 20 条），点击历史行重载。
 *
 * [I18N]：会话 ID/model/程序原文为数据非 UI 文案；页面文案走 strings.xml 五语。
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
private class QrPreviewHistoryStore(context: Context) {
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

private sealed interface QrPreviewState {
    data object Idle : QrPreviewState
    data object Loading : QrPreviewState
    data class Error(val message: String) : QrPreviewState
    data class Loaded(val entry: QrPreviewEntry) : QrPreviewState
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
private val previewHandleLookup = object : HandleLookup {
    override suspend fun payloadOf(domain: String, handleId: String): String? = null
}
private val previewPhotoResolver = OpenUiPhotoResolver { null }

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OpenUiQrPreviewTab() {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val token by app.container.userPreferencesRepository.serverAuthTokenFlow.collectAsState(initial = "")
    val client = remember {
        CloudAgentClient(deviceIdProvider = { app.cachedDeviceIdProvider() })
    }
    val historyStore = remember { QrPreviewHistoryStore(context) }
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<QrPreviewState>(QrPreviewState.Idle) }
    var history by remember { mutableStateOf(historyStore.list()) }
    var showScanner by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val parseErrorText = stringResource(R.string.debug_qr_error_parse)

    fun load(raw: String) {
        val sessionId = parsePreviewSessionId(raw)
        if (sessionId == null) {
            state = QrPreviewState.Error(parseErrorText)
            return
        }
        state = QrPreviewState.Loading
        scope.launch {
            if (token.isBlank()) {
                state = QrPreviewState.Error(context.getString(R.string.debug_qr_error_no_token))
                return@launch
            }
            client.getFinalProgram(token, sessionId).fold(
                onSuccess = { json ->
                    val entry = QrPreviewEntry(
                        sessionId = sessionId,
                        model = json.optString("model"),
                        program = json.optString("program"),
                        loadedAt = System.currentTimeMillis(),
                    )
                    historyStore.record(entry)
                    history = historyStore.list()
                    state = QrPreviewState.Loaded(entry)
                },
                onFailure = { e ->
                    state = QrPreviewState.Error(e.message ?: "load failed")
                },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Text(
                text = stringResource(R.string.debug_qr_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.debug_qr_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.debug_qr_input_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                Button(onClick = { load(input) }, enabled = state !is QrPreviewState.Loading) {
                    Text(stringResource(R.string.debug_qr_load))
                }
                OutlinedButton(
                    onClick = {
                        if (cameraPermission.status.isGranted) {
                            showScanner = true
                        } else {
                            cameraPermission.launchPermissionRequest()
                        }
                    },
                ) {
                    Text(stringResource(R.string.debug_qr_scan))
                }
            }
        }
        when (val s = state) {
            is QrPreviewState.Loading -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(Spacing.lg).padding(end = Spacing.sm))
                    Text(stringResource(R.string.debug_qr_loading), style = MaterialTheme.typography.bodySmall)
                }
            }
            is QrPreviewState.Error -> item {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            is QrPreviewState.Loaded -> item {
                Card {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text(
                            text = "${s.entry.sessionId.take(8)} · ${s.entry.model}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OpenUiCardHost(
                            part = MessagePart.OpenUiCard(
                                partId = "qr-${s.entry.sessionId}",
                                program = s.entry.program,
                                fallbackSummary = "QR preview degraded",
                            ),
                            handleLookup = previewHandleLookup,
                            photoResolver = previewPhotoResolver,
                            onViewStateChange = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            QrPreviewState.Idle -> Unit
        }
        if (history.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.debug_qr_history, history.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        historyStore.clear()
                        history = emptyList()
                    }) {
                        Text(stringResource(R.string.debug_qr_clear))
                    }
                }
            }
            items(history, key = { it.sessionId }) { entry ->
                OutlinedButton(
                    onClick = {
                        input = entry.sessionId
                        state = QrPreviewState.Loaded(entry)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${entry.sessionId.take(8)} · ${entry.model} · ${formatTs(entry.loadedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = entry.summary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (showScanner) {
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            QrCodeScanner(
                onResult = { text ->
                    showScanner = false
                    input = text
                    load(text)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun formatTs(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
