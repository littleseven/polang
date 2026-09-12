package com.mamba.picme.domain.repository

import com.mamba.picme.agent.core.model.context.MediaAsset
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    val allMedia: Flow<List<MediaAsset>>

    /**
     * 相册访问授权状态（双端统一抽象；Android 实现按 READ_MEDIA_* 权限映射，AddOnly 仅 iOS）。
     *
     * 快照语义：每次收集反映收集时刻的权限态，不监听运行时权限变更；需刷新时重新收集。
     * Android 实现以图片访问权限为准（视频权限不独立体现）。
     */
    val accessState: Flow<AccessState>

    suspend fun insertMedia(mediaAsset: MediaAsset): Long

    suspend fun deleteMedia(mediaAsset: MediaAsset)

    suspend fun deleteMediaByIds(ids: List<Long>)

    suspend fun getMediaById(id: Long): MediaAsset?

    suspend fun refreshMediaLibrary()

    /**
     * 回收站删除成功后的乐观本地移除：同步剔除本地缓存（内存缓存 + 本地持久层）中的
     * 这些 URI 并触发 allMedia 重发射，让网格即时收缩，不等 refreshMediaLibrary 全量重扫。
     * 平台无双层缓存镜像的实现（iOS 由系统照片库变更监听驱动）可为空操作。
     */
    suspend fun removeTrashedFromLocalCache(uris: List<String>)

    /** 轻量刷新:bump refreshVersion 触发 allMedia 重 emit(不重载 MediaStore)。单张 retag 后用。 */
    fun refreshLabels()

    /**
     * 获取需要用户授权删除的 URI 字面值列表（Android 11+）
     */
    fun getPendingDeleteUris(): List<String>

    /**
     * 清除待删除的 URI 列表
     */
    fun clearPendingDeleteUris()

    /**
     * 在用户授权后执行删除操作
     */
    suspend fun executePendingDeletes()
}
