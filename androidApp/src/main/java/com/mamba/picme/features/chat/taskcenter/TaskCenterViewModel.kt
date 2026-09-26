package com.mamba.picme.features.chat.taskcenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.features.chat.engineer.TaskCenterItem
import com.mamba.picme.features.chat.engineer.TaskCenterList
import com.mamba.picme.features.chat.engineer.TaskCenterPartition
import com.mamba.picme.features.chat.parseEngineerTaskState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 任务中心页 VM（spec US-12~16 + D7）：跨会话聚合 TASK_CARD 消息，分区输出。
 *
 * 实时刷新（US-16）由 Room Flow 驱动——结构性 SSE 事件逐条落库（ChatViewModel.persistEngineerTask），
 * 本 VM 不开新通道；metadata 解析失败的消息跳过（对齐 loadMessages 容错语义）。
 */
class TaskCenterViewModel(
    chatMessageDao: ChatMessageDao,
    chatSessionDao: ChatSessionDao,
) : ViewModel() {

    val taskList: StateFlow<TaskCenterList> =
        combine(
            chatMessageDao.getTaskCardMessages(),
            chatSessionDao.getAllSessions(),
        ) { messages, sessions ->
            val sessionTitles = sessions.associate { session -> session.sessionId to session.title }
            val items = messages.mapNotNull { entity ->
                val task = parseEngineerTaskState(entity.metadata) ?: return@mapNotNull null
                TaskCenterItem(
                    sessionId = entity.sessionId,
                    title = entity.content.ifBlank { task.sourceText },
                    sessionTitle = sessionTitles[entity.sessionId],
                    task = task,
                )
            }
            TaskCenterPartition.partition(items)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TaskCenterList(active = emptyList(), history = emptyList()),
        )
}
