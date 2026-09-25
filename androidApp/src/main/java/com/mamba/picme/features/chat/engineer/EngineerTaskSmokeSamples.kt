package com.mamba.picme.features.chat.engineer

import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/** [DEV_ONLY] /task 冒烟样本：五态任务卡各一，供 ui-driver 截图与视觉走查（对齐 HtmlCardSmokeSamples 先例）。 */
object EngineerTaskSmokeSamples {

    fun all(nowMs: Long): List<EngineerTaskState> = listOf(
        EngineerTaskReducer.initial("task_smoke_running", "帮我整理相册模块的包结构", nowMs - 30_000)
            .copy(sid = "a1b2c3d4e5f6", stage = "Edit", recentStages = listOf("Read", "Grep", "Edit"), turns = 3),
        EngineerTaskReducer.initial("task_smoke_truncated", "重构 ChatViewModel", nowMs - 90_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.AWAITING_CONTINUE, truncatedReason = "max_turns", turns = 10),
        EngineerTaskReducer.initial("task_smoke_deliver", "修复登录页崩溃", nowMs - 150_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.AWAITING_DELIVER, turns = 6, fileChangeCount = 3,
                recentStages = listOf("Read", "Edit", "Bash", "file_change:Login.kt")),
        EngineerTaskReducer.initial("task_smoke_done", "写一个日期工具函数", nowMs - 300_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.COMPLETED, turns = 2, resultSummary = "已创建 DateUtils.formatRelative()"),
        EngineerTaskReducer.initial("task_smoke_failed", "升级 Koog 到 9.9", nowMs - 400_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.FAILED, errorSummary = "connection lost"),
    )
}
