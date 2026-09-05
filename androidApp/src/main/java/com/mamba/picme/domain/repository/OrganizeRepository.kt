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
}
