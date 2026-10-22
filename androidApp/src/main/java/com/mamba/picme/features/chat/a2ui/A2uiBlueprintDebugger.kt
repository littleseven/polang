package com.mamba.picme.features.chat.a2ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A2UI 蓝图调试器（debug 包专用，BuildConfig.DEBUG 门控）。
 *
 * 徽标内容全为协议标识符（A2UI v0.9 · sf-xxxxxxxx），无自然语言，[I18N] 豁免。
 * 查看器解析失败时显示原文+原因，永不崩聊天页。
 */

/** 一条已解析的 A2UI 线协议消息：kind = 消息对象中除 version 外的首键。 */
data class BlueprintMessage(
    val kind: String,
    val version: String?,
    val prettyJson: String,
)

/** 蓝图解析三态：Ok=成功逐条列出；Malformed=JSON 非法或不是数组，原文保留兜底。 */
sealed interface BlueprintParse {
    data class Ok(val messages: List<BlueprintMessage>) : BlueprintParse
    data class Malformed(val raw: String, val reason: String) : BlueprintParse
}

private val blueprintJson = Json { prettyPrint = true }

/** 把 messagesJson（JSON 数组，元素为 A2UI 消息对象）解析成可读列表；任何非法输入降级 Malformed。 */
fun parseBlueprintMessages(messagesJson: String): BlueprintParse {
    val root = try {
        blueprintJson.parseToJsonElement(messagesJson)
    } catch (e: Exception) {
        return BlueprintParse.Malformed(messagesJson, e.message ?: "parse error")
    }
    val array = root as? JsonArray
        ?: return BlueprintParse.Malformed(messagesJson, "root is not a JSON array")
    val messages = array.mapIndexed { index, element ->
        val obj = element as? JsonObject
            ?: return BlueprintParse.Malformed(messagesJson, "message[$index] is not a JSON object")
        val version = obj["version"]?.toString()?.removeSurrounding("\"")
        val kind = obj.keys.firstOrNull { key -> key != "version" } ?: "unknown"
        val pretty = try {
            blueprintJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), obj)
        } catch (_: Exception) {
            obj.toString()
        }
        BlueprintMessage(kind = kind, version = version, prettyJson = pretty)
    }
    return BlueprintParse.Ok(messages)
}

/**
 * Debug 徽标 overlay：右上角胶囊 `A2UI v0.9 · sf-xxxxxxxx`（surfaceId 截前 8 位），点击开蓝图查看器。
 * 版本取首条消息的 version（catalogId 由查看器详情展示）。
 */
@Composable
fun A2uiDebugBadge(
    surfaceId: String,
    messagesJson: String,
    modifier: Modifier = Modifier,
) {
    var showViewer by remember(surfaceId) { mutableStateOf(false) }
    val parse = remember(messagesJson) { parseBlueprintMessages(messagesJson) }
    val version = (parse as? BlueprintParse.Ok)?.messages?.firstOrNull()?.version ?: "?"
    val shortId = surfaceId.take(8)

    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            )
            .clickable { showViewer = true }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "A2UI $version · $shortId",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontFamily = FontFamily.Monospace,
        )
    }

    if (showViewer) {
        A2uiBlueprintViewerDialog(
            surfaceId = surfaceId,
            parse = parse,
            onDismiss = { showViewer = false },
        )
    }
}

/** 蓝图查看器：surfaceId 全量 + 消息分区 pretty JSON（可滚动）+ 复制全部；Malformed 时原文+原因。 */
@Composable
private fun A2uiBlueprintViewerDialog(
    surfaceId: String,
    parse: BlueprintParse,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = surfaceId,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (parse) {
                    is BlueprintParse.Ok -> parse.messages.forEachIndexed { index, message ->
                        Text(
                            text = "#${index + 1} ${message.kind}" +
                                (message.version?.let { v -> " · $v" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = message.prettyJson,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (index < parse.messages.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                    is BlueprintParse.Malformed -> {
                        Text(
                            text = parse.reason,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = parse.raw,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val all = when (parse) {
                        is BlueprintParse.Ok -> parse.messages
                            .joinToString("\n\n") { message -> message.prettyJson }
                        is BlueprintParse.Malformed -> parse.raw
                    }
                    clipboard.setText(AnnotatedString(all))
                },
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
