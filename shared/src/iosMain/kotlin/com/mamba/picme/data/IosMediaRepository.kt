package com.mamba.picme.data

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.repository.AccessState
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * `MediaRepository` 的 iOS 实现：Photos framework 调用下沉到 Swift（[IosMediaRepositoryBridge]），
 * 本类只做 DTO → 领域模型映射与 Flow 编排，不接触任何 iOS 平台类型（D7 组合根模式）。
 *
 * id 派生：`localIdentifier.hashCode()`（进程内稳定；仅用于查找/删除定位，不持久化、不跨端）。
 */
class IosMediaRepository(
    private val bridge: IosMediaRepositoryBridge
) : MediaRepository {

    override val allMedia: Flow<List<MediaAsset>> = callbackFlow {
        trySend(fetch())
        bridge.addChangeListener { trySend(fetch()) }
        awaitClose { bridge.removeChangeListener() }
    }

    /** 快照语义（接口契约）：每次收集反映收集时刻权限态，不监听运行时变更。 */
    override val accessState: Flow<AccessState> = flow {
        emit(bridge.currentAccessState())
    }

    override suspend fun getMediaById(id: Long): MediaAsset? =
        fetch().firstOrNull { it.id == id }

    /**
     * iOS 插入（保存图片到相册）Phase 5 相册段不需要（相机拍照保存在 Task 12+ 相机段经
     * `PHAssetChangeRequest.creationRequestForAsset` 另行落地）；返回 -1 表未实现。
     */
    override suspend fun insertMedia(mediaAsset: MediaAsset): Long = -1L

    override suspend fun deleteMedia(mediaAsset: MediaAsset) {
        bridge.deleteMedia(listOf(mediaAsset.uri))
    }

    override suspend fun deleteMediaByIds(ids: List<Long>) {
        val identifiers = fetch().filter { it.id in ids }.map { it.uri }
        if (identifiers.isNotEmpty()) {
            bridge.deleteMedia(identifiers)
        }
    }

    /** iOS 无 MediaStore scan 等价物：数据新鲜度由 PHPhotoLibraryObserver 驱动，无需主动刷新。 */
    override suspend fun refreshMediaLibrary() = Unit

    /** iOS 无 Room/内存缓存双层镜像，allMedia 由 PHPhotoLibraryObserver 即时驱动，乐观移除为空操作。 */
    override suspend fun removeTrashedFromLocalCache(uris: List<String>) = Unit

    /** iOS 无标签库（TAG 属 Phase 6），无刷新等价物。 */
    override fun refreshLabels() = Unit

    // Android 11+ IntentSender 删除授权三方法：iOS 删除走 PHAssetChangeRequest 系统确认窗，无待授权队列。
    override fun getPendingDeleteUris(): List<String> = emptyList()

    override fun clearPendingDeleteUris() = Unit

    override suspend fun executePendingDeletes() = Unit

    private fun fetch(): List<MediaAsset> = bridge.fetchAllMedia().map(::toDomain)

    private fun toDomain(item: IosMediaItem): MediaAsset = MediaAsset(
        id = item.localIdentifier.hashCode().toLong(),
        uri = item.localIdentifier,
        type = if (item.mediaType == "VIDEO") MediaType.VIDEO else MediaType.PHOTO,
        captureDate = item.captureDateMs,
        fileName = item.fileName,
        duration = item.durationMs
    )
}
