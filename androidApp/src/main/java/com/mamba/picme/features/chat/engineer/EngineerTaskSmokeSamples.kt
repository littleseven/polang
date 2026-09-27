package com.mamba.picme.features.chat.engineer

import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus

/**
 * [DEV_ONLY] /task 冒烟样本：供 ui-driver 截图与视觉走查（对齐 HtmlCardSmokeSamples 先例）。
 * 2026-09-27 H2 HTML 化扩充：各态补 recentStages 覆盖展开帧（时间线/最近事件/diff 行）；
 * 新增 long 样本（RUNNING × 20 阶段）——展开超 1.0 屏应自动升格 FULLPAGE 预览 → 全屏查看器（spec §14）；
 * deliver 样本补 errorSummary 覆盖交付错误块。
 */
object EngineerTaskSmokeSamples {

    fun all(nowMs: Long): List<EngineerTaskState> = listOf(
        EngineerTaskReducer.initial("task_smoke_running", "帮我整理相册模块的包结构", nowMs - 30_000)
            .copy(sid = "a1b2c3d4e5f6", stage = "Edit", recentStages = listOf("Read", "Grep", "Edit"), turns = 3,
                updatedAtMs = nowMs - 5_000),
        EngineerTaskReducer.initial("task_smoke_truncated", "重构 ChatViewModel", nowMs - 90_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.AWAITING_CONTINUE, truncatedReason = "max_turns", turns = 10,
                costCents = 128, recentStages = listOf("Read", "Edit", "Bash", "Read", "Edit"),
                updatedAtMs = nowMs - 60_000),
        EngineerTaskReducer.initial("task_smoke_deliver", "修复登录页崩溃", nowMs - 150_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.AWAITING_DELIVER, turns = 6, fileChangeCount = 3,
                errorSummary = "gateway 502 on /deliver (retryable)",
                recentStages = listOf("Read", "Edit", "Bash", "file_change:Login.kt"), updatedAtMs = nowMs - 30_000),
        EngineerTaskReducer.initial("task_smoke_done", "写一个日期工具函数", nowMs - 300_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.COMPLETED, turns = 2, resultSummary = "已创建 DateUtils.formatRelative()",
                recentStages = listOf("Read", "Edit"), updatedAtMs = nowMs - 120_000),
        EngineerTaskReducer.initial("task_smoke_failed", "升级 Koog 到 9.9", nowMs - 400_000)
            .copy(sid = "a1b2c3d4e5f6", status = EngineerTaskStatus.FAILED, errorSummary = "connection lost",
                recentStages = listOf("Read", "Bash", "Bash"), updatedAtMs = nowMs - 200_000),
        // 展开态超一屏：验证 HTML 卡双形态复用——展开自动转 FULLPAGE 预览，点击进全屏查看器
        EngineerTaskReducer.initial("task_smoke_long", "全量扫描并修复 lint 违规", nowMs - 600_000)
            .copy(
                sid = "a1b2c3d4e5f6",
                stage = "Edit LintBaseline.kt",
                turns = 47,
                costCents = 312,
                fileChangeCount = 9,
                recentStages = listOf(
                    "Read  settings.gradle.kts", "Grep  detekt", "Bash  ./gradlew detektBaseline",
                    "Edit  detekt-config.yml", "Read  ChatViewModel.kt", "Edit  ChatViewModel.kt",
                    "Bash  ./gradlew detekt", "Read  Theme.kt", "Edit  Theme.kt",
                    "Bash  ./gradlew ktlintCheck", "file_change:detekt-config.yml",
                    "Read  build.gradle.kts", "Edit  build.gradle.kts", "Grep  LongMethod",
                    "Bash  ./gradlew detekt", "Read  EngineerTaskCard.kt", "Edit  EngineerTaskCard.kt",
                    "Bash  ./gradlew compileDebugKotlin", "Read  HtmlCard.kt", "Edit  LintBaseline.kt",
                ),
                updatedAtMs = nowMs - 10_000,
            ),
    )
}
