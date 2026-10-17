package com.mamba.picme.features.cloudagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.features.common.chat.AgentMarkdown

/**
 * 云端 Agent 最小聊天页（spec §5，调试入口实验页——豁免 PARITY 固化，转正时再入 08-UI-SPECS）。
 * 顶栏通道标记（网关/BYOK）+ 消息气泡列表 + 输入框 + 发送/停止。
 */
@Composable
fun CloudAgentChatScreen(
    viewModel: CloudAgentChatViewModel,
    onNavigateBack: () -> Unit,
    onGoRemoteModelSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.prepare() }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题 + 通道胶囊
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.cloud_agent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            (state as? CloudAgentChatViewModel.UiState.Chat)?.let { chat ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = stringResource(
                            if (chat.channel == "byok") R.string.cloud_agent_channel_byok
                            else R.string.cloud_agent_channel_gateway,
                        ),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        when (val s = state) {
            CloudAgentChatViewModel.UiState.Preparing ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            CloudAgentChatViewModel.UiState.NeedByokConsent ->
                AlertDialog(
                    onDismissRequest = onNavigateBack,
                    title = { Text(stringResource(R.string.cloud_agent_byok_dialog_title)) },
                    text = { Text(stringResource(R.string.cloud_agent_byok_dialog_message)) },
                    confirmButton = {
                        TextButton(onClick = onGoRemoteModelSettings) {
                            Text(stringResource(R.string.cloud_agent_byok_dialog_go_settings))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.useGatewayInstead() }) {
                            Text(stringResource(R.string.cloud_agent_byok_dialog_use_gateway))
                        }
                    },
                )
            is CloudAgentChatViewModel.UiState.Failed ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (s.reason == "not_logged_in") stringResource(R.string.cloud_agent_not_logged_in)
                        else s.reason,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            is CloudAgentChatViewModel.UiState.Chat -> ChatContent(s, viewModel)
        }
    }
}

@Composable
private fun ChatContent(chat: CloudAgentChatViewModel.UiState.Chat, viewModel: CloudAgentChatViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.size - 1)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chat.messages, key = { message -> message.id }) { message ->
                when (message.role) {
                    CloudAgentChatViewModel.Role.USER -> Bubble(text = message.text, isUser = true)
                    CloudAgentChatViewModel.Role.ASSISTANT -> Bubble(text = message.text, isUser = false)
                    CloudAgentChatViewModel.Role.TOOL -> Text(
                        text = stringResource(R.string.cloud_agent_tool_call, message.text),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CloudAgentChatViewModel.Role.ERROR -> Text(
                        text = message.text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (chat.inFlight) {
                item {
                    Text(
                        text = stringResource(R.string.cloud_agent_thinking),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        var input by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { value -> input = value },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.cloud_agent_input_hint)) },
                maxLines = 3,
            )
            if (chat.inFlight) {
                IconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.cloud_agent_stop))
                }
            } else {
                IconButton(
                    onClick = {
                        viewModel.send(input.trim())
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.cloud_agent_send))
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isUser) {
                    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    AgentMarkdown(
                        content = text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}
