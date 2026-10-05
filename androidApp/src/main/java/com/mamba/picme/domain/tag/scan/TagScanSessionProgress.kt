package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass

/**
 * TAG 扫描会话增强进度
 *
 * 所有数值均为**任务级**统计（一个媒体可能对应 Pass 1 + Pass 3 等多个任务），
 * 与数据库累计统计（media_assets 字段计数）口径不同，UI 上应明确区分。
 */
data class TagScanSessionProgress(
    val sessionId: String,
    val state: ScanSessionState,
    val currentPass: TagScanPass? = null,
    val currentMediaId: Long? = null,
    /** 已完成任务数（status = COMPLETED） */
    val processed: Int = 0,
    /** 本会话任务总数 */
    val total: Int = 0,
    /** 待处理任务数（status = PENDING） */
    val pending: Int = 0,
    /** 失败任务数（status = FAILED） */
    val failed: Int = 0,
    val estimatedRemainingMs: Long? = null,
    /**
     * 任务域加权进度 0..1（2026-10-05 口径立法）：自动 sweep = 基线快照口径（含积压、
     * 跨链式批次连续、中途新增照片不进分母）；手动会话 = 任务表口径。
     * null = 无可量化工作（渲染不定态）。与库域完成度（LibraryCompletion）各自独立，禁止混拼。
     */
    val weightedFraction: Float? = null,
    val messages: List<ScanMessage> = emptyList()
)

enum class ScanSessionState {
    IDLE,
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    COMPLETED
}

data class ScanMessage(
    val timestamp: Long = System.currentTimeMillis(),
    val level: MessageLevel,
    val text: String
)

enum class MessageLevel {
    INFO,
    WARNING,
    ERROR
}
