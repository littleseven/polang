package com.mamba.picme.features.cloudagent

import android.Manifest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.mamba.picme.data.remote.picme.CloudAgentClient
import com.mamba.picme.features.debug.openui.OpenUiDemoItem
import com.mamba.picme.features.debug.openui.QrPreviewEntry
import com.mamba.picme.features.debug.openui.QrPreviewHistoryStore
import com.mamba.picme.features.debug.openui.formatQrPreviewTs
import com.mamba.picme.features.debug.openui.openUiDemoGroups
import com.mamba.picme.features.debug.openui.openUiElementShapeNote
import com.mamba.picme.features.debug.openui.parsePreviewSessionId
import com.mamba.picme.features.debug.openui.QrCodeScanner
import kotlinx.coroutines.launch

/**
 * OpenUI 调试合一页（debug 专用，2026-11-07 自 Debug 页「OpenUI 画廊」与「扫码预览」
 * 两个 Tab 合并迁入云端 Agent 调试页，头部 OpenUI 图标进入）：
 * 会话加载（手输/扫码 → `GET /agent/sessions/{id}/final-program`）+ 预览历史 +
 * 全组件画廊同页承载。加载成功的最终程序经 [onLoaded] 注入云端 Agent 页消息流
 * （OPENUI_CARD 内存消息）渲染并关闭本页；画廊组件卡在本页内联渲染（原画廊口径）。
 *
 * [I18N]：页面文案复用 debug_qr_* 五语字符串；「OpenUI」与组件/分组名为协议标识符豁免。
 */

/** 会话加载结果（Agent First：枚举优于条件）。 */
internal sealed interface OpenUiLoadResult {
    data class Ok(val entry: QrPreviewEntry) : OpenUiLoadResult
    data object ParseError : OpenUiLoadResult
    data object NoToken : OpenUiLoadResult
    data class Failure(val message: String) : OpenUiLoadResult
}

/**
 * 会话加载器：解析输入 → 拉 final-program → 留底历史。供云端 Agent 页头部扫码直达
 * 与 [OpenUiDebugOverlay] 共用同一加载口径。
 */
internal class OpenUiPreviewLoader(
    private val client: CloudAgentClient,
    private val token: () -> String,
    val historyStore: QrPreviewHistoryStore,
) {
    suspend fun load(raw: String): OpenUiLoadResult {
        val sessionId = parsePreviewSessionId(raw) ?: return OpenUiLoadResult.ParseError
        val authToken = token()
        if (authToken.isBlank()) return OpenUiLoadResult.NoToken
        return client.getFinalProgram(authToken, sessionId).fold(
            onSuccess = { json ->
                val entry = QrPreviewEntry(
                    sessionId = sessionId,
                    model = json.optString("model"),
                    program = json.optString("program"),
                    loadedAt = System.currentTimeMillis(),
                )
                historyStore.record(entry)
                OpenUiLoadResult.Ok(entry)
            },
            onFailure = { error -> OpenUiLoadResult.Failure(error.message ?: "load failed") },
        )
    }
}

/** 注入卡片的来源标注（会话短 id · model）。 */
internal fun openUiPreviewLabel(entry: QrPreviewEntry): String =
    "${entry.sessionId.take(8)} · ${entry.model}"

@Composable
internal fun rememberOpenUiPreviewLoader(): OpenUiPreviewLoader {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val token by app.container.userPreferencesRepository.serverAuthTokenFlow.collectAsState(initial = "")
    val client = remember { CloudAgentClient(deviceIdProvider = { app.cachedDeviceIdProvider() }) }
    val historyStore = remember { QrPreviewHistoryStore(context) }
    // token 经 State 捕获，lambda 内读取恒为最新值
    return remember(client, historyStore) { OpenUiPreviewLoader(client, { token }, historyStore) }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OpenUiDebugOverlay(
    onLoaded: (label: String, program: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val loader = rememberOpenUiPreviewLoader()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(loader.historyStore.list()) }
    var showScanner by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val parseErrorText = stringResource(R.string.debug_qr_error_parse)
    val noTokenText = stringResource(R.string.debug_qr_error_no_token)

    fun submit(raw: String) {
        if (loading) return
        loading = true
        errorText = null
        scope.launch {
            when (val result = loader.load(raw)) {
                is OpenUiLoadResult.Ok -> {
                    history = loader.historyStore.list()
                    onLoaded(openUiPreviewLabel(result.entry), result.entry.program)
                    onDismiss()
                }
                OpenUiLoadResult.ParseError -> errorText = parseErrorText
                OpenUiLoadResult.NoToken -> errorText = noTokenText
                is OpenUiLoadResult.Failure -> errorText = result.message
            }
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：关闭 + 协议名（OpenUI 为协议标识符，[I18N] 豁免，徽标同口径）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                    }
                    Text(
                        text = "OpenUI",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.debug_qr_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { value -> input = value },
                            label = { Text(stringResource(R.string.debug_qr_input_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            modifier = Modifier.padding(top = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = { submit(input) }, enabled = !loading) {
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
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(Spacing.lg).padding(start = Spacing.sm),
                                )
                                Text(
                                    text = stringResource(R.string.debug_qr_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        errorText?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = Spacing.sm),
                            )
                        }
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
                                    loader.historyStore.clear()
                                    history = emptyList()
                                }) {
                                    Text(stringResource(R.string.debug_qr_clear))
                                }
                            }
                        }
                        items(history, key = { "hist-${it.sessionId}" }) { entry ->
                            OutlinedButton(
                                onClick = {
                                    onLoaded(openUiPreviewLabel(entry), entry.program)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "${entry.sessionId.take(8)} · ${entry.model} · ${formatQrPreviewTs(entry.loadedAt)}",
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
                    item {
                        HorizontalDivider(modifier = Modifier.padding(top = Spacing.sm))
                        Text(
                            text = "OpenUI Load All · ${openUiDemoGroups.sumOf { it.demos.size }} components · ${openUiDemoGroups.size} groups",
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = Spacing.sm),
                        )
                    }
                    openUiDemoGroups.forEach { group ->
                        item(key = "group-${group.title}") {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.sm),
                            )
                        }
                        items(group.demos, key = { "demo-${it.name}" }) { demo ->
                            OpenUiDemoItem(demo)
                        }
                    }
                    item {
                        Text(
                            text = openUiElementShapeNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    submit(text)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
