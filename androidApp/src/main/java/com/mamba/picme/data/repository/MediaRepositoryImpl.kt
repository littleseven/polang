package com.mamba.picme.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.core.common.Logger
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.repository.AccessState
import com.mamba.picme.domain.repository.AndroidMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import android.app.RecoverableSecurityException
import android.content.IntentSender

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class MediaRepositoryImpl(
    private val mediaDao: MediaDao,
    context: Context
) : AndroidMediaRepository {

    companion object {
        private const val TAG = "Gallery"
    }

    private val appContext = context.applicationContext
    private val appLanguage: AppLanguage by lazy {
        // 展示用：按 UI 语言取 labelsEn/labelsZh（老数据回退 labels）。DataStore 单例，读一次缓存。
        UserPreferencesRepository(appContext).getAppLanguageBlocking()
    }
    private val refreshVersion = MutableStateFlow(0)

    // 系统媒体缓存：仅在显式刷新时更新，避免每次 Room Flow 发射都查询 MediaStore
    // 这是滚动性能问题的根因之一 —— 后台索引频繁触发 Room 更新，
    // 每次更新都重新查询全部系统媒体（万级图库需要 200-500ms），
    // 导致数据管线延迟 → 缩略图加载滞后 → 大面积白屏
    private val systemMediaCache = MutableStateFlow<List<MediaAsset>>(emptyList())
    private var systemMediaLoaded = false

    // 存储待删除的 URI 列表，用于权限请求后重试
    private val pendingDeleteUris = mutableListOf<Uri>()
    private var pendingRecoverableIntentSender: IntentSender? = null
    private var pendingDeleteIds: List<Long>? = null

    override val allMedia: Flow<List<MediaAsset>> = combine(
        mediaDao.getAllMedia(),
        refreshVersion
    ) { entities, _ ->
        val dbMedia = entities.map { entity -> entity.toDomain() }
        // 懒加载：首次收集时查询系统媒体库并缓存
        // 后续仅通过 refreshMediaLibrary() 更新缓存，避免每次 Room Flow 发射都重新查询
        if (!systemMediaLoaded) {
            systemMediaCache.value = loadSystemMedia()
            systemMediaLoaded = true
        }
        mergeMedia(dbMedia = dbMedia, systemMedia = systemMediaCache.value)
    }

    /**
     * 相册访问授权状态。冷流：每次收集时按当前权限实况映射。
     * READ_MEDIA_IMAGES（或 33- 的 READ_EXTERNAL_STORAGE）→ Full；
     * API 34+ 部分照片授权（READ_MEDIA_VISUAL_USER_SELECTED）→ Limited；否则 Denied。
     */
    override val accessState: Flow<AccessState> = flow {
        emit(currentAccessState())
    }

    private fun currentAccessState(): AccessState = when {
        hasImageReadPermission() -> AccessState.Full
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> AccessState.Limited
        else -> AccessState.Denied
    }

    /**
     * 获取待删除的 URI 字面值列表（用于权限请求）
     */
    override fun getPendingDeleteUris(): List<String> {
        return pendingDeleteUris.map { uri -> uri.toString() }
    }

    /**
     * 清除待删除的 URI 列表
     */
    override fun clearPendingDeleteUris() {
        pendingDeleteUris.clear()
    }

    override fun getPendingRecoverableIntentSender(): IntentSender? =
        pendingRecoverableIntentSender

    override fun clearPendingRecoverable() {
        pendingRecoverableIntentSender = null
    }

    /** 记录媒体在查看器被打开（整理中心 v2 价值保护「用户互动」信号）；失败静默 + 日志，不影响查看体验。 */
    override suspend fun markMediaViewed(uri: String) {
        withContext(Dispatchers.IO) {
            runCatching { mediaDao.updateLastViewedAt(uri, System.currentTimeMillis()) }
                .onFailure { error -> Logger.w(TAG, "markMediaViewed failed: $uri", error) }
        }
    }

    /**
     * 在用户授权后执行删除操作
     * 注意：对于 Android 11+，MediaStore.createDeleteRequest 已经处理了删除
     * 这里只需要清理数据库记录并刷新即可
     * 对于 Android 10 (API 29)，会重试之前失败的删除操作
     */
    override suspend fun executePendingDeletes() {
        val savedIds = pendingDeleteIds
        pendingDeleteIds = null

        // Android 10 (API 29): retry deletion after recoverable auth
        if (pendingRecoverableIntentSender != null && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            clearPendingRecoverable()
            if (savedIds != null) {
                Logger.d(TAG, "Retrying deletion for API 29 after user authorization")
                deleteMediaByIds(savedIds)
            } else {
                refreshMediaLibrary()
            }
            return
        }

        if (pendingDeleteUris.isEmpty()) return

        val urisToDelete = pendingDeleteUris.toList()
        Logger.d(TAG, "Executing pending deletes for ${urisToDelete.size} items")

        // 清除待删除列表（系统已通过 createDeleteRequest 处理删除）
        clearPendingDeleteUris()

        // 清理 Room 数据库记录（通过 URI 查找对应的本地 ID）
        withContext(Dispatchers.IO) {
            val currentAssets = allMedia.first()
            val localIds = currentAssets
                .filter { asset -> asset.uri.toUri() in urisToDelete }
                .mapNotNull { asset -> if (asset.id != 0L) asset.id else null }

            if (localIds.isNotEmpty()) {
                Logger.d(TAG, "Cleaning up local DB records: $localIds")
                mediaDao.deleteMediaByIds(localIds)
            }

            // 刷新媒体库
            refreshMediaLibrary()
        }

        Logger.d(TAG, "Pending deletes execution completed")
    }

    override suspend fun insertMedia(mediaAsset: MediaAsset): Long {
        return mediaDao.insertMedia(mediaAsset.toEntity())
    }

    override suspend fun deleteMedia(mediaAsset: MediaAsset) {
        deleteMediaByIds(listOf(mediaAsset.id))
    }

    override suspend fun deleteMediaByIds(ids: List<Long>) {
        if (ids.isEmpty()) {
            return
        }

        val idSet = ids.toSet()
        Logger.d(TAG, "Starting deletion for IDs: $idSet")

        // 重要：在开始新的删除操作前，清空之前的待处理列表
        // 避免多次删除操作导致 URI 累积
        if (pendingDeleteUris.isNotEmpty()) {
            Logger.w(TAG, "Clearing ${pendingDeleteUris.size} pending URIs from previous operation")
            clearPendingDeleteUris()
        }
        clearPendingRecoverable()
        pendingDeleteIds = ids

        // 1. 获取待删除资产的 URI（优先从内存流，其次查库）
        val assetsToDelete = mutableListOf<MediaAsset>()

        // 尝试从当前流中获取
        val currentAssets = allMedia.first()
        assetsToDelete.addAll(currentAssets.filter { asset -> asset.id in idSet })

        // 补充查询：如果流中没找到（可能是刚插入的本地记录），直接查 Room
        val missingIds = idSet - assetsToDelete.map { it.id }.toSet()
        if (missingIds.isNotEmpty()) {
            val dbEntities = mediaDao.getMediaByIds(missingIds.toList())
            assetsToDelete.addAll(dbEntities.map { it.toDomain() })
        }

        // 2. 执行物理文件删除
        val successfullyDeletedIds = mutableListOf<Long>()
        var needsUserAuth = false
        for (asset in assetsToDelete) {
            // 跳过已不存在的文件（可能已被其他应用删除）
            val uri = asset.uri.toUri()
            val exists = runCatching {
                appContext.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() } ?: false
            }.getOrDefault(false)
            if (!exists) {
                Logger.w(TAG, "File no longer exists, skipping: ${asset.uri}")
                successfullyDeletedIds.add(asset.id)
                continue
            }

            Logger.d(TAG, "Attempting to delete file: ${asset.uri}")
            val deleted = deleteSystemMedia(asset.uri)

            if (deleted) {
                successfullyDeletedIds.add(asset.id)
            } else if (pendingRecoverableIntentSender != null) {
                // Android 10 (API 29): 需要逐条授权，停止处理后续资产
                needsUserAuth = true
                break
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: 收集所有需要授权的 URI，继续处理后续资产
                pendingDeleteUris.add(asset.uri.toUri())
                needsUserAuth = true
            }
        }

        // 3. 只有确认物理删除成功、或不需要额外权限时，才清理 Room 数据库记录
        // 如果需要用户授权，推迟数据库清理，避免用户拒绝后出现数据不一致
        if (!needsUserAuth) {
            pendingDeleteIds = null
            // id=0 才是未入库哨兵；负 id（如 sample 数据）是合法入库行，不能漏删。
            val localIds = successfullyDeletedIds.filter { id -> id != 0L }
            if (localIds.isNotEmpty()) {
                Logger.d(TAG, "Deleting local DB records: $localIds")
                mediaDao.deleteMediaByIds(localIds)
                // 级联清理人脸数据：删这些媒体的 embedding，再对齐 persons 表
                // （重算 faceCount、修/清悬空 coverMediaId、删空人物），避免人物页出现空白聚类。
                val personDao = AppDatabase.getDatabase(appContext).personDao()
                personDao.deleteEmbeddingsByMediaIds(localIds)
                personDao.reconcilePersons(System.currentTimeMillis())
            }
            // 清理 Coil 图片缓存，避免已删除文件的缩略图残留
            val imageLoader = coil.Coil.imageLoader(appContext)
            assetsToDelete
                .filter { asset -> asset.id in successfullyDeletedIds }
                .forEach { asset ->
                    imageLoader.diskCache?.remove(asset.uri)
                    imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(asset.uri))
                }
            refreshMediaLibrary()
        }

        Logger.d(TAG, "Deletion process completed. Pending auth: ${pendingDeleteUris.size}, Recoverable: ${pendingRecoverableIntentSender != null}")
    }

    override suspend fun getMediaById(id: Long): MediaAsset? {
        if (id != 0L) {
            return mediaDao.getMediaById(id)?.toDomain() ?: allMedia.first().firstOrNull { asset -> asset.id == id }
        }
        return allMedia.first().firstOrNull { asset -> asset.id == id }
    }

    override fun refreshLabels() {
        refreshVersion.value = refreshVersion.value + 1
    }

    override suspend fun refreshMediaLibrary() {
        withContext(Dispatchers.IO) {
            val systemMedia = loadSystemMedia()
            // 先更新缓存再同步 Room：避免 Room 变更触发的 Flow 发射
            // 在缓存更新前就执行 mergeMedia，产生中间态不一致数据
            systemMediaCache.value = systemMedia
            systemMediaLoaded = true
            syncSystemMediaToDb(systemMedia)
        }
        refreshVersion.value = refreshVersion.value + 1
    }

    /**
     * 将系统媒体库中的照片/视频同步到 Room 数据库。
     * 只插入新照片，不覆盖已有数据（保护 hasFace/faceId 等聚类结果）。
     * 同时清理 Room 中已不存在于系统媒体库的过期条目（解决脏数据引起的黑块问题）。
     *
     * @param systemMedia 已从 MediaStore 查询的系统媒体列表（由调用方传入避免重复查询）
     */
    private suspend fun syncSystemMediaToDb(systemMedia: List<MediaAsset>) {
        val dbMedia = mediaDao.getAllMediaNow()
        val dbUriSet = dbMedia.map { it.uri }.toSet()
        val systemUriSet = systemMedia.map { it.uri }.toHashSet()

        // 1. 插入新照片
        var inserted = 0
        for (asset in systemMedia) {
            if (asset.uri !in dbUriSet) {
                mediaDao.insertMedia(asset.toEntity())
                inserted++
            }
        }
        if (inserted > 0) {
            Logger.i(TAG, "Synced $inserted new media to database (total system: ${systemMedia.size})")
        }

        // 2. 清理过期条目：文件已被外部删除但 Room 中仍有记录
        //    这会导致列表中显示纯黑缩略图（脏数据问题）
        val staleEntries = dbMedia.filter { entity ->
            entity.uri.startsWith("content://media/") && entity.uri !in systemUriSet
        }
        if (staleEntries.isNotEmpty()) {
            val staleIds = mutableListOf<Long>()
            val imageLoader = coil.Coil.imageLoader(appContext)
            for (entry in staleEntries) {
                val uri = entry.uri.toUri()
                val exists = runCatching {
                    appContext.contentResolver.query(uri, null, null, null, null)
                        ?.use { it.moveToFirst() } ?: false
                }.getOrDefault(false)
                if (!exists) {
                    staleIds.add(entry.id)
                    // 同时清理 Coil 缓存，避免脏缩略图残留
                    imageLoader.diskCache?.remove(entry.uri)
                    imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(entry.uri))
                }
            }
            if (staleIds.isNotEmpty()) {
                mediaDao.deleteMediaByIds(staleIds)
                Logger.i(TAG, "Cleaned up ${staleIds.size} stale media entries (files no longer exist)")
            }
        }
    }

    private suspend fun loadSystemMedia(): List<MediaAsset> = withContext(Dispatchers.IO) {
        val media = mutableListOf<MediaAsset>()
        if (hasImageReadPermission()) {
            media += queryImagesFromMediaStore()
        }
        if (hasVideoReadPermission()) {
            media += queryVideosFromMediaStore()
        }
        media.sortedByDescending { asset -> asset.captureDate }
    }

    private fun queryImagesFromMediaStore(): List<MediaAsset> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE
        )
        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val latIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.LATITUDE)
                val lonIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.LONGITUDE)
                buildList {
                    while (cursor.moveToNext()) {
                        val mediaStoreId = cursor.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            mediaStoreId
                        )
                        val lat = if (cursor.isNull(latIndex)) null else cursor.getDouble(latIndex)
                        val lon = if (cursor.isNull(lonIndex)) null else cursor.getDouble(lonIndex)
                        add(
                            MediaAsset(
                                id = syntheticMediaId(mediaStoreId, MediaType.PHOTO),
                                uri = uri.toString(),
                                type = MediaType.PHOTO,
                                captureDate = resolveCaptureDate(
                                    dateTakenMs = cursor.getLong(dateTakenIndex),
                                    dateAddedSeconds = cursor.getLong(dateAddedIndex)
                                ),
                                fileName = cursor.getString(nameIndex) ?: uri.lastPathSegment.orEmpty(),
                                latitude = lat,
                                longitude = lon
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.onFailure { error ->
            Logger.e(TAG, "Failed to query system images", error)
        }.getOrDefault(emptyList())
    }

    private fun queryVideosFromMediaStore(): List<MediaAsset> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        )
        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                buildList {
                    while (cursor.moveToNext()) {
                        val mediaStoreId = cursor.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            mediaStoreId
                        )
                        add(
                            MediaAsset(
                                id = syntheticMediaId(mediaStoreId, MediaType.VIDEO),
                                uri = uri.toString(),
                                type = MediaType.VIDEO,
                                captureDate = resolveCaptureDate(
                                    dateTakenMs = cursor.getLong(dateTakenIndex),
                                    dateAddedSeconds = cursor.getLong(dateAddedIndex)
                                ),
                                fileName = cursor.getString(nameIndex) ?: uri.lastPathSegment.orEmpty(),
                                duration = cursor.getLong(durationIndex).takeIf { duration -> duration > 0L }
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.onFailure { error ->
            Logger.e(TAG, "Failed to query system videos", error)
        }.getOrDefault(emptyList())
    }

    private fun mergeMedia(dbMedia: List<MediaAsset>, systemMedia: List<MediaAsset>): List<MediaAsset> {
        val dbByUri = dbMedia.associateBy { asset -> asset.uri }
        val systemUris = systemMedia.map { asset -> asset.uri }.toHashSet()

        val mergedSystemMedia = systemMedia.map { systemAsset ->
            val dbAsset = dbByUri[systemAsset.uri] ?: return@map systemAsset
            systemAsset.copy(
                id = dbAsset.id,
                type = dbAsset.type,
                captureDate = if (systemAsset.captureDate > 0L) systemAsset.captureDate else dbAsset.captureDate,
                fileName = if (systemAsset.fileName.isNotBlank()) systemAsset.fileName else dbAsset.fileName,
                duration = systemAsset.duration ?: dbAsset.duration,
                hasFace = dbAsset.hasFace,
                faceId = dbAsset.faceId,
                source = dbAsset.source,
                labels = dbAsset.labels,
                ocrText = dbAsset.ocrText,
                latitude = dbAsset.latitude,
                longitude = dbAsset.longitude,
                locationName = dbAsset.locationName,
                city = dbAsset.city,
                indexedAt = dbAsset.indexedAt,
                faceFocusY = dbAsset.faceFocusY,
                // 美学/人脸画质分只存在于 DB（MediaStore 无此列），必须随 merge 透传，
                // 否则预览页信息弹窗的「美学评分/人脸质量」行对系统相册内照片永不显示
                aestheticScore = dbAsset.aestheticScore,
                faceQualityScore = dbAsset.faceQualityScore
            )
        }

        val localOnlyMedia = dbMedia.filterNot { asset -> asset.uri in systemUris }
        return (mergedSystemMedia + localOnlyMedia).sortedByDescending { asset -> asset.captureDate }
    }

    /**
     * 尝试删除系统媒体文件
     * @return true 如果删除成功，false 如果需要用户授权
     */
    private fun deleteSystemMedia(uriString: String): Boolean {
        val uri = uriString.toUri()
        if (uri.scheme != "content") {
            Logger.w(TAG, "Cannot delete non-content URI: $uriString")
            return false
        }

        return try {
            val deletedRows = appContext.contentResolver.delete(uri, null, null)
            if (deletedRows > 0) {
                Logger.d(TAG, "Successfully deleted media: $uriString")
                true
            } else {
                Logger.w(TAG, "Delete returned 0 rows for: $uriString")
                false
            }
        } catch (e: SecurityException) {
            // Android 10+ 需要用户授权才能删除非应用创建的文件
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = e as? RecoverableSecurityException
                if (recoverable != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    // Android 10 (API 29): 保存恢复性授权 IntentSender
                    pendingRecoverableIntentSender = recoverable.userAction.actionIntent.intentSender
                    Logger.w(TAG, "Recoverable security exception saved for API 29: $uriString")
                } else {
                    Logger.w(TAG, "Security exception, user authorization required for: $uriString")
                }
                false
            } else {
                Logger.e(TAG, "Failed to delete media: $uriString", e)
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete media: $uriString", e)
            false
        }
    }

    private fun hasImageReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasVideoReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveCaptureDate(dateTakenMs: Long, dateAddedSeconds: Long): Long {
        return when {
            dateTakenMs > 0L -> dateTakenMs
            dateAddedSeconds > 0L -> dateAddedSeconds * 1000L
            else -> 0L
        }
    }

    private fun syntheticMediaId(mediaStoreId: Long, mediaType: MediaType): Long {
        val typeSalt = when (mediaType) {
            MediaType.VIDEO -> 2L
            else -> 1L
        }
        return -((mediaStoreId * 10L) + typeSalt)
    }

    private fun MediaEntity.toDomain(): MediaAsset = MediaAsset(
        id = id,
        uri = uri,
        type = type,
        captureDate = captureDate,
        fileName = fileName,
        duration = duration,
        hasFace = hasFace,
        faceId = faceId,
        source = source,
        labels = labelsForLanguage(appLanguage),
        ocrText = ocrText,
        latitude = latitude,
        longitude = longitude,
        locationName = locationName,
        city = city,
        indexedAt = indexedAt,
        faceFocusY = faceFocusY,
        // 美学/人脸画质分必须透传：预览页信息弹窗展示依赖它（漏传会导致「美学评分」行永不显示）
        aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore
    )

    private fun MediaAsset.toEntity(): MediaEntity = MediaEntity(
        id = id,
        uri = uri,
        type = type,
        captureDate = captureDate,
        fileName = fileName,
        duration = duration,
        hasFace = hasFace,
        faceId = faceId,
        source = source,
        labels = labels,
        ocrText = ocrText,
        latitude = latitude,
        longitude = longitude,
        locationName = locationName,
        city = city,
        indexedAt = indexedAt,
        faceFocusY = faceFocusY,
        // 与 toDomain 对称，避免 entity→domain→entity 往返把已有分数回写成 null
        aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore
    )
}
