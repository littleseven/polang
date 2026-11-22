package com.mamba.picme.domain.dedup

import kotlinx.coroutines.flow.StateFlow

/**
 * 去重扫描会话状态机（服务化扫描的唯一状态口径）。
 *
 * RUNNING/PAUSED 为活跃态（startScan 重入守卫区间）；COMPLETED 携带
 * [DedupScanProgress.resultGroups] 供观察方落 Results；CANCELLED 为终态，
 * 观察方见 CANCELLED 或 null 均应退出扫描态。
 */
enum class DedupScanState { RUNNING, PAUSED, COMPLETED, CANCELLED }

/**
 * 会话进度快照：状态 + 阶段三元组（[phase]/[phaseIndex]/[phaseCount]）+ 计数 +
 * 渐进发现组（[foundGroups]，最新在前）+ 终态结果（[resultGroups]，COMPLETED 时非空）。
 */
data class DedupScanProgress(
    val state: DedupScanState,
    val phase: DedupLevel,
    val phaseIndex: Int,
    val phaseCount: Int,
    val scanned: Int,
    val total: Int,
    val foundGroups: List<DedupGroup>,
    val resultGroups: List<DedupGroup>? = null,
)

/**
 * 去重扫描会话接缝（Agent First：ViewModel 只观察与转发，扫描执行归服务端会话所有）。
 *
 * 生产实现两态：
 * - [com.mamba.picme.service.dedup.DedupScanSessionManager]——真执行核心（纯 Kotlin，可 JVM 单测）；
 * - [com.mamba.picme.service.dedup.ServiceBackedDedupScanSession]——intent 控制面包装。
 * JVM 单测注入 fake（MutableStateFlow + 调用计数）即可驱动 ViewModel 全部分支。
 */
interface DedupScanSession {
    /** 当前会话进度；null = 无会话（IDLE）。 */
    val progress: StateFlow<DedupScanProgress?>

    /** 发起扫描；活跃态（RUNNING/PAUSED）重入被忽略。 */
    fun startScan(config: DedupScanConfig)

    fun pauseScan()

    fun resumeScan()

    fun cancelScan()
}
