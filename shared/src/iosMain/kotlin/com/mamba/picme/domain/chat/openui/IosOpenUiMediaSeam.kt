package com.mamba.picme.domain.chat.openui

import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.flow.first

/**
 * OpenUI photoId → iOS 媒体解析缝（2026-11-12，对位 androidApp OpenUiPhotoResolver）：
 * photoId = `MediaAsset.id` = `localIdentifier.hashCode()`（[com.mamba.picme.data.IosMediaRepository]
 * 同一 id 空间；Android「Room 正 id / 负 synthetic id 双通道」在 iOS 退化为单 id 空间——
 * `getMediaById` 本就经 allMedia 全量反查，正负 id 同径命中）。
 *
 * 解析产物 uri = PHAsset localIdentifier（[PRIVACY] uri 只在端侧，不进 program/payload/LLM）；
 * Swift 渲染层经 ThumbnailLoader/localIdentifier 加载图像。faceFocusY / aspectRatio
 * iOS 媒体桥不携带（nil → 渲染层回落组件缺省 4:3，平台差异台账项）。
 *
 * 快照缓存：单卡生命周期只 fetch 一次（PHPhotoLibrary 全量枚举是重操作，
 * Android 逐 id 查 Room DAO 的对等优化）；媒体库变更期间重建卡即刷新。
 */
class IosOpenUiMediaSeam(private val repository: MediaRepository) {

    private var idToUriCache: Map<Long, String>? = null

    /** 批量解析（顺序对齐 [ids]，失配跳过——Android resolveAllUrisInOrder 的 mapNotNull 语义）。 */
    suspend fun resolveUris(ids: List<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val cache = ensureCache()
        return ids.mapNotNull { id -> cache[id] }
    }

    /** 单 id 解析（Image/Video/Banner 等单图组件）。 */
    suspend fun resolveUri(id: Long): String? = ensureCache()[id]

    private suspend fun ensureCache(): Map<Long, String> {
        idToUriCache?.let { return it }
        val cache = repository.allMedia.first().associate { asset -> asset.id to asset.uri }
        idToUriCache = cache
        return cache
    }
}
