package com.mamba.picme.domain.repository

import com.mamba.picme.domain.organize.OrganizeItem
import kotlinx.coroutines.flow.Flow

/**
 * 整理中心（F1）类目数据源接口：Room `media_assets` 轻量投影 + MediaStore 批量 meta
 * 按 content uri 合并的 [OrganizeItem] 快照。生产实现为 data 层 `OrganizeRepositoryImpl`。
 */
interface OrganizeRepository {

    /** hub 统计流：媒体库任何写触发重算。 */
    fun observeItems(): Flow<List<OrganizeItem>>

    /** 类目详情全量（一次性）。 */
    suspend fun loadItems(): List<OrganizeItem>

    /**
     * 惰性补算模糊/曝光分：对 blurScore 为 null 的图片分批计算并回写 Room
     * （Room Flow 自动驱动 UI 刷新）。幂等，可反复调用。
     * [excludeUris] = 已知永久失败行（解码失败/云端占位符），本批跳过——
     * 调用方跨批累计记忆，防失败行永久占据批次头部导致后续行静默停摆。
     */
    suspend fun backfillQualitySignals(
        batchLimit: Int = 200,
        excludeUris: Set<String> = emptySet(),
    ): BackfillBatchResult
}

/**
 * 单批补算产出：attempted = 本批待算张数，written = 成功回写张数；
 * [failedUris] = 本批 attempted 但解码/回写失败的 uri（调用方并入排除集跨批跳过）。
 */
data class BackfillBatchResult(
    val attempted: Int,
    val written: Int,
    val failedUris: List<String> = emptyList(),
)
