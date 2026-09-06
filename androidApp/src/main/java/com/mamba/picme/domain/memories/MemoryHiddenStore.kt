package com.mamba.picme.domain.memories

import kotlinx.coroutines.flow.Flow

/**
 * 回忆隐藏存储（引擎无关接口；生产实现 = DataStore stringSet，见 data/preferences/）。
 * 隐藏条目为 Memory.id（稳定 id，如 `on_this_day:09-05`），量级为个位数，整体集合读写。
 */
interface MemoryHiddenStore {

    /** 已隐藏回忆 id 集（响应式：隐藏操作触发重发）。 */
    val ids: Flow<Set<String>>

    /** 隐藏一条回忆（幂等）。 */
    suspend fun hide(id: String)
}
