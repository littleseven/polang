package com.mamba.picme.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.data.repository.RelationDisplayItem
import com.mamba.picme.domain.memory.MemorySource
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRelationPicker
import com.mamba.picme.features.common.personRelationLabelRes
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「AI 记忆」管理页：人物关系 + 事实记忆的查看/编辑/删除/清空。
 *
 * 形态参照 [DataPrivacyScreen]（设置二级页 + onNavigateBack）；
 * 两个 section 的数据均由 Room Flow 驱动，改动即时刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFactsScreen(
    viewModel: MemoryFactsViewModel,
    onNavigateBack: () -> Unit
) {
    val facts by viewModel.facts.collectAsState()
    var editingFact by remember { mutableStateOf<MemoryFactEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.settings_ai_memory)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) },
                actions = {
                    if (facts.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text(
                                text = stringResource(R.string.memory_facts_clear_all),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (facts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.memory_facts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // ── 事实记忆 section ──
                item(key = "facts_header") {
                    MemorySectionHeader(titleRes = R.string.memory_facts_facts_section)
                }
                if (facts.isEmpty()) {
                    item(key = "facts_empty") {
                        MemorySectionEmptyRow(textRes = R.string.memory_facts_empty)
                    }
                } else {
                    items(items = facts, key = { fact -> "fact_${fact.factId}" }) { fact ->
                        MemoryFactRow(
                            fact = fact,
                            onEdit = { editingFact = fact },
                            onDelete = { viewModel.forgetFact(fact.factId) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    // ── 编辑对话框（content + category） ─────────────────────────
    editingFact?.let { fact ->
        var content by remember(fact.factId) { mutableStateOf(fact.content) }
        var category by remember(fact.factId) { mutableStateOf(fact.category.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingFact = null },
            title = { Text(stringResource(R.string.memory_facts_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.memory_facts_content_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text(stringResource(R.string.memory_facts_category_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = content.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.updateFact(fact.factId, trimmed, category)
                        }
                        editingFact = null
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingFact = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 注：人物关系编辑统一在人物信息编辑页（PersonInfoScreen），本页专注事实记忆。

    // ── 清空全部二次确认 ────────────────────────────────────────
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.memory_facts_clear_confirm_title)) },
            text = {
                Text(stringResource(R.string.memory_facts_clear_confirm_message, facts.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllFacts()
                        showClearConfirm = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.memory_facts_clear_all),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** section 标题（与设置页分组标题风格一致：primary 色小标题） */
@Composable
private fun MemorySectionHeader(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** 单 section 空态（另一 section 有内容时的局部空态） */
@Composable
private fun MemorySectionEmptyRow(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** 单条人物关系："X 是我的 Y"（有自定义称呼时显示原话）+ 编辑/删除 */
@Composable
private fun PersonRelationRow(
    relation: RelationDisplayItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                R.string.memory_relation_item_format,
                relation.subjectName,
                relation.customLabel ?: stringResource(personRelationLabelRes(relation.predicate))
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.memory_facts_edit_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.memory_facts_delete_desc),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 单条事实：内容 + 来源标签 + 创建时间 + 编辑/删除入口 */
@Composable
private fun MemoryFactRow(
    fact: MemoryFactEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fact.content,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = buildString {
                    append(stringResource(memoryFactSourceLabelRes(fact.source)))
                    fact.category?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    append(" · ")
                    append(formatFactTime(fact.createdAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.memory_facts_edit_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.memory_facts_delete_desc),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 来源枚举 → 本地化标签（UI 层映射，不把领域枚举名直接展示给用户） */
private fun memoryFactSourceLabelRes(source: String): Int =
    when (MemorySource.fromStored(source)) {
        MemorySource.JS_DISPATCH -> R.string.memory_facts_source_js
        MemorySource.CHAT_TOOL, null -> R.string.memory_facts_source_chat
    }

private fun formatFactTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
