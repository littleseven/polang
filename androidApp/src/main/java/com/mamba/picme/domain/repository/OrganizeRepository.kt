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
     * （Room Flow 自动驱动 UI 刷新）。返回本次补算张数。幂等，可反复调用。
     */
    suspend fun backfillQualitySignals(batchLimit: Int = 200): Int
}
