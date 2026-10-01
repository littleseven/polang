package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass

/** 扫描态主状态卡的阶段标签（UI 层按枚举取五语整句文案键，不跨语拼接）。 */
enum class ScanStage { FACE, CLUSTER, CONTENT, SEMANTIC, PREPARING }

/** 主操作按钮语义；NONE = 过渡态禁用（暂停中/取消中）。 */
enum class ScanCardAction { PAUSE, RESUME, RETRY_FAILED, NONE }

/**
 * 叙述行结构化数据（spec §5.2）：任务级进度只允许以「第 x/y 张」自然语言形态出现，
 * 禁止渲染为百分比。UI 层 stringResource 参数化插值（西/法语语序占位符）。
 */
data class ScanNarrative(val processed: Int, val total: Int, val etaMs: Long?)

/**
 * 扫描态主状态卡 UI 模型（spec §7 边界态矩阵的纯映射产物）。
 * 返回 null = 不渲染卡片（IDLE/CANCELLED/零失败 COMPLETED 由 ScanActionCard caption 承接）。
 */
data class ScanCardUiModel(
    val stage: ScanStage,
    val isPaused: Boolean,
    val isTerminalWithFailures: Boolean,
    val primaryAction: ScanCardAction,
    val cancelEnabled: Boolean,
    val narrative: ScanNarrative?,
    val failedCount: Int,
)

/** TagScanPass → ScanStage 映射（通知标题与卡片阶段名共用同一口径）。 */
fun scanStageOf(pass: TagScanPass?): ScanStage = when (pass) {
    TagScanPass.FACE_DETECTION -> ScanStage.FACE
    TagScanPass.DBSCAN -> ScanStage.CLUSTER
    TagScanPass.IMAGE_TAGGING -> ScanStage.CONTENT
    TagScanPass.MOBILE_CLIP_ENCODING -> ScanStage.SEMANTIC
    null -> ScanStage.PREPARING
}

/**
 * 活跃态（RUNNING/PAUSING/PAUSED/CANCELLING）调用点不重传阶段时保留上一帧：
 * 暂停后卡片/通知标题仍是「人脸阶段」，不回退成「准备中」误导阶段丢失。
 * 终态与 IDLE 不保留陈旧阶段。
 */
fun retainedCurrentPass(state: ScanSessionState, incoming: TagScanPass?, previous: TagScanPass?): TagScanPass? =
    when (state) {
        ScanSessionState.IDLE, ScanSessionState.CANCELLED, ScanSessionState.COMPLETED -> incoming
        else -> incoming ?: previous
    }

/** 会话进度 → 卡片 UI 模型纯映射；UI 零逻辑，全矩阵 JVM 可测。 */
fun scanCardUiModel(progress: TagScanSessionProgress): ScanCardUiModel? {
    val stage = scanStageOf(progress.currentPass)
    val narrative = if (progress.total > 0) {
        ScanNarrative(
            processed = progress.processed,
            total = progress.total,
            // 暂停时 ETA 无意义，叙述行只保留「第 x/y 张」
            etaMs = if (progress.state == ScanSessionState.PAUSED) null else progress.estimatedRemainingMs,
        )
    } else {
        null
    }
    return when (progress.state) {
        ScanSessionState.RUNNING -> ScanCardUiModel(
            stage = stage, isPaused = false, isTerminalWithFailures = false,
            primaryAction = ScanCardAction.PAUSE, cancelEnabled = true,
            narrative = narrative, failedCount = progress.failed,
        )
        ScanSessionState.PAUSING -> ScanCardUiModel(
            stage = stage, isPaused = false, isTerminalWithFailures = false,
            primaryAction = ScanCardAction.NONE, cancelEnabled = true,
            narrative = narrative, failedCount = progress.failed,
        )
        ScanSessionState.PAUSED -> ScanCardUiModel(
            stage = stage, isPaused = true, isTerminalWithFailures = false,
            primaryAction = ScanCardAction.RESUME, cancelEnabled = true,
            narrative = narrative, failedCount = progress.failed,
        )
        ScanSessionState.CANCELLING -> ScanCardUiModel(
            stage = stage, isPaused = false, isTerminalWithFailures = false,
            primaryAction = ScanCardAction.NONE, cancelEnabled = false,
            narrative = narrative, failedCount = progress.failed,
        )
        ScanSessionState.COMPLETED ->
            if (progress.failed > 0) {
                ScanCardUiModel(
                    stage = stage, isPaused = false, isTerminalWithFailures = true,
                    primaryAction = ScanCardAction.RETRY_FAILED, cancelEnabled = false,
                    narrative = null, failedCount = progress.failed,
                )
            } else {
                null
            }
        ScanSessionState.CANCELLED, ScanSessionState.IDLE -> null
    }
}

/** ETA 时长渲染（天/时/分/秒两级展示）；负值按 0 处理。 */
fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
